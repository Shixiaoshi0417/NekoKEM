/* NKEM v4 multi-recipient round trips, limits and tamper rejection. */
#include "nekokem.h"
#include "file.h"

#include <openssl/bio.h>
#include <openssl/buffer.h>
#include <openssl/core_names.h>
#include <openssl/crypto.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/params.h>
#include <openssl/pem.h>
#include <openssl/rand.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifndef _WIN32
#include <unistd.h>
#endif

#define CHECK(condition)                                                  \
    do {                                                                  \
        if (!(condition)) {                                               \
            fprintf(stderr, "Check failed at line %d: %s\n", __LINE__,    \
                    #condition);                                          \
            return 0;                                                     \
        }                                                                 \
    } while (0)

#define KEY_COUNT (NEKOKEM_MAX_RECIPIENTS + 1U)
#define DATA_SIZE (3U * 65536U + 1234U)
#define ENTRY_OFFSET(index) \
    (NKEM_V4_HEADER_SIZE + NKEM_V4_SALT_SIZE + (index) * NKEM_V4_ENTRY_SIZE)

static const unsigned char password[] = "public-test-multi-recipient";
static char public_paths[KEY_COUNT][32];
static char private_paths[KEY_COUNT][32];

static int write_private(const char *path, const void *bytes, size_t length)
{
    AtomicFile output = {0};
    int success = atomic_file_open(&output, path, 0600) &&
                  file_write_all(output.stream, bytes, length) &&
                  atomic_file_commit(&output);

    atomic_file_abort(&output);
    return success;
}

static int read_all(const char *path, unsigned char **bytes, size_t *length)
{
    return file_read_regular(path, 64U * 1024U * 1024U, bytes, length);
}

static int file_is_empty(const char *path)
{
    FILE *stream = fopen(path, "rb");
    int empty = stream != NULL && fgetc(stream) == EOF && ferror(stream) == 0;

    if (stream != NULL) {
        (void)fclose(stream);
    }
    return empty;
}

static int file_equals(const char *path, const unsigned char *bytes,
                       size_t length)
{
    unsigned char *actual = NULL;
    size_t actual_len = 0U;
    int equal;

    /* Core's regular-file reader rejects empty files by design. */
    if (length == 0U) {
        return file_is_empty(path);
    }
    equal = read_all(path, &actual, &actual_len) && actual_len == length &&
            memcmp(actual, bytes, length) == 0;
    OPENSSL_free(actual);
    return equal;
}

static int exists(const char *path)
{
    FILE *stream = fopen(path, "rb");

    if (stream == NULL) {
        return 0;
    }
    (void)fclose(stream);
    return 1;
}

/* Fast plaintext-PEM Hybrid keys; recipient 0 uses Core's NKPR key instead. */
static int make_plain_keypair(size_t index)
{
    EVP_PKEY *x448 = EVP_PKEY_Q_keygen(NULL, NULL, "X448");
    EVP_PKEY *mlkem = EVP_PKEY_Q_keygen(NULL, NULL, "ML-KEM-1024");
    BIO *public_bio = BIO_new(BIO_s_mem());
    BIO *private_bio = BIO_new(BIO_s_mem());
    BUF_MEM *public_pem = NULL;
    BUF_MEM *private_pem = NULL;
    int success = x448 != NULL && mlkem != NULL && public_bio != NULL &&
                  private_bio != NULL &&
                  PEM_write_bio_PUBKEY(public_bio, x448) == 1 &&
                  PEM_write_bio_PUBKEY(public_bio, mlkem) == 1 &&
                  PEM_write_bio_PrivateKey(private_bio, x448, NULL, NULL, 0,
                                           NULL, NULL) == 1 &&
                  PEM_write_bio_PrivateKey(private_bio, mlkem, NULL, NULL, 0,
                                           NULL, NULL) == 1 &&
                  BIO_get_mem_ptr(public_bio, &public_pem) > 0 &&
                  BIO_get_mem_ptr(private_bio, &private_pem) > 0 &&
                  write_private(public_paths[index], public_pem->data,
                                public_pem->length) &&
                  write_private(private_paths[index], private_pem->data,
                                private_pem->length);

    if (private_pem != NULL) {
        OPENSSL_cleanse(private_pem->data, private_pem->max);
    }
    BIO_free(public_bio);
    BIO_free(private_bio);
    EVP_PKEY_free(x448);
    EVP_PKEY_free(mlkem);
    return success;
}

static int decrypt_as(size_t index, const char *input, const char *output)
{
    return index == 0U
               ? nekokem_decrypt_file(input, output, private_paths[0],
                                      password, sizeof(password) - 1U)
               : nekokem_decrypt_file(input, output, private_paths[index],
                                      NULL, 0U);
}

static int encrypt_to(const char *input, const char *output,
                      const char *const *keys, size_t count)
{
    return nekokem_encrypt_file_multi_with_progress(
        input, output, keys, count, NULL, NULL);
}

/* Writes a copy of source with one byte XORed, or with bytes appended/removed. */
static int mutate(const char *source, const char *destination,
                  size_t offset, unsigned char mask, long resize)
{
    unsigned char *bytes = NULL;
    size_t length = 0U;
    int success = 0;

    if (!read_all(source, &bytes, &length)) {
        return 0;
    }
    if (resize < 0) {
        length -= (size_t)(-resize);
    } else if (resize > 0) {
        unsigned char *grown = OPENSSL_realloc(bytes, length + (size_t)resize);

        if (grown == NULL) {
            goto cleanup;
        }
        bytes = grown;
        memset(bytes + length, 0x5a, (size_t)resize);
        length += (size_t)resize;
    }
    if (offset < length) {
        bytes[offset] ^= mask;
    }
    success = write_private(destination, bytes, length);

cleanup:
    OPENSSL_free(bytes);
    return success;
}

static int swap_entries(const char *source, const char *destination)
{
    unsigned char *bytes = NULL;
    unsigned char entry[NKEM_V4_ENTRY_SIZE];
    size_t length = 0U;
    int success;

    if (!read_all(source, &bytes, &length)) {
        return 0;
    }
    memcpy(entry, bytes + ENTRY_OFFSET(0U), sizeof(entry));
    memmove(bytes + ENTRY_OFFSET(0U), bytes + ENTRY_OFFSET(1U), sizeof(entry));
    memcpy(bytes + ENTRY_OFFSET(1U), entry, sizeof(entry));
    success = write_private(destination, bytes, length);
    OPENSSL_free(bytes);
    return success;
}

static void put_be(unsigned char *output, uint64_t value, size_t length)
{
    while (length-- > 0U) {
        output[length] = (unsigned char)(value & 0xffU);
        value >>= 8U;
    }
}

/* The fixed header exactly as docs/NKEM-v4.md lists it, without Core's encoder. */
static void spec_header(unsigned char header[NKEM_V4_HEADER_SIZE],
                        size_t count, uint64_t ciphertext_len)
{
    memset(header, 0, NKEM_V4_HEADER_SIZE);
    memcpy(header, "NKEM", 4U);
    header[4] = 4U;  /* version */
    header[5] = 4U;  /* algorithm ID */
    put_be(header + 6, 32U, 2U);
    put_be(header + 8, count, 2U);
    put_be(header + 10, 1672U, 2U);
    put_be(header + 16, ciphertext_len, 8U);
    header[24] = 32U;  /* salt */
    header[25] = 12U;  /* nonce */
    header[26] = 16U;  /* tag */
    header[27] = 64U;  /* header MAC; bytes 12-15 and 28-31 stay zero */
}

/* Two recipients and DATA_SIZE bytes of ciphertext, written out by hand. */
static const unsigned char two_recipient_header[NKEM_V4_HEADER_SIZE] = {
    'N', 'K', 'E', 'M', 0x04, 0x04, 0x00, 0x20,
    0x00, 0x02, 0x06, 0x88, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0x04, 0xd2,
    0x20, 0x0c, 0x10, 0x40, 0x00, 0x00, 0x00, 0x00};

/* Little-endian X448 scalar 4 * subgroup order; clamping leaves it unchanged. */
static const unsigned char degenerate_x448[56] = {
    0xcc, 0x13, 0x61, 0xad, 0x4a, 0x0a, 0xe3, 0x8d,
    0x54, 0x3d, 0x16, 0x37, 0xca, 0x09, 0xb3, 0x85,
    0x40, 0xda, 0x58, 0xbb, 0x26, 0x6d, 0x3b, 0x11,
    0xa7, 0x8f, 0x28, 0xf3, 0xfd, 0xff, 0xff, 0xff,
    0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
    0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
    0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
};

static EVP_PKEY *read_public(const char *path, const char *algorithm)
{
    BIO *input = BIO_new_file(path, "rb");
    EVP_PKEY *key = NULL;

    while (input != NULL && key == NULL) {
        EVP_PKEY *next = PEM_read_bio_PUBKEY(input, NULL, NULL, NULL);

        if (next == NULL) {
            break;
        }
        if (EVP_PKEY_is_a(next, algorithm) == 1) {
            key = next;
        } else {
            EVP_PKEY_free(next);
        }
    }
    BIO_free(input);
    /* Reading past the last PEM block leaves an error Core would print. */
    ERR_clear_error();
    return key;
}

/* A valid public-key file with the X448 key of one file and the ML-KEM key of another. */
static int splice_public(const char *x448_from, const char *mlkem_from,
                         const char *output)
{
    EVP_PKEY *x448 = read_public(x448_from, "X448");
    EVP_PKEY *mlkem = read_public(mlkem_from, "ML-KEM-1024");
    BIO *pem = BIO_new(BIO_s_mem());
    BUF_MEM *buffer = NULL;
    int success = x448 != NULL && mlkem != NULL && pem != NULL &&
                  PEM_write_bio_PUBKEY(pem, x448) == 1 &&
                  PEM_write_bio_PUBKEY(pem, mlkem) == 1 &&
                  BIO_get_mem_ptr(pem, &buffer) > 0 &&
                  write_private(output, buffer->data, buffer->length);

    BIO_free(pem);
    EVP_PKEY_free(x448);
    EVP_PKEY_free(mlkem);
    return success;
}

/* A private-key file with the degenerate X448 key and a valid ML-KEM key. */
static int write_degenerate_private(const char *mlkem_from, const char *output)
{
    EVP_PKEY *x448 = EVP_PKEY_new_raw_private_key_ex(
        NULL, "X448", NULL, degenerate_x448, sizeof(degenerate_x448));
    EVP_PKEY *mlkem = NULL;
    BIO *input = BIO_new_file(mlkem_from, "rb");
    BIO *pem = BIO_new(BIO_s_mem());
    BUF_MEM *buffer = NULL;
    int success;

    while (input != NULL && mlkem == NULL) {
        EVP_PKEY *next = PEM_read_bio_PrivateKey(input, NULL, NULL, NULL);

        if (next == NULL) {
            break;
        }
        if (EVP_PKEY_is_a(next, "ML-KEM-1024") == 1) {
            mlkem = next;
        } else {
            EVP_PKEY_free(next);
        }
    }
    ERR_clear_error();
    success = x448 != NULL && mlkem != NULL && pem != NULL &&
              PEM_write_bio_PrivateKey(pem, x448, NULL, NULL, 0, NULL, NULL) == 1 &&
              PEM_write_bio_PrivateKey(pem, mlkem, NULL, NULL, 0, NULL, NULL) == 1 &&
              BIO_get_mem_ptr(pem, &buffer) > 0 &&
              write_private(output, buffer->data, buffer->length);
    if (buffer != NULL) {
        OPENSSL_cleanse(buffer->data, buffer->max);
    }
    BIO_free(input);
    BIO_free(pem);
    EVP_PKEY_free(x448);
    EVP_PKEY_free(mlkem);
    return success;
}

static int hkdf_sha512(const unsigned char *key, size_t key_len,
                       const unsigned char *salt, const char *label,
                       const unsigned char *suffix, size_t suffix_len,
                       unsigned char *output, size_t output_len)
{
    unsigned char info[256];
    size_t label_len = strlen(label);
    EVP_KDF *kdf = EVP_KDF_fetch(NULL, "HKDF", NULL);
    EVP_KDF_CTX *context = kdf != NULL ? EVP_KDF_CTX_new(kdf) : NULL;
    OSSL_PARAM parameters[5];
    int success;

    if (label_len + suffix_len > sizeof(info)) {
        EVP_KDF_CTX_free(context);
        EVP_KDF_free(kdf);
        return 0;
    }
    memcpy(info, label, label_len);
    if (suffix_len > 0U) {
        memcpy(info + label_len, suffix, suffix_len);
    }
    parameters[0] = OSSL_PARAM_construct_utf8_string(
        OSSL_KDF_PARAM_DIGEST, (char *)"SHA512", 0U);
    parameters[1] = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_KEY, (void *)key, key_len);
    parameters[2] = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_SALT, (void *)salt, NKEM_V4_SALT_SIZE);
    parameters[3] = OSSL_PARAM_construct_octet_string(
        OSSL_KDF_PARAM_INFO, info, label_len + suffix_len);
    parameters[4] = OSSL_PARAM_construct_end();
    success = context != NULL &&
              EVP_KDF_derive(context, output, output_len, parameters) == 1;
    EVP_KDF_CTX_free(context);
    EVP_KDF_free(kdf);
    return success;
}

