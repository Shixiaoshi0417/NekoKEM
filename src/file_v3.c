#include "file.h"

#include <stdint.h>
#include <stdio.h>
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
        output[index] = (unsigned char)(value >> (56U - (index * 8U)));
    }
}

static uint16_t get_u16_be(const unsigned char *input)
{
    return (uint16_t)(((uint16_t)input[0] << 8) | (uint16_t)input[1]);
}

static uint32_t get_u32_be(const unsigned char *input)
{
    return ((uint32_t)input[0] << 24) |
           ((uint32_t)input[1] << 16) |
           ((uint32_t)input[2] << 8) |
           (uint32_t)input[3];
}

static uint64_t get_u64_be(const unsigned char *input)
{
    uint64_t value = 0U;
    size_t index;

    for (index = 0U; index < 8U; ++index) {
        value = (value << 8) | (uint64_t)input[index];
    }
    return value;
}

void nkem_v3_header_encode(unsigned char output[NKEM_V3_HEADER_SIZE],
                           uint16_t x448_ephemeral_len,
                           uint32_t kem_ciphertext_len,
                           uint64_t ciphertext_len)
{
    memcpy(output, "NKEM", 4U);
    output[4] = NKEM_V3_VERSION;
    output[5] = NKEM_V3_ALGORITHM_ID;
    put_u16_be(output + 6U, (uint16_t)NKEM_V3_HEADER_SIZE);
    put_u16_be(output + 8U, x448_ephemeral_len);
    put_u16_be(output + 10U, 0U);
    put_u32_be(output + 12U, kem_ciphertext_len);
    put_u64_be(output + 16U, ciphertext_len);
    output[24] = NKEM_V3_SALT_SIZE;
    output[25] = NKEM_NONCE_SIZE;
    output[26] = NKEM_TAG_SIZE;
    output[27] = 0U;
    put_u32_be(output + 28U, 0U);
}

static int nkem_v3_header_decode_internal(
    const unsigned char input[NKEM_V3_HEADER_SIZE],
    NkemV3Header *header,
    int report_errors)
{
    if (memcmp(input, "NKEM", 4U) != 0) {
        if (report_errors != 0) {
            fprintf(stderr, "Invalid NKEM magic\n");
        }
        return 0;
    }
    if (input[4] != NKEM_V3_VERSION) {
        if (report_errors != 0) {
            fprintf(stderr, "Expected NKEM v3, found version %u\n",
                    input[4]);
        }
        return 0;
    }
    if (input[5] != NKEM_V3_ALGORITHM_ID) {
        if (report_errors != 0) {
            fprintf(stderr,
                    "Unsupported NKEM v3 algorithm id: %u\n",
                    input[5]);
        }
        return 0;
    }
    if (get_u16_be(input + 6U) != NKEM_V3_HEADER_SIZE) {
        if (report_errors != 0) {
            fprintf(stderr, "Invalid NKEM v3 header length\n");
        }
        return 0;
    }
    if (get_u16_be(input + 10U) != 0U || input[27] != 0U ||
        get_u32_be(input + 28U) != 0U) {
        if (report_errors != 0) {
            fprintf(stderr, "Unsupported NKEM v3 header flags\n");
        }
        return 0;
    }

    header->x448_ephemeral_len = get_u16_be(input + 8U);
    header->kem_ciphertext_len = get_u32_be(input + 12U);
    header->ciphertext_len = get_u64_be(input + 16U);
    header->salt_len = input[24];
    header->nonce_len = input[25];
    header->tag_len = input[26];

    if (header->x448_ephemeral_len !=
        NKEM_X448_EPHEMERAL_PUBLIC_SIZE) {
        if (report_errors != 0) {
            fprintf(stderr,
                    "Invalid X448 ephemeral public-key length\n");
        }
        return 0;
    }
    if (header->kem_ciphertext_len == 0U ||
        header->kem_ciphertext_len > NKEM_MAX_KEM_CIPHERTEXT_SIZE) {
        if (report_errors != 0) {
            fprintf(stderr, "Invalid ML-KEM ciphertext length\n");
        }
        return 0;
    }
    if (header->salt_len != NKEM_V3_SALT_SIZE ||
        header->nonce_len != NKEM_NONCE_SIZE ||
        header->tag_len != NKEM_TAG_SIZE) {
        if (report_errors != 0) {
            fprintf(stderr,
                    "Unsupported HKDF salt, AES-GCM nonce, or tag length\n");
        }
        return 0;
    }
    return 1;
}

int nkem_v3_header_decode(
    const unsigned char input[NKEM_V3_HEADER_SIZE],
    NkemV3Header *header)
{
    return nkem_v3_header_decode_internal(input, header, 1);
}

static int nkem_v3_container_size_is_valid_internal(
    const NkemV3Header *header,
    uint64_t actual_size,
    int report_errors)
{
    uint64_t expected_size = NKEM_V3_HEADER_SIZE;
    const uint64_t fields[] = {
        header->x448_ephemeral_len,
        header->kem_ciphertext_len,
        header->salt_len,
        header->nonce_len,
        header->ciphertext_len,
        header->tag_len
    };
    size_t index;

    for (index = 0U; index < (sizeof(fields) / sizeof(fields[0])); ++index) {
        if (UINT64_MAX - expected_size < fields[index]) {
            if (report_errors != 0) {
                fprintf(stderr,
                        "NKEM v3 container length overflows\n");
            }
            return 0;
        }
        expected_size += fields[index];
    }
    if (expected_size != actual_size) {
        if (report_errors != 0) {
            fprintf(stderr,
                    "NKEM v3 container is truncated or has trailing data\n");
        }
        return 0;
    }
    return 1;
}

int nkem_v3_container_size_is_valid(const NkemV3Header *header,
                                    uint64_t actual_size)
{
    return nkem_v3_container_size_is_valid_internal(
        header, actual_size, 1);
}

int nkem_v3_container_parse(const unsigned char *input, size_t input_len)
{
    NkemV3Header header;

    if (input == NULL || input_len < NKEM_V3_HEADER_SIZE ||
        (uintmax_t)input_len > UINT64_MAX ||
        !nkem_v3_header_decode_internal(input, &header, 0)) {
        return 0;
    }
    return nkem_v3_container_size_is_valid_internal(
        &header, (uint64_t)input_len, 0);
}
