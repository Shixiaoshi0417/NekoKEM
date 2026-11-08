#ifndef NEKOKEM_V5_INTERNAL_H
#define NEKOKEM_V5_INTERNAL_H

#include "file.h"
#include "nekokem.h"

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

/*
 * Decrypts and verifies an opened NKEM v5 container whose 32-byte header has
 * already been read; see docs/NKEM-v5.md. policy and result may be NULL.
 * The caller owns and closes the input stream. Returns the public
 * NEKOKEM_OPERATION_* or NEKOKEM_ERROR_* codes.
 */
int nekokem_v5_decrypt_opened(
    FILE *input,
    uint64_t container_size,
    const unsigned char raw_header[NKEM_V4_HEADER_SIZE],
    const char *output_path,
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len,
    const NekoKEMVerifyPolicy *policy,
    NekoKEMVerifyResult *result,
    NekoKEMProgressCallback progress_callback,
    void *progress_user_data);

/* Validates the struct size and flags of a caller's policy; NULL is valid. */
int nekokem_v5_policy_is_valid(const NekoKEMVerifyPolicy *policy);

#endif
