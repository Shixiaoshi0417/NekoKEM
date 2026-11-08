/*
 * Writes the throwaway fuzzing key used by fuzz_decrypt and genuine
 * containers for it: NKEM v3, NKEM v3 with no data, NKEM v4 with the fuzzing
 * key as the second of two recipients, NKEM v4 with no data and the key as
 * the first of two, NKEM v4 with the key as the last of the maximum 64
 * recipients, and the same NKEM v5 cases signed by the fuzzing key, which
 * is a four-component identity key. Mutations therefore reach the trial
 * decryption, header MAC, payload authentication, signature-block and
 * signature checks and the output commit paths.
 * With a third directory it also writes fuzz_identity seeds there: the
 * identity and legacy public keys and the canonical identity encoding.
 * Usage: fuzz_decrypt_seeds <binary directory> <seed directory> [identity]
 */
#include "../include/nekokem.h"
#include "../src/file.h"
#include "../src/hybrid.h"
#include "../src/identity.h"

#include <openssl/bio.h>
#include <openssl/buffer.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <stdio.h>
#include <stdlib.h>

#define MAX_RECIPIENTS 64U

static int write_file(const char *path, const void *bytes, size_t length)
{
    AtomicFile output = {0};
    int success = atomic_file_open(&output, path, 0600) &&
                  (length == 0U ||
                   file_write_all(output.stream, bytes, length)) &&
                  atomic_file_commit(&output);

    atomic_file_abort(&output);
    return success;
}

static int join(char *output, size_t size, const char *directory,
                const char *name)
{
    int written = snprintf(output, size, "%s/%s", directory, name);

    return written > 0 && (size_t)written < size;
}

/*
 * A plaintext-PEM Hybrid key pair, with Ed448 and ML-DSA-87 identity
 * components when identity is set; the private half is optional.
 */
static int make_keypair(const char *public_path, const char *private_path,
                        int identity)
{
    static const char *const names[4] = {"X448", "ML-KEM-1024", "ED448",
                                         "ML-DSA-87"};
    EVP_PKEY *keys[4] = {NULL, NULL, NULL, NULL};
    BIO *public_bio = BIO_new(BIO_s_mem());
    BIO *private_bio = BIO_new(BIO_s_mem());
    BUF_MEM *public_pem = NULL;
    BUF_MEM *private_pem = NULL;
    size_t count = identity ? 4U : 2U;
    size_t index;
    int success = public_bio != NULL && private_bio != NULL;

    for (index = 0U; success && index < count; index++) {
        keys[index] = EVP_PKEY_Q_keygen(NULL, NULL, names[index]);
        success = keys[index] != NULL &&
                  PEM_write_bio_PUBKEY(public_bio, keys[index]) == 1 &&
                  PEM_write_bio_PrivateKey(private_bio, keys[index], NULL,
                                           NULL, 0, NULL, NULL) == 1;
    }
    success = success && BIO_get_mem_ptr(public_bio, &public_pem) > 0 &&
              BIO_get_mem_ptr(private_bio, &private_pem) > 0 &&
              write_file(public_path, public_pem->data, public_pem->length) &&
              (private_path == NULL ||
               write_file(private_path, private_pem->data,
                          private_pem->length));

    if (private_pem != NULL) {
        OPENSSL_cleanse(private_pem->data, private_pem->max);
    }
    BIO_free(public_bio);
    BIO_free(private_bio);
    for (index = 0U; index < 4U; index++) {
        EVP_PKEY_free(keys[index]);
    }
    return success;
}

static int encrypt_signed(const char *input, const char *output,
                          const char *const *recipients, size_t count,
                          const char *sender)
{
    NekoKEMEncryptOptions options = {0};

    options.struct_size = sizeof(options);
    options.sender_private_key_path = sender;
    return nekokem_encrypt_file_v5_with_progress(input, output, recipients,
                                                 count, &options, NULL, NULL,
                                                 NULL) ==
           NEKOKEM_OPERATION_SUCCESS;
}

/* Identity and legacy public keys, and the raw identity encoding. */
static int write_identity_seeds(const char *directory,
                                const char *public_key,
                                const char *other_key)
{
    unsigned char *pem = NULL;
    unsigned char encoding[IDENTITY_ENCODING_SIZE];
    HybridKeys keys = {0};
    char path[4096];
    size_t pem_len = 0U;
    int success = ensure_directory(directory, 0700) &&
                  file_read_regular(public_key, 1024U * 1024U, &pem,
                                    &pem_len) &&
                  join(path, sizeof(path), directory, "identity.pub") &&
                  write_file(path, pem, pem_len) &&
                  hybrid_parse_public_keys(pem, pem_len, &keys) &&
                  identity_encode(&keys, encoding) &&
                  join(path, sizeof(path), directory, "identity.nkid") &&
                  write_file(path, encoding, sizeof(encoding));

    OPENSSL_free(pem);
    pem = NULL;
    success = success &&
              file_read_regular(other_key, 1024U * 1024U, &pem, &pem_len) &&
              join(path, sizeof(path), directory, "legacy.pub") &&
              write_file(path, pem, pem_len);
    OPENSSL_free(pem);
    hybrid_keys_cleanup(&keys);
    return success;
}

