/* NKEM v4 multi-recipient container; the format is specified in docs/NKEM-v4.md. */
#include "nekokem_v4.h"

#include "aes.h"
#include "hybrid.h"
#include "kem.h"
#include "secure_mem.h"

#include <openssl/core_names.h>
#include <openssl/crypto.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/params.h>
#include <openssl/rand.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

/* header || salt: the prefix shared by every recipient entry's wrap AAD. */
#define V4_PREFIX_SIZE (NKEM_V4_HEADER_SIZE + NKEM_V4_SALT_SIZE)
#define V4_KEM_OFFSET NKEM_X448_EPHEMERAL_PUBLIC_SIZE
#define V4_WRAPPED_OFFSET (V4_KEM_OFFSET + NKEM_V3_KEM_CIPHERTEXT_SIZE)
#define V4_WRAP_TAG_OFFSET (V4_WRAPPED_OFFSET + NKEM_V4_FILE_KEY_SIZE)
/* header || salt || X448 ephemeral public key || ML-KEM ciphertext */
#define V4_WRAP_AAD_SIZE (V4_PREFIX_SIZE + V4_WRAPPED_OFFSET)
#define V4_MAC_KEY_SIZE 64U

_Static_assert(V4_WRAP_TAG_OFFSET + NKEM_TAG_SIZE == NKEM_V4_ENTRY_SIZE,
               "Recipient entry layout");
_Static_assert(KEM_CIPHERTEXT_SIZE == NKEM_V3_KEM_CIPHERTEXT_SIZE &&
               X448_PUBLIC_KEY_SIZE == NKEM_X448_EPHEMERAL_PUBLIC_SIZE &&
               AES256_KEY_SIZE == NKEM_V4_FILE_KEY_SIZE,
               "Recipient entry component sizes");

static const char wrap_label[] = "NekoKEM v4 recipient wrap X448-MLKEM1024";
static const char payload_label[] = "NekoKEM v4 payload AES-256-GCM";
static const char mac_label[] = "NekoKEM v4 header HMAC-SHA512";
/* Each wrap key encrypts exactly one message, so a fixed nonce never repeats. */
static const unsigned char wrap_nonce[NKEM_NONCE_SIZE] = {0};

static int valid_path(const char *path)
{
    return path != NULL && path[0] != '\0';
}

static int hkdf_sha512(const unsigned char *key,
                       size_t key_len,
                       const unsigned char salt[NKEM_V4_SALT_SIZE],
                       const unsigned char *info,
                       size_t info_len,
                       unsigned char *output,
                       size_t output_len)
{
    static char digest_name[] = "SHA512";
    EVP_KDF *algorithm = NULL;
    EVP_KDF_CTX *context = NULL;
    OSSL_PARAM parameters[5];
    int success = 0;

    algorithm = EVP_KDF_fetch(NULL, "HKDF", NULL);
    if (algorithm != NULL) {
        context = EVP_KDF_CTX_new(algorithm);
    }
    if (context == NULL) {
        print_openssl_error("NKEM v4 key derivation failed");
        goto cleanup;
    }
    parameters[0] = OSSL_PARAM_construct_utf8_string(
        OSSL_KDF_PARAM_DIGEST, digest_name, 0U);
    parameters[1] = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_KEY, (void *)key, key_len);
    parameters[2] = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_SALT, (void *)salt, NKEM_V4_SALT_SIZE);
    parameters[3] = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_INFO, (void *)info, info_len);
    parameters[4] = OSSL_PARAM_construct_end();
    if (EVP_KDF_derive(context, output, output_len, parameters) <= 0) {
        print_openssl_error("NKEM v4 key derivation failed");
        goto cleanup;
    }
    success = 1;

cleanup:
    if (success == 0) {
        secure_mem_clear(output, output_len);
    }
    EVP_KDF_CTX_free(context);
    EVP_KDF_free(algorithm);
    return success;
}

