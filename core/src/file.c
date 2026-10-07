#if defined(__linux__) && !defined(_GNU_SOURCE)
#define _GNU_SOURCE
#endif
#if defined(__APPLE__) && !defined(_DARWIN_C_SOURCE)
#define _DARWIN_C_SOURCE
#endif

#include "file.h"
#include "secure_mem.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <openssl/err.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#ifndef _WIN32
#include <sys/file.h>
#endif
#ifdef __linux__
#include <linux/fs.h>
#include <sys/syscall.h>
#endif
#ifdef __APPLE__
#include <sys/acl.h>
#endif
#ifndef _WIN32
#include <unistd.h>
#endif

#ifndef O_NOFOLLOW
#define O_NOFOLLOW 0
#endif

#ifdef NEKOKEM_TEST_FAULT_INJECTION
typedef struct {
    FileTestFault fault;
    unsigned int fail_on_call;
    unsigned int call_count;
} FileTestFaultState;

static FileTestFaultState test_fault;
static int test_links_unavailable;
static void (*test_before_noreplace_rename)(const char *);

void file_test_set_before_noreplace_rename(void (*hook)(const char *))
{
    test_before_noreplace_rename = hook;
}

void file_test_set_links_unavailable(int unavailable)
{
    test_links_unavailable = unavailable;
}

void file_test_fault_set(FileTestFault fault, unsigned int fail_on_call)
{
    test_fault.fault = fault;
    test_fault.fail_on_call = fail_on_call;
    test_fault.call_count = 0U;
}

void file_test_fault_reset(void)
{
    test_fault.fault = FILE_TEST_FAULT_NONE;
    test_fault.fail_on_call = 0U;
    test_fault.call_count = 0U;
    test_before_noreplace_rename = NULL;
}

static int test_fault_should_fail(FileTestFault fault)
{
    if (test_fault.fault != fault) {
        return 0;
    }
    ++test_fault.call_count;
    return test_fault.fail_on_call == 0U ||
           test_fault.call_count == test_fault.fail_on_call;
}
#endif

#ifndef _WIN32
static int file_fsync(int descriptor)
{
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (test_fault_should_fail(FILE_TEST_FAULT_FSYNC)) {
        errno = EIO;
        return -1;
    }
#endif
    return fsync(descriptor);
}

int file_sync_regular_fd(int descriptor)
{
#ifdef __APPLE__
    struct stat status;

    /* F_FULLFSYNC is a regular-file durability barrier, not a directory API. */
    if (fstat(descriptor, &status) != 0) {
        return -1;
    }
    if (!S_ISREG(status.st_mode)) {
        errno = EINVAL;
        return -1;
    }
#endif
    if (file_fsync(descriptor) != 0) {
        return -1;
    }
#ifdef __APPLE__
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (test_fault_should_fail(FILE_TEST_FAULT_FULLFSYNC)) {
        errno = EIO;
        return -1;
    }
#endif
    /* Never turn an unsupported or failed device-cache flush into success. */
    return fcntl(descriptor, F_FULLFSYNC);
#else
    return 0;
#endif
}

int file_private_acl_is_safe(int descriptor)
{
#ifdef __APPLE__
    acl_t acl;
    acl_entry_t entry;
    int entry_id = ACL_FIRST_ENTRY;
    int saved_errno;
    int success = 0;

    errno = 0;
    acl = acl_get_fd_np(descriptor, ACL_TYPE_EXTENDED);
    if (acl == NULL) {
        /* Darwin reports an absent extended ACL as ENOENT. */
        return errno == ENOENT;
    }
    if (acl_valid(acl) != 0) {
        goto cleanup;
    }
    while (acl_get_entry(acl, entry_id, &entry) == 0) {
        acl_tag_t tag;

        if (acl_get_tag_type(entry, &tag) != 0) {
            goto cleanup;
        }
        /* Mode 0600/0700 cannot mask a Darwin allow ACE. Deny ACEs are safe. */
        if (tag != ACL_EXTENDED_DENY) {
            errno = EACCES;
            goto cleanup;
        }
        entry_id = ACL_NEXT_ENTRY;
    }
    /* Unlike POSIX ACL iterators, Darwin returns -1/EINVAL at the end. */
    if (errno == EINVAL) {
        success = 1;
    }

cleanup:
    saved_errno = errno;
    (void)acl_free(acl);
    errno = saved_errno;
    return success;
#else
    (void)descriptor;
    return 1;
#endif
}

static int file_rename(const char *old_path, const char *new_path)
{
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (test_fault_should_fail(FILE_TEST_FAULT_RENAME)) {
        errno = EIO;
        return -1;
    }
#endif
    return rename(old_path, new_path);
}

/*
 * Volumes and policies without hard links make link() fail: FAT and some
 * network shares, and Android, whose SELinux policy denies apps link() in
 * their own data directories (EACCES).
 */
static int file_link(const char *existing_path, const char *new_path)
{
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (test_links_unavailable != 0) {
        errno = EACCES;
        return -1;
    }
#endif
    return link(existing_path, new_path);
}

