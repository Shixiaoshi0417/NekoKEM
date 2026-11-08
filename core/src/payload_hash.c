#include "payload_hash.h"

#include "file.h"

#include <openssl/evp.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <pthread.h>
#include <signal.h>
#include <unistd.h>
#endif

/* Labels of docs/NKEM-v5.md#payload-digest, without NUL. */
static const char leaf_label[] = "NekoKEM v5 payload leaf";
static const char root_label[] = "NekoKEM v5 payload root";

/* One leaf being filled, one waiting, and one per worker being hashed. */
#define PAYLOAD_HASH_SLOTS (PAYLOAD_HASH_MAX_WORKERS + 2U)

enum {
    SLOT_FREE,
    SLOT_FILLING,
    SLOT_QUEUED,
    SLOT_HASHING,
    SLOT_DONE
};

typedef struct {
    unsigned char *data;
    size_t length;
    uint64_t index;
    int state;
    unsigned char digest[PAYLOAD_HASH_DIGEST_SIZE];
} LeafSlot;

struct PayloadHash {
    /* The root digest; only the caller's thread touches it. */
    EVP_MD_CTX *root;
    uint64_t expected;
    uint64_t absorbed;
    /* Index the next dispatched leaf gets, and the next one the root takes. */
    uint64_t next_leaf;
    uint64_t next_root;
    LeafSlot slots[PAYLOAD_HASH_SLOTS];
    size_t slot_limit;
    LeafSlot *filling;
    size_t worker_count;
    /* Set before the first worker starts; workers read only this one. */
    int threaded;
    int stopping;
    int failed;
    int finished;
#ifdef _WIN32
    SRWLOCK lock;
    CONDITION_VARIABLE changed;
    HANDLE threads[PAYLOAD_HASH_MAX_WORKERS];
#else
    pthread_mutex_t lock;
    pthread_cond_t changed;
    pthread_t threads[PAYLOAD_HASH_MAX_WORKERS];
#endif
};

#ifdef NEKOKEM_TEST_FAULT_INJECTION
static uint64_t test_fail_leaf = UINT64_MAX;
static int test_fail_threads;

void payload_hash_test_fail_leaf(uint64_t index)
{
    test_fail_leaf = index;
}

void payload_hash_test_fail_threads(int fail)
{
    test_fail_threads = fail;
}
#endif

/* Locking is needed only while workers run; inline hashing never waits. */
static void hash_lock(PayloadHash *hash)
{
    if (hash->threaded == 0) {
        return;
    }
#ifdef _WIN32
    AcquireSRWLockExclusive(&hash->lock);
#else
    (void)pthread_mutex_lock(&hash->lock);
#endif
}

static void hash_unlock(PayloadHash *hash)
{
    if (hash->threaded == 0) {
        return;
    }
#ifdef _WIN32
    ReleaseSRWLockExclusive(&hash->lock);
#else
    (void)pthread_mutex_unlock(&hash->lock);
#endif
}

static void hash_wait(PayloadHash *hash)
{
#ifdef _WIN32
    (void)SleepConditionVariableSRW(&hash->changed, &hash->lock, INFINITE, 0);
#else
    (void)pthread_cond_wait(&hash->changed, &hash->lock);
#endif
}

static void hash_wake(PayloadHash *hash)
{
    if (hash->threaded == 0) {
        return;
    }
#ifdef _WIN32
    WakeAllConditionVariable(&hash->changed);
#else
    (void)pthread_cond_broadcast(&hash->changed);
#endif
}

static void store_u64_be(unsigned char output[8], uint64_t value)
{
    size_t index;

    for (index = 0U; index < 8U; ++index) {
        output[7U - index] = (unsigned char)(value >> (8U * index));
    }
}

/* leaf digest = SHA-512(leaf label || u64be(index) || leaf bytes) */
static int hash_leaf(uint64_t index, const unsigned char *data, size_t length,
                     unsigned char digest[PAYLOAD_HASH_DIGEST_SIZE])
{
    EVP_MD_CTX *context;
    unsigned char encoded_index[8];
    unsigned int digest_len = 0U;
    int success;

#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (index == test_fail_leaf) {
        return 0;
    }
