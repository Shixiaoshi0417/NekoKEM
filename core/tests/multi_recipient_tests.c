/* NKEM v4 multi-recipient round trips, limits and tamper rejection. */
#include "nekokem.h"
#include "file.h"

#include <openssl/bio.h>
#include <openssl/buffer.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
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

    /* One recipient is a valid v4 container as well. */
    CHECK(encrypt_to("plain", "one.nkem", one, 1U) ==
          NEKOKEM_OPERATION_SUCCESS);
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
              test_tampering() && test_progress_and_cancellation();

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
        "cancelled.nkem", "cancelled-out"};
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
