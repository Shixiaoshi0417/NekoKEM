#include "hash_pipeline.h"

#include "file.h"
#include "secure_mem.h"

#include <stdlib.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <pthread.h>
#include <signal.h>
#endif

/* Four chunks keep the worker busy while one is read and one written. */
#define HASH_PIPELINE_CHUNKS 4U

typedef struct {
    unsigned char *data;
    size_t length;
} HashChunk;

struct HashPipeline {
    EVP_MD_CTX *digest;
    HashChunk chunks[HASH_PIPELINE_CHUNKS];
    /* Free chunks form a stack; queued chunks a ring in submission order. */
    unsigned char *free_chunks[HASH_PIPELINE_CHUNKS];
    size_t free_count;
    size_t queue[HASH_PIPELINE_CHUNKS];
    size_t queue_head;
    size_t queue_count;
    int finishing;
    int stopping;
    int failed;
#ifdef _WIN32
    SRWLOCK lock;
    CONDITION_VARIABLE changed;
    HANDLE thread;
#else
    pthread_mutex_t lock;
    pthread_cond_t changed;
    pthread_t thread;
#endif
};

#ifdef NEKOKEM_TEST_FAULT_INJECTION
static int test_start_fails;

void hash_pipeline_test_fail_start(int fail)
{
    test_start_fails = fail;
}
#endif

#ifdef _WIN32
static void pipeline_lock(HashPipeline *pipeline) { AcquireSRWLockExclusive(&pipeline->lock); }
static void pipeline_unlock(HashPipeline *pipeline) { ReleaseSRWLockExclusive(&pipeline->lock); }
static void pipeline_wait(HashPipeline *pipeline)
{
    (void)SleepConditionVariableSRW(&pipeline->changed, &pipeline->lock, INFINITE, 0);
}
static void pipeline_wake(HashPipeline *pipeline) { WakeAllConditionVariable(&pipeline->changed); }
#else
static void pipeline_lock(HashPipeline *pipeline) { (void)pthread_mutex_lock(&pipeline->lock); }
static void pipeline_unlock(HashPipeline *pipeline) { (void)pthread_mutex_unlock(&pipeline->lock); }
static void pipeline_wait(HashPipeline *pipeline)
{
    (void)pthread_cond_wait(&pipeline->changed, &pipeline->lock);
}
static void pipeline_wake(HashPipeline *pipeline) { (void)pthread_cond_broadcast(&pipeline->changed); }
#endif

static size_t chunk_index(const HashPipeline *pipeline, const unsigned char *data)
{
    size_t index;

    for (index = 0U; index < HASH_PIPELINE_CHUNKS; ++index) {
        if (pipeline->chunks[index].data == data) {
            return index;
        }
    }
    return HASH_PIPELINE_CHUNKS;
}

static void pipeline_work(HashPipeline *pipeline)
{
    pipeline_lock(pipeline);
    for (;;) {
        HashChunk *chunk;
        int hashed;

        while (pipeline->queue_count == 0U && pipeline->finishing == 0 &&
               pipeline->stopping == 0) {
            pipeline_wait(pipeline);
        }
        if (pipeline->stopping != 0 ||
            (pipeline->queue_count == 0U && pipeline->finishing != 0)) {
            break;
        }
        chunk = &pipeline->chunks[pipeline->queue[pipeline->queue_head]];
        pipeline_unlock(pipeline);
        /* Only this thread touches the digest while the pipeline runs. */
        hashed = pipeline->failed == 0 &&
                 EVP_DigestUpdate(pipeline->digest, chunk->data, chunk->length) == 1;
        pipeline_lock(pipeline);
        if (!hashed) {
            pipeline->failed = 1;
        }
        pipeline->queue_head = (pipeline->queue_head + 1U) % HASH_PIPELINE_CHUNKS;
        --pipeline->queue_count;
        pipeline->free_chunks[pipeline->free_count++] = chunk->data;
        pipeline_wake(pipeline);
    }
    pipeline_unlock(pipeline);
}

#ifdef _WIN32
static DWORD WINAPI pipeline_thread(LPVOID argument)
{
    pipeline_work((HashPipeline *)argument);
    return 0;
}
#else
static void *pipeline_thread(void *argument)
{
    pipeline_work((HashPipeline *)argument);
    return NULL;
}
#endif

static void pipeline_free(HashPipeline *pipeline)
{
    size_t index;

    for (index = 0U; index < HASH_PIPELINE_CHUNKS; ++index) {
        if (pipeline->chunks[index].data != NULL) {
            /* Encryption fills chunks with plaintext before sealing it. */
            secure_mem_clear(pipeline->chunks[index].data, HASH_PIPELINE_CHUNK_SIZE);
            free(pipeline->chunks[index].data);
        }
    }
    free(pipeline);
}

