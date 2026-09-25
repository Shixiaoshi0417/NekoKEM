#include "../nekokem_core/include/nekokem.h"
#include "../src/file.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define TEST_PATH_SIZE 512U

typedef struct TestPaths {
    char plaintext[TEST_PATH_SIZE];
    char v1_public[TEST_PATH_SIZE];
    char v1_private[TEST_PATH_SIZE];
    char hybrid_public[TEST_PATH_SIZE];
    char hybrid_private[TEST_PATH_SIZE];
    char v1_container[TEST_PATH_SIZE];
    char v2_container[TEST_PATH_SIZE];
    char v3_container[TEST_PATH_SIZE];
    char v1_output[TEST_PATH_SIZE];
    char v2_output[TEST_PATH_SIZE];
    char v3_output[TEST_PATH_SIZE];
    char rejected_v1[TEST_PATH_SIZE];
    char rejected_v2[TEST_PATH_SIZE];
} TestPaths;

static int make_path(char *output,
                     size_t output_size,
                     const char *directory,
                     const char *name)
{
    int length = snprintf(output, output_size, "%s/%s", directory, name);

    return length > 0 && (size_t)length < output_size;
}

static int initialize_paths(TestPaths *paths, const char *directory)
{
    return make_path(paths->plaintext, sizeof(paths->plaintext),
                     directory, "plaintext.bin") &&
           make_path(paths->v1_public, sizeof(paths->v1_public),
                     directory, "v1-public.key") &&
           make_path(paths->v1_private, sizeof(paths->v1_private),
                     directory, "v1-private.key") &&
           make_path(paths->hybrid_public, sizeof(paths->hybrid_public),
                     directory, "hybrid-public.key") &&
           make_path(paths->hybrid_private, sizeof(paths->hybrid_private),
                     directory, "hybrid-private.key.enc") &&
           make_path(paths->v1_container, sizeof(paths->v1_container),
                     directory, "v1.nkem") &&
           make_path(paths->v2_container, sizeof(paths->v2_container),
                     directory, "v2.nkem") &&
           make_path(paths->v3_container, sizeof(paths->v3_container),
                     directory, "v3.nkem") &&
           make_path(paths->v1_output, sizeof(paths->v1_output),
                     directory, "v1-output.bin") &&
           make_path(paths->v2_output, sizeof(paths->v2_output),
                     directory, "v2-output.bin") &&
           make_path(paths->v3_output, sizeof(paths->v3_output),
                     directory, "v3-output.bin") &&
           make_path(paths->rejected_v1, sizeof(paths->rejected_v1),
                     directory, "rejected-v1.bin") &&
           make_path(paths->rejected_v2, sizeof(paths->rejected_v2),
                     directory, "rejected-v2.bin");
}

static int write_plaintext(const char *path)
{
    static const unsigned char content[] = {
        'N', 'K', 'E', 'M', ' ', 'v', '3', '\n', 0U, 1U, 0xffU
    };
    FILE *output = fopen(path, "wb");
    int success = 0;

    if (output == NULL) {
        return 0;
    }
    if (fwrite(content, 1U, sizeof(content), output) == sizeof(content) &&
        fclose(output) == 0) {
        output = NULL;
        success = 1;
    }
    if (output != NULL) {
        (void)fclose(output);
    }
    return success;
}

static int files_equal(const char *left_path, const char *right_path)
{
    FILE *left = NULL;
    FILE *right = NULL;
    int equal = 0;

    left = fopen(left_path, "rb");
    right = fopen(right_path, "rb");
    if (left == NULL || right == NULL) {
        goto cleanup;
    }
    for (;;) {
        unsigned char left_buffer[4096];
        unsigned char right_buffer[4096];
        size_t left_len = fread(left_buffer, 1U, sizeof(left_buffer), left);
        size_t right_len = fread(right_buffer, 1U, sizeof(right_buffer), right);

        if (left_len != right_len ||
            memcmp(left_buffer, right_buffer, left_len) != 0) {
            goto cleanup;
        }
        if (left_len < sizeof(left_buffer)) {
            if (ferror(left) != 0 || ferror(right) != 0 ||
                feof(left) == 0 || feof(right) == 0) {
                goto cleanup;
            }
            equal = 1;
            break;
        }
    }

cleanup:
    if (left != NULL) {
        (void)fclose(left);
    }
    if (right != NULL) {
        (void)fclose(right);
    }
    return equal;
}