static int file_rename_new(const char *temporary_path, const char *final_path)
{
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (test_before_noreplace_rename != NULL) {
        test_before_noreplace_rename(final_path);
    }
    if (test_fault_should_fail(FILE_TEST_FAULT_NOREPLACE_UNAVAILABLE)) {
        errno = ENOTSUP;
        return -1;
    }
#endif
#if defined(__linux__) && defined(SYS_renameat2)
    /* The syscall also works before Android's libc exposes renameat2(). */
    return (int)syscall(SYS_renameat2, AT_FDCWD, temporary_path,
                       AT_FDCWD, final_path, RENAME_NOREPLACE);
#elif defined(__APPLE__) && defined(RENAME_EXCL)
    return renamex_np(temporary_path, final_path, RENAME_EXCL);
#else
    (void)temporary_path;
    (void)final_path;
    errno = ENOTSUP;
    return -1;
#endif
}

/* Both publication paths atomically refuse an existing destination. */
static int file_publish_new(const char *temporary_path, const char *final_path)
{
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (test_fault_should_fail(FILE_TEST_FAULT_RENAME)) {
        errno = EIO;
        return -1;
    }
#endif
    if (file_link(temporary_path, final_path) == 0) {
        if (unlink(temporary_path) == 0) {
            return 0;
        }
        {
            /* A second link would make a private key unreadable later. */
            int saved_errno = errno;
            (void)unlink(final_path);
            errno = saved_errno;
        }
        return -1;
    }
    if (errno == EEXIST) {
        return -1;
    }
    /* Never fall back to a check followed by an overwriting rename(). */
    return file_rename_new(temporary_path, final_path);
}

#endif

static const char *(*message_translator)(const char *);

void file_set_message_translator(const char *(*translator)(const char *))
{
    message_translator = translator;
}

const char *file_message(const char *message)
{
    int saved_errno = errno;
    const char *translated = message_translator != NULL ? message_translator(message) : message;
    errno = saved_errno;
    return translated;
}

void print_openssl_error(const char *context)
{
    unsigned long error_code;
    char error_text[256];

    fprintf(stderr, "%s\n", file_message(context));
    while ((error_code = ERR_get_error()) != 0UL) {
        ERR_error_string_n(error_code, error_text, sizeof(error_text));
        fprintf(stderr, "OpenSSL: %s\n", error_text);
    }
}

void print_system_error(const char *context)
{
    fprintf(stderr, "%s: %s\n", file_message(context), strerror(errno));
}

int file_output_spares_keys(const char *output_path,
                            const char *const *key_paths,
                            size_t key_count)
{
    size_t index;

    for (index = 0U; index < key_count; ++index) {
        int same = 0;

        if (!file_paths_are_same_file(output_path, key_paths[index], &same)) {
            print_system_error("Cannot compare the output with the key files");
            return 0;
        }
        if (same != 0) {
            fprintf(stderr, file_message("The output path is a key file this operation uses; choose another output path\n"));
            return 0;
        }
    }
    return 1;
}

#ifndef _WIN32
int ensure_directory(const char *path, mode_t mode)
{
    struct stat status;
    mode_t requested_mode = mode & (mode_t)0777;

    if (path == NULL || requested_mode != mode) {
        errno = EINVAL;
        print_system_error("Invalid private directory request");
        return 0;
    }
    if (mkdir(path, requested_mode) != 0 && errno != EEXIST) {
        print_system_error("Cannot create directory");
        return 0;
    }
    if (lstat(path, &status) != 0) {
        print_system_error("Cannot inspect directory");
        return 0;
    }
    if (!S_ISDIR(status.st_mode)) {
        fprintf(stderr, file_message("%s exists but is not a directory\n"), path);
        return 0;
    }
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (test_fault_should_fail(FILE_TEST_FAULT_FOREIGN_OWNER)) {
        fprintf(stderr, file_message("%s is not owned by the current user\n"), path);
        return 0;
    }
#endif
    if (status.st_uid != geteuid()) {
        fprintf(stderr, file_message("%s is not owned by the current user\n"), path);
        return 0;
    }
    if ((status.st_mode & (mode_t)0777) != requested_mode) {
        fprintf(stderr, file_message("%s has unsafe permissions (expected %03o)\n"),
                path, (unsigned int)requested_mode);
        return 0;
    }
#ifdef __APPLE__
    {
        int descriptor = open(path, O_RDONLY | O_CLOEXEC |
                                    O_NOFOLLOW | O_DIRECTORY);
        struct stat opened_status;
        int safe = descriptor >= 0 &&
                   fstat(descriptor, &opened_status) == 0 &&
                   opened_status.st_dev == status.st_dev &&
                   opened_status.st_ino == status.st_ino &&
                   file_private_acl_is_safe(descriptor);
        int saved_errno = errno;

        if (descriptor >= 0 && close(descriptor) != 0 && safe != 0) {
            saved_errno = errno;
            safe = 0;
        }
        if (safe == 0) {
            errno = saved_errno != 0 ? saved_errno : EACCES;
            print_system_error("Cannot inspect directory");
            return 0;
        }
    }
#endif
    return 1;
}

FILE *file_open_regular(const char *path)
{
    struct stat status;
    FILE *stream;
    int descriptor;

    if (path == NULL || path[0] == '\0') {
        errno = EINVAL;
        print_system_error("Invalid regular input path");
        return NULL;
    }
    /* Reject FIFOs before a blocking open can prevent validation/cancellation. */
    descriptor = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (descriptor < 0) {
        print_system_error("Cannot open regular input file");
        return NULL;
    }
    if (fstat(descriptor, &status) != 0) {
        print_system_error("Cannot inspect regular input file");
        (void)close(descriptor);
        return NULL;
    }
    if (!S_ISREG(status.st_mode) || status.st_size < 0) {
        fprintf(stderr, file_message("Input must be a regular file with a valid size\n"));
        (void)close(descriptor);
        return NULL;
    }
    stream = fdopen(descriptor, "rb");
    if (stream == NULL) {
        print_system_error("Cannot open regular input stream");
        (void)close(descriptor);
    }
    return stream;
}

