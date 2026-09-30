#include "nekokem.h"
#include "file.h"
#include "hybrid.h"
#include "private_key.h"
#include "secure_mem.h"

#include <dirent.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

static const unsigned char password[] = "input-boundary-test-password";

static int probe(unsigned int operation, const char *fifo,
                 const char *plaintext, const char *container,
                 const char *public_key, const char *output)
{
    unsigned char *data = NULL;
    size_t length = 0U;
    HybridKeys keys = {0};
    int result = 1;

    switch (operation) {
    case 0U:
        result = file_read_sensitive(fifo, 1024U, &data, &length);
        break;
    case 1U:
        result = protected_private_key_read(
            fifo, password, sizeof(password) - 1U, &data, &length);
        break;
    case 2U:
        result = hybrid_load_private_keys(fifo, &keys);
        break;
    case 3U:
        result = nekokem_encrypt_file(fifo, output, public_key);
        break;
    case 4U:
        result = nekokem_decrypt_file(
            fifo, output, plaintext, password, sizeof(password) - 1U);
        break;
    case 5U:
    case 6U:
        result = nekokem_decrypt_file(
            container, output, fifo, password, sizeof(password) - 1U);
        break;
    case 7U:
        result = nekokem_encrypt_file(plaintext, output, fifo);
        break;
    default:
        return 1;
    }
    secure_free(data, length);
    hybrid_keys_cleanup(&keys);
    return result == 0 ? 0 : 1;
}

static int bounded_probe(unsigned int operation, const char *fifo,
                         const char *plaintext, const char *container,
                         const char *public_key, const char *output)
{
    pid_t child = fork();
    pid_t waited;
    int status = 0;

    if (child < 0) {
        perror("fork input-boundary probe");
        return 0;
    }
    if (child == 0) {
        /* A FIFO has no writer. The alarm makes a blocking open a test failure. */
        (void)signal(SIGALRM, SIG_DFL);
        (void)alarm(2U);
        _exit(probe(operation, fifo, plaintext, container, public_key, output));
    }
    do {
        waited = waitpid(child, &status, 0);
    } while (waited < 0 && errno == EINTR);
    if (waited != child || !WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        fprintf(stderr, "FIFO operation %u failed (wait status %d)\n",
                operation, status);
        return 0;
    }
    return 1;
}

static int sentinel_is_intact(const char *path)
{
    static const unsigned char sentinel[] = "existing-output";
    unsigned char bytes[sizeof(sentinel)];
    FILE *stream = fopen(path, "rb");
    int success;

    if (stream == NULL) {
        return 0;
    }
    success = fread(bytes, 1U, sizeof(bytes), stream) == sizeof(bytes) &&
              memcmp(bytes, sentinel, sizeof(bytes)) == 0 &&
              fgetc(stream) == EOF && ferror(stream) == 0;
    return fclose(stream) == 0 && success;
}

int main(void)
{
    static const unsigned char sentinel[] = "existing-output";
    char directory[] = "/tmp/nekokem-input-boundaries.XXXXXX";
    char paths[7][256] = {{0}};
    const char *names[] = {"plain", "valid.nkem", "public.key", "private.enc",
                           "fifo.key", "fifo.enc", "output"};
    FILE *stream = NULL;
    DIR *entries = NULL;
    struct dirent *entry;
    size_t index;
    unsigned int operation;
    int success = 0;

    if (mkdtemp(directory) == NULL) {
        goto cleanup;
    }
    for (index = 0U; index < 7U; ++index) {
        int count = snprintf(paths[index], sizeof(paths[index]), "%s/%s",
                             directory, names[index]);
        if (count < 0 || (size_t)count >= sizeof(paths[index])) {
            goto cleanup;
        }
    }
    stream = fopen(paths[0], "wb");
    if (stream == NULL || !file_write_all(stream, "test", 4U)) {
        goto cleanup;
    }
    if (fclose(stream) != 0) {
        stream = NULL;
        goto cleanup;
    }
    stream = fopen(paths[6], "wb");
    if (stream == NULL || !file_write_all(stream, sentinel, sizeof(sentinel))) {
        goto cleanup;
    }
    if (fclose(stream) != 0) {
        stream = NULL;
        goto cleanup;
    }
    stream = NULL;
    if (mkfifo(paths[4], 0600) != 0 || mkfifo(paths[5], 0600) != 0 ||
        !nekokem_generate_keypair(paths[2], paths[3], password,
                                  sizeof(password) - 1U) ||
        !nekokem_encrypt_file(paths[0], paths[1], paths[2])) {
        goto cleanup;
    }
    success = 1;
    for (operation = 0U; operation < 8U; ++operation) {
        const char *fifo = operation == 1U || operation == 6U
                               ? paths[5] : paths[4];
        if (!bounded_probe(operation, fifo, paths[0], paths[1],
                            paths[2], paths[6]) || !sentinel_is_intact(paths[6])) {
            success = 0;
        }
    }
    /* The regular-file open change must preserve empty NKEM v3 round trips. */
    if (success != 0) {
        uint64_t size = UINT64_MAX;

        stream = fopen(paths[0], "wb");
        if (stream == NULL) {
            success = 0;
            goto cleanup;
        }
        if (fclose(stream) != 0) {
            stream = NULL;
            success = 0;
            goto cleanup;
        }
        stream = NULL;
        if (!nekokem_encrypt_file(paths[0], paths[1], paths[2]) ||
            !nekokem_decrypt_file(paths[1], paths[6], paths[3],
                                  password, sizeof(password) - 1U)) {
            success = 0;
        } else {
            stream = fopen(paths[6], "rb");
            if (stream == NULL || !file_get_size(stream, &size) || size != 0U) {
                success = 0;
            }
            if (stream != NULL && fclose(stream) != 0) {
                success = 0;
            }
            stream = NULL;
        }
    }
    entries = opendir(directory);
    if (entries == NULL) {
        success = 0;
    } else {
        while ((entry = readdir(entries)) != NULL) {
            if (strstr(entry->d_name, ".tmp.") != NULL ||
                strstr(entry->d_name, ".bak.") != NULL) {
                success = 0;
            }
        }
        if (closedir(entries) != 0) {
            success = 0;
        }
    }

cleanup:
    if (stream != NULL) {
        (void)fclose(stream);
    }
    for (index = 0U; index < 7U; ++index) {
        (void)unlink(paths[index]);
    }
    (void)rmdir(directory);
    if (success != 0) {
        puts("All 8 FIFO input-boundary probes and empty v3 round trip passed");
    }
    return success != 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
