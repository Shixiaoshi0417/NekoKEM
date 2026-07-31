#ifndef NEKOKEM_PRIVATE_KEY_H
#define NEKOKEM_PRIVATE_KEY_H

#include <stddef.h>

#define NKPR_MAGIC "NKPR"
#define NKPR_VERSION 1U
#define NKPR_HEADER_SIZE 36U
#define NKPR_SALT_SIZE 32U
#define NKPR_NONCE_SIZE 12U
#define NKPR_TAG_SIZE 16U
#define NKPR_ARGON2_MEMORY_KIB 65536U
#define NKPR_ARGON2_ITERATIONS 3U
#define NKPR_ARGON2_PARALLELISM 4U
#define NKPR_MAX_PEM_SIZE (1024U * 1024U)

int protected_private_key_write(
    const char *path,
    const unsigned char *pem,
    size_t pem_len,
    const unsigned char *password,
    size_t password_len);

int protected_private_key_read(
    const char *path,
    const unsigned char *password,
    size_t password_len,
    unsigned char **pem,
    size_t *pem_len);

int private_key_path_is_encrypted(const char *path);

/*
 * Parse and validate only the NKPR header, parameters, and total structure.
 * This function is deliberately quiet and performs no KDF or decryption.
 */
int protected_private_key_container_parse(
    const unsigned char *input,
    size_t input_len);

#endif