int file_get_size(FILE *stream, uint64_t *size)
{
    struct stat status;

    if (stream == NULL || size == NULL) {
        errno = EINVAL;
        print_system_error("Invalid file size request");
        return 0;
    }
    if (fstat(fileno(stream), &status) != 0) {
        print_system_error("Cannot inspect input file");
        return 0;
    }
    if (!S_ISREG(status.st_mode)) {
        fprintf(stderr, file_message("Input must be a regular file\n"));
        return 0;
    }
    if (status.st_size < 0) {
        fprintf(stderr, file_message("Input file has an invalid size\n"));
        return 0;
    }
    *size = (uint64_t)status.st_size;
    return 1;
}

#endif

int file_read_exact(FILE *stream, void *buffer, size_t length)
{
    unsigned char *position = buffer;
    size_t remaining = length;

    if (stream == NULL || (buffer == NULL && length != 0U)) {
        errno = EINVAL;
        print_system_error("Invalid input read request");
        return 0;
    }
    while (remaining > 0U) {
        size_t count = fread(position, 1U, remaining, stream);

        if (count == 0U) {
            if (ferror(stream) != 0) {
                print_system_error("Cannot read input file");
            } else {
                fprintf(stderr, file_message("Unexpected end of input file\n"));
            }
            return 0;
        }
        position += count;
        remaining -= count;
    }
    return 1;
}

int file_write_all(FILE *stream, const void *buffer, size_t length)
{
    const unsigned char *position = buffer;
    size_t remaining = length;

    if (stream == NULL || (buffer == NULL && length != 0U)) {
        errno = EINVAL;
        print_system_error("Invalid output write request");
        return 0;
    }
    while (remaining > 0U) {
        size_t request = remaining;
        size_t count;

#ifdef NEKOKEM_TEST_FAULT_INJECTION
        if (test_fault_should_fail(FILE_TEST_FAULT_ENOSPC)) {
            errno = ENOSPC;
            print_system_error("Cannot write output file");
            return 0;
        }
        if (test_fault.fault == FILE_TEST_FAULT_SHORT_WRITE &&
            request > 3U) {
            request = 3U;
        }
#endif
        count = fwrite(position, 1U, request, stream);

        if (count == 0U) {
            if (ferror(stream) == 0) {
                errno = EIO;
            }
            print_system_error("Cannot write output file");
            return 0;
        }
        position += count;
        remaining -= count;
    }
    return 1;
}

int file_disable_buffering(FILE *stream)
{
    if (stream == NULL) {
        errno = EINVAL;
        print_system_error("Invalid stream buffering request");
        return 0;
    }
    if (setvbuf(stream, NULL, _IONBF, 0U) != 0) {
        errno = EIO;
        print_system_error("Cannot disable stream buffering");
        return 0;
    }
    return 1;
}

#ifndef _WIN32
int file_read_regular(const char *path,
                      size_t maximum_size,
                      unsigned char **buffer,
                      size_t *length)
{
    struct stat status;
    unsigned char *local_buffer = NULL;
    size_t capacity = 0U;
    size_t position = 0U;
    int descriptor = -1;
    int success = 0;

    if (path == NULL || buffer == NULL || length == NULL ||
        maximum_size == 0U) {
        errno = EINVAL;
        print_system_error("Invalid regular-file read request");
        return 0;
    }
    *buffer = NULL;
    *length = 0U;
    descriptor = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (descriptor < 0) {
        print_system_error("Cannot open regular input file");
        goto cleanup;
    }
    if (fstat(descriptor, &status) != 0) {
        print_system_error("Cannot inspect regular input file");
        goto cleanup;
    }
    if (!S_ISREG(status.st_mode) || status.st_size <= 0) {
        fprintf(stderr, file_message("Input must be a non-empty regular file\n"));
        goto cleanup;
    }
    if ((uintmax_t)status.st_size > (uintmax_t)maximum_size ||
        (uintmax_t)status.st_size > (uintmax_t)SIZE_MAX) {
        fprintf(stderr, file_message("Regular input exceeds the size limit\n"));
        goto cleanup;
    }
    capacity = (size_t)status.st_size;
    local_buffer = OPENSSL_malloc(capacity);
    if (local_buffer == NULL) {
        print_openssl_error("Cannot allocate regular-file buffer");
        goto cleanup;
    }
    while (position < capacity) {
        ssize_t count = read(descriptor, local_buffer + position,
                             capacity - position);

        if (count < 0) {
            if (errno == EINTR) {
                continue;
            }
            print_system_error("Cannot read regular input file");
            goto cleanup;
        }
        if (count == 0) {
            fprintf(stderr, file_message("Regular input changed while being read\n"));
            goto cleanup;
        }
        position += (size_t)count;
    }
    {
        unsigned char extra_byte;
        ssize_t count;

        do {
            count = read(descriptor, &extra_byte, 1U);
        } while (count < 0 && errno == EINTR);
        if (count < 0) {
            print_system_error("Cannot verify regular input length");
            goto cleanup;
        }
        if (count != 0) {
            fprintf(stderr, file_message("Regular input changed while being read\n"));
            goto cleanup;
        }
    }
    if (close(descriptor) != 0) {
        descriptor = -1;
        print_system_error("Cannot close regular input file");
        goto cleanup;
    }
    descriptor = -1;
    *buffer = local_buffer;
    *length = capacity;
    local_buffer = NULL;
    success = 1;

cleanup:
    if (descriptor >= 0) {
        (void)close(descriptor);
    }
    OPENSSL_free(local_buffer);
    return success;
}

