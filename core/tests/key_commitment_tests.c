/* NKPR v3 key commitment; see docs/key-commitment.md. */
#include "nekokem.h"
#include "file.h"
#include "private_key.h"

#include <openssl/core_names.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/params.h>
#include <openssl/thread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define CHECK(condition)                                                  \
    do {                                                                  \
        if (!(condition)) {                                               \
            fprintf(stderr, "Check failed at line %d: %s\n", __LINE__,    \
                    #condition);                                          \
            return 0;                                                     \
        }                                                                 \
    } while (0)

#define BLOCK 16U
#define KEY_SIZE 32U
#define MASTER_SIZE 64U
#define V2_AAD_SIZE (NKPR_HEADER_SIZE + NKPR_SALT_SIZE + NKPR_NONCE_SIZE)
#define V3_AAD_SIZE (V2_AAD_SIZE + NKPR_COMMITMENT_SIZE)
#define MAX_CONTAINER (V3_AAD_SIZE + 64U + NKPR_TAG_SIZE)

static const char cipher_info[] = "NekoKEM NKPR v3 AES-256-GCM";
static const char commitment_info[] = "NekoKEM NKPR v3 key commitment";
static const unsigned char password[] = "key-commitment-test-password";
static const unsigned char other_password[] = "another-candidate-password";

/*
 * Known answers from an independent implementation (argon2-cffi 25.1 and
 * the HKDF of pyca/cryptography) for kat_password and the salt 00 01 .. 1f.
 */
static const unsigned char kat_password[] = "NKPR v3 known-answer password";
static const unsigned char kat_master[MASTER_SIZE] = {
    0xe7, 0x80, 0xf7, 0xe5, 0x25, 0x89, 0x24, 0x2d, 0x7d, 0xfc, 0x2a,
    0x2b, 0xad, 0x97, 0x93, 0x3d, 0x0c, 0x4d, 0x3a, 0x1d, 0x12, 0xa8,
    0xfa, 0x5f, 0xbb, 0x7a, 0x22, 0x00, 0x59, 0x04, 0x92, 0xb4, 0xec,
    0x00, 0xbf, 0xdd, 0x44, 0xe5, 0x24, 0xa6, 0x97, 0x52, 0x51, 0x33,
    0xef, 0x03, 0x8c, 0xf6, 0x52, 0x3c, 0xf1, 0xe3, 0x57, 0x15, 0xad,
    0xa2, 0x8e, 0xa9, 0xcf, 0x70, 0x15, 0xba, 0xa1, 0xe1,
};
static const unsigned char kat_cipher_key[KEY_SIZE] = {
    0xcc, 0x66, 0x8a, 0xa7, 0x11, 0x66, 0x6f, 0x7c, 0x55, 0x46, 0x90,
    0x2f, 0xd4, 0xbe, 0xfd, 0x17, 0xea, 0x06, 0xea, 0xd7, 0xcc, 0xe4,
    0x3b, 0xe7, 0x02, 0x9b, 0xed, 0xc7, 0xeb, 0x88, 0xe2, 0x0b,
};
static const unsigned char kat_commitment[NKPR_COMMITMENT_SIZE] = {
    0x48, 0x19, 0xb7, 0xcf, 0xd7, 0x94, 0x7c, 0x57, 0x99, 0x8f, 0xa4,
    0xdd, 0x5a, 0x3a, 0x32, 0x28, 0x56, 0x51, 0x04, 0x33, 0x67, 0x00,
    0xd8, 0xcf, 0xd6, 0x5d, 0xf8, 0x98, 0x64, 0x5f, 0x04, 0x60,
};

static int argon2id(const unsigned char *secret, size_t secret_len,
                    const unsigned char salt[NKPR_SALT_SIZE],
                    unsigned char *output, size_t output_len)
{
    EVP_KDF *algorithm = EVP_KDF_fetch(NULL, "ARGON2ID", NULL);
    EVP_KDF_CTX *context = algorithm != NULL ? EVP_KDF_CTX_new(algorithm)
                                             : NULL;
    OSSL_PARAM parameters[8];
    uint32_t iterations = NKPR_ARGON2_ITERATIONS;
    uint32_t lanes = NKPR_ARGON2_PARALLELISM;
    uint32_t threads = NKPR_ARGON2_PARALLELISM;
    uint32_t memory = NKPR_ARGON2_MEMORY_KIB;
    uint32_t version = 0x13U;
    int success;

    (void)OSSL_set_max_threads(NULL, NKPR_ARGON2_PARALLELISM);
    parameters[0] = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_PASSWORD, (void *)secret, secret_len);
    parameters[1] = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_SALT, (void *)salt, NKPR_SALT_SIZE);
    parameters[2] = OSSL_PARAM_construct_uint32(OSSL_KDF_PARAM_ITER,
                                                &iterations);
    parameters[3] = OSSL_PARAM_construct_uint32(OSSL_KDF_PARAM_THREADS,
                                                &threads);
    parameters[4] = OSSL_PARAM_construct_uint32(OSSL_KDF_PARAM_ARGON2_LANES,
                                                &lanes);
    parameters[5] = OSSL_PARAM_construct_uint32(
        OSSL_KDF_PARAM_ARGON2_MEMCOST, &memory);
    parameters[6] = OSSL_PARAM_construct_uint32(
        OSSL_KDF_PARAM_ARGON2_VERSION, &version);
    parameters[7] = OSSL_PARAM_construct_end();
    success = context != NULL &&
              EVP_KDF_derive(context, output, output_len, parameters) > 0;
    EVP_KDF_CTX_free(context);
    EVP_KDF_free(algorithm);
    return success;
}

