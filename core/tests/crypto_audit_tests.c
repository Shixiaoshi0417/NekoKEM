#include "nekokem.h"
#include "file.h"
#include "hybrid.h"
#include "private_key.h"
#include "secure_mem.h"
#include "x448_encoding.h"

#include <openssl/buffer.h>
#include <openssl/err.h>
#include <openssl/pem.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "Crypto audit check failed at line %d: %s\n", \
                __LINE__, #condition); \
        return 0; \
    } \
} while (0)

static const unsigned char password[] = "crypto-audit-public-test-password";

static int write_bytes(const char *path, const unsigned char *bytes, size_t size)
{
    AtomicFile output = {0};
    int result = atomic_file_open(&output, path, 0600) &&
                 file_write_all(output.stream, bytes, size) &&
                 atomic_file_commit(&output);

    atomic_file_abort(&output);
    return result;
}

static void prime_encoding(unsigned char output[X448_ENCODED_SIZE])
{
    memset(output, 0xff, X448_ENCODED_SIZE);
    output[28] = 0xfeU;
}

static int test_canonical_parsers(void)
{
    unsigned char encoded[X448_ENCODED_SIZE];
    unsigned char v3[NKEM_V3_HEADER_SIZE + NKEM_X448_EPHEMERAL_PUBLIC_SIZE +
                     NKEM_V3_KEM_CIPHERTEXT_SIZE + NKEM_V3_SALT_SIZE +
                     NKEM_NONCE_SIZE + NKEM_TAG_SIZE] = {0};
    unsigned char v4[NKEM_V4_HEADER_SIZE + NKEM_V4_SALT_SIZE +
                     2U * NKEM_V4_ENTRY_SIZE + NKEM_V4_MAC_SIZE +
                     NKEM_NONCE_SIZE + NKEM_TAG_SIZE] = {0};
    size_t index;

    nkem_v3_header_encode(v3, X448_ENCODED_SIZE, KEM_CIPHERTEXT_SIZE, 0U);
    nkem_v4_header_encode(v4, 2U, 0U);
    CHECK(nkem_v3_container_parse(v3, sizeof(v3)));
    CHECK(nkem_v4_container_parse(v4, sizeof(v4)));

    prime_encoding(encoded);
    --encoded[0]; /* p - 1 is canonical, even though it has small order. */
    CHECK(x448_public_is_canonical(encoded));
    CHECK(x448_public_has_small_order(encoded));
    memcpy(v3 + NKEM_V3_HEADER_SIZE, encoded, sizeof(encoded));
    CHECK(nkem_v3_container_parse(v3, sizeof(v3)));
    for (index = 0U; index < 2U; ++index) {
        size_t offset = NKEM_V4_HEADER_SIZE + NKEM_V4_SALT_SIZE +
                        index * NKEM_V4_ENTRY_SIZE;

        memcpy(v4 + offset, encoded, sizeof(encoded));
        CHECK(nkem_v4_container_parse(v4, sizeof(v4)));
        prime_encoding(encoded);
        memcpy(v4 + offset, encoded, sizeof(encoded));
        CHECK(!nkem_v4_container_parse(v4, sizeof(v4)));
        memset(v4 + offset, 0, sizeof(encoded));
        --encoded[0];
    }
    prime_encoding(encoded);
    CHECK(!x448_public_is_canonical(encoded));
    memcpy(v3 + NKEM_V3_HEADER_SIZE, encoded, sizeof(encoded));
    CHECK(!nkem_v3_container_parse(v3, sizeof(v3)));
    encoded[0] = 0U; /* p + 1; carry through the first 28 bytes. */
    memset(encoded, 0, 28U);
    encoded[28] = 0xffU;
    CHECK(!x448_public_is_canonical(encoded));
    memcpy(v3 + NKEM_V3_HEADER_SIZE, encoded, sizeof(encoded));
    CHECK(!nkem_v3_container_parse(v3, sizeof(v3)));
    memset(encoded, 0xff, sizeof(encoded));
    CHECK(!x448_public_is_canonical(encoded));
    CHECK(!x448_public_is_canonical(NULL));
    return 1;
}