int file_read_sensitive(const char *path,
                        size_t maximum_size,
                        unsigned char **buffer,
                        size_t *length)
{
    struct stat status;
    unsigned char *local_buffer = NULL;
    size_t capacity = 0U;
    size_t position = 0U;
    int descriptor = -1;
    int success = 0;

    if (path == NULL || buffer == NULL || length == NULL ||
        maximum_size == 0U) {
        errno = EINVAL;
        print_system_error("Invalid sensitive-file read request");
        return 0;
    }
    *buffer = NULL;
    *length = 0U;

    descriptor = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (descriptor < 0) {
        print_system_error("Cannot open sensitive file");
        goto cleanup;
    }
    if (fstat(descriptor, &status) != 0) {
        print_system_error("Cannot inspect sensitive file");
        goto cleanup;
    }
    if (!S_ISREG(status.st_mode) || status.st_size <= 0) {
        fprintf(stderr, file_message("Sensitive input must be a non-empty regular file\n"));
        goto cleanup;
    }
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (test_fault_should_fail(FILE_TEST_FAULT_FOREIGN_OWNER)) {
        fprintf(stderr, file_message("Sensitive input is not owned by the current user\n"));
        goto cleanup;
    }
#endif
    if (status.st_uid != geteuid()) {
        fprintf(stderr, file_message("Sensitive input is not owned by the current user\n"));
        goto cleanup;
    }
    if ((status.st_mode & (mode_t)0777) != (mode_t)0600) {
        fprintf(stderr, file_message("Sensitive input must have mode 0600\n"));
        goto cleanup;
    }
    if (status.st_nlink != (nlink_t)1) {
        fprintf(stderr, file_message("Sensitive input must have exactly one hard link\n"));
        goto cleanup;
    }
    if (!file_private_acl_is_safe(descriptor)) {
        print_system_error("Cannot inspect sensitive file");
        goto cleanup;
    }
    if ((uintmax_t)status.st_size > (uintmax_t)maximum_size ||
        (uintmax_t)status.st_size > (uintmax_t)SIZE_MAX) {
        fprintf(stderr, file_message("Sensitive input exceeds the size limit\n"));
        goto cleanup;
    }
    capacity = (size_t)status.st_size;
    local_buffer = OPENSSL_malloc(capacity);
    if (local_buffer == NULL) {
        print_openssl_error("Cannot allocate sensitive-file buffer");
        goto cleanup;
    }

    while (position < capacity) {
        ssize_t count = read(descriptor, local_buffer + position,
                             capacity - position);

        if (count < 0) {
            if (errno == EINTR) {
                continue;
            }
            print_system_error("Cannot read sensitive file");
            goto cleanup;
        }
        if (count == 0) {
            fprintf(stderr, file_message("Sensitive file changed while being read\n"));
            goto cleanup;
        }
        position += (size_t)count;
    }

    {
        unsigned char extra_byte;
        ssize_t count;

        do {
            count = read(descriptor, &extra_byte, 1U);
        } while (count < 0 && errno == EINTR);
        secure_mem_clear(&extra_byte, sizeof(extra_byte));
        if (count < 0) {
            print_system_error("Cannot verify sensitive-file length");
            goto cleanup;
        }
        if (count != 0) {
            fprintf(stderr, file_message("Sensitive file changed while being read\n"));
            goto cleanup;
        }
    }

    if (close(descriptor) != 0) {
        descriptor = -1;
        print_system_error("Cannot close sensitive file");
        goto cleanup;
    }
    descriptor = -1;
    *buffer = local_buffer;
    *length = capacity;
    local_buffer = NULL;
    success = 1;

cleanup:
    if (descriptor >= 0) {
        (void)close(descriptor);
    }
    secure_free(local_buffer, capacity);
    return success;
}

