#include "file.h"
#include "secure_mem.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <openssl/err.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

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

static void put_u16_be(unsigned char *output, uint16_t value)
{
    output[0] = (unsigned char)(value >> 8);
    output[1] = (unsigned char)value;
}

static void put_u32_be(unsigned char *output, uint32_t value)
{
    output[0] = (unsigned char)(value >> 24);
    output[1] = (unsigned char)(value >> 16);
    output[2] = (unsigned char)(value >> 8);
    output[3] = (unsigned char)value;
}

static void put_u64_be(unsigned char *output, uint64_t value)
{
    size_t index;

    for (index = 0U; index < 8U; ++index) {
        output[index] = (unsigned char)(value >> (56U - (index * 8U)));
    }
}

static uint16_t get_u16_be(const unsigned char *input)
{
    return (uint16_t)(((uint16_t)input[0] << 8) | (uint16_t)input[1]);
}

static uint32_t get_u32_be(const unsigned char *input)
{
    return ((uint32_t)input[0] << 24) |
           ((uint32_t)input[1] << 16) |
           ((uint32_t)input[2] << 8) |
           (uint32_t)input[3];
}

static uint64_t get_u64_be(const unsigned char *input)
{
    uint64_t value = 0U;
    size_t index;

    for (index = 0U; index < 8U; ++index) {
        value = (value << 8) | (uint64_t)input[index];
    }
    return value;
}

void print_openssl_error(const char *context)
{
    unsigned long error_code;
    char error_text[256];

    fprintf(stderr, "%s\n", context);
    while ((error_code = ERR_get_error()) != 0UL) {
        ERR_error_string_n(error_code, error_text, sizeof(error_text));
        fprintf(stderr, "OpenSSL: %s\n", error_text);
    }
}

void print_system_error(const char *context)
{
    fprintf(stderr, "%s: %s\n", context, strerror(errno));
}

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
        fprintf(stderr, "%s exists but is not a directory\n", path);
        return 0;
    }
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (test_fault_should_fail(FILE_TEST_FAULT_FOREIGN_OWNER)) {
        fprintf(stderr, "%s is not owned by the current user\n", path);
        return 0;
    }
#endif
    if (status.st_uid != geteuid()) {
        fprintf(stderr, "%s is not owned by the current user\n", path);
        return 0;
    }
    if ((status.st_mode & (mode_t)0777) != requested_mode) {
        fprintf(stderr, "%s has unsafe permissions (expected %03o)\n",
                path, (unsigned int)requested_mode);
        return 0;
    }
    return 1;
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
        fprintf(stderr, "Input must be a regular file\n");
        return 0;
    }
    if (status.st_size < 0) {
        fprintf(stderr, "Input file has an invalid size\n");
        return 0;
    }
    *size = (uint64_t)status.st_size;
    return 1;
}

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
                fprintf(stderr, "Unexpected end of input file\n");
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

    descriptor = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (descriptor < 0) {
        print_system_error("Cannot open sensitive file");
        goto cleanup;
    }
    if (fstat(descriptor, &status) != 0) {
        print_system_error("Cannot inspect sensitive file");
        goto cleanup;
    }
    if (!S_ISREG(status.st_mode) || status.st_size <= 0) {
        fprintf(stderr, "Sensitive input must be a non-empty regular file\n");
        goto cleanup;
    }
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (test_fault_should_fail(FILE_TEST_FAULT_FOREIGN_OWNER)) {
        fprintf(stderr, "Sensitive input is not owned by the current user\n");
        goto cleanup;
    }