static int hkdf(const unsigned char master[MASTER_SIZE],
                const unsigned char salt[NKPR_SALT_SIZE],
                const char *info, unsigned char output[KEY_SIZE])
{
    static char digest[] = "SHA512";
    EVP_KDF *algorithm = EVP_KDF_fetch(NULL, "HKDF", NULL);
    EVP_KDF_CTX *context = algorithm != NULL ? EVP_KDF_CTX_new(algorithm)
                                             : NULL;
    OSSL_PARAM parameters[5];
    int success;

    parameters[0] = OSSL_PARAM_construct_utf8_string(OSSL_KDF_PARAM_DIGEST,
                                                     digest, 0U);
    parameters[1] = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_KEY, (void *)master, MASTER_SIZE);
    parameters[2] = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_SALT, (void *)salt, NKPR_SALT_SIZE);
    parameters[3] = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_INFO, (void *)info, strlen(info));
    parameters[4] = OSSL_PARAM_construct_end();
    success = context != NULL &&
              EVP_KDF_derive(context, output, KEY_SIZE, parameters) > 0;
    EVP_KDF_CTX_free(context);
    EVP_KDF_free(algorithm);
    return success;
}

/* The keys Core derives for an NKPR v3 container, computed independently. */
static int v3_keys(const unsigned char *secret, size_t secret_len,
                   const unsigned char salt[NKPR_SALT_SIZE],
                   unsigned char key[KEY_SIZE],
                   unsigned char commitment[NKPR_COMMITMENT_SIZE])
{
    unsigned char master[MASTER_SIZE];

    return argon2id(secret, secret_len, salt, master, sizeof(master)) &&
           hkdf(master, salt, cipher_info, key) &&
           hkdf(master, salt, commitment_info, commitment);
}

static void put_header(unsigned char *output, unsigned int version,
                       uint64_t ciphertext_len)
{
    static const unsigned char parameters[] = {
        1U, 1U, 0U,             /* Argon2id, AES-256-GCM, flags */
        0U, 1U, 0U, 0U,         /* 65536 KiB */
        0U, 0U, 0U, 3U,         /* iterations */
        0U, 0U, 0U, 4U,         /* lanes */
        0U, 0U, 0U, 0x13U,      /* Argon2 version */
        0U, 32U, 12U, 16U,      /* salt, nonce, tag sizes */
    };
    size_t index;

    memcpy(output, NKPR_MAGIC, 4U);
    output[4] = (unsigned char)version;
    memcpy(output + 5U, parameters, sizeof(parameters));
    for (index = 0U; index < 8U; ++index) {
        output[28U + index] =
            (unsigned char)(ciphertext_len >> (56U - 8U * index));
    }
}

