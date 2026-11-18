#ifndef NEKOKEM_HYBRID_H
#define NEKOKEM_HYBRID_H

#include "file.h"
#include "kem.h"

#include <openssl/evp.h>
#include <stddef.h>

#define X448_ALGORITHM_NAME "X448"
#define X448_PUBLIC_KEY_SIZE NKEM_X448_EPHEMERAL_PUBLIC_SIZE
#define X448_SHARED_SECRET_SIZE 56U
#define ED448_ALGORITHM_NAME "ED448"
#define MLDSA_ALGORITHM_NAME "ML-DSA-87"

/*
 * x448 and mlkem are always present. ed448 and mldsa are present together
 * for a four-component identity key (NKEM v5) and NULL for a two-component
 * encryption-only key.
 */
typedef struct {
    EVP_PKEY *x448;
    EVP_PKEY *mlkem;
    EVP_PKEY *ed448;
    EVP_PKEY *mldsa;
} HybridKeys;

int hybrid_keys_have_identity(const HybridKeys *keys);

void hybrid_keys_cleanup(HybridKeys *keys);

/*
 * Generates a four-component identity key pair (NKPR v3). replace == 0
 * never overwrites: an existing key file fails the commit.
 */
int hybrid_generate_keypair(const char *public_path,
                            const char *private_path,
                            const unsigned char *password,
                            size_t password_len,
                            int replace);
/*
 * Adds signing keys to an NKPR v1 key pair and writes both files: four
 * public PEM blocks and an NKPR v3 private key, committed together.
 */
int hybrid_upgrade_to_identity(const char *public_path,
                               const char *private_path,
                               const unsigned char *password,
                               size_t password_len);
/*
 * Rewrites an NKPR v2 identity key pair as NKPR v3 with the same keys and
 * password; the public file must hold this private key's four public keys.
 */
int hybrid_rewrite_identity_keys(const char *public_path,
                                 const char *private_path,
                                 const unsigned char *password,
                                 size_t password_len);
/* Writes the key in output_path as NKPR v2 (identity) or v1 (two parts). */
int hybrid_export_private_key_legacy(const char *private_path,
                                     const unsigned char *password,
                                     size_t password_len,
                                     const char *output_path);
/* Two or four PEM public keys; a fifth or anything else fails. */
int hybrid_parse_public_keys(const unsigned char *pem,
                             size_t pem_len,
                             HybridKeys *keys);
int hybrid_load_public_keys(const char *path, HybridKeys *keys);
/* include_identity != 0 writes all four components, else the legacy two. */
int hybrid_write_public_keys(FILE *output, const HybridKeys *keys,
                             int include_identity);
int hybrid_load_private_keys(const char *path, HybridKeys *keys);
/*
 * Returned by the private-key loaders when the file is NKPR v1 or v2 and the
 * caller did not accept older formats. Checked before the password is used;
 * see docs/key-commitment.md.
 */
#define HYBRID_LEGACY_KEY_REFUSED (-1)

/* Returns 1, 0 or HYBRID_LEGACY_KEY_REFUSED. */
int hybrid_load_protected_private_keys(
    const char *path,
    const unsigned char *password,
    size_t password_len,
    int accept_legacy,
    HybridKeys *keys);
/*
 * NKPR keys require a password; a plaintext Hybrid PEM key does not. The
 * format is read from the file contents, never inferred from its name.
 * Returns 1, 0 or HYBRID_LEGACY_KEY_REFUSED.
 */
int hybrid_load_decryption_keys(
    const char *path,
    const unsigned char *password,
    size_t password_len,
    int accept_legacy,
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
