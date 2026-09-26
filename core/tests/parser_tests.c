#include "file.h"
#include "private_key.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define NKPR_TEST_KDF_ID_ARGON2ID 1U
#define NKPR_TEST_CIPHER_ID_AES256_GCM 1U
#define NKPR_TEST_ARGON2_VERSION 0x13U

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
        output[index] = (unsigned char)(value >> (56U - index * 8U));
    }
}

static size_t build_valid_nkem(unsigned char *buffer, size_t capacity)
{
    const uint32_t kem_len = 32U;
    const uint64_t ciphertext_len = 16U;
    const size_t total = NKEM_V3_HEADER_SIZE + NKEM_X448_EPHEMERAL_PUBLIC_SIZE +
                         kem_len + NKEM_V3_SALT_SIZE + NKEM_NONCE_SIZE +
                         (size_t)ciphertext_len + NKEM_TAG_SIZE;
    if (capacity < total) return 0U;
    memset(buffer, 0, total);
    nkem_v3_header_encode(buffer, NKEM_X448_EPHEMERAL_PUBLIC_SIZE,
                          kem_len, ciphertext_len);
    return total;
}

static size_t build_valid_nkpr(unsigned char *buffer, size_t capacity)
{
    const uint64_t ciphertext_len = 32U;
    const size_t total = NKPR_HEADER_SIZE + NKPR_SALT_SIZE + NKPR_NONCE_SIZE +
                         (size_t)ciphertext_len + NKPR_TAG_SIZE;
    if (capacity < total) return 0U;
    memset(buffer, 0, total);
    memcpy(buffer, "NKPR", 4U);
    buffer[4] = NKPR_VERSION;
    buffer[5] = NKPR_TEST_KDF_ID_ARGON2ID;
    buffer[6] = NKPR_TEST_CIPHER_ID_AES256_GCM;
    buffer[7] = 0U;
    put_u32_be(buffer + 8U, NKPR_ARGON2_MEMORY_KIB);
    put_u32_be(buffer + 12U, NKPR_ARGON2_ITERATIONS);
    put_u32_be(buffer + 16U, NKPR_ARGON2_PARALLELISM);
    put_u32_be(buffer + 20U, NKPR_TEST_ARGON2_VERSION);
    put_u16_be(buffer + 24U, NKPR_SALT_SIZE);
    buffer[26] = NKPR_NONCE_SIZE;
    buffer[27] = NKPR_TAG_SIZE;
    put_u64_be(buffer + 28U, ciphertext_len);
    return total;
}

static int expect_nkem_invalid(const unsigned char *input, size_t input_len)
{
    return nkem_v3_container_parse(input, input_len) == 0;
}

static int expect_nkpr_invalid(const unsigned char *input, size_t input_len)
{
    return protected_private_key_container_parse(input, input_len) == 0;
}