/* One-shot AES-256-GCM with a 12-byte nonce; output receives length bytes. */
static int gcm_seal(const unsigned char key[32], const unsigned char *nonce,
                    const unsigned char *aad, size_t aad_len,
                    const unsigned char *input, size_t length,
                    unsigned char *output, unsigned char tag[NKEM_TAG_SIZE])
{
    EVP_CIPHER_CTX *context = EVP_CIPHER_CTX_new();
    unsigned char final_block[EVP_MAX_BLOCK_LENGTH];
    int written = 0;
    int success = context != NULL &&
                  EVP_EncryptInit_ex(context, EVP_aes_256_gcm(), NULL, key,
                                     nonce) == 1 &&
                  EVP_EncryptUpdate(context, NULL, &written, aad,
                                    (int)aad_len) == 1 &&
                  (length == 0U ||
                   EVP_EncryptUpdate(context, output, &written, input,
                                     (int)length) == 1) &&
                  EVP_EncryptFinal_ex(context, final_block, &written) == 1 &&
                  EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_GET_TAG,
                                      (int)NKEM_TAG_SIZE, tag) == 1;

    EVP_CIPHER_CTX_free(context);
    return success;
}

/* One recipient entry, written from docs/NKEM-v4.md without Core's code. */
static int seal_entry(const char *public_path,
                      const unsigned char prefix[NKEM_V4_HEADER_SIZE + NKEM_V4_SALT_SIZE],
                      const unsigned char file_key[NKEM_V4_FILE_KEY_SIZE],
                      unsigned char entry[NKEM_V4_ENTRY_SIZE])
{
    static const unsigned char zero_nonce[NKEM_NONCE_SIZE] = {0};
    const size_t prefix_len = NKEM_V4_HEADER_SIZE + NKEM_V4_SALT_SIZE;
    const size_t wrapped = 56U + 1568U;
    EVP_PKEY *x448 = read_public(public_path, "X448");
    EVP_PKEY *mlkem = read_public(public_path, "ML-KEM-1024");
    EVP_PKEY *ephemeral = EVP_PKEY_Q_keygen(NULL, NULL, "X448");
    EVP_PKEY_CTX *context = NULL;
    unsigned char secret[56U + 32U];
    unsigned char keys[2U * 56U];
    unsigned char wrap_key[32];
    unsigned char aad[NKEM_V4_HEADER_SIZE + NKEM_V4_SALT_SIZE + 56U + 1568U];
    size_t length = 56U;
    size_t ciphertext_len = 1568U;
    size_t mlkem_len = 32U;
    int success = 0;

    if (x448 == NULL || mlkem == NULL || ephemeral == NULL ||
        EVP_PKEY_get_raw_public_key(ephemeral, entry, &length) != 1 ||
        length != 56U) {
        goto cleanup;
    }
    length = 56U;
    if (EVP_PKEY_get_raw_public_key(x448, keys + 56U, &length) != 1 ||
        length != 56U) {
        goto cleanup;
    }
    memcpy(keys, entry, 56U);
    context = EVP_PKEY_CTX_new_from_pkey(NULL, ephemeral, NULL);
    length = 56U;
    if (context == NULL || EVP_PKEY_derive_init(context) != 1 ||
        EVP_PKEY_derive_set_peer(context, x448) != 1 ||
        EVP_PKEY_derive(context, secret, &length) != 1 || length != 56U) {
        goto cleanup;
    }
    EVP_PKEY_CTX_free(context);
    context = EVP_PKEY_CTX_new_from_pkey(NULL, mlkem, NULL);
    if (context == NULL || EVP_PKEY_encapsulate_init(context, NULL) != 1 ||
        EVP_PKEY_encapsulate(context, entry + 56U, &ciphertext_len,
                             secret + 56U, &mlkem_len) != 1 ||
        ciphertext_len != 1568U || mlkem_len != 32U) {
        goto cleanup;
    }
    memcpy(aad, prefix, prefix_len);
    memcpy(aad + prefix_len, entry, wrapped);
    success = hkdf_sha512(secret, sizeof(secret), prefix + NKEM_V4_HEADER_SIZE,
                          "NekoKEM v4 recipient wrap X448-MLKEM1024",
                          keys, sizeof(keys), wrap_key, sizeof(wrap_key)) &&
              gcm_seal(wrap_key, zero_nonce, aad, sizeof(aad), file_key,
                       NKEM_V4_FILE_KEY_SIZE, entry + wrapped,
                       entry + wrapped + NKEM_V4_FILE_KEY_SIZE);

cleanup:
    OPENSSL_cleanse(secret, sizeof(secret));
    OPENSSL_cleanse(wrap_key, sizeof(wrap_key));
    EVP_PKEY_CTX_free(context);
    EVP_PKEY_free(x448);
    EVP_PKEY_free(mlkem);
    EVP_PKEY_free(ephemeral);
    return success;
}