#endif
    if (status.st_uid != geteuid()) {
        fprintf(stderr, "Sensitive input is not owned by the current user\n");
        goto cleanup;
    }
    if ((status.st_mode & (mode_t)0777) != (mode_t)0600) {
        fprintf(stderr, "Sensitive input must have mode 0600\n");
        goto cleanup;
    }
    if (status.st_nlink != (nlink_t)1) {
        fprintf(stderr, "Sensitive input must have exactly one hard link\n");
        goto cleanup;
    }
    if ((uintmax_t)status.st_size > (uintmax_t)maximum_size ||
        (uintmax_t)status.st_size > (uintmax_t)SIZE_MAX) {
        fprintf(stderr, "Sensitive input exceeds the size limit\n");
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
            fprintf(stderr, "Sensitive file changed while being read\n");
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
            fprintf(stderr, "Sensitive file changed while being read\n");
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
        fprintf(stderr, "Output path is too long\n");
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
        fprintf(stderr, "Cannot construct temporary path\n");
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
    if (fchmod(descriptor, mode) != 0) {
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
    } else if (file_fsync(fileno(file->stream)) != 0) {
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

static char *create_backup_link(const char *final_path, int *existed)
{
    static const char suffix[] = ".bak.XXXXXX";
    struct stat status;
    char *backup_path = NULL;
    size_t path_length;
    size_t allocation_size;
    int descriptor = -1;
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
    if (close(descriptor) != 0) {
        descriptor = -1;
        goto cleanup;
    }
    descriptor = -1;
    if (unlink(backup_path) != 0 || link(final_path, backup_path) != 0) {
        goto cleanup;
    }
    return backup_path;

cleanup:
    {
        int saved_errno = errno;
        if (descriptor >= 0) {
            (void)close(descriptor);
        }
        if (backup_path != NULL) {
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

int atomic_file_commit_pair(AtomicFile *first, AtomicFile *second)
{
    AtomicFile *files[2] = {first, second};
    char *backups[2] = {NULL, NULL};
    int existed[2] = {0, 0};
    int published[2] = {0, 0};
    int preserve_backup[2] = {0, 0};
    size_t count = second == NULL ? 1U : 2U;
    size_t index;
    int saved_errno = 0;
    int success = 0;

    if (first == NULL || first->temporary_path == NULL ||
        first->final_path == NULL ||
        (second != NULL &&
         (first == second || second->temporary_path == NULL ||
          second->final_path == NULL ||
          strcmp(first->final_path, second->final_path) == 0))) {
        errno = EINVAL;
        print_system_error("Invalid atomic output pair");
        return 0;
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
    for (index = 0U; index < count; ++index) {
        errno = 0;
        backups[index] = create_backup_link(files[index]->final_path,
                                             &existed[index]);
        if (existed[index] < 0 ||
            (existed[index] != 0 && backups[index] == NULL)) {
            saved_errno = errno != 0 ? errno : EIO;
            goto rollback;
        }
    }
    for (index = 0U; index < count; ++index) {
        if (file_rename(files[index]->temporary_path,
                        files[index]->final_path) != 0) {
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
                if (existed[reverse] != 0 && backups[reverse] != NULL) {
                    if (rename(backups[reverse],
                               files[reverse]->final_path) == 0) {
                        free(backups[reverse]);
                        backups[reverse] = NULL;
                    } else {
                        preserve_backup[reverse] = 1;
                        print_system_error(
                            "Cannot restore atomic output backup");
                    }
                } else {
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
    if (success == 0) {
        errno = saved_errno != 0 ? saved_errno : EIO;
        print_system_error("Cannot commit atomic output transaction");
        return 0;
    }
    return 1;
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

void nkem_header_encode(unsigned char output[NKEM_HEADER_SIZE],
                        uint32_t kem_ciphertext_len,
                        uint64_t ciphertext_len)
{
    memcpy(output, "NKEM", 4U);
    output[4] = NKEM_VERSION;
    output[5] = NKEM_ALGORITHM_ID;
    put_u16_be(output + 6U, (uint16_t)NKEM_HEADER_SIZE);
    put_u32_be(output + 8U, kem_ciphertext_len);
    put_u64_be(output + 12U, ciphertext_len);
    output[20] = NKEM_NONCE_SIZE;
    output[21] = NKEM_TAG_SIZE;
    put_u16_be(output + 22U, 0U);
}

static int nkem_header_decode_internal(
    const unsigned char input[NKEM_HEADER_SIZE],
    NkemHeader *header,
    int report_errors)
{
    if (memcmp(input, "NKEM", 4U) != 0) {
        if (report_errors != 0) {
            fprintf(stderr, "Invalid NKEM magic\n");
        }
        return 0;
    }
    if (input[4] != NKEM_VERSION) {
        if (report_errors != 0) {
            fprintf(stderr, "Unsupported NKEM version: %u\n",
                    input[4]);
        }
        return 0;
    }
    if (input[5] != NKEM_ALGORITHM_ID) {
        if (report_errors != 0) {
            fprintf(stderr, "Unsupported NKEM algorithm id: %u\n",
                    input[5]);
        }
        return 0;
    }
    if (get_u16_be(input + 6U) != NKEM_HEADER_SIZE) {
        if (report_errors != 0) {
            fprintf(stderr, "Invalid NKEM header length\n");
        }
        return 0;
    }
    if (get_u16_be(input + 22U) != 0U) {
        if (report_errors != 0) {
            fprintf(stderr, "Unsupported NKEM header flags\n");
        }
        return 0;
    }

    header->kem_ciphertext_len = get_u32_be(input + 8U);
    header->ciphertext_len = get_u64_be(input + 12U);
    header->nonce_len = input[20];
    header->tag_len = input[21];

    if (header->kem_ciphertext_len == 0U ||
        header->kem_ciphertext_len > NKEM_MAX_KEM_CIPHERTEXT_SIZE) {
        if (report_errors != 0) {
            fprintf(stderr, "Invalid ML-KEM ciphertext length\n");
        }
        return 0;
    }
    if (header->nonce_len != NKEM_NONCE_SIZE ||
        header->tag_len != NKEM_TAG_SIZE) {
        if (report_errors != 0) {
            fprintf(stderr,
                    "Unsupported AES-GCM nonce or tag length\n");
        }
        return 0;
    }
    return 1;
}

int nkem_header_decode(const unsigned char input[NKEM_HEADER_SIZE],
                       NkemHeader *header)
{
    if (input == NULL || header == NULL) {
        fprintf(stderr, "Invalid NKEM v1 header decode request\n");
        return 0;
    }
    return nkem_header_decode_internal(input, header, 1);
}

static int nkem_container_size_is_valid_internal(
    const NkemHeader *header,
    uint64_t actual_size,
    int report_errors)
{
    uint64_t expected_size = NKEM_HEADER_SIZE;
    const uint64_t fields[] = {
        header->kem_ciphertext_len,
        header->nonce_len,
        header->ciphertext_len,
        header->tag_len
    };
    size_t index;

    for (index = 0U; index < (sizeof(fields) / sizeof(fields[0])); ++index) {
        if (UINT64_MAX - expected_size < fields[index]) {
            if (report_errors != 0) {
                fprintf(stderr, "NKEM container length overflows\n");
            }
            return 0;
        }
        expected_size += fields[index];
    }
    if (expected_size != actual_size) {
        if (report_errors != 0) {
            fprintf(stderr,
                    "NKEM container is truncated or has trailing data\n");
        }
        return 0;
    }
    return 1;
}

int nkem_container_size_is_valid(const NkemHeader *header,
                                 uint64_t actual_size)
{
    if (header == NULL) {
        fprintf(stderr, "Invalid NKEM v1 size validation request\n");
        return 0;
    }
    return nkem_container_size_is_valid_internal(
        header, actual_size, 1);
}

void nkem_v2_header_encode(unsigned char output[NKEM_V2_HEADER_SIZE],
                           uint16_t x448_ephemeral_len,
                           uint32_t kem_ciphertext_len,
                           uint64_t ciphertext_len)
{
    memcpy(output, "NKEM", 4U);
    output[4] = NKEM_V2_VERSION;
    output[5] = NKEM_V2_ALGORITHM_ID;
    put_u16_be(output + 6U, (uint16_t)NKEM_V2_HEADER_SIZE);
    put_u16_be(output + 8U, x448_ephemeral_len);
    put_u16_be(output + 10U, 0U);
    put_u32_be(output + 12U, kem_ciphertext_len);
    put_u64_be(output + 16U, ciphertext_len);
    output[24] = NKEM_NONCE_SIZE;
    output[25] = NKEM_TAG_SIZE;
    put_u16_be(output + 26U, 0U);
}

static int nkem_v2_header_decode_internal(
    const unsigned char input[NKEM_V2_HEADER_SIZE],
    NkemV2Header *header,
    int report_errors)
{
    if (memcmp(input, "NKEM", 4U) != 0) {
        if (report_errors != 0) {
            fprintf(stderr, "Invalid NKEM magic\n");
        }
        return 0;
    }
    if (input[4] != NKEM_V2_VERSION) {
        if (report_errors != 0) {
            fprintf(stderr, "Expected NKEM v2, found version %u\n",
                    input[4]);
        }
        return 0;
    }
    if (input[5] != NKEM_V2_ALGORITHM_ID) {
        if (report_errors != 0) {
            fprintf(stderr,
                    "Unsupported NKEM v2 algorithm id: %u\n",
                    input[5]);
        }
        return 0;
    }
    if (get_u16_be(input + 6U) != NKEM_V2_HEADER_SIZE) {
        if (report_errors != 0) {
            fprintf(stderr, "Invalid NKEM v2 header length\n");
        }
        return 0;
    }
    if (get_u16_be(input + 10U) != 0U ||
        get_u16_be(input + 26U) != 0U) {
        if (report_errors != 0) {
            fprintf(stderr, "Unsupported NKEM v2 header flags\n");
        }
        return 0;
    }

    header->x448_ephemeral_len = get_u16_be(input + 8U);
    header->kem_ciphertext_len = get_u32_be(input + 12U);
    header->ciphertext_len = get_u64_be(input + 16U);
    header->nonce_len = input[24];
    header->tag_len = input[25];

    if (header->x448_ephemeral_len !=
        NKEM_X448_EPHEMERAL_PUBLIC_SIZE) {
        if (report_errors != 0) {
            fprintf(stderr,
                    "Invalid X448 ephemeral public-key length\n");
        }
        return 0;
    }
    if (header->kem_ciphertext_len == 0U ||
        header->kem_ciphertext_len > NKEM_MAX_KEM_CIPHERTEXT_SIZE) {
        if (report_errors != 0) {
            fprintf(stderr, "Invalid ML-KEM ciphertext length\n");
        }
        return 0;
    }
    if (header->nonce_len != NKEM_NONCE_SIZE ||
        header->tag_len != NKEM_TAG_SIZE) {
        if (report_errors != 0) {
            fprintf(stderr,
                    "Unsupported AES-GCM nonce or tag length\n");
        }
        return 0;
    }
    return 1;
}

int nkem_v2_header_decode(
    const unsigned char input[NKEM_V2_HEADER_SIZE],
    NkemV2Header *header)
{
    if (input == NULL || header == NULL) {
        fprintf(stderr, "Invalid NKEM v2 header decode request\n");
        return 0;
    }
    return nkem_v2_header_decode_internal(input, header, 1);
}

static int nkem_v2_container_size_is_valid_internal(
    const NkemV2Header *header,
    uint64_t actual_size,
    int report_errors)
{
    uint64_t expected_size = NKEM_V2_HEADER_SIZE;
    const uint64_t fields[] = {
        header->x448_ephemeral_len,
        header->kem_ciphertext_len,
        header->nonce_len,
        header->ciphertext_len,
        header->tag_len
    };
    size_t index;

    for (index = 0U; index < (sizeof(fields) / sizeof(fields[0])); ++index) {
        if (UINT64_MAX - expected_size < fields[index]) {
            if (report_errors != 0) {
                fprintf(stderr,
                        "NKEM v2 container length overflows\n");
            }
            return 0;
        }
        expected_size += fields[index];
    }
    if (expected_size != actual_size) {
        if (report_errors != 0) {
            fprintf(stderr,
                    "NKEM v2 container is truncated or has trailing data\n");
        }
        return 0;
    }
    return 1;
}

int nkem_v2_container_size_is_valid(const NkemV2Header *header,
                                    uint64_t actual_size)
{
    if (header == NULL) {
        fprintf(stderr, "Invalid NKEM v2 size validation request\n");
        return 0;
    }
    return nkem_v2_container_size_is_valid_internal(
        header, actual_size, 1);
}

int nkem_container_parse(const unsigned char *input, size_t input_len)
{
    NkemHeader v1_header;
    NkemV2Header v2_header;

    if (input == NULL || input_len < 5U ||
        (uintmax_t)input_len > UINT64_MAX) {
        return 0;
    }
    if (input[4] == NKEM_VERSION) {
        if (input_len < NKEM_HEADER_SIZE ||
            !nkem_header_decode_internal(input, &v1_header, 0)) {
            return 0;
        }
        return nkem_container_size_is_valid_internal(
            &v1_header, (uint64_t)input_len, 0);
    }
    if (input[4] == NKEM_V2_VERSION) {
        if (input_len < NKEM_V2_HEADER_SIZE ||
            !nkem_v2_header_decode_internal(input, &v2_header, 0)) {
            return 0;
        }
        return nkem_v2_container_size_is_valid_internal(
            &v2_header, (uint64_t)input_len, 0);
    }
    if (input[4] == NKEM_V3_VERSION) {
        return nkem_v3_container_parse(input, input_len);
    }
    return 0;
}
