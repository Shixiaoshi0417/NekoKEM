#include "nekokem.h"

#include "aes.h"
#include "file.h"
#include "hybrid.h"
#include "kem.h"
#include "private_key.h"
#include "secure_mem.h"

#include <limits.h>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <openssl/x509.h>
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

static unsigned char *build_v1_aad(
    const unsigned char header[NKEM_HEADER_SIZE],
    const unsigned char *kem_ciphertext,
    size_t kem_ciphertext_len,
    const unsigned char nonce[NKEM_NONCE_SIZE],
    size_t *aad_len)
{
    unsigned char *aad;
    size_t length;

    if (kem_ciphertext_len >
        SIZE_MAX - NKEM_HEADER_SIZE - NKEM_NONCE_SIZE) {
        fprintf(stderr, "Authenticated metadata length overflows\n");
        return NULL;
    }
    length = NKEM_HEADER_SIZE + kem_ciphertext_len + NKEM_NONCE_SIZE;
    aad = OPENSSL_malloc(length);
    if (aad == NULL) {
        print_openssl_error("Cannot allocate authenticated metadata");
        return NULL;
    }
    memcpy(aad, header, NKEM_HEADER_SIZE);
    memcpy(aad + NKEM_HEADER_SIZE, kem_ciphertext, kem_ciphertext_len);
    memcpy(aad + NKEM_HEADER_SIZE + kem_ciphertext_len,
           nonce, NKEM_NONCE_SIZE);
    *aad_len = length;
    return aad;
}

static unsigned char *build_v2_aad(
    const unsigned char header[NKEM_V2_HEADER_SIZE],
    const unsigned char *ephemeral_public,
    size_t ephemeral_public_len,
    const unsigned char *kem_ciphertext,
    size_t kem_ciphertext_len,
    const unsigned char nonce[NKEM_NONCE_SIZE],
    size_t *aad_len)
{
    unsigned char *aad;
    size_t length = NKEM_V2_HEADER_SIZE;

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
    if (NKEM_NONCE_SIZE > SIZE_MAX - length) {
        fprintf(stderr, "Authenticated metadata length overflows\n");
        return NULL;
    }
    length += NKEM_NONCE_SIZE;

    aad = OPENSSL_malloc(length);
    if (aad == NULL) {
        print_openssl_error("Cannot allocate v2 authenticated metadata");
        return NULL;
    }
    memcpy(aad, header, NKEM_V2_HEADER_SIZE);
    memcpy(aad + NKEM_V2_HEADER_SIZE,
           ephemeral_public, ephemeral_public_len);
    memcpy(aad + NKEM_V2_HEADER_SIZE + ephemeral_public_len,
           kem_ciphertext, kem_ciphertext_len);
    memcpy(aad + NKEM_V2_HEADER_SIZE + ephemeral_public_len +
               kem_ciphertext_len,
           nonce, NKEM_NONCE_SIZE);
    *aad_len = length;
    return aad;
}

int nekokem_generate_v1_keypair(const char *public_key_path,
                                const char *private_key_path)
{
    if (!valid_path(public_key_path) || !valid_path(private_key_path)) {
        fprintf(stderr, "NekoKEM Core received an empty key path\n");
        return 0;
    }
    return kem_generate_keypair(public_key_path, private_key_path);
}

int nekokem_generate_keypair(const char *public_key_path,
                             const char *private_key_path,
                             const unsigned char *password,
                             size_t password_len)
{
    if (!valid_path(public_key_path) || !valid_path(private_key_path)) {
        fprintf(stderr, "NekoKEM Core received an empty key path\n");
        return 0;
    }
    if (password == NULL || password_len == 0U) {
        fprintf(stderr, "A non-empty private-key password is required\n");
        return 0;
    }
    return hybrid_generate_keypair(public_key_path, private_key_path,
                                   password, password_len);
}

