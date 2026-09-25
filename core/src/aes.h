#ifndef NEKOKEM_AES_H
#define NEKOKEM_AES_H

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

#define AES_GCM_KEY_SIZE 32U
#define AES_GCM_NONCE_SIZE 12U
#define AES_GCM_TAG_SIZE 16U

#define AES_GCM_FILE_ERROR 0
#define AES_GCM_FILE_SUCCESS 1
#define AES_GCM_FILE_CANCELLED (-1)

typedef int (*AesProgressCallback)(uint64_t processed_bytes,
                                   uint64_t total_bytes,
                                   void *user_data);

int aes_gcm_encrypt_file(FILE *input,
                         FILE *output,
                         uint64_t plaintext_len,
                         const unsigned char key[AES_GCM_KEY_SIZE],
                         const unsigned char nonce[AES_GCM_NONCE_SIZE],
                         const unsigned char *aad,
                         size_t aad_len,
                         unsigned char tag[AES_GCM_TAG_SIZE]);

int aes_gcm_encrypt_file_with_progress(
    FILE *input,
    FILE *output,
    uint64_t plaintext_len,
    const unsigned char key[AES_GCM_KEY_SIZE],
    const unsigned char nonce[AES_GCM_NONCE_SIZE],
    const unsigned char *aad,
    size_t aad_len,
    unsigned char tag[AES_GCM_TAG_SIZE],
    AesProgressCallback progress_callback,
    void *progress_user_data);

int aes_gcm_decrypt_file(FILE *input,
                         FILE *output,
                         uint64_t ciphertext_len,
                         const unsigned char key[AES_GCM_KEY_SIZE],
                         const unsigned char nonce[AES_GCM_NONCE_SIZE],
                         const unsigned char *aad,
                         size_t aad_len);

int aes_gcm_decrypt_file_with_progress(
    FILE *input,
    FILE *output,
    uint64_t ciphertext_len,
    const unsigned char key[AES_GCM_KEY_SIZE],
    const unsigned char nonce[AES_GCM_NONCE_SIZE],
    const unsigned char *aad,
    size_t aad_len,
    AesProgressCallback progress_callback,
    void *progress_user_data);

#endif