#endif
    context = EVP_MD_CTX_new();
    store_u64_be(encoded_index, index);
    success = context != NULL &&
              EVP_DigestInit_ex(context, EVP_sha512(), NULL) == 1 &&
              EVP_DigestUpdate(context, leaf_label, sizeof(leaf_label) - 1U) == 1 &&
              EVP_DigestUpdate(context, encoded_index, sizeof(encoded_index)) == 1 &&
              EVP_DigestUpdate(context, data, length) == 1 &&
              EVP_DigestFinal_ex(context, digest, &digest_len) == 1 &&
              digest_len == PAYLOAD_HASH_DIGEST_SIZE;
    EVP_MD_CTX_free(context);
    return success;
}

static LeafSlot *next_queued(PayloadHash *hash)
{
    LeafSlot *found = NULL;
    size_t index;

    for (index = 0U; index < PAYLOAD_HASH_SLOTS; ++index) {
        LeafSlot *slot = &hash->slots[index];

        if (slot->state == SLOT_QUEUED &&
            (found == NULL || slot->index < found->index)) {
            found = slot;
        }
    }
    return found;
}

static void hash_work(PayloadHash *hash)
{
    hash_lock(hash);
    for (;;) {
        LeafSlot *slot = NULL;
        int hashed;

        while (hash->stopping == 0 && (slot = next_queued(hash)) == NULL) {
            hash_wait(hash);
        }
        if (hash->stopping != 0) {
            break;
        }
        slot->state = SLOT_HASHING;
        hash_unlock(hash);
        /* The caller leaves a slot alone while it is queued or hashing. */
        hashed = hash_leaf(slot->index, slot->data, slot->length, slot->digest);
        hash_lock(hash);
        slot->state = SLOT_DONE;
        if (!hashed) {
            hash->failed = 1;
        }
        hash_wake(hash);
    }
    hash_unlock(hash);
}

#ifdef _WIN32
static DWORD WINAPI hash_thread(LPVOID argument)
{
    hash_work((PayloadHash *)argument);
    return 0;
}
#else
static void *hash_thread(void *argument)
{
    hash_work((PayloadHash *)argument);
    return NULL;
}
#endif

static size_t online_processors(void)
{
#ifdef _WIN32
    SYSTEM_INFO info;

    GetSystemInfo(&info);
    return info.dwNumberOfProcessors > 0U ? (size_t)info.dwNumberOfProcessors : 1U;
#elif defined(_SC_NPROCESSORS_ONLN)
    long count = sysconf(_SC_NPROCESSORS_ONLN);

    return count > 0 ? (size_t)count : 1U;
#else
    return 1U;
#endif
}

static void start_workers(PayloadHash *hash, size_t wanted)
{
    size_t started = 0U;
    size_t index;

#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (test_fail_threads != 0) {
        wanted = 0U;
    }
#endif
    if (wanted == 0U) {
        return;
    }
#ifdef _WIN32
    InitializeSRWLock(&hash->lock);
    InitializeConditionVariable(&hash->changed);
    hash->threaded = 1;
    for (index = 0U; index < wanted; ++index) {
        hash->threads[started] = CreateThread(NULL, 0, hash_thread, hash, 0, NULL);
        if (hash->threads[started] != NULL) {
            ++started;
        }
    }
#else
    if (pthread_mutex_init(&hash->lock, NULL) != 0) {
        return;
    }
    if (pthread_cond_init(&hash->changed, NULL) != 0) {
        (void)pthread_mutex_destroy(&hash->lock);
        return;
    }
    hash->threaded = 1;
    {
        /* Signals such as the CLI's interrupt handling stay on the caller. */
        sigset_t all;
        sigset_t previous;

        (void)sigfillset(&all);
        if (pthread_sigmask(SIG_BLOCK, &all, &previous) == 0) {
            for (index = 0U; index < wanted; ++index) {
                if (pthread_create(&hash->threads[started], NULL, hash_thread,
                                   hash) == 0) {
                    ++started;
                }
            }
            (void)pthread_sigmask(SIG_SETMASK, &previous, NULL);
        }
    }
    if (started == 0U) {
        (void)pthread_cond_destroy(&hash->changed);
        (void)pthread_mutex_destroy(&hash->lock);
    }
