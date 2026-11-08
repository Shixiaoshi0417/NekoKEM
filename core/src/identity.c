/* NekoKEM v5 identity keys and hybrid signatures; see docs/NKEM-v5.md. */
#include "identity.h"

#include "file.h"
#include "secure_mem.h"
#include "x448_encoding.h"

#include <openssl/core_names.h>
#include <openssl/crypto.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/params.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static const char fingerprint_label[] = "NekoKEM v5 identity fingerprint";
static const char ed448_context[] = "NekoKEM v5 Ed448";
static const char mldsa_context[] = "NekoKEM v5 ML-DSA-87";

typedef struct {
    unsigned char id;
    size_t length;
    const char *algorithm;
} IdentityComponent;

static const IdentityComponent components[IDENTITY_COMPONENT_COUNT] = {
    {IDENTITY_ID_X448, X448_PUBLIC_KEY_SIZE, X448_ALGORITHM_NAME},
    {IDENTITY_ID_MLKEM1024, IDENTITY_MLKEM1024_PUBLIC_SIZE, KEM_ALGORITHM_NAME},
    {IDENTITY_ID_ED448, IDENTITY_ED448_PUBLIC_SIZE, ED448_ALGORITHM_NAME},
    {IDENTITY_ID_MLDSA87, IDENTITY_MLDSA87_PUBLIC_SIZE, MLDSA_ALGORITHM_NAME},
};

_Static_assert(IDENTITY_ENCODING_SIZE == 4291U, "Identity encoding size");
_Static_assert(IDENTITY_FINGERPRINT_STRING_SIZE == 96U,
               "Fingerprint string size matches NEKOKEM_FINGERPRINT_STRING_SIZE");

static EVP_PKEY *component_key(const HybridKeys *keys, size_t index)
{
    switch (index) {
    case 0U:
        return keys->x448;
    case 1U:
        return keys->mlkem;
    case 2U:
        return keys->ed448;
    default:
        return keys->mldsa;
    }
}

static EVP_PKEY **component_slot(HybridKeys *keys, size_t index)
{
    switch (index) {
    case 0U:
        return &keys->x448;
    case 1U:
        return &keys->mlkem;
    case 2U:
        return &keys->ed448;
    default:
        return &keys->mldsa;
    }
}

int identity_ed448_public_is_valid(
    const unsigned char encoded[IDENTITY_ED448_PUBLIC_SIZE])
{
    /*
     * RFC 8032: y is the low 455 bits and must be below p, the Curve448
     * prime, so the top byte holds only the x sign bit. y = 0, 1 and p - 1
     * are exactly the points of order 1, 2 and 4 (and their non-canonical
     * x = 0 encodings with the sign bit set).
     */
    return encoded != NULL && (encoded[56] & 0x7fU) == 0U &&
           x448_public_is_canonical(encoded) &&
           !x448_public_has_small_order(encoded);
}

int identity_ed448_key_is_valid(EVP_PKEY *key)
{
    unsigned char encoded[IDENTITY_ED448_PUBLIC_SIZE];
    size_t length = sizeof(encoded);

    return key != NULL && EVP_PKEY_is_a(key, ED448_ALGORITHM_NAME) == 1 &&
           EVP_PKEY_get_raw_public_key(key, encoded, &length) == 1 &&
           length == sizeof(encoded) &&
           identity_ed448_public_is_valid(encoded);
}

int identity_encode(const HybridKeys *keys,
                    unsigned char output[IDENTITY_ENCODING_SIZE])
{
    unsigned char *cursor = output;
    size_t index;

    if (keys == NULL || output == NULL) {
        return 0;
    }
    memcpy(cursor, IDENTITY_MAGIC, 4U);
    cursor[4] = IDENTITY_ENCODING_VERSION;
    cursor[5] = IDENTITY_COMPONENT_COUNT;
    cursor += 6U;
    for (index = 0U; index < IDENTITY_COMPONENT_COUNT; ++index) {
        EVP_PKEY *key = component_key(keys, index);
        size_t length = components[index].length;

        if (key == NULL ||
            EVP_PKEY_is_a(key, components[index].algorithm) != 1) {
            fprintf(stderr, file_message("The key is not a four-component identity key\n"));
            return 0;
        }
        cursor[0] = components[index].id;
        cursor[1] = (unsigned char)(length >> 8);
        cursor[2] = (unsigned char)length;
        if (EVP_PKEY_get_raw_public_key(key, cursor + 3U, &length) != 1 ||
            length != components[index].length) {
            print_openssl_error("Cannot read an identity public-key component");
            return 0;
        }
        cursor += 3U + length;
    }
    return 1;
}