/* The wrap key binds the X448 exchange it came from, as in X-Wing. */
static int derive_wrap_key(
    const unsigned char *x448_secret,
    size_t x448_secret_len,
    const unsigned char *mlkem_secret,
    size_t mlkem_secret_len,
    const unsigned char salt[NKEM_V4_SALT_SIZE],
    const unsigned char ephemeral_public[X448_PUBLIC_KEY_SIZE],
    const unsigned char recipient_public[X448_PUBLIC_KEY_SIZE],
    unsigned char wrap_key[AES256_KEY_SIZE])
{
    unsigned char secret[X448_SHARED_SECRET_SIZE + KEM_SHARED_SECRET_SIZE];
    unsigned char info[sizeof(wrap_label) - 1U + 2U * X448_PUBLIC_KEY_SIZE];
    size_t label_len = sizeof(wrap_label) - 1U;
    int success;

    if (x448_secret == NULL || mlkem_secret == NULL ||
        x448_secret_len != X448_SHARED_SECRET_SIZE ||
        mlkem_secret_len != KEM_SHARED_SECRET_SIZE) {
        fprintf(stderr, file_message("Invalid hybrid key-derivation input lengths\n"));
        return 0;
    }
    memcpy(secret, x448_secret, X448_SHARED_SECRET_SIZE);
    memcpy(secret + X448_SHARED_SECRET_SIZE, mlkem_secret,
           KEM_SHARED_SECRET_SIZE);
    memcpy(info, wrap_label, label_len);
    memcpy(info + label_len, ephemeral_public, X448_PUBLIC_KEY_SIZE);
    memcpy(info + label_len + X448_PUBLIC_KEY_SIZE, recipient_public,
           X448_PUBLIC_KEY_SIZE);
    success = hkdf_sha512(secret, sizeof(secret), salt, info, sizeof(info),
                          wrap_key, AES256_KEY_SIZE);
    secure_mem_clear(secret, sizeof(secret));
    return success;
}

static int derive_file_keys(const unsigned char file_key[NKEM_V4_FILE_KEY_SIZE],
                            const unsigned char salt[NKEM_V4_SALT_SIZE],
                            unsigned char payload_key[AES256_KEY_SIZE],
                            unsigned char mac_key[V4_MAC_KEY_SIZE])
{
    return hkdf_sha512(file_key, NKEM_V4_FILE_KEY_SIZE, salt,
                       (const unsigned char *)payload_label,
                       sizeof(payload_label) - 1U,
                       payload_key, AES256_KEY_SIZE) &&
           hkdf_sha512(file_key, NKEM_V4_FILE_KEY_SIZE, salt,
                       (const unsigned char *)mac_label,
                       sizeof(mac_label) - 1U,
                       mac_key, V4_MAC_KEY_SIZE);
}

/* HMAC-SHA512 commits to its key, unlike AES-GCM; see docs/NKEM-v4.md. */
static int header_mac(const unsigned char mac_key[V4_MAC_KEY_SIZE],
                      const unsigned char *data,
                      size_t data_len,
                      unsigned char output[NKEM_V4_MAC_SIZE])
{
    static char digest_name[] = "SHA512";
    EVP_MAC *algorithm = NULL;
    EVP_MAC_CTX *context = NULL;
    OSSL_PARAM parameters[2];
    size_t output_len = 0U;
    int success = 0;

    algorithm = EVP_MAC_fetch(NULL, "HMAC", NULL);
    if (algorithm != NULL) {
        context = EVP_MAC_CTX_new(algorithm);
    }
    parameters[0] = OSSL_PARAM_construct_utf8_string(
        OSSL_MAC_PARAM_DIGEST, digest_name, 0U);
    parameters[1] = OSSL_PARAM_construct_end();
    if (context == NULL ||
        EVP_MAC_init(context, mac_key, V4_MAC_KEY_SIZE, parameters) != 1 ||
        EVP_MAC_update(context, data, data_len) != 1 ||
        EVP_MAC_final(context, output, &output_len,
                      NKEM_V4_MAC_SIZE) != 1 ||
        output_len != NKEM_V4_MAC_SIZE) {
        print_openssl_error("NKEM v4 header MAC failed");
        goto cleanup;
    }
    success = 1;

cleanup:
    if (success == 0) {
        secure_mem_clear(output, NKEM_V4_MAC_SIZE);
    }
    EVP_MAC_CTX_free(context);
    EVP_MAC_free(algorithm);
    return success;
}