/*
 * An independent NKEM v4 encoder playing a sender that knows the file key.
 * Entry `malformed` gets an all-zero X448 key, which no correct encryptor
 * writes, under a valid header MAC and payload; SIZE_MAX writes none.
 */
static int build_container(const char *const *keys, size_t count,
                           size_t malformed, const unsigned char *data,
                           const char *output)
{
    const size_t entries = NKEM_V4_HEADER_SIZE + NKEM_V4_SALT_SIZE;
    const size_t mac_offset = entries + count * NKEM_V4_ENTRY_SIZE;
    const size_t metadata_len = mac_offset + NKEM_V4_MAC_SIZE + NKEM_NONCE_SIZE;
    const size_t total = metadata_len + DATA_SIZE + NKEM_TAG_SIZE;
    unsigned char *bytes = OPENSSL_zalloc(total);
    unsigned char *salt = bytes + NKEM_V4_HEADER_SIZE;
    unsigned char file_key[NKEM_V4_FILE_KEY_SIZE];
    unsigned char payload_key[32];
    unsigned char mac_key[64];
    size_t mac_len = 0U;
    size_t index;
    int success = 0;

    if (bytes == NULL) {
        return 0;
    }
    spec_header(bytes, count, DATA_SIZE);
    if (RAND_bytes(file_key, (int)sizeof(file_key)) != 1 ||
        RAND_bytes(salt, (int)NKEM_V4_SALT_SIZE) != 1 ||
        RAND_bytes(bytes + mac_offset + NKEM_V4_MAC_SIZE,
                   (int)NKEM_NONCE_SIZE) != 1) {
        goto cleanup;
    }
    for (index = 0U; index < count; ++index) {
        unsigned char *entry = bytes + entries + index * NKEM_V4_ENTRY_SIZE;

        if (index == malformed) {
            if (RAND_bytes(entry + 56U, (int)(NKEM_V4_ENTRY_SIZE - 56U)) != 1) {
                goto cleanup;
            }
        } else if (!seal_entry(keys[index], bytes, file_key, entry)) {
            goto cleanup;
        }
    }
    success = hkdf_sha512(file_key, sizeof(file_key), salt,
                          "NekoKEM v4 payload AES-256-GCM", NULL, 0U,
                          payload_key, sizeof(payload_key)) &&
              hkdf_sha512(file_key, sizeof(file_key), salt,
                          "NekoKEM v4 header HMAC-SHA512", NULL, 0U,
                          mac_key, sizeof(mac_key)) &&
              EVP_Q_mac(NULL, "HMAC", NULL, "SHA512", NULL, mac_key,
                        sizeof(mac_key), bytes, mac_offset,
                        bytes + mac_offset, NKEM_V4_MAC_SIZE,
                        &mac_len) != NULL &&
              mac_len == NKEM_V4_MAC_SIZE &&
              gcm_seal(payload_key, bytes + mac_offset + NKEM_V4_MAC_SIZE,
                       bytes, metadata_len, data, DATA_SIZE,
                       bytes + metadata_len,
                       bytes + metadata_len + DATA_SIZE) &&
              write_private(output, bytes, total);

cleanup:
    OPENSSL_cleanse(file_key, sizeof(file_key));
    OPENSSL_cleanse(payload_key, sizeof(payload_key));
    OPENSSL_cleanse(mac_key, sizeof(mac_key));
    OPENSSL_free(bytes);
    return success;
}

