#include "file.h"
#include "secure_mem.h"

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

#define TEST_BUFFER_SIZE 128U

static int make_path(char *output, size_t output_size,
                     const char *directory, const char *name)
{
    int result = snprintf(output, output_size, "%s/%s", directory, name);

    return result >= 0 && (size_t)result < output_size;
}

static int write_plain_file(const char *path, const unsigned char *data,
                            size_t length, mode_t mode)
{
    int descriptor = -1;
    size_t position = 0U;
    int success = 0;

    descriptor = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, mode);
    if (descriptor < 0 || fchmod(descriptor, mode) != 0) {
        goto cleanup;
    }
    while (position < length) {
        ssize_t count = write(descriptor, data + position, length - position);

        if (count < 0 && errno == EINTR) {
            continue;
        }
        if (count <= 0) {
            goto cleanup;
        }
        position += (size_t)count;
    }
    success = 1;

cleanup:
    if (descriptor >= 0 && close(descriptor) != 0) {
        success = 0;
    }
    return success;
}

static int file_equals(const char *path, const unsigned char *expected,
                       size_t expected_len)
{
    unsigned char buffer[TEST_BUFFER_SIZE];
    FILE *input = NULL;
    size_t count;
    int equal = 0;

    if (expected_len > sizeof(buffer)) {
        return 0;
    }
    input = fopen(path, "rb");
    if (input == NULL) {
        goto cleanup;
    }
    count = fread(buffer, 1U, sizeof(buffer), input);
    if (count == expected_len &&
        memcmp(buffer, expected, expected_len) == 0 &&
        fgetc(input) == EOF && ferror(input) == 0) {
        equal = 1;
    }

cleanup:
    if (input != NULL && fclose(input) != 0) {
        equal = 0;
    }
    secure_mem_clear(buffer, sizeof(buffer));
    return equal;
}

static int has_transaction_artifact(const char *directory)
{
    DIR *stream = opendir(directory);
    struct dirent *entry;
    int found = 0;

    if (stream == NULL) {
        return 1;
    }
    while ((entry = readdir(stream)) != NULL) {
        if (strstr(entry->d_name, ".tmp.") != NULL ||
            strstr(entry->d_name, ".bak.") != NULL) {
            found = 1;
            break;
        }
    }
    if (closedir(stream) != 0) {
        found = 1;
    }
    return found;
}

static int test_directory_validation(const char *root)
{
    char unsafe[256];
    char regular[256];
    char target[256];
    char symbolic[256];
    char foreign[256];
    int success = 0;

    if (!make_path(unsafe, sizeof(unsafe), root, "unsafe") ||
        !make_path(regular, sizeof(regular), root, "regular") ||
        !make_path(target, sizeof(target), root, "target") ||
        !make_path(symbolic, sizeof(symbolic), root, "symbolic") ||
        !make_path(foreign, sizeof(foreign), root, "foreign") ||
        mkdir(unsafe, 0777) != 0 || chmod(unsafe, 0777) != 0 ||
        ensure_directory(unsafe, 0700) != 0 ||
        !write_plain_file(regular, (const unsigned char *)"x", 1U, 0600) ||
        ensure_directory(regular, 0700) != 0 ||
        mkdir(target, 0700) != 0 || symlink(target, symbolic) != 0 ||
        ensure_directory(symbolic, 0700) != 0 ||
        mkdir(foreign, 0700) != 0) {
        goto cleanup;
    }
    file_test_fault_set(FILE_TEST_FAULT_FOREIGN_OWNER, 1U);
    if (ensure_directory(foreign, 0700) != 0) {
        goto cleanup;
    }
    file_test_fault_reset();
    success = 1;

cleanup:
    (void)unlink(symbolic);
    (void)unlink(regular);
    (void)rmdir(foreign);
    (void)rmdir(target);
    (void)rmdir(unsafe);
    return success;
}

