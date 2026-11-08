/* NKEM v5 payload digest: tree layout, worker independence and failures. */
#include "payload_hash.h"

#include <openssl/evp.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(condition)                                                    \
    do {                                                                    \
        if (!(condition)) {                                                 \
            fprintf(stderr, "%s:%d: check failed: %s\n", __FILE__,          \
                    __LINE__, #condition);                                  \
            return 0;                                                       \
        }                                                                   \
    } while (0)

#define LEAF PAYLOAD_HASH_LEAF_SIZE

/*
 * docs/NKEM-v5.md test vector: 2.5 MiB + 3 bytes with byte i = i mod 251
 * and the tag 00 01 .. 0f.
 */
static const char vector_digest[] =
    "829a3198f9894e5f0008a34575168f0cc436a5a341cb9511b6ed501e69277962"
    "ecbcc712915fd1602efe47c2ea4a8c4ff80c61638a71e62790dd1208c35d744d";

static void store_be64(unsigned char output[8], uint64_t value)
{
    size_t index;

    for (index = 0U; index < 8U; ++index) {
        output[7U - index] = (unsigned char)(value >> (8U * index));
    }
}

/* An independent one-shot reading of the specification. */
static int reference(const unsigned char *data, uint64_t length,
                     const unsigned char tag[16], unsigned char digest[64])
{
    static const char leaf_label[] = "NekoKEM v5 payload leaf";
    static const char root_label[] = "NekoKEM v5 payload root";
    uint64_t leaves = (length + LEAF - 1U) / LEAF;
    size_t root_len = (sizeof(root_label) - 1U) + 8U + (size_t)leaves * 64U + 16U;
    unsigned char *root = malloc(root_len);
    unsigned char *leaf_input = malloc((sizeof(leaf_label) - 1U) + 8U + LEAF);
    unsigned int digest_len = 0U;
    size_t offset;
    uint64_t index;
    int success = root != NULL && leaf_input != NULL;

    if (success) {
        memcpy(root, root_label, sizeof(root_label) - 1U);
        offset = sizeof(root_label) - 1U;
        store_be64(root + offset, length);
        offset += 8U;
        for (index = 0U; success && index < leaves; ++index) {
            size_t chunk = (size_t)(length - index * LEAF < LEAF
                                        ? length - index * LEAF
                                        : LEAF);
            size_t input_len = sizeof(leaf_label) - 1U;

            memcpy(leaf_input, leaf_label, input_len);
            store_be64(leaf_input + input_len, index);
            input_len += 8U;
            memcpy(leaf_input + input_len, data + index * LEAF, chunk);
            input_len += chunk;
            success = EVP_Digest(leaf_input, input_len, root + offset,
                                 &digest_len, EVP_sha512(), NULL) == 1 &&
                      digest_len == 64U;
            offset += 64U;
        }
        memcpy(root + offset, tag, 16U);
        success = success &&
                  EVP_Digest(root, root_len, digest, &digest_len, EVP_sha512(),
                             NULL) == 1 &&
                  digest_len == 64U;
    }
    free(root);
    free(leaf_input);
    return success;
}

/* Hashes data in updates of step bytes (0 = varying sizes). */
static int compute(const unsigned char *data, uint64_t length, size_t workers,
                   size_t step, const unsigned char tag[16],
                   unsigned char digest[64])
{
    PayloadHash *hash = payload_hash_new_with_workers(length, workers);
    uint64_t done = 0U;
    size_t round = 0U;
    int success = hash != NULL;

    while (success && done < length) {
        size_t take = step != 0U ? step : (size_t)(1U + (round * 7919U) % 200003U);

        if ((uint64_t)take > length - done) {
            take = (size_t)(length - done);
        }
        success = payload_hash_update(hash, data + done, take);
        done += take;
        ++round;
    }
    success = success && payload_hash_final(hash, tag, digest);
    payload_hash_free(hash);
    return success;
}

static void hex(const unsigned char digest[64], char output[129])
{
    size_t index;

    for (index = 0U; index < 64U; ++index) {
        (void)snprintf(output + 2U * index, 3U, "%02x", digest[index]);
    }
}

static int test_matches_reference(const unsigned char *data,
                                  const unsigned char tag[16])
{
    static const uint64_t lengths[] = {
        0U, 1U, LEAF - 1U, LEAF, LEAF + 1U, 2U * LEAF, 2U * LEAF + 17U,
        5U * LEAF - 1U, 9U * LEAF + 65536U,
    };
    static const size_t steps[] = {0U, 1U << 16, LEAF, 3U * LEAF};
    size_t length_index;

    for (length_index = 0U; length_index < sizeof(lengths) / sizeof(lengths[0]);
         ++length_index) {
        unsigned char expected[64];
        size_t workers;

        CHECK(reference(data, lengths[length_index], tag, expected));
        for (workers = 0U; workers <= PAYLOAD_HASH_MAX_WORKERS + 1U; ++workers) {
            size_t step_index;

            for (step_index = 0U; step_index < sizeof(steps) / sizeof(steps[0]);
                 ++step_index) {
                unsigned char actual[64];

                if (lengths[length_index] > 2U * LEAF && steps[step_index] == 1U) {
                    continue;
                }
                CHECK(compute(data, lengths[length_index], workers,
                              steps[step_index], tag, actual));
                CHECK(memcmp(actual, expected, 64U) == 0);
            }
        }
    }
    return 1;
}

static int test_vector(const unsigned char *data, const unsigned char tag[16])
{
    unsigned char digest[64];
    char text[129];

    CHECK(compute(data, 5U * LEAF / 2U + 3U, 2U, 0U, tag, digest));
    hex(digest, text);
    if (strcmp(text, vector_digest) != 0) {
        fprintf(stderr, "test vector digest: %s\n", text);
        return 0;
    }
    return 1;
}

static int test_domain_separation(const unsigned char *data,
                                  const unsigned char tag[16])
{
    unsigned char first[64];
    unsigned char second[64];
    unsigned char other_tag[16];

    /* The length, every leaf and the tag each change the digest. */
    CHECK(compute(data, LEAF, 0U, 0U, tag, first));
    CHECK(compute(data, LEAF - 1U, 0U, 0U, tag, second));
    CHECK(memcmp(first, second, 64U) != 0);
    CHECK(compute(data + 1, LEAF, 0U, 0U, tag, second));
    CHECK(memcmp(first, second, 64U) != 0);
    memcpy(other_tag, tag, sizeof(other_tag));
    other_tag[15] ^= 1U;
    CHECK(compute(data, LEAF, 0U, 0U, other_tag, second));
    CHECK(memcmp(first, second, 64U) != 0);
    /* Not the old flat SHA-512 of ciphertext || tag. */
    {
        unsigned char *flat = malloc(LEAF + 16U);
        unsigned int digest_len = 0U;

        CHECK(flat != NULL);
        memcpy(flat, data, LEAF);
        memcpy(flat + LEAF, tag, 16U);
        CHECK(EVP_Digest(flat, LEAF + 16U, second, &digest_len, EVP_sha512(),
                         NULL) == 1);
        free(flat);
        CHECK(memcmp(first, second, 64U) != 0);
    }
    return 1;
}

static int test_misuse(const unsigned char *data, const unsigned char tag[16])
{
    unsigned char digest[64];
    PayloadHash *hash;

    /* Short input, long input, and a second final all fail. */
    hash = payload_hash_new_with_workers(3U * LEAF, 2U);
    CHECK(hash != NULL);
    CHECK(payload_hash_update(hash, data, 2U * LEAF));
    CHECK(!payload_hash_final(hash, tag, digest));
    payload_hash_free(hash);

    hash = payload_hash_new_with_workers(LEAF, 0U);
    CHECK(hash != NULL);
    CHECK(!payload_hash_update(hash, data, LEAF + 1U));
    CHECK(payload_hash_update(hash, data, LEAF));
    CHECK(!payload_hash_update(hash, data, 1U));
    CHECK(payload_hash_final(hash, tag, digest));
    CHECK(!payload_hash_final(hash, tag, digest));
    payload_hash_free(hash);

    /* Freeing with leaves still queued stops the workers cleanly. */
    hash = payload_hash_new_with_workers(8U * LEAF, 4U);
    CHECK(hash != NULL);
    CHECK(payload_hash_update(hash, data, 5U * LEAF + 1U));
    payload_hash_free(hash);
    payload_hash_free(NULL);

    /* One leaf never needs a worker; more leaves use what was asked for. */
    hash = payload_hash_new_with_workers(LEAF, 4U);
    CHECK(hash != NULL && payload_hash_worker_count(hash) == 0U);
    payload_hash_free(hash);
    hash = payload_hash_new_with_workers(2U * LEAF, 4U);
    CHECK(hash != NULL && payload_hash_worker_count(hash) == 2U);
    payload_hash_free(hash);
    hash = payload_hash_new_with_workers(9U * LEAF, 9U);
    CHECK(hash != NULL &&
          payload_hash_worker_count(hash) == PAYLOAD_HASH_MAX_WORKERS);
    payload_hash_free(hash);
    hash = payload_hash_new(9U * LEAF);
    CHECK(hash != NULL &&
          payload_hash_worker_count(hash) <= PAYLOAD_HASH_MAX_WORKERS);
    payload_hash_free(hash);
    return 1;
}

#ifdef NEKOKEM_TEST_FAULT_INJECTION
static int test_faults(const unsigned char *data, const unsigned char tag[16])
{
    static const uint64_t failing[] = {0U, 1U, 4U, 8U};
    unsigned char expected[64];
    unsigned char actual[64];
    size_t index;

    for (index = 0U; index < sizeof(failing) / sizeof(failing[0]); ++index) {
        size_t workers;

        payload_hash_test_fail_leaf(failing[index]);
        for (workers = 0U; workers <= PAYLOAD_HASH_MAX_WORKERS; ++workers) {
            CHECK(!compute(data, 8U * LEAF + 5U, workers, 0U, tag, actual));
        }
    }
    payload_hash_test_fail_leaf(UINT64_MAX);

    /* Without threads the digest is computed inline and stays the same. */
    CHECK(reference(data, 6U * LEAF + 9U, tag, expected));
    payload_hash_test_fail_threads(1);
    {
        PayloadHash *hash = payload_hash_new_with_workers(6U * LEAF, 4U);

        CHECK(hash != NULL && payload_hash_worker_count(hash) == 0U);
        payload_hash_free(hash);
    }
    CHECK(compute(data, 6U * LEAF + 9U, 4U, 0U, tag, actual));
    payload_hash_test_fail_threads(0);
    CHECK(memcmp(actual, expected, 64U) == 0);
    return 1;
}
#endif

int main(void)
{
    size_t size = 12U * LEAF;
    unsigned char *data = malloc(size);
    unsigned char tag[16];
    size_t index;
    int success;

    if (data == NULL) {
        return 1;
    }
    for (index = 0U; index < size; ++index) {
        data[index] = (unsigned char)(index % 251U);
    }
    for (index = 0U; index < sizeof(tag); ++index) {
        tag[index] = (unsigned char)index;
    }
    success = test_vector(data, tag) && test_matches_reference(data, tag) &&
              test_domain_separation(data, tag) && test_misuse(data, tag);
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    success = success && test_faults(data, tag);
#endif
    free(data);
    if (!success) {
        return 1;
    }
    puts("payload hash tests passed");
    return 0;
}
