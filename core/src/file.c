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
#include <openssl/rand.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#ifndef _WIN32
#include <sys/file.h>
#include <dirent.h>
#include <strings.h>
#include <time.h>
#endif
#ifdef __linux__
#include <linux/fs.h>
#include <sys/syscall.h>
#endif
#ifdef __APPLE__
#include <sys/acl.h>
#endif
#ifndef _WIN32
#include <signal.h>
#include <stdatomic.h>
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
static unsigned int test_restore_fail_on_call;
static unsigned int test_restore_call_count;
static void (*test_before_noreplace_rename)(const char *);

void file_test_set_before_noreplace_rename(void (*hook)(const char *))
{
    test_before_noreplace_rename = hook;
}

void file_test_set_restore_failure(unsigned int fail_on_call)
{
    test_restore_fail_on_call = fail_on_call;
    test_restore_call_count = 0U;
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
    test_restore_fail_on_call = 0U;
    test_restore_call_count = 0U;
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
static int test_restore_should_fail(void)
{
    return (test_restore_fail_on_call != 0U &&
            ++test_restore_call_count == test_restore_fail_on_call) ||
           test_fault_should_fail(FILE_TEST_FAULT_RESTORE);
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
    if (test_before_noreplace_rename != NULL) {
        test_before_noreplace_rename(new_path);
    }
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
 * their own data directories (EACCES). linkat without AT_SYMLINK_FOLLOW
 * links a symbolic link itself; Darwin's link() would link its target.
 */
static int file_link(const char *existing_path, const char *new_path)
{
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (test_links_unavailable != 0) {
        errno = EACCES;
        return -1;
    }
#endif
    return linkat(AT_FDCWD, existing_path, AT_FDCWD, new_path, 0);
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

static int publication_unsupported(int error)
{
    return error == EINVAL || error == ENOTSUP || error == ENOSYS
#if defined(EOPNOTSUPP) && EOPNOTSUPP != ENOTSUP
           || error == EOPNOTSUPP
#endif
        ;
}

/*
 * Both publication paths atomically refuse an existing destination. Where a
 * filesystem has neither hard links nor a no-replace rename (FAT and exFAT on
 * macOS, some network and FUSE mounts), a single output may fall back to the
 * existence check and rename used before create-only outputs; only a writer
 * racing that check can then be replaced. Key pairs never take that path.
 */
static int file_publish_new(const char *temporary_path, const char *final_path,
                            int checked_fallback)
{
    struct stat status;

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
    if (file_rename_new(temporary_path, final_path) == 0) {
        return 0;
    }
    if (checked_fallback == 0 || !publication_unsupported(errno)) {
        return -1;
    }
    if (lstat(final_path, &status) == 0) {
        errno = EEXIST;
        return -1;
    }
    if (errno != ENOENT) {
        return -1;
    }
    return file_rename(temporary_path, final_path);
}

#endif

static const char *(*message_translator)(const char *);

static _Thread_local int output_no_replace;

int file_set_output_no_replace(int enabled)
{
    int previous = output_no_replace;
    output_no_replace = enabled != 0;
    return previous;
}

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

const char *file_display_safe_path(const char *path)
{
    static _Thread_local char buffers[4][4096];
    static _Thread_local unsigned int next;
    char *output = buffers[next++ % 4U];
    size_t used = 0U;
    const unsigned char *input = (const unsigned char *)(path != NULL ? path : "");

    while (*input != 0U && used < sizeof(buffers[0]) - 1U) {
        uint32_t character = *input;
        size_t width = 1U;
        size_t index;
        int valid = 1;

        if (character >= 0xc2U && character <= 0xdfU) {
            width = 2U;
            character &= 0x1fU;
        } else if (character >= 0xe0U && character <= 0xefU) {
            width = 3U;
            character &= 0x0fU;
        } else if (character >= 0xf0U && character <= 0xf4U) {
            width = 4U;
            character &= 0x07U;
        } else if (character >= 0x80U) {
            valid = 0;
        }
        for (index = 1U; index < width; ++index) {
            if (input[index] == 0U || (input[index] & 0xc0U) != 0x80U) {
                valid = 0;
                break;
            }
            character = (character << 6U) | (input[index] & 0x3fU);
        }
        if (valid == 0 || (width == 2U && character < 0x80U) ||
            (width == 3U && character < 0x800U) ||
            (width == 4U && character < 0x10000U) ||
            character > 0x10ffffU ||
            (character >= 0xd800U && character <= 0xdfffU)) {
            output[used++] = '?';
            ++input;
        } else if (character < 0x20U ||
                   (character >= 0x7fU && character <= 0x9fU) ||
                   character == 0xadU || character == 0x061cU ||
                   character == 0x180eU ||
                   (character >= 0x200bU && character <= 0x200fU) ||
                   (character >= 0x2028U && character <= 0x202eU) ||
                   (character >= 0x2060U && character <= 0x206fU) ||
                   character == 0xfeffU) {
            output[used++] = '?';
            input += width;
        } else {
            if (width > sizeof(buffers[0]) - 1U - used) {
                break;
            }
            memcpy(output + used, input, width);
            used += width;
            input += width;
        }
    }
    output[used] = '\0';
    return output;
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
        fprintf(stderr, file_message("%s exists but is not a directory\n"), file_display_safe_path(path));
        return 0;
    }
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (test_fault_should_fail(FILE_TEST_FAULT_FOREIGN_OWNER)) {
        fprintf(stderr, file_message("%s is not owned by the current user\n"), file_display_safe_path(path));
        return 0;
    }
#endif
    if (status.st_uid != geteuid()) {
        fprintf(stderr, file_message("%s is not owned by the current user\n"), file_display_safe_path(path));
        return 0;
    }
    if ((status.st_mode & (mode_t)0777) != requested_mode) {
        fprintf(stderr, file_message("%s has unsafe permissions (expected %03o)\n"),
                file_display_safe_path(path), (unsigned int)requested_mode);
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
    } else if (!file_disable_buffering(stream)) {
        (void)fclose(stream);
        stream = NULL;
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
    descriptor = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
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

static int inspect_key_artifacts(const char *path, const struct stat *key,
                                  int remove_own, const char *ignored_path);

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
    (void)inspect_key_artifacts(path, &status, 0, NULL);
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

/*
 * Temporary outputs not yet committed or aborted, for a program about to die
 * of a signal. A slot holds the AtomicFile's own path string; the lock-free
 * slots let threads share them and a signal handler read them.
 */
#define TEMPORARY_OUTPUT_SLOTS 8U
static _Atomic(char *) temporary_outputs[TEMPORARY_OUTPUT_SLOTS];

static void temporary_output_track(char *path)
{
    size_t index;

    for (index = 0U; index < TEMPORARY_OUTPUT_SLOTS; ++index) {
        char *expected = NULL;

        if (atomic_compare_exchange_strong(&temporary_outputs[index],
                                           &expected, path)) {
            return;
        }
    }
    /* Beyond the slots an output only misses the cleanup on a signal. */
}

static void temporary_output_untrack(const char *path)
{
    size_t index;

    for (index = 0U; index < TEMPORARY_OUTPUT_SLOTS; ++index) {
        char *expected = (char *)path;

        if (atomic_compare_exchange_strong(&temporary_outputs[index],
                                           &expected, NULL)) {
            return;
        }
    }
}

/*
 * Holds back the signals a CLI handles by removing temporary outputs, so a
 * pair is published or rolled back as a whole before the handler runs.
 */
static int file_defer_interrupts(sigset_t *previous)
{
    static const int interrupts[] = {SIGHUP, SIGINT, SIGQUIT, SIGPIPE, SIGTERM};
    sigset_t deferred;
    size_t index;

    (void)sigemptyset(&deferred);
    for (index = 0U; index < sizeof(interrupts) / sizeof(interrupts[0]); ++index) {
        (void)sigaddset(&deferred, interrupts[index]);
    }
    return pthread_sigmask(SIG_BLOCK, &deferred, previous) == 0;
}

void file_remove_temporary_outputs(void)
{
    size_t index;

    for (index = 0U; index < TEMPORARY_OUTPUT_SLOTS; ++index) {
        char *path = atomic_load(&temporary_outputs[index]);

        if (path != NULL) {
            (void)unlink(path);
        }
    }
}

/* Darwin lacks mkostemp; exclusive open still installs CLOEXEC atomically. */
static int file_mkostemp(char *pattern)
{
#if defined(__linux__)
    return mkostemp(pattern, O_CLOEXEC);
#else
    static const char alphabet[] = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    size_t length = strlen(pattern);
    unsigned char random[6];
    unsigned int attempt;

    if (length < 6U || strcmp(pattern + length - 6U, "XXXXXX") != 0) {
        errno = EINVAL;
        return -1;
    }
    for (attempt = 0U; attempt < 128U; ++attempt) {
        int descriptor;
        size_t index;

        if (RAND_bytes(random, (int)sizeof(random)) != 1) {
            errno = EIO;
            return -1;
        }
        for (index = 0U; index < sizeof(random); ++index) {
            pattern[length - 6U + index] = alphabet[random[index] % 62U];
        }
        descriptor = open(pattern, O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
        if (descriptor >= 0 || errno != EEXIST) {
            return descriptor;
        }
    }
    errno = EEXIST;
    return -1;
#endif
}

int atomic_file_open(AtomicFile *file, const char *final_path, mode_t mode)
{
    static const char suffix[] = ".tmp.XXXXXX";
    size_t path_length;
    size_t allocation_size;
    int descriptor = -1;
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

    descriptor = file_mkostemp(file->temporary_path);
    if (descriptor < 0) {
        print_system_error("Cannot create temporary output file");
        free(file->temporary_path);
        file->temporary_path = NULL;
        return 0;
    }
    temporary_output_track(file->temporary_path);
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
    if (descriptor < 0 && lock_path != NULL) {
        fprintf(stderr, file_message("Cannot acquire key directory lock %s: %s\n"),
                file_display_safe_path(lock_path), strerror(saved_errno));
    }
    free(lock_path);
    free(directory);
    errno = saved_errno;
    return descriptor;
}

static int wait_pair_lock(int descriptor, const char *key_path)
{
    const struct timespec delay = {0, 50000000L};
    unsigned int attempt;

    for (attempt = 0U; attempt < 20U; ++attempt) {
        if (flock(descriptor, LOCK_EX | LOCK_NB) == 0) {
            return 1;
        }
        if (errno != EWOULDBLOCK && errno != EAGAIN && errno != EINTR) {
            break;
        }
        if (attempt + 1U < 20U) {
            /* A signal may shorten this delay; it never extends the bound. */
            (void)nanosleep(&delay, NULL);
        }
    }
    {
        char *directory = parent_directory_path(key_path);
        int saved_errno = errno;

        fprintf(stderr, file_message("Cannot acquire key directory lock %s/.nekokem-pair.lock: %s\n"),
                file_display_safe_path(directory), strerror(saved_errno));
        free(directory);
        errno = saved_errno;
    }
    return 0;
}

int file_pair_lock_acquire(const char *key_path)
{
    struct stat identity;
    int descriptor = open_pair_directory_lock(key_path, &identity);

    if (descriptor >= 0 && !wait_pair_lock(descriptor, key_path)) {
        int saved_errno = errno;
        (void)close(descriptor);
        errno = saved_errno;
        return -1;
    }
    return descriptor;
}

void file_pair_lock_release(int descriptor)
{
    if (descriptor >= 0) {
        (void)close(descriptor);
    }
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
        if (!wait_pair_lock(descriptor, order[index] == 0U ? first_path : second_path)) {
            return 0;
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

/* Only the application's reserved transaction names in this directory match. */
static int artifact_name_matches(const char *name, const char *key_name)
{
    size_t length = strlen(key_name);
    const char *suffix;
    size_t index;

    if (strncmp(name, key_name, length) != 0) {
        return 0;
    }
    suffix = name + length;
    do {
        if (strncmp(suffix, ".bak.", 5U) != 0 &&
            strncmp(suffix, ".tmp.", 5U) != 0) {
            return 0;
        }
        suffix += 5U;
        for (index = 0U; index < 6U; ++index) {
            unsigned char character = (unsigned char)*suffix;

            if (!((character >= 'a' && character <= 'z') ||
                  (character >= 'A' && character <= 'Z') ||
                  (character >= '0' && character <= '9'))) {
                return 0;
            }
            ++suffix;
        }
    } while (*suffix != '\0');
    return 1;
}

/* Ask the filesystem to resolve a residual's stem, including case folding
 * and Unicode name normalization. Unknown missing-key aliases stay intact. */
static size_t artifact_key_stem_length(const char *name, const char *key_name,
                                       int directory, const struct stat *key,
                                       int *confirmed)
{
    const char *suffix;

    *confirmed = 1;
    if (artifact_name_matches(name, key_name)) {
        return strlen(key_name);
    }
    for (suffix = name + 1; *suffix != '\0'; ++suffix) {
        char *stem;
        struct stat status;
        int same = 0;
        size_t length;

        if (*suffix != '.' || !artifact_name_matches(suffix, "")) continue;
        length = (size_t)(suffix - name);
        stem = strndup(name, length);
        if (stem == NULL) continue;
        if (key != NULL) {
            same = fstatat(directory, stem, &status, AT_SYMLINK_NOFOLLOW) == 0 &&
                status.st_dev == key->st_dev && status.st_ino == key->st_ino;
        } else if (strcasecmp(stem, key_name) == 0) {
            /* Spelling alone cannot establish ownership without a live key. */
            same = 1;
            *confirmed = 0;
        }
        free(stem);
        if (same != 0) return length;
    }
    return 0U;
}

#define KEY_RESIDUE_STALE_SECONDS 600

static int inspect_key_artifacts(const char *path, const struct stat *key,
                                  int remove_own, const char *ignored_path)
{
    char *directory = parent_directory_path(path);
    const char *key_name = strrchr(path, '/');
    DIR *stream = NULL;
    int descriptor;
    int success = 1;
    struct dirent *entry;

    key_name = key_name == NULL ? path : key_name + 1;
    if (directory == NULL) {
        return 0;
    }
    if (key != NULL && key->st_nlink > (nlink_t)1) {
        fprintf(stderr, file_message("Key %s has multiple hard links; other copies may remain\n"),
                file_display_safe_path(path));
    }
    descriptor = open(directory, O_RDONLY | O_CLOEXEC | O_DIRECTORY);
    if (descriptor < 0 || (stream = fdopendir(descriptor)) == NULL) {
        if (descriptor >= 0) {
            (void)close(descriptor);
        }
        free(directory);
        return 0;
    }
    for (;;) {
        struct stat status;
        char *candidate;
        size_t size;
        int same;
        int ours;
        int confirmed;
        int stale;
        size_t stem_length;
        unsigned char magic[27];

        errno = 0;
        entry = readdir(stream);
        if (entry == NULL) {
            if (errno != 0) {
                success = 0;
            }
            break;
        }
        stem_length = artifact_key_stem_length(entry->d_name, key_name, dirfd(stream), key, &confirmed);
        if (stem_length == 0U) {
            continue;
        }
        size = strlen(directory) + strlen(entry->d_name) + 2U;
        candidate = malloc(size);
        if (candidate == NULL) {
            success = 0;
            break;
        }
        (void)snprintf(candidate, size, "%s/%s", directory, entry->d_name);
        if (ignored_path != NULL &&
            strcmp(entry->d_name, strrchr(ignored_path, '/') != NULL
                ? strrchr(ignored_path, '/') + 1 : ignored_path) == 0) {
            free(candidate);
            continue;
        }
        if (fstatat(dirfd(stream), entry->d_name, &status, AT_SYMLINK_NOFOLLOW) != 0) {
            free(candidate);
            success = 0;
            continue;
        }
        fprintf(stderr, file_message("Key transaction residue at %s; inspect it before cleanup or recovery\n"),
                file_display_safe_path(candidate));
        same = key != NULL && status.st_dev == key->st_dev && status.st_ino == key->st_ino;
        /* A key generation stages its file before taking the pair lock, so a
         * recent temporary file may still be written by another process. */
        stale = time(NULL) - status.st_mtime > KEY_RESIDUE_STALE_SECONDS;
        /* Same-inode residues belong to this key. An older backup must be
         * an owner-only NKPR file; temporary files may be incomplete writes. */
        ours = confirmed != 0 && S_ISREG(status.st_mode) && status.st_uid == geteuid() &&
               (status.st_mode & (mode_t)0777) == (mode_t)0600 &&
               (same != 0 ||
                (status.st_nlink == (nlink_t)1 &&
                 ((stale != 0 && strncmp(entry->d_name + stem_length, ".tmp.", 5U) == 0) ||
                  ((file_peek_regular(candidate, magic, 4U) &&
                    memcmp(magic, "NKPR", 4U) == 0) ||
                   (file_peek_regular(candidate, magic, sizeof(magic)) &&
                    memcmp(magic, "-----BEGIN PRIVATE KEY-----", sizeof(magic)) == 0)))));
        if (remove_own != 0) {
            struct stat current;

            if (ours == 0) {
                success = 0;
            } else if (fstatat(dirfd(stream), entry->d_name, &current,
                               AT_SYMLINK_NOFOLLOW) != 0 ||
                       current.st_dev != status.st_dev || current.st_ino != status.st_ino ||
                       unlinkat(dirfd(stream), entry->d_name, 0) != 0) {
                success = 0;
            }
        }
        free(candidate);
    }
    if (closedir(stream) != 0) {
        success = 0;
    }
    free(directory);
    return success;
}

int file_delete_private_key_under_lock(const char *path)
{
    struct stat status;
    struct stat current;
    int exists = 1;
    int success;

    if (path == NULL || path[0] == '\0') {
        errno = EINVAL;
        return 0;
    }
    if (lstat(path, &status) != 0) {
        if (errno != ENOENT) {
            print_system_error("Cannot inspect private key for deletion");
            return 0;
        }
        exists = 0;
    } else if (!S_ISREG(status.st_mode) || status.st_uid != geteuid()) {
        fprintf(stderr, file_message("Private-key deletion target is not an owned regular file\n"));
        return 0;
    }
    success = inspect_key_artifacts(path, exists != 0 ? &status : NULL, 1, NULL);
    if (exists != 0) {
        if (lstat(path, &current) != 0 || current.st_dev != status.st_dev ||
            current.st_ino != status.st_ino || unlink(path) != 0) {
            print_system_error("Cannot delete private key");
            return 0;
        }
    }
    if (!fsync_parent_directory(path, 1)) {
        print_system_error("Cannot sync the private-key directory after deletion");
        return 0;
    }
    /* The key itself is gone: a retained residue is reported, not a failure,
     * so deleting a key pair can go on to the public key and be retried. */
    if (success == 0) {
        fprintf(stderr, file_message("Some key transaction residues were retained; inspect them before cleanup\n"));
    }
    return 1;
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
static int copy_backup_descriptor(int source, const struct stat *expected,
                       const char *backup_path, int *created)
{
    unsigned char buffer[16384];
    struct stat status;
    int target = -1;
    int success = 0;
    int saved_errno;
    off_t offset = 0;

    if (!S_ISREG(expected->st_mode)) {
        errno = ENOTSUP;
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
        ssize_t count = pread(source, buffer, sizeof(buffer), offset);

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
        offset += count;
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
    errno = saved_errno;
    return success;
}

static int copy_backup(const char *final_path, const struct stat *expected,
                       const char *backup_path, int *created)
{
    int descriptor = open(final_path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    int result;
    int saved_errno;

    if (descriptor < 0) return 0;
    result = copy_backup_descriptor(descriptor, expected, backup_path, created);
    saved_errno = errno;
    if (close(descriptor) != 0 && result != 0) {
        saved_errno = errno;
        result = 0;
    }
    errno = saved_errno;
    return result;
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
    descriptor = file_mkostemp(backup_path);
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
        struct stat linked;

        /* The backup must be the entry examined above, nothing it names. */
        if (lstat(backup_path, &linked) == 0 &&
            linked.st_dev == status.st_dev && linked.st_ino == status.st_ino) {
            return backup_path;
        }
        created = 1;
        errno = EBUSY;
        goto cleanup;
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
    temporary_output_untrack(file->temporary_path);
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

static int path_identifies_descriptor(const char *path, int descriptor)
{
    struct stat opened;
    struct stat named;

    return descriptor >= 0 && fstat(descriptor, &opened) == 0 &&
        lstat(path, &named) == 0 && opened.st_dev == named.st_dev &&
        opened.st_ino == named.st_ino;
}

static int file_restore(const char *source, const char *target, int inject)
{
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (inject != 0 && test_restore_should_fail()) {
        errno = EIO;
        return -1;
    }
#else
    (void)inject;
#endif
    return rename(source, target);
}

static int file_remove_restored(const char *path)
{
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (test_restore_should_fail()) {
        errno = EIO;
        return -1;
    }
#endif
    return unlink(path);
}

static int atomic_file_commit_pair_mode(AtomicFile *first,
                                        AtomicFile *second,
                                        int replace)
{
    AtomicFile *files[2] = {first, second};
    char *backups[2] = {NULL, NULL};
    int existed[2] = {0, 0};
    struct stat original_identity[2];
    int original_present[2] = {0, 0};
    int published[2] = {0, 0};
    int published_descriptors[2] = {-1, -1};
    int lock_descriptors[2] = {-1, -1};
    int preserve_backup[2] = {0, 0};
    int preserve_new_stage[2] = {0, 0};
    size_t count = second == NULL ? 1U : 2U;
    size_t index;
    sigset_t previous_signals;
    int signals_deferred = 0;
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
        {
            struct stat status;
            int present = lstat(files[index]->final_path, &status) == 0;
            original_present[index] = present;
            if (present != 0) original_identity[index] = status;

            if (!present && errno != ENOENT) {
                saved_errno = errno;
                goto rollback;
            }
            (void)inspect_key_artifacts(files[index]->final_path,
                                         present ? &status : NULL, 0,
                                         files[index]->temporary_path);
        }
    }
    signals_deferred = file_defer_interrupts(&previous_signals);
    /* Every backup exists before the first publication: a backup that fails
     * (ENOSPC for a copy where links are unavailable) then publishes nothing,
     * instead of leaving one new key beside an old one. */
    for (index = 0U; index < count && replace != 0; ++index) {
        backups[index] = create_backup_link(files[index]->final_path, &existed[index]);
        if (existed[index] < 0 || (existed[index] != 0 && backups[index] == NULL)) {
            saved_errno = errno != 0 ? errno : EIO;
            goto rollback;
        }
    }
    for (index = 0U; index < count; ++index) {
        if (index != 0U) {
            struct stat first_status;
            struct stat next_status;

            /* Let every POSIX filesystem resolve its own name aliases. */
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
        if ((replace != 0
                 ? file_rename(files[index]->temporary_path,
                               files[index]->final_path)
                 : file_publish_new(files[index]->temporary_path,
                                    files[index]->final_path, count == 1U)) != 0) {
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
        int rollback_failed = 0;
        int ours[2] = {0, 0};
        int restored[2] = {0, 0};
        int new_staged[2] = {0, 0};
        struct stat restored_identity[2];
        size_t reverse = count;

        /* Keep each new inode of a pair available until the whole rollback
         * succeeds: a failed second restore must not strand an old/new key
         * mixture. A single output cannot mix, so it is never copied, however
         * large a decrypted file is. */
        for (index = 0U; index < count; ++index) {
            struct stat status;
            int created = 0;

            if (published[index] == 0) continue;
            ours[index] = path_identifies_descriptor(files[index]->final_path,
                                                       published_descriptors[index]);
            if (count == 1U) continue;
            if (published_descriptors[index] >= 0 &&
                fstat(published_descriptors[index], &status) == 0 &&
                copy_backup_descriptor(published_descriptors[index], &status,
                                         files[index]->temporary_path, &created)) {
                new_staged[index] = 1;
            } else {
                /* Do not begin a rollback without a recoverable copy of
                 * every new file, including an unlinked retained inode. */
                rollback_failed = 1;
            }
        }
        while (reverse > 0U && rollback_failed == 0) {
            --reverse;
            if (published[reverse] == 0) {
                continue;
            }
            if (ours[reverse] == 0) {
                preserve_backup[reverse] = backups[reverse] != NULL;
                continue;
            }
            if (!path_identifies_descriptor(files[reverse]->final_path,
                                               published_descriptors[reverse])) {
                ours[reverse] = 0;
                preserve_backup[reverse] = backups[reverse] != NULL;
                continue;
            }
            if (existed[reverse] != 0 && backups[reverse] != NULL) {
                int backup_exists = 0;
                struct stat restore_identity;
                char *restore = create_backup_link(backups[reverse], &backup_exists);
                int ready = restore != NULL && lstat(restore, &restore_identity) == 0;
                int recovered = ready &&
                    path_identifies_descriptor(files[reverse]->final_path,
                                                published_descriptors[reverse]) &&
                    file_restore(restore, files[reverse]->final_path, 1) == 0;

                if (restore != NULL) {
                    (void)unlink(restore);
                    free(restore);
                }
                if (recovered == 0) {
                    rollback_failed = 1;
                    print_system_error("Cannot restore atomic output backup");
                    break;
                }
                if (lstat(files[reverse]->final_path, &restored_identity[reverse]) != 0 ||
                    restored_identity[reverse].st_dev != restore_identity.st_dev ||
                    restored_identity[reverse].st_ino != restore_identity.st_ino) {
                    rollback_failed = 1;
                    break;
                }
                restored[reverse] = 1;
            } else if (file_remove_restored(files[reverse]->final_path) != 0) {
                rollback_failed = 1;
                break;
            } else {
                restored[reverse] = 1;
            }
        }
        if (rollback_failed != 0) {
            fprintf(stderr, file_message("Key-pair rollback stopped; retaining the new outputs where possible\n"));
            for (index = 0U; index < count; ++index) {
                struct stat expected;
                struct stat current;
                int named = lstat(files[index]->final_path, &current) == 0;
                int can_replace = 0;
                int kept = 0;

                preserve_backup[index] = backups[index] != NULL;
                /* Never overwrite a writer that ignores the directory lock. */
                if (restored[index] != 0) {
                    can_replace = existed[index] != 0
                        ? named && current.st_dev == restored_identity[index].st_dev &&
                            current.st_ino == restored_identity[index].st_ino
                        : !named;
                } else if (published[index] != 0) {
                    can_replace = named && fstat(published_descriptors[index], &expected) == 0 &&
                        current.st_dev == expected.st_dev && current.st_ino == expected.st_ino;
                } else if (original_present[index] != 0) {
                    can_replace = named && current.st_dev == original_identity[index].st_dev &&
                        current.st_ino == original_identity[index].st_ino;
                } else {
                    can_replace = !named;
                }
                if (can_replace != 0 && published[index] == 0 &&
                    original_present[index] != 0 && backups[index] == NULL) {
                    int old_exists = 0;
                    struct stat still_old;

                    /* Completing the new pair must not destroy an old key
                     * whose backup creation triggered the original failure. */
                    backups[index] = create_backup_link(files[index]->final_path, &old_exists);
                    preserve_backup[index] = backups[index] != NULL;
                    can_replace = backups[index] != NULL && old_exists > 0 &&
                        lstat(files[index]->final_path, &still_old) == 0 &&
                        still_old.st_dev == original_identity[index].st_dev &&
                        still_old.st_ino == original_identity[index].st_ino;
                }
                if (can_replace != 0 &&
                    (new_staged[index] != 0 || published[index] == 0)) {
                    kept = file_restore(files[index]->temporary_path,
                                          files[index]->final_path, 0) == 0;
                } else if (can_replace != 0 && published[index] != 0 && restored[index] == 0) {
                    kept = 1;
                }
                if (kept == 0 && (new_staged[index] != 0 || published[index] == 0)) {
                    preserve_new_stage[index] = 1;
                    fprintf(stderr, file_message("New contents for %s are staged at %s\n"),
                            file_display_safe_path(files[index]->final_path),
                            file_display_safe_path(files[index]->temporary_path));
                }
                fprintf(stderr, file_message("New output at %s: %s\n"),
                        file_display_safe_path(files[index]->final_path),
                        file_message(kept != 0 ? "retained" : "recovery required"));
            }
        }
        for (index = 0U; index < count; ++index) {
            if (preserve_backup[index] != 0 && backups[index] != NULL) {
                fprintf(stderr, file_message("The previous contents of %s are kept as %s\n"),
                        file_display_safe_path(files[index]->final_path),
                        file_display_safe_path(backups[index]));
            }
            if (files[index] != NULL && files[index]->final_path != NULL) {
                (void)fsync_parent_directory(files[index]->final_path, 0);
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
        if (success != 0 || preserve_new_stage[index] != 0) {
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
    if (signals_deferred != 0) {
        (void)pthread_sigmask(SIG_SETMASK, &previous_signals, NULL);
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
    return output_no_replace != 0
        ? atomic_file_commit_pair_new(file, NULL)
        : atomic_file_commit_pair(file, NULL);
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
        temporary_output_untrack(file->temporary_path);
        free(file->temporary_path);
        file->temporary_path = NULL;
    }
    file->final_path = NULL;
}

#else
#include "file_windows.inc"
#endif
