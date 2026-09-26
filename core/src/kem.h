#ifndef NEKOKEM_KEM_H
#define NEKOKEM_KEM_H

#include <openssl/evp.h>
#include <stddef.h>

#define KEM_ALGORITHM_NAME "ML-KEM-1024"
#define KEM_CIPHERTEXT_SIZE 1568U
#define KEM_SHARED_SECRET_SIZE 32U
#define KEM_MAX_SHARED_SECRET_SIZE 4096U
#define AES256_KEY_SIZE 32U

int kem_encapsulate(EVP_PKEY *public_key,
                    unsigned char **ciphertext,
                    size_t *ciphertext_len,
                    unsigned char **shared_secret,
                    size_t *shared_secret_len);
int kem_decapsulate(EVP_PKEY *private_key,
                    const unsigned char *ciphertext,
                    size_t ciphertext_len,
                    unsigned char **shared_secret,
                    size_t *shared_secret_len);

#endif
