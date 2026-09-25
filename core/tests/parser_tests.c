#include "file.h"
#include "private_key.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

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
    buffer[5] = NKPR_KDF_ID_ARGON2ID;
    buffer[6] = NKPR_CIPHER_ID_AES256_GCM;
    buffer[7] = 0U;
    put_u32_be(buffer + 8U, NKPR_ARGON2_MEMORY_KIB);
    put_u32_be(buffer + 12U, NKPR_ARGON2_ITERATIONS);
    put_u32_be(buffer + 16U, NKPR_ARGON2_PARALLELISM);
    put_u32_be(buffer + 20U, NKPR_ARGON2_VERSION);
    put_u16_be(buffer + 24U, NKPR_SALT_SIZE);
    buffer[26] = NKPR_NONCE_SIZE;
    buffer[27] = NKPR_TAG_SIZE;
    put_u64_be(buffer + 28U, ciphertext_len);
    return total;
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
        !nkpr_container_parse(nkpr, nkpr_len)) {
        return EXIT_FAILURE;
    }
    for (i = 0U; i < nkem_len; ++i) {
        if (nkem_v3_container_parse(nkem, i)) return EXIT_FAILURE;
    }
    for (i = 0U; i < nkpr_len; ++i) {
        if (nkpr_container_parse(nkpr, i)) return EXIT_FAILURE;
    }

    nkem[4] = 1U;
    if (nkem_v3_container_parse(nkem, nkem_len)) return EXIT_FAILURE;
    nkem[4] = 2U;
    if (nkem_v3_container_parse(nkem, nkem_len)) return EXIT_FAILURE;
    nkem[4] = NKEM_V3_VERSION;
    nkem[5] ^= 1U;
    if (nkem_v3_container_parse(nkem, nkem_len)) return EXIT_FAILURE;

    nkpr[4] ^= 1U;
    if (nkpr_container_parse(nkpr, nkpr_len)) return EXIT_FAILURE;

    puts("NKEM v3 and NKPR parser tests passed");
    return EXIT_SUCCESS;
}