/* Writes MAX_RECIPIENTS - 1 other public keys and lists the fuzzing key last. */
static int make_max_recipients(const char *directory, const char *public_key,
                               char paths[MAX_RECIPIENTS][4096],
                               const char *recipients[MAX_RECIPIENTS])
{
    char name[64];
    unsigned int index;

    for (index = 0U; index + 1U < MAX_RECIPIENTS; index++) {
        (void)snprintf(name, sizeof(name), "decrypt-max-%02u.pub", index);
        if (!join(paths[index], sizeof(paths[index]), directory, name) ||
            !make_keypair(paths[index], NULL, 0)) {
            return 0;
        }
        recipients[index] = paths[index];
    }
    recipients[MAX_RECIPIENTS - 1U] = public_key;
    return 1;
}

int main(int argc, char **argv)
{
    static const char sample[] =
        "NekoKEM fuzzing plaintext: public, generated for every build.\n";
    static char max_paths[MAX_RECIPIENTS][4096];
    char key[4096];
    char public_key[4096];
    char other_key[4096];
    char plain[4096];
    char empty[4096];
    char seed[4096];
    const char *recipients[2];
    const char *first_recipients[2];
    const char *max_recipients[MAX_RECIPIENTS];
    int success;

    if ((argc != 3 && argc != 4) || !ensure_directory(argv[2], 0700) ||
        !join(key, sizeof(key), argv[1], "decrypt-key.pem") ||
        !join(public_key, sizeof(public_key), argv[1], "decrypt-key.pub") ||
        !join(other_key, sizeof(other_key), argv[1], "decrypt-other.pub") ||
        !join(plain, sizeof(plain), argv[1], "decrypt-plain") ||
        !join(empty, sizeof(empty), argv[1], "decrypt-empty")) {
        fprintf(stderr, "Usage: fuzz_decrypt_seeds <bin directory> <seed directory> [identity seed directory]\n");
        return EXIT_FAILURE;
    }
    recipients[0] = other_key;
    recipients[1] = public_key;
    first_recipients[0] = public_key;
    first_recipients[1] = other_key;
    success = make_keypair(public_key, key, 1) &&
              make_keypair(other_key, NULL, 0) &&
              make_max_recipients(argv[1], public_key, max_paths,
                                  max_recipients) &&
              write_file(plain, sample, sizeof(sample) - 1U) &&
              write_file(empty, "", 0U) &&
              join(seed, sizeof(seed), argv[2], "valid-v3") &&
              nekokem_encrypt_file(plain, seed, public_key) &&
              join(seed, sizeof(seed), argv[2], "valid-v3-empty") &&
              nekokem_encrypt_file(empty, seed, public_key) &&
              join(seed, sizeof(seed), argv[2], "valid-v4") &&
              nekokem_encrypt_file_multi_with_progress(
                  plain, seed, recipients, 2U, NULL, NULL) ==
                  NEKOKEM_OPERATION_SUCCESS &&
              join(seed, sizeof(seed), argv[2], "valid-v4-empty") &&
              nekokem_encrypt_file_multi_with_progress(
                  empty, seed, first_recipients, 2U, NULL, NULL) ==
                  NEKOKEM_OPERATION_SUCCESS &&
              join(seed, sizeof(seed), argv[2], "valid-v4-max") &&
              nekokem_encrypt_file_multi_with_progress(
                  plain, seed, max_recipients, MAX_RECIPIENTS, NULL, NULL) ==
                  NEKOKEM_OPERATION_SUCCESS &&
              join(seed, sizeof(seed), argv[2], "valid-v5") &&
              encrypt_signed(plain, seed, first_recipients, 1U, key) &&
              join(seed, sizeof(seed), argv[2], "valid-v5-empty") &&
              encrypt_signed(empty, seed, first_recipients, 1U, key) &&
              join(seed, sizeof(seed), argv[2], "valid-v5-two") &&
              encrypt_signed(plain, seed, recipients, 2U, key) &&
              join(seed, sizeof(seed), argv[2], "valid-v5-max") &&
              encrypt_signed(plain, seed, max_recipients, MAX_RECIPIENTS,
                             key) &&
              (argc != 4 ||
               write_identity_seeds(argv[3], public_key, other_key));
    (void)remove(plain);
    (void)remove(empty);
    if (!success) {
        fprintf(stderr, "Cannot write the decryption fuzzing seeds\n");
        return EXIT_FAILURE;
    }
    return EXIT_SUCCESS;
}
