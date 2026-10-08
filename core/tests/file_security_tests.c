#include "file.h"
#include "secure_mem.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <openssl/crypto.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/file.h>
#ifdef __APPLE__
#include <grp.h>
#include <membership.h>
#include <sys/acl.h>
#include <uuid/uuid.h>
#endif
#include <sys/types.h>
#include <sys/wait.h>
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

static void remove_pair_lock(const char *directory)
{
    char path[256];

    if (make_path(path, sizeof(path), directory, ".nekokem-pair.lock")) {
        (void)unlink(path);
    }
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

static int test_regular_file_validation(const char *root)
{
    static const unsigned char contents[] = "public-test";
    char regular_path[256];
    char fifo_path[256];
    unsigned char *buffer = NULL;
    size_t length = 0U;
    int success = 0;

    if (!make_path(regular_path, sizeof(regular_path), root, "public.key") ||
        !make_path(fifo_path, sizeof(fifo_path), root, "public.fifo") ||
        !write_plain_file(regular_path, contents, sizeof(contents), 0644) ||
        !file_read_regular(regular_path, 1024U, &buffer, &length) ||
        length != sizeof(contents) ||
        memcmp(buffer, contents, sizeof(contents)) != 0) {
        goto cleanup;
    }
    OPENSSL_free(buffer);
    buffer = NULL;
    length = 0U;
    if (file_read_regular(regular_path, sizeof(contents) - 1U,
                          &buffer, &length) != 0 ||
        mkfifo(fifo_path, 0600) != 0 ||
        file_read_regular(fifo_path, 1024U, &buffer, &length) != 0) {
        goto cleanup;
    }
    success = 1;

cleanup:
    OPENSSL_free(buffer);
    (void)unlink(fifo_path);
    (void)unlink(regular_path);
    return success;
}

static int test_regular_stream_validation(const char *root)
{
    char regular_path[256];
    char symbolic_path[256];
    FILE *stream = NULL;
    uint64_t size = UINT64_MAX;
    int flags;
    int success = 0;

    if (!make_path(regular_path, sizeof(regular_path), root, "empty") ||
        !make_path(symbolic_path, sizeof(symbolic_path), root, "empty.link") ||
        !write_plain_file(regular_path, NULL, 0U, 0600) ||
        symlink(regular_path, symbolic_path) != 0) {
        goto cleanup;
    }
    stream = file_open_regular(symbolic_path);
    if (stream == NULL) {
        goto cleanup;
    }
    flags = fcntl(fileno(stream), F_GETFD);
    if (!file_get_size(stream, &size) || size != 0U ||
        flags < 0 || (flags & FD_CLOEXEC) == 0 ||
        fgetc(stream) != EOF || ferror(stream) != 0) {
        goto cleanup;
    }
    if (fclose(stream) != 0) {
        stream = NULL;
        goto cleanup;
    }
    stream = file_open_regular(root);
    if (stream != NULL) {
        goto cleanup;
    }
    success = 1;

cleanup:
    if (stream != NULL) {
        (void)fclose(stream);
    }
    (void)unlink(symbolic_path);
    (void)unlink(regular_path);
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

static int test_single_file_flush_failure(const char *root, FileTestFault fault)
{
    static const unsigned char old_data[] = "old-data";
    static const unsigned char new_data[] = "new-data";
    char output_path[256];
    AtomicFile output = {0};
    int success = 0;

    if (!make_path(output_path, sizeof(output_path), root, "flush.bin") ||
        !write_plain_file(output_path, old_data, sizeof(old_data), 0600) ||
        !stage_bytes(&output, output_path, new_data, sizeof(new_data))) {
        goto cleanup;
    }
    file_test_fault_set(fault, 1U);
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

static char symlink_target_path[256];
static nlink_t symlink_target_links;

/* At publication the backup must link the symbolic link, not its target. */
static void record_symlink_target_links(const char *final_path)
{
    struct stat status;

    (void)final_path;
    symlink_target_links = lstat(symlink_target_path, &status) == 0
                               ? status.st_nlink
                               : 0;
}

static int test_symlink_output_replacement(const char *root)
{
    static const unsigned char target_data[] = "target-data";
    static const unsigned char output_data[] = "output-data";
    char target_path[256];
    char output_path[256];
    AtomicFile output = {0};
    struct stat status;
    int success = 0;

    if (!make_path(target_path, sizeof(target_path), root, "target.bin") ||
        !make_path(output_path, sizeof(output_path), root, "output.link") ||
        !make_path(symlink_target_path, sizeof(symlink_target_path), root,
                   "target.bin") ||
        !write_plain_file(target_path, target_data,
                          sizeof(target_data), 0600) ||
        symlink(target_path, output_path) != 0 ||
        !stage_bytes(&output, output_path,
                     output_data, sizeof(output_data)) ||
        fstat(fileno(output.stream), &status) != 0 ||
        (status.st_mode & (mode_t)0777) != (mode_t)0600) {
        goto cleanup;
    }
    symlink_target_links = 0;
    file_test_set_before_noreplace_rename(record_symlink_target_links);
    if (!atomic_file_commit(&output) || symlink_target_links != 1 ||
        lstat(output_path, &status) != 0 ||
        !S_ISREG(status.st_mode) ||
        !file_equals(output_path, output_data, sizeof(output_data)) ||
        !file_equals(target_path, target_data, sizeof(target_data)) ||
        has_transaction_artifact(root)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    file_test_fault_reset();
    atomic_file_abort(&output);
    (void)unlink(output_path);
    (void)unlink(target_path);
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

static int test_new_pair_rollback(const char *root, int create_only)
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
    if ((create_only != 0
             ? atomic_file_commit_pair_new(&public_output, &private_output)
             : atomic_file_commit_pair(&public_output, &private_output)) != 0) {
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

/* Android denies apps link(); commits must work without hard links. */
static int test_commits_without_links(const char *root)
{
    static const unsigned char old_public[] = "old-public";
    static const unsigned char old_private[] = "old-private";
    static const unsigned char new_public[] = "new-public";
    static const unsigned char new_private[] = "new-private";
    char public_path[256];
    char private_path[256];
    AtomicFile public_output = {0};
    AtomicFile private_output = {0};
    struct stat status;
    int success = 0;

    if (!make_path(public_path, sizeof(public_path), root, "nolink-public.key") ||
        !make_path(private_path, sizeof(private_path), root,
                   "nolink-private.key") ||
        !stage_bytes(&public_output, public_path,
                     old_public, sizeof(old_public)) ||
        !stage_bytes(&private_output, private_path,
                     old_private, sizeof(old_private)) ||
        !atomic_file_commit_pair_new(&public_output, &private_output) ||
        !file_equals(public_path, old_public, sizeof(old_public)) ||
        !file_equals(private_path, old_private, sizeof(old_private)) ||
        has_transaction_artifact(root)) {
        goto cleanup;
    }
    /* Create-only still refuses names that exist. */
    if (!stage_bytes(&public_output, public_path,
                     new_public, sizeof(new_public)) ||
        !stage_bytes(&private_output, private_path,
                     new_private, sizeof(new_private)) ||
        atomic_file_commit_pair_new(&public_output, &private_output) != 0 ||
        !file_equals(public_path, old_public, sizeof(old_public)) ||
        !file_equals(private_path, old_private, sizeof(old_private)) ||
        has_transaction_artifact(root)) {
        goto cleanup;
    }
    /* Replacing keeps copies as backups and removes them afterwards. */
    if (chmod(private_path, 0400) != 0 ||
        !stage_bytes(&public_output, public_path,
                     new_public, sizeof(new_public)) ||
        !stage_bytes(&private_output, private_path,
                     new_private, sizeof(new_private)) ||
        !atomic_file_commit_pair(&public_output, &private_output) ||
        !file_equals(public_path, new_public, sizeof(new_public)) ||
        !file_equals(private_path, new_private, sizeof(new_private)) ||
        lstat(private_path, &status) != 0 ||
        (status.st_mode & (mode_t)0777) != (mode_t)0600 ||
        has_transaction_artifact(root)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    atomic_file_abort(&private_output);
    atomic_file_abort(&public_output);
    (void)unlink(private_path);
    (void)unlink(public_path);
    return success;
}

static const unsigned char competing_key[] = "concurrently-created-key";
static unsigned int competing_publish_call;
static unsigned int competing_publish_target;
static int competing_write_succeeded;

static void create_competing_key(const char *final_path)
{
    ++competing_publish_call;
    if (competing_publish_call == competing_publish_target) {
        int descriptor = open(final_path, O_WRONLY | O_CREAT | O_EXCL, 0600);

        if (descriptor >= 0) {
            competing_write_succeeded =
                write(descriptor, competing_key, sizeof(competing_key)) ==
                (ssize_t)sizeof(competing_key);
            if (close(descriptor) != 0) {
                competing_write_succeeded = 0;
            }
        }
    }
}

/* A writer wins the destination immediately before the rename syscall. */
static int test_noreplace_race(const char *root, unsigned int collision_on)
{
    static const unsigned char data[] = "new-key";
    char public_path[256] = {0};
    char private_path[256] = {0};
    AtomicFile public_output = {0};
    AtomicFile private_output = {0};
    int success = 0;

    if (!make_path(public_path, sizeof(public_path), root, "race-public.key") ||
        !make_path(private_path, sizeof(private_path), root, "race-private.key") ||
        !stage_bytes(&public_output, public_path, data, sizeof(data)) ||
        !stage_bytes(&private_output, private_path, data, sizeof(data))) {
        goto cleanup;
    }
    competing_publish_call = 0U;
    competing_publish_target = collision_on;
    competing_write_succeeded = 0;
    file_test_set_before_noreplace_rename(create_competing_key);
    if (atomic_file_commit_pair_new(&public_output, &private_output) != 0 ||
        errno != EEXIST || competing_write_succeeded == 0 ||
        !file_equals(collision_on == 1U ? public_path : private_path,
                     competing_key, sizeof(competing_key)) ||
        access(collision_on == 1U ? private_path : public_path, F_OK) == 0 ||
        has_transaction_artifact(root)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    file_test_fault_reset();
    atomic_file_abort(&private_output);
    atomic_file_abort(&public_output);
    if (private_path[0] != '\0') {
        (void)unlink(private_path);
    }
    if (public_path[0] != '\0') {
        (void)unlink(public_path);
    }
    return success;
}

static int test_noreplace_unavailable(const char *root, unsigned int fail_on)
{
    static const unsigned char data[] = "new-key";
    char public_path[256] = {0};
    char private_path[256] = {0};
    AtomicFile public_output = {0};
    AtomicFile private_output = {0};
    int success = 0;

    if (!make_path(public_path, sizeof(public_path), root, "unsupported-public.key") ||
        !make_path(private_path, sizeof(private_path), root, "unsupported-private.key") ||
        !stage_bytes(&public_output, public_path, data, sizeof(data)) ||
        !stage_bytes(&private_output, private_path, data, sizeof(data))) {
        goto cleanup;
    }
    file_test_fault_set(FILE_TEST_FAULT_NOREPLACE_UNAVAILABLE, fail_on);
    if (atomic_file_commit_pair_new(&public_output, &private_output) != 0 ||
        errno != ENOTSUP || access(public_path, F_OK) == 0 ||
        access(private_path, F_OK) == 0 || has_transaction_artifact(root)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    file_test_fault_reset();
    atomic_file_abort(&private_output);
    atomic_file_abort(&public_output);
    if (private_path[0] != '\0') {
        (void)unlink(private_path);
    }
    if (public_path[0] != '\0') {
        (void)unlink(public_path);
    }
    return success;
}

static const unsigned char other_public[] = "other-public";
static const unsigned char other_private[] = "other-private";
static char interrupted_public_path[256];
static char interrupted_lock_path[256];
static unsigned int interrupted_publish_count;
static int other_pair_created;
static int lock_blocked_another_process;

static void replace_published_key_before_second_commit(const char *private_path)
{
    char replacement_path[256];
    pid_t child;
    int status;
    int path_length;

    if (++interrupted_publish_count != 2U) {
        return;
    }
    child = fork();
    if (child == 0) {
        int descriptor = open(interrupted_lock_path, O_RDWR | O_CLOEXEC);
        int result = descriptor >= 0 ? flock(descriptor, LOCK_EX | LOCK_NB) : 0;
        int blocked = result != 0 &&
                      (errno == EWOULDBLOCK || errno == EAGAIN);

        if (descriptor >= 0) {
            (void)close(descriptor);
        }
        _exit(blocked ? 0 : 1);
    }
    if (child <= 0 || waitpid(child, &status, 0) != child ||
        !WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        return;
    }
    lock_blocked_another_process = 1;

    /* The competing writer deliberately ignores the application's lock. */
    path_length = snprintf(replacement_path, sizeof(replacement_path),
                           "%s.other", interrupted_public_path);
    if (path_length < 0 || (size_t)path_length >= sizeof(replacement_path)) {
        return;
    }
    if (!write_plain_file(replacement_path, other_public,
                          sizeof(other_public), 0600) ||
        rename(replacement_path, interrupted_public_path) != 0 ||
        !write_plain_file(private_path, other_private,
                          sizeof(other_private), 0600)) {
        (void)unlink(replacement_path);
        return;
    }
    other_pair_created = 1;
}

static int test_interleaved_pair_rollback(const char *root)
{
    static const unsigned char our_public[] = "our-public";
    static const unsigned char our_private[] = "our-private";
    char private_path[256] = {0};
    AtomicFile public_output = {0};
    AtomicFile private_output = {0};
    int success = 0;

    interrupted_public_path[0] = '\0';
    if (!make_path(interrupted_public_path, sizeof(interrupted_public_path),
                   root, "interleaved-public.key") ||
        !make_path(private_path, sizeof(private_path), root,
                   "interleaved-private.key") ||
        !make_path(interrupted_lock_path, sizeof(interrupted_lock_path), root,
                   ".nekokem-pair.lock") ||
        !stage_bytes(&public_output, interrupted_public_path,
                     our_public, sizeof(our_public)) ||
        !stage_bytes(&private_output, private_path,
                     our_private, sizeof(our_private))) {
        goto cleanup;
    }
    interrupted_publish_count = 0U;
    other_pair_created = 0;
    lock_blocked_another_process = 0;
    file_test_set_before_noreplace_rename(
        replace_published_key_before_second_commit);
    if (atomic_file_commit_pair_new(&public_output, &private_output) != 0 ||
        errno != EEXIST || other_pair_created == 0 ||
        lock_blocked_another_process == 0 ||
        !file_equals(interrupted_public_path, other_public,
                     sizeof(other_public)) ||
        !file_equals(private_path, other_private, sizeof(other_private)) ||
        has_transaction_artifact(root)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    file_test_fault_reset();
    atomic_file_abort(&private_output);
    atomic_file_abort(&public_output);
    if (private_path[0] != '\0') {
        (void)unlink(private_path);
    }
    if (interrupted_public_path[0] != '\0') {
        (void)unlink(interrupted_public_path);
    }
    return success;
}

static char swapped_public_path[256];
static unsigned int swap_publish_count;
static const unsigned char other_writer_public[] = "other-writer-public";

/* Another writer replaces our first published key, then our second publish fails. */
static void swap_first_key_before_second_publish(const char *final_path)
{
    char staged[300];
    int written;

    (void)final_path;
    if (++swap_publish_count != 2U) {
        return;
    }
    written = snprintf(staged, sizeof(staged), "%s.other", swapped_public_path);
    if (written > 0 && (size_t)written < sizeof(staged) &&
        write_plain_file(staged, other_writer_public,
                         sizeof(other_writer_public), 0600) &&
        rename(staged, swapped_public_path) == 0) {
        file_test_fault_set(FILE_TEST_FAULT_RENAME, 1U);
    }
}

static int test_replaced_output_keeps_backup(const char *root)
{
    static const unsigned char old_public[] = "old-public";
    static const unsigned char old_private[] = "old-private";
    static const unsigned char new_public[] = "new-public";
    static const unsigned char new_private[] = "new-private";
    static const char prefix[] = "swapped-public.key.bak.";
    char private_path[256] = {0};
    char backup_path[512] = {0};
    AtomicFile public_output = {0};
    AtomicFile private_output = {0};
    DIR *stream = NULL;
    struct dirent *entry;
    int success = 0;

    swapped_public_path[0] = '\0';
    if (!make_path(swapped_public_path, sizeof(swapped_public_path), root,
                   "swapped-public.key") ||
        !make_path(private_path, sizeof(private_path), root,
                   "swapped-private.key") ||
        !write_plain_file(swapped_public_path, old_public,
                          sizeof(old_public), 0600) ||
        !write_plain_file(private_path, old_private,
                          sizeof(old_private), 0600) ||
        !stage_bytes(&public_output, swapped_public_path,
                     new_public, sizeof(new_public)) ||
        !stage_bytes(&private_output, private_path,
                     new_private, sizeof(new_private))) {
        goto cleanup;
    }
    swap_publish_count = 0U;
    file_test_set_before_noreplace_rename(swap_first_key_before_second_publish);
    if (atomic_file_commit_pair(&public_output, &private_output) != 0) {
        goto cleanup;
    }
    file_test_fault_reset();
    /* Rollback leaves the other writer's file and never publishes ours. */
    if (swap_publish_count != 2U ||
        !file_equals(swapped_public_path, other_writer_public,
                     sizeof(other_writer_public)) ||
        !file_equals(private_path, old_private, sizeof(old_private))) {
        goto cleanup;
    }
    /* The old public key stays in the backup that the message names. */
    stream = opendir(root);
    while (stream != NULL && (entry = readdir(stream)) != NULL) {
        if (strncmp(entry->d_name, prefix, sizeof(prefix) - 1U) == 0 &&
            !make_path(backup_path, sizeof(backup_path), root, entry->d_name)) {
            goto cleanup;
        }
    }
    if (backup_path[0] == '\0' ||
        !file_equals(backup_path, old_public, sizeof(old_public))) {
        goto cleanup;
    }
    success = 1;

cleanup:
    file_test_fault_reset();
    if (stream != NULL) {
        (void)closedir(stream);
    }
    atomic_file_abort(&private_output);
    atomic_file_abort(&public_output);
    if (backup_path[0] != '\0') {
        (void)unlink(backup_path);
    }
    if (private_path[0] != '\0') {
        (void)unlink(private_path);
    }
    if (swapped_public_path[0] != '\0') {
        (void)unlink(swapped_public_path);
    }
    return success;
}

static int test_pair_alias_rejection(const char *root)
{
    static const unsigned char public_data[] = "public";
    static const unsigned char private_data[] = "private";
    char directory[256];
    char public_path[256];
    char aliased_path[256];
    AtomicFile public_output = {0};
    AtomicFile private_output = {0};
    int success = 0;

    if (!make_path(directory, sizeof(directory), root, "alias") ||
        !make_path(public_path, sizeof(public_path), directory,
                   "public.key") ||
        !make_path(aliased_path, sizeof(aliased_path), directory,
                   "./public.key") ||
        mkdir(directory, 0700) != 0 ||
        !stage_bytes(&public_output, public_path,
                     public_data, sizeof(public_data)) ||
        !stage_bytes(&private_output, aliased_path,
                     private_data, sizeof(private_data))) {
        goto cleanup;
    }
    if (atomic_file_commit_pair(&public_output, &private_output) != 0 ||
        access(public_path, F_OK) == 0 ||
        has_transaction_artifact(directory)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    atomic_file_abort(&private_output);
    atomic_file_abort(&public_output);
    (void)unlink(aliased_path);
    (void)unlink(public_path);
    remove_pair_lock(directory);
    (void)rmdir(directory);
    return success;
}

#ifdef __APPLE__
static int test_filesystem_alias_rejection(const char *root,
                                          const char *first_name,
                                          const char *second_name)
{
    static const unsigned char old_data[] = "old-data";
    static const unsigned char public_data[] = "public";
    static const unsigned char private_data[] = "private";
    char first_path[256] = {0};
    char second_path[256] = {0};
    AtomicFile first = {0};
    AtomicFile second = {0};
    struct stat first_status;
    struct stat second_status;
    int success = 0;

    if (!make_path(first_path, sizeof(first_path), root, first_name) ||
        !make_path(second_path, sizeof(second_path), root, second_name) ||
        !write_plain_file(first_path, old_data, sizeof(old_data), 0600) ||
        lstat(first_path, &first_status) != 0) {
        goto cleanup;
    }
    if (lstat(second_path, &second_status) != 0) {
        if (errno != ENOENT) {
            goto cleanup;
        }
        /* A case-sensitive/normalization-sensitive volume has distinct names. */
        if (!stage_bytes(&first, first_path, public_data, sizeof(public_data)) ||
            !stage_bytes(&second, second_path, private_data, sizeof(private_data)) ||
            !atomic_file_commit_pair(&first, &second) ||
            !file_equals(first_path, public_data, sizeof(public_data)) ||
            !file_equals(second_path, private_data, sizeof(private_data))) {
            goto cleanup;
        }
        success = 1;
        goto cleanup;
    }
    if (first_status.st_dev != second_status.st_dev ||
        first_status.st_ino != second_status.st_ino ||
        !stage_bytes(&first, first_path, public_data, sizeof(public_data)) ||
        !stage_bytes(&second, second_path, private_data, sizeof(private_data)) ||
        atomic_file_commit_pair(&first, &second) != 0 ||
        !file_equals(first_path, old_data, sizeof(old_data)) ||
        !file_equals(second_path, old_data, sizeof(old_data)) ||
        has_transaction_artifact(root) || unlink(first_path) != 0) {
        goto cleanup;
    }
    /* Also reject aliases when neither output existed before the transaction. */
    if (!stage_bytes(&first, first_path, public_data, sizeof(public_data)) ||
        !stage_bytes(&second, second_path, private_data, sizeof(private_data)) ||
        atomic_file_commit_pair(&first, &second) != 0 ||
        access(first_path, F_OK) == 0 || access(second_path, F_OK) == 0 ||
        has_transaction_artifact(root)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    atomic_file_abort(&second);
    atomic_file_abort(&first);
    (void)unlink(second_path);
    (void)unlink(first_path);
    return success;
}

static int set_test_acl(const char *path, acl_tag_t tag,
                        acl_perm_t permission, int inherit)
{
    struct group *everyone = getgrnam("everyone");
    uuid_t qualifier;
    acl_t acl = acl_init(tag == ACL_UNDEFINED_TAG ? 0 : 1);
    acl_entry_t entry;
    acl_permset_t permissions;
    int success = 0;

    if (acl == NULL) {
        return 0;
    }
    if (tag != ACL_UNDEFINED_TAG) {
        if (everyone == NULL ||
            mbr_gid_to_uuid(everyone->gr_gid, qualifier) != 0 ||
            acl_create_entry(&acl, &entry) != 0 ||
            acl_set_tag_type(entry, tag) != 0 ||
            acl_set_qualifier(entry, qualifier) != 0 ||
            acl_get_permset(entry, &permissions) != 0 ||
            acl_add_perm(permissions, permission) != 0 ||
            acl_set_permset(entry, permissions) != 0) {
            goto cleanup;
        }
        if (inherit != 0) {
            acl_flagset_t flags;

            if (acl_get_flagset_np(entry, &flags) != 0 ||
                acl_add_flag_np(flags, ACL_ENTRY_FILE_INHERIT) != 0 ||
                acl_set_flagset_np(entry, flags) != 0) {
                goto cleanup;
            }
        }
    }
    success = acl_set_file(path, ACL_TYPE_EXTENDED, acl) == 0;

cleanup:
    (void)acl_free(acl);
    return success;
}

static int test_darwin_acl_validation(const char *root)
{
    static const unsigned char contents[] = "acl-test";
    char file_path[256] = {0};
    char directory_path[256] = {0};
    char output_path[256] = {0};
    unsigned char *bytes = NULL;
    size_t length = 0U;
    AtomicFile output = {0};
    int directory_descriptor = -1;
    int success = 0;

    if (!make_path(file_path, sizeof(file_path), root, "acl.key") ||
        !make_path(directory_path, sizeof(directory_path), root, "acl-dir") ||
        !make_path(output_path, sizeof(output_path), directory_path, "output.key") ||
        !write_plain_file(file_path, contents, sizeof(contents), 0600) ||
        mkdir(directory_path, 0700) != 0 ||
        !set_test_acl(file_path, ACL_EXTENDED_ALLOW, ACL_READ_DATA, 0) ||
        file_read_sensitive(file_path, sizeof(contents), &bytes, &length) != 0 ||
        bytes != NULL || length != 0U ||
        !set_test_acl(file_path, ACL_EXTENDED_DENY, ACL_EXECUTE, 0) ||
        !file_read_sensitive(file_path, sizeof(contents), &bytes, &length) ||
        length != sizeof(contents) || memcmp(bytes, contents, length) != 0 ||
        !set_test_acl(directory_path, ACL_EXTENDED_ALLOW, ACL_LIST_DIRECTORY, 1) ||
        ensure_directory(directory_path, 0700) != 0 ||
        atomic_file_open(&output, output_path, 0600) != 0 ||
        has_transaction_artifact(directory_path) ||
        !set_test_acl(directory_path, ACL_UNDEFINED_TAG, ACL_READ_DATA, 0) ||
        !ensure_directory(directory_path, 0700)) {
        goto cleanup;
    }
    directory_descriptor = open(directory_path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (directory_descriptor < 0 ||
        file_sync_regular_fd(directory_descriptor) != -1 || errno != EINVAL) {
        goto cleanup;
    }
    success = 1;

cleanup:
    if (directory_descriptor >= 0) {
        (void)close(directory_descriptor);
    }
    atomic_file_abort(&output);
    secure_free(bytes, length);
    (void)set_test_acl(file_path, ACL_UNDEFINED_TAG, ACL_READ_DATA, 0);
    (void)set_test_acl(directory_path, ACL_UNDEFINED_TAG, ACL_READ_DATA, 0);
    (void)unlink(output_path);
    (void)unlink(file_path);
    (void)rmdir(directory_path);
    return success;
}
#endif

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
    if (!test_regular_file_validation(test_directory)) {
        fprintf(stderr, "Regular-file validation subtest failed\n");
        goto cleanup;
    }
    if (!test_regular_stream_validation(test_directory)) {
        fprintf(stderr, "Regular-stream validation subtest failed\n");
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
    if (!test_single_file_flush_failure(test_directory, FILE_TEST_FAULT_FSYNC)) {
        fprintf(stderr, "Temporary-file fsync failure subtest failed\n");
        goto cleanup;
    }
#ifdef __APPLE__
    if (!test_single_file_flush_failure(test_directory, FILE_TEST_FAULT_FULLFSYNC) ||
        !test_pair_rollback(test_directory, FILE_TEST_FAULT_FULLFSYNC, 2U)) {
        fprintf(stderr, "Darwin device-cache flush failure subtest failed\n");
        goto cleanup;
    }
    if (!test_darwin_acl_validation(test_directory)) {
        fprintf(stderr, "Darwin extended ACL subtest failed\n");
        goto cleanup;
    }
    if (!test_filesystem_alias_rejection(test_directory, "Case.key", "case.key") ||
        !test_filesystem_alias_rejection(test_directory,
                                         "caf\xc3\xa9.key", "cafe\xcc\x81.key")) {
        fprintf(stderr, "Darwin filesystem alias rollback subtest failed\n");
        goto cleanup;
    }
#endif
    if (!test_symlink_output_replacement(test_directory)) {
        fprintf(stderr, "Symlink output replacement subtest failed\n");
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
    if (!test_new_pair_rollback(test_directory, 0) ||
        !test_new_pair_rollback(test_directory, 1)) {
        fprintf(stderr, "New pair rollback subtest failed\n");
        goto cleanup;
    }
    if (!test_replaced_output_keeps_backup(test_directory)) {
        fprintf(stderr, "Replaced output backup subtest failed\n");
        goto cleanup;
    }
    file_test_set_links_unavailable(1);
    if (!test_interleaved_pair_rollback(test_directory) ||
        !test_replaced_output_keeps_backup(test_directory) ||
        !test_noreplace_race(test_directory, 1U) ||
        !test_noreplace_race(test_directory, 2U) ||
        !test_noreplace_unavailable(test_directory, 1U) ||
        !test_noreplace_unavailable(test_directory, 2U) ||
        !test_commits_without_links(test_directory) ||
        !test_pair_rollback(test_directory, FILE_TEST_FAULT_RENAME, 2U) ||
        !test_pair_rollback(test_directory, FILE_TEST_FAULT_FSYNC, 3U) ||
        !test_new_pair_rollback(test_directory, 0) ||
        !test_new_pair_rollback(test_directory, 1)) {
        file_test_set_links_unavailable(0);
        fprintf(stderr, "Commit without hard links subtest failed\n");
        goto cleanup;
    }
    file_test_set_links_unavailable(0);
    if (!test_pair_alias_rejection(test_directory)) {
        fprintf(stderr, "Pair alias rejection subtest failed\n");
        goto cleanup;
    }
    if (has_transaction_artifact(test_directory)) {
        fprintf(stderr, "Transaction artifact cleanup subtest failed\n");
        goto cleanup;
    }
    puts("File security and rollback tests passed");
    success = 1;

cleanup:
    remove_pair_lock(test_directory);
    (void)rmdir(test_directory);
    return success != 0 ? 0 : 1;
}