static int test_sensitive_file_validation(const char *root)
{
    static const unsigned char contents[] = "sensitive-test";
    char private_path[256];
    char symbolic_path[256];
    char hardlink_path[256];
    char directory_path[256];
    unsigned char *buffer = NULL;
    size_t length = 0U;
    int success = 0;

    if (!make_path(private_path, sizeof(private_path), root, "private.key") ||
        !make_path(symbolic_path, sizeof(symbolic_path), root, "key.link") ||
        !make_path(hardlink_path, sizeof(hardlink_path), root, "key.hard") ||
        !make_path(directory_path, sizeof(directory_path), root, "key.dir") ||
        !write_plain_file(private_path, contents, sizeof(contents), 0600) ||
        !file_read_sensitive(private_path, 1024U, &buffer, &length) ||
        length != sizeof(contents) ||
        memcmp(buffer, contents, sizeof(contents)) != 0) {
        goto cleanup;
    }
    secure_free(buffer, length);
    buffer = NULL;
    length = 0U;
    if (chmod(private_path, 0777) != 0 ||
        file_read_sensitive(private_path, 1024U, &buffer, &length) != 0 ||
        chmod(private_path, 0600) != 0 ||
        symlink(private_path, symbolic_path) != 0 ||
        file_read_sensitive(symbolic_path, 1024U, &buffer, &length) != 0 ||
        link(private_path, hardlink_path) != 0 ||
        file_read_sensitive(private_path, 1024U, &buffer, &length) != 0 ||
        unlink(hardlink_path) != 0 || mkdir(directory_path, 0700) != 0 ||
        file_read_sensitive(directory_path, 1024U, &buffer, &length) != 0) {
        goto cleanup;
    }
    file_test_fault_set(FILE_TEST_FAULT_FOREIGN_OWNER, 1U);
    if (file_read_sensitive(private_path, 1024U, &buffer, &length) != 0) {
        goto cleanup;
    }
    file_test_fault_reset();
    success = 1;

cleanup:
    secure_free(buffer, length);
    (void)unlink(symbolic_path);
    (void)unlink(hardlink_path);
    (void)unlink(private_path);
    (void)rmdir(directory_path);
    return success;
}

static int stage_bytes(AtomicFile *output, const char *path,
                       const unsigned char *data, size_t length)
{
    return atomic_file_open(output, path, 0600) &&
           file_write_all(output->stream, data, length);
}