#endif
    /* Only the caller's thread reads worker_count. */
    hash->worker_count = started;
    if (started == 0U) {
        hash->threaded = 0;
    }
}

static void stop_workers(PayloadHash *hash)
{
    size_t index;

    if (hash->threaded == 0) {
        return;
    }
    hash_lock(hash);
    hash->stopping = 1;
    hash_wake(hash);
    hash_unlock(hash);
    for (index = 0U; index < hash->worker_count; ++index) {
#ifdef _WIN32
        (void)WaitForSingleObject(hash->threads[index], INFINITE);
        (void)CloseHandle(hash->threads[index]);
#else
        (void)pthread_join(hash->threads[index], NULL);
#endif
    }
#ifndef _WIN32
    (void)pthread_cond_destroy(&hash->changed);
    (void)pthread_mutex_destroy(&hash->lock);
#endif
    hash->worker_count = 0U;
    hash->threaded = 0;
}

/* Feeds finished leaves to the root in index order; call with the lock held. */
static void drain(PayloadHash *hash)
{
    for (;;) {
        LeafSlot *ready = NULL;
        size_t index;

        for (index = 0U; index < PAYLOAD_HASH_SLOTS; ++index) {
            if (hash->slots[index].state == SLOT_DONE &&
                hash->slots[index].index == hash->next_root) {
                ready = &hash->slots[index];
                break;
            }
        }
        if (ready == NULL) {
            return;
        }
        if (hash->failed == 0 &&
            EVP_DigestUpdate(hash->root, ready->digest, sizeof(ready->digest)) != 1) {
            hash->failed = 1;
        }
        ready->state = SLOT_FREE;
        ++hash->next_root;
    }
}

static LeafSlot *acquire(PayloadHash *hash)
{
    LeafSlot *slot = NULL;

    hash_lock(hash);
    for (;;) {
        size_t index;

        drain(hash);
        if (hash->failed != 0) {
            break;
        }
        for (index = 0U; index < hash->slot_limit && slot == NULL; ++index) {
            LeafSlot *candidate = &hash->slots[index];

            if (candidate->data == NULL) {
                candidate->data = malloc(PAYLOAD_HASH_LEAF_SIZE);
                if (candidate->data == NULL) {
                    hash->failed = 1;
                    break;
                }
                candidate->state = SLOT_FREE;
            }
            if (candidate->state == SLOT_FREE) {
                slot = candidate;
            }
        }
        if (slot != NULL || hash->failed != 0 || hash->worker_count == 0U) {
            break;
        }
        hash_wait(hash);
    }
    if (slot != NULL) {
        slot->state = SLOT_FILLING;
        slot->length = 0U;
    }
    hash_unlock(hash);
    return slot;
}

static void dispatch(PayloadHash *hash, LeafSlot *slot)
{
    slot->index = hash->next_leaf++;
    if (hash->worker_count == 0U) {
        if (!hash_leaf(slot->index, slot->data, slot->length, slot->digest)) {
            hash->failed = 1;
        }
        slot->state = SLOT_DONE;
        drain(hash);
        return;
    }
    hash_lock(hash);
    slot->state = SLOT_QUEUED;
    hash_wake(hash);
    hash_unlock(hash);
}

