#include "nekokem.h"
#include "nekokem_internal.h"
#include "private_key.h"
#include "secure_mem.h"

#include <openssl/crypto.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define TEST_PATH_SIZE 4096U
#define PUBLIC_KEY_TEST_MAX_SIZE (1024U * 1024U)

static int make_path(char *output,
                     size_t output_size,
                     const char *directory,
                     const char *name)
{
    int written = snprintf(output, output_size, "%s/%s", directory, name);

    return written >= 0 && (size_t)written < output_size;
}

static int append_trailing_data(const char *path)
{
    static const unsigned char trailing[] = "not another PEM block\n";
    FILE *output = fopen(path, "ab");
    int success = 0;

    if (output != NULL &&
        fwrite(trailing, 1U, sizeof(trailing) - 1U, output) ==
            sizeof(trailing) - 1U &&
        fflush(output) == 0) {
        success = 1;
    }
    if (output != NULL && fclose(output) != 0) {
        success = 0;
    }
    return success;
}

static int append_spaces_to_size(const char *path, size_t target_size)
{
    unsigned char spaces[256];
    struct stat status;
    FILE *output = NULL;
    size_t remaining;
    int success = 0;

    if (stat(path, &status) != 0 || status.st_size < 0 ||
        (uintmax_t)status.st_size > target_size) {
        return 0;
    }
    remaining = target_size - (size_t)status.st_size;
    memset(spaces, ' ', sizeof(spaces));
    output = fopen(path, "ab");
    if (output == NULL) {
        return 0;
    }
    while (remaining > 0U) {
        size_t chunk = remaining < sizeof(spaces) ? remaining : sizeof(spaces);

        if (fwrite(spaces, 1U, chunk, output) != chunk) {
            goto cleanup;
        }
        remaining -= chunk;
    }
    success = fflush(output) == 0;

cleanup:
    if (fclose(output) != 0) {
        success = 0;
    }
    return success;
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
    char modified_private_path[TEST_PATH_SIZE] = {0};
    char export_path[TEST_PATH_SIZE] = {0};
    unsigned char *private_pem = NULL;
    unsigned char *modified_private_pem = NULL;
    size_t private_pem_len = 0U;
    char first_fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE] = {0};
    char restarted_fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE] = {0};
    char exported_fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE] = {0};
    char private_fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE] = {0};
    struct stat status;
    int success = 0;

    memcpy(password, initial_password, sizeof(initial_password));
    memcpy(wrong_password, wrong_password_value, sizeof(wrong_password_value));
    if (mkdtemp(directory) == NULL ||
        !make_path(public_path, sizeof(public_path), directory,
                   "public.key") ||
        !make_path(private_path, sizeof(private_path), directory,
                   "private.nkpr.enc") ||
        !make_path(modified_private_path, sizeof(modified_private_path),
                   directory, "modified-private.nkpr.enc") ||
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
    if (!nekokem_internal_private_key_fingerprint(
            private_path, password,
            sizeof(initial_password) - 1U,
            private_fingerprint, sizeof(private_fingerprint)) ||
        strcmp(first_fingerprint, private_fingerprint) != 0 ||
        nekokem_internal_private_key_fingerprint(
            private_path, wrong_password,
            sizeof(wrong_password_value) - 1U,
            private_fingerprint, sizeof(private_fingerprint))) {
        fprintf(stderr, "Private/public fingerprint validation failed\n");
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
    if (!append_spaces_to_size(export_path, PUBLIC_KEY_TEST_MAX_SIZE) ||
        !nekokem_public_key_fingerprint(
            export_path, exported_fingerprint,
            sizeof(exported_fingerprint)) ||
        strcmp(first_fingerprint, exported_fingerprint) != 0 ||
        !append_spaces_to_size(export_path,
                               PUBLIC_KEY_TEST_MAX_SIZE + 1U) ||
        nekokem_public_key_fingerprint(
            export_path, exported_fingerprint,
            sizeof(exported_fingerprint)) != 0 ||
        !nekokem_export_public_key(public_path, export_path)) {
        fprintf(stderr, "Public-key size boundary test failed\n");
        goto cleanup;
    }
    if (!append_trailing_data(export_path) ||
        nekokem_public_key_fingerprint(
            export_path, exported_fingerprint,
            sizeof(exported_fingerprint)) != 0) {
        fprintf(stderr, "Trailing public-key data was accepted\n");
        goto cleanup;
    }
    if (!protected_private_key_read(
            private_path, password, sizeof(initial_password) - 1U,
            &private_pem, &private_pem_len) ||
        private_pem_len > SIZE_MAX - 3U ||
        (modified_private_pem = OPENSSL_malloc(private_pem_len + 3U)) == NULL) {
        fprintf(stderr, "Cannot prepare private-key tail tests\n");
        goto cleanup;
    }
    memcpy(modified_private_pem, private_pem, private_pem_len);
    modified_private_pem[private_pem_len] = '\n';
    modified_private_pem[private_pem_len + 1U] = '\t';
    if (!protected_private_key_write(
            modified_private_path, modified_private_pem,
            private_pem_len + 2U,
            password, sizeof(initial_password) - 1U) ||
        !nekokem_check_private_key_password(
            modified_private_path, password,
            sizeof(initial_password) - 1U)) {
        fprintf(stderr, "Private-key whitespace tail was rejected\n");
        goto cleanup;
    }
    modified_private_pem[private_pem_len + 2U] = 'X';
    if (!protected_private_key_write(
            modified_private_path, modified_private_pem,
            private_pem_len + 3U,
            password, sizeof(initial_password) - 1U) ||
        nekokem_check_private_key_password(
            modified_private_path, password,
            sizeof(initial_password) - 1U) != 0) {
        fprintf(stderr, "Trailing private-key data was accepted\n");
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
    secure_free(private_pem, private_pem_len);
    secure_free(modified_private_pem, private_pem_len + 3U);
    secure_mem_clear(password, sizeof(password));
    secure_mem_clear(wrong_password, sizeof(wrong_password));
    secure_mem_clear(first_fingerprint, sizeof(first_fingerprint));
    secure_mem_clear(restarted_fingerprint, sizeof(restarted_fingerprint));
    secure_mem_clear(exported_fingerprint, sizeof(exported_fingerprint));
    secure_mem_clear(private_fingerprint, sizeof(private_fingerprint));
    (void)unlink(private_path);
    (void)unlink(modified_private_path);
    (void)unlink(public_path);
    (void)unlink(export_path);
    (void)rmdir(directory);
    if (success != 0) {
        puts("Key-management lifecycle tests passed");
        return EXIT_SUCCESS;
    }
    return EXIT_FAILURE;
}
