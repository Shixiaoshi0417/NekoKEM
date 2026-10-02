#include "aes.h"
#include "file.h"

#include <limits.h>
#include <openssl/evp.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define STREAM_CHUNK_SIZE 65536U
#define MAX_TEST_SIZE (3U * STREAM_CHUNK_SIZE + 17U)
#define TEST_AAD_SIZE 37U

typedef struct ProgressState {
    uint64_t total;
    uint64_t next;
    uint64_t last;
    uint64_t cancel_at;
    size_t calls;
    int finished;
    int invalid;
} ProgressState;

static int check_progress(uint64_t processed, uint64_t total, void *user_data)
{
    ProgressState *state = user_data;
    uint64_t remaining;

    if (total != state->total || processed != state->next ||
        processed > total || state->finished != 0) {
        state->invalid = 1;
        return 0;
    }
    state->last = processed;
    ++state->calls;
    remaining = total - processed;
    state->next += remaining > STREAM_CHUNK_SIZE
                       ? STREAM_CHUNK_SIZE : remaining;
    state->finished = processed == total;
    return state->cancel_at == UINT64_MAX || processed < state->cancel_at;
}

static void fill_pattern(unsigned char *data, size_t length)
{
    size_t index;

    for (index = 0U; index < length; ++index) {
        data[index] = (unsigned char)(1U +
            ((index * 37U + (index >> 7U) * 13U) % 255U));
    }
}

/* Use a single out-of-place EVP update, independent of the streaming loop. */
static int reference_encrypt(
    const unsigned char *plaintext, size_t length,
    const unsigned char key[AES_GCM_KEY_SIZE],
    const unsigned char nonce[AES_GCM_NONCE_SIZE],
    const unsigned char *aad, size_t aad_length,
    unsigned char *ciphertext)
{
    EVP_CIPHER_CTX *context = NULL;
    int written = 0;
    int final_length = 0;
    int success = 0;

    if (length > (size_t)INT_MAX || aad_length > (size_t)INT_MAX) {
        return 0;
    }
    context = EVP_CIPHER_CTX_new();
    if (context == NULL ||
        EVP_EncryptInit_ex(context, EVP_aes_256_gcm(), NULL,
                           key, nonce) != 1 ||
        EVP_EncryptUpdate(context, NULL, &written,
                          aad, (int)aad_length) != 1 ||
        EVP_EncryptUpdate(context, ciphertext, &written,
                          plaintext, (int)length) != 1 ||
        written < 0 || (size_t)written != length ||
        EVP_EncryptFinal_ex(context, ciphertext + length,
                            &final_length) != 1 ||
        final_length != 0 ||
        EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_GET_TAG,
                            (int)AES_GCM_TAG_SIZE,
                            ciphertext + length) != 1) {
        goto cleanup;
    }
    success = 1;

cleanup:
    EVP_CIPHER_CTX_free(context);
    return success;
}

static FILE *make_stream(const unsigned char *bytes, size_t length)
{
    FILE *stream = tmpfile();

    if (stream == NULL) {
        return NULL;
    }
    if (!file_disable_buffering(stream) ||
        (length != 0U && fwrite(bytes, 1U, length, stream) != length) ||
        fseek(stream, 0L, SEEK_SET) != 0) {
        (void)fclose(stream);
        return NULL;
    }
    return stream;
}

static int stream_matches(FILE *stream, const unsigned char *expected,
                           size_t length)
{
    unsigned char buffer[4096];
    size_t position = 0U;

    if (fseek(stream, 0L, SEEK_SET) != 0) {
        return 0;
    }
    while (position < length) {
        size_t wanted = length - position;

        if (wanted > sizeof(buffer)) {
            wanted = sizeof(buffer);
        }
        if (fread(buffer, 1U, wanted, stream) != wanted ||
            memcmp(buffer, expected + position, wanted) != 0) {
            return 0;
        }
        position += wanted;
    }
    return fgetc(stream) == EOF && ferror(stream) == 0;
}

