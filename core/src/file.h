#ifndef NEKOKEM_FILE_H
#define NEKOKEM_FILE_H

#include <stdint.h>
#include <stdio.h>
#include <sys/types.h>

#define NKEM_NONCE_SIZE 12U
#define NKEM_TAG_SIZE 16U
#define NKEM_MAX_KEM_CIPHERTEXT_SIZE 65536U
#define NKEM_X448_EPHEMERAL_PUBLIC_SIZE 56U
#define NKEM_V3_HEADER_SIZE 32U
#define NKEM_V3_VERSION 3U
#define NKEM_V3_ALGORITHM_ID 3U
#define NKEM_V3_SALT_SIZE 32U

typedef struct {
    uint16_t x448_ephemeral_len;
    uint32_t kem_ciphertext_len;
    uint64_t ciphertext_len;
    uint8_t salt_len;
    uint8_t nonce_len;
    uint8_t tag_len;
} NkemV3Header;

typedef struct {
    FILE *stream;
    char *temporary_path;
    const char *final_path;
} AtomicFile;

#ifdef NEKOKEM_TEST_FAULT_INJECTION
typedef enum {
    FILE_TEST_FAULT_NONE = 0,
    FILE_TEST_FAULT_SHORT_WRITE,
    FILE_TEST_FAULT_ENOSPC,
    FILE_TEST_FAULT_FSYNC,
    FILE_TEST_FAULT_RENAME,
    FILE_TEST_FAULT_FOREIGN_OWNER
} FileTestFault;

void file_test_fault_set(FileTestFault fault, unsigned int fail_on_call);
void file_test_fault_reset(void);
#endif

void print_openssl_error(const char *context);
void print_system_error(const char *context);

int ensure_directory(const char *path, mode_t mode);
int file_get_size(FILE *stream, uint64_t *size);
int file_read_exact(FILE *stream, void *buffer, size_t length);
int file_write_all(FILE *stream, const void *buffer, size_t length);
int file_disable_buffering(FILE *stream);
int file_read_sensitive(const char *path,
                        size_t maximum_size,
                        unsigned char **buffer,
                        size_t *length);

int atomic_file_open(AtomicFile *file, const char *final_path, mode_t mode);
int atomic_file_prepare(AtomicFile *file);
int atomic_file_commit(AtomicFile *file);
int atomic_file_commit_pair(AtomicFile *first, AtomicFile *second);
void atomic_file_abort(AtomicFile *file);

void nkem_v3_header_encode(unsigned char output[NKEM_V3_HEADER_SIZE],
                           uint16_t x448_ephemeral_len,
                           uint32_t kem_ciphertext_len,
                           uint64_t ciphertext_len);
int nkem_v3_header_decode(
    const unsigned char input[NKEM_V3_HEADER_SIZE],
    NkemV3Header *header);
int nkem_v3_container_size_is_valid(const NkemV3Header *header,
                                    uint64_t actual_size);
int nkem_v3_container_parse(const unsigned char *input, size_t input_len);


#endif