int atomic_file_open(AtomicFile *file, const char *final_path, mode_t mode)
{
    static const char suffix[] = ".tmp.XXXXXX";
    size_t path_length;
    size_t allocation_size;
    int descriptor = -1;
    int flags;
    int result;

    if (file == NULL || final_path == NULL || final_path[0] == '\0') {
        errno = EINVAL;
        print_system_error("Invalid output path");
        return 0;
    }
    memset(file, 0, sizeof(*file));
    path_length = strlen(final_path);
    if (path_length > (SIZE_MAX - sizeof(suffix))) {
        fprintf(stderr, file_message("Output path is too long\n"));
        return 0;
    }
    allocation_size = path_length + sizeof(suffix);
    file->temporary_path = malloc(allocation_size);
    if (file->temporary_path == NULL) {
        print_system_error("Cannot allocate temporary path");
        return 0;
    }
    result = snprintf(file->temporary_path, allocation_size, "%s%s",
                      final_path, suffix);
    if (result < 0 || (size_t)result >= allocation_size) {
        fprintf(stderr, file_message("Cannot construct temporary path\n"));
        atomic_file_abort(file);
        return 0;
    }

    descriptor = mkstemp(file->temporary_path);
    if (descriptor < 0) {
        print_system_error("Cannot create temporary output file");
        atomic_file_abort(file);
        return 0;
    }
    flags = fcntl(descriptor, F_GETFD);
    if (flags < 0 || fcntl(descriptor, F_SETFD, flags | FD_CLOEXEC) < 0) {
        print_system_error("Cannot protect temporary output descriptor");
        (void)close(descriptor);
        atomic_file_abort(file);
        return 0;
    }
    if (fchmod(descriptor, mode) != 0 ||
        !file_private_acl_is_safe(descriptor)) {
        print_system_error("Cannot set output file permissions");
        (void)close(descriptor);
        atomic_file_abort(file);
        return 0;
    }
    file->stream = fdopen(descriptor, "wb");
    if (file->stream == NULL) {
        print_system_error("Cannot open temporary output stream");
        (void)close(descriptor);
        atomic_file_abort(file);
        return 0;
    }
    if (!file_disable_buffering(file->stream)) {
        atomic_file_abort(file);
        return 0;
    }
    file->final_path = final_path;
    return 1;
}

int atomic_file_prepare(AtomicFile *file)
{
    int saved_errno = 0;

    if (file == NULL || file->stream == NULL ||
        file->temporary_path == NULL || file->final_path == NULL) {
        errno = EINVAL;
        print_system_error("Invalid atomic output state");
        return 0;
    }
    if (fflush(file->stream) != 0) {
        saved_errno = errno;
    } else if (file_sync_regular_fd(fileno(file->stream)) != 0) {
        saved_errno = errno;
    }
    if (fclose(file->stream) != 0 && saved_errno == 0) {
        saved_errno = errno;
    }
    file->stream = NULL;

    if (saved_errno != 0) {
        errno = saved_errno;
        print_system_error("Cannot flush temporary output file");
        return 0;
    }
    return 1;
}

static char *parent_directory_path(const char *path)
{
    const char *separator;
    char *directory;
    size_t length;

    if (path == NULL || path[0] == '\0') {
        errno = EINVAL;
        return NULL;
    }
    separator = strrchr(path, '/');
    if (separator == NULL) {
        return strdup(".");
    }
    length = separator == path ? 1U : (size_t)(separator - path);
    if (length == SIZE_MAX) {
        errno = EOVERFLOW;
        return NULL;
    }
    directory = malloc(length + 1U);
    if (directory == NULL) {
        return NULL;
    }
    memcpy(directory, path, length);
    directory[length] = '\0';
    return directory;
}

/* Keep this file: removing a lock file lets a waiter and a newcomer lock
 * different inodes for the same directory. Two-file commits use these locks
 * through publication, rollback and backup cleanup. */
static int open_pair_directory_lock(const char *final_path,
                                    struct stat *identity)
{
    static const char name[] = "/.nekokem-pair.lock";
    char *directory = parent_directory_path(final_path);
    char *lock_path = NULL;
    struct stat path_status;
    int descriptor = -1;
    int saved_errno;
    size_t length;

    if (directory == NULL) {
        return -1;
    }
    length = strlen(directory);
    if (length > SIZE_MAX - sizeof(name)) {
        errno = EOVERFLOW;
        goto cleanup;
    }
    lock_path = malloc(length + sizeof(name));
    if (lock_path == NULL) {
        goto cleanup;
    }
    memcpy(lock_path, directory, length);
    memcpy(lock_path + length, name, sizeof(name));
    descriptor = open(lock_path,
                      O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK,
                      0600);
    if (descriptor < 0) {
        goto cleanup;
    }
    if (fstat(descriptor, identity) != 0 ||
        lstat(lock_path, &path_status) != 0) {
        goto reject;
    }
    if (!S_ISREG(identity->st_mode) ||
        identity->st_uid != geteuid() ||
        (identity->st_mode & (mode_t)0777) != (mode_t)0600 ||
        identity->st_nlink != (nlink_t)1 ||
        identity->st_dev != path_status.st_dev ||
        identity->st_ino != path_status.st_ino ||
        !file_private_acl_is_safe(descriptor)) {
        errno = EACCES;
        goto reject;
    }
    goto cleanup;

reject:
    saved_errno = errno;
    (void)close(descriptor);
    descriptor = -1;
    errno = saved_errno;

cleanup:
    saved_errno = errno;
    free(lock_path);
    free(directory);
    errno = saved_errno;
    return descriptor;
}

static int lock_pair_directories(const char *first_path,
                                 const char *second_path,
                                 int descriptors[2])
{
    struct stat identity[2];
    size_t order[2] = {0U, 1U};
    size_t index;

    descriptors[0] = open_pair_directory_lock(first_path, &identity[0]);
    if (descriptors[0] < 0) {
        return 0;
    }
    descriptors[1] = open_pair_directory_lock(second_path, &identity[1]);
    if (descriptors[1] < 0) {
        return 0;
    }
    if (identity[0].st_dev == identity[1].st_dev &&
        identity[0].st_ino == identity[1].st_ino) {
        (void)close(descriptors[1]);
        descriptors[1] = -1;
    } else if (identity[1].st_dev < identity[0].st_dev ||
               (identity[1].st_dev == identity[0].st_dev &&
                identity[1].st_ino < identity[0].st_ino)) {
        order[0] = 1U;
        order[1] = 0U;
    }
    for (index = 0U; index < 2U; ++index) {
        int descriptor = descriptors[order[index]];

        if (descriptor < 0) {
            continue;
        }
        while (flock(descriptor, LOCK_EX) != 0) {
            if (errno != EINTR) {
                return 0;
            }
        }
    }
    return 1;
}