static int test_write_failures(const char *root)
{
    unsigned char data[TEST_BUFFER_SIZE];
    char output_path[256];
    AtomicFile output = {0};
    size_t index;
    int success = 0;

    for (index = 0U; index < sizeof(data); ++index) {
        data[index] = (unsigned char)index;
    }
    if (!make_path(output_path, sizeof(output_path), root, "write.bin") ||
        !atomic_file_open(&output, output_path, 0600)) {
        goto cleanup;
    }
    file_test_fault_set(FILE_TEST_FAULT_SHORT_WRITE, 0U);
    if (!file_write_all(output.stream, data, sizeof(data))) {
        goto cleanup;
    }
    file_test_fault_reset();
    if (!atomic_file_commit(&output) ||
        !file_equals(output_path, data, sizeof(data))) {
        goto cleanup;
    }
    (void)unlink(output_path);

    if (!atomic_file_open(&output, output_path, 0600)) {
        goto cleanup;
    }
    file_test_fault_set(FILE_TEST_FAULT_ENOSPC, 1U);
    if (file_write_all(output.stream, data, sizeof(data)) != 0) {
        goto cleanup;
    }
    file_test_fault_reset();
    atomic_file_abort(&output);
    if (access(output_path, F_OK) == 0 || has_transaction_artifact(root)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    file_test_fault_reset();
    atomic_file_abort(&output);
    (void)unlink(output_path);
    secure_mem_clear(data, sizeof(data));
    return success;
}

static int test_single_file_fsync_rollback(const char *root)
{
    static const unsigned char old_data[] = "old-data";
    static const unsigned char new_data[] = "new-data";
    char output_path[256];
    AtomicFile output = {0};
    int success = 0;

    if (!make_path(output_path, sizeof(output_path), root, "fsync.bin") ||
        !write_plain_file(output_path, old_data, sizeof(old_data), 0600) ||
        !stage_bytes(&output, output_path, new_data, sizeof(new_data))) {
        goto cleanup;
    }
    file_test_fault_set(FILE_TEST_FAULT_FSYNC, 2U);
    if (atomic_file_commit(&output) != 0) {
        goto cleanup;
    }
    file_test_fault_reset();
    if (!file_equals(output_path, old_data, sizeof(old_data)) ||
        has_transaction_artifact(root)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    file_test_fault_reset();
    atomic_file_abort(&output);
    (void)unlink(output_path);
    return success;
}

static int test_pair_rollback(const char *root, FileTestFault fault,
                              unsigned int fail_on_call)
{
    static const unsigned char old_public[] = "old-public";
    static const unsigned char old_private[] = "old-private";
    static const unsigned char new_public[] = "new-public";
    static const unsigned char new_private[] = "new-private";
    char public_path[256];
    char private_path[256];
    AtomicFile public_output = {0};
    AtomicFile private_output = {0};
    int success = 0;

    if (!make_path(public_path, sizeof(public_path), root, "public.key") ||
        !make_path(private_path, sizeof(private_path), root, "private.key") ||
        !write_plain_file(public_path, old_public, sizeof(old_public), 0600) ||
        !write_plain_file(private_path, old_private, sizeof(old_private), 0600) ||
        !stage_bytes(&public_output, public_path,
                     new_public, sizeof(new_public)) ||
        !stage_bytes(&private_output, private_path,
                     new_private, sizeof(new_private))) {
        goto cleanup;
    }
    file_test_fault_set(fault, fail_on_call);
    if (atomic_file_commit_pair(&public_output, &private_output) != 0) {
        goto cleanup;
    }
    file_test_fault_reset();
    if (!file_equals(public_path, old_public, sizeof(old_public)) ||
        !file_equals(private_path, old_private, sizeof(old_private)) ||
        has_transaction_artifact(root)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    file_test_fault_reset();
    atomic_file_abort(&private_output);
    atomic_file_abort(&public_output);
    (void)unlink(private_path);
    (void)unlink(public_path);
    return success;
}

static int test_new_pair_rollback(const char *root)
{
    static const unsigned char public_data[] = "new-public";
    static const unsigned char private_data[] = "new-private";
    char public_path[256];
    char private_path[256];
    AtomicFile public_output = {0};
    AtomicFile private_output = {0};
    int success = 0;

    if (!make_path(public_path, sizeof(public_path), root, "new-public.key") ||
        !make_path(private_path, sizeof(private_path), root,
                   "new-private.key") ||
        !stage_bytes(&public_output, public_path,
                     public_data, sizeof(public_data)) ||
        !stage_bytes(&private_output, private_path,
                     private_data, sizeof(private_data))) {
        goto cleanup;
    }
    file_test_fault_set(FILE_TEST_FAULT_RENAME, 2U);
    if (atomic_file_commit_pair(&public_output, &private_output) != 0) {
        goto cleanup;
    }
    file_test_fault_reset();
    if (access(public_path, F_OK) == 0 || access(private_path, F_OK) == 0 ||
        has_transaction_artifact(root)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    file_test_fault_reset();
    atomic_file_abort(&private_output);
    atomic_file_abort(&public_output);
    (void)unlink(private_path);
    (void)unlink(public_path);
    return success;
}

int main(void)
{
    char test_directory[] = "/tmp/nekokem-file-security.XXXXXX";
    int success = 0;

    if (mkdtemp(test_directory) == NULL) {
        perror("mkdtemp file-security tests");
        goto cleanup;
    }
    if (!test_directory_validation(test_directory)) {
        fprintf(stderr, "Directory validation subtest failed\n");
        goto cleanup;
    }
    if (!test_sensitive_file_validation(test_directory)) {
        fprintf(stderr, "Sensitive-file validation subtest failed\n");
        goto cleanup;
    }
    if (!test_write_failures(test_directory)) {
        fprintf(stderr, "Short-write/ENOSPC subtest failed\n");
        goto cleanup;
    }
    if (!test_single_file_fsync_rollback(test_directory)) {
        fprintf(stderr, "Single-file fsync rollback subtest failed\n");
        goto cleanup;
    }
    if (!test_pair_rollback(test_directory, FILE_TEST_FAULT_RENAME, 2U)) {
        fprintf(stderr, "Pair rename rollback subtest failed\n");
        goto cleanup;
    }
    if (!test_pair_rollback(test_directory, FILE_TEST_FAULT_FSYNC, 3U)) {
        fprintf(stderr, "Pair fsync rollback subtest failed\n");
        goto cleanup;
    }
    if (!test_new_pair_rollback(test_directory)) {
        fprintf(stderr, "New pair rollback subtest failed\n");
        goto cleanup;
    }
    if (has_transaction_artifact(test_directory)) {
        fprintf(stderr, "Transaction artifact cleanup subtest failed\n");
        goto cleanup;
    }
    puts("File security and rollback tests passed");
    success = 1;

cleanup:
    (void)rmdir(test_directory);
    return success != 0 ? 0 : 1;
}
