#include "hybrid.h"
#include "private_key.h"
#include "secure_mem.h"

#include <openssl/buffer.h>
#include <openssl/core_names.h>
#include <openssl/crypto.h>
#include <openssl/kdf.h>
#include <openssl/params.h>
#include <openssl/pem.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define X448_MAX_SHARED_SECRET_SIZE 1024U
#define MAX_PRIVATE_KEY_FILE_SIZE (1024U * 1024U)

static int validate_key(EVP_PKEY *key,
                        const char *algorithm,
                        const char *description)
{
    if (key == NULL || EVP_PKEY_is_a(key, algorithm) != 1) {
        fprintf(stderr, "%s is not an %s key\n", description, algorithm);
        return 0;
    }
    return 1;
}

static EVP_PKEY *generate_key(const char *algorithm)
{
    EVP_PKEY_CTX *context = NULL;
    EVP_PKEY *key = NULL;
    EVP_PKEY *result = NULL;

    context = EVP_PKEY_CTX_new_from_name(NULL, algorithm, NULL);
    if (context == NULL) {
        print_openssl_error("Cannot create hybrid key generation context");
        goto cleanup;
    }
    if (EVP_PKEY_keygen_init(context) <= 0) {
        print_openssl_error("Cannot initialize hybrid key generation");
        goto cleanup;
    }
    if (EVP_PKEY_keygen(context, &key) <= 0) {
        print_openssl_error("Cannot generate hybrid key component");
        goto cleanup;
    }
    result = key;
    key = NULL;

cleanup:
    EVP_PKEY_free(key);
    EVP_PKEY_CTX_free(context);
    return result;
}

void hybrid_keys_cleanup(HybridKeys *keys)
{
    if (keys == NULL) {
        return;
    }
    EVP_PKEY_free(keys->x448);
    EVP_PKEY_free(keys->mlkem);
    keys->x448 = NULL;
    keys->mlkem = NULL;
}

