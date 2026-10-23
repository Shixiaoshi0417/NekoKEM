/*
 * Writes the throwaway fuzzing key used by fuzz_decrypt and genuine
 * containers for it: NKEM v3, NKEM v3 with no data, NKEM v4 with the fuzzing
 * key as the second of two recipients, NKEM v4 with no data and the key as
 * the first of two, and NKEM v4 with the key as the last of the maximum 64
 * recipients, so mutations reach the trial decryption, header MAC, payload
 * authentication and output commit paths.
 * Usage: fuzz_decrypt_seeds <binary directory> <seed directory>
 */
#include "../include/nekokem.h"
#include "../src/file.h"

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

/* A plaintext-PEM Hybrid key pair; the private half is optional. */
static int make_keypair(const char *public_path, const char *private_path)
{
    EVP_PKEY *x448 = EVP_PKEY_Q_keygen(NULL, NULL, "X448");
    EVP_PKEY *mlkem = EVP_PKEY_Q_keygen(NULL, NULL, "ML-KEM-1024");
    BIO *public_bio = BIO_new(BIO_s_mem());
    BIO *private_bio = BIO_new(BIO_s_mem());
    BUF_MEM *public_pem = NULL;
    BUF_MEM *private_pem = NULL;
    int success = x448 != NULL && mlkem != NULL && public_bio != NULL &&
                  private_bio != NULL &&
                  PEM_write_bio_PUBKEY(public_bio, x448) == 1 &&
                  PEM_write_bio_PUBKEY(public_bio, mlkem) == 1 &&
                  PEM_write_bio_PrivateKey(private_bio, x448, NULL, NULL, 0,
                                           NULL, NULL) == 1 &&
                  PEM_write_bio_PrivateKey(private_bio, mlkem, NULL, NULL, 0,
                                           NULL, NULL) == 1 &&
                  BIO_get_mem_ptr(public_bio, &public_pem) > 0 &&
                  BIO_get_mem_ptr(private_bio, &private_pem) > 0 &&
                  write_file(public_path, public_pem->data,
                             public_pem->length) &&
                  (private_path == NULL ||
                   write_file(private_path, private_pem->data,
                              private_pem->length));

    if (private_pem != NULL) {
        OPENSSL_cleanse(private_pem->data, private_pem->max);
    }
    BIO_free(public_bio);
    BIO_free(private_bio);
    EVP_PKEY_free(x448);
    EVP_PKEY_free(mlkem);
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
            !make_keypair(paths[index], NULL)) {
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

    if (argc != 3 || !ensure_directory(argv[2], 0700) ||
        !join(key, sizeof(key), argv[1], "decrypt-key.pem") ||
        !join(public_key, sizeof(public_key), argv[1], "decrypt-key.pub") ||
        !join(other_key, sizeof(other_key), argv[1], "decrypt-other.pub") ||
        !join(plain, sizeof(plain), argv[1], "decrypt-plain") ||
        !join(empty, sizeof(empty), argv[1], "decrypt-empty")) {
        fprintf(stderr, "Usage: fuzz_decrypt_seeds <bin directory> <seed directory>\n");
        return EXIT_FAILURE;
    }
    recipients[0] = other_key;
    recipients[1] = public_key;
    first_recipients[0] = public_key;
    first_recipients[1] = other_key;
    success = make_keypair(public_key, key) && make_keypair(other_key, NULL) &&
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
                  NEKOKEM_OPERATION_SUCCESS;
    (void)remove(plain);
    (void)remove(empty);
    if (!success) {
        fprintf(stderr, "Cannot write the decryption fuzzing seeds\n");
        return EXIT_FAILURE;
    }
    return EXIT_SUCCESS;
}
