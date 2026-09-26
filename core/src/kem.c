#include "kem.h"

#include "file.h"
#include "secure_mem.h"

#include <openssl/crypto.h>
#include <stdio.h>

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