int identity_decode(const unsigned char input[IDENTITY_ENCODING_SIZE],
                    HybridKeys *keys)
{
    HybridKeys loaded = {0};
    const unsigned char *cursor = input;
    size_t index;
    int success = 0;

    if (input == NULL || keys == NULL) {
        return 0;
    }
    memset(keys, 0, sizeof(*keys));
    if (memcmp(cursor, IDENTITY_MAGIC, 4U) != 0 ||
        cursor[4] != IDENTITY_ENCODING_VERSION ||
        cursor[5] != IDENTITY_COMPONENT_COUNT) {
        return 0;
    }
    cursor += 6U;
    (void)ERR_set_mark();
    for (index = 0U; index < IDENTITY_COMPONENT_COUNT; ++index) {
        size_t length = components[index].length;
        const unsigned char *raw = cursor + 3U;
        EVP_PKEY **slot = component_slot(&loaded, index);

        if (cursor[0] != components[index].id ||
            (((size_t)cursor[1] << 8) | (size_t)cursor[2]) != length) {
            goto cleanup;
        }
        if ((index == 0U && (!x448_public_is_canonical(raw) ||
                             x448_public_has_small_order(raw))) ||
            (index == 2U && !identity_ed448_public_is_valid(raw))) {
            goto cleanup;
        }
        /* ML-KEM import performs the FIPS 203 modulus check. */
        *slot = EVP_PKEY_new_raw_public_key_ex(
            NULL, components[index].algorithm, NULL, raw, length);
        if (*slot == NULL) {
            goto cleanup;
        }
        cursor += 3U + length;
    }
    *keys = loaded;
    memset(&loaded, 0, sizeof(loaded));
    success = 1;

cleanup:
    (void)ERR_pop_to_mark();
    hybrid_keys_cleanup(&loaded);
    return success;
}

int identity_fingerprint(const unsigned char encoding[IDENTITY_ENCODING_SIZE],
                         char *output,
                         size_t output_size)
{
    EVP_MD_CTX *context = NULL;
    unsigned char digest[EVP_MAX_MD_SIZE];
    unsigned int digest_len = 0U;
    int success = 0;

    if (encoding == NULL || output == NULL ||
        output_size < IDENTITY_FINGERPRINT_STRING_SIZE) {
        fprintf(stderr, file_message("Invalid fingerprint input or output buffer\n"));
        return 0;
    }
    output[0] = '\0';
    context = EVP_MD_CTX_new();
    if (context == NULL ||
        EVP_DigestInit_ex(context, EVP_sha256(), NULL) != 1 ||
        EVP_DigestUpdate(context, fingerprint_label,
                         sizeof(fingerprint_label) - 1U) != 1 ||
        EVP_DigestUpdate(context, encoding, IDENTITY_ENCODING_SIZE) != 1 ||
        EVP_DigestFinal_ex(context, digest, &digest_len) != 1 ||
        digest_len != 32U) {
        print_openssl_error("Cannot calculate identity fingerprint");
        goto cleanup;
    }
    success = identity_format_fingerprint(digest, output, output_size);

cleanup:
    EVP_MD_CTX_free(context);
    return success;
}

int identity_format_fingerprint(const unsigned char digest[32],
                                char *output,
                                size_t output_size)
{
    static const char hex[] = "0123456789ABCDEF";
    size_t index;
    size_t position = 0U;

    if (digest == NULL || output == NULL ||
        output_size < IDENTITY_FINGERPRINT_STRING_SIZE) {
        return 0;
    }
    for (index = 0U; index < 32U; ++index) {
        output[position++] = hex[(digest[index] >> 4) & 0x0fU];
        output[position++] = hex[digest[index] & 0x0fU];
        if (index + 1U < 32U) {
            output[position++] = ':';
        }
    }
    output[position] = '\0';
    return 1;
}