#ifndef _WIN32
/* Decrypts with stderr captured; 1 when it failed, printed and wrote nothing. */
static int failure_message(const char *key, const char *input, char *message,
                           size_t size)
{
    FILE *capture = tmpfile();
    size_t length;
    int saved;
    int failed;

    if (capture == NULL) {
        return 0;
    }
    (void)fflush(stderr);
    saved = dup(fileno(stderr));
    if (saved < 0 || dup2(fileno(capture), fileno(stderr)) < 0) {
        (void)fclose(capture);
        return 0;
    }
    failed = !nekokem_decrypt_file(input, "fresh-out", key, NULL, 0U);
    (void)fflush(stderr);
    (void)dup2(saved, fileno(stderr));
    (void)close(saved);
    rewind(capture);
    length = fread(message, 1U, size - 1U, capture);
    message[length] = '\0';
    (void)fclose(capture);
    return failed && length > 0U && !exists("fresh-out");
}
#endif

typedef struct {
    uint64_t last;
    uint64_t total;
    int calls;
    int cancel_after;
} Progress;

static int progress(uint64_t processed, uint64_t total, void *data)
{
    Progress *state = data;

    if (processed < state->last || processed > total) {
        return 0;
    }
    state->last = processed;
    state->total = total;
    ++state->calls;
    return state->cancel_after < 0 || state->calls <= state->cancel_after;
}

