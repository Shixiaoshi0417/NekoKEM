#ifndef NEKOKEM_PAYLOAD_HASH_H
#define NEKOKEM_PAYLOAD_HASH_H

#include <stddef.h>
#include <stdint.h>

/*
 * The NKEM v5 payload digest (docs/NKEM-v5.md#payload-digest): the payload
 * ciphertext is split into 1 MiB leaves, each hashed with SHA-512 on its own,
 * and a root SHA-512 over the length, the leaf digests and the payload tag
 * gives the digest that the sender signs.
 *
 * Leaves are hashed on worker threads while the caller runs AES-GCM and file
 * I/O; the digest does not depend on how many workers ran or on how the
 * caller split its updates. Without workers every leaf is hashed inline.
 */
typedef struct PayloadHash PayloadHash;

#define PAYLOAD_HASH_LEAF_SIZE 1048576U
#define PAYLOAD_HASH_DIGEST_SIZE 64U
#define PAYLOAD_HASH_TAG_SIZE 16U
/* Upper bound on worker threads; each in-flight leaf holds 1 MiB. */
#define PAYLOAD_HASH_MAX_WORKERS 4U

/* A digest of exactly ciphertext_len bytes, with workers chosen by CPU count. */
PayloadHash *payload_hash_new(uint64_t ciphertext_len);
/* As above with at most workers threads; 0 hashes every leaf inline. */
PayloadHash *payload_hash_new_with_workers(uint64_t ciphertext_len,
                                           size_t workers);
/* The number of worker threads that started; 0 when hashing inline. */
size_t payload_hash_worker_count(const PayloadHash *hash);
/* Absorbs the next length bytes of ciphertext in order. */
int payload_hash_update(PayloadHash *hash, const unsigned char *data,
                        size_t length);
/* Ends with the payload tag; fails unless exactly ciphertext_len bytes came. */
int payload_hash_final(PayloadHash *hash,
                       const unsigned char tag[PAYLOAD_HASH_TAG_SIZE],
                       unsigned char digest[PAYLOAD_HASH_DIGEST_SIZE]);
/* Stops any workers and frees the hash; NULL is a no-op. */
void payload_hash_free(PayloadHash *hash);

#ifdef NEKOKEM_TEST_FAULT_INJECTION
/* Makes hashing of the leaf with this index fail; UINT64_MAX disables it. */
void payload_hash_test_fail_leaf(uint64_t index);
/* Makes worker threads fail to start, so hashing falls back inline. */
void payload_hash_test_fail_threads(int fail);
#endif

#endif
