/* NekoKEM v5 identity keys and hybrid signatures; see docs/NKEM-v5.md. */
#ifndef NEKOKEM_IDENTITY_H
#define NEKOKEM_IDENTITY_H

#include "hybrid.h"

#include <stddef.h>

#define IDENTITY_MAGIC "NKID"
#define IDENTITY_ENCODING_VERSION 1U
#define IDENTITY_COMPONENT_COUNT 4U
#define IDENTITY_ID_X448 0x01U
#define IDENTITY_ID_MLKEM1024 0x02U
#define IDENTITY_ID_ED448 0x03U
#define IDENTITY_ID_MLDSA87 0x04U

#define IDENTITY_MLKEM1024_PUBLIC_SIZE 1568U
#define IDENTITY_ED448_PUBLIC_SIZE 57U
#define IDENTITY_MLDSA87_PUBLIC_SIZE 2592U
#define IDENTITY_ED448_SIGNATURE_SIZE 114U
#define IDENTITY_MLDSA87_SIGNATURE_SIZE 4627U
#define IDENTITY_SIGNATURE_SIZE \
    (IDENTITY_ED448_SIGNATURE_SIZE + IDENTITY_MLDSA87_SIGNATURE_SIZE)

/* "NKID" || version || count || 4 x (id || u16 length || raw key) */
#define IDENTITY_ENCODING_SIZE                                         \
    (4U + 1U + 1U + 4U * 3U + X448_PUBLIC_KEY_SIZE +                    \
     IDENTITY_MLKEM1024_PUBLIC_SIZE + IDENTITY_ED448_PUBLIC_SIZE +       \
     IDENTITY_MLDSA87_PUBLIC_SIZE)

/* 32 SHA-256 bytes as uppercase, colon-separated hex, plus NUL. */
#define IDENTITY_FINGERPRINT_STRING_SIZE 96U

/* Writes the canonical encoding of a four-component key. */
int identity_encode(const HybridKeys *keys,
                    unsigned char output[IDENTITY_ENCODING_SIZE]);

/*
 * Strictly decodes and validates a canonical encoding into public keys.
 * Quiet: returns 0 without printing for any malformed or invalid input.
 */
int identity_decode(const unsigned char input[IDENTITY_ENCODING_SIZE],
                    HybridKeys *keys);

int identity_fingerprint(const unsigned char encoding[IDENTITY_ENCODING_SIZE],
                         char *output,
                         size_t output_size);

/* Formats 32 digest bytes as uppercase, colon-separated hex. */
int identity_format_fingerprint(const unsigned char digest[32],
                                char *output,
                                size_t output_size);

/* Raw-byte checks used by identity_decode and key loading. */
int identity_ed448_public_is_valid(
    const unsigned char encoded[IDENTITY_ED448_PUBLIC_SIZE]);
/* Whether a loaded Ed448 key passes identity_ed448_public_is_valid. */
int identity_ed448_key_is_valid(EVP_PKEY *key);

/* Ed448 signature || ML-DSA-87 signature over message. */
int identity_sign(const HybridKeys *keys,
                  const unsigned char *message,
                  size_t message_len,
                  unsigned char signature[IDENTITY_SIGNATURE_SIZE]);

/* 1 when both signatures verify, 0 otherwise. Quiet. */
int identity_verify(const HybridKeys *keys,
                    const unsigned char *message,
                    size_t message_len,
                    const unsigned char signature[IDENTITY_SIGNATURE_SIZE]);

#endif
