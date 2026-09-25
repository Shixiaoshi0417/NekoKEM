#include "private_key.h"

#include "file.h"
#include "secure_mem.h"

#include <limits.h>
#include <openssl/core_names.h>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/params.h>
#include <openssl/rand.h>
#include <openssl/thread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define NKPR_KDF_ID_ARGON2ID 1U
#define NKPR_CIPHER_ID_AES256_GCM 1U
#define NKPR_ARGON2_VERSION 0x13U
#define NKPR_FLAGS 0U
#define NKPR_MAX_CONTAINER_SIZE \
    (NKPR_HEADER_SIZE + NKPR_SALT_SIZE + NKPR_NONCE_SIZE + \
     NKPR_MAX_PEM_SIZE + NKPR_TAG_SIZE)

typedef struct {
    uint32_t memory_kib;
    uint32_t iterations;
    uint32_t parallelism;
    uint32_t argon2_version;
    uint64_t ciphertext_len;
} NkprHeader;

static int checked_size_add(size_t left, size_t right, size_t *result)
{
    if (result == NULL || SIZE_MAX - left < right) {
        return 0;
    }
    *result = left + right;
    return 1;
}

static void put_u16_be(unsigned char *output, uint16_t value)
{
    output[0] = (unsigned char)(value >> 8);
    output[1] = (unsigned char)value;
}

static void put_u32_be(unsigned char *output, uint32_t value)
{
    output[0] = (unsigned char)(value >> 24);
    output[1] = (unsigned char)(value >> 16);
    output[2] = (unsigned char)(value >> 8);
    output[3] = (unsigned char)value;
}

static void put_u64_be(unsigned char *output, uint64_t value)
{
    size_t index;

    for (index = 0U; index < 8U; ++index) {
        output[index] =
            (unsigned char)(value >> (56U - (index * 8U)));
    }
}

static uint16_t get_u16_be(const unsigned char *input)
{
    return (uint16_t)(((uint16_t)input[0] << 8) |
                      (uint16_t)input[1]);
}

static uint32_t get_u32_be(const unsigned char *input)
{
    return ((uint32_t)input[0] << 24) |
           ((uint32_t)input[1] << 16) |
           ((uint32_t)input[2] << 8) |
           (uint32_t)input[3];
}

static uint64_t get_u64_be(const unsigned char *input)
{
    uint64_t value = 0U;
    size_t index;

    for (index = 0U; index < 8U; ++index) {
        value = (value << 8) | (uint64_t)input[index];
    }
    return value;
}

static void nkpr_header_encode(
    unsigned char output[NKPR_HEADER_SIZE],
    uint64_t ciphertext_len)
{
    memcpy(output, NKPR_MAGIC, 4U);
    output[4] = NKPR_VERSION;
    output[5] = NKPR_KDF_ID_ARGON2ID;
    output[6] = NKPR_CIPHER_ID_AES256_GCM;
    output[7] = NKPR_FLAGS;
    put_u32_be(output + 8U, NKPR_ARGON2_MEMORY_KIB);
    put_u32_be(output + 12U, NKPR_ARGON2_ITERATIONS);
    put_u32_be(output + 16U, NKPR_ARGON2_PARALLELISM);
    put_u32_be(output + 20U, NKPR_ARGON2_VERSION);
    put_u16_be(output + 24U, (uint16_t)NKPR_SALT_SIZE);
    output[26] = NKPR_NONCE_SIZE;
    output[27] = NKPR_TAG_SIZE;
    put_u64_be(output + 28U, ciphertext_len);
}

