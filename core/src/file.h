#ifndef NEKOKEM_FILE_H
#define NEKOKEM_FILE_H

#include <stdint.h>
#include <stdio.h>
#include <sys/types.h>

#define NKEM_NONCE_SIZE 12U
#define NKEM_TAG_SIZE 16U
#define NKEM_X448_EPHEMERAL_PUBLIC_SIZE 56U
#define NKEM_V3_KEM_CIPHERTEXT_SIZE 1568U
#define NKEM_V3_HEADER_SIZE 32U
#define NKEM_V3_VERSION 3U
#define NKEM_V3_ALGORITHM_ID 3U
#define NKEM_V3_SALT_SIZE 32U

/* NKEM v4 multi-recipient container; see docs/NKEM-v4.md. */
#define NKEM_V4_HEADER_SIZE 32U
#define NKEM_V4_VERSION 4U
#define NKEM_V4_ALGORITHM_ID 4U
#define NKEM_V4_SALT_SIZE 32U
#define NKEM_V4_FILE_KEY_SIZE 32U
#define NKEM_V4_MAC_SIZE 64U
#define NKEM_V4_MAX_RECIPIENTS 64U
#define NKEM_V4_ENTRY_SIZE                                        \
    (NKEM_X448_EPHEMERAL_PUBLIC_SIZE + NKEM_V3_KEM_CIPHERTEXT_SIZE + \
     NKEM_V4_FILE_KEY_SIZE + NKEM_TAG_SIZE)

/* SP 800-38D section 5.2.1.1: 2^39 - 256 bits = 2^36 - 32 bytes. */
#define NKEM_GCM_MAX_DATA_SIZE ((UINT64_C(1) << 36) - UINT64_C(32))

typedef struct {
    uint16_t x448_ephemeral_len;
    uint32_t kem_ciphertext_len;
    uint64_t ciphertext_len;
    uint8_t salt_len;
    uint8_t nonce_len;
    uint8_t tag_len;
} NkemV3Header;

typedef struct {
    uint16_t recipient_count;
    uint16_t entry_len;
    uint64_t ciphertext_len;
    uint8_t salt_len;
    uint8_t nonce_len;
    uint8_t tag_len;
    uint8_t mac_len;
} NkemV4Header;

typedef struct {
    FILE *stream;
    char *temporary_path;
    const char *final_path;
#ifdef _WIN32
    void *platform_state;
#endif
} AtomicFile;

#ifdef NEKOKEM_TEST_FAULT_INJECTION
typedef enum {
    FILE_TEST_FAULT_NONE = 0,
    FILE_TEST_FAULT_SHORT_WRITE,
    FILE_TEST_FAULT_ENOSPC,
    FILE_TEST_FAULT_FSYNC,
    FILE_TEST_FAULT_FULLFSYNC,
    FILE_TEST_FAULT_RENAME,
    FILE_TEST_FAULT_NOREPLACE_UNAVAILABLE,
    FILE_TEST_FAULT_FOREIGN_OWNER
} FileTestFault;

void file_test_fault_set(FileTestFault fault, unsigned int fail_on_call);
void file_test_fault_reset(void);
/* POSIX: link() fails like on Android until reset with 0; combines with faults. */
void file_test_set_links_unavailable(int unavailable);
/* POSIX: schedule a competing writer immediately before the atomic rename. */
void file_test_set_before_noreplace_rename(void (*hook)(const char *));
#endif

/* Set once before starting CLI operations. Core defaults to identity translation. */
void file_set_message_translator(const char *(*translator)(const char *));
const char *file_message(const char *message)
#if defined(__GNUC__) || defined(__clang__)
    __attribute__((format_arg(1)))
#endif
    ;

void print_openssl_error(const char *context);
void print_system_error(const char *context);

int ensure_directory(const char *path, mode_t mode);
FILE *file_open_regular(const char *path);
int file_get_size(FILE *stream, uint64_t *size);
int file_read_exact(FILE *stream, void *buffer, size_t length);
int file_write_all(FILE *stream, const void *buffer, size_t length);
int file_disable_buffering(FILE *stream);
#ifndef _WIN32
/* fsync-style result. Darwin also requires F_FULLFSYNC for regular files. */
int file_sync_regular_fd(int descriptor);
/* Darwin private files/directories must not have an ACL that grants access. */
int file_private_acl_is_safe(int descriptor);
#endif
int file_read_regular(const char *path,
                      size_t maximum_size,
                      unsigned char **buffer,
                      size_t *length);
int file_read_sensitive(const char *path,
                        size_t maximum_size,
                        unsigned char **buffer,
                        size_t *length);

/*
 * Whether path names a directory entry; symbolic links and reparse points
 * are not followed. Returns 0 only when the check itself fails.
 */
int file_path_exists(const char *path, int *exists);
/*
 * Whether both paths name the same existing file, compared by identity
 * (device and inode, or volume and file index) rather than by spelling, so
 * "./a", "dir/../a" and case variants on case-insensitive volumes match. A
 * missing path never matches. Links are not followed. Returns 0 only when
 * the check itself fails.
 */
int file_paths_are_same_file(const char *first, const char *second, int *same);
/*
 * Fails with a message when the output path names the same file as one of
 * the keys an operation uses, so a commit can never replace that key.
 */
int file_output_spares_keys(const char *output_path,
                            const char *const *key_paths,
                            size_t key_count);
/* Quietly reads the first length bytes of a regular file; links not followed. */
int file_peek_regular(const char *path, unsigned char *prefix, size_t length);

int atomic_file_open(AtomicFile *file, const char *final_path, mode_t mode);
int atomic_file_prepare(AtomicFile *file);
int atomic_file_commit(AtomicFile *file);
int atomic_file_commit_pair(AtomicFile *first, AtomicFile *second);
/* Like atomic_file_commit_pair, but never replaces an existing file. */
int atomic_file_commit_pair_new(AtomicFile *first, AtomicFile *second);
void atomic_file_abort(AtomicFile *file);

int nkem_gcm_data_size_is_valid(uint64_t data_size);
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

void nkem_v4_header_encode(unsigned char output[NKEM_V4_HEADER_SIZE],
                           uint16_t recipient_count,
                           uint64_t ciphertext_len);
int nkem_v4_header_decode(
    const unsigned char input[NKEM_V4_HEADER_SIZE],
    NkemV4Header *header);
/* Bytes from the header through the payload nonce: the payload AAD. */
uint64_t nkem_v4_metadata_size(uint16_t recipient_count);
int nkem_v4_container_size_is_valid(const NkemV4Header *header,
                                    uint64_t actual_size);
int nkem_v4_container_parse(const unsigned char *input, size_t input_len);


#endif
