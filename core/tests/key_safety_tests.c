/*
 * Keys are never replaced by accident: key generation refuses existing key
 * files, an operation's output may not be one of its keys under any
 * spelling, and an NKPR key is recognized by its contents, not its name.
 */
#include "nekokem.h"
#include "file.h"

#include <openssl/bio.h>
#include <openssl/buffer.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifndef _WIN32
#include <unistd.h>
#endif

static const unsigned char password[] = "public-test-key-safety";
static const unsigned char plaintext[] = "key safety plaintext\n";

typedef struct {
    unsigned char *bytes;
    size_t length;
} Snapshot;

static int write_private(const char *path, const void *bytes, size_t length)
{
    AtomicFile output = {0};
    int success = atomic_file_open(&output, path, 0600) &&
                  file_write_all(output.stream, bytes, length) &&
                  atomic_file_commit(&output);

    atomic_file_abort(&output);
    return success;
}

static int snapshot(const char *path, Snapshot *saved)
{
    return file_read_regular(path, 16U * 1024U * 1024U, &saved->bytes,
                             &saved->length);
}

static void snapshot_free(Snapshot *saved)
{
    OPENSSL_free(saved->bytes);
    saved->bytes = NULL;
    saved->length = 0U;
}

static int unchanged(const char *path, const Snapshot *saved)
{
    Snapshot now = {0};
    int same = snapshot(path, &now) && now.length == saved->length &&
               memcmp(now.bytes, saved->bytes, now.length) == 0;

    snapshot_free(&now);
    return same;
}

static int missing(const char *path)
{
    int exists = 1;

    return file_path_exists(path, &exists) && exists == 0;
}

/* A plaintext-PEM Hybrid key pair, written under the given names. */
static int make_plain_keypair(const char *public_path, const char *private_path)
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
                  write_private(public_path, public_pem->data,
                                public_pem->length) &&
                  write_private(private_path, private_pem->data,
                                private_pem->length);

    if (private_pem != NULL) {
        OPENSSL_cleanse(private_pem->data, private_pem->max);
    }
    BIO_free(public_bio);
    BIO_free(private_bio);
    EVP_PKEY_free(x448);
    EVP_PKEY_free(mlkem);
    return success;
}

static int decrypts_to_plaintext(const char *cipher, const char *key,
                                 const unsigned char *secret,
                                 size_t secret_len)
{
    Snapshot output = {0};
    int success;

    (void)remove("decrypted");
    success = nekokem_decrypt_file(cipher, "decrypted", key, secret,
                                   secret_len) &&
              snapshot("decrypted", &output) &&
              output.length == sizeof(plaintext) - 1U &&
              memcmp(output.bytes, plaintext, output.length) == 0;
    snapshot_free(&output);
    return success;
}

static int test_keygen_never_replaces(void)
{
    Snapshot public_key = {0};
    Snapshot private_key = {0};
    AtomicFile racing = {0};
    static const char staged[] = "staged";
    static const char appeared[] = "appeared first";
    int success = 0;

    if (!nekokem_generate_keypair("public.key", "private.key.enc", password,
                                  sizeof(password) - 1U) ||
        !snapshot("public.key", &public_key) ||
        !snapshot("private.key.enc", &private_key)) {
        fprintf(stderr, "Cannot create the first key pair\n");
        goto cleanup;
    }
    /* Both files exist, or only the private key does: nothing is written. */
    if (nekokem_generate_keypair("public.key", "private.key.enc", password,
                                 sizeof(password) - 1U) ||
        !unchanged("public.key", &public_key) ||
        !unchanged("private.key.enc", &private_key) ||
        remove("public.key") != 0 ||
        nekokem_generate_keypair("public.key", "private.key.enc", password,
                                 sizeof(password) - 1U) ||
        !missing("public.key") ||
        !unchanged("private.key.enc", &private_key)) {
        fprintf(stderr, "Key generation replaced or added a key file\n");
        goto cleanup;
    }
    /* Rotation is a separate, explicit call. */
    if (!nekokem_replace_keypair("public.key", "private.key.enc", password,
                                 sizeof(password) - 1U) ||
        unchanged("private.key.enc", &private_key) ||
        missing("public.key")) {
        fprintf(stderr, "Explicit key replacement failed\n");
        goto cleanup;
    }
    /* A name that appears after staging still wins over a create-only commit. */
    if (!atomic_file_open(&racing, "race", 0600) ||
        !file_write_all(racing.stream, staged, sizeof(staged) - 1U) ||
        !write_private("race", appeared, sizeof(appeared) - 1U) ||
        atomic_file_commit_pair_new(&racing, NULL)) {
        fprintf(stderr, "Create-only commit replaced an existing file\n");
        goto cleanup;
    }
    snapshot_free(&public_key);
    if (!snapshot("race", &public_key) ||
        public_key.length != sizeof(appeared) - 1U ||
        memcmp(public_key.bytes, appeared, public_key.length) != 0) {
        fprintf(stderr, "Create-only commit changed the existing file\n");
        goto cleanup;
    }
    success = 1;

cleanup:
    atomic_file_abort(&racing);
    snapshot_free(&public_key);
    snapshot_free(&private_key);
    return success;
}