int nekokem_encrypt_file_v1(const char *input_path,
                            const char *output_path,
                            const char *public_key_path)
{
    FILE *input = NULL;
    AtomicFile output = {0};
    EVP_PKEY *public_key = NULL;
    unsigned char *kem_ciphertext = NULL;
    unsigned char *shared_secret = NULL;
    unsigned char *aad = NULL;
    unsigned char header[NKEM_HEADER_SIZE];
    unsigned char nonce[NKEM_NONCE_SIZE];
    unsigned char tag[NKEM_TAG_SIZE];
    unsigned char aes_key[AES256_KEY_SIZE] = {0};
    size_t kem_ciphertext_len = 0U;
    size_t shared_secret_len = 0U;
    size_t aad_len = 0U;
    uint64_t plaintext_len = 0U;
    int success = 0;

    if (!validate_file_paths(input_path, output_path, public_key_path)) {
        goto cleanup;
    }
    input = fopen(input_path, "rb");
    if (input == NULL) {
        print_system_error("Cannot open plaintext");
        goto cleanup;
    }
    if (!file_disable_buffering(input) ||
        !file_get_size(input, &plaintext_len)) {
        goto cleanup;
    }
    public_key = kem_load_public_key(public_key_path);
    if (public_key == NULL ||
        !kem_encapsulate(public_key, &kem_ciphertext,
                         &kem_ciphertext_len, &shared_secret,
                         &shared_secret_len)) {
        goto cleanup;
    }
    EVP_PKEY_free(public_key);
    public_key = NULL;
    if (kem_ciphertext_len > UINT32_MAX) {
        fprintf(stderr, "ML-KEM ciphertext is too large for NKEM v1\n");
        goto cleanup;
    }
    if (RAND_bytes(nonce, (int)sizeof(nonce)) != 1) {
        print_openssl_error("Cannot generate AES-GCM nonce");
        goto cleanup;
    }
    if (!derive_aes256_key(shared_secret, shared_secret_len,
                           nonce, sizeof(nonce), aes_key)) {
        goto cleanup;
    }
    secure_free(shared_secret, shared_secret_len);
    shared_secret = NULL;
    shared_secret_len = 0U;

    nkem_header_encode(header, (uint32_t)kem_ciphertext_len,
                       plaintext_len);
    aad = build_v1_aad(header, kem_ciphertext, kem_ciphertext_len,
                       nonce, &aad_len);
    if (aad == NULL || !atomic_file_open(&output, output_path, 0600)) {
        goto cleanup;
    }
    if (!file_write_all(output.stream, header, sizeof(header)) ||
        !file_write_all(output.stream, kem_ciphertext,
                        kem_ciphertext_len) ||
        !file_write_all(output.stream, nonce, sizeof(nonce)) ||
        !aes_gcm_encrypt_file(input, output.stream, plaintext_len,
                              aes_key, nonce, aad, aad_len, tag) ||
        !file_write_all(output.stream, tag, sizeof(tag)) ||
        !atomic_file_commit(&output)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    atomic_file_abort(&output);
    if (input != NULL) {
        (void)fclose(input);
    }
    EVP_PKEY_free(public_key);
    OPENSSL_free(kem_ciphertext);
    secure_free(shared_secret, shared_secret_len);
    OPENSSL_free(aad);
    secure_mem_clear(aes_key, sizeof(aes_key));
    return success;
}

int nekokem_decrypt_file_v1(const char *input_path,
                            const char *output_path,
                            const char *private_key_path)
{
    FILE *input = NULL;
    AtomicFile output = {0};
    EVP_PKEY *private_key = NULL;
    unsigned char *kem_ciphertext = NULL;
    unsigned char *shared_secret = NULL;
    unsigned char *aad = NULL;
    unsigned char raw_header[NKEM_HEADER_SIZE];
    unsigned char nonce[NKEM_NONCE_SIZE];
    unsigned char aes_key[AES256_KEY_SIZE] = {0};
    NkemHeader header;
    size_t shared_secret_len = 0U;
    size_t aad_len = 0U;
    size_t kem_ciphertext_len = 0U;
    uint64_t container_size = 0U;
    int success = 0;

    if (!validate_file_paths(input_path, output_path, private_key_path)) {
        goto cleanup;
    }
    input = fopen(input_path, "rb");
    if (input == NULL) {
        print_system_error("Cannot open NKEM input");
        goto cleanup;
    }
    if (!file_disable_buffering(input) ||
        !file_get_size(input, &container_size) ||
        !file_read_exact(input, raw_header, sizeof(raw_header)) ||
        !nkem_header_decode(raw_header, &header) ||
        !nkem_container_size_is_valid(&header, container_size)) {
        goto cleanup;
    }

    kem_ciphertext_len = (size_t)header.kem_ciphertext_len;
    kem_ciphertext = OPENSSL_malloc(kem_ciphertext_len);
    if (kem_ciphertext == NULL) {
        print_openssl_error("Cannot allocate ML-KEM ciphertext buffer");
        goto cleanup;
    }
    if (!file_read_exact(input, kem_ciphertext, kem_ciphertext_len) ||
        !file_read_exact(input, nonce, sizeof(nonce))) {
        goto cleanup;
    }
    private_key = kem_load_private_key(private_key_path);
    if (private_key == NULL ||
        !kem_decapsulate(private_key, kem_ciphertext,
                         kem_ciphertext_len, &shared_secret,
                         &shared_secret_len)) {
        goto cleanup;
    }
    EVP_PKEY_free(private_key);
    private_key = NULL;
    if (!derive_aes256_key(shared_secret, shared_secret_len,
                           nonce, sizeof(nonce), aes_key)) {
        goto cleanup;
    }
    secure_free(shared_secret, shared_secret_len);
    shared_secret = NULL;
    shared_secret_len = 0U;
    aad = build_v1_aad(raw_header, kem_ciphertext, kem_ciphertext_len,
                       nonce, &aad_len);
    if (aad == NULL || !atomic_file_open(&output, output_path, 0600)) {
        goto cleanup;
    }
    if (!aes_gcm_decrypt_file(input, output.stream,
                              header.ciphertext_len, aes_key, nonce,
                              aad, aad_len) ||
        fgetc(input) != EOF || ferror(input) != 0) {
        if (ferror(input) != 0) {
            print_system_error("Cannot finish reading NKEM input");
        }
        goto cleanup;
    }
    if (!atomic_file_commit(&output)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    atomic_file_abort(&output);
    if (input != NULL) {
        (void)fclose(input);
    }
    EVP_PKEY_free(private_key);
    OPENSSL_free(kem_ciphertext);
    secure_free(shared_secret, shared_secret_len);
    OPENSSL_free(aad);
    secure_mem_clear(aes_key, sizeof(aes_key));
    return success;
}

int nekokem_encrypt_file_v2(const char *input_path,
                         const char *output_path,
                         const char *public_key_path)
{
    FILE *input = NULL;
    AtomicFile output = {0};
    HybridKeys public_keys = {0};
    unsigned char *x448_secret = NULL;
    unsigned char *kem_ciphertext = NULL;
    unsigned char *mlkem_secret = NULL;
    unsigned char *aad = NULL;
    unsigned char raw_header[NKEM_V2_HEADER_SIZE];
    unsigned char ephemeral_public[X448_PUBLIC_KEY_SIZE];
    unsigned char nonce[NKEM_NONCE_SIZE];
    unsigned char tag[NKEM_TAG_SIZE];
    unsigned char aes_key[AES256_KEY_SIZE] = {0};
    size_t x448_secret_len = 0U;
    size_t kem_ciphertext_len = 0U;
    size_t mlkem_secret_len = 0U;
    size_t aad_len = 0U;
    uint64_t plaintext_len = 0U;
    int success = 0;

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
        fprintf(stderr, "ML-KEM ciphertext is too large for NKEM v2\n");
        goto cleanup;
    }
    if (RAND_bytes(nonce, (int)sizeof(nonce)) != 1) {
        print_openssl_error("Cannot generate AES-GCM nonce");
        goto cleanup;
    }
    if (!hybrid_derive_aes256_key(
            x448_secret, x448_secret_len,
            mlkem_secret, mlkem_secret_len,
            nonce, sizeof(nonce), aes_key)) {
        goto cleanup;
    }
    secure_free(x448_secret, x448_secret_len);
    x448_secret = NULL;
    x448_secret_len = 0U;
    secure_free(mlkem_secret, mlkem_secret_len);
    mlkem_secret = NULL;
    mlkem_secret_len = 0U;

    nkem_v2_header_encode(
        raw_header, (uint16_t)sizeof(ephemeral_public),
        (uint32_t)kem_ciphertext_len, plaintext_len);
    aad = build_v2_aad(raw_header,
                       ephemeral_public, sizeof(ephemeral_public),
                       kem_ciphertext, kem_ciphertext_len,
                       nonce, &aad_len);
    if (aad == NULL || !atomic_file_open(&output, output_path, 0600)) {
        goto cleanup;
    }
    if (!file_write_all(output.stream, raw_header, sizeof(raw_header)) ||
        !file_write_all(output.stream, ephemeral_public,
                        sizeof(ephemeral_public)) ||
        !file_write_all(output.stream, kem_ciphertext,
                        kem_ciphertext_len) ||
        !file_write_all(output.stream, nonce, sizeof(nonce)) ||
        !aes_gcm_encrypt_file(input, output.stream, plaintext_len,
                              aes_key, nonce, aad, aad_len, tag) ||
        !file_write_all(output.stream, tag, sizeof(tag)) ||
        !atomic_file_commit(&output)) {
        goto cleanup;
    }
    success = 1;

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
    return success;
}

int nekokem_decrypt_file_v2(const char *input_path,
                         const char *output_path,
                         const char *private_key_path,
                         const unsigned char *password,
                         size_t password_len)
{
    FILE *input = NULL;
    AtomicFile output = {0};
    HybridKeys private_keys = {0};
    unsigned char *x448_secret = NULL;
    unsigned char *kem_ciphertext = NULL;
    unsigned char *mlkem_secret = NULL;
    unsigned char *aad = NULL;
    unsigned char raw_header[NKEM_V2_HEADER_SIZE];
    unsigned char ephemeral_public[X448_PUBLIC_KEY_SIZE];
    unsigned char nonce[NKEM_NONCE_SIZE];
    unsigned char aes_key[AES256_KEY_SIZE] = {0};
    NkemV2Header header;
    size_t x448_secret_len = 0U;
    size_t kem_ciphertext_len = 0U;
    size_t mlkem_secret_len = 0U;
    size_t aad_len = 0U;
    uint64_t container_size = 0U;
    int success = 0;

    if (!validate_file_paths(input_path, output_path, private_key_path)) {
        goto cleanup;
    }
    input = fopen(input_path, "rb");
    if (input == NULL) {
        print_system_error("Cannot open NKEM v2 input");
        goto cleanup;
    }
    if (!file_disable_buffering(input) ||
        !file_get_size(input, &container_size) ||
        !file_read_exact(input, raw_header, sizeof(raw_header)) ||
        !nkem_v2_header_decode(raw_header, &header) ||
        !nkem_v2_container_size_is_valid(&header, container_size)) {
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
            nonce, sizeof(nonce), aes_key)) {
        goto cleanup;
    }
    secure_free(x448_secret, x448_secret_len);
    x448_secret = NULL;
    x448_secret_len = 0U;
    secure_free(mlkem_secret, mlkem_secret_len);
    mlkem_secret = NULL;
    mlkem_secret_len = 0U;
    aad = build_v2_aad(raw_header,
                       ephemeral_public, sizeof(ephemeral_public),
                       kem_ciphertext, kem_ciphertext_len,
                       nonce, &aad_len);
    if (aad == NULL || !atomic_file_open(&output, output_path, 0600)) {
        goto cleanup;
    }
    if (!aes_gcm_decrypt_file(input, output.stream,
                              header.ciphertext_len, aes_key, nonce,
                              aad, aad_len) ||
        fgetc(input) != EOF || ferror(input) != 0) {
        if (ferror(input) != 0) {
            print_system_error("Cannot finish reading NKEM v2 input");
        }
        goto cleanup;
    }
    if (!atomic_file_commit(&output)) {
        goto cleanup;
    }
    success = 1;

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
    return success;
}

