#ifndef NEKOKEM_AES_H
#define NEKOKEM_AES_H

#include <stdint.h>
#include <stdio.h>
#include <stddef.h>

#define AES_GCM_KEY_SIZE 32U
#define AES_GCM_NONCE_SIZE 12U
#define AES_GCM_TAG_SIZE 16U

int aes_gcm_encrypt_file(FILE *input,
                         FILE *output,
                         uint64_t plaintext_len,
                         const unsigned char key[AES_GCM_KEY_SIZE],
                         const unsigned char nonce[AES_GCM_NONCE_SIZE],
                         const unsigned char *aad,
                         size_t aad_len,
                         unsigned char tag[AES_GCM_TAG_SIZE]);

int aes_gcm_decrypt_file(FILE *input,
                         FILE *output,
                         uint64_t ciphertext_len,
                         const unsigned char key[AES_GCM_KEY_SIZE],
                         const unsigned char nonce[AES_GCM_NONCE_SIZE],
                         const unsigned char *aad,
                         size_t aad_len);

#endif