static int test_canonical_imports(void)
{
    unsigned char encoded[X448_ENCODED_SIZE];
    unsigned char ephemeral[X448_ENCODED_SIZE];
    unsigned char *secret = NULL;
    size_t secret_len = 0U;
    EVP_PKEY *private_key = EVP_PKEY_Q_keygen(NULL, NULL, "X448");
    EVP_PKEY *mlkem = EVP_PKEY_Q_keygen(NULL, NULL, "ML-KEM-1024");
    EVP_PKEY *noncanonical;
    BIO *pem = BIO_new(BIO_s_mem());
    BUF_MEM *buffer = NULL;
    HybridKeys loaded = {0};
    char fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE];
    int result;

    prime_encoding(encoded);
    /* OpenSSL imports unreduced u, so the application must validate it. */
    noncanonical = EVP_PKEY_new_raw_public_key_ex(
        NULL, "X448", NULL, encoded, sizeof(encoded));
    CHECK(private_key != NULL && mlkem != NULL && noncanonical != NULL &&
          pem != NULL);
    CHECK(PEM_write_bio_PUBKEY(pem, noncanonical) == 1 &&
          PEM_write_bio_PUBKEY(pem, mlkem) == 1 &&
          BIO_get_mem_ptr(pem, &buffer) > 0 &&
          write_bytes("noncanonical.pub", (unsigned char *)buffer->data,
                      buffer->length));
    result = !hybrid_load_public_keys("noncanonical.pub", &loaded) &&
             !nekokem_public_key_fingerprint("noncanonical.pub", fingerprint,
                                             sizeof(fingerprint)) &&
             !hybrid_x448_encapsulate(noncanonical, ephemeral, &secret,
                                       &secret_len) &&
             !hybrid_x448_decapsulate(private_key, encoded, sizeof(encoded),
                                       &secret, &secret_len);
    hybrid_keys_cleanup(&loaded);
    secure_free(secret, secret_len);
    EVP_PKEY_free(private_key);
    EVP_PKEY_free(mlkem);
    EVP_PKEY_free(noncanonical);
    BIO_free(pem);
    CHECK(result);
    return 1;
}

static int decode_message(const unsigned char *container, size_t container_len,
                           const unsigned char *secret, size_t secret_len,
                           char *message, size_t capacity)
{
    FILE *capture = tmpfile();
    int original = dup(STDERR_FILENO);
    unsigned char *pem = NULL;
    size_t pem_len = 0U;
    size_t count;
    int result = 0;

    if (capture == NULL || original < 0 || fflush(stderr) != 0 ||
        dup2(fileno(capture), STDERR_FILENO) < 0) {
        goto cleanup;
    }
    result = !protected_private_key_decode(container, container_len,
                                            secret, secret_len, &pem, &pem_len);
    if (fflush(stderr) != 0 || dup2(original, STDERR_FILENO) < 0 ||
        fseek(capture, 0L, SEEK_SET) != 0) {
        result = 0;
        goto cleanup;
    }
    count = fread(message, 1U, capacity - 1U, capture);
    message[count] = '\0';
    if (ferror(capture) || count == 0U || !feof(capture)) {
        result = 0;
    }

cleanup:
    secure_free(pem, pem_len);
    if (original >= 0) {
        (void)dup2(original, STDERR_FILENO);
        (void)close(original);
    }
    if (capture != NULL) {
        (void)fclose(capture);
    }
    return result;
}

