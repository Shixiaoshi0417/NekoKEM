#ifndef NEKOKEM_V4_INTERNAL_H
#define NEKOKEM_V4_INTERNAL_H

#include "file.h"
#include "nekokem.h"

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

_Static_assert(NKEM_V4_HEADER_SIZE == NKEM_V3_HEADER_SIZE,
               "Version dispatch reads one fixed-size header");
_Static_assert(NEKOKEM_MAX_RECIPIENTS == NKEM_V4_MAX_RECIPIENTS,
               "Public and container recipient limits must match");

/*
 * Decrypts an opened NKEM v4 container whose 32-byte header has already been
 * read. The caller owns and closes the input stream. Returns the public
 * NEKOKEM_OPERATION_* result codes.
 */
int nekokem_v4_decrypt_opened(
    FILE *input,
    uint64_t container_size,
    const unsigned char raw_header[NKEM_V4_HEADER_SIZE],
    const char *output_path,
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len,
    NekoKEMProgressCallback progress_callback,
    void *progress_user_data);

#endif