PayloadHash *payload_hash_new_with_workers(uint64_t ciphertext_len,
                                           size_t workers)
{
    PayloadHash *hash = calloc(1U, sizeof(*hash));
    unsigned char encoded_length[8];
    uint64_t leaves = ciphertext_len / PAYLOAD_HASH_LEAF_SIZE +
                      (ciphertext_len % PAYLOAD_HASH_LEAF_SIZE != 0U ? 1U : 0U);

    if (hash == NULL) {
        print_openssl_error("Cannot hash NKEM ciphertext");
        return NULL;
    }
    hash->expected = ciphertext_len;
    hash->root = EVP_MD_CTX_new();
    store_u64_be(encoded_length, ciphertext_len);
    /* root = SHA-512(root label || u64be(length) || leaf digests || tag) */
    if (hash->root == NULL ||
        EVP_DigestInit_ex(hash->root, EVP_sha512(), NULL) != 1 ||
        EVP_DigestUpdate(hash->root, root_label, sizeof(root_label) - 1U) != 1 ||
        EVP_DigestUpdate(hash->root, encoded_length, sizeof(encoded_length)) != 1) {
        print_openssl_error("Cannot hash NKEM ciphertext");
        payload_hash_free(hash);
        return NULL;
    }
    if (workers > PAYLOAD_HASH_MAX_WORKERS) {
        workers = PAYLOAD_HASH_MAX_WORKERS;
    }
    /* A single leaf has nothing to overlap with. */
    if (leaves < 2U) {
        workers = 0U;
    } else if ((uint64_t)workers > leaves) {
        workers = (size_t)leaves;
    }
    start_workers(hash, workers);
    hash->slot_limit = hash->worker_count == 0U ? 1U : hash->worker_count + 2U;
    return hash;
}

PayloadHash *payload_hash_new(uint64_t ciphertext_len)
{
    return payload_hash_new_with_workers(ciphertext_len, online_processors());
}

size_t payload_hash_worker_count(const PayloadHash *hash)
{
    return hash == NULL ? 0U : hash->worker_count;
}

int payload_hash_update(PayloadHash *hash, const unsigned char *data,
                        size_t length)
{
    if (hash == NULL || hash->finished != 0 || (data == NULL && length != 0U) ||
        (uint64_t)length > hash->expected - hash->absorbed) {
        print_openssl_error("Cannot hash NKEM ciphertext");
        return 0;
    }
    while (length > 0U) {
        size_t take;

        if (hash->filling == NULL) {
            hash->filling = acquire(hash);
            if (hash->filling == NULL) {
                print_openssl_error("Cannot hash NKEM ciphertext");
                return 0;
            }
        }
        take = PAYLOAD_HASH_LEAF_SIZE - hash->filling->length;
        if (take > length) {
            take = length;
        }
        memcpy(hash->filling->data + hash->filling->length, data, take);
        hash->filling->length += take;
        hash->absorbed += (uint64_t)take;
        data += take;
        length -= take;
        if (hash->filling->length == PAYLOAD_HASH_LEAF_SIZE) {
            dispatch(hash, hash->filling);
            hash->filling = NULL;
        }
    }
    return 1;
}

int payload_hash_final(PayloadHash *hash,
                       const unsigned char tag[PAYLOAD_HASH_TAG_SIZE],
                       unsigned char digest[PAYLOAD_HASH_DIGEST_SIZE])
{
    unsigned int digest_len = 0U;
    int failed;

    if (hash == NULL || tag == NULL || digest == NULL || hash->finished != 0 ||
        hash->absorbed != hash->expected) {
        print_openssl_error("Cannot hash NKEM ciphertext");
        return 0;
    }
    hash->finished = 1;
    if (hash->filling != NULL) {
        dispatch(hash, hash->filling);
        hash->filling = NULL;
    }
    hash_lock(hash);
    for (;;) {
        drain(hash);
        if (hash->failed != 0 || hash->next_root == hash->next_leaf ||
            hash->worker_count == 0U) {
            break;
        }
        hash_wait(hash);
    }
    failed = hash->failed != 0 || hash->next_root != hash->next_leaf;
    hash_unlock(hash);
    stop_workers(hash);
    if (failed ||
        EVP_DigestUpdate(hash->root, tag, PAYLOAD_HASH_TAG_SIZE) != 1 ||
        EVP_DigestFinal_ex(hash->root, digest, &digest_len) != 1 ||
        digest_len != PAYLOAD_HASH_DIGEST_SIZE) {
        print_openssl_error("Cannot hash NKEM ciphertext");
        return 0;
    }
    return 1;
}

void payload_hash_free(PayloadHash *hash)
{
    size_t index;

    if (hash == NULL) {
        return;
    }
    stop_workers(hash);
    for (index = 0U; index < PAYLOAD_HASH_SLOTS; ++index) {
        free(hash->slots[index].data);
    }
    EVP_MD_CTX_free(hash->root);
    free(hash);
}
