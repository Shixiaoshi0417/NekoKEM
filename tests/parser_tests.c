#include "../src/file.h"
#include "../src/private_key.h"

#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define V1_KEM_TEST_SIZE 1U
#define V1_TEST_SIZE \
    (NKEM_HEADER_SIZE + V1_KEM_TEST_SIZE + NKEM_NONCE_SIZE + \
     NKEM_TAG_SIZE)
#define V2_KEM_TEST_SIZE 1U
#define V2_TEST_SIZE \
    (NKEM_V2_HEADER_SIZE + NKEM_X448_EPHEMERAL_PUBLIC_SIZE + \
     V2_KEM_TEST_SIZE + NKEM_NONCE_SIZE + NKEM_TAG_SIZE)
#define V3_KEM_TEST_SIZE 1U
#define V3_TEST_SIZE \
    (NKEM_V3_HEADER_SIZE + NKEM_X448_EPHEMERAL_PUBLIC_SIZE + \
     V3_KEM_TEST_SIZE + NKEM_V3_SALT_SIZE + NKEM_NONCE_SIZE + \
     NKEM_TAG_SIZE)
#define NKPR_TEST_CIPHERTEXT_SIZE 1U
#define NKPR_TEST_SIZE \
    (NKPR_HEADER_SIZE + NKPR_SALT_SIZE + NKPR_NONCE_SIZE + \
     NKPR_TEST_CIPHERTEXT_SIZE + NKPR_TAG_SIZE)

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

static void build_valid_nkpr(
    unsigned char output[NKPR_TEST_SIZE])
{
    memset(output, 0, NKPR_TEST_SIZE);
    memcpy(output, NKPR_MAGIC, 4U);
    output[4] = NKPR_VERSION;
    output[5] = 1U;
    output[6] = 1U;
    output[7] = 0U;
    put_u32_be(output + 8U, NKPR_ARGON2_MEMORY_KIB);
    put_u32_be(output + 12U, NKPR_ARGON2_ITERATIONS);
    put_u32_be(output + 16U, NKPR_ARGON2_PARALLELISM);
    put_u32_be(output + 20U, 0x13U);
    put_u16_be(output + 24U, (uint16_t)NKPR_SALT_SIZE);
    output[26] = NKPR_NONCE_SIZE;
    output[27] = NKPR_TAG_SIZE;
    put_u64_be(output + 28U, NKPR_TEST_CIPHERTEXT_SIZE);
}

static int expect_nkem_invalid(const char *name,
                               const unsigned char *input,
                               size_t input_len)
{
    if (nkem_container_parse(input, input_len) != 0) {
        fprintf(stderr, "NKEM parser accepted invalid case: %s\n",
                name);
        return 0;
    }
    return 1;
}

static int expect_nkpr_invalid(const char *name,
                               const unsigned char *input,
                               size_t input_len)
{
    if (protected_private_key_container_parse(input, input_len) != 0) {
        fprintf(stderr, "NKPR parser accepted invalid case: %s\n",
                name);
        return 0;
    }
    return 1;
}

static int test_valid_inputs(
    const unsigned char v1[V1_TEST_SIZE],
    const unsigned char v2[V2_TEST_SIZE],
    const unsigned char v3[V3_TEST_SIZE],
    const unsigned char nkpr[NKPR_TEST_SIZE])
{
    if (nkem_container_parse(v1, V1_TEST_SIZE) == 0 ||
        nkem_container_parse(v2, V2_TEST_SIZE) == 0 ||
        nkem_container_parse(v3, V3_TEST_SIZE) == 0 ||
        protected_private_key_container_parse(
            nkpr, NKPR_TEST_SIZE) == 0) {
        fprintf(stderr, "A minimal valid parser fixture was rejected\n");
        return 0;
    }
    return 1;
}

static int test_truncation(
    const unsigned char v1[V1_TEST_SIZE],
    const unsigned char v2[V2_TEST_SIZE],
    const unsigned char v3[V3_TEST_SIZE],
    const unsigned char nkpr[NKPR_TEST_SIZE])
{
    size_t length;

    for (length = 0U; length < V1_TEST_SIZE; ++length) {
        if (!expect_nkem_invalid("truncated v1", v1, length)) {
            return 0;
        }
    }
    for (length = 0U; length < V2_TEST_SIZE; ++length) {
        if (!expect_nkem_invalid("truncated v2", v2, length)) {
            return 0;
        }
    }
    for (length = 0U; length < V3_TEST_SIZE; ++length) {
        if (!expect_nkem_invalid("truncated v3", v3, length)) {
            return 0;
        }
    }
    for (length = 0U; length < NKPR_TEST_SIZE; ++length) {
        if (!expect_nkpr_invalid("truncated", nkpr, length)) {
            return 0;
        }
    }
    return 1;
}

