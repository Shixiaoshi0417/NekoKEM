#ifndef NEKOKEM_X448_ENCODING_H
#define NEKOKEM_X448_ENCODING_H

#include <stddef.h>

#define X448_ENCODED_SIZE 56U

/* RFC 7748 encodes u little-endian; generated keys always satisfy u < p. */
static inline int x448_public_is_canonical(const unsigned char *encoded)
{
    size_t index = X448_ENCODED_SIZE;

    if (encoded == NULL) {
        return 0;
    }
    while (index-- > 0U) {
        unsigned char prime = index == 28U ? 0xfeU : 0xffU;

        if (encoded[index] != prime) {
            return encoded[index] < prime;
        }
    }
    return 0;
}

/* The canonical low-order u coordinates on Curve448 are 0, 1 and p - 1. */
static inline int x448_public_has_small_order(const unsigned char *encoded)
{
    size_t index;
    unsigned char high_bits = 0U;
    unsigned char minus_one_difference = 0U;

    for (index = 0U; index < X448_ENCODED_SIZE; ++index) {
        unsigned char minus_one = index == 0U || index == 28U ? 0xfeU : 0xffU;

        if (index != 0U) {
            high_bits |= encoded[index];
        }
        minus_one_difference |= (unsigned char)(encoded[index] ^ minus_one);
    }
    return (high_bits == 0U && encoded[0] <= 1U) ||
           minus_one_difference == 0U;
}

#endif