static int test_round_trips(const unsigned char *data)
{
    const char *const two[] = {public_paths[0], public_paths[1]};
    const char *const one[] = {public_paths[2]};
    const char *const keys[] = {
        public_paths[0], public_paths[1], public_paths[2], public_paths[3]};
    unsigned char *container = NULL;
    size_t container_len = 0U;
    NkemV4Header header;
    size_t index;

    CHECK(encrypt_to("plain", "two.nkem", two, 2U) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(read_all("two.nkem", &container, &container_len));
    CHECK(container_len == nkem_v4_metadata_size(2U) + DATA_SIZE +
                               NKEM_TAG_SIZE);
    CHECK(nkem_v4_header_decode(container, &header));
    CHECK(header.recipient_count == 2U && header.ciphertext_len == DATA_SIZE);
    {
        unsigned char expected[NKEM_V4_HEADER_SIZE];

        spec_header(expected, 2U, DATA_SIZE);
        CHECK(memcmp(expected, two_recipient_header, sizeof(expected)) == 0);
        CHECK(memcmp(container, two_recipient_header, sizeof(expected)) == 0);
    }
    CHECK(nkem_v4_container_parse(container, container_len));
    CHECK(!nkem_v3_container_parse(container, container_len));
    OPENSSL_free(container);

    for (index = 0U; index < 2U; ++index) {
        CHECK(write_private("out", "keep", 4U));
        CHECK(decrypt_as(index, "two.nkem", "out"));
        CHECK(file_equals("out", data, DATA_SIZE));
    }
    /* A key that is not listed cannot decrypt, and no output is replaced. */
    CHECK(write_private("out", "keep", 4U));
    CHECK(!decrypt_as(2U, "two.nkem", "out"));
    CHECK(file_equals("out", (const unsigned char *)"keep", 4U));
    CHECK(!decrypt_as(3U, "two.nkem", "fresh-out"));
    CHECK(!exists("fresh-out"));
    /* The protected recipient key still needs its own password. */
    CHECK(!nekokem_decrypt_file("two.nkem", "fresh-out", private_paths[0],
                                (const unsigned char *)"wrong", 5U));
    CHECK(!nekokem_decrypt_file("two.nkem", "fresh-out", private_paths[0],
                                NULL, 0U));
    CHECK(!exists("fresh-out"));

    /* Every listed key decrypts, whatever its position in the list. */
    CHECK(encrypt_to("plain", "four.nkem", keys, 4U) ==
          NEKOKEM_OPERATION_SUCCESS);
    for (index = 0U; index < 4U; ++index) {
        CHECK(decrypt_as(index, "four.nkem", "out"));
        CHECK(file_equals("out", data, DATA_SIZE));
    }

    /* One recipient keeps the v3 format that older releases read. */
    CHECK(encrypt_to("plain", "one.nkem", one, 1U) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(read_all("one.nkem", &container, &container_len));
    CHECK(container[4] == 3U && nkem_v3_container_parse(container, container_len));
    OPENSSL_free(container);
    CHECK(decrypt_as(2U, "one.nkem", "out"));
    CHECK(file_equals("out", data, DATA_SIZE));
    CHECK(!decrypt_as(1U, "one.nkem", "fresh-out"));

    CHECK(encrypt_to("empty", "empty.nkem", two, 2U) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(decrypt_as(1U, "empty.nkem", "out"));
    CHECK(file_equals("out", NULL, 0U));

    /* The single-recipient API still writes v3, and both decrypt together. */
    CHECK(nekokem_encrypt_file("plain", "v3.nkem", public_paths[1]));
    CHECK(read_all("v3.nkem", &container, &container_len));
    CHECK(container[4] == 3U && nkem_v3_container_parse(container, container_len));
    OPENSSL_free(container);
    CHECK(decrypt_as(1U, "v3.nkem", "out"));
    CHECK(file_equals("out", data, DATA_SIZE));
    return 1;
}

static int test_recipient_limits(const unsigned char *data)
{
    const char *keys[KEY_COUNT];
    const char *const duplicate[] = {public_paths[1], public_paths[2],
                                     public_paths[1]};
    const char *const invalid[] = {public_paths[1], private_paths[2]};
    const char *const missing[] = {public_paths[1], "missing.key"};
    const char *const empty_path[] = {public_paths[1], ""};
    size_t index;

    for (index = 0U; index < KEY_COUNT; ++index) {
        keys[index] = public_paths[index];
    }
    CHECK(encrypt_to("plain", "max.nkem", keys, NEKOKEM_MAX_RECIPIENTS) ==
          NEKOKEM_OPERATION_SUCCESS);
    /* The last entry is found after trying every other recipient's entry. */
    CHECK(decrypt_as(NEKOKEM_MAX_RECIPIENTS - 1U, "max.nkem", "out"));
    CHECK(file_equals("out", data, DATA_SIZE));
    CHECK(!decrypt_as(NEKOKEM_MAX_RECIPIENTS, "max.nkem", "fresh-out"));

    /* Invalid lists fail before any output exists or is replaced. */
    CHECK(write_private("existing.nkem", "keep", 4U));
    CHECK(encrypt_to("plain", "existing.nkem", keys, KEY_COUNT) ==
          NEKOKEM_OPERATION_ERROR);
    CHECK(encrypt_to("plain", "existing.nkem", keys, 0U) ==
          NEKOKEM_OPERATION_ERROR);
    CHECK(encrypt_to("plain", "existing.nkem", NULL, 2U) ==
          NEKOKEM_OPERATION_ERROR);
    CHECK(encrypt_to("plain", "existing.nkem", duplicate, 3U) ==
          NEKOKEM_OPERATION_ERROR);
    CHECK(encrypt_to("plain", "existing.nkem", invalid, 2U) ==
          NEKOKEM_OPERATION_ERROR);
    CHECK(encrypt_to("plain", "existing.nkem", missing, 2U) ==
          NEKOKEM_OPERATION_ERROR);
    CHECK(encrypt_to("plain", "existing.nkem", empty_path, 2U) ==
          NEKOKEM_OPERATION_ERROR);
    CHECK(encrypt_to("missing-input", "existing.nkem", keys, 2U) ==
          NEKOKEM_OPERATION_ERROR);
    CHECK(file_equals("existing.nkem", (const unsigned char *)"keep", 4U));
    CHECK(encrypt_to("plain", "fresh.nkem", duplicate, 3U) ==
          NEKOKEM_OPERATION_ERROR);
    CHECK(!exists("fresh.nkem"));
    return 1;
}

static int rejected(const char *container)
{
    size_t index;

    for (index = 0U; index < 2U; ++index) {
        if (decrypt_as(index, container, "fresh-out") || exists("fresh-out")) {
            fprintf(stderr, "Modified container accepted by recipient %u\n",
                    (unsigned int)index);
            return 0;
        }
    }
    return 1;
}

static int test_tampering(void)
{
    const size_t mac_offset = ENTRY_OFFSET(2U);
    const size_t nonce_offset = mac_offset + NKEM_V4_MAC_SIZE;
    const size_t payload_offset = nonce_offset + NKEM_NONCE_SIZE;
    const size_t offsets[] = {
        4U, 5U, 9U, 11U, 15U, 23U, 27U, 31U,  /* header fields */
        NKEM_V4_HEADER_SIZE,                  /* HKDF salt */
        ENTRY_OFFSET(0U),                     /* recipient 0 X448 key */
        ENTRY_OFFSET(0U) + 100U,              /* recipient 0 ML-KEM ciphertext */
        ENTRY_OFFSET(1U) + NKEM_V4_ENTRY_SIZE - 40U, /* recipient 1 wrapped key */
        ENTRY_OFFSET(1U) + NKEM_V4_ENTRY_SIZE - 1U,  /* recipient 1 wrap tag */
        mac_offset, mac_offset + NKEM_V4_MAC_SIZE - 1U,
        nonce_offset, payload_offset, payload_offset + DATA_SIZE - 1U,
        payload_offset + DATA_SIZE + NKEM_TAG_SIZE - 1U};
    size_t index;

    for (index = 0U; index < sizeof(offsets) / sizeof(offsets[0]); ++index) {
        CHECK(mutate("two.nkem", "tampered.nkem", offsets[index], 0x01U, 0));
        if (!rejected("tampered.nkem")) {
            fprintf(stderr, "Offset %u\n", (unsigned int)offsets[index]);
            return 0;
        }
    }
    /* Reordering entries changes no entry but fails the header MAC. */
    CHECK(swap_entries("two.nkem", "tampered.nkem"));
    CHECK(rejected("tampered.nkem"));
    CHECK(mutate("two.nkem", "tampered.nkem", SIZE_MAX, 0U, 1));
    CHECK(rejected("tampered.nkem"));
    CHECK(mutate("two.nkem", "tampered.nkem", SIZE_MAX, 0U, -1));
    CHECK(rejected("tampered.nkem"));
    /* Unknown versions take the v3 path and are rejected there. */
    CHECK(mutate("two.nkem", "tampered.nkem", 4U, 0x04U ^ 0x05U, 0));
    CHECK(rejected("tampered.nkem"));
    return 1;
}

/* Trial decryption opens every entry and reports one failure (PR #29 audit). */
static int test_trial_decryption(const unsigned char *data)
{
    const char *const pair[] = {public_paths[1], public_paths[2]};
    const char *const spliced[] = {public_paths[1], "spliced.pub"};
    size_t malformed;
    size_t index;

    /* The independent encoder interoperates with Core. */
    CHECK(build_container(pair, 2U, SIZE_MAX, data, "built.nkem"));
    for (index = 1U; index <= 2U; ++index) {
        CHECK(decrypt_as(index, "built.nkem", "out"));
        CHECK(file_equals("out", data, DATA_SIZE));
    }
    /* A malformed entry rejects the container wherever it is, including after
     * this key's own entry, although the MAC and payload verify. */
    for (malformed = 0U; malformed < 2U; ++malformed) {
        CHECK(build_container(pair, 2U, malformed, data, "built.nkem"));
        for (index = 1U; index <= 2U; ++index) {
            CHECK(!decrypt_as(index, "built.nkem", "fresh-out"));
            CHECK(!exists("fresh-out"));
        }
    }

    CHECK(encrypt_to("plain", "pair-v4.nkem", pair, 2U) ==
          NEKOKEM_OPERATION_SUCCESS);

    /* Two keys sharing one component are rejected, not only identical keys;
     * the spliced key alone is a valid public key. */
    CHECK(splice_public(public_paths[1], public_paths[3], "spliced.pub"));
    CHECK(nekokem_encrypt_file("plain", "spliced.nkem", "spliced.pub"));
    CHECK(encrypt_to("plain", "fresh.nkem", spliced, 2U) ==
          NEKOKEM_OPERATION_ERROR);
    CHECK(!exists("fresh.nkem"));
    CHECK(splice_public(public_paths[3], public_paths[1], "spliced.pub"));
    CHECK(encrypt_to("plain", "fresh.nkem", spliced, 2U) ==
          NEKOKEM_OPERATION_ERROR);
    CHECK(!exists("fresh.nkem"));

    /* The private key whose X448 secret is zero with every curve point is
     * refused when it is loaded, before any container is read. */
    CHECK(write_degenerate_private(private_paths[1], "degenerate.key"));
    CHECK(!nekokem_decrypt_file("pair-v4.nkem", "fresh-out", "degenerate.key",
                                NULL, 0U));
    CHECK(!nekokem_decrypt_file("v3.nkem", "fresh-out", "degenerate.key",
                                NULL, 0U));
    CHECK(!exists("fresh-out"));

#ifndef _WIN32
    {
        char first[512];
        char other[512];
        size_t entry;

        /* Breaking either entry's wrap tag gives both recipients the same
         * failure, whether their own entry stopped opening or the header MAC
         * failed, and a key that is not listed sees it too. */
        CHECK(encrypt_to("plain", "pair.nkem", pair, 2U) ==
              NEKOKEM_OPERATION_SUCCESS);
        CHECK(failure_message(private_paths[3], "pair.nkem", first,
                              sizeof(first)));
        for (entry = 0U; entry < 2U; ++entry) {
            CHECK(mutate("pair.nkem", "tampered.nkem",
                         ENTRY_OFFSET(entry) + NKEM_V4_ENTRY_SIZE - 1U,
                         0x01U, 0));
            for (index = 1U; index <= 2U; ++index) {
                CHECK(failure_message(private_paths[index], "tampered.nkem",
                                      other, sizeof(other)));
                CHECK(strcmp(first, other) == 0);
            }
        }
        /* The degenerate key fails the same way for v3, v4 and malformed
         * containers: its result no longer depends on the container. */
        CHECK(failure_message("degenerate.key", "pair-v4.nkem", first,
                              sizeof(first)));
        CHECK(build_container(pair, 2U, 1U, data, "built.nkem"));
        CHECK(failure_message("degenerate.key", "built.nkem", other,
                              sizeof(other)));
        CHECK(strcmp(first, other) == 0);
        CHECK(failure_message("degenerate.key", "v3.nkem", other,
                              sizeof(other)));
        CHECK(strcmp(first, other) == 0);
    }
#endif
    return 1;
}

static int test_progress_and_cancellation(void)
{
    const char *const two[] = {public_paths[1], public_paths[2]};
    Progress state = {0U, 0U, 0, -1};

    CHECK(nekokem_encrypt_file_multi_with_progress(
              "plain", "progress.nkem", two, 2U, progress, &state) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(state.calls > 1 && state.last == DATA_SIZE && state.total == DATA_SIZE);
    state = (Progress){0U, 0U, 0, -1};
    CHECK(nekokem_decrypt_file_with_progress(
              "progress.nkem", "out", private_paths[2], NULL, 0U,
              progress, &state) == NEKOKEM_OPERATION_SUCCESS);
    CHECK(state.calls > 1 && state.last == DATA_SIZE);

    /* Cancellation after the first chunk commits nothing. */
    state = (Progress){0U, 0U, 0, 1};
    CHECK(nekokem_encrypt_file_multi_with_progress(
              "plain", "cancelled.nkem", two, 2U, progress, &state) ==
          NEKOKEM_OPERATION_CANCELLED);
    CHECK(!exists("cancelled.nkem"));
    state = (Progress){0U, 0U, 0, 1};
    CHECK(nekokem_decrypt_file_with_progress(
              "progress.nkem", "cancelled-out", private_paths[1], NULL, 0U,
              progress, &state) == NEKOKEM_OPERATION_CANCELLED);
    CHECK(!exists("cancelled-out"));
    return 1;
}

static int run(void)
{
    unsigned char *data = OPENSSL_malloc(DATA_SIZE);
    size_t index;
    int success = 0;

    if (data == NULL) {
        return 0;
    }
    for (index = 0U; index < DATA_SIZE; ++index) {
        data[index] = (unsigned char)((index * 131U + 7U) & 0xffU);
    }
    for (index = 0U; index < KEY_COUNT; ++index) {
        (void)snprintf(public_paths[index], sizeof(public_paths[index]),
                       "recipient-%u.pub", (unsigned int)index);
        /* Core reads a ".enc" private key as NKPR and others as plain PEM. */
        (void)snprintf(private_paths[index], sizeof(private_paths[index]),
                       index == 0U ? "recipient-%u.key.enc"
                                   : "recipient-%u.key", (unsigned int)index);
    }
    if (!write_private("plain", data, DATA_SIZE) ||
        !write_private("empty", "", 0U) ||
        !nekokem_generate_keypair(public_paths[0], private_paths[0],
                                  password, sizeof(password) - 1U)) {
        fprintf(stderr, "Cannot create multi-recipient fixtures\n");
        goto cleanup;
    }
    for (index = 1U; index < KEY_COUNT; ++index) {
        if (!make_plain_keypair(index)) {
            fprintf(stderr, "Cannot create recipient key %u\n",
                    (unsigned int)index);
            goto cleanup;
        }
    }
    success = test_round_trips(data) && test_recipient_limits(data) &&
              test_tampering() && test_trial_decryption(data) &&
              test_progress_and_cancellation();

cleanup:
    OPENSSL_free(data);
    return success;
}

#ifndef _WIN32
static void remove_fixtures(void)
{
    static const char *const names[] = {
        "plain", "empty", "out", "two.nkem", "four.nkem", "one.nkem",
        "empty.nkem", "v3.nkem", "max.nkem", "existing.nkem",
        "tampered.nkem", "progress.nkem", "fresh-out", "fresh.nkem",
        "cancelled.nkem", "cancelled-out", "built.nkem", "spliced.pub",
        "spliced.nkem", "pair.nkem", "pair-v4.nkem", "degenerate.key"};
    size_t index;

    for (index = 0U; index < sizeof(names) / sizeof(names[0]); ++index) {
        (void)remove(names[index]);
    }
    for (index = 0U; index < KEY_COUNT; ++index) {
        (void)remove(public_paths[index]);
        (void)remove(private_paths[index]);
    }
}
#endif

int main(void)
{
#ifndef _WIN32
    /* Windows CI runs each test in its own empty directory. */
    char directory[] = "/tmp/nekokem-multi-recipient.XXXXXX";

    if (mkdtemp(directory) == NULL || chdir(directory) != 0) {
        perror("Cannot create test directory");
        return EXIT_FAILURE;
    }
#endif
    int success = run();

#ifndef _WIN32
    remove_fixtures();
    if (chdir("/") == 0) {
        (void)rmdir(directory);
    }
#endif
    if (!success) {
        fprintf(stderr, "NKEM v4 multi-recipient tests failed\n");
        return EXIT_FAILURE;
    }
    puts("NKEM v4 multi-recipient tests passed");
    return EXIT_SUCCESS;
}