static int sign_one(EVP_PKEY *key,
                    const char *context_string,
                    size_t context_len,
                    const unsigned char *message,
                    size_t message_len,
                    unsigned char *signature,
                    size_t signature_len)
{
    EVP_MD_CTX *context = EVP_MD_CTX_new();
    OSSL_PARAM parameters[2];
    size_t written = signature_len;
    int success = 0;

    parameters[0] = OSSL_PARAM_construct_octet_string(
        OSSL_SIGNATURE_PARAM_CONTEXT_STRING, (void *)context_string,
        context_len);
    parameters[1] = OSSL_PARAM_construct_end();
    if (context == NULL ||
        EVP_DigestSignInit_ex(context, NULL, NULL, NULL, NULL, key,
                              parameters) != 1 ||
        EVP_DigestSign(context, signature, &written, message,
                       message_len) != 1 ||
        written != signature_len) {
        print_openssl_error("Cannot sign the NKEM v5 container");
        goto cleanup;
    }
    success = 1;

cleanup:
    EVP_MD_CTX_free(context);
    return success;
}

static int verify_one(EVP_PKEY *key,
                      const char *context_string,
                      size_t context_len,
                      const unsigned char *message,
                      size_t message_len,
                      const unsigned char *signature,
                      size_t signature_len)
{
    EVP_MD_CTX *context = EVP_MD_CTX_new();
    OSSL_PARAM parameters[2];
    int valid = 0;

    parameters[0] = OSSL_PARAM_construct_octet_string(
        OSSL_SIGNATURE_PARAM_CONTEXT_STRING, (void *)context_string,
        context_len);
    parameters[1] = OSSL_PARAM_construct_end();
    valid = context != NULL &&
            EVP_DigestVerifyInit_ex(context, NULL, NULL, NULL, NULL, key,
                                    parameters) == 1 &&
            EVP_DigestVerify(context, signature, signature_len, message,
                             message_len) == 1;
    EVP_MD_CTX_free(context);
    return valid;
}

int identity_sign(const HybridKeys *keys,
                  const unsigned char *message,
                  size_t message_len,
                  unsigned char signature[IDENTITY_SIGNATURE_SIZE])
{
    if (keys == NULL || keys->ed448 == NULL || keys->mldsa == NULL ||
        message == NULL || signature == NULL) {
        fprintf(stderr, file_message("Signing needs a four-component identity private key; upgrade the key first\n"));
        return 0;
    }
    if (!sign_one(keys->ed448, ed448_context, sizeof(ed448_context) - 1U,
                  message, message_len, signature,
                  IDENTITY_ED448_SIGNATURE_SIZE) ||
        !sign_one(keys->mldsa, mldsa_context, sizeof(mldsa_context) - 1U,
                  message, message_len,
                  signature + IDENTITY_ED448_SIGNATURE_SIZE,
                  IDENTITY_MLDSA87_SIGNATURE_SIZE)) {
        secure_mem_clear(signature, IDENTITY_SIGNATURE_SIZE);
        return 0;
    }
    return 1;
}

int identity_verify(const HybridKeys *keys,
                    const unsigned char *message,
                    size_t message_len,
                    const unsigned char signature[IDENTITY_SIGNATURE_SIZE])
{
    int ed448;
    int mldsa;

    if (keys == NULL || keys->ed448 == NULL || keys->mldsa == NULL ||
        message == NULL || signature == NULL) {
        return 0;
    }
    (void)ERR_set_mark();
    /* Both are always checked; neither result short-circuits the other. */
    ed448 = verify_one(keys->ed448, ed448_context, sizeof(ed448_context) - 1U,
                       message, message_len, signature,
                       IDENTITY_ED448_SIGNATURE_SIZE);
    mldsa = verify_one(keys->mldsa, mldsa_context, sizeof(mldsa_context) - 1U,
                       message, message_len,
                       signature + IDENTITY_ED448_SIGNATURE_SIZE,
                       IDENTITY_MLDSA87_SIGNATURE_SIZE);
    (void)ERR_pop_to_mark();
    return ed448 && mldsa;
}
