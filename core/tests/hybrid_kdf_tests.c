#include "hybrid.h"
#include "secure_mem.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(void)
{
    static const unsigned char expected[AES256_KEY_SIZE] = {
        0x0d, 0x5a, 0x25, 0xc1, 0x1a, 0x42, 0x0b, 0x5c,
        0xb6, 0x76, 0x79, 0xe5, 0x90, 0x35, 0x6e, 0x0d,
        0x83, 0x85, 0x23, 0xce, 0x31, 0xfe, 0x55, 0x5f,
        0x5d, 0x02, 0x74, 0x1b, 0xe1, 0x23, 0x46, 0x94
    };
    unsigned char x448_secret[X448_SHARED_SECRET_SIZE];
    unsigned char mlkem_secret[KEM_SHARED_SECRET_SIZE];
    unsigned char salt[NKEM_V3_SALT_SIZE];
    unsigned char key[AES256_KEY_SIZE] = {0};
    size_t index;
    int success = 0;

    for (index = 0U; index < sizeof(x448_secret); ++index) {
        x448_secret[index] = (unsigned char)index;
    }
    for (index = 0U; index < sizeof(mlkem_secret); ++index) {
        mlkem_secret[index] = (unsigned char)(0x80U + index);
    }
    for (index = 0U; index < sizeof(salt); ++index) {
        salt[index] = (unsigned char)index;
    }
    if (!hybrid_derive_aes256_key(
            x448_secret, sizeof(x448_secret),
            mlkem_secret, sizeof(mlkem_secret),
            salt, sizeof(salt), key) ||
        memcmp(key, expected, sizeof(expected)) != 0) {
        fprintf(stderr, "NKEM v3 Hybrid KDF regression failed\n");
        goto cleanup;
    }
    puts("NKEM v3 Hybrid KDF regression test passed");
    success = 1;

cleanup:
    secure_mem_clear(x448_secret, sizeof(x448_secret));
    secure_mem_clear(mlkem_secret, sizeof(mlkem_secret));
    secure_mem_clear(salt, sizeof(salt));
    secure_mem_clear(key, sizeof(key));
    return success != 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
