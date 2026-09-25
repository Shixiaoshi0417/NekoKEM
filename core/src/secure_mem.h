#ifndef NEKOKEM_SECURE_MEM_H
#define NEKOKEM_SECURE_MEM_H

#include <stddef.h>

/* Clear stack or caller-owned sensitive memory without dead-store removal. */
void secure_mem_clear(void *memory, size_t length);

/* Clear and free memory allocated by OPENSSL_malloc(). */
void secure_free(void *memory, size_t length);

#endif
