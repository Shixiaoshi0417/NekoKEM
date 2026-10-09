#include "nekokem.h"

#include "file.h"
#include "hybrid.h"
#include "private_key.h"
#include "secure_mem.h"

#include <errno.h>
#include <openssl/pem.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/stat.h>
#ifndef _WIN32
#include <unistd.h>
#else
#include "windows_io.h"
#endif

#define MANAGED_NKPR_MAX_SIZE \
    (NKPR_HEADER_SIZE + NKPR_SALT_SIZE + NKPR_NONCE_SIZE + \
     NKPR_MAX_PEM_SIZE + NKPR_TAG_SIZE)

static int managed_path_is_valid(const char *path)
{
    return path != NULL && path[0] != '\0';
}

int nekokem_private_key_exists(const char *private_key_path)
{
#ifndef _WIN32
    struct stat status;
#endif
    unsigned char *container = NULL;
    size_t container_len = 0U;
    int exists = 0;

    if (!managed_path_is_valid(private_key_path)) {
        goto cleanup;
    }
#ifndef _WIN32
    if (lstat(private_key_path, &status) != 0 ||
        !S_ISREG(status.st_mode) ||
        (status.st_mode & (mode_t)0777) != (mode_t)0600) {
        goto cleanup;
    }
#endif
    if (!file_read_sensitive(private_key_path,
                             MANAGED_NKPR_MAX_SIZE,
                             &container,
                             &container_len) ||
        !protected_private_key_container_parse(container,
                                               container_len)) {
        goto cleanup;
    }
    exists = 1;

cleanup:
    secure_free(container, container_len);
    return exists;
}

int nekokem_check_private_key_password(
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len)
{
    HybridKeys keys = {0};
    int success = 0;

    if (!managed_path_is_valid(private_key_path) ||
        password == NULL || password_len == 0U ||
        !nekokem_private_key_exists(private_key_path)) {
        goto cleanup;
    }
    if (!hybrid_load_protected_private_keys(
            private_key_path, password, password_len, &keys)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    hybrid_keys_cleanup(&keys);
    return success;
}

int nekokem_export_public_key(const char *public_key_path,
                              const char *output_path)
{
    HybridKeys keys = {0};
    AtomicFile output = {0};
    int success = 0;

    if (!managed_path_is_valid(public_key_path) ||
        !managed_path_is_valid(output_path)) {
        fprintf(stderr, file_message("Invalid public-key export path\n"));
        goto cleanup;
    }
    if (!hybrid_load_public_keys(public_key_path, &keys) ||
        !atomic_file_open(&output, output_path, 0600)) {
        goto cleanup;
    }
    if (PEM_write_PUBKEY(output.stream, keys.x448) != 1 ||
        PEM_write_PUBKEY(output.stream, keys.mlkem) != 1) {
        print_openssl_error("Cannot export hybrid public key");
        goto cleanup;
    }
    if (!atomic_file_commit(&output)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    atomic_file_abort(&output);
    hybrid_keys_cleanup(&keys);
    return success;
}

int nekokem_delete_private_key(const char *private_key_path)
{
    int success;
#ifndef _WIN32
    int lock;
#endif

    if (!managed_path_is_valid(private_key_path)) {
        fprintf(stderr, file_message("Invalid private-key deletion path\n"));
        return 0;
    }
#ifndef _WIN32
    lock = file_pair_lock_acquire(private_key_path);
    if (lock < 0) {
        return errno == ENOENT;
    }
#endif
    success = file_delete_private_key_under_lock(private_key_path);
#ifndef _WIN32
    file_pair_lock_release(lock);
#endif
    return success;
}