static int x448_raw_public(EVP_PKEY *key,
                           unsigned char output[X448_PUBLIC_KEY_SIZE])
{
    size_t length = X448_PUBLIC_KEY_SIZE;

    if (key == NULL || EVP_PKEY_is_a(key, X448_ALGORITHM_NAME) != 1 ||
        EVP_PKEY_get_raw_public_key(key, output, &length) != 1 ||
        length != X448_PUBLIC_KEY_SIZE) {
        print_openssl_error("Cannot read the recipient X448 public key");
        return 0;
    }
    return 1;
}

static int wrap_file_key(const unsigned char wrap_key[AES256_KEY_SIZE],
                         const unsigned char aad[V4_WRAP_AAD_SIZE],
                         const unsigned char file_key[NKEM_V4_FILE_KEY_SIZE],
                         unsigned char wrapped[NKEM_V4_FILE_KEY_SIZE],
                         unsigned char tag[NKEM_TAG_SIZE])
{
    EVP_CIPHER_CTX *context = EVP_CIPHER_CTX_new();
    unsigned char final_block[EVP_MAX_BLOCK_LENGTH];
    int length = 0;
    int final_len = 0;
    int success = 0;

    if (context == NULL ||
        EVP_EncryptInit_ex(context, EVP_aes_256_gcm(), NULL,
                           NULL, NULL) <= 0 ||
        EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_SET_IVLEN,
                            (int)NKEM_NONCE_SIZE, NULL) <= 0 ||
        EVP_EncryptInit_ex(context, NULL, NULL, wrap_key, wrap_nonce) <= 0 ||
        EVP_EncryptUpdate(context, NULL, &length, aad,
                          (int)V4_WRAP_AAD_SIZE) <= 0 ||
        EVP_EncryptUpdate(context, wrapped, &length, file_key,
                          (int)NKEM_V4_FILE_KEY_SIZE) <= 0 ||
        length != (int)NKEM_V4_FILE_KEY_SIZE ||
        EVP_EncryptFinal_ex(context, final_block, &final_len) <= 0 ||
        final_len != 0 ||
        EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_GET_TAG,
                            (int)NKEM_TAG_SIZE, tag) <= 0) {
        print_openssl_error("NKEM v4 file-key wrapping failed");
        goto cleanup;
    }
    success = 1;

cleanup:
    EVP_CIPHER_CTX_free(context);
    return success;
}

/*
 * Returns 1 when the entry unwraps under this wrap key, 0 when its tag does
 * not verify (another recipient's entry) and -1 on an OpenSSL error. A
 * non-matching entry is expected during trial decryption and stays silent.
 */
static int unwrap_file_key(const unsigned char wrap_key[AES256_KEY_SIZE],
                           const unsigned char aad[V4_WRAP_AAD_SIZE],
                           const unsigned char wrapped[NKEM_V4_FILE_KEY_SIZE],
                           const unsigned char tag[NKEM_TAG_SIZE],
                           unsigned char file_key[NKEM_V4_FILE_KEY_SIZE])
{
    EVP_CIPHER_CTX *context = EVP_CIPHER_CTX_new();
    unsigned char expected_tag[NKEM_TAG_SIZE];
    unsigned char final_block[EVP_MAX_BLOCK_LENGTH];
    int length = 0;
    int result = -1;

    memcpy(expected_tag, tag, sizeof(expected_tag));
    if (context == NULL ||
        EVP_DecryptInit_ex(context, EVP_aes_256_gcm(), NULL,
                           NULL, NULL) <= 0 ||
        EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_SET_IVLEN,
                            (int)NKEM_NONCE_SIZE, NULL) <= 0 ||
        EVP_DecryptInit_ex(context, NULL, NULL, wrap_key, wrap_nonce) <= 0 ||
        EVP_DecryptUpdate(context, NULL, &length, aad,
                          (int)V4_WRAP_AAD_SIZE) <= 0 ||
        EVP_DecryptUpdate(context, file_key, &length, wrapped,
                          (int)NKEM_V4_FILE_KEY_SIZE) <= 0 ||
        length != (int)NKEM_V4_FILE_KEY_SIZE ||
        EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_SET_TAG,
                            (int)sizeof(expected_tag), expected_tag) <= 0) {
        print_openssl_error("NKEM v4 file-key wrapping failed");
        goto cleanup;
    }
    (void)ERR_set_mark();
    result = EVP_DecryptFinal_ex(context, final_block, &length) > 0 &&
                     length == 0
                 ? 1
                 : 0;
    (void)ERR_pop_to_mark();