static int test_mutated_fields(
    const unsigned char v2[V2_TEST_SIZE],
    const unsigned char v3[V3_TEST_SIZE],
    const unsigned char nkpr[NKPR_TEST_SIZE])
{
    unsigned char mutated_nkem[V2_TEST_SIZE];
    unsigned char mutated_v3[V3_TEST_SIZE];
    unsigned char mutated_nkpr[NKPR_TEST_SIZE];

    memcpy(mutated_nkem, v2, sizeof(mutated_nkem));
    mutated_nkem[4] = 0x7fU;
    if (!expect_nkem_invalid("illegal version", mutated_nkem,
                             sizeof(mutated_nkem))) {
        return 0;
    }
    memcpy(mutated_nkem, v2, sizeof(mutated_nkem));
    mutated_nkem[5] = 0x7fU;
    if (!expect_nkem_invalid("illegal algorithm", mutated_nkem,
                             sizeof(mutated_nkem))) {
        return 0;
    }
    memcpy(mutated_nkem, v2, sizeof(mutated_nkem));
    put_u64_be(mutated_nkem + 16U, 1U);
    if (!expect_nkem_invalid("wrong ciphertext length", mutated_nkem,
                             sizeof(mutated_nkem))) {
        return 0;
    }
    memcpy(mutated_nkem, v2, sizeof(mutated_nkem));
    put_u32_be(mutated_nkem + 12U,
               NKEM_MAX_KEM_CIPHERTEXT_SIZE + 1U);
    if (!expect_nkem_invalid("oversized KEM field", mutated_nkem,
                             sizeof(mutated_nkem))) {
        return 0;
    }
    memcpy(mutated_nkem, v2, sizeof(mutated_nkem));
    put_u64_be(mutated_nkem + 16U, UINT64_MAX);
    if (!expect_nkem_invalid("overflowing length", mutated_nkem,
                             sizeof(mutated_nkem))) {
        return 0;
    }

    memcpy(mutated_v3, v3, sizeof(mutated_v3));
    mutated_v3[5] = 0x7fU;
    if (!expect_nkem_invalid("v3 illegal algorithm", mutated_v3,
                             sizeof(mutated_v3))) {
        return 0;
    }
    memcpy(mutated_v3, v3, sizeof(mutated_v3));
    mutated_v3[24] = 0x7fU;
    if (!expect_nkem_invalid("v3 illegal salt length", mutated_v3,
                             sizeof(mutated_v3))) {
        return 0;
    }
    memcpy(mutated_v3, v3, sizeof(mutated_v3));
    mutated_v3[25] = 0x7fU;
    if (!expect_nkem_invalid("v3 illegal nonce length", mutated_v3,
                             sizeof(mutated_v3))) {
        return 0;
    }
    memcpy(mutated_v3, v3, sizeof(mutated_v3));
    put_u32_be(mutated_v3 + 12U,
               NKEM_MAX_KEM_CIPHERTEXT_SIZE + 1U);
    if (!expect_nkem_invalid("v3 oversized KEM field", mutated_v3,
                             sizeof(mutated_v3))) {
        return 0;
    }
    memcpy(mutated_v3, v3, sizeof(mutated_v3));
    put_u64_be(mutated_v3 + 16U, UINT64_MAX);
    if (!expect_nkem_invalid("v3 overflowing length", mutated_v3,
                             sizeof(mutated_v3))) {
        return 0;
    }

    memcpy(mutated_nkpr, nkpr, sizeof(mutated_nkpr));
    mutated_nkpr[4] = 0x7fU;
    if (!expect_nkpr_invalid("illegal version", mutated_nkpr,
                             sizeof(mutated_nkpr))) {
        return 0;
    }
    memcpy(mutated_nkpr, nkpr, sizeof(mutated_nkpr));
    mutated_nkpr[5] = 0x7fU;
    if (!expect_nkpr_invalid("illegal KDF id", mutated_nkpr,
                             sizeof(mutated_nkpr))) {
        return 0;
    }
    memcpy(mutated_nkpr, nkpr, sizeof(mutated_nkpr));
    mutated_nkpr[6] = 0x7fU;
    if (!expect_nkpr_invalid("illegal cipher id", mutated_nkpr,
                             sizeof(mutated_nkpr))) {
        return 0;
    }
    memcpy(mutated_nkpr, nkpr, sizeof(mutated_nkpr));
    put_u64_be(mutated_nkpr + 28U, 2U);
    if (!expect_nkpr_invalid("wrong ciphertext length", mutated_nkpr,
                             sizeof(mutated_nkpr))) {
        return 0;
    }
    memcpy(mutated_nkpr, nkpr, sizeof(mutated_nkpr));
    put_u32_be(mutated_nkpr + 8U, UINT32_MAX);
    if (!expect_nkpr_invalid("oversized memory field", mutated_nkpr,
                             sizeof(mutated_nkpr))) {
        return 0;
    }
    memcpy(mutated_nkpr, nkpr, sizeof(mutated_nkpr));
    put_u64_be(mutated_nkpr + 28U, UINT64_MAX);
    return expect_nkpr_invalid("overflowing length", mutated_nkpr,
                               sizeof(mutated_nkpr));
}

