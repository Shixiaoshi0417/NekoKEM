#ifndef NEKOKEM_FUZZ_COMMON_H
#define NEKOKEM_FUZZ_COMMON_H

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>

#define NEKOKEM_FUZZ_MAX_INPUT_SIZE (2U * 1024U * 1024U)

static int fuzz_read_input(const char *path,
                           unsigned char **buffer,
                           size_t *length)
{
    struct stat status;
    unsigned char *data = NULL;
    size_t capacity;
    size_t position = 0U;
    int descriptor = -1;
    int success = 0;

    if (path == NULL || buffer == NULL || length == NULL) {
        return 0;
    }
    *buffer = NULL;
    *length = 0U;
    descriptor = open(path, O_RDONLY | O_CLOEXEC);
    if (descriptor < 0 || fstat(descriptor, &status) != 0 ||
        !S_ISREG(status.st_mode) || status.st_size < 0 ||
        (uintmax_t)status.st_size >
            (uintmax_t)NEKOKEM_FUZZ_MAX_INPUT_SIZE ||
        (uintmax_t)status.st_size > (uintmax_t)SIZE_MAX) {
        goto cleanup;
    }
    capacity = (size_t)status.st_size;
    data = malloc(capacity == 0U ? 1U : capacity);
    if (data == NULL) {
        goto cleanup;
    }
    while (position < capacity) {
        ssize_t count = read(descriptor, data + position,
                             capacity - position);

        if (count < 0) {
            if (errno == EINTR) {
                continue;
            }
            goto cleanup;
        }
        if (count == 0) {
            goto cleanup;
        }
        position += (size_t)count;
    }
    *buffer = data;
    *length = capacity;
    data = NULL;
    success = 1;

cleanup:
    if (descriptor >= 0) {
        (void)close(descriptor);
    }
    free(data);
    return success;
}

#endif