static int test_output_never_replaces_a_key(void)
{
    static const char *const recipients[] = {"public.key", "second.pub"};
    Snapshot public_key = {0};
    Snapshot private_key = {0};
    Snapshot second_key = {0};
    int success = 0;

    if (!write_private("plain", plaintext, sizeof(plaintext) - 1U) ||
        !make_plain_keypair("second.pub", "second.key") ||
        !nekokem_encrypt_file("plain", "cipher.nkem", "public.key") ||
        !snapshot("public.key", &public_key) ||
        !snapshot("private.key.enc", &private_key) ||
        !snapshot("second.pub", &second_key) ||
        !ensure_directory("nested", 0700)) {
        fprintf(stderr, "Cannot create output alias fixtures\n");
        goto cleanup;
    }
    /* The private key in use, by its own name and by another spelling. */
    if (nekokem_decrypt_file("cipher.nkem", "private.key.enc",
                             "private.key.enc", password,
                             sizeof(password) - 1U) ||
        nekokem_decrypt_file("cipher.nkem", "./private.key.enc",
                             "private.key.enc", password,
                             sizeof(password) - 1U) ||
        nekokem_decrypt_file("cipher.nkem", "nested/../private.key.enc",
                             "private.key.enc", password,
                             sizeof(password) - 1U) ||
        !unchanged("private.key.enc", &private_key)) {
        fprintf(stderr, "Decryption output replaced the private key\n");
        goto cleanup;
    }
    /* A public key in use, for one recipient and for several. */
    if (nekokem_encrypt_file("plain", "nested/../public.key", "public.key") ||
        nekokem_encrypt_file_multi_with_progress(
            "plain", "./second.pub", recipients, 2U, NULL, NULL) !=
            NEKOKEM_OPERATION_ERROR ||
        !unchanged("public.key", &public_key) ||
        !unchanged("second.pub", &second_key)) {
        fprintf(stderr, "Encryption output replaced a public key\n");
        goto cleanup;
    }
    /* A different output still works. */
    if (!decrypts_to_plaintext("cipher.nkem", "private.key.enc", password,
                               sizeof(password) - 1U)) {
        fprintf(stderr, "Ordinary decryption failed\n");
        goto cleanup;
    }
    success = 1;

cleanup:
    snapshot_free(&public_key);
    snapshot_free(&private_key);
    snapshot_free(&second_key);
    return success;
}

static int test_nkpr_detected_by_contents(void)
{
    Snapshot nkpr = {0};
    Snapshot pem = {0};
    int success = 0;

    /* Android exports NKPR as private.nkpr; no ".enc" suffix is needed. */
    if (!snapshot("private.key.enc", &nkpr) ||
        !write_private("private.nkpr", nkpr.bytes, nkpr.length) ||
        nekokem_private_key_requires_password("private.nkpr") != 1 ||
        !decrypts_to_plaintext("cipher.nkem", "private.nkpr", password,
                               sizeof(password) - 1U) ||
        nekokem_decrypt_file("cipher.nkem", "decrypted", "private.nkpr",
                             NULL, 0U)) {
        fprintf(stderr, "An NKPR key without .enc was not read as NKPR\n");
        goto cleanup;
    }
    /* A plaintext PEM key named ".enc" is still plaintext PEM. */
    if (!snapshot("second.key", &pem) ||
        !write_private("plain-pem.key.enc", pem.bytes, pem.length) ||
        nekokem_private_key_requires_password("plain-pem.key.enc") != 0 ||
        !nekokem_encrypt_file("plain", "second.nkem", "second.pub") ||
        !decrypts_to_plaintext("second.nkem", "plain-pem.key.enc", NULL, 0U)) {
        fprintf(stderr, "A plaintext PEM key named .enc was not read as PEM\n");
        goto cleanup;
    }
    if (nekokem_private_key_requires_password("missing-key") != 0) {
        fprintf(stderr, "A missing key asked for a password\n");
        goto cleanup;
    }
    success = 1;

cleanup:
    if (nkpr.bytes != NULL) OPENSSL_cleanse(nkpr.bytes, nkpr.length);
    if (pem.bytes != NULL) OPENSSL_cleanse(pem.bytes, pem.length);
    snapshot_free(&nkpr);
    snapshot_free(&pem);
    return success;
}

#ifndef _WIN32
static void remove_fixtures(void)
{
    static const char *const names[] = {
        "public.key", "private.key.enc", "race", "plain", "second.pub",
        "second.key", "cipher.nkem", "decrypted", "private.nkpr",
        "plain-pem.key.enc", "second.nkem"};
    size_t index;

    for (index = 0U; index < sizeof(names) / sizeof(names[0]); ++index) {
        (void)remove(names[index]);
    }
    (void)rmdir("nested");
}
#endif

int main(void)
{
#ifndef _WIN32
    /* Windows CI runs each test in its own empty directory. */
    char directory[] = "/tmp/nekokem-key-safety.XXXXXX";

    if (mkdtemp(directory) == NULL || chdir(directory) != 0) {
        perror("Cannot create test directory");
        return EXIT_FAILURE;
    }
#endif
    int success = test_keygen_never_replaces() &&
                  test_output_never_replaces_a_key() &&
                  test_nkpr_detected_by_contents();

#ifndef _WIN32
    remove_fixtures();
    if (chdir("/") == 0) {
        (void)rmdir(directory);
    }
#endif
    if (!success) {
        fprintf(stderr, "Key safety tests failed\n");
        return EXIT_FAILURE;
    }
    puts("Key safety tests passed");
    return EXIT_SUCCESS;
}
