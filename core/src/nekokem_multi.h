/*
 * Building blocks shared by the NKEM v4 and v5 multi-recipient containers.
 * Both use the same recipient entries, header MAC and payload; only the HKDF
 * labels differ, so no key derived for one version serves the other.
 */
#ifndef NEKOKEM_MULTI_H
#define NEKOKEM_MULTI_H

#include "file.h"
#include "hybrid.h"

#include <openssl/evp.h>
#include <stddef.h>
#include <stdint.h>

#define NKEM_MULTI_MAX_LABEL_SIZE 64U
#define NKEM_MULTI_MAC_KEY_SIZE 64U
/* header || salt: the prefix shared by every recipient entry's wrap AAD. */
#define NKEM_MULTI_PREFIX_SIZE (NKEM_V4_HEADER_SIZE + NKEM_V4_SALT_SIZE)

typedef struct {
    const char *wrap;
    const char *payload;
    const char *mac;
} NkemMultiLabels;

extern const NkemMultiLabels nkem_v4_labels;

int nkem_multi_hkdf_sha512(const unsigned char *key,
                           size_t key_len,
                           const unsigned char salt[NKEM_V4_SALT_SIZE],
                           const unsigned char *info,
                           size_t info_len,
                           unsigned char *output,
                           size_t output_len);

int nkem_multi_derive_file_keys(
    const NkemMultiLabels *labels,
    const unsigned char file_key[NKEM_V4_FILE_KEY_SIZE],
    const unsigned char salt[NKEM_V4_SALT_SIZE],
    unsigned char payload_key[AES256_KEY_SIZE],
    unsigned char mac_key[NKEM_MULTI_MAC_KEY_SIZE]);

int nkem_multi_header_mac(const unsigned char mac_key[NKEM_MULTI_MAC_KEY_SIZE],
                          const unsigned char *data,
                          size_t data_len,
                          unsigned char output[NKEM_V4_MAC_SIZE]);

int nkem_multi_x448_raw_public(EVP_PKEY *key,
                               unsigned char output[X448_PUBLIC_KEY_SIZE]);

/* Fills one recipient entry: X448 + ML-KEM encapsulation and wrapped key. */
int nkem_multi_seal_recipient(
    const NkemMultiLabels *labels,
    const HybridKeys *recipient,
    const unsigned char prefix[NKEM_MULTI_PREFIX_SIZE],
    const unsigned char file_key[NKEM_V4_FILE_KEY_SIZE],
    unsigned char entry[NKEM_V4_ENTRY_SIZE]);

/* Loads and validates every recipient before any output exists. */
HybridKeys *nkem_multi_load_recipients(const char *const *public_key_paths,
                                       size_t public_key_count);
void nkem_multi_recipients_cleanup(HybridKeys *recipients, size_t count);

int nkem_multi_open_metadata(const NkemMultiLabels *labels,
                             const HybridKeys *private_keys,
                             const unsigned char *metadata,
                             uint16_t recipient_count,
                             unsigned char payload_key[AES256_KEY_SIZE],
                             unsigned char file_key_out[NKEM_V4_FILE_KEY_SIZE]);

#endif