HashPipeline *hash_pipeline_start(EVP_MD_CTX *digest)
{
    HashPipeline *pipeline;
    size_t index;
    int started;

#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (test_start_fails != 0) {
        return NULL;
    }
#endif
    if (digest == NULL) {
        return NULL;
    }
    pipeline = calloc(1U, sizeof(*pipeline));
    if (pipeline == NULL) {
        return NULL;
    }
    pipeline->digest = digest;
    for (index = 0U; index < HASH_PIPELINE_CHUNKS; ++index) {
        pipeline->chunks[index].data = malloc(HASH_PIPELINE_CHUNK_SIZE);
        if (pipeline->chunks[index].data == NULL) {
            pipeline_free(pipeline);
            return NULL;
        }
        pipeline->free_chunks[pipeline->free_count++] = pipeline->chunks[index].data;
    }
#ifdef _WIN32
    InitializeSRWLock(&pipeline->lock);
    InitializeConditionVariable(&pipeline->changed);
    pipeline->thread = CreateThread(NULL, 0, pipeline_thread, pipeline, 0, NULL);
    started = pipeline->thread != NULL;
#else
    if (pthread_mutex_init(&pipeline->lock, NULL) != 0) {
        pipeline_free(pipeline);
        return NULL;
    }
    if (pthread_cond_init(&pipeline->changed, NULL) != 0) {
        (void)pthread_mutex_destroy(&pipeline->lock);
        pipeline_free(pipeline);
        return NULL;
    }
    {
        /* Signals such as the CLI's interrupt handling stay on the caller. */
        sigset_t all;
        sigset_t previous;

        (void)sigfillset(&all);
        started = pthread_sigmask(SIG_BLOCK, &all, &previous) == 0;
        if (started) {
            started = pthread_create(&pipeline->thread, NULL, pipeline_thread, pipeline) == 0;
            (void)pthread_sigmask(SIG_SETMASK, &previous, NULL);
        }
    }
    if (!started) {
        (void)pthread_cond_destroy(&pipeline->changed);
        (void)pthread_mutex_destroy(&pipeline->lock);
    }
#endif
    if (!started) {
        pipeline_free(pipeline);
        return NULL;
    }
    return pipeline;
}

unsigned char *hash_pipeline_acquire(HashPipeline *pipeline)
{
    unsigned char *chunk = NULL;

    pipeline_lock(pipeline);
    while (pipeline->free_count == 0U && pipeline->failed == 0) {
        pipeline_wait(pipeline);
    }
    if (pipeline->failed == 0) {
        chunk = pipeline->free_chunks[--pipeline->free_count];
    }
    pipeline_unlock(pipeline);
    return chunk;
}

int hash_pipeline_submit(HashPipeline *pipeline, unsigned char *chunk,
                         size_t length)
{
    size_t index = chunk_index(pipeline, chunk);
    int accepted;

    pipeline_lock(pipeline);
    accepted = pipeline->failed == 0 && index < HASH_PIPELINE_CHUNKS &&
               length <= HASH_PIPELINE_CHUNK_SIZE &&
               pipeline->queue_count < HASH_PIPELINE_CHUNKS;
    if (accepted) {
        pipeline->chunks[index].length = length;
        pipeline->queue[(pipeline->queue_head + pipeline->queue_count) %
                        HASH_PIPELINE_CHUNKS] = index;
        ++pipeline->queue_count;
        pipeline_wake(pipeline);
    } else if (index < HASH_PIPELINE_CHUNKS) {
        /* A refused chunk goes back to the free stack. */
        pipeline->free_chunks[pipeline->free_count++] = chunk;
    }
    pipeline_unlock(pipeline);
    return accepted;
}

static int pipeline_stop(HashPipeline *pipeline, int finish)
{
    int failed;

    pipeline_lock(pipeline);
    if (finish) {
        pipeline->finishing = 1;
    } else {
        pipeline->stopping = 1;
    }
    pipeline_wake(pipeline);
    pipeline_unlock(pipeline);
#ifdef _WIN32
    (void)WaitForSingleObject(pipeline->thread, INFINITE);
    (void)CloseHandle(pipeline->thread);
#else
    (void)pthread_join(pipeline->thread, NULL);
    (void)pthread_cond_destroy(&pipeline->changed);
    (void)pthread_mutex_destroy(&pipeline->lock);
#endif
    failed = pipeline->failed;
    pipeline_free(pipeline);
    return failed == 0;
}

int hash_pipeline_finish(HashPipeline *pipeline)
{
    if (pipeline == NULL) {
        return 0;
    }
    if (!pipeline_stop(pipeline, 1)) {
        print_openssl_error("Cannot hash NKEM ciphertext");
        return 0;
    }
    return 1;
}

void hash_pipeline_abort(HashPipeline *pipeline)
{
    if (pipeline != NULL) {
        (void)pipeline_stop(pipeline, 0);
    }
}