static int digest_public_key_component(EVP_MD_CTX *context,
                                       EVP_PKEY *key)
{
    unsigned char length_prefix[4];
    unsigned char *der = NULL;
    unsigned char *cursor;
    int encoded_len;
    int result_len;
    int success = 0;

    encoded_len = i2d_PUBKEY(key, NULL);
    if (encoded_len <= 0 || (uint64_t)encoded_len > UINT32_MAX) {
        print_openssl_error("Cannot query public-key DER length");
        goto cleanup;
    }
    der = OPENSSL_malloc((size_t)encoded_len);
    if (der == NULL) {
        print_openssl_error("Cannot allocate public-key DER buffer");
        goto cleanup;
    }
    cursor = der;
    result_len = i2d_PUBKEY(key, &cursor);
    if (result_len != encoded_len ||
        cursor != der + (size_t)encoded_len) {
        print_openssl_error("Cannot encode public key as DER");
        goto cleanup;
    }

    length_prefix[0] = (unsigned char)((uint32_t)encoded_len >> 24);
    length_prefix[1] = (unsigned char)((uint32_t)encoded_len >> 16);
    length_prefix[2] = (unsigned char)((uint32_t)encoded_len >> 8);
    length_prefix[3] = (unsigned char)(uint32_t)encoded_len;
    if (EVP_DigestUpdate(context, length_prefix,
                         sizeof(length_prefix)) != 1 ||
        EVP_DigestUpdate(context, der, (size_t)encoded_len) != 1) {
        print_openssl_error("Cannot update public-key fingerprint");
        goto cleanup;
    }
    success = 1;

cleanup:
    OPENSSL_free(der);
    return success;
}

