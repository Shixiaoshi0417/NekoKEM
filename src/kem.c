#include "kem.h"

#include "file.h"
#include "secure_mem.h"

#include <openssl/core_names.h>
#include <openssl/crypto.h>
#include <openssl/kdf.h>
#include <openssl/params.h>
#include <openssl/pem.h>
#include <stdio.h>
#include <string.h>

#define MAX_PRIVATE_KEY_FILE_SIZE (1024U * 1024U)

static int validate_kem_key(EVP_PKEY *key, const char *description)
{
    if (key == NULL || EVP_PKEY_is_a(key, KEM_ALGORITHM_NAME) != 1) {
        fprintf(stderr, "%s is not an %s key\n",
                description, KEM_ALGORITHM_NAME);
        return 0;
    }
    return 1;
}

int kem_generate_keypair(const char *public_path, const char *private_path)
{
    EVP_PKEY_CTX *context = NULL;
    EVP_PKEY *key = NULL;
    AtomicFile public_file = {0};
    AtomicFile private_file = {0};
    int success = 0;

    context = EVP_PKEY_CTX_new_from_name(NULL, KEM_ALGORITHM_NAME, NULL);
    if (context == NULL) {
        print_openssl_error("Cannot create ML-KEM key generation context");
        goto cleanup;
    }
    if (EVP_PKEY_keygen_init(context) <= 0) {
        print_openssl_error("Cannot initialize ML-KEM key generation");
        goto cleanup;
    }
    if (EVP_PKEY_keygen(context, &key) <= 0) {
        print_openssl_error("Cannot generate ML-KEM-1024 key pair");
        goto cleanup;
    }
    if (!atomic_file_open(&private_file, private_path, 0600) ||
        !atomic_file_open(&public_file, public_path, 0644)) {
        goto cleanup;
    }
    if (PEM_write_PrivateKey(private_file.stream, key, NULL, NULL, 0,
                             NULL, NULL) != 1) {
        print_openssl_error("Cannot write private key");
        goto cleanup;
    }
    if (PEM_write_PUBKEY(public_file.stream, key) != 1) {
        print_openssl_error("Cannot write public key");
        goto cleanup;
    }
    if (!atomic_file_commit_pair(&private_file, &public_file)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    atomic_file_abort(&private_file);
    atomic_file_abort(&public_file);
    EVP_PKEY_free(key);
    EVP_PKEY_CTX_free(context);
    return success;
}

EVP_PKEY *kem_load_public_key(const char *path)
{
    BIO *input = NULL;
    EVP_PKEY *key = NULL;
    EVP_PKEY *result = NULL;

    input = BIO_new_file(path, "rb");
    if (input == NULL) {
        print_openssl_error("Cannot open public key");
        goto cleanup;
    }
    key = PEM_read_bio_PUBKEY(input, NULL, NULL, NULL);
    if (key == NULL) {
        print_openssl_error("Cannot parse public key");
        goto cleanup;
    }
    if (!validate_kem_key(key, "Public key")) {
        goto cleanup;
    }
    result = key;
    key = NULL;

cleanup:
    EVP_PKEY_free(key);
    BIO_free(input);
    return result;
}

EVP_PKEY *kem_load_private_key(const char *path)
{
    BIO *input = NULL;
    EVP_PKEY *key = NULL;
    EVP_PKEY *result = NULL;
    unsigned char *pem = NULL;
    size_t pem_len = 0U;

    if (!file_read_sensitive(path, MAX_PRIVATE_KEY_FILE_SIZE,
                             &pem, &pem_len)) {
        goto cleanup;
    }
    if (pem_len > (size_t)INT_MAX) {
        fprintf(stderr, "Private-key file is too large for PEM parsing\n");
        goto cleanup;
    }
    input = BIO_new_mem_buf(pem, (int)pem_len);
    if (input == NULL) {
        print_openssl_error("Cannot create private-key memory BIO");
        goto cleanup;
    }
    key = PEM_read_bio_PrivateKey(input, NULL, NULL, NULL);
    if (key == NULL) {
        print_openssl_error("Cannot parse private key");
        goto cleanup;
    }
    if (!validate_kem_key(key, "Private key")) {
        goto cleanup;
    }
    result = key;
    key = NULL;

cleanup:
    EVP_PKEY_free(key);
    BIO_free(input);
    secure_free(pem, pem_len);
    return result;
}

int kem_encapsulate(EVP_PKEY *public_key,
                    unsigned char **ciphertext,
                    size_t *ciphertext_len,
                    unsigned char **shared_secret,
                    size_t *shared_secret_len)
{
    EVP_PKEY_CTX *context = NULL;
    unsigned char *local_ciphertext = NULL;
    unsigned char *local_secret = NULL;
    size_t local_ciphertext_len = 0U;
    size_t local_ciphertext_capacity = 0U;
    size_t local_secret_len = 0U;
    size_t local_secret_capacity = 0U;
    int success = 0;

    if (public_key == NULL || ciphertext == NULL || ciphertext_len == NULL ||
        shared_secret == NULL || shared_secret_len == NULL) {
        fprintf(stderr, "Invalid ML-KEM encapsulation request\n");
        return 0;
    }
    *ciphertext = NULL;
    *shared_secret = NULL;
    *ciphertext_len = 0U;
    *shared_secret_len = 0U;

    context = EVP_PKEY_CTX_new_from_pkey(NULL, public_key, NULL);
    if (context == NULL) {
        print_openssl_error("Cannot create ML-KEM encapsulation context");
        goto cleanup;
    }
    if (EVP_PKEY_encapsulate_init(context, NULL) <= 0) {
        print_openssl_error("Cannot initialize ML-KEM encapsulation");
        goto cleanup;
    }
    if (EVP_PKEY_encapsulate(context, NULL, &local_ciphertext_len,
                             NULL, &local_secret_capacity) <= 0) {
        print_openssl_error("Cannot query ML-KEM encapsulation sizes");
        goto cleanup;
    }
    if (local_ciphertext_len != KEM_CIPHERTEXT_SIZE ||
        local_ciphertext_len > NKEM_MAX_KEM_CIPHERTEXT_SIZE ||
        local_secret_capacity != KEM_SHARED_SECRET_SIZE ||
        local_secret_capacity > KEM_MAX_SHARED_SECRET_SIZE) {
        fprintf(stderr, "OpenSSL returned invalid ML-KEM output sizes\n");
        goto cleanup;
    }

    local_ciphertext_capacity = local_ciphertext_len;
    local_ciphertext = OPENSSL_malloc(local_ciphertext_capacity);
    local_secret = OPENSSL_malloc(local_secret_capacity);
    if (local_ciphertext == NULL || local_secret == NULL) {
        print_openssl_error("Cannot allocate ML-KEM output buffers");
        goto cleanup;
    }
    local_secret_len = local_secret_capacity;
    if (EVP_PKEY_encapsulate(context, local_ciphertext,
                             &local_ciphertext_len, local_secret,
                             &local_secret_len) <= 0) {
        print_openssl_error("ML-KEM encapsulation failed");
        goto cleanup;
    }
    if (local_ciphertext_len != KEM_CIPHERTEXT_SIZE ||
        local_ciphertext_len > local_ciphertext_capacity ||
        local_secret_len != KEM_SHARED_SECRET_SIZE ||
        local_secret_len > local_secret_capacity) {
        fprintf(stderr, "OpenSSL returned invalid ML-KEM output lengths\n");
        goto cleanup;
    }

    *ciphertext = local_ciphertext;
    *ciphertext_len = local_ciphertext_len;
    *shared_secret = local_secret;
    *shared_secret_len = local_secret_len;
    local_ciphertext = NULL;
    local_secret = NULL;
    success = 1;

cleanup:
    OPENSSL_free(local_ciphertext);
    secure_free(local_secret, local_secret_capacity);
    EVP_PKEY_CTX_free(context);
    return success;
}

int kem_decapsulate(EVP_PKEY *private_key,
                    const unsigned char *ciphertext,
                    size_t ciphertext_len,
                    unsigned char **shared_secret,
                    size_t *shared_secret_len)
{
    EVP_PKEY_CTX *context = NULL;
    unsigned char *local_secret = NULL;
    size_t local_secret_len = 0U;
    size_t local_secret_capacity = 0U;
    int success = 0;

    if (private_key == NULL || ciphertext == NULL ||
        ciphertext_len != KEM_CIPHERTEXT_SIZE ||
        shared_secret == NULL || shared_secret_len == NULL) {
        fprintf(stderr, "Invalid ML-KEM decapsulation request\n");
        return 0;
    }
    *shared_secret = NULL;
    *shared_secret_len = 0U;

    context = EVP_PKEY_CTX_new_from_pkey(NULL, private_key, NULL);
    if (context == NULL) {
        print_openssl_error("Cannot create ML-KEM decapsulation context");
        goto cleanup;
    }
    if (EVP_PKEY_decapsulate_init(context, NULL) <= 0) {
        print_openssl_error("Cannot initialize ML-KEM decapsulation");
        goto cleanup;
    }
    if (EVP_PKEY_decapsulate(context, NULL, &local_secret_capacity,
                             ciphertext, ciphertext_len) <= 0) {
        print_openssl_error("Cannot query ML-KEM shared-secret size");
        goto cleanup;
    }
    if (local_secret_capacity != KEM_SHARED_SECRET_SIZE ||
        local_secret_capacity > KEM_MAX_SHARED_SECRET_SIZE) {
        fprintf(stderr, "OpenSSL returned an invalid shared-secret size\n");
        goto cleanup;
    }

    local_secret = OPENSSL_malloc(local_secret_capacity);
    if (local_secret == NULL) {
        print_openssl_error("Cannot allocate ML-KEM shared-secret buffer");
        goto cleanup;
    }
    local_secret_len = local_secret_capacity;
    if (EVP_PKEY_decapsulate(context, local_secret, &local_secret_len,
                             ciphertext, ciphertext_len) <= 0) {
        print_openssl_error("ML-KEM decapsulation failed");
        goto cleanup;
    }
    if (local_secret_len != KEM_SHARED_SECRET_SIZE ||
        local_secret_len > local_secret_capacity) {
        fprintf(stderr, "OpenSSL returned an invalid ML-KEM secret length\n");
        goto cleanup;
    }

    *shared_secret = local_secret;
    *shared_secret_len = local_secret_len;
    local_secret = NULL;
    success = 1;

cleanup:
    secure_free(local_secret, local_secret_capacity);
    EVP_PKEY_CTX_free(context);
    return success;
}

int derive_aes256_key(const unsigned char *shared_secret,
                      size_t shared_secret_len,
                      const unsigned char *salt,
                      size_t salt_len,
                      unsigned char output_key[AES256_KEY_SIZE])
{
    static char digest_name[] = "SHA256";
    static unsigned char info[] =
        "NekoKEM v1 ML-KEM-1024/HKDF-SHA256/AES-256-GCM";
    EVP_KDF *algorithm = NULL;
    EVP_KDF_CTX *context = NULL;
    OSSL_PARAM parameters[5];
    OSSL_PARAM *parameter = parameters;
    int success = 0;

    if (output_key == NULL) {
        fprintf(stderr, "Invalid v1 key-derivation output\n");
        return 0;
    }
    secure_mem_clear(output_key, AES256_KEY_SIZE);
    if (shared_secret == NULL ||
        shared_secret_len != KEM_SHARED_SECRET_SIZE ||
        salt == NULL || salt_len == 0U) {
        fprintf(stderr, "Invalid v1 key-derivation inputs\n");
        goto cleanup;
    }

    algorithm = EVP_KDF_fetch(NULL, "HKDF", NULL);
    if (algorithm == NULL) {
        print_openssl_error("Cannot fetch HKDF");
        goto cleanup;
    }
    context = EVP_KDF_CTX_new(algorithm);
    if (context == NULL) {
        print_openssl_error("Cannot create HKDF context");
        goto cleanup;
    }

    *parameter++ = OSSL_PARAM_construct_utf8_string(
        OSSL_KDF_PARAM_DIGEST, digest_name, 0U);
    *parameter++ = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_KEY, (void *)shared_secret, shared_secret_len);
    *parameter++ = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_SALT, (void *)salt, salt_len);
    *parameter++ = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_INFO, info, sizeof(info) - 1U);
    *parameter = OSSL_PARAM_construct_end();

    if (EVP_KDF_derive(context, output_key, AES256_KEY_SIZE,
                       parameters) <= 0) {
        print_openssl_error("HKDF-SHA256 derivation failed");
        goto cleanup;
    }
    success = 1;

cleanup:
    if (success == 0) {
        secure_mem_clear(output_key, AES256_KEY_SIZE);
    }
    EVP_KDF_CTX_free(context);
    EVP_KDF_free(algorithm);
    return success;
}