static int atomic_file_targets_are_same(
    const char *first_path,
    const char *second_path,
    int *same_target)
{
    struct stat first_directory_status;
    struct stat second_directory_status;
    char *first_directory = NULL;
    char *second_directory = NULL;
    const char *first_name;
    const char *second_name;
    int result = 0;

    if (first_path == NULL || second_path == NULL || same_target == NULL) {
        errno = EINVAL;
        return 0;
    }
    *same_target = 0;
    first_directory = parent_directory_path(first_path);
    second_directory = parent_directory_path(second_path);
    if (first_directory == NULL || second_directory == NULL ||
        stat(first_directory, &first_directory_status) != 0 ||
        stat(second_directory, &second_directory_status) != 0 ||
        !S_ISDIR(first_directory_status.st_mode) ||
        !S_ISDIR(second_directory_status.st_mode)) {
        goto cleanup;
    }
    first_name = strrchr(first_path, '/');
    second_name = strrchr(second_path, '/');
    first_name = first_name == NULL ? first_path : first_name + 1;
    second_name = second_name == NULL ? second_path : second_name + 1;
    *same_target = first_directory_status.st_dev ==
                       second_directory_status.st_dev &&
                   first_directory_status.st_ino ==
                       second_directory_status.st_ino &&
                   strcmp(first_name, second_name) == 0;
    result = 1;

cleanup:
    free(first_directory);
    free(second_directory);
    return result;
}

static int fsync_parent_directory(const char *path, int inject_fault)
{
    char *directory = NULL;
    int descriptor = -1;
    int result = 0;
    int saved_errno = 0;

    directory = parent_directory_path(path);
    if (directory == NULL) {
        goto cleanup;
    }
    descriptor = open(directory, O_RDONLY | O_CLOEXEC | O_DIRECTORY);
    if (descriptor < 0) {
        goto cleanup;
    }
    if ((inject_fault != 0 ? file_fsync(descriptor) : fsync(descriptor)) != 0) {
        goto cleanup;
    }
    result = 1;

cleanup:
    saved_errno = errno;
    if (descriptor >= 0 && close(descriptor) != 0 && result != 0) {
        saved_errno = errno;
        result = 0;
    }
    free(directory);
    errno = saved_errno;
    return result;
}

static int write_descriptor_all(int descriptor, const unsigned char *buffer,
                                size_t length)
{
    while (length > 0U) {
        ssize_t count = write(descriptor, buffer, length);

        if (count < 0 && errno == EINTR) {
            continue;
        }
        if (count <= 0) {
            if (count == 0) {
                errno = EIO;
            }
            return 0;
        }
        buffer += (size_t)count;
        length -= (size_t)count;
    }
    return 1;
}

/*
 * Keeps the old file as a synced private copy where it cannot be hard-linked.
 * The final name is untouched until rename() replaces it, and a rollback
 * renames the copy back.
 */
