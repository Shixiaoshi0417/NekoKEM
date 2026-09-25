#include "nekokem.h"
#include "nekokem_internal.h"

#include "file.h"
#include "hybrid.h"
#include "private_key.h"
#include "secure_mem.h"

#include <limits.h>
#include <openssl/evp.h>
#include <openssl/x509.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static int valid_path(const char *path)
{
    return path != NULL && path[0] != '\\0';
}

int nekokem_generate_keypair(const char *public_key_path,
                             const char *private_key_path,
                             const unsigned char *password,
                             size_t password_len)
{
    if (!valid_path(public_key_path) || !valid_path(private_key_path)) {
        fprintf(stderr, "NekoKEM Core received an empty key path\\n");
        return 0;
    }
    if (password == NULL || password_len == 0U) {
        fprintf(stderr, "A non-empty private-key password is required\\n");
        return 0;
    }
    return hybrid_generate_keypair(public_key_path, private_key_path,
                                   password, password_len);
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

static int fingerprint_hybrid_keys(const HybridKeys *keys,
                                   char *output,
                                   size_t output_size)
{
    static const unsigned char domain[] =
        "NekoKEM v2 hybrid public-key fingerprint";
    static const char hex[] = "0123456789ABCDEF";
    EVP_MD_CTX *context = NULL;
    unsigned char fingerprint[EVP_MAX_MD_SIZE] = {0};
    unsigned int fingerprint_len = 0U;
    size_t input_index;
    size_t output_index = 0U;
    int success = 0;

    if (keys == NULL || keys->x448 == NULL || keys->mlkem == NULL ||
        output == NULL ||
        output_size < NEKOKEM_FINGERPRINT_STRING_SIZE) {
        fprintf(stderr, "Invalid fingerprint input or output buffer\n");
        goto cleanup;
    }
    output[0] = '\0';
    context = EVP_MD_CTX_new();
    if (context == NULL) {
        print_openssl_error("Cannot create fingerprint context");
        goto cleanup;
    }
    if (EVP_DigestInit_ex(context, EVP_sha256(), NULL) != 1 ||
        EVP_DigestUpdate(context, domain, sizeof(domain) - 1U) != 1 ||
        !digest_public_key_component(context, keys->x448) ||
        !digest_public_key_component(context, keys->mlkem) ||
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
    return success;
}

int nekokem_public_key_fingerprint(const char *public_key_path,
                                   char *output,
                                   size_t output_size)
{
    HybridKeys keys = {0};
    int success = 0;

    if (!valid_path(public_key_path) || output == NULL ||
        output_size < NEKOKEM_FINGERPRINT_STRING_SIZE) {
        fprintf(stderr, "Invalid fingerprint output buffer or key path\n");
        goto cleanup;
    }
    output[0] = '\0';
    if (!hybrid_load_public_keys(public_key_path, &keys) ||
        !fingerprint_hybrid_keys(&keys, output, output_size)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    if (success == 0 && output != NULL && output_size > 0U) {
        output[0] = '\0';
    }
    hybrid_keys_cleanup(&keys);
    return success;
}

int nekokem_internal_private_key_fingerprint(
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len,
    char *output,
    size_t output_size)
{
    HybridKeys keys = {0};
    int success = 0;

    if (!valid_path(private_key_path) || password == NULL ||
        password_len == 0U || output == NULL ||
        output_size < NEKOKEM_FINGERPRINT_STRING_SIZE) {
        fprintf(stderr, "Invalid private-key fingerprint argument\n");
        goto cleanup;
    }
    output[0] = '\0';
    if (!nekokem_private_key_exists(private_key_path) ||
        !hybrid_load_protected_private_keys(
            private_key_path, password, password_len, &keys) ||
        !fingerprint_hybrid_keys(&keys, output, output_size)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    if (success == 0 && output != NULL && output_size > 0U) {
        output[0] = '\0';
    }
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