static int container_has_version(const char *path, unsigned char version)
{
    unsigned char prefix[5];
    FILE *input = fopen(path, "rb");
    int matches = 0;

    if (input != NULL &&
        fread(prefix, 1U, sizeof(prefix), input) == sizeof(prefix) &&
        memcmp(prefix, "NKEM", 4U) == 0 && prefix[4] == version) {
        matches = 1;
    }
    if (input != NULL) {
        (void)fclose(input);
    }
    return matches;
}

static void cleanup_paths(const TestPaths *paths, const char *directory)
{
    (void)unlink(paths->plaintext);
    (void)unlink(paths->v1_public);
    (void)unlink(paths->v1_private);
    (void)unlink(paths->hybrid_public);
    (void)unlink(paths->hybrid_private);
    (void)unlink(paths->v1_container);
    (void)unlink(paths->v2_container);
    (void)unlink(paths->v3_container);
    (void)unlink(paths->v1_output);
    (void)unlink(paths->v2_output);
    (void)unlink(paths->v3_output);
    (void)unlink(paths->rejected_v1);
    (void)unlink(paths->rejected_v2);
    (void)rmdir(directory);
}

int main(void)
{
    static const unsigned char password[] =
        "v3-version-compatibility-test-password";
    char directory[] = "/tmp/nekokem-v3-tests.XXXXXX";
    TestPaths paths;
    int success = 0;

    memset(&paths, 0, sizeof(paths));
    if (mkdtemp(directory) == NULL ||
        !initialize_paths(&paths, directory) ||
        !write_plaintext(paths.plaintext)) {
        fprintf(stderr, "Cannot initialize NKEM version tests\n");
        goto cleanup;
    }

    if (!nekokem_generate_v1_keypair(paths.v1_public,
                                     paths.v1_private) ||
        !nekokem_generate_keypair(paths.hybrid_public,
                                  paths.hybrid_private,
                                  password, sizeof(password) - 1U)) {
        fprintf(stderr, "Version-test key generation failed\n");
        goto cleanup;
    }

    if (!nekokem_encrypt_file_v1(paths.plaintext, paths.v1_container,
                                 paths.v1_public) ||
        !container_has_version(paths.v1_container, NKEM_VERSION) ||
        !nekokem_decrypt_file(paths.v1_container, paths.v1_output,
                              paths.v1_private, NULL, 0U) ||
        !files_equal(paths.plaintext, paths.v1_output)) {
        fprintf(stderr, "NKEM v1 compatibility test failed\n");
        goto cleanup;
    }

    if (!nekokem_encrypt_file_v2(paths.plaintext, paths.v2_container,
                                 paths.hybrid_public) ||
        !container_has_version(paths.v2_container, NKEM_V2_VERSION) ||
        !nekokem_decrypt_file(paths.v2_container, paths.v2_output,
                              paths.hybrid_private,
                              password, sizeof(password) - 1U) ||
        !files_equal(paths.plaintext, paths.v2_output)) {
        fprintf(stderr, "NKEM v2 compatibility test failed\n");
        goto cleanup;
    }

    if (!nekokem_encrypt_file(paths.plaintext, paths.v3_container,
                              paths.hybrid_public) ||
        !container_has_version(paths.v3_container, NKEM_V3_VERSION) ||
        !nekokem_decrypt_file(paths.v3_container, paths.v3_output,
                              paths.hybrid_private,
                              password, sizeof(password) - 1U) ||
        !files_equal(paths.plaintext, paths.v3_output)) {
        fprintf(stderr, "NKEM v3 round-trip test failed\n");
        goto cleanup;
    }

    errno = 0;
    if (nekokem_decrypt_file_v1(paths.v3_container, paths.rejected_v1,
                                paths.v1_private) != 0 ||
        access(paths.rejected_v1, F_OK) == 0 || errno != ENOENT) {
        fprintf(stderr, "NKEM v1 path accepted an NKEM v3 container\n");
        goto cleanup;
    }
    errno = 0;
    if (nekokem_decrypt_file_v2(paths.v3_container, paths.rejected_v2,
                                paths.hybrid_private,
                                password, sizeof(password) - 1U) != 0 ||
        access(paths.rejected_v2, F_OK) == 0 || errno != ENOENT) {
        fprintf(stderr, "NKEM v2 path accepted an NKEM v3 container\n");
        goto cleanup;
    }

    success = 1;
    puts("NKEM v1/v2 compatibility and v3 round-trip tests passed");

cleanup:
    cleanup_paths(&paths, directory);
    return success != 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
