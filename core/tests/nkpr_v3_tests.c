/*
 * NKPR v3 key commitment (docs/key-commitment.md): known-answer vectors from
 * an independent implementation (nkpr_v3_vectors.py), the commitment check
 * before AES-GCM, exact container lengths, and identity keys stored as v3
 * working everywhere Core reads a private key.
 */
#include "nekokem.h"
#include "nekokem_internal.h"
#include "private_key.h"
#include "secure_mem.h"

#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define CHECK(condition)                                                  \
    do {                                                                  \
        if (!(condition)) {                                               \
            fprintf(stderr, "%s:%d: check failed: %s\n", __FILE__,        \
                    __LINE__, #condition);                                \
            return 0;                                                     \
        }                                                                 \
    } while (0)

static const char kat_password[] = "NekoKEM NKPR v3 test password";
static const char kat_plaintext[] = "NKPR v3 known-answer plaintext\n";
static const char kat_cipher_key[] =
    "8ef489b465bd5bdc732150ad3a14778bd33b2cfb1cdd075581001c537a2a14e1";
static const char kat_container[] =
    "4e4b5052030101000001000000000003000000040000001300200c10000000000000001f"
    "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    "8f90500ca995f5b0f2664227b154829b85c0afc75a711dad7523fdd773ae0296"
    "6465666768696a6b6c6d6e6f"
    "fc6e5c1e3d0f4632b3a31add8e7195ba220d187a7e732b7c3c555e7ffd1f1e"
    "6cfd1ad3c736fe040fae7b89f6e89726";

#define KAT_CONTAINER_SIZE (sizeof(kat_container) / 2U)
#define COMMITMENT_OFFSET (NKPR_HEADER_SIZE + NKPR_SALT_SIZE)
#define NONCE_OFFSET (COMMITMENT_OFFSET + NKPR_COMMITMENT_SIZE)
#define CIPHERTEXT_OFFSET (NONCE_OFFSET + NKPR_NONCE_SIZE)

static size_t from_hex(const char *hex, unsigned char *output, size_t size)
{
    size_t length = strlen(hex) / 2U;
    size_t index;

    if (length > size) {
        return 0U;
    }
    for (index = 0U; index < length; ++index) {
        unsigned int byte;

        if (sscanf(hex + index * 2U, "%2x", &byte) != 1) {
            return 0U;
        }
        output[index] = (unsigned char)byte;
    }
    return length;
}

/* 1 when the container opens with password and yields the expected bytes. */
static int opens(const unsigned char *container, size_t length,
                 const char *password, const char *expected)
{
    unsigned char *pem = NULL;
    size_t pem_len = 0U;
    unsigned int version = 0U;
    int result = protected_private_key_decode_version(
        container, length, (const unsigned char *)password, strlen(password),
        &pem, &pem_len, &version);

    if (result != 0) {
        result = version == NKPR_VERSION_COMMITTED &&
                 (expected == NULL ||
                  (pem_len == strlen(expected) &&
                   memcmp(pem, expected, pem_len) == 0));
    }
    secure_free(pem, pem_len);
    return result;
}

/*
 * Re-seals the KAT ciphertext under the real cipher key with whatever
 * commitment the container now holds, so only the commitment check can
 * reject it.
 */
static int reseal(unsigned char *container)
{
    unsigned char key[32];
    EVP_CIPHER_CTX *context = EVP_CIPHER_CTX_new();
    size_t plaintext_len = strlen(kat_plaintext);
    int length = 0;
    int success = 0;

    if (context == NULL || from_hex(kat_cipher_key, key, sizeof(key)) != 32U ||
        EVP_EncryptInit_ex(context, EVP_aes_256_gcm(), NULL, NULL, NULL) != 1 ||
        EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_SET_IVLEN,
                            (int)NKPR_NONCE_SIZE, NULL) != 1 ||
        EVP_EncryptInit_ex(context, NULL, NULL, key,
                           container + NONCE_OFFSET) != 1 ||
        EVP_EncryptUpdate(context, NULL, &length, container,
                          (int)CIPHERTEXT_OFFSET) != 1 ||
        EVP_EncryptUpdate(context, container + CIPHERTEXT_OFFSET, &length,
                          (const unsigned char *)kat_plaintext,
                          (int)plaintext_len) != 1 ||
        EVP_EncryptFinal_ex(context, container + CIPHERTEXT_OFFSET, &length) != 1 ||
        EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_GET_TAG, (int)NKPR_TAG_SIZE,
                            container + CIPHERTEXT_OFFSET + plaintext_len) != 1) {
        goto cleanup;
    }
    success = 1;

cleanup:
    EVP_CIPHER_CTX_free(context);
    OPENSSL_cleanse(key, sizeof(key));
    return success;
}