static int nkpr_header_decode_internal(
    const unsigned char input[NKPR_HEADER_SIZE],
    NkprHeader *header,
    int report_errors)
{
    if (memcmp(input, NKPR_MAGIC, 4U) != 0) {
        if (report_errors != 0) {
            fprintf(stderr, "Invalid NKPR private-key magic\n");
        }
        return 0;
    }
    if (input[4] != NKPR_VERSION ||
        input[5] != NKPR_KDF_ID_ARGON2ID ||
        input[6] != NKPR_CIPHER_ID_AES256_GCM ||
        input[7] != NKPR_FLAGS) {
        if (report_errors != 0) {
            fprintf(stderr, "Unsupported NKPR private-key format\n");
        }
        return 0;
    }

    header->memory_kib = get_u32_be(input + 8U);
    header->iterations = get_u32_be(input + 12U);
    header->parallelism = get_u32_be(input + 16U);
    header->argon2_version = get_u32_be(input + 20U);
    header->ciphertext_len = get_u64_be(input + 28U);
    if (header->memory_kib != NKPR_ARGON2_MEMORY_KIB ||
        header->iterations != NKPR_ARGON2_ITERATIONS ||
        header->parallelism != NKPR_ARGON2_PARALLELISM ||
        header->argon2_version != NKPR_ARGON2_VERSION ||
        get_u16_be(input + 24U) != NKPR_SALT_SIZE ||
        input[26] != NKPR_NONCE_SIZE ||
        input[27] != NKPR_TAG_SIZE) {
        if (report_errors != 0) {
            fprintf(stderr, "Unsupported NKPR protection parameters\n");
        }
        return 0;
    }
    if (header->ciphertext_len == 0U ||
        header->ciphertext_len > NKPR_MAX_PEM_SIZE ||
        header->ciphertext_len > (uint64_t)INT_MAX) {
        if (report_errors != 0) {
            fprintf(stderr,
                    "Invalid NKPR private-key ciphertext length\n");
        }
        return 0;
    }
    return 1;
}

static int nkpr_container_decode_internal(
    const unsigned char *input,
    size_t input_len,
    NkprHeader *header,
    int report_errors)
{
    size_t ciphertext_len;
    size_t expected_len;

    if (input == NULL ||
        input_len < NKPR_HEADER_SIZE + NKPR_SALT_SIZE +
                        NKPR_NONCE_SIZE + NKPR_TAG_SIZE) {
        if (report_errors != 0) {
            fprintf(stderr, "NKPR private-key container is truncated\n");
        }
        return 0;
    }
    if (!nkpr_header_decode_internal(input, header, report_errors)) {
        return 0;
    }
    ciphertext_len = (size_t)header->ciphertext_len;
    expected_len = NKPR_HEADER_SIZE;
    if (!checked_size_add(expected_len, NKPR_SALT_SIZE, &expected_len) ||
        !checked_size_add(expected_len, NKPR_NONCE_SIZE, &expected_len) ||
        !checked_size_add(expected_len, ciphertext_len, &expected_len) ||
        !checked_size_add(expected_len, NKPR_TAG_SIZE, &expected_len)) {
        if (report_errors != 0) {
            fprintf(stderr, "NKPR private-key length overflows\n");
        }
        return 0;
    }
    if (input_len != expected_len) {
        if (report_errors != 0) {
            fprintf(stderr, "Invalid NKPR private-key container length\n");
        }
        return 0;
    }
    return 1;
}

int protected_private_key_container_parse(
    const unsigned char *input,
    size_t input_len)
{
    NkprHeader header;

    return nkpr_container_decode_internal(input, input_len, &header, 0);
}