int nekokem_public_key_fingerprint(const char *public_key_path,
                                   char *output,
                                   size_t output_size)
{
    static const unsigned char domain[] =
        "NekoKEM v2 hybrid public-key fingerprint";
    static const char hex[] = "0123456789ABCDEF";
    HybridKeys keys = {0};
    EVP_MD_CTX *context = NULL;
    unsigned char fingerprint[EVP_MAX_MD_SIZE] = {0};
    unsigned int fingerprint_len = 0U;
    size_t input_index;
    size_t output_index = 0U;
    int success = 0;

    if (!valid_path(public_key_path) || output == NULL ||
        output_size < NEKOKEM_FINGERPRINT_STRING_SIZE) {
        fprintf(stderr, "Invalid fingerprint output buffer or key path\n");
        goto cleanup;
    }
    output[0] = '\0';
    if (!hybrid_load_public_keys(public_key_path, &keys)) {
        goto cleanup;
    }
    context = EVP_MD_CTX_new();
    if (context == NULL) {
        print_openssl_error("Cannot create fingerprint context");
        goto cleanup;
    }
    if (EVP_DigestInit_ex(context, EVP_sha256(), NULL) != 1 ||
        EVP_DigestUpdate(context, domain, sizeof(domain) - 1U) != 1 ||
        !digest_public_key_component(context, keys.x448) ||
        !digest_public_key_component(context, keys.mlkem) ||
        EVP_DigestFinal_ex(context, fingerprint,
                           &fingerprint_len) != 1) {
        print_openssl_error("Cannot calculate public-key fingerprint");
        goto cleanup;
    }
    if (fingerprint_len != 32U) {
        fprintf(stderr, "Unexpected SHA-256 fingerprint length\n");
        goto cleanup;
    }

    for (input_index = 0U; input_index < fingerprint_len;
         ++input_index) {
        output[output_index++] =
            hex[(fingerprint[input_index] >> 4) & 0x0fU];
        output[output_index++] = hex[fingerprint[input_index] & 0x0fU];
        if (input_index + 1U < fingerprint_len) {
            output[output_index++] = ':';
        }
    }
    output[output_index] = '\0';
    success = 1;

cleanup:
    if (success == 0 && output != NULL && output_size > 0U) {
        output[0] = '\0';
    }
    secure_mem_clear(fingerprint, sizeof(fingerprint));
    EVP_MD_CTX_free(context);
    hybrid_keys_cleanup(&keys);
    return success;
}

int nekokem_private_key_requires_password(const char *private_key_path)
{
    if (!valid_path(private_key_path)) {
        return 0;
    }
    return private_key_path_is_encrypted(private_key_path);
}