static int test_nkem_mutations(const unsigned char *valid, size_t valid_len)
{
    unsigned char mutated[512];
    NkemV3Header header;

    if (!nkem_gcm_data_size_is_valid(NKEM_GCM_MAX_DATA_SIZE) ||
        nkem_gcm_data_size_is_valid(
            NKEM_GCM_MAX_DATA_SIZE + UINT64_C(1))) {
        return 0;
    }

    memcpy(mutated, valid, valid_len);
    mutated[4] = 1U;
    if (!expect_nkem_invalid(mutated, valid_len)) return 0;
    mutated[4] = 2U;
    if (!expect_nkem_invalid(mutated, valid_len)) return 0;

    memcpy(mutated, valid, valid_len);
    mutated[5] ^= 1U;
    if (!expect_nkem_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    put_u16_be(mutated + 6U, 0U);
    if (!expect_nkem_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    put_u16_be(mutated + 8U, 0U);
    if (!expect_nkem_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    mutated[10] = 1U;
    if (!expect_nkem_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    put_u32_be(mutated + 12U, NKEM_MAX_KEM_CIPHERTEXT_SIZE + 1U);
    if (!expect_nkem_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    put_u64_be(mutated + 16U, UINT64_MAX);
    if (!expect_nkem_invalid(mutated, valid_len)) return 0;

    nkem_v3_header_encode(mutated, NKEM_X448_EPHEMERAL_PUBLIC_SIZE,
                          NKEM_MAX_KEM_CIPHERTEXT_SIZE,
                          NKEM_GCM_MAX_DATA_SIZE);
    if (nkem_v3_header_decode(mutated, &header) == 0) return 0;
    nkem_v3_header_encode(mutated, NKEM_X448_EPHEMERAL_PUBLIC_SIZE,
                          NKEM_MAX_KEM_CIPHERTEXT_SIZE,
                          NKEM_GCM_MAX_DATA_SIZE + UINT64_C(1));
    if (nkem_v3_header_decode(mutated, &header) != 0) return 0;
    memcpy(mutated, valid, valid_len);
    mutated[24] ^= 1U;
    if (!expect_nkem_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    mutated[25] ^= 1U;
    if (!expect_nkem_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    mutated[26] ^= 1U;
    if (!expect_nkem_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    mutated[27] = 1U;
    if (!expect_nkem_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    put_u32_be(mutated + 28U, 1U);
    if (!expect_nkem_invalid(mutated, valid_len)) return 0;

    memcpy(mutated, valid, valid_len);
    mutated[valid_len] = 0U;
    return expect_nkem_invalid(mutated, valid_len + 1U);
}

static int test_nkpr_mutations(const unsigned char *valid, size_t valid_len)
{
    unsigned char mutated[512];

    memcpy(mutated, valid, valid_len);
    mutated[4] ^= 1U;
    if (!expect_nkpr_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    mutated[5] ^= 1U;
    if (!expect_nkpr_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    mutated[6] ^= 1U;
    if (!expect_nkpr_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    mutated[7] = 1U;
    if (!expect_nkpr_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    put_u32_be(mutated + 8U, UINT32_MAX);
    if (!expect_nkpr_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    put_u32_be(mutated + 12U, 0U);
    if (!expect_nkpr_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    put_u32_be(mutated + 16U, 0U);
    if (!expect_nkpr_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    put_u32_be(mutated + 20U, 0U);
    if (!expect_nkpr_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    put_u16_be(mutated + 24U, 0U);
    if (!expect_nkpr_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    mutated[26] ^= 1U;
    if (!expect_nkpr_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    mutated[27] ^= 1U;
    if (!expect_nkpr_invalid(mutated, valid_len)) return 0;
    memcpy(mutated, valid, valid_len);
    put_u64_be(mutated + 28U, UINT64_MAX);
    if (!expect_nkpr_invalid(mutated, valid_len)) return 0;

    memcpy(mutated, valid, valid_len);
    mutated[valid_len] = 0U;
    return expect_nkpr_invalid(mutated, valid_len + 1U);
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
            memcpy(random_data, "RNDM", 4U);
        }
        if (!expect_nkem_invalid(random_data, length) ||
            !expect_nkpr_invalid(random_data, length)) {
            return 0;
        }
    }
    return 1;
}

int main(void)
{
    unsigned char nkem[512];
    unsigned char nkpr[512];
    size_t nkem_len = build_valid_nkem(nkem, sizeof(nkem));
    size_t nkpr_len = build_valid_nkpr(nkpr, sizeof(nkpr));
    size_t i;

    if (nkem_len == 0U || nkpr_len == 0U ||
        !nkem_v3_container_parse(nkem, nkem_len) ||
        !protected_private_key_container_parse(nkpr, nkpr_len)) {
        return EXIT_FAILURE;
    }
    for (i = 0U; i < nkem_len; ++i) {
        if (nkem_v3_container_parse(nkem, i)) return EXIT_FAILURE;
    }
    for (i = 0U; i < nkpr_len; ++i) {
        if (protected_private_key_container_parse(nkpr, i)) return EXIT_FAILURE;
    }

    if (!test_nkem_mutations(nkem, nkem_len) ||
        !test_nkpr_mutations(nkpr, nkpr_len) ||
        !test_random_inputs()) {
        return EXIT_FAILURE;
    }

    puts("NKEM v3 and NKPR parser tests passed");
    return EXIT_SUCCESS;
}