static int aes_block(const unsigned char key[KEY_SIZE],
                     const unsigned char input[BLOCK],
                     unsigned char output[BLOCK])
{
    EVP_CIPHER_CTX *context = EVP_CIPHER_CTX_new();
    int length = 0;
    int success = context != NULL &&
                  EVP_EncryptInit_ex(context, EVP_aes_256_ecb(), NULL, key,
                                     NULL) == 1 &&
                  EVP_CIPHER_CTX_set_padding(context, 0) == 1 &&
                  EVP_EncryptUpdate(context, output, &length, input,
                                    (int)BLOCK) == 1 &&
                  length == (int)BLOCK;

    EVP_CIPHER_CTX_free(context);
    return success;
}

/* GF(2^128) multiplication in the GCM bit order. */
static void gf_multiply(const unsigned char x[BLOCK],
                        const unsigned char y[BLOCK],
                        unsigned char output[BLOCK])
{
    unsigned char z[BLOCK] = {0};
    unsigned char v[BLOCK];
    size_t bit;
    size_t index;

    memcpy(v, y, BLOCK);
    for (bit = 0U; bit < 128U; ++bit) {
        unsigned char carry = (unsigned char)(v[BLOCK - 1U] & 1U);

        if ((((unsigned int)x[bit / 8U] >> (7U - bit % 8U)) & 1U) != 0U) {
            for (index = 0U; index < BLOCK; ++index) {
                z[index] ^= v[index];
            }
        }
        for (index = BLOCK - 1U; index > 0U; --index) {
            v[index] = (unsigned char)((v[index] >> 1) |
                                       ((v[index - 1U] & 1U) << 7));
        }
        v[0] = (unsigned char)(v[0] >> 1);
        if (carry != 0U) {
            v[0] ^= 0xe1U;
        }
    }
    memcpy(output, z, BLOCK);
}

/* x^(2^128 - 2), the inverse of a non-zero x. */
static void gf_invert(const unsigned char x[BLOCK],
                      unsigned char output[BLOCK])
{
    unsigned char power[BLOCK];
    unsigned char result[BLOCK] = {0x80U};
    size_t round;

    memcpy(power, x, BLOCK);
    for (round = 1U; round < 128U; ++round) {
        gf_multiply(power, power, power);
        gf_multiply(result, power, result);
    }
    memcpy(output, result, BLOCK);
}

/* The GCM tag of one ciphertext block under a key, nonce and AAD. */
static int gcm_tag(const unsigned char key[KEY_SIZE],
                   const unsigned char nonce[NKPR_NONCE_SIZE],
                   const unsigned char *aad, size_t aad_len,
                   const unsigned char ciphertext[BLOCK],
                   unsigned char hash_key[BLOCK],
                   unsigned char tag[BLOCK])
{
    unsigned char zero[BLOCK] = {0};
    unsigned char counter[BLOCK] = {0};
    unsigned char mask[BLOCK];
    unsigned char state[BLOCK] = {0};
    unsigned char lengths[BLOCK] = {0};
    size_t offset;
    size_t index;

    memcpy(counter, nonce, NKPR_NONCE_SIZE);
    counter[BLOCK - 1U] = 1U;
    if (aad_len % BLOCK != 0U || !aes_block(key, zero, hash_key) ||
        !aes_block(key, counter, mask)) {
        return 0;
    }
    for (offset = 0U; offset < aad_len; offset += BLOCK) {
        for (index = 0U; index < BLOCK; ++index) {
            state[index] ^= aad[offset + index];
        }
        gf_multiply(state, hash_key, state);
    }
    for (index = 0U; index < BLOCK; ++index) {
        state[index] ^= ciphertext[index];
    }
    gf_multiply(state, hash_key, state);
    lengths[6] = (unsigned char)((aad_len * 8U) >> 8);
    lengths[7] = (unsigned char)(aad_len * 8U);
    lengths[15] = (unsigned char)(BLOCK * 8U);
    for (index = 0U; index < BLOCK; ++index) {
        state[index] ^= lengths[index];
    }
    gf_multiply(state, hash_key, state);
    for (index = 0U; index < BLOCK; ++index) {
        tag[index] = state[index] ^ mask[index];
    }
    return 1;
}

