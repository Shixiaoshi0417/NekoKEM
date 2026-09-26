#include "nekokem.h"

#include "aes.h"
#include "file.h"
#include "hybrid.h"
#include "kem.h"
#include "private_key.h"
#include "secure_mem.h"

#include <limits.h>
#include <openssl/rand.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static int valid_path(const char *path)
{
    return path != NULL && path[0] != '\0';
}

static int validate_file_paths(const char *input_path,
                               const char *output_path,
                               const char *key_path)
{
    if (!valid_path(input_path) || !valid_path(output_path) ||
        !valid_path(key_path)) {
        fprintf(stderr, "NekoKEM Core received an empty file path\n");
        return 0;
    }
    return 1;
}

static unsigned char *build_v3_aad(
    const unsigned char header[NKEM_V3_HEADER_SIZE],
    const unsigned char *ephemeral_public,
    size_t ephemeral_public_len,
    const unsigned char *kem_ciphertext,
    size_t kem_ciphertext_len,
    const unsigned char salt[NKEM_V3_SALT_SIZE],
    const unsigned char nonce[NKEM_NONCE_SIZE],
    size_t *aad_len)
{
    unsigned char *aad;
    size_t length = NKEM_V3_HEADER_SIZE;
    size_t offset;

    if (ephemeral_public_len > SIZE_MAX - length) {
        fprintf(stderr, "Authenticated metadata length overflows\n");
        return NULL;
    }
    length += ephemeral_public_len;
    if (kem_ciphertext_len > SIZE_MAX - length) {
        fprintf(stderr, "Authenticated metadata length overflows\n");
        return NULL;
    }
    length += kem_ciphertext_len;
    if (NKEM_V3_SALT_SIZE > SIZE_MAX - length) {
        fprintf(stderr, "Authenticated metadata length overflows\n");
        return NULL;
    }
    length += NKEM_V3_SALT_SIZE;
    if (NKEM_NONCE_SIZE > SIZE_MAX - length) {
        fprintf(stderr, "Authenticated metadata length overflows\n");
        return NULL;
    }
    length += NKEM_NONCE_SIZE;

    aad = OPENSSL_malloc(length);
    if (aad == NULL) {
        print_openssl_error("Cannot allocate v3 authenticated metadata");
        return NULL;
    }
    offset = 0U;
    memcpy(aad + offset, header, NKEM_V3_HEADER_SIZE);
    offset += NKEM_V3_HEADER_SIZE;
    memcpy(aad + offset, ephemeral_public, ephemeral_public_len);
    offset += ephemeral_public_len;
    memcpy(aad + offset, kem_ciphertext, kem_ciphertext_len);
    offset += kem_ciphertext_len;
    memcpy(aad + offset, salt, NKEM_V3_SALT_SIZE);
    offset += NKEM_V3_SALT_SIZE;
    memcpy(aad + offset, nonce, NKEM_NONCE_SIZE);
    *aad_len = length;
    return aad;
}