static int derive_protection_key(
    const unsigned char *password,
    size_t password_len,
    const unsigned char salt[NKPR_SALT_SIZE],
    unsigned char key[32])
{
    EVP_KDF *algorithm = NULL;
    EVP_KDF_CTX *context = NULL;
    OSSL_PARAM parameters[9];
    OSSL_PARAM *parameter = parameters;
    uint32_t iterations = NKPR_ARGON2_ITERATIONS;
    uint32_t threads = NKPR_ARGON2_PARALLELISM;
    uint32_t lanes = NKPR_ARGON2_PARALLELISM;
    uint32_t memory_kib = NKPR_ARGON2_MEMORY_KIB;
    uint32_t early_clean = 1U;
    uint32_t version = NKPR_ARGON2_VERSION;
    int success = 0;

    if (key == NULL) {
        fprintf(stderr, "Invalid private-key protection output\n");
        return 0;
    }
    secure_mem_clear(key, 32U);
    if (password == NULL || password_len == 0U ||
        password_len > NKPR_MAX_PASSWORD_SIZE || salt == NULL) {
        fprintf(stderr, "Invalid private-key protection parameters\n");
        goto cleanup;
    }
    if ((OSSL_get_thread_support_flags() &
         OSSL_THREAD_SUPPORT_FLAG_THREAD_POOL) == 0U) {
        fprintf(stderr,
                "OpenSSL was built without Argon2 thread-pool support\n");
        goto cleanup;
    }
    if (OSSL_get_max_threads(NULL) < NKPR_ARGON2_PARALLELISM &&
        OSSL_set_max_threads(NULL, NKPR_ARGON2_PARALLELISM) != 1) {
        print_openssl_error("Cannot initialize OpenSSL Argon2 thread pool");
        goto cleanup;
    }

    algorithm = EVP_KDF_fetch(NULL, "ARGON2ID", NULL);
    if (algorithm == NULL) {
        print_openssl_error("Cannot fetch Argon2id");
        goto cleanup;
    }
    context = EVP_KDF_CTX_new(algorithm);
    if (context == NULL) {
        print_openssl_error("Cannot create Argon2id context");
        goto cleanup;
    }

    *parameter++ = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_PASSWORD, (void *)password, password_len);
    *parameter++ = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_SALT, (void *)salt, NKPR_SALT_SIZE);
    *parameter++ = OSSL_PARAM_construct_uint32(
        OSSL_KDF_PARAM_ITER, &iterations);
    *parameter++ = OSSL_PARAM_construct_uint32(
        OSSL_KDF_PARAM_THREADS, &threads);
    *parameter++ = OSSL_PARAM_construct_uint32(
        OSSL_KDF_PARAM_ARGON2_LANES, &lanes);
    *parameter++ = OSSL_PARAM_construct_uint32(
        OSSL_KDF_PARAM_ARGON2_MEMCOST, &memory_kib);
    *parameter++ = OSSL_PARAM_construct_uint32(
        OSSL_KDF_PARAM_EARLY_CLEAN, &early_clean);
    *parameter++ = OSSL_PARAM_construct_uint32(
        OSSL_KDF_PARAM_ARGON2_VERSION, &version);
    *parameter = OSSL_PARAM_construct_end();

    if (EVP_KDF_derive(context, key, 32U, parameters) <= 0) {
        print_openssl_error("Argon2id private-key derivation failed");
        goto cleanup;
    }
    success = 1;

cleanup:
    if (success == 0) {
        secure_mem_clear(key, 32U);
    }
    EVP_KDF_CTX_free(context);
    EVP_KDF_free(algorithm);
    return success;
}

static int encrypt_pem(
    const unsigned char *pem,
    size_t pem_len,
    const unsigned char key[32],
    const unsigned char header[NKPR_HEADER_SIZE],
    const unsigned char salt[NKPR_SALT_SIZE],
    const unsigned char nonce[NKPR_NONCE_SIZE],
    unsigned char *ciphertext,
    unsigned char tag[NKPR_TAG_SIZE])
{
    EVP_CIPHER_CTX *context = NULL;
    int output_len = 0;
    int final_len = 0;
    int success = 0;

    if (pem == NULL || pem_len == 0U || pem_len > (size_t)INT_MAX ||
        key == NULL || header == NULL || salt == NULL || nonce == NULL ||
        ciphertext == NULL || tag == NULL) {
        fprintf(stderr, "Invalid private-key encryption request\n");
        return 0;
    }

    context = EVP_CIPHER_CTX_new();
    if (context == NULL) {
        print_openssl_error("Cannot create private-key cipher context");
        goto cleanup;
    }
    if (EVP_EncryptInit_ex(context, EVP_aes_256_gcm(), NULL,
                           NULL, NULL) <= 0 ||
        EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_SET_IVLEN,
                            (int)NKPR_NONCE_SIZE, NULL) <= 0 ||
        EVP_EncryptInit_ex(context, NULL, NULL, key, nonce) <= 0 ||
        EVP_EncryptUpdate(context, NULL, &output_len,
                          header, (int)NKPR_HEADER_SIZE) <= 0 ||
        EVP_EncryptUpdate(context, NULL, &output_len,
                          salt, (int)NKPR_SALT_SIZE) <= 0 ||
        EVP_EncryptUpdate(context, NULL, &output_len,
                          nonce, (int)NKPR_NONCE_SIZE) <= 0 ||
        EVP_EncryptUpdate(context, ciphertext, &output_len,
                          pem, (int)pem_len) <= 0 ||
        EVP_EncryptFinal_ex(context, ciphertext + (size_t)output_len,
                            &final_len) <= 0) {
        print_openssl_error("Cannot encrypt hybrid private key");
        goto cleanup;
    }
    if (output_len < 0 || final_len < 0 ||
        (size_t)output_len + (size_t)final_len != pem_len) {
        fprintf(stderr, "Unexpected private-key ciphertext length\n");
        goto cleanup;
    }
    if (EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_GET_TAG,
                            (int)NKPR_TAG_SIZE, tag) <= 0) {
        print_openssl_error(
            "Cannot retrieve private-key authentication tag");
        goto cleanup;
    }
    success = 1;