/*
 * The multi-key attack on AES-GCM: one ciphertext block and tag that
 * authenticate under both keys for this nonce and AAD.
 */
static int gcm_two_key_collision(const unsigned char first[KEY_SIZE],
                                 const unsigned char second[KEY_SIZE],
                                 const unsigned char nonce[NKPR_NONCE_SIZE],
                                 const unsigned char *aad, size_t aad_len,
                                 unsigned char ciphertext[BLOCK],
                                 unsigned char tag[BLOCK])
{
    unsigned char zero[BLOCK] = {0};
    unsigned char first_hash[BLOCK];
    unsigned char second_hash[BLOCK];
    unsigned char first_tag[BLOCK];
    unsigned char second_tag[BLOCK];
    unsigned char coefficient[BLOCK];
    unsigned char square[BLOCK];
    size_t index;

    if (!gcm_tag(first, nonce, aad, aad_len, zero, first_hash, first_tag) ||
        !gcm_tag(second, nonce, aad, aad_len, zero, second_hash,
                 second_tag)) {
        return 0;
    }
    /* The block enters each tag multiplied by that key's H^2. */
    gf_multiply(first_hash, first_hash, coefficient);
    gf_multiply(second_hash, second_hash, square);
    for (index = 0U; index < BLOCK; ++index) {
        coefficient[index] ^= square[index];
        first_tag[index] ^= second_tag[index];
    }
    gf_invert(coefficient, coefficient);
    gf_multiply(first_tag, coefficient, ciphertext);
    return gcm_tag(first, nonce, aad, aad_len, ciphertext, first_hash,
                   tag) &&
           gcm_tag(second, nonce, aad, aad_len, ciphertext, second_hash,
                   second_tag) &&
           memcmp(tag, second_tag, BLOCK) == 0;
}

static int opens(const unsigned char *container, size_t length,
                 const unsigned char *secret, size_t secret_len,
                 unsigned int *version)
{
    unsigned char *pem = NULL;
    size_t pem_len = 0U;
    int success = protected_private_key_decode_version(
        container, length, secret, secret_len, &pem, &pem_len, version);

    if (success) {
        OPENSSL_clear_free(pem, pem_len);
    }
    return success;
}

static int gcm_seal(const unsigned char key[KEY_SIZE],
                    const unsigned char nonce[NKPR_NONCE_SIZE],
                    const unsigned char *aad, size_t aad_len,
                    const unsigned char *plaintext, size_t plaintext_len,
                    unsigned char *ciphertext, unsigned char *tag)
{
    EVP_CIPHER_CTX *context = EVP_CIPHER_CTX_new();
    int length = 0;
    int success =
        context != NULL &&
        EVP_EncryptInit_ex(context, EVP_aes_256_gcm(), NULL, key, nonce) ==
            1 &&
        EVP_EncryptUpdate(context, NULL, &length, aad, (int)aad_len) == 1 &&
        EVP_EncryptUpdate(context, ciphertext, &length, plaintext,
                          (int)plaintext_len) == 1 &&
        EVP_EncryptFinal_ex(context, ciphertext + length, &length) == 1 &&
        EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_GET_TAG,
                            (int)NKPR_TAG_SIZE, tag) == 1;

    EVP_CIPHER_CTX_free(context);
    return success;
}

