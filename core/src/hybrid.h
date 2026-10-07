#ifndef NEKOKEM_HYBRID_H
#define NEKOKEM_HYBRID_H

#include "file.h"
#include "kem.h"

#include <openssl/evp.h>
#include <stddef.h>

#define X448_ALGORITHM_NAME "X448"
#define X448_PUBLIC_KEY_SIZE NKEM_X448_EPHEMERAL_PUBLIC_SIZE
#define X448_SHARED_SECRET_SIZE 56U

typedef struct {
    EVP_PKEY *x448;
    EVP_PKEY *mlkem;
} HybridKeys;

void hybrid_keys_cleanup(HybridKeys *keys);

/* replace == 0 never overwrites: an existing key file fails the commit. */
int hybrid_generate_keypair(const char *public_path,
                            const char *private_path,
                            const unsigned char *password,
                            size_t password_len,
                            int replace);
int hybrid_load_public_keys(const char *path, HybridKeys *keys);
int hybrid_load_private_keys(const char *path, HybridKeys *keys);
int hybrid_load_protected_private_keys(
    const char *path,
    const unsigned char *password,
    size_t password_len,
    HybridKeys *keys);
/*
 * NKPR keys require a password; a plaintext Hybrid PEM key does not. The
 * format is read from the file contents, never inferred from its name.
 */
int hybrid_load_decryption_keys(
    const char *path,
    const unsigned char *password,
    size_t password_len,
    HybridKeys *keys);

int hybrid_x448_encapsulate(
    EVP_PKEY *recipient_public_key,
    unsigned char ephemeral_public[X448_PUBLIC_KEY_SIZE],
    unsigned char **shared_secret,
    size_t *shared_secret_len);
int hybrid_x448_decapsulate(
    EVP_PKEY *recipient_private_key,
    const unsigned char ephemeral_public[X448_PUBLIC_KEY_SIZE],
    size_t ephemeral_public_len,
    unsigned char **shared_secret,
    size_t *shared_secret_len);

int hybrid_derive_aes256_key(
    const unsigned char *x448_secret,
    size_t x448_secret_len,
    const unsigned char *mlkem_secret,
    size_t mlkem_secret_len,
    const unsigned char *salt,
    size_t salt_len,
    unsigned char output_key[AES256_KEY_SIZE]);

#endif