static int test_nkpr_errors(void)
{
    unsigned char *container = NULL;
    unsigned char *mutated = NULL;
    size_t length = 0U;
    char wrong_password[512];
    char damaged[512];
    static const size_t offsets[] = {0U, 4U, 8U, 24U, 28U, 36U, 68U, 80U};
    size_t index;
    int result = 0;

    CHECK(nekokem_generate_keypair("normal.pub", "normal.nkpr", password,
                                   sizeof(password) - 1U));
    CHECK(nekokem_check_private_key_password("normal.nkpr", password,
                                             sizeof(password) - 1U));
    CHECK(file_read_sensitive("normal.nkpr", NKPR_MAX_CONTAINER_SIZE,
                               &container, &length));
    mutated = OPENSSL_malloc(length);
    CHECK(mutated != NULL);
    CHECK(decode_message(container, length, (const unsigned char *)"wrong", 5U,
                          wrong_password, sizeof(wrong_password)));
    for (index = 0U; index < sizeof(offsets) / sizeof(offsets[0]); ++index) {
        memcpy(mutated, container, length);
        mutated[offsets[index]] ^= 1U;
        CHECK(decode_message(mutated, length, password, sizeof(password) - 1U,
                              damaged, sizeof(damaged)));
        CHECK(strcmp(wrong_password, damaged) == 0);
    }
    memcpy(mutated, container, length);
    mutated[length - 1U] ^= 1U;
    CHECK(decode_message(mutated, length, password, sizeof(password) - 1U,
                          damaged, sizeof(damaged)));
    CHECK(strcmp(wrong_password, damaged) == 0);
    CHECK(decode_message(container, length - 1U, password, sizeof(password) - 1U,
                          damaged, sizeof(damaged)));
    CHECK(strcmp(wrong_password, damaged) == 0);
    result = 1;
    secure_free(container, length);
    secure_free(mutated, length);
    return result;
}

static int test_degenerate_password_check(void)
{
    /* Clamped scalar 4 * subgroup order generates the zero public point. */
    static const unsigned char scalar[X448_ENCODED_SIZE] = {
        0xcc, 0x13, 0x61, 0xad, 0x4a, 0x0a, 0xe3, 0x8d,
        0x54, 0x3d, 0x16, 0x37, 0xca, 0x09, 0xb3, 0x85,
        0x40, 0xda, 0x58, 0xbb, 0x26, 0x6d, 0x3b, 0x11,
        0xa7, 0x8f, 0x28, 0xf3, 0xfd, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff
    };
    EVP_PKEY *x448 = EVP_PKEY_new_raw_private_key_ex(
        NULL, "X448", NULL, scalar, sizeof(scalar));
    EVP_PKEY *mlkem = EVP_PKEY_Q_keygen(NULL, NULL, "ML-KEM-1024");
    BIO *pem = BIO_new(BIO_s_mem());
    BUF_MEM *buffer = NULL;
    HybridKeys loaded = {0};
    int result;

    CHECK(x448 != NULL && mlkem != NULL && pem != NULL);
    CHECK(PEM_write_bio_PrivateKey(pem, x448, NULL, NULL, 0, NULL, NULL) == 1 &&
          PEM_write_bio_PrivateKey(pem, mlkem, NULL, NULL, 0, NULL, NULL) == 1 &&
          BIO_get_mem_ptr(pem, &buffer) > 0);
    CHECK(protected_private_key_write("degenerate.nkpr",
                                       (unsigned char *)buffer->data,
                                       buffer->length, password,
                                       sizeof(password) - 1U));
    result = nekokem_private_key_exists("degenerate.nkpr") &&
             !nekokem_check_private_key_password("degenerate.nkpr", password,
                                                  sizeof(password) - 1U) &&
             !hybrid_load_decryption_keys("degenerate.nkpr", password,
                                           sizeof(password) - 1U, &loaded);
    secure_mem_clear(buffer->data, buffer->max);
    hybrid_keys_cleanup(&loaded);
    BIO_free(pem);
    EVP_PKEY_free(x448);
    EVP_PKEY_free(mlkem);
    CHECK(result);
    return 1;
}