/* Core derives the independently computed master, key and commitment. */
static int test_known_answers(void)
{
    static const unsigned char plaintext[] = "known-answer plaintext";
    unsigned char container[MAX_CONTAINER] = {0};
    unsigned char salt[NKPR_SALT_SIZE];
    unsigned char master[MASTER_SIZE];
    unsigned char key[KEY_SIZE];
    unsigned char commitment[NKPR_COMMITMENT_SIZE];
    unsigned char *pem = NULL;
    size_t pem_len = 0U;
    size_t plaintext_len = sizeof(plaintext) - 1U;
    size_t length = V3_AAD_SIZE + plaintext_len + NKPR_TAG_SIZE;
    unsigned int version = 0U;
    size_t index;

    for (index = 0U; index < sizeof(salt); ++index) {
        salt[index] = (unsigned char)index;
    }
    CHECK(argon2id(kat_password, sizeof(kat_password) - 1U, salt, master,
                   sizeof(master)));
    CHECK(memcmp(master, kat_master, sizeof(master)) == 0);
    CHECK(hkdf(master, salt, cipher_info, key));
    CHECK(memcmp(key, kat_cipher_key, sizeof(key)) == 0);
    CHECK(hkdf(master, salt, commitment_info, commitment));
    CHECK(memcmp(commitment, kat_commitment, sizeof(commitment)) == 0);

    /* A container built only from the known answers. */
    put_header(container, NKPR_VERSION_COMMITTED, plaintext_len);
    memcpy(container + NKPR_HEADER_SIZE, salt, sizeof(salt));
    memcpy(container + NKPR_HEADER_SIZE + NKPR_SALT_SIZE, kat_commitment,
           sizeof(kat_commitment));
    memset(container + V3_AAD_SIZE - NKPR_NONCE_SIZE, 0xa5,
           NKPR_NONCE_SIZE);
    CHECK(gcm_seal(kat_cipher_key,
                   container + V3_AAD_SIZE - NKPR_NONCE_SIZE, container,
                   V3_AAD_SIZE, plaintext, plaintext_len,
                   container + V3_AAD_SIZE,
                   container + V3_AAD_SIZE + plaintext_len));
    CHECK(protected_private_key_container_version(container, length) ==
          NKPR_VERSION_COMMITTED);
    CHECK(protected_private_key_decode_version(
        container, length, kat_password, sizeof(kat_password) - 1U, &pem,
        &pem_len, &version));
    CHECK(version == NKPR_VERSION_COMMITTED && pem_len == plaintext_len &&
          memcmp(pem, plaintext, plaintext_len) == 0);
    OPENSSL_clear_free(pem, pem_len);
    CHECK(!opens(container, length, password, sizeof(password) - 1U,
                 &version));

    /* Every authenticated part is bound: commitment, salt, nonce, header. */
    container[NKPR_HEADER_SIZE + NKPR_SALT_SIZE] ^= 1U;
    CHECK(!opens(container, length, kat_password,
                 sizeof(kat_password) - 1U, &version));
    container[NKPR_HEADER_SIZE + NKPR_SALT_SIZE] ^= 1U;
    container[NKPR_HEADER_SIZE] ^= 1U;
    CHECK(!opens(container, length, kat_password,
                 sizeof(kat_password) - 1U, &version));
    container[NKPR_HEADER_SIZE] ^= 1U;
    container[V3_AAD_SIZE - 1U] ^= 1U;
    CHECK(!opens(container, length, kat_password,
                 sizeof(kat_password) - 1U, &version));
    container[V3_AAD_SIZE - 1U] ^= 1U;
    container[length - 1U] ^= 1U;
    CHECK(!opens(container, length, kat_password,
                 sizeof(kat_password) - 1U, &version));
    container[length - 1U] ^= 1U;
    CHECK(opens(container, length, kat_password, sizeof(kat_password) - 1U,
                &version));

    /* The version byte alone never converts a container. */
    container[4] = NKPR_VERSION_IDENTITY;
    CHECK(protected_private_key_container_version(container, length) == 0U);
    CHECK(!opens(container, length, kat_password,
                 sizeof(kat_password) - 1U, &version));
    return 1;
}

/*
 * One NKPR v2 file opens under two passwords. Relabelled or rebuilt as
 * NKPR v3 it opens under at most the one password it commits to.
 */
