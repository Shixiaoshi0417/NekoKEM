#include "aes.h"

#include "file.h"
#include "secure_mem.h"

#include <limits.h>
#include <openssl/evp.h>

#define IO_BUFFER_SIZE 65536U

static int add_encrypt_aad(EVP_CIPHER_CTX *context,
                           const unsigned char *aad,
                           size_t aad_len)
{
    int output_len = 0;

    if (aad_len > (size_t)INT_MAX) {
        fprintf(stderr, "Authenticated metadata is too large\n");
        return 0;
    }
    if (EVP_EncryptUpdate(context, NULL, &output_len, aad,
                          (int)aad_len) <= 0) {
        print_openssl_error("Cannot authenticate NKEM metadata");
        return 0;
    }
    return 1;
}

static int add_decrypt_aad(EVP_CIPHER_CTX *context,
                           const unsigned char *aad,
                           size_t aad_len)
{
    int output_len = 0;

    if (aad_len > (size_t)INT_MAX) {
        fprintf(stderr, "Authenticated metadata is too large\n");
        return 0;
    }
    if (EVP_DecryptUpdate(context, NULL, &output_len, aad,
                          (int)aad_len) <= 0) {
        print_openssl_error("Cannot authenticate NKEM metadata");
        return 0;
    }
    return 1;
}

int aes_gcm_encrypt_file(FILE *input,
                         FILE *output,
                         uint64_t plaintext_len,
                         const unsigned char key[AES_GCM_KEY_SIZE],
                         const unsigned char nonce[AES_GCM_NONCE_SIZE],
                         const unsigned char *aad,
                         size_t aad_len,
                         unsigned char tag[AES_GCM_TAG_SIZE])
{
    EVP_CIPHER_CTX *context = NULL;
    unsigned char input_buffer[IO_BUFFER_SIZE];
    unsigned char output_buffer[IO_BUFFER_SIZE + EVP_MAX_BLOCK_LENGTH];
    uint64_t remaining = plaintext_len;
    int output_len = 0;
    int success = 0;

    context = EVP_CIPHER_CTX_new();
    if (context == NULL) {
        print_openssl_error("Cannot create AES-GCM encryption context");
        goto cleanup;
    }
    if (EVP_EncryptInit_ex(context, EVP_aes_256_gcm(), NULL,
                           NULL, NULL) <= 0 ||
        EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_SET_IVLEN,
                            (int)AES_GCM_NONCE_SIZE, NULL) <= 0 ||
        EVP_EncryptInit_ex(context, NULL, NULL, key, nonce) <= 0) {
        print_openssl_error("Cannot initialize AES-256-GCM encryption");
        goto cleanup;
    }
    if (!add_encrypt_aad(context, aad, aad_len)) {
        goto cleanup;
    }

    while (remaining > 0U) {
        size_t wanted = remaining > (uint64_t)sizeof(input_buffer)
                            ? sizeof(input_buffer)
                            : (size_t)remaining;
        size_t count = fread(input_buffer, 1U, wanted, input);

        if (count != wanted) {
            if (ferror(input) != 0) {
                print_system_error("Cannot read plaintext");
            } else {
                fprintf(stderr, "Plaintext changed while being encrypted\n");
            }
            goto cleanup;
        }
        if (EVP_EncryptUpdate(context, output_buffer, &output_len,
                              input_buffer, (int)count) <= 0) {
            print_openssl_error("AES-256-GCM encryption failed");
            goto cleanup;
        }
        if (output_len < 0 ||
            !file_write_all(output, output_buffer, (size_t)output_len)) {
            goto cleanup;
        }
        remaining -= (uint64_t)count;
    }

    if (fgetc(input) != EOF) {
        fprintf(stderr, "Plaintext grew while being encrypted\n");
        goto cleanup;
    }
    if (ferror(input) != 0) {
        print_system_error("Cannot verify the end of plaintext");
        goto cleanup;
    }
    if (EVP_EncryptFinal_ex(context, output_buffer, &output_len) <= 0) {
        print_openssl_error("Cannot finalize AES-256-GCM encryption");
        goto cleanup;
    }
    if (output_len < 0 ||
        !file_write_all(output, output_buffer, (size_t)output_len)) {
        goto cleanup;
    }
    if (EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_GET_TAG,
                            (int)AES_GCM_TAG_SIZE, tag) <= 0) {
        print_openssl_error("Cannot retrieve AES-GCM authentication tag");
        goto cleanup;
    }
    success = 1;

cleanup:
    EVP_CIPHER_CTX_free(context);
    secure_mem_clear(input_buffer, sizeof(input_buffer));
    secure_mem_clear(output_buffer, sizeof(output_buffer));
    return success;
}

int aes_gcm_decrypt_file(FILE *input,
                         FILE *output,
                         uint64_t ciphertext_len,
                         const unsigned char key[AES_GCM_KEY_SIZE],
                         const unsigned char nonce[AES_GCM_NONCE_SIZE],
                         const unsigned char *aad,
                         size_t aad_len)
{
    EVP_CIPHER_CTX *context = NULL;
    unsigned char input_buffer[IO_BUFFER_SIZE];
    unsigned char output_buffer[IO_BUFFER_SIZE + EVP_MAX_BLOCK_LENGTH];
    unsigned char tag[AES_GCM_TAG_SIZE];
    uint64_t remaining = ciphertext_len;
    int output_len = 0;
    int success = 0;

    context = EVP_CIPHER_CTX_new();
    if (context == NULL) {
        print_openssl_error("Cannot create AES-GCM decryption context");
        goto cleanup;
    }
    if (EVP_DecryptInit_ex(context, EVP_aes_256_gcm(), NULL,
                           NULL, NULL) <= 0 ||
        EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_SET_IVLEN,
                            (int)AES_GCM_NONCE_SIZE, NULL) <= 0 ||
        EVP_DecryptInit_ex(context, NULL, NULL, key, nonce) <= 0) {
        print_openssl_error("Cannot initialize AES-256-GCM decryption");
        goto cleanup;
    }
    if (!add_decrypt_aad(context, aad, aad_len)) {
        goto cleanup;
    }

    while (remaining > 0U) {
        size_t wanted = remaining > (uint64_t)sizeof(input_buffer)
                            ? sizeof(input_buffer)
                            : (size_t)remaining;

        if (!file_read_exact(input, input_buffer, wanted)) {
            goto cleanup;
        }
        if (EVP_DecryptUpdate(context, output_buffer, &output_len,
                              input_buffer, (int)wanted) <= 0) {
            print_openssl_error("AES-256-GCM decryption failed");
            goto cleanup;
        }
        if (output_len < 0 ||
            !file_write_all(output, output_buffer, (size_t)output_len)) {
            goto cleanup;
        }
        remaining -= (uint64_t)wanted;
    }

    if (!file_read_exact(input, tag, sizeof(tag))) {
        goto cleanup;
    }
    if (EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_SET_TAG,
                            (int)sizeof(tag), tag) <= 0) {
        print_openssl_error("Cannot set AES-GCM authentication tag");
        goto cleanup;
    }
    if (EVP_DecryptFinal_ex(context, output_buffer, &output_len) <= 0) {
        print_openssl_error(
            "Authentication failed: wrong key or modified NKEM file");
        goto cleanup;
    }
    if (output_len < 0 ||
        !file_write_all(output, output_buffer, (size_t)output_len)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    EVP_CIPHER_CTX_free(context);
    secure_mem_clear(input_buffer, sizeof(input_buffer));
    secure_mem_clear(output_buffer, sizeof(output_buffer));
    secure_mem_clear(tag, sizeof(tag));
    return success;
}
