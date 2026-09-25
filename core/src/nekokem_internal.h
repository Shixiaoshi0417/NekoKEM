#ifndef NEKOKEM_CORE_INTERNAL_H
#define NEKOKEM_CORE_INTERNAL_H

#include <stddef.h>

/*
 * Front-end bridge helper. This is intentionally absent from the public Core
 * API: it validates and unlocks one managed NKPR key, derives the same
 * canonical public-key fingerprint used by nekokem_public_key_fingerprint(),
 * and releases the parsed keys before returning.
 */
int nekokem_internal_private_key_fingerprint(
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len,
    char *output,
    size_t output_size);

#endif