static int known_answer_and_commitment(void)
{
    unsigned char container[KAT_CONTAINER_SIZE];
    unsigned char changed[KAT_CONTAINER_SIZE];
    const size_t length = sizeof(container);
    const size_t positions[] = {COMMITMENT_OFFSET, NONCE_OFFSET,
                                CIPHERTEXT_OFFSET, length - 1U};
    size_t index;

    CHECK(from_hex(kat_container, container, sizeof(container)) == length);
    CHECK(length == NKPR_HEADER_SIZE + NKPR_SALT_SIZE + NKPR_COMMITMENT_SIZE +
                        NKPR_NONCE_SIZE + strlen(kat_plaintext) + NKPR_TAG_SIZE);

    /* Core derives the same keys as the independent implementation. */
    CHECK(opens(container, length, kat_password, kat_plaintext));
    CHECK(!opens(container, length, "NekoKEM NKPR v3 test passwore", NULL));

    /* A ciphertext that verifies under the real key but a commitment that
     * does not match: only the commitment check stops it. */
    memcpy(changed, container, length);
    CHECK(reseal(changed));
    CHECK(opens(changed, length, kat_password, kat_plaintext));
    changed[COMMITMENT_OFFSET + 7U] ^= 0x01U;
    CHECK(reseal(changed));
    CHECK(!opens(changed, length, kat_password, NULL));

    /* The commitment, nonce, ciphertext and tag are all authenticated. */
    for (index = 0U; index < sizeof(positions) / sizeof(positions[0]); ++index) {
        memcpy(changed, container, length);
        changed[positions[index]] ^= 0x80U;
        CHECK(!opens(changed, length, kat_password, NULL));
    }

    /* The length follows the version, so neither relabelling parses. */
    CHECK(protected_private_key_container_parse(container, length));
    CHECK(!protected_private_key_container_parse(container, length - 1U));
    memcpy(changed, container, length);
    changed[4] = NKPR_VERSION_IDENTITY;
    CHECK(!protected_private_key_container_parse(changed, length));
    CHECK(!opens(changed, length, kat_password, NULL));
    CHECK(protected_private_key_container_parse(changed,
                                                length - NKPR_COMMITMENT_SIZE));
    changed[4] = 4U;
    CHECK(!protected_private_key_container_parse(changed, length));
    return 1;
}

static long file_size(const char *path)
{
    struct stat status;

    return stat(path, &status) == 0 ? (long)status.st_size : -1L;
}

static int write_text(const char *path, const char *text)
{
    FILE *output = fopen(path, "wb");
    int success = output != NULL &&
                  fwrite(text, 1U, strlen(text), output) == strlen(text);

    if (output != NULL && fclose(output) != 0) {
        success = 0;
    }
    return success;
}

static int files_equal(const char *first, const char *second)
{
    FILE *a = fopen(first, "rb");
    FILE *b = fopen(second, "rb");
    int equal = a != NULL && b != NULL;

    while (equal) {
        int left = fgetc(a);
        int right = fgetc(b);

        if (left != right) {
            equal = 0;
        } else if (left == EOF) {
            break;
        }
    }
    if (a != NULL) {
        fclose(a);
    }
    if (b != NULL) {
        fclose(b);
    }
    return equal;
}