static int test_two_password_file(void)
{
    unsigned char container[MAX_CONTAINER] = {0};
    unsigned char first_master[MASTER_SIZE];
    unsigned char second_master[MASTER_SIZE];
    unsigned char first_key[KEY_SIZE];
    unsigned char second_key[KEY_SIZE];
    unsigned char commitment[NKPR_COMMITMENT_SIZE];
    unsigned char *salt = container + NKPR_HEADER_SIZE;
    unsigned char *nonce;
    size_t length = V2_AAD_SIZE + BLOCK + NKPR_TAG_SIZE;
    unsigned int version = 0U;

    /* NKPR v2: the Argon2id output is the AES key. */
    put_header(container, NKPR_VERSION_IDENTITY, BLOCK);
    memset(salt, 0x5a, NKPR_SALT_SIZE);
    nonce = salt + NKPR_SALT_SIZE;
    memset(nonce, 0x3c, NKPR_NONCE_SIZE);
    CHECK(argon2id(password, sizeof(password) - 1U, salt, first_key,
                   sizeof(first_key)));
    CHECK(argon2id(other_password, sizeof(other_password) - 1U, salt,
                   second_key, sizeof(second_key)));
    CHECK(gcm_two_key_collision(first_key, second_key, nonce, container,
                                V2_AAD_SIZE, container + V2_AAD_SIZE,
                                container + V2_AAD_SIZE + BLOCK));
    CHECK(opens(container, length, password, sizeof(password) - 1U,
                &version));
    CHECK(version == NKPR_VERSION_IDENTITY);
    CHECK(opens(container, length, other_password,
                sizeof(other_password) - 1U, &version));
    CHECK(!opens(container, length, kat_password,
                 sizeof(kat_password) - 1U, &version));

    /* Relabelled as v3 the structure no longer fits. */
    container[4] = NKPR_VERSION_COMMITTED;
    CHECK(protected_private_key_container_version(container, length) == 0U);
    CHECK(!opens(container, length, password, sizeof(password) - 1U,
                 &version));
    CHECK(!opens(container, length, other_password,
                 sizeof(other_password) - 1U, &version));

    /*
     * Rebuilt as v3 by an attacker who knows both passwords: the tag is
     * forged for both derived AES keys, but the file can carry only one
     * commitment.
     */
    memset(container, 0, sizeof(container));
    length = V3_AAD_SIZE + BLOCK + NKPR_TAG_SIZE;
    put_header(container, NKPR_VERSION_COMMITTED, BLOCK);
    memset(salt, 0x5a, NKPR_SALT_SIZE);
    nonce = salt + NKPR_SALT_SIZE + NKPR_COMMITMENT_SIZE;
    memset(nonce, 0x3c, NKPR_NONCE_SIZE);
    CHECK(argon2id(password, sizeof(password) - 1U, salt, first_master,
                   sizeof(first_master)));
    CHECK(argon2id(other_password, sizeof(other_password) - 1U, salt,
                   second_master, sizeof(second_master)));
    CHECK(hkdf(first_master, salt, cipher_info, first_key));
    CHECK(hkdf(second_master, salt, cipher_info, second_key));
    CHECK(hkdf(first_master, salt, commitment_info, commitment));
    memcpy(salt + NKPR_SALT_SIZE, commitment, sizeof(commitment));
    CHECK(gcm_two_key_collision(first_key, second_key, nonce, container,
                                V3_AAD_SIZE, container + V3_AAD_SIZE,
                                container + V3_AAD_SIZE + BLOCK));
    CHECK(opens(container, length, password, sizeof(password) - 1U,
                &version));
    CHECK(version == NKPR_VERSION_COMMITTED);
    CHECK(!opens(container, length, other_password,
                 sizeof(other_password) - 1U, &version));
    return 1;
}

static int read_file(const char *path, unsigned char **bytes, size_t *length)
{
    return file_read_regular(path, NKPR_MAX_CONTAINER_SIZE, bytes, length);
}

static int same_file(const char *path, const unsigned char *bytes,
                     size_t length)
{
    unsigned char *current = NULL;
    size_t current_len = 0U;
    int same = read_file(path, &current, &current_len) &&
               current_len == length && memcmp(current, bytes, length) == 0;

    OPENSSL_free(current);
    return same;
}

