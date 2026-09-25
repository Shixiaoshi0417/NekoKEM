#include "nekokem.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define DEFAULT_TEST_BYTES (4ULL * 1024ULL * 1024ULL)
#define LARGE_TEST_BYTES (3ULL * 1024ULL * 1024ULL * 1024ULL)
#define COMPARE_BUFFER_SIZE 65536U

typedef struct ProgressState {
    uint64_t expected_total;
    uint64_t last_processed;
    uint64_t cancel_after;
    size_t callback_count;
    int invalid;
} ProgressState;

static int progress_callback(uint64_t processed_bytes,
                             uint64_t total_bytes,
                             void *user_data)
{
    ProgressState *state = user_data;

    if (state == NULL || total_bytes != state->expected_total ||
        processed_bytes < state->last_processed ||
        processed_bytes > total_bytes) {
        if (state != NULL) {
            state->invalid = 1;
        }
        return 0;
    }
    state->last_processed = processed_bytes;
    state->callback_count++;
    return state->cancel_after == UINT64_MAX ||
           processed_bytes < state->cancel_after;
}

static int create_sparse_file(const char *path, uint64_t length)
{
    int descriptor;
    int success = 0;

    if (length > (uint64_t)INT64_MAX) {
        return 0;
    }
    descriptor = open(path, O_WRONLY | O_CREAT | O_EXCL, 0600);
    if (descriptor < 0) {
        perror("open progress-test input");
        return 0;
    }
    if (ftruncate(descriptor, (off_t)length) != 0) {
        perror("ftruncate progress-test input");
        goto cleanup;
    }
    success = 1;

cleanup:
    if (close(descriptor) != 0) {
        perror("close progress-test input");
        success = 0;
    }
    return success;
}

static int files_equal(const char *first_path, const char *second_path)
{
    FILE *first = NULL;
    FILE *second = NULL;
    unsigned char first_buffer[COMPARE_BUFFER_SIZE];
    unsigned char second_buffer[COMPARE_BUFFER_SIZE];
    int equal = 0;

    first = fopen(first_path, "rb");
    second = fopen(second_path, "rb");
    if (first == NULL || second == NULL) {
        goto cleanup;
    }
    for (;;) {
        size_t first_count = fread(first_buffer, 1U,
                                   sizeof(first_buffer), first);
        size_t second_count = fread(second_buffer, 1U,
                                    sizeof(second_buffer), second);

        if (first_count != second_count ||
            memcmp(first_buffer, second_buffer, first_count) != 0) {
            goto cleanup;
        }
        if (first_count == 0U) {
            if (ferror(first) != 0 || ferror(second) != 0) {
                goto cleanup;
            }
            break;
        }
    }
    equal = 1;

cleanup:
    if (first != NULL) {
        (void)fclose(first);
    }
    if (second != NULL) {
        (void)fclose(second);
    }
    return equal;
}

static int path_absent(const char *path)
{
    struct stat status;

    if (lstat(path, &status) == 0) {
        return 0;
    }
    return errno == ENOENT;
}

static int transaction_artifacts_absent(const char *directory)
{
    DIR *stream = opendir(directory);
    struct dirent *entry;
    int absent = 1;

    if (stream == NULL) {
        return 0;
    }
    while ((entry = readdir(stream)) != NULL) {
        if (strstr(entry->d_name, ".tmp.") != NULL ||
            strstr(entry->d_name, ".bak.") != NULL) {
            absent = 0;
            break;
        }
    }
    if (closedir(stream) != 0) {
        absent = 0;
    }
    return absent;
}

static int parse_test_size(uint64_t *test_bytes)
{
    const char *value = getenv("NEKOKEM_PROGRESS_TEST_BYTES");
    char *end = NULL;
    unsigned long long parsed;

    if (value == NULL || value[0] == '\0') {
        *test_bytes = DEFAULT_TEST_BYTES;
        return 1;
    }
    errno = 0;
    parsed = strtoull(value, &end, 10);
    if (errno != 0 || end == value || *end != '\0' || parsed == 0ULL) {
        return 0;
    }
    *test_bytes = (uint64_t)parsed;
    return 1;
}

