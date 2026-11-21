#ifndef NEKOKEM_CORE_INTERNAL_H
#define NEKOKEM_CORE_INTERNAL_H

#include <stddef.h>
#include <stdint.h>

/*
 * Front-end bridge helper. This is intentionally absent from the public Core
 * API: it validates and unlocks one managed NKPR key, derives the same
 * canonical public-key fingerprint used by nekokem_public_key_fingerprint(),
 * and releases the parsed keys before returning. As every call that unlocks
 * a key, it refuses NKPR v1 and v2 unless flags holds
 * NEKOKEM_PRIVATE_KEY_LEGACY (the installation's own key); see
 * docs/key-commitment.md. Returns 1, 0 or NEKOKEM_ERROR_LEGACY_PRIVATE_KEY.
 */
int nekokem_internal_private_key_fingerprint(
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len,
    uint32_t flags,
    char *output,
    size_t output_size);

#endif