/* The length of the first two PEM blocks of a four-component key. */
static size_t two_component_length(const unsigned char *pem, size_t pem_len)
{
    static const char begin[] = "-----BEGIN";
    size_t found = 0U;
    size_t offset;

    for (offset = 0U; offset + sizeof(begin) - 1U <= pem_len; ++offset) {
        if (memcmp(pem + offset, begin, sizeof(begin) - 1U) == 0 &&
            ++found == 3U) {
            return offset;
        }
    }
    return 0U;
}

/* New keys are v3; v1, v2 and v3 all read; only v2 is rewritten as v3. */
static int test_versions_and_upgrade(void)
{
    char fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE];
    char upgraded[NEKOKEM_FINGERPRINT_STRING_SIZE];
    unsigned char *file = NULL;
    unsigned char *pem = NULL;
    unsigned char *copy = NULL;
    unsigned char key[KEY_SIZE];
    unsigned char commitment[NKPR_COMMITMENT_SIZE];
    size_t file_len = 0U;
    size_t pem_len = 0U;
    size_t copy_len = 0U;
    size_t legacy_len;
    unsigned int version = 0U;
    struct stat status;

    CHECK(nekokem_generate_keypair("a.pub", "a.key", password,
                                   sizeof(password) - 1U));
    CHECK(nekokem_private_key_format_version("a.key") ==
          NEKOKEM_NKPR_VERSION_COMMITTED);
    CHECK(nekokem_private_key_format_version("a.pub") == 0);
    CHECK(nekokem_private_key_format_version("missing.key") == 0);
    CHECK(nekokem_private_key_format_version(NULL) == 0);
    CHECK(nekokem_public_key_fingerprint("a.pub", fingerprint,
                                         sizeof(fingerprint)));

    /* The stored commitment is the one this password derives. */
    CHECK(read_file("a.key", &file, &file_len));
    CHECK(file_len > V3_AAD_SIZE + NKPR_TAG_SIZE);
    CHECK(v3_keys(password, sizeof(password) - 1U, file + NKPR_HEADER_SIZE,
                  key, commitment));
    CHECK(memcmp(file + NKPR_HEADER_SIZE + NKPR_SALT_SIZE, commitment,
                 sizeof(commitment)) == 0);
    CHECK(memcmp(commitment, key, sizeof(key)) != 0);

    /* A v3 key is already current. */
    CHECK(!nekokem_upgrade_private_key_format("a.key", password,
                                              sizeof(password) - 1U));
    CHECK(same_file("a.key", file, file_len));
    OPENSSL_free(file);
    file = NULL;

    /* The same keys as an NKPR v2 file from NekoKEM 5.0 to 5.2. */
    CHECK(protected_private_key_read_version("a.key", password,
                                             sizeof(password) - 1U, &pem,
                                             &pem_len, &version));
    CHECK(version == NKPR_VERSION_COMMITTED);
    CHECK(protected_private_key_write_version("b.key", pem, pem_len,
                                              password,
                                              sizeof(password) - 1U,
                                              NKPR_VERSION_IDENTITY));
    CHECK(nekokem_private_key_format_version("b.key") ==
          NEKOKEM_NKPR_VERSION_IDENTITY);
    CHECK(nekokem_check_private_key_password("b.key", password,
                                             sizeof(password) - 1U));
    CHECK(read_file("b.key", &file, &file_len));

    /* Refusals leave the file as it was. */
    CHECK(!nekokem_upgrade_private_key_format("b.key", other_password,
                                              sizeof(other_password) - 1U));
    CHECK(!nekokem_upgrade_private_key_format("b.key", NULL, 0U));
    CHECK(!nekokem_upgrade_private_key_format("", password,
                                              sizeof(password) - 1U));
    CHECK(!nekokem_upgrade_private_key_format("missing.key", password,
                                              sizeof(password) - 1U));
    CHECK(same_file("b.key", file, file_len));
    OPENSSL_free(file);
    file = NULL;

    CHECK(nekokem_upgrade_private_key_format("b.key", password,
                                             sizeof(password) - 1U));
    CHECK(nekokem_private_key_format_version("b.key") ==
          NEKOKEM_NKPR_VERSION_COMMITTED);
    CHECK(stat("b.key", &status) == 0 &&
          (status.st_mode & (mode_t)0777) == (mode_t)0600);
    CHECK(nekokem_check_private_key_password("b.key", password,
                                             sizeof(password) - 1U));
    CHECK(!nekokem_check_private_key_password(
        "b.key", other_password, sizeof(other_password) - 1U));
    CHECK(protected_private_key_read_version("b.key", password,
                                             sizeof(password) - 1U, &copy,
                                             &copy_len, &version));
    CHECK(version == NKPR_VERSION_COMMITTED && copy_len == pem_len &&
          memcmp(copy, pem, pem_len) == 0);
    OPENSSL_clear_free(copy, copy_len);
    CHECK(!nekokem_upgrade_private_key_format("b.key", password,
                                              sizeof(password) - 1U));
    /* The upgraded key still belongs to the same public key. */
    CHECK(nekokem_encrypt_file("a.pub", "a.nkem", "a.pub"));
    CHECK(nekokem_decrypt_file("a.nkem", "a.out", "b.key", password,
                               sizeof(password) - 1U));

    /* A two-component NKPR v1 key reads, and needs the identity upgrade. */
    legacy_len = two_component_length(pem, pem_len);
    CHECK(legacy_len != 0U);
    CHECK(protected_private_key_write_version("c.key", pem, legacy_len,
                                              password,
                                              sizeof(password) - 1U,
                                              NKPR_VERSION));
    CHECK(nekokem_private_key_format_version("c.key") ==
          NEKOKEM_NKPR_VERSION_LEGACY);
    CHECK(nekokem_check_private_key_password("c.key", password,
                                             sizeof(password) - 1U));
    CHECK(nekokem_decrypt_file("a.nkem", "c.out", "c.key", password,
                               sizeof(password) - 1U));
    CHECK(read_file("c.key", &file, &file_len));
    CHECK(!nekokem_upgrade_private_key_format("c.key", password,
                                              sizeof(password) - 1U));
    CHECK(same_file("c.key", file, file_len));
    OPENSSL_free(file);
    CHECK(nekokem_export_legacy_public_key("a.pub", "c.pub"));
    CHECK(nekokem_upgrade_to_identity_keys("c.pub", "c.key", password,
                                           sizeof(password) - 1U));
    CHECK(nekokem_private_key_format_version("c.key") ==
          NEKOKEM_NKPR_VERSION_COMMITTED);
    CHECK(nekokem_public_key_fingerprint("c.pub", upgraded,
                                         sizeof(upgraded)));
    CHECK(strcmp(upgraded, fingerprint) != 0);
    CHECK(nekokem_decrypt_file("a.nkem", "d.out", "c.key", password,
                               sizeof(password) - 1U));

    /* Two components under a four-component version are rejected. */
    CHECK(protected_private_key_write_version("d.key", pem, legacy_len,
                                              password,
                                              sizeof(password) - 1U,
                                              NKPR_VERSION_COMMITTED));
    CHECK(!nekokem_check_private_key_password("d.key", password,
                                              sizeof(password) - 1U));
    OPENSSL_clear_free(pem, pem_len);
    return 1;
}

static const char *const fixtures[] = {
    "a.pub", "a.key", "a.nkem", "a.out", "b.key", "c.pub", "c.key",
    "c.out", "d.key", "d.out",
};

int main(void)
{
    char directory[] = "/tmp/nekokem-key-commitment.XXXXXX";
    size_t index;
    int success;

    if (mkdtemp(directory) == NULL || chdir(directory) != 0) {
        perror("Cannot create test directory");
        return EXIT_FAILURE;
    }
    success = test_known_answers() && test_two_password_file() &&
              test_versions_and_upgrade();
    for (index = 0U; index < sizeof(fixtures) / sizeof(fixtures[0]);
         ++index) {
        (void)unlink(fixtures[index]);
    }
    if (chdir("/") == 0) {
        (void)rmdir(directory);
    }
    if (!success) {
        fprintf(stderr, "NKPR v3 key commitment tests failed\n");
        return EXIT_FAILURE;
    }
    puts("NKPR v3 key commitment tests passed");
    return EXIT_SUCCESS;
}