cleanup:
    EVP_CIPHER_CTX_free(context);
    return success;
}

static int decrypt_pem(
    const unsigned char *ciphertext,
    size_t ciphertext_len,
    const unsigned char tag[NKPR_TAG_SIZE],
    const unsigned char key[32],
    const unsigned char header[NKPR_HEADER_SIZE],
    const unsigned char salt[NKPR_SALT_SIZE],
    const unsigned char nonce[NKPR_NONCE_SIZE],
    unsigned char *pem,
    size_t *pem_len)
{
    EVP_CIPHER_CTX *context = NULL;
    int output_len = 0;
    int final_len = 0;
    int success = 0;

    if (ciphertext == NULL || ciphertext_len == 0U ||
        ciphertext_len > (size_t)INT_MAX || tag == NULL || key == NULL ||
        header == NULL || salt == NULL || nonce == NULL || pem == NULL ||
        pem_len == NULL) {
        fprintf(stderr, "Invalid private-key decryption request\n");
        return 0;
    }
    *pem_len = 0U;

    context = EVP_CIPHER_CTX_new();
    if (context == NULL) {
        print_openssl_error("Cannot create private-key cipher context");
        goto cleanup;
    }
    if (EVP_DecryptInit_ex(context, EVP_aes_256_gcm(), NULL,
                           NULL, NULL) <= 0 ||
        EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_SET_IVLEN,
                            (int)NKPR_NONCE_SIZE, NULL) <= 0 ||
        EVP_DecryptInit_ex(context, NULL, NULL, key, nonce) <= 0 ||
        EVP_DecryptUpdate(context, NULL, &output_len,
                          header, (int)NKPR_HEADER_SIZE) <= 0 ||
        EVP_DecryptUpdate(context, NULL, &output_len,
                          salt, (int)NKPR_SALT_SIZE) <= 0 ||
        EVP_DecryptUpdate(context, NULL, &output_len,
                          nonce, (int)NKPR_NONCE_SIZE) <= 0 ||
        EVP_DecryptUpdate(context, pem, &output_len,
                          ciphertext, (int)ciphertext_len) <= 0 ||
        EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_SET_TAG,
                            (int)NKPR_TAG_SIZE, (void *)tag) <= 0) {
        print_openssl_error("Cannot decrypt hybrid private key");
        goto cleanup;
    }
    if (EVP_DecryptFinal_ex(context, pem + (size_t)output_len,
                            &final_len) <= 0) {
        print_openssl_error(
            "Private-key password is incorrect or NKPR data "
            "is corrupted");
        goto cleanup;
    }
    if (output_len < 0 || final_len < 0 ||
        (size_t)output_len + (size_t)final_len != ciphertext_len) {
        fprintf(stderr, "Unexpected decrypted private-key length\n");
        goto cleanup;
    }
    *pem_len = (size_t)output_len + (size_t)final_len;
    success = 1;

cleanup:
    EVP_CIPHER_CTX_free(context);
    return success;
}