/* An identity key rewritten as NKPR v3 works wherever Core reads keys. */
static int identity_key_as_v3(void)
{
    static const char password[] = "nkpr v3 identity password";
    const unsigned char *bytes = (const unsigned char *)password;
    const size_t password_len = strlen(password);
    const char *recipients[1] = {"public.key"};
    char v2_fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE];
    char v3_fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE];
    unsigned char *pem = NULL;
    size_t pem_len = 0U;
    char *text = NULL;
    size_t legacy_len;
    unsigned int version = 0U;
    NekoKEMEncryptOptions options;
    NekoKEMEncryptResult encrypted;
    NekoKEMVerifyResult verified;
    char *second_end;
    int success = 0;

    if (!nekokem_generate_keypair("public.key", "v2.enc", bytes, password_len) ||
        !protected_private_key_read_version("v2.enc", bytes, password_len,
                                            &pem, &pem_len, &version) ||
        version != NKPR_VERSION_IDENTITY ||
        !protected_private_key_write_version("v3.enc", pem, pem_len, bytes,
                                             password_len,
                                             NKPR_VERSION_COMMITTED)) {
        fprintf(stderr, "Cannot prepare the NKPR v3 identity key\n");
        goto cleanup;
    }
    if (file_size("v3.enc") != file_size("v2.enc") + (long)NKPR_COMMITMENT_SIZE ||
        nekokem_private_key_exists("v3.enc") != 1 ||
        nekokem_private_key_requires_password("v3.enc") != 1 ||
        nekokem_check_private_key_password("v3.enc", bytes, password_len) != 1 ||
        nekokem_check_private_key_password(
            "v3.enc", (const unsigned char *)"wrong", 5U) != 0 ||
        !nekokem_internal_private_key_fingerprint(
            "v2.enc", bytes, password_len, v2_fingerprint,
            sizeof(v2_fingerprint)) ||
        !nekokem_internal_private_key_fingerprint(
            "v3.enc", bytes, password_len, v3_fingerprint,
            sizeof(v3_fingerprint)) ||
        strcmp(v2_fingerprint, v3_fingerprint) != 0) {
        fprintf(stderr, "The NKPR v3 identity key is not read like NKPR v2\n");
        goto cleanup;
    }

    /* Signs with and decrypts with the v3 key. */
    memset(&options, 0, sizeof(options));
    memset(&encrypted, 0, sizeof(encrypted));
    memset(&verified, 0, sizeof(verified));
    options.struct_size = sizeof(options);
    options.sender_private_key_path = "v3.enc";
    options.password = bytes;
    options.password_len = password_len;
    encrypted.struct_size = sizeof(encrypted);
    verified.struct_size = sizeof(verified);
    if (!write_text("plain.txt", "signed with an NKPR v3 key\n") ||
        nekokem_encrypt_file_v5_with_progress(
            "plain.txt", "file.nkem", recipients, 1U, &options, &encrypted,
            NULL, NULL) != NEKOKEM_OPERATION_SUCCESS ||
        encrypted.format_version != 5 ||
        nekokem_decrypt_file_verified_with_progress(
            "file.nkem", "restored.txt", "v3.enc", bytes, password_len, NULL,
            &verified, NULL, NULL) != NEKOKEM_OPERATION_SUCCESS ||
        verified.signature_status != NEKOKEM_SIGNATURE_VALID_UNTRUSTED ||
        strcmp(verified.signer_fingerprint, v3_fingerprint) != 0 ||
        !files_equal("plain.txt", "restored.txt")) {
        fprintf(stderr, "Signing or decrypting with the NKPR v3 key failed\n");
        goto cleanup;
    }

    /* NKPR v3 always holds four keys: two-component PEM in v3 is refused,
     * while the same PEM stays valid as NKPR v1. */
    text = OPENSSL_zalloc(pem_len + 1U);
    if (text == NULL) {
        goto cleanup;
    }
    memcpy(text, pem, pem_len);
    second_end = strstr(text, "-----END PRIVATE KEY-----");
    second_end = second_end == NULL
                     ? NULL
                     : strstr(second_end + 1, "-----END PRIVATE KEY-----");
    if (second_end == NULL) {
        fprintf(stderr, "Unexpected identity private-key PEM\n");
        goto cleanup;
    }
    legacy_len = (size_t)(second_end - text) +
                 strlen("-----END PRIVATE KEY-----\n");
    if (!protected_private_key_write_version("legacy-v1.enc", pem, legacy_len,
                                             bytes, password_len,
                                             NKPR_VERSION) ||
        !protected_private_key_write_version("legacy-v3.enc", pem, legacy_len,
                                             bytes, password_len,
                                             NKPR_VERSION_COMMITTED) ||
        nekokem_check_private_key_password("legacy-v1.enc", bytes,
                                           password_len) != 1 ||
        nekokem_check_private_key_password("legacy-v3.enc", bytes,
                                           password_len) != 0) {
        fprintf(stderr, "NKPR v3 accepted a two-component key\n");
        goto cleanup;
    }
    success = 1;

cleanup:
    if (text != NULL) {
        secure_free(text, pem_len + 1U);
    }
    secure_free(pem, pem_len);
    return success;
}

int main(void)
{
    static const char *const files[] = {
        "public.key", "v2.enc", "v3.enc", "plain.txt", "file.nkem",
        "restored.txt", "legacy-v1.enc", "legacy-v3.enc", ".nekokem-pair.lock",
    };
    char directory[] = "/tmp/nekokem-nkpr-v3.XXXXXX";
    size_t index;
    int success;

    if (mkdtemp(directory) == NULL || chdir(directory) != 0) {
        perror("Cannot create test directory");
        return EXIT_FAILURE;
    }
    success = known_answer_and_commitment() && identity_key_as_v3();
    for (index = 0U; index < sizeof(files) / sizeof(files[0]); ++index) {
        (void)remove(files[index]);
    }
    if (chdir("/") == 0) {
        (void)rmdir(directory);
    }
    if (!success) {
        fprintf(stderr, "NKPR v3 tests failed\n");
        return EXIT_FAILURE;
    }
    puts("NKPR v3 tests passed");
    return EXIT_SUCCESS;
}