int main(void)
{
    static const unsigned char password[] =
        "progress-test-private-key-password";
    char test_directory[] = "/tmp/nekokem-progress-tests.XXXXXX";
    char input_path[256];
    char encrypted_path[256];
    char decrypted_path[256];
    char cancelled_encrypt_path[256];
    char cancelled_decrypt_path[256];
    char public_key_path[256];
    char private_key_path[256];
    ProgressState progress;
    uint64_t test_bytes;
    uint64_t cancel_after;
    int result;
    int success = 0;

    if (!parse_test_size(&test_bytes) ||
        mkdtemp(test_directory) == NULL) {
        perror("prepare progress-test directory");
        return 1;
    }
    if (snprintf(input_path, sizeof(input_path), "%s/input.bin",
                 test_directory) < 0 ||
        snprintf(encrypted_path, sizeof(encrypted_path), "%s/data.nkem",
                 test_directory) < 0 ||
        snprintf(decrypted_path, sizeof(decrypted_path), "%s/output.bin",
                 test_directory) < 0 ||
        snprintf(cancelled_encrypt_path, sizeof(cancelled_encrypt_path),
                 "%s/cancel-encrypt.nkem", test_directory) < 0 ||
        snprintf(cancelled_decrypt_path, sizeof(cancelled_decrypt_path),
                 "%s/cancel-decrypt.bin", test_directory) < 0 ||
        snprintf(public_key_path, sizeof(public_key_path), "%s/public.key",
                 test_directory) < 0 ||
        snprintf(private_key_path, sizeof(private_key_path),
                 "%s/private.key.enc", test_directory) < 0) {
        goto cleanup;
    }
    if (!create_sparse_file(input_path, test_bytes) ||
        !nekokem_generate_keypair(
            public_key_path, private_key_path,
            password, sizeof(password) - 1U)) {
        goto cleanup;
    }

    cancel_after = test_bytes < (1024ULL * 1024ULL)
                       ? test_bytes / 2U
                       : 1024ULL * 1024ULL;
    if (cancel_after == 0U) {
        cancel_after = 1U;
    }
    progress = (ProgressState){test_bytes, 0U, cancel_after, 0U, 0};
    result = nekokem_encrypt_file_with_progress(
        input_path, cancelled_encrypt_path, public_key_path,
        progress_callback, &progress);
    if (result != NEKOKEM_OPERATION_CANCELLED || progress.invalid != 0 ||
        progress.callback_count < 2U ||
        !path_absent(cancelled_encrypt_path) ||
        !transaction_artifacts_absent(test_directory)) {
        fprintf(stderr, "Progress encryption cancellation test failed\n");
        goto cleanup;
    }

    progress = (ProgressState){test_bytes, 0U, UINT64_MAX, 0U, 0};
    result = nekokem_encrypt_file_with_progress(
        input_path, encrypted_path, public_key_path,
        progress_callback, &progress);
    if (result != NEKOKEM_OPERATION_SUCCESS || progress.invalid != 0 ||
        progress.last_processed != test_bytes ||
        progress.callback_count < 2U) {
        fprintf(stderr, "Progress encryption completion test failed\n");
        goto cleanup;
    }

    progress = (ProgressState){test_bytes, 0U, cancel_after, 0U, 0};
    result = nekokem_decrypt_file_with_progress(
        encrypted_path, cancelled_decrypt_path, private_key_path,
        password, sizeof(password) - 1U,
        progress_callback, &progress);
    if (result != NEKOKEM_OPERATION_CANCELLED || progress.invalid != 0 ||
        progress.callback_count < 2U ||
        !path_absent(cancelled_decrypt_path) ||
        !transaction_artifacts_absent(test_directory)) {
        fprintf(stderr, "Progress decryption cancellation test failed\n");
        goto cleanup;
    }

    progress = (ProgressState){test_bytes, 0U, UINT64_MAX, 0U, 0};
    result = nekokem_decrypt_file_with_progress(
        encrypted_path, decrypted_path, private_key_path,
        password, sizeof(password) - 1U,
        progress_callback, &progress);
    if (result != NEKOKEM_OPERATION_SUCCESS || progress.invalid != 0 ||
        progress.last_processed != test_bytes ||
        progress.callback_count < 2U ||
        !files_equal(input_path, decrypted_path)) {
        fprintf(stderr, "Progress decryption completion test failed\n");
        goto cleanup;
    }
    printf("Progress/cancellation test passed for %llu bytes\n",
           (unsigned long long)test_bytes);
    success = 1;

cleanup:
    (void)unlink(cancelled_decrypt_path);
    (void)unlink(cancelled_encrypt_path);
    (void)unlink(decrypted_path);
    (void)unlink(encrypted_path);
    (void)unlink(private_key_path);
    (void)unlink(public_key_path);
    (void)unlink(input_path);
    (void)rmdir(test_directory);
    return success != 0 ? 0 : 1;
}