int nekokem_encrypt_file_with_progress(
    const char *input_path,
    const char *output_path,
    const char *public_key_path,
    NekoKEMProgressCallback progress_callback,
    void *progress_user_data)
{
    FILE *input = NULL;
    AtomicFile output = {0};
    HybridKeys public_keys = {0};
    unsigned char *x448_secret = NULL;
    unsigned char *kem_ciphertext = NULL;
    unsigned char *mlkem_secret = NULL;
    unsigned char *aad = NULL;
    unsigned char raw_header[NKEM_V3_HEADER_SIZE];
    unsigned char ephemeral_public[X448_PUBLIC_KEY_SIZE];
    unsigned char salt[NKEM_V3_SALT_SIZE];
    unsigned char nonce[NKEM_NONCE_SIZE];
    unsigned char tag[NKEM_TAG_SIZE];
    unsigned char aes_key[AES256_KEY_SIZE] = {0};
    size_t x448_secret_len = 0U;
    size_t kem_ciphertext_len = 0U;
    size_t mlkem_secret_len = 0U;
    size_t aad_len = 0U;
    uint64_t plaintext_len = 0U;
    int aes_result;
    int result = NEKOKEM_OPERATION_ERROR;

    if (!validate_file_paths(input_path, output_path, public_key_path)) {
        goto cleanup;
    }
    input = fopen(input_path, "rb");
    if (input == NULL) {
        print_system_error("Cannot open plaintext");
        goto cleanup;
    }
    if (!file_disable_buffering(input) ||
        !file_get_size(input, &plaintext_len) ||
        !nkem_gcm_data_size_is_valid(plaintext_len) ||
        !hybrid_load_public_keys(public_key_path, &public_keys) ||
        !hybrid_x448_encapsulate(public_keys.x448, ephemeral_public,
                                 &x448_secret, &x448_secret_len) ||
        !kem_encapsulate(public_keys.mlkem, &kem_ciphertext,
                         &kem_ciphertext_len, &mlkem_secret,
                         &mlkem_secret_len)) {
        goto cleanup;
    }
    hybrid_keys_cleanup(&public_keys);
    if (kem_ciphertext_len > UINT32_MAX) {
        fprintf(stderr, "ML-KEM ciphertext is too large for NKEM v3\n");
        goto cleanup;
    }
    if (RAND_bytes(salt, (int)sizeof(salt)) != 1 ||
        RAND_bytes(nonce, (int)sizeof(nonce)) != 1) {
        print_openssl_error("Cannot generate HKDF salt or AES-GCM nonce");
        goto cleanup;
    }
    if (!hybrid_derive_aes256_key(
            x448_secret, x448_secret_len,
            mlkem_secret, mlkem_secret_len,
            salt, sizeof(salt), aes_key)) {
        goto cleanup;
    }
    secure_free(x448_secret, x448_secret_len);
    x448_secret = NULL;
    x448_secret_len = 0U;
    secure_free(mlkem_secret, mlkem_secret_len);
    mlkem_secret = NULL;
    mlkem_secret_len = 0U;

    nkem_v3_header_encode(
        raw_header, (uint16_t)sizeof(ephemeral_public),
        (uint32_t)kem_ciphertext_len, plaintext_len);
    aad = build_v3_aad(raw_header,
                       ephemeral_public, sizeof(ephemeral_public),
                       kem_ciphertext, kem_ciphertext_len,
                       salt, nonce, &aad_len);
    if (aad == NULL || !atomic_file_open(&output, output_path, 0600)) {
        goto cleanup;
    }
    if (!file_write_all(output.stream, raw_header, sizeof(raw_header)) ||
        !file_write_all(output.stream, ephemeral_public,
                        sizeof(ephemeral_public)) ||
        !file_write_all(output.stream, kem_ciphertext,
                        kem_ciphertext_len) ||
        !file_write_all(output.stream, salt, sizeof(salt)) ||
        !file_write_all(output.stream, nonce, sizeof(nonce))) {
        goto cleanup;
    }
    aes_result = aes_gcm_encrypt_file_with_progress(
        input, output.stream, plaintext_len,
        aes_key, nonce, aad, aad_len, tag,
        progress_callback, progress_user_data);
    if (aes_result == AES_GCM_FILE_CANCELLED) {
        result = NEKOKEM_OPERATION_CANCELLED;
        goto cleanup;
    }
    if (aes_result != AES_GCM_FILE_SUCCESS ||
        !file_write_all(output.stream, tag, sizeof(tag)) ||
        !atomic_file_commit(&output)) {
        goto cleanup;
    }
    result = NEKOKEM_OPERATION_SUCCESS;

cleanup:
    atomic_file_abort(&output);
    if (input != NULL) {
        (void)fclose(input);
    }
    hybrid_keys_cleanup(&public_keys);
    secure_free(x448_secret, x448_secret_len);
    OPENSSL_free(kem_ciphertext);
    secure_free(mlkem_secret, mlkem_secret_len);
    OPENSSL_free(aad);
    secure_mem_clear(aes_key, sizeof(aes_key));
    return result;
}

int nekokem_encrypt_file(const char *input_path,
                         const char *output_path,
                         const char *public_key_path)
{
    return nekokem_encrypt_file_with_progress(
               input_path, output_path, public_key_path,
               NULL, NULL) == NEKOKEM_OPERATION_SUCCESS;
}