static int copy_backup(const char *final_path, const struct stat *expected,
                       const char *backup_path, int *created)
{
    unsigned char buffer[16384];
    struct stat status;
    int source;
    int target = -1;
    int success = 0;
    int saved_errno;

    if (!S_ISREG(expected->st_mode)) {
        errno = ENOTSUP;
        return 0;
    }
    source = open(final_path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (source < 0) {
        return 0;
    }
    if (fstat(source, &status) != 0) {
        goto cleanup;
    }
    if (!S_ISREG(status.st_mode) || status.st_dev != expected->st_dev ||
        status.st_ino != expected->st_ino) {
        errno = EBUSY;
        goto cleanup;
    }
    target = open(backup_path,
                  O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (target < 0) {
        goto cleanup;
    }
    *created = 1;
    for (;;) {
        ssize_t count = read(source, buffer, sizeof(buffer));

        if (count < 0 && errno == EINTR) {
            continue;
        }
        if (count < 0) {
            goto cleanup;
        }
        if (count == 0) {
            break;
        }
        if (!write_descriptor_all(target, buffer, (size_t)count)) {
            goto cleanup;
        }
    }
    if (fchmod(target, status.st_mode & 0777) != 0 ||
        file_sync_regular_fd(target) != 0) {
        goto cleanup;
    }
    success = 1;

cleanup:
    saved_errno = errno;
    secure_mem_clear(buffer, sizeof(buffer));
    if (target >= 0 && close(target) != 0 && success != 0) {
        saved_errno = errno;
        success = 0;
    }
    (void)close(source);
    errno = saved_errno;
    return success;
}

static char *create_backup_link(const char *final_path, int *existed)
{
    static const char suffix[] = ".bak.XXXXXX";
    struct stat status;
    char *backup_path = NULL;
    size_t path_length;
    size_t allocation_size;
    int descriptor = -1;
    int created = 0;
    int result;

    *existed = 0;
    if (lstat(final_path, &status) != 0) {
        if (errno == ENOENT) {
            return NULL;
        }
        *existed = -1;
        return NULL;
    }
    *existed = 1;
    path_length = strlen(final_path);
    if (path_length > SIZE_MAX - sizeof(suffix)) {
        errno = EOVERFLOW;
        return NULL;
    }
    allocation_size = path_length + sizeof(suffix);
    backup_path = malloc(allocation_size);
    if (backup_path == NULL) {
        return NULL;
    }
    result = snprintf(backup_path, allocation_size, "%s%s",
                      final_path, suffix);
    if (result < 0 || (size_t)result >= allocation_size) {
        errno = EOVERFLOW;
        goto cleanup;
    }
    descriptor = mkstemp(backup_path);
    if (descriptor < 0) {
        goto cleanup;
    }
    created = 1;
    if (close(descriptor) != 0) {
        descriptor = -1;
        goto cleanup;
    }
    descriptor = -1;
    if (unlink(backup_path) != 0) {
        goto cleanup;
    }
    created = 0;
    if (file_link(final_path, backup_path) == 0) {
        return backup_path;
    }
    if (errno == EEXIST) {
        /* Another process took the name; it is not ours to remove. */
        goto cleanup;
    }
    {
        int link_errno = errno;

        if (copy_backup(final_path, &status, backup_path, &created)) {
            return backup_path;
        }
        if (errno == ENOTSUP) {
            errno = link_errno;
        }
    }

cleanup:
    {
        int saved_errno = errno;
        if (descriptor >= 0) {
            (void)close(descriptor);
        }
        if (backup_path != NULL && created != 0) {
            (void)unlink(backup_path);
        }
        free(backup_path);
        errno = saved_errno;
    }
    return NULL;
}

static void atomic_file_release(AtomicFile *file)
{
    free(file->temporary_path);
    file->temporary_path = NULL;
    file->final_path = NULL;
}

int file_path_exists(const char *path, int *exists)
{
    struct stat status;

    if (path == NULL || exists == NULL) {
        errno = EINVAL;
        return 0;
    }
    *exists = 0;
    if (lstat(path, &status) == 0) {
        *exists = 1;
        return 1;
    }
    return errno == ENOENT || errno == ENOTDIR;
}

int file_paths_are_same_file(const char *first, const char *second, int *same)
{
    struct stat first_status;
    struct stat second_status;

    if (first == NULL || second == NULL || same == NULL) {
        errno = EINVAL;
        return 0;
    }
    *same = 0;
    if (lstat(first, &first_status) != 0 ||
        lstat(second, &second_status) != 0) {
        return errno == ENOENT || errno == ENOTDIR;
    }
    *same = first_status.st_dev == second_status.st_dev &&
            first_status.st_ino == second_status.st_ino;
    return 1;
}

int file_peek_regular(const char *path, unsigned char *prefix, size_t length)
{
    struct stat status;
    size_t position = 0U;
    int descriptor;
    int success = 0;

    if (path == NULL || prefix == NULL || length == 0U) {
        return 0;
    }
    descriptor = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (descriptor < 0) {
        return 0;
    }
    if (fstat(descriptor, &status) == 0 && S_ISREG(status.st_mode)) {
        while (position < length) {
            ssize_t count = read(descriptor, prefix + position,
                                 length - position);

            if (count < 0 && errno == EINTR) {
                continue;
            }
            if (count <= 0) {
                break;
            }
            position += (size_t)count;
        }
        success = position == length;
    }
    (void)close(descriptor);
    return success;
}

static int atomic_file_commit_pair_mode(AtomicFile *first,
                                        AtomicFile *second,
                                        int replace)
{
    AtomicFile *files[2] = {first, second};
    char *backups[2] = {NULL, NULL};
    int existed[2] = {0, 0};
    int published[2] = {0, 0};
    int published_descriptors[2] = {-1, -1};
    int lock_descriptors[2] = {-1, -1};
    int preserve_backup[2] = {0, 0};
    size_t count = second == NULL ? 1U : 2U;
    size_t index;
    int saved_errno = 0;
    int success = 0;
    int same_target = 0;

    if (first == NULL || first->temporary_path == NULL ||
        first->final_path == NULL ||
        (second != NULL &&
         (first == second || second->temporary_path == NULL ||
          second->final_path == NULL))) {
        errno = EINVAL;
        print_system_error("Invalid atomic output pair");
        return 0;
    }
    if (second != NULL &&
        (!atomic_file_targets_are_same(first->final_path,
                                       second->final_path,
                                       &same_target) || same_target != 0)) {
        saved_errno = errno != 0 ? errno : EINVAL;
        if (same_target != 0) {
            saved_errno = EINVAL;
        }
        goto rollback;
    }
    for (index = 0U; index < count; ++index) {
        if (files[index] == NULL || files[index]->temporary_path == NULL ||
            files[index]->final_path == NULL ||
            (files[index]->stream != NULL &&
             !atomic_file_prepare(files[index]))) {
            saved_errno = errno != 0 ? errno : EINVAL;
            goto rollback;
        }
    }
    if (second != NULL &&
        !lock_pair_directories(first->final_path, second->final_path,
                               lock_descriptors)) {
        saved_errno = errno != 0 ? errno : EIO;
        goto rollback;
    }
    for (index = 0U; index < count; ++index) {
        errno = 0;
        published_descriptors[index] = open(files[index]->temporary_path,
                                            O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        if (published_descriptors[index] < 0) {
            saved_errno = errno;
            goto rollback;
        }
        if (replace == 0) {
            int exists = 0;

            /* Fail before anything is published; link() re-checks below. */
            if (!file_path_exists(files[index]->final_path, &exists) ||
                exists != 0) {
                saved_errno = exists != 0 ? EEXIST
                                          : (errno != 0 ? errno : EIO);
                goto rollback;
            }
            continue;
        }
        backups[index] = create_backup_link(files[index]->final_path,
                                             &existed[index]);
        if (existed[index] < 0 ||
            (existed[index] != 0 && backups[index] == NULL)) {
            saved_errno = errno != 0 ? errno : EIO;
            goto rollback;
        }
    }
    for (index = 0U; index < count; ++index) {
#ifdef __APPLE__
        if (index != 0U) {
            struct stat first_status;
            struct stat next_status;

            /* Let APFS/HFS+ resolve case folding and Unicode normalization. */
            if (lstat(files[0]->final_path, &first_status) != 0) {
                saved_errno = errno;
                goto rollback;
            }
            if (lstat(files[index]->final_path, &next_status) == 0) {
                if (first_status.st_dev == next_status.st_dev &&
                    first_status.st_ino == next_status.st_ino) {
                    saved_errno = EINVAL;
                    goto rollback;
                }
            } else if (errno != ENOENT) {
                saved_errno = errno;
                goto rollback;
            }
        }
#endif
        if ((replace != 0
                 ? file_rename(files[index]->temporary_path,
                               files[index]->final_path)
                 : file_publish_new(files[index]->temporary_path,
                                    files[index]->final_path)) != 0) {
            saved_errno = errno;
            goto rollback;
        }
        published[index] = 1;
    }
    for (index = 0U; index < count; ++index) {
        if (!fsync_parent_directory(files[index]->final_path, 1)) {
            saved_errno = errno != 0 ? errno : EIO;
            goto rollback;
        }
    }
    success = 1;

rollback:
    if (success == 0) {
        size_t reverse = count;

        while (reverse > 0U) {
            --reverse;
            if (published[reverse] != 0) {
                struct stat published_status;
                struct stat current_status;
                int still_ours = published_descriptors[reverse] >= 0 &&
                    fstat(published_descriptors[reverse],
                          &published_status) == 0 &&
                    lstat(files[reverse]->final_path,
                          &current_status) == 0 &&
                    published_status.st_dev == current_status.st_dev &&
                    published_status.st_ino == current_status.st_ino;

                if (existed[reverse] != 0 && backups[reverse] != NULL) {
                    if (still_ours != 0 &&
                        rename(backups[reverse],
                               files[reverse]->final_path) == 0) {
                        free(backups[reverse]);
                        backups[reverse] = NULL;
                    } else {
                        preserve_backup[reverse] = 1;
                        if (still_ours != 0) {
                            print_system_error(
                                "Cannot restore atomic output backup");
                        }
                    }
                } else if (still_ours != 0) {
                    /* Only remove the inode this transaction published. The
                     * locks keep other pair commits out of this interval. */
                    (void)unlink(files[reverse]->final_path);
                }
            }
            if (files[reverse] != NULL &&
                files[reverse]->final_path != NULL) {
                (void)fsync_parent_directory(
                    files[reverse]->final_path, 0);
            }
        }
    }
    for (index = 0U; index < count; ++index) {
        if (published_descriptors[index] >= 0) {
            (void)close(published_descriptors[index]);
        }
        if (backups[index] != NULL) {
            if (preserve_backup[index] == 0) {
                (void)unlink(backups[index]);
            }
            free(backups[index]);
            if (preserve_backup[index] == 0 && files[index] != NULL &&
                files[index]->final_path != NULL) {
                (void)fsync_parent_directory(files[index]->final_path, 0);
            }
        }
        if (success != 0) {
            atomic_file_release(files[index]);
        } else {
            atomic_file_abort(files[index]);
        }
    }
    for (index = 0U; index < 2U; ++index) {
        if (lock_descriptors[index] >= 0) {
            (void)close(lock_descriptors[index]);
        }
    }
    if (success == 0) {
        errno = saved_errno != 0 ? saved_errno : EIO;
        print_system_error("Cannot commit atomic output transaction");
        return 0;
    }
    return 1;
}

int atomic_file_commit_pair(AtomicFile *first, AtomicFile *second)
{
    return atomic_file_commit_pair_mode(first, second, 1);
}

int atomic_file_commit_pair_new(AtomicFile *first, AtomicFile *second)
{
    return atomic_file_commit_pair_mode(first, second, 0);
}

int atomic_file_commit(AtomicFile *file)
{
    return atomic_file_commit_pair(file, NULL);
}

void atomic_file_abort(AtomicFile *file)
{
    if (file == NULL) {
        return;
    }
    if (file->stream != NULL) {
        (void)fclose(file->stream);
        file->stream = NULL;
    }
    if (file->temporary_path != NULL) {
        (void)unlink(file->temporary_path);
        free(file->temporary_path);
        file->temporary_path = NULL;
    }
    file->final_path = NULL;
}

#else
#include "file_windows.inc"
#endif
