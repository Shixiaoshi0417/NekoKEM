#include "nekokem.h"
#include "secure_mem.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
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

int main(void)
{
    static const unsigned char initial_password[] =
        "android-local-key-test-password";
    static const unsigned char wrong_password_value[] =
        "android-local-key-wrong-password";
    unsigned char password[sizeof(initial_password)] = {0};
    unsigned char wrong_password[sizeof(wrong_password_value)] = {0};
    char directory[] = "/tmp/nekokem-key-management.XXXXXX";
    char public_path[TEST_PATH_SIZE] = {0};
    char private_path[TEST_PATH_SIZE] = {0};
    char export_path[TEST_PATH_SIZE] = {0};
    char first_fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE] = {0};
    char restarted_fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE] = {0};
    char exported_fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE] = {0};
    struct stat status;
    int success = 0;

    memcpy(password, initial_password, sizeof(initial_password));
    memcpy(wrong_password, wrong_password_value, sizeof(wrong_password_value));
    if (mkdtemp(directory) == NULL ||
        !make_path(public_path, sizeof(public_path), directory,
                   "public.key") ||
        !make_path(private_path, sizeof(private_path), directory,
                   "private.nkpr.enc") ||
        !make_path(export_path, sizeof(export_path), directory,
                   "exported-public.key")) {
        fprintf(stderr, "Cannot prepare key-management test paths\n");
        goto cleanup;
    }
    if (!nekokem_generate_keypair(public_path, private_path,
                                  password,
                                  sizeof(initial_password) - 1U) ||
        stat(private_path, &status) != 0 ||
        (status.st_mode & (mode_t)0777) != (mode_t)0600) {
        fprintf(stderr, "Managed key generation or permissions failed\n");
        goto cleanup;
    }
    if (!nekokem_private_key_exists(private_path) ||
        !nekokem_public_key_fingerprint(
            public_path, first_fingerprint,
            sizeof(first_fingerprint))) {
        fprintf(stderr, "Initial managed-key state is invalid\n");
        goto cleanup;
    }

    if (!nekokem_check_private_key_password(
            private_path, password,
            sizeof(initial_password) - 1U) ||
        nekokem_check_private_key_password(
            private_path, wrong_password,
            sizeof(wrong_password_value) - 1U)) {
        fprintf(stderr,
                "Correct/wrong managed-key password test failed\n");
        goto cleanup;
    }


    /* A second stateless call models an App process restart. */
    if (!nekokem_private_key_exists(private_path) ||
        !nekokem_public_key_fingerprint(
            public_path, restarted_fingerprint,
            sizeof(restarted_fingerprint)) ||
        strcmp(first_fingerprint, restarted_fingerprint) != 0) {
        fprintf(stderr, "Managed-key state did not survive restart\n");
        goto cleanup;
    }
    if (!nekokem_export_public_key(public_path, export_path) ||
        !nekokem_public_key_fingerprint(
            export_path, exported_fingerprint,
            sizeof(exported_fingerprint)) ||
        strcmp(first_fingerprint, exported_fingerprint) != 0) {
        fprintf(stderr, "Exported public-key fingerprint mismatch\n");
        goto cleanup;
    }
    if (!nekokem_delete_private_key(private_path) ||
        nekokem_private_key_exists(private_path) ||
        !nekokem_delete_private_key(private_path)) {
        fprintf(stderr, "Managed private-key deletion failed\n");
        goto cleanup;
    }
    success = 1;

cleanup:
    secure_mem_clear(password, sizeof(password));
    secure_mem_clear(wrong_password, sizeof(wrong_password));
    secure_mem_clear(first_fingerprint, sizeof(first_fingerprint));
    secure_mem_clear(restarted_fingerprint, sizeof(restarted_fingerprint));
    secure_mem_clear(exported_fingerprint, sizeof(exported_fingerprint));
    (void)unlink(private_path);
    (void)unlink(public_path);
    (void)unlink(export_path);
    (void)rmdir(directory);
    if (success != 0) {
        puts("Key-management lifecycle tests passed");
        return EXIT_SUCCESS;
    }
    return EXIT_FAILURE;
}
