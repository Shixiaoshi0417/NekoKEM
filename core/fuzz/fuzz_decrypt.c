/*
 * Full decryption of an untrusted container: NKEM v3/v4 header parsing,
 * X448 and ML-KEM-1024 decapsulation, v4 entry unwrapping and header MAC,
 * streaming AES-GCM authentication and the atomic output transaction. The
 * plaintext-PEM key beside this binary is a throwaway fuzzing key written by
 * fuzz_decrypt_seeds; it protects nothing.
 */
#include "../include/nekokem.h"

#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int key_path(const char *program, char *output, size_t size)
{
    const char *slash = strrchr(program, '/');
    size_t directory = slash == NULL ? 0U : (size_t)(slash - program) + 1U;
    int written;

    if (directory > (size_t)INT_MAX) {
        return 0;
    }
    written = snprintf(output, size, "%.*s%s", (int)directory, program,
                       "decrypt-key.pem");
    return written > 0 && (size_t)written < size;
}

int main(int argc, char **argv)
{
    char key[PATH_MAX];
    char directory[] = "/tmp/nekokem-fuzz-decrypt.XXXXXX";
    char output[sizeof(directory) + 8U];
    int written;

    if (argc != 2 || !key_path(argv[0], key, sizeof(key)) ||
        mkdtemp(directory) == NULL) {
        return 0;
    }
    written = snprintf(output, sizeof(output), "%s/out", directory);
    if (written > 0 && (size_t)written < sizeof(output)) {
        (void)nekokem_decrypt_file(argv[1], output, key, NULL, 0U);
        (void)remove(output);
    }
    (void)rmdir(directory);
    return 0;
}
