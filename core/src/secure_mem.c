#include "secure_mem.h"

#include <openssl/crypto.h>

void secure_mem_clear(void *memory, size_t length)
{
    if (memory != NULL && length > 0U) {
        OPENSSL_cleanse(memory, length);
    }
}

void secure_free(void *memory, size_t length)
{
    if (memory != NULL) {
        OPENSSL_clear_free(memory, length);
    }
}
