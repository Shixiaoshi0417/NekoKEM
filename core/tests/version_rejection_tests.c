#include "nekokem.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define TEST_PATH_SIZE 4096U

static int make_path(char *output,
                     size_t output_size,
                     const char *directory,
                     const char *name)
{
    int written = snprintf(output, output_size, "%s/%s", directory, name);

    return written >= 0 && (size_t)written < output_size;
}

static int write_plaintext(const char *path,
                           const unsigned char *data,
                           size_t length)
{
    FILE *output = fopen(path, "wb");
    int success = 0;

    if (output != NULL && fwrite(data, 1U, length, output) == length &&
        fflush(output) == 0) {
        success = 1;
    }
    if (output != NULL && fclose(output) != 0) {
        success = 0;
    }
    if (success == 0) {
        (void)unlink(path);
    }
    return success;
}

static int copy_with_version(const char *source_path,
                             const char *destination_path,
                             unsigned char version)
{
    unsigned char buffer[4096];
    FILE *source = NULL;
    FILE *destination = NULL;
    size_t count;
    int success = 0;

    source = fopen(source_path, "rb");
    destination = fopen(destination_path, "wb+");
    if (source == NULL || destination == NULL) {
        goto cleanup;
    }
    while ((count = fread(buffer, 1U, sizeof(buffer), source)) > 0U) {
        if (fwrite(buffer, 1U, count, destination) != count) {
            goto cleanup;
        }
    }
    if (ferror(source) != 0 || fseek(destination, 4L, SEEK_SET) != 0 ||
        fputc((int)version, destination) == EOF || fflush(destination) != 0) {
        goto cleanup;
    }
    success = 1;

cleanup:
    if (source != NULL && fclose(source) != 0) {
        success = 0;
    }
    if (destination != NULL && fclose(destination) != 0) {
        success = 0;
    }
    if (success == 0) {
        (void)unlink(destination_path);
    }
    return success;
}

static int file_equals(const char *path,
                       const unsigned char *expected,
                       size_t expected_length)
{
    unsigned char buffer[128];
    FILE *input = NULL;
    size_t count;
    int equal = 0;

    if (expected_length > sizeof(buffer)) {
        return 0;
    }
    input = fopen(path, "rb");
    if (input == NULL) {
        return 0;
    }
    count = fread(buffer, 1U, sizeof(buffer), input);
    if (count == expected_length &&
        memcmp(buffer, expected, expected_length) == 0 &&
        fgetc(input) == EOF && ferror(input) == 0) {
        equal = 1;
    }
    if (fclose(input) != 0) {
        equal = 0;
    }
    return equal;
}

int main(void)
{
    static const unsigned char password[] =
        "version-rejection-test-password";
    static const unsigned char plaintext[] =
        "NKEM v3 public decrypt regression\n";
    char directory[] = "/tmp/nekokem-version-rejection.XXXXXX";
    char public_path[TEST_PATH_SIZE] = {0};
    char private_path[TEST_PATH_SIZE] = {0};
    char plaintext_path[TEST_PATH_SIZE] = {0};
    char v3_path[TEST_PATH_SIZE] = {0};
    char v3_output_path[TEST_PATH_SIZE] = {0};
    char v1_path[TEST_PATH_SIZE] = {0};
    char v2_path[TEST_PATH_SIZE] = {0};
    char v1_output_path[TEST_PATH_SIZE] = {0};
    char v2_output_path[TEST_PATH_SIZE] = {0};
    int success = 0;

    if (mkdtemp(directory) == NULL ||
        !make_path(public_path, sizeof(public_path), directory,
                   "public.key") ||
        !make_path(private_path, sizeof(private_path), directory,
                   "private.nkpr.enc") ||
        !make_path(plaintext_path, sizeof(plaintext_path), directory,
                   "plaintext.bin") ||
        !make_path(v3_path, sizeof(v3_path), directory, "valid-v3.nkem") ||
        !make_path(v3_output_path, sizeof(v3_output_path), directory,
                   "v3-output.bin") ||
        !make_path(v1_path, sizeof(v1_path), directory, "version-1.nkem") ||
        !make_path(v2_path, sizeof(v2_path), directory, "version-2.nkem") ||
        !make_path(v1_output_path, sizeof(v1_output_path), directory,
                   "version-1-output.bin") ||
        !make_path(v2_output_path, sizeof(v2_output_path), directory,
                   "version-2-output.bin")) {
        fprintf(stderr, "Cannot prepare version-rejection test paths\n");
        goto cleanup;
    }
    if (!write_plaintext(plaintext_path, plaintext,
                         sizeof(plaintext) - 1U) ||
        !nekokem_generate_keypair(public_path, private_path,
                                  password, sizeof(password) - 1U) ||
        !nekokem_encrypt_file(plaintext_path, v3_path, public_path) ||
        !nekokem_decrypt_file(v3_path, v3_output_path, private_path,
                              password, sizeof(password) - 1U) ||
        !file_equals(v3_output_path, plaintext,
                     sizeof(plaintext) - 1U)) {
        fprintf(stderr, "NKEM v3 public decrypt regression failed\n");
        goto cleanup;
    }
    if (!copy_with_version(v3_path, v1_path, 1U) ||
        !copy_with_version(v3_path, v2_path, 2U)) {
        fprintf(stderr, "Cannot prepare legacy-version fixtures\n");
        goto cleanup;
    }
    if (nekokem_decrypt_file(v1_path, v1_output_path, private_path,
                             password, sizeof(password) - 1U) != 0 ||
        access(v1_output_path, F_OK) == 0) {
        fprintf(stderr,
                "Public decrypt accepted version 1 or left output\n");
        goto cleanup;
    }
    if (nekokem_decrypt_file(v2_path, v2_output_path, private_path,
                             password, sizeof(password) - 1U) != 0 ||
        access(v2_output_path, F_OK) == 0) {
        fprintf(stderr,
                "Public decrypt accepted version 2 or left output\n");
        goto cleanup;
    }
    success = 1;

cleanup:
    (void)unlink(v2_output_path);
    (void)unlink(v1_output_path);
    (void)unlink(v2_path);
    (void)unlink(v1_path);
    (void)unlink(v3_output_path);
    (void)unlink(v3_path);
    (void)unlink(plaintext_path);
    (void)unlink(private_path);
    (void)unlink(public_path);
    (void)rmdir(directory);
    if (success != 0) {
        puts("Public decrypt version rejection tests passed");
        return EXIT_SUCCESS;
    }
    return EXIT_FAILURE;
}
