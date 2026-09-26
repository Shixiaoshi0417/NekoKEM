#include "aes.h"
#include "file.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

static int count_progress(uint64_t processed_bytes,
                          uint64_t total_bytes,
                          void *user_data)
{
    unsigned int *calls = user_data;

    (void)processed_bytes;
    (void)total_bytes;
    ++*calls;
    return 1;
}

int main(void)
{
    FILE *input = NULL;
    FILE *output = NULL;
    unsigned char key[AES_GCM_KEY_SIZE] = {0};
    unsigned char nonce[AES_GCM_NONCE_SIZE] = {0};
    unsigned char tag[AES_GCM_TAG_SIZE] = {0};
    const uint64_t over_limit =
        NKEM_GCM_MAX_DATA_SIZE + UINT64_C(1);
    unsigned int progress_calls = 0U;
    int result = EXIT_FAILURE;

    if (!nkem_gcm_data_size_is_valid(NKEM_GCM_MAX_DATA_SIZE) ||
        nkem_gcm_data_size_is_valid(over_limit)) {
        goto cleanup;
    }
    input = tmpfile();
    output = tmpfile();
    if (input == NULL || output == NULL) {
        goto cleanup;
    }
    if (aes_gcm_encrypt_file_with_progress(
            input, output, over_limit, key, nonce, NULL, 0U, tag,
            count_progress, &progress_calls) != AES_GCM_FILE_ERROR ||
        progress_calls != 0U || ftell(output) != 0L) {
        goto cleanup;
    }
    if (aes_gcm_decrypt_file_with_progress(
            input, output, over_limit, key, nonce, NULL, 0U,
            count_progress, &progress_calls) != AES_GCM_FILE_ERROR ||
        progress_calls != 0U || ftell(output) != 0L) {
        goto cleanup;
    }
    result = EXIT_SUCCESS;

cleanup:
    if (output != NULL) {
        (void)fclose(output);
    }
    if (input != NULL) {
        (void)fclose(input);
    }
    return result;
}