cleanup:
    if (result != 1) {
        secure_mem_clear(file_key, NKEM_V4_FILE_KEY_SIZE);
    }
    secure_mem_clear(final_block, sizeof(final_block));
    EVP_CIPHER_CTX_free(context);
    return result;
}

/* Fills one recipient entry: X448 + ML-KEM encapsulation and the wrapped file key. */
static int seal_recipient(const HybridKeys *recipient,
                          const unsigned char prefix[V4_PREFIX_SIZE],
                          const unsigned char file_key[NKEM_V4_FILE_KEY_SIZE],
                          unsigned char entry[NKEM_V4_ENTRY_SIZE])
{
    unsigned char *x448_secret = NULL;
    unsigned char *kem_ciphertext = NULL;
    unsigned char *mlkem_secret = NULL;
    unsigned char recipient_public[X448_PUBLIC_KEY_SIZE];
    unsigned char wrap_key[AES256_KEY_SIZE] = {0};
    unsigned char wrap_aad[V4_WRAP_AAD_SIZE];
    size_t x448_secret_len = 0U;
    size_t kem_ciphertext_len = 0U;
    size_t mlkem_secret_len = 0U;
    int success = 0;

    if (!x448_raw_public(recipient->x448, recipient_public) ||
        !hybrid_x448_encapsulate(recipient->x448, entry,
                                 &x448_secret, &x448_secret_len) ||
        !kem_encapsulate(recipient->mlkem, &kem_ciphertext,
                         &kem_ciphertext_len, &mlkem_secret,
                         &mlkem_secret_len)) {
        goto cleanup;
    }
    /* kem_encapsulate() accepts only the fixed ML-KEM-1024 ciphertext size. */
    memcpy(entry + V4_KEM_OFFSET, kem_ciphertext, NKEM_V3_KEM_CIPHERTEXT_SIZE);
    memcpy(wrap_aad, prefix, V4_PREFIX_SIZE);
    memcpy(wrap_aad + V4_PREFIX_SIZE, entry, V4_WRAPPED_OFFSET);
    if (!derive_wrap_key(x448_secret, x448_secret_len,
                         mlkem_secret, mlkem_secret_len,
                         prefix + NKEM_V4_HEADER_SIZE, entry,
                         recipient_public, wrap_key) ||
        !wrap_file_key(wrap_key, wrap_aad, file_key,
                       entry + V4_WRAPPED_OFFSET,
                       entry + V4_WRAP_TAG_OFFSET)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    secure_free(x448_secret, x448_secret_len);
    OPENSSL_free(kem_ciphertext);
    secure_free(mlkem_secret, mlkem_secret_len);
    secure_mem_clear(wrap_key, sizeof(wrap_key));
    return success;
}

/* Returns 1 for this key's entry, 0 for another recipient's and -1 on error. */
static int open_recipient(const HybridKeys *keys,
                          const unsigned char recipient_public[X448_PUBLIC_KEY_SIZE],
                          const unsigned char prefix[V4_PREFIX_SIZE],
                          const unsigned char entry[NKEM_V4_ENTRY_SIZE],
                          unsigned char file_key[NKEM_V4_FILE_KEY_SIZE])
{
    unsigned char *x448_secret = NULL;
    unsigned char *mlkem_secret = NULL;
    unsigned char wrap_key[AES256_KEY_SIZE] = {0};
    unsigned char wrap_aad[V4_WRAP_AAD_SIZE];
    size_t x448_secret_len = 0U;
    size_t mlkem_secret_len = 0U;
    int result = -1;

    /* ML-KEM rejects implicitly: another recipient's ciphertext yields an
     * unrelated secret and fails only at the wrap tag. An X448 key with an
     * all-zero shared secret fails here; no correct encryptor writes one. */
    if (!hybrid_x448_decapsulate(keys->x448, entry,
                                 X448_PUBLIC_KEY_SIZE,
                                 &x448_secret, &x448_secret_len) ||
        !kem_decapsulate(keys->mlkem, entry + V4_KEM_OFFSET,
                         NKEM_V3_KEM_CIPHERTEXT_SIZE,
                         &mlkem_secret, &mlkem_secret_len) ||
        !derive_wrap_key(x448_secret, x448_secret_len,
                         mlkem_secret, mlkem_secret_len,
                         prefix + NKEM_V4_HEADER_SIZE, entry,
                         recipient_public, wrap_key)) {
        goto cleanup;
    }
    memcpy(wrap_aad, prefix, V4_PREFIX_SIZE);
    memcpy(wrap_aad + V4_PREFIX_SIZE, entry, V4_WRAPPED_OFFSET);
    result = unwrap_file_key(wrap_key, wrap_aad,
                             entry + V4_WRAPPED_OFFSET,
                             entry + V4_WRAP_TAG_OFFSET, file_key);

cleanup:
    secure_free(x448_secret, x448_secret_len);
    secure_free(mlkem_secret, mlkem_secret_len);
    secure_mem_clear(wrap_key, sizeof(wrap_key));
    return result;
}

static void recipients_cleanup(HybridKeys *recipients, size_t count)
{
    size_t index;

    if (recipients == NULL) {
        return;
    }
    for (index = 0U; index < count; ++index) {
        hybrid_keys_cleanup(&recipients[index]);
    }
    OPENSSL_free(recipients);
}

/* Loads and validates every recipient before any output exists. */
static HybridKeys *load_recipients(const char *const *public_key_paths,
                                   size_t public_key_count)
{
    HybridKeys *recipients;
    size_t index;
    size_t other;

    recipients = OPENSSL_zalloc(public_key_count * sizeof(*recipients));
    if (recipients == NULL) {
        print_openssl_error("Cannot allocate NKEM v4 metadata");
        return NULL;
    }
    for (index = 0U; index < public_key_count; ++index) {
        if (!hybrid_load_public_keys(public_key_paths[index],
                                     &recipients[index])) {
            goto failure;
        }
        for (other = 0U; other < index; ++other) {
            if (EVP_PKEY_eq(recipients[index].x448,
                            recipients[other].x448) == 1 ||
                EVP_PKEY_eq(recipients[index].mlkem,
                            recipients[other].mlkem) == 1) {
                fprintf(stderr, file_message("The same public key is listed more than once\n"));
                goto failure;
            }
        }
    }
    return recipients;

failure:
    recipients_cleanup(recipients, public_key_count);
    return NULL;
}

int nekokem_encrypt_file_multi_with_progress(
    const char *input_path,
    const char *output_path,
    const char *const *public_key_paths,
    size_t public_key_count,
    NekoKEMProgressCallback progress_callback,
    void *progress_user_data)
{
    FILE *input = NULL;
    AtomicFile output = {0};
    HybridKeys *recipients = NULL;
    unsigned char *metadata = NULL;
    unsigned char file_key[NKEM_V4_FILE_KEY_SIZE] = {0};
    unsigned char payload_key[AES256_KEY_SIZE] = {0};
    unsigned char mac_key[V4_MAC_KEY_SIZE] = {0};
    unsigned char tag[NKEM_TAG_SIZE];
    unsigned char *nonce;
    size_t metadata_len = 0U;
    size_t mac_offset;
    size_t index;
    uint64_t plaintext_len = 0U;
    int aes_result;
    int result = NEKOKEM_OPERATION_ERROR;

    if (!valid_path(input_path) || !valid_path(output_path)) {
        fprintf(stderr, file_message("NekoKEM Core received an empty file path\n"));
        goto cleanup;
    }
    if (public_key_paths == NULL || public_key_count == 0U ||
        public_key_count > NKEM_V4_MAX_RECIPIENTS) {
        fprintf(stderr, file_message("Invalid recipient public-key list\n"));
        goto cleanup;
    }
    for (index = 0U; index < public_key_count; ++index) {
        if (!valid_path(public_key_paths[index])) {
            fprintf(stderr, file_message("NekoKEM Core received an empty file path\n"));
            goto cleanup;
        }
    }
    input = file_open_regular(input_path);
    if (input == NULL) {
        goto cleanup;
    }
    if (!file_disable_buffering(input) ||
        !file_get_size(input, &plaintext_len) ||
        !nkem_gcm_data_size_is_valid(plaintext_len)) {
        goto cleanup;
    }
    recipients = load_recipients(public_key_paths, public_key_count);
    if (recipients == NULL) {
        goto cleanup;
    }

    metadata_len = (size_t)nkem_v4_metadata_size((uint16_t)public_key_count);
    mac_offset = V4_PREFIX_SIZE + public_key_count * NKEM_V4_ENTRY_SIZE;
    metadata = OPENSSL_malloc(metadata_len);
    if (metadata == NULL) {
        print_openssl_error("Cannot allocate NKEM v4 metadata");
        goto cleanup;
    }
    nonce = metadata + mac_offset + NKEM_V4_MAC_SIZE;
    nkem_v4_header_encode(metadata, (uint16_t)public_key_count,
                          plaintext_len);
    if (RAND_bytes(file_key, (int)sizeof(file_key)) != 1 ||
        RAND_bytes(metadata + NKEM_V4_HEADER_SIZE,
                   (int)NKEM_V4_SALT_SIZE) != 1 ||
        RAND_bytes(nonce, (int)NKEM_NONCE_SIZE) != 1) {
        print_openssl_error("Cannot generate NKEM v4 random values");
        goto cleanup;
    }
    for (index = 0U; index < public_key_count; ++index) {
        if (!seal_recipient(&recipients[index], metadata, file_key,
                            metadata + V4_PREFIX_SIZE +
                                index * NKEM_V4_ENTRY_SIZE)) {
            goto cleanup;
        }
    }
    recipients_cleanup(recipients, public_key_count);
    recipients = NULL;

    if (!derive_file_keys(file_key, metadata + NKEM_V4_HEADER_SIZE,
                          payload_key, mac_key) ||
        !header_mac(mac_key, metadata, mac_offset, metadata + mac_offset)) {
        goto cleanup;
    }
    secure_mem_clear(file_key, sizeof(file_key));
    secure_mem_clear(mac_key, sizeof(mac_key));

    if (!atomic_file_open(&output, output_path, 0600)) {
        goto cleanup;
    }
    /* The payload AAD is exactly the on-disk metadata, already in wire order. */
    if (!file_write_all(output.stream, metadata, metadata_len)) {
        goto cleanup;
    }
    aes_result = aes_gcm_encrypt_file_with_progress(
        input, output.stream, plaintext_len,
        payload_key, nonce, metadata, metadata_len, tag,
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
    recipients_cleanup(recipients, public_key_count);
    OPENSSL_free(metadata);
    secure_mem_clear(file_key, sizeof(file_key));
    secure_mem_clear(payload_key, sizeof(payload_key));
    secure_mem_clear(mac_key, sizeof(mac_key));
    return result;
}

int nekokem_v4_decrypt_opened(
    FILE *input,
    uint64_t container_size,
    const unsigned char raw_header[NKEM_V4_HEADER_SIZE],
    const char *output_path,
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len,
    NekoKEMProgressCallback progress_callback,
    void *progress_user_data)
{
    AtomicFile output = {0};
    HybridKeys private_keys = {0};
    NkemV4Header header;
    unsigned char *metadata = NULL;
    unsigned char recipient_public[X448_PUBLIC_KEY_SIZE];
    unsigned char file_key[NKEM_V4_FILE_KEY_SIZE] = {0};
    unsigned char payload_key[AES256_KEY_SIZE] = {0};
    unsigned char mac_key[V4_MAC_KEY_SIZE] = {0};
    unsigned char expected_mac[NKEM_V4_MAC_SIZE];
    size_t metadata_len = 0U;
    size_t mac_offset;
    size_t index;
    int opened = 0;
    int aes_result;
    int result = NEKOKEM_OPERATION_ERROR;

    if (!nkem_v4_header_decode(raw_header, &header) ||
        !nkem_v4_container_size_is_valid(&header, container_size)) {
        goto cleanup;
    }
    metadata_len = (size_t)nkem_v4_metadata_size(header.recipient_count);
    mac_offset = V4_PREFIX_SIZE +
                 (size_t)header.recipient_count * NKEM_V4_ENTRY_SIZE;
    metadata = OPENSSL_malloc(metadata_len);
    if (metadata == NULL) {
        print_openssl_error("Cannot allocate NKEM v4 metadata");
        goto cleanup;
    }
    memcpy(metadata, raw_header, NKEM_V4_HEADER_SIZE);
    if (!file_read_exact(input, metadata + NKEM_V4_HEADER_SIZE,
                         metadata_len - NKEM_V4_HEADER_SIZE) ||
        !hybrid_load_decryption_keys(private_key_path, password,
                                     password_len, &private_keys) ||
        !x448_raw_public(private_keys.x448, recipient_public)) {
        goto cleanup;
    }
    /* Entries carry no recipient identifier: try each one in order. */
    for (index = 0U; index < header.recipient_count && opened == 0; ++index) {
        opened = open_recipient(
            &private_keys, recipient_public, metadata,
            metadata + V4_PREFIX_SIZE + index * NKEM_V4_ENTRY_SIZE,
            file_key);
        if (opened < 0) {
            goto cleanup;
        }
    }
    hybrid_keys_cleanup(&private_keys);
    if (opened == 0) {
        fprintf(stderr, file_message("This file is not encrypted for this private key\n"));
        goto cleanup;
    }

    /* Verify the whole recipient list before reading any ciphertext. */
    if (!derive_file_keys(file_key, metadata + NKEM_V4_HEADER_SIZE,
                          payload_key, mac_key) ||
        !header_mac(mac_key, metadata, mac_offset, expected_mac)) {
        goto cleanup;
    }
    secure_mem_clear(file_key, sizeof(file_key));
    secure_mem_clear(mac_key, sizeof(mac_key));
    if (CRYPTO_memcmp(expected_mac, metadata + mac_offset,
                      NKEM_V4_MAC_SIZE) != 0) {
        fprintf(stderr, file_message("NKEM v4 header authentication failed: the file was modified\n"));
        goto cleanup;
    }

    if (!atomic_file_open(&output, output_path, 0600)) {
        goto cleanup;
    }
    aes_result = aes_gcm_decrypt_file_with_progress(
        input, output.stream, header.ciphertext_len, payload_key,
        metadata + mac_offset + NKEM_V4_MAC_SIZE, metadata, metadata_len,
        progress_callback, progress_user_data);
    if (aes_result == AES_GCM_FILE_CANCELLED) {
        result = NEKOKEM_OPERATION_CANCELLED;
        goto cleanup;
    }
    if (aes_result != AES_GCM_FILE_SUCCESS ||
        fgetc(input) != EOF || ferror(input) != 0) {
        if (ferror(input) != 0) {
            print_system_error("Cannot finish reading NKEM v4 input");
        }
        goto cleanup;
    }
    if (!atomic_file_commit(&output)) {
        goto cleanup;
    }
    result = NEKOKEM_OPERATION_SUCCESS;

cleanup:
    atomic_file_abort(&output);
    hybrid_keys_cleanup(&private_keys);
    OPENSSL_free(metadata);
    secure_mem_clear(file_key, sizeof(file_key));
    secure_mem_clear(payload_key, sizeof(payload_key));
    secure_mem_clear(mac_key, sizeof(mac_key));
    secure_mem_clear(expected_mac, sizeof(expected_mac));
    return result;
}