static int test_random_inputs(void)
{
    unsigned char random_data[256];
    uint32_t state = 0x4e4b454dU;
    size_t sample;
    size_t index;

    for (sample = 0U; sample < 128U; ++sample) {
        size_t length = (sample * 37U) % sizeof(random_data);

        for (index = 0U; index < length; ++index) {
            state = (state * 1664525U) + 1013904223U;
            random_data[index] = (unsigned char)(state >> 24);
        }
        if (length >= 4U) {
            random_data[0] = 'R';
            random_data[1] = 'N';
            random_data[2] = 'D';
            random_data[3] = 'M';
        }
        if (!expect_nkem_invalid("random bytes",
                                 random_data, length) ||
            !expect_nkpr_invalid("random bytes",
                                 random_data, length)) {
            return 0;
        }
    }
    return 1;
}

static int test_version_separation(
    const unsigned char v3[V3_TEST_SIZE])
{
    NkemHeader v1_header;
    NkemV2Header v2_header;

    if (nkem_header_decode(v3, &v1_header) != 0 ||
        nkem_v2_header_decode(v3, &v2_header) != 0) {
        fprintf(stderr,
                "NKEM v3 header was accepted by a legacy decoder\n");
        return 0;
    }
    if (nkem_container_parse(v3, V3_TEST_SIZE) == 0) {
        fprintf(stderr, "NKEM v3 dispatcher rejected a valid container\n");
        return 0;
    }
    return 1;
}

int main(void)
{
    unsigned char valid_v1[V1_TEST_SIZE] = {0};
    unsigned char valid_v2[V2_TEST_SIZE] = {0};
    unsigned char valid_v3[V3_TEST_SIZE] = {0};
    unsigned char valid_nkpr[NKPR_TEST_SIZE];

    nkem_header_encode(valid_v1, V1_KEM_TEST_SIZE, 0U);
    nkem_v2_header_encode(valid_v2,
                          NKEM_X448_EPHEMERAL_PUBLIC_SIZE,
                          V2_KEM_TEST_SIZE, 0U);
    nkem_v3_header_encode(valid_v3,
                          NKEM_X448_EPHEMERAL_PUBLIC_SIZE,
                          V3_KEM_TEST_SIZE, 0U);
    build_valid_nkpr(valid_nkpr);

    if (!test_valid_inputs(valid_v1, valid_v2, valid_v3,
                           valid_nkpr) ||
        !expect_nkem_invalid("null input", NULL, 0U) ||
        !expect_nkpr_invalid("null input", NULL, 0U) ||
        !test_truncation(valid_v1, valid_v2, valid_v3,
                         valid_nkpr) ||
        !test_mutated_fields(valid_v2, valid_v3, valid_nkpr) ||
        !test_version_separation(valid_v3) ||
        !test_random_inputs()) {
        return 1;
    }
    puts("NKEM/NKPR parser robustness tests passed");
    return 0;
}