static int test_low_order_entries(void)
{
    const char *const recipients[] = {"normal.pub", "second.pub"};
    const char *const private_keys[] = {"normal.nkpr", "second.nkpr"};
    unsigned char encoded[X448_ENCODED_SIZE];
    unsigned char *container = NULL;
    unsigned char *mutated = NULL;
    size_t length = 0U;
    size_t entry;
    size_t kind;
    size_t recipient;
    int exists;

    CHECK(nekokem_generate_keypair("second.pub", "second.nkpr", password,
                                   sizeof(password) - 1U));
    CHECK(write_bytes("plain", (const unsigned char *)"audit", 5U));
    CHECK(nekokem_encrypt_file_multi_with_progress(
              "plain", "multi.nkem", recipients, 2U, NULL, NULL) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(file_read_regular("multi.nkem", 16384U, &container, &length));
    mutated = OPENSSL_malloc(length);
    CHECK(mutated != NULL);
    for (entry = 0U; entry < 2U; ++entry) {
        for (kind = 0U; kind < 4U; ++kind) {
            memset(encoded, 0, sizeof(encoded));
            if (kind == 1U) {
                encoded[0] = 1U;
            } else if (kind >= 2U) {
                prime_encoding(encoded);
                if (kind == 2U) {
                    --encoded[0];
                }
            }
            memcpy(mutated, container, length);
            memcpy(mutated + NKEM_V4_HEADER_SIZE + NKEM_V4_SALT_SIZE +
                   entry * NKEM_V4_ENTRY_SIZE, encoded, sizeof(encoded));
            CHECK(write_bytes("bad.nkem", mutated, length));
            for (recipient = 0U; recipient < 2U; ++recipient) {
                CHECK(!nekokem_decrypt_file("bad.nkem", "out",
                                             private_keys[recipient], password,
                                             sizeof(password) - 1U));
                CHECK(file_path_exists("out", &exists) && exists == 0);
            }
        }
    }
    OPENSSL_free(container);
    OPENSSL_free(mutated);
    return 1;
}

static int test_facade_newlines(void)
{
    FILE *capture = tmpfile();
    int original = dup(STDERR_FILENO);
    char output[256];
    size_t count;
    int result = 0;

    if (capture == NULL || original < 0 || fflush(stderr) != 0 ||
        dup2(fileno(capture), STDERR_FILENO) < 0) {
        goto cleanup;
    }
    result = !nekokem_generate_keypair("", "unused", password,
                                       sizeof(password) - 1U) &&
             !nekokem_generate_keypair("unused.pub", "unused.nkpr", NULL, 0U);
    if (fflush(stderr) != 0 || dup2(original, STDERR_FILENO) < 0 ||
        fseek(capture, 0L, SEEK_SET) != 0) {
        result = 0;
        goto cleanup;
    }
    count = fread(output, 1U, sizeof(output) - 1U, capture);
    output[count] = '\0';
    result = result && !ferror(capture) &&
             strcmp(output, "NekoKEM Core received an empty key path\n"
                            "A non-empty private-key password is required\n") == 0;

cleanup:
    if (original >= 0) {
        (void)dup2(original, STDERR_FILENO);
        (void)close(original);
    }
    if (capture != NULL) {
        (void)fclose(capture);
    }
    CHECK(result);
    return 1;
}

int main(void)
{
    char directory[] = "/tmp/nekokem-crypto-audit.XXXXXX";
    static const char *const names[] = {
        "noncanonical.pub", "normal.pub", "normal.nkpr", "degenerate.nkpr",
        "second.pub", "second.nkpr", "plain", "multi.nkem", "bad.nkem", "out"
    };
    size_t index;
    int result;

    if (mkdtemp(directory) == NULL || chdir(directory) != 0) {
        perror("crypto audit test directory");
        return EXIT_FAILURE;
    }
    result = test_canonical_parsers() && test_canonical_imports() &&
             test_nkpr_errors() && test_degenerate_password_check() &&
             test_low_order_entries() && test_facade_newlines();
    for (index = 0U; index < sizeof(names) / sizeof(names[0]); ++index) {
        (void)unlink(names[index]);
    }
    (void)unlink(".nekokem-pair.lock");
    if (chdir("/") == 0) {
        (void)rmdir(directory);
    }
    if (result) {
        puts("Core crypto audit regressions passed");
    }
    return result ? EXIT_SUCCESS : EXIT_FAILURE;
}