static int run_stream(
    const char *label, int decrypt,
    const unsigned char *input_bytes, size_t input_length,
    size_t data_length, const unsigned char *expected,
    const unsigned char *expected_tag,
    const unsigned char key[AES_GCM_KEY_SIZE],
    const unsigned char nonce[AES_GCM_NONCE_SIZE],
    const unsigned char *aad, size_t aad_length,
    int expected_result, uint64_t cancel_at)
{
    FILE *input = NULL;
    FILE *output = NULL;
    unsigned char tag[AES_GCM_TAG_SIZE];
    ProgressState state = {(uint64_t)data_length, 0U, 0U,
                           cancel_at, 0U, 0, 0};
    size_t expected_length = data_length;
    int result;
    int success = 0;

    memset(tag, 0xa5, sizeof(tag));
    input = make_stream(input_bytes, input_length);
    output = make_stream(NULL, 0U);
    if (input == NULL || output == NULL) {
        goto cleanup;
    }
    if (decrypt != 0) {
        result = aes_gcm_decrypt_file_with_progress(
            input, output, (uint64_t)data_length,
            key, nonce, aad, aad_length, check_progress, &state);
    } else {
        result = aes_gcm_encrypt_file_with_progress(
            input, output, (uint64_t)data_length,
            key, nonce, aad, aad_length, tag, check_progress, &state);
    }
    if (result != expected_result || state.invalid != 0 ||
        state.calls == 0U) {
        goto cleanup;
    }
    if (expected_result == AES_GCM_FILE_ERROR) {
        success = 1;
        goto cleanup;
    }
    if (expected_result == AES_GCM_FILE_CANCELLED) {
        if (state.last != cancel_at || cancel_at > data_length ||
            ftell(input) != (long)cancel_at) {
            goto cleanup;
        }
        expected_length = (size_t)cancel_at;
    } else if (state.finished == 0 || state.last != data_length ||
               (decrypt == 0 &&
                memcmp(tag, expected_tag, sizeof(tag)) != 0)) {
        goto cleanup;
    }
    if (state.calls != 1U +
            expected_length / STREAM_CHUNK_SIZE +
            (expected_length % STREAM_CHUNK_SIZE != 0U ? 1U : 0U) ||
        !stream_matches(output, expected, expected_length)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    if (output != NULL && fclose(output) != 0) {
        success = 0;
    }
    if (input != NULL && fclose(input) != 0) {
        success = 0;
    }
    if (success == 0) {
        fprintf(stderr, "AES stream test failed: %s (%s, %zu bytes)\n",
                label, decrypt != 0 ? "decrypt" : "encrypt", data_length);
    }
    return success;
}

static int test_lengths(
    unsigned char *plaintext, unsigned char *ciphertext,
    const unsigned char key[AES_GCM_KEY_SIZE],
    const unsigned char nonce[AES_GCM_NONCE_SIZE],
    const unsigned char aad[TEST_AAD_SIZE])
{
    static const size_t lengths[] = {
        0U, 1U, 15U, 16U, 17U, 65535U, 65536U, 65537U,
        2U * STREAM_CHUNK_SIZE, MAX_TEST_SIZE
    };
    size_t index;

    for (index = 0U; index < sizeof(lengths) / sizeof(lengths[0]); ++index) {
        size_t length = lengths[index];

        if (!reference_encrypt(plaintext, length, key, nonce,
                                aad, TEST_AAD_SIZE, ciphertext) ||
            !run_stream("reference ciphertext and tag", 0,
                        plaintext, length, length, ciphertext,
                        ciphertext + length, key, nonce, aad, TEST_AAD_SIZE,
                        AES_GCM_FILE_SUCCESS, UINT64_MAX) ||
            !run_stream("reference ciphertext decrypts", 1,
                        ciphertext, length + AES_GCM_TAG_SIZE, length,
                        plaintext, NULL, key, nonce, aad, TEST_AAD_SIZE,
                        AES_GCM_FILE_SUCCESS, UINT64_MAX)) {
            return 0;
        }
    }
    return 1;
}

static int test_rejections(
    unsigned char *plaintext, unsigned char *ciphertext,
    const unsigned char key[AES_GCM_KEY_SIZE],
    const unsigned char nonce[AES_GCM_NONCE_SIZE],
    const unsigned char aad[TEST_AAD_SIZE])
{
    const size_t length = STREAM_CHUNK_SIZE + 1U;
    unsigned char changed_aad[TEST_AAD_SIZE];
    int success;

    if (!reference_encrypt(plaintext, length, key, nonce,
                            aad, TEST_AAD_SIZE, ciphertext)) {
        return 0;
    }
    ciphertext[length + AES_GCM_TAG_SIZE - 1U] ^= 0x80U;
    success = run_stream("tampered authentication tag", 1,
                         ciphertext, length + AES_GCM_TAG_SIZE, length,
                         NULL, NULL, key, nonce, aad, TEST_AAD_SIZE,
                         AES_GCM_FILE_ERROR, UINT64_MAX);
    ciphertext[length + AES_GCM_TAG_SIZE - 1U] ^= 0x80U;
    if (success == 0) {
        return 0;
    }
    ciphertext[STREAM_CHUNK_SIZE] ^= 0x40U;
    success = run_stream("tampered final ciphertext byte", 1,
                         ciphertext, length + AES_GCM_TAG_SIZE, length,
                         NULL, NULL, key, nonce, aad, TEST_AAD_SIZE,
                         AES_GCM_FILE_ERROR, UINT64_MAX);
    ciphertext[STREAM_CHUNK_SIZE] ^= 0x40U;
    if (success == 0) {
        return 0;
    }
    memcpy(changed_aad, aad, sizeof(changed_aad));
    changed_aad[0] ^= 0x20U;
    return run_stream("tampered authenticated metadata", 1,
                      ciphertext, length + AES_GCM_TAG_SIZE, length,
                      NULL, NULL, key, nonce, changed_aad, sizeof(changed_aad),
                      AES_GCM_FILE_ERROR, UINT64_MAX) &&
           run_stream("truncated ciphertext", 1,
                      ciphertext, length - 1U, length,
                      NULL, NULL, key, nonce, aad, TEST_AAD_SIZE,
                      AES_GCM_FILE_ERROR, UINT64_MAX) &&
           run_stream("truncated authentication tag", 1,
                      ciphertext, length + AES_GCM_TAG_SIZE - 1U, length,
                      NULL, NULL, key, nonce, aad, TEST_AAD_SIZE,
                      AES_GCM_FILE_ERROR, UINT64_MAX) &&
           run_stream("truncated plaintext", 0,
                      plaintext, length - 1U, length,
                      NULL, NULL, key, nonce, aad, TEST_AAD_SIZE,
                      AES_GCM_FILE_ERROR, UINT64_MAX) &&
           run_stream("plaintext grew", 0,
                      plaintext, length + 1U, length,
                      NULL, NULL, key, nonce, aad, TEST_AAD_SIZE,
                      AES_GCM_FILE_ERROR, UINT64_MAX);
}

static int test_cancellation(
    unsigned char *plaintext, unsigned char *ciphertext,
    const unsigned char key[AES_GCM_KEY_SIZE],
    const unsigned char nonce[AES_GCM_NONCE_SIZE],
    const unsigned char aad[TEST_AAD_SIZE])
{
    const size_t length = 2U * STREAM_CHUNK_SIZE + 17U;
    const uint64_t cancel_at[] = {0U, STREAM_CHUNK_SIZE, (uint64_t)length};
    size_t index;

    if (!reference_encrypt(plaintext, length, key, nonce,
                            aad, TEST_AAD_SIZE, ciphertext)) {
        return 0;
    }
    for (index = 0U; index < sizeof(cancel_at) / sizeof(cancel_at[0]); ++index) {
        if (!run_stream("cancellation byte boundary", 0,
                        plaintext, length, length, ciphertext, NULL,
                        key, nonce, aad, TEST_AAD_SIZE,
                        AES_GCM_FILE_CANCELLED, cancel_at[index]) ||
            !run_stream("cancellation byte boundary", 1,
                        ciphertext, length + AES_GCM_TAG_SIZE, length,
                        plaintext, NULL, key, nonce, aad, TEST_AAD_SIZE,
                        AES_GCM_FILE_CANCELLED, cancel_at[index])) {
            return 0;
        }
    }
    return 1;
}

int main(void)
{
    unsigned char key[AES_GCM_KEY_SIZE];
    unsigned char nonce[AES_GCM_NONCE_SIZE];
    unsigned char aad[TEST_AAD_SIZE];
    unsigned char *plaintext = NULL;
    unsigned char *ciphertext = NULL;
    int result = EXIT_FAILURE;

    plaintext = malloc(MAX_TEST_SIZE);
    ciphertext = malloc(MAX_TEST_SIZE + EVP_MAX_BLOCK_LENGTH + AES_GCM_TAG_SIZE);
    if (plaintext == NULL || ciphertext == NULL) {
        goto cleanup;
    }
    fill_pattern(plaintext, MAX_TEST_SIZE);
    fill_pattern(key, sizeof(key));
    fill_pattern(nonce, sizeof(nonce));
    fill_pattern(aad, sizeof(aad));
    if (!test_lengths(plaintext, ciphertext, key, nonce, aad) ||
        !test_rejections(plaintext, ciphertext, key, nonce, aad) ||
        !test_cancellation(plaintext, ciphertext, key, nonce, aad)) {
        goto cleanup;
    }
    puts("AES-GCM streaming reference, authentication, and cancellation tests passed");
    result = EXIT_SUCCESS;

cleanup:
    free(ciphertext);
    free(plaintext);
    return result;
}
