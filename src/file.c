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

    if (mkdir(path, mode) == 0) {
        return 1;
    }
    if (errno != EEXIST) {
        print_system_error("Cannot create directory");
        return 0;
    }
    if (stat(path, &status) != 0) {
        print_system_error("Cannot inspect directory");
        return 0;
    }
    if (!S_ISDIR(status.st_mode)) {
        fprintf(stderr, "%s exists but is not a directory\n", path);
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

    while (remaining > 0U) {
        size_t count = fwrite(position, 1U, remaining, stream);

        if (count == 0U) {
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

    descriptor = open(path, O_RDONLY | O_CLOEXEC);
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
    int descriptor;

    if (file == NULL || final_path == NULL) {
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
    if (snprintf(file->temporary_path, allocation_size, "%s%s",
                 final_path, suffix) < 0) {
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

int atomic_file_commit(AtomicFile *file)
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
    } else if (fsync(fileno(file->stream)) != 0) {
        saved_errno = errno;
    }
    if (fclose(file->stream) != 0 && saved_errno == 0) {
        saved_errno = errno;
    }
    file->stream = NULL;

    if (saved_errno == 0 &&
        rename(file->temporary_path, file->final_path) != 0) {
        saved_errno = errno;
    }
    if (saved_errno != 0) {
        (void)unlink(file->temporary_path);
        free(file->temporary_path);
        file->temporary_path = NULL;
        file->final_path = NULL;
        errno = saved_errno;
        print_system_error("Cannot commit output file");
        return 0;
    }

    free(file->temporary_path);
    file->temporary_path = NULL;
    file->final_path = NULL;
    return 1;
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
    return 0;
}
