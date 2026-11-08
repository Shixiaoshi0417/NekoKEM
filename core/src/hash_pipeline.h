#ifndef NEKOKEM_HASH_PIPELINE_H
#define NEKOKEM_HASH_PIPELINE_H

#include <openssl/evp.h>
#include <stddef.h>

/*
 * Hashes NKEM v5 ciphertext on a second thread while the caller runs
 * AES-GCM and file I/O. Chunks are hashed in the order they are submitted,
 * so the digest is exactly the one a single-threaded loop would compute.
 *
 * The caller borrows a chunk with hash_pipeline_acquire(), fills it with
 * ciphertext, and hands it back with hash_pipeline_submit(); it must not
 * touch the chunk afterwards. The digest belongs to the worker until
 * hash_pipeline_finish() or hash_pipeline_abort() returns.
 */
typedef struct HashPipeline HashPipeline;

/* Chunk capacity: one I/O block plus the spare EVP output room. */
#define HASH_PIPELINE_CHUNK_SIZE (65536U + EVP_MAX_BLOCK_LENGTH)

/* NULL when no worker can be started; the caller then hashes inline. */
HashPipeline *hash_pipeline_start(EVP_MD_CTX *digest);
/* A free chunk, or NULL after the worker failed to hash. */
unsigned char *hash_pipeline_acquire(HashPipeline *pipeline);
/* Queues length bytes of chunk; 0 after the worker failed to hash. */
int hash_pipeline_submit(HashPipeline *pipeline, unsigned char *chunk,
                         size_t length);
/* Hashes everything queued, stops the worker and frees the pipeline. */
int hash_pipeline_finish(HashPipeline *pipeline);
/* Stops without finishing the queue and frees the pipeline; NULL is a no-op. */
void hash_pipeline_abort(HashPipeline *pipeline);

#ifdef NEKOKEM_TEST_FAULT_INJECTION
/* Makes hash_pipeline_start() fail, so callers take the inline path. */
void hash_pipeline_test_fail_start(int fail);
#endif

#endif