static int decrypt_file_v3_with_progress(
    const char *input_path,
    const char *output_path,
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len,
    NekoKEMProgressCallback progress_callback,
    void *progress_user_data)
{
    FILE *input = NULL;
    AtomicFile output = {0};
    HybridKeys private_keys = {0};
    unsigned char *x448_secret = NULL;
    unsigned char *kem_ciphertext = NULL;
    unsigned char *mlkem_secret = NULL;
    unsigned char *aad = NULL;
    unsigned char raw_header[NKEM_V3_HEADER_SIZE];
    unsigned char ephemeral_public[X448_PUBLIC_KEY_SIZE];
    unsigned char salt[NKEM_V3_SALT_SIZE];
    unsigned char nonce[NKEM_NONCE_SIZE];
    unsigned char aes_key[AES256_KEY_SIZE] = {0};
    NkemV3Header header;
    size_t x448_secret_len = 0U;
    size_t kem_ciphertext_len = 0U;
    size_t mlkem_secret_len = 0U;
    size_t aad_len = 0U;
    uint64_t container_size = 0U;
    int aes_result;
    int result = NEKOKEM_OPERATION_ERROR;

    if (!validate_file_paths(input_path, output_path, private_key_path)) {
        goto cleanup;
    }
    input = fopen(input_path, "rb");
    if (input == NULL) {
        print_system_error("Cannot open NKEM v3 input");
        goto cleanup;
    }
    if (!file_disable_buffering(input) ||
        !file_get_size(input, &container_size) ||
        !file_read_exact(input, raw_header, sizeof(raw_header)) ||
        !nkem_v3_header_decode(raw_header, &header) ||
        !nkem_v3_container_size_is_valid(&header, container_size)) {
        goto cleanup;
    }

    kem_ciphertext_len = (size_t)header.kem_ciphertext_len;
    kem_ciphertext = OPENSSL_malloc(kem_ciphertext_len);
    if (kem_ciphertext == NULL) {
        print_openssl_error("Cannot allocate ML-KEM ciphertext buffer");
        goto cleanup;
    }
    if (!file_read_exact(input, ephemeral_public,
                         sizeof(ephemeral_public)) ||
        !file_read_exact(input, kem_ciphertext, kem_ciphertext_len) ||
        !file_read_exact(input, salt, sizeof(salt)) ||
        !file_read_exact(input, nonce, sizeof(nonce))) {
        goto cleanup;
    }
    if (private_key_path_is_encrypted(private_key_path)) {
        if (password == NULL || password_len == 0U) {
            fprintf(stderr, "A password is required for this private key\n");
            goto cleanup;
        }
        if (!hybrid_load_protected_private_keys(
                private_key_path, password, password_len,
                &private_keys)) {
            goto cleanup;
        }
    } else if (!hybrid_load_private_keys(private_key_path,
                                         &private_keys)) {
        goto cleanup;
    }
    if (!hybrid_x448_decapsulate(
            private_keys.x448, ephemeral_public,
            (size_t)header.x448_ephemeral_len,
            &x448_secret, &x448_secret_len) ||
        !kem_decapsulate(private_keys.mlkem, kem_ciphertext,
                         kem_ciphertext_len, &mlkem_secret,
                         &mlkem_secret_len)) {
        goto cleanup;
    }
    hybrid_keys_cleanup(&private_keys);
    if (!hybrid_derive_aes256_key(
            x448_secret, x448_secret_len,
            mlkem_secret, mlkem_secret_len,
            salt, sizeof(salt), aes_key)) {
        goto cleanup;
    }
    secure_free(x448_secret, x448_secret_len);
    x448_secret = NULL;
    x448_secret_len = 0U;
    secure_free(mlkem_secret, mlkem_secret_len);
    mlkem_secret = NULL;
    mlkem_secret_len = 0U;
    aad = build_v3_aad(raw_header,
                       ephemeral_public, sizeof(ephemeral_public),
                       kem_ciphertext, kem_ciphertext_len,
                       salt, nonce, &aad_len);
    if (aad == NULL || !atomic_file_open(&output, output_path, 0600)) {
        goto cleanup;
    }
    aes_result = aes_gcm_decrypt_file_with_progress(
        input, output.stream, header.ciphertext_len,
        aes_key, nonce, aad, aad_len,
        progress_callback, progress_user_data);
    if (aes_result == AES_GCM_FILE_CANCELLED) {
        result = NEKOKEM_OPERATION_CANCELLED;
        goto cleanup;
    }
    if (aes_result != AES_GCM_FILE_SUCCESS ||
        fgetc(input) != EOF || ferror(input) != 0) {
        if (ferror(input) != 0) {
            print_system_error("Cannot finish reading NKEM v3 input");
        }
        goto cleanup;
    }
    if (!atomic_file_commit(&output)) {
        goto cleanup;
    }
    result = NEKOKEM_OPERATION_SUCCESS;

cleanup:
    atomic_file_abort(&output);
    if (input != NULL) {
        (void)fclose(input);
    }
    hybrid_keys_cleanup(&private_keys);
    secure_free(x448_secret, x448_secret_len);
    OPENSSL_free(kem_ciphertext);
    secure_free(mlkem_secret, mlkem_secret_len);
    OPENSSL_free(aad);
    secure_mem_clear(aes_key, sizeof(aes_key));
    return result;
}

int nekokem_decrypt_file_with_progress(
    const char *input_path,
    const char *output_path,
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len,
    NekoKEMProgressCallback progress_callback,
    void *progress_user_data)
{
    if (!validate_file_paths(input_path, output_path, private_key_path)) {
        return NEKOKEM_OPERATION_ERROR;
    }
    return decrypt_file_v3_with_progress(
        input_path, output_path, private_key_path,
        password, password_len,
        progress_callback, progress_user_data);
}

int nekokem_decrypt_file(const char *input_path,
                         const char *output_path,
                         const char *private_key_path,
                         const unsigned char *password,
                         size_t password_len)
{
    return nekokem_decrypt_file_with_progress(
               input_path, output_path, private_key_path,
               password, password_len, NULL, NULL) ==
           NEKOKEM_OPERATION_SUCCESS;
}