int protected_private_key_stage(
    AtomicFile *output,
    const char *path,
    const unsigned char *pem,
    size_t pem_len,
    const unsigned char *password,
    size_t password_len)
{
    unsigned char header[NKPR_HEADER_SIZE] = {0};
    unsigned char salt[NKPR_SALT_SIZE] = {0};
    unsigned char nonce[NKPR_NONCE_SIZE] = {0};
    unsigned char tag[NKPR_TAG_SIZE] = {0};
    unsigned char key[32] = {0};
    unsigned char *ciphertext = NULL;
    int success = 0;

    if (output == NULL || path == NULL || pem == NULL || pem_len == 0U ||
        pem_len > NKPR_MAX_PEM_SIZE || pem_len > (size_t)INT_MAX ||
        password == NULL || password_len == 0U ||
        password_len > NKPR_MAX_PASSWORD_SIZE) {
        fprintf(stderr, "Invalid hybrid private-key PEM data\n");
        goto cleanup;
    }
    if (RAND_bytes(salt, (int)sizeof(salt)) != 1 ||
        RAND_bytes(nonce, (int)sizeof(nonce)) != 1) {
        print_openssl_error("Cannot generate NKPR salt or nonce");
        goto cleanup;
    }
    if (!derive_protection_key(password, password_len, salt, key)) {
        goto cleanup;
    }
    nkpr_header_encode(header, (uint64_t)pem_len);
    ciphertext = OPENSSL_malloc(pem_len);
    if (ciphertext == NULL) {
        print_openssl_error("Cannot allocate private-key ciphertext");
        goto cleanup;
    }
    if (!encrypt_pem(pem, pem_len, key, header, salt, nonce,
                     ciphertext, tag)) {
        goto cleanup;
    }
    if (!atomic_file_open(output, path, 0600) ||
        !file_write_all(output->stream, header, sizeof(header)) ||
        !file_write_all(output->stream, salt, sizeof(salt)) ||
        !file_write_all(output->stream, nonce, sizeof(nonce)) ||
        !file_write_all(output->stream, ciphertext, pem_len) ||
        !file_write_all(output->stream, tag, sizeof(tag))) {
        goto cleanup;
    }
    success = 1;

cleanup:
    if (success == 0) {
        atomic_file_abort(output);
    }
    secure_free(ciphertext, pem_len);
    secure_mem_clear(key, sizeof(key));
    secure_mem_clear(header, sizeof(header));
    secure_mem_clear(salt, sizeof(salt));
    secure_mem_clear(nonce, sizeof(nonce));
    secure_mem_clear(tag, sizeof(tag));
    return success;
}

int protected_private_key_write(
    const char *path,
    const unsigned char *pem,
    size_t pem_len,
    const unsigned char *password,
    size_t password_len)
{
    AtomicFile output = {0};
    int success = 0;

    if (!protected_private_key_stage(&output, path, pem, pem_len,
                                     password, password_len) ||
        !atomic_file_commit(&output)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    atomic_file_abort(&output);
    return success;
}

int protected_private_key_read(
    const char *path,
    const unsigned char *password,
    size_t password_len,
    unsigned char **pem,
    size_t *pem_len)
{
    unsigned char *container = NULL;
    unsigned char *plaintext = NULL;
    unsigned char key[32] = {0};
    NkprHeader header;
    const unsigned char *salt;
    const unsigned char *nonce;
    const unsigned char *ciphertext;
    const unsigned char *tag;
    size_t container_len = 0U;
    size_t ciphertext_len;
    size_t plaintext_capacity = 0U;
    size_t plaintext_len = 0U;
    int success = 0;

    if (pem == NULL || pem_len == NULL) {
        fprintf(stderr, "Invalid protected private-key output request\n");
        return 0;
    }
    *pem = NULL;
    *pem_len = 0U;
    if (!file_read_sensitive(path, NKPR_MAX_CONTAINER_SIZE,
                             &container, &container_len)) {
        goto cleanup;
    }
    if (!nkpr_container_decode_internal(container, container_len,
                                        &header, 1)) {
        goto cleanup;
    }
    ciphertext_len = (size_t)header.ciphertext_len;

    salt = container + NKPR_HEADER_SIZE;
    nonce = salt + NKPR_SALT_SIZE;
    ciphertext = nonce + NKPR_NONCE_SIZE;
    tag = ciphertext + ciphertext_len;
    if (!derive_protection_key(password, password_len, salt, key)) {
        goto cleanup;
    }
    plaintext = OPENSSL_malloc(ciphertext_len);
    if (plaintext == NULL) {
        print_openssl_error("Cannot allocate decrypted private-key PEM");
        goto cleanup;
    }
    plaintext_capacity = ciphertext_len;
    if (!decrypt_pem(ciphertext, ciphertext_len, tag, key,
                     container, salt, nonce, plaintext,
                     &plaintext_len)) {
        goto cleanup;
    }

    *pem = plaintext;
    *pem_len = plaintext_len;
    plaintext = NULL;
    success = 1;

cleanup:
    secure_free(plaintext, plaintext_capacity);
    secure_free(container, container_len);
    secure_mem_clear(key, sizeof(key));
    return success;
}

int private_key_path_is_encrypted(const char *path)
{
    static const char suffix[] = ".enc";
    size_t path_len;
    size_t suffix_len = sizeof(suffix) - 1U;

    if (path == NULL) {
        return 0;
    }
    path_len = strlen(path);
    return path_len >= suffix_len &&
           strcmp(path + path_len - suffix_len, suffix) == 0;
}