int hybrid_generate_keypair(const char *public_path,
                            const char *private_path,
                            const unsigned char *password,
                            size_t password_len)
{
    HybridKeys keys = {0};
    AtomicFile public_file = {0};
    AtomicFile private_file = {0};
    BIO *private_bio = NULL;
    BUF_MEM *private_buffer = NULL;
    int success = 0;

    keys.x448 = generate_key(X448_ALGORITHM_NAME);
    keys.mlkem = generate_key(KEM_ALGORITHM_NAME);
    if (keys.x448 == NULL || keys.mlkem == NULL) {
        goto cleanup;
    }
    private_bio = BIO_new(BIO_s_mem());
    if (private_bio == NULL) {
        print_openssl_error("Cannot create hybrid private-key memory BIO");
        goto cleanup;
    }
    if (PEM_write_bio_PrivateKey(private_bio, keys.x448,
                                 NULL, NULL, 0, NULL, NULL) != 1 ||
        PEM_write_bio_PrivateKey(private_bio, keys.mlkem,
                                 NULL, NULL, 0, NULL, NULL) != 1) {
        print_openssl_error("Cannot serialize hybrid private keys");
        goto cleanup;
    }
    if (BIO_get_mem_ptr(private_bio, &private_buffer) <= 0 ||
        private_buffer == NULL || private_buffer->data == NULL ||
        private_buffer->length == 0U ||
        private_buffer->length > NKPR_MAX_PEM_SIZE ||
        private_buffer->length > (size_t)INT_MAX) {
        fprintf(stderr, "Invalid serialized hybrid private-key length\n");
        goto cleanup;
    }
    if (!atomic_file_open(&public_file, public_path, 0644)) {
        goto cleanup;
    }
    if (PEM_write_PUBKEY(public_file.stream, keys.x448) != 1 ||
        PEM_write_PUBKEY(public_file.stream, keys.mlkem) != 1) {
        print_openssl_error("Cannot write hybrid public keys");
        goto cleanup;
    }
    if (!protected_private_key_stage(
            &private_file, private_path,
            (const unsigned char *)private_buffer->data,
            private_buffer->length, password, password_len) ||
        !atomic_file_commit_pair(&public_file, &private_file)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    atomic_file_abort(&public_file);
    atomic_file_abort(&private_file);
    if (private_buffer != NULL && private_buffer->data != NULL) {
        secure_mem_clear(private_buffer->data, private_buffer->max);
    }
    BIO_free(private_bio);
    hybrid_keys_cleanup(&keys);
    return success;
}

static int parse_hybrid_private_keys(const unsigned char *pem,
                                     size_t pem_len,
                                     HybridKeys *keys)
{
    BIO *input = NULL;
    HybridKeys loaded = {0};
    int success = 0;

    keys->x448 = NULL;
    keys->mlkem = NULL;
    if (pem == NULL || pem_len == 0U || pem_len > (size_t)INT_MAX) {
        fprintf(stderr, "Invalid hybrid private-key PEM length\n");
        goto cleanup;
    }
    input = BIO_new_mem_buf(pem, (int)pem_len);
    if (input == NULL) {
        print_openssl_error(
            "Cannot create hybrid private-key memory BIO");
        goto cleanup;
    }
    loaded.x448 = PEM_read_bio_PrivateKey(input, NULL, NULL, NULL);
    loaded.mlkem = PEM_read_bio_PrivateKey(input, NULL, NULL, NULL);
    if (loaded.x448 == NULL || loaded.mlkem == NULL) {
        print_openssl_error("Cannot parse both hybrid key components");
        goto cleanup;
    }
    if (!validate_key(loaded.x448, X448_ALGORITHM_NAME,
                      "First hybrid key component") ||
        !validate_key(loaded.mlkem, KEM_ALGORITHM_NAME,
                      "Second hybrid key component")) {
        goto cleanup;
    }

    *keys = loaded;
    loaded.x448 = NULL;
    loaded.mlkem = NULL;
    success = 1;

cleanup:
    BIO_free(input);
    hybrid_keys_cleanup(&loaded);
    return success;
}

int hybrid_load_public_keys(const char *path, HybridKeys *keys)
{
    BIO *input = NULL;
    HybridKeys loaded = {0};
    int success = 0;

    keys->x448 = NULL;
    keys->mlkem = NULL;
    input = BIO_new_file(path, "rb");
    if (input == NULL) {
        print_openssl_error("Cannot open hybrid public-key file");
        goto cleanup;
    }
    loaded.x448 = PEM_read_bio_PUBKEY(input, NULL, NULL, NULL);
    loaded.mlkem = PEM_read_bio_PUBKEY(input, NULL, NULL, NULL);
    if (loaded.x448 == NULL || loaded.mlkem == NULL) {
        print_openssl_error("Cannot parse both hybrid key components");
        goto cleanup;
    }
    if (!validate_key(loaded.x448, X448_ALGORITHM_NAME,
                      "First hybrid key component") ||
        !validate_key(loaded.mlkem, KEM_ALGORITHM_NAME,
                      "Second hybrid key component")) {
        goto cleanup;
    }
    *keys = loaded;
    loaded.x448 = NULL;
    loaded.mlkem = NULL;
    success = 1;

cleanup:
    BIO_free(input);
    hybrid_keys_cleanup(&loaded);
    return success;
}

int hybrid_load_private_keys(const char *path, HybridKeys *keys)
{
    unsigned char *pem = NULL;
    size_t pem_len = 0U;
    int success = 0;

    keys->x448 = NULL;
    keys->mlkem = NULL;
    if (!file_read_sensitive(path, MAX_PRIVATE_KEY_FILE_SIZE,
                             &pem, &pem_len)) {
        goto cleanup;
    }
    success = parse_hybrid_private_keys(pem, pem_len, keys);

cleanup:
    secure_free(pem, pem_len);
    return success;
}

int hybrid_load_protected_private_keys(
    const char *path,
    const unsigned char *password,
    size_t password_len,
    HybridKeys *keys)
{
    unsigned char *pem = NULL;
    size_t pem_len = 0U;
    int success = 0;

    keys->x448 = NULL;
    keys->mlkem = NULL;
    if (!protected_private_key_read(path, password, password_len,
                                    &pem, &pem_len)) {
        goto cleanup;
    }
    success = parse_hybrid_private_keys(pem, pem_len, keys);

cleanup:
    secure_free(pem, pem_len);
    return success;
}

static int x448_derive(EVP_PKEY *private_key,
                       EVP_PKEY *peer_public_key,
                       unsigned char **shared_secret,
                       size_t *shared_secret_len)
{
    EVP_PKEY_CTX *context = NULL;
    unsigned char *secret = NULL;
    size_t secret_capacity = 0U;
    size_t secret_len = 0U;
    int success = 0;

    if (shared_secret == NULL || shared_secret_len == NULL ||
        private_key == NULL || peer_public_key == NULL) {
        fprintf(stderr, "Invalid X448 derivation request\n");
        return 0;
    }
    *shared_secret = NULL;
    *shared_secret_len = 0U;
    context = EVP_PKEY_CTX_new_from_pkey(NULL, private_key, NULL);
    if (context == NULL) {
        print_openssl_error("Cannot create X448 derivation context");
        goto cleanup;
    }
    if (EVP_PKEY_derive_init(context) <= 0 ||
        EVP_PKEY_derive_set_peer(context, peer_public_key) <= 0) {
        print_openssl_error("Cannot initialize X448 key agreement");
        goto cleanup;
    }
    if (EVP_PKEY_derive(context, NULL, &secret_capacity) <= 0) {
        print_openssl_error("Cannot query X448 shared-secret size");
        goto cleanup;
    }
    if (secret_capacity != X448_SHARED_SECRET_SIZE ||
        secret_capacity > X448_MAX_SHARED_SECRET_SIZE) {
        fprintf(stderr, "OpenSSL returned an invalid X448 secret size\n");
        goto cleanup;
    }

    secret = OPENSSL_malloc(secret_capacity);
    if (secret == NULL) {
        print_openssl_error("Cannot allocate X448 shared-secret buffer");
        goto cleanup;
    }
    secret_len = secret_capacity;
    if (EVP_PKEY_derive(context, secret, &secret_len) <= 0 ||
        secret_len != X448_SHARED_SECRET_SIZE) {
        print_openssl_error("X448 key agreement failed");
        goto cleanup;
    }

    *shared_secret = secret;
    *shared_secret_len = secret_len;
    secret = NULL;
    success = 1;

cleanup:
    secure_free(secret, secret_capacity);
    EVP_PKEY_CTX_free(context);
    return success;
}

int hybrid_x448_encapsulate(
    EVP_PKEY *recipient_public_key,
    unsigned char ephemeral_public[X448_PUBLIC_KEY_SIZE],
    unsigned char **shared_secret,
    size_t *shared_secret_len)
{
    EVP_PKEY *ephemeral_key = NULL;
    size_t public_len = 0U;
    int success = 0;

    if (ephemeral_public == NULL || shared_secret == NULL ||
        shared_secret_len == NULL) {
        fprintf(stderr, "Invalid X448 encapsulation output request\n");
        return 0;
    }
    if (!validate_key(recipient_public_key, X448_ALGORITHM_NAME,
                      "Hybrid recipient public key")) {
        return 0;
    }
    ephemeral_key = generate_key(X448_ALGORITHM_NAME);
    if (ephemeral_key == NULL) {
        goto cleanup;
    }
    if (EVP_PKEY_get_raw_public_key(ephemeral_key, NULL, &public_len) != 1 ||
        public_len != X448_PUBLIC_KEY_SIZE) {
        print_openssl_error("Cannot query X448 ephemeral public key");
        goto cleanup;
    }
    if (EVP_PKEY_get_raw_public_key(ephemeral_key, ephemeral_public,
                                    &public_len) != 1 ||
        public_len != X448_PUBLIC_KEY_SIZE) {
        print_openssl_error("Cannot export X448 ephemeral public key");
        goto cleanup;
    }
    if (!x448_derive(ephemeral_key, recipient_public_key,
                     shared_secret, shared_secret_len)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    EVP_PKEY_free(ephemeral_key);
    return success;
}

int hybrid_x448_decapsulate(
    EVP_PKEY *recipient_private_key,
    const unsigned char ephemeral_public[X448_PUBLIC_KEY_SIZE],
    size_t ephemeral_public_len,
    unsigned char **shared_secret,
    size_t *shared_secret_len)
{
    EVP_PKEY *ephemeral_key = NULL;
    int success = 0;

    if (ephemeral_public == NULL || shared_secret == NULL ||
        shared_secret_len == NULL) {
        fprintf(stderr, "Invalid X448 decapsulation request\n");
        return 0;
    }
    if (!validate_key(recipient_private_key, X448_ALGORITHM_NAME,
                      "Hybrid recipient private key")) {
        return 0;
    }
    if (ephemeral_public_len != X448_PUBLIC_KEY_SIZE) {
        fprintf(stderr, "Invalid X448 ephemeral public-key length\n");
        return 0;
    }
    ephemeral_key = EVP_PKEY_new_raw_public_key_ex(
        NULL, X448_ALGORITHM_NAME, NULL,
        ephemeral_public, ephemeral_public_len);
    if (ephemeral_key == NULL) {
        print_openssl_error("Cannot import X448 ephemeral public key");
        goto cleanup;
    }
    if (!x448_derive(recipient_private_key, ephemeral_key,
                     shared_secret, shared_secret_len)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    EVP_PKEY_free(ephemeral_key);
    return success;
}

int hybrid_derive_aes256_key(
    const unsigned char *x448_secret,
    size_t x448_secret_len,
    const unsigned char *mlkem_secret,
    size_t mlkem_secret_len,
    const unsigned char *salt,
    size_t salt_len,
    unsigned char output_key[AES256_KEY_SIZE])
{
    static char digest_name[] = "SHA512";
    static unsigned char info[] =
        "NekoKEM v2 HYBRID-X448-MLKEM1024/HKDF-SHA512/AES-256-GCM";
    EVP_KDF *algorithm = NULL;
    EVP_KDF_CTX *context = NULL;
    OSSL_PARAM parameters[5];
    OSSL_PARAM *parameter = parameters;
    unsigned char *combined_secret = NULL;
    size_t combined_len;
    int success = 0;

    if (output_key == NULL) {
        fprintf(stderr, "Invalid hybrid key-derivation output\n");
        return 0;
    }
    secure_mem_clear(output_key, AES256_KEY_SIZE);

    if (x448_secret == NULL || mlkem_secret == NULL || salt == NULL ||
        x448_secret_len != X448_SHARED_SECRET_SIZE ||
        mlkem_secret_len != KEM_SHARED_SECRET_SIZE || salt_len == 0U) {
        fprintf(stderr, "Invalid hybrid key-derivation input lengths\n");
        goto cleanup;
    }
    if (x448_secret_len > SIZE_MAX - mlkem_secret_len) {
        fprintf(stderr, "Hybrid shared-secret length overflows\n");
        goto cleanup;
    }
    combined_len = x448_secret_len + mlkem_secret_len;
    if (combined_len == 0U) {
        fprintf(stderr, "Hybrid shared secret is empty\n");
        goto cleanup;
    }
    combined_secret = OPENSSL_malloc(combined_len);
    if (combined_secret == NULL) {
        print_openssl_error("Cannot allocate hybrid shared secret");
        goto cleanup;
    }
    memcpy(combined_secret, x448_secret, x448_secret_len);
    memcpy(combined_secret + x448_secret_len,
           mlkem_secret, mlkem_secret_len);

    algorithm = EVP_KDF_fetch(NULL, "HKDF", NULL);
    if (algorithm == NULL) {
        print_openssl_error("Cannot fetch HKDF for hybrid mode");
        goto cleanup;
    }
    context = EVP_KDF_CTX_new(algorithm);
    if (context == NULL) {
        print_openssl_error("Cannot create hybrid HKDF context");
        goto cleanup;
    }

    *parameter++ = OSSL_PARAM_construct_utf8_string(
        OSSL_KDF_PARAM_DIGEST, digest_name, 0U);
    *parameter++ = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_KEY, combined_secret, combined_len);
    *parameter++ = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_SALT, (void *)salt, salt_len);
    *parameter++ = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_INFO, info, sizeof(info) - 1U);
    *parameter = OSSL_PARAM_construct_end();

    if (EVP_KDF_derive(context, output_key, AES256_KEY_SIZE,
                       parameters) <= 0) {
        print_openssl_error("HKDF-SHA512 derivation failed");
        goto cleanup;
    }
    success = 1;

cleanup:
    if (success == 0) {
        secure_mem_clear(output_key, AES256_KEY_SIZE);
    }
    EVP_KDF_CTX_free(context);
    EVP_KDF_free(algorithm);
    secure_free(combined_secret,
                combined_secret == NULL ? 0U : combined_len);
    return success;
}
