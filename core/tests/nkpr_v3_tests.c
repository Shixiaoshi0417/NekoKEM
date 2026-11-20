/*
 * NKPR v3 key commitment (docs/key-commitment.md): known-answer vectors from
 * an independent implementation (nkpr_v3_vectors.py), the commitment check
 * before AES-GCM, exact container lengths, new keys written as v3, and the
 * rule that NKPR v1 and v2 open only as the installation's own key, which
 * can be rewritten as v3 or exported back to v2. A crafted NKPR v2 file that
 * opens under two passwords shows the attack the rule exists for.
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

static int nkpr_version_of(const char *path)
{
    unsigned char header[5] = {0};
    FILE *input = fopen(path, "rb");
    int version = -1;

    if (input != NULL && fread(header, 1U, sizeof(header), input) == sizeof(header) &&
        memcmp(header, NKPR_MAGIC, 4U) == 0) {
        version = header[4];
    }
    if (input != NULL) {
        fclose(input);
    }
    return version;
}

static int fingerprint_of(const char *public_path,
                          char output[NEKOKEM_FINGERPRINT_STRING_SIZE])
{
    return nekokem_public_key_fingerprint(public_path, output,
                                          NEKOKEM_FINGERPRINT_STRING_SIZE);
}

/*
 * New keys are NKPR v3 and work wherever Core reads keys. NKPR v1 and v2 are
 * refused before the password is used unless the call says the key is the
 * installation's own, and such a key can be rewritten as v3 or exported back.
 */
/*
 * From nkpr_v3_vectors.py: an NKPR v2 container whose AES-GCM tag verifies
 * under the Argon2id keys of both passwords. Its plaintext is not a key, but
 * whether the tag verified is observable through timing, so one unlock
 * attempt tests two candidates.
 */
static const char two_password_v2[] =
    "4e4b5052020101000001000000000003000000040000001300200c10000000000000"
    "0010202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
    "c8c9cacbcccdcecfd0d1d2d325c5a5ee6ba66af45a0b061d26063ea29158192bd2"
    "3891ccf7941e5320a496f4";
static const char *const two_passwords[] = {
    "first candidate password", "second candidate password"};

static int opens_as_v2(const unsigned char *container, size_t length,
                       const char *password)
{
    unsigned char *pem = NULL;
    size_t pem_len = 0U;
    unsigned int version = 0U;
    int result = protected_private_key_decode_version(
        container, length, (const unsigned char *)password, strlen(password),
        &pem, &pem_len, &version);

    secure_free(pem, pem_len);
    return result != 0 && version == NKPR_VERSION_IDENTITY;
}

static int write_private_bytes(const char *path, const unsigned char *bytes,
                               size_t length)
{
    FILE *output = fopen(path, "wb");
    int success = output != NULL && chmod(path, 0600) == 0 &&
                  fwrite(bytes, 1U, length, output) == length;

    if (output != NULL && fclose(output) != 0) {
        success = 0;
    }
    return success;
}

static int crafted_two_password_v2(void)
{
    unsigned char container[sizeof(two_password_v2) / 2U];
    unsigned char relabelled[sizeof(container) + NKPR_COMMITMENT_SIZE];
    const size_t length = sizeof(container);
    const size_t commitment_at = NKPR_HEADER_SIZE + NKPR_SALT_SIZE;
    char fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE];
    size_t index;

    CHECK(from_hex(two_password_v2, container, length) == length);
    for (index = 0U; index < 2U; ++index) {
        const char *password = two_passwords[index];
        const unsigned char *bytes = (const unsigned char *)password;

        /* The attack: AES-GCM accepts the same file under both passwords. */
        CHECK(opens_as_v2(container, length, password));
        CHECK(write_private_bytes("crafted-v2.enc", container, length));
        /* Every path that is not the own key refuses it before Argon2id. */
        CHECK(nekokem_check_private_key_password_flags(
                  "crafted-v2.enc", bytes, strlen(password), 0U) ==
              NEKOKEM_ERROR_LEGACY_PRIVATE_KEY);
        CHECK(nekokem_internal_private_key_fingerprint(
                  "crafted-v2.enc", bytes, strlen(password), 0U, fingerprint,
                  sizeof(fingerprint)) == NEKOKEM_ERROR_LEGACY_PRIVATE_KEY);
        /* Upgrading or exporting a key that is not the own one, too. */
        CHECK(nekokem_update_private_key_format(
                  "crafted.pub", "crafted-v2.enc", bytes, strlen(password),
                  0U) == NEKOKEM_ERROR_LEGACY_PRIVATE_KEY);
        CHECK(nekokem_export_private_key_legacy(
                  "crafted-v2.enc", bytes, strlen(password),
                  "crafted-export.enc", 0U) ==
              NEKOKEM_ERROR_LEGACY_PRIVATE_KEY);
        CHECK(access("crafted-export.enc", F_OK) != 0);
        /* Accepted as an own key, the tag verifies but no key comes out. */
        CHECK(nekokem_check_private_key_password_flags(
                  "crafted-v2.enc", bytes, strlen(password),
                  NEKOKEM_PRIVATE_KEY_LEGACY) == 0);
    }

    /* Relabelled as NKPR v3 it no longer parses; with a commitment slot
     * added, the commitment matches neither password. */
    memcpy(relabelled, container, length);
    relabelled[4] = NKPR_VERSION_COMMITTED;
    CHECK(!protected_private_key_container_parse(relabelled, length));
    memcpy(relabelled, container, commitment_at);
    relabelled[4] = NKPR_VERSION_COMMITTED;
    memset(relabelled + commitment_at, 0, NKPR_COMMITMENT_SIZE);
    memcpy(relabelled + commitment_at + NKPR_COMMITMENT_SIZE,
           container + commitment_at, length - commitment_at);
    CHECK(protected_private_key_container_parse(relabelled,
                                                sizeof(relabelled)));
    for (index = 0U; index < 2U; ++index) {
        CHECK(!opens(relabelled, sizeof(relabelled), two_passwords[index],
                     NULL));
    }
    return 1;
}

static int identity_key_as_v3(void)
{
    static const char password[] = "nkpr v3 identity password";
    const unsigned char *bytes = (const unsigned char *)password;
    const unsigned char *wrong = (const unsigned char *)"wrong password";
    const size_t password_len = strlen(password);
    const char *recipients[1] = {"public.key"};
    char v2_fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE];
    char v3_fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE];
    char legacy_before[NEKOKEM_FINGERPRINT_STRING_SIZE];
    char legacy_after[NEKOKEM_FINGERPRINT_STRING_SIZE];
    unsigned char *pem = NULL;
    size_t pem_len = 0U;
    char *text = NULL;
    size_t legacy_len;
    unsigned int version = 0U;
    NekoKEMEncryptOptions options;
    NekoKEMEncryptResult encrypted;
    NekoKEMVerifyPolicy own_key;
    NekoKEMVerifyResult verified;
    char *second_end;
    int success = 0;

    /* Key generation writes NKPR v3; an NKPR v2 copy holds the same keys. */
    CHECK(nekokem_generate_keypair("public.key", "v3.enc", bytes, password_len));
    CHECK(nkpr_version_of("v3.enc") == (int)NKPR_VERSION_COMMITTED);
    if (!protected_private_key_read_version("v3.enc", bytes, password_len,
                                            &pem, &pem_len, &version) ||
        version != NKPR_VERSION_COMMITTED ||
        !protected_private_key_write_version("v2.enc", pem, pem_len, bytes,
                                             password_len,
                                             NKPR_VERSION_IDENTITY)) {
        fprintf(stderr, "Cannot prepare the NKPR v2 copy\n");
        goto cleanup;
    }
    if (nekokem_private_key_exists("v3.enc") != 1 ||
        nekokem_private_key_requires_password("v3.enc") != 1 ||
        nekokem_private_key_is_legacy("v3.enc") != 0 ||
        nekokem_private_key_is_legacy("v2.enc") != 1 ||
        nekokem_private_key_is_legacy("public.key") != 0 ||
        nekokem_check_private_key_password("v3.enc", bytes, password_len) != 1 ||
        nekokem_check_private_key_password("v3.enc", wrong, 14U) != 0 ||
        nekokem_internal_private_key_fingerprint(
            "v2.enc", bytes, password_len, NEKOKEM_PRIVATE_KEY_LEGACY,
            v2_fingerprint, sizeof(v2_fingerprint)) != 1 ||
        nekokem_internal_private_key_fingerprint(
            "v3.enc", bytes, password_len, 0U, v3_fingerprint,
            sizeof(v3_fingerprint)) != 1 ||
        strcmp(v2_fingerprint, v3_fingerprint) != 0) {
        fprintf(stderr, "The NKPR v3 key is not read like NKPR v2\n");
        goto cleanup;
    }

    /* NKPR v2 needs the legacy flag, and is refused even for a wrong
     * password: the format is checked before the password is used. */
    if (nekokem_check_private_key_password("v2.enc", bytes, password_len) != 0 ||
        nekokem_check_private_key_password_flags(
            "v2.enc", bytes, password_len, 0U) != NEKOKEM_ERROR_LEGACY_PRIVATE_KEY ||
        nekokem_check_private_key_password_flags(
            "v2.enc", wrong, 14U, 0U) != NEKOKEM_ERROR_LEGACY_PRIVATE_KEY ||
        nekokem_check_private_key_password_flags(
            "v2.enc", bytes, password_len, NEKOKEM_PRIVATE_KEY_LEGACY) != 1 ||
        nekokem_check_private_key_password_flags(
            "v2.enc", wrong, 14U, NEKOKEM_PRIVATE_KEY_LEGACY) != 0 ||
        nekokem_check_private_key_password_flags(
            "v3.enc", bytes, password_len, 0x80U) != 0 ||
        nekokem_internal_private_key_fingerprint(
            "v2.enc", bytes, password_len, 0U, v2_fingerprint,
            sizeof(v2_fingerprint)) != NEKOKEM_ERROR_LEGACY_PRIVATE_KEY ||
        v2_fingerprint[0] != '\0' ||
        nekokem_internal_private_key_fingerprint(
            "v2.enc", wrong, 14U, 0U, v2_fingerprint,
            sizeof(v2_fingerprint)) != NEKOKEM_ERROR_LEGACY_PRIVATE_KEY ||
        nekokem_internal_private_key_fingerprint(
            "v3.enc", bytes, password_len, 0x80U, v2_fingerprint,
            sizeof(v2_fingerprint)) != 0) {
        fprintf(stderr, "The legacy rule for checking passwords is wrong\n");
        goto cleanup;
    }

    /* Signs with the v3 key, and with the v2 key only as the own key. */
    memset(&options, 0, sizeof(options));
    memset(&encrypted, 0, sizeof(encrypted));
    options.struct_size = sizeof(options);
    options.sender_private_key_path = "v3.enc";
    options.password = bytes;
    options.password_len = password_len;
    encrypted.struct_size = sizeof(encrypted);
    CHECK(write_text("plain.txt", "signed with an NKPR v3 key\n"));
    CHECK(nekokem_encrypt_file_v5_with_progress(
              "plain.txt", "file.nkem", recipients, 1U, &options, &encrypted,
              NULL, NULL) == NEKOKEM_OPERATION_SUCCESS &&
          encrypted.format_version == 5);
    options.sender_private_key_path = "v2.enc";
    CHECK(nekokem_encrypt_file_v5_with_progress(
              "plain.txt", "legacy.nkem", recipients, 1U, &options, &encrypted,
              NULL, NULL) == NEKOKEM_ERROR_LEGACY_PRIVATE_KEY);
    CHECK(access("legacy.nkem", F_OK) != 0);
    options.flags = NEKOKEM_ENCRYPT_LEGACY_SENDER_KEY;
    CHECK(nekokem_encrypt_file_v5_with_progress(
              "plain.txt", "legacy.nkem", recipients, 1U, &options, &encrypted,
              NULL, NULL) == NEKOKEM_OPERATION_SUCCESS);

    /* Decrypts with the v3 key; the v2 key needs the own-key flag. */
    memset(&verified, 0, sizeof(verified));
    verified.struct_size = sizeof(verified);
    CHECK(nekokem_decrypt_file_verified_with_progress(
              "file.nkem", "restored.txt", "v3.enc", bytes, password_len, NULL,
              &verified, NULL, NULL) == NEKOKEM_OPERATION_SUCCESS &&
          verified.signature_status == NEKOKEM_SIGNATURE_VALID_UNTRUSTED &&
          strcmp(verified.signer_fingerprint, v3_fingerprint) == 0 &&
          files_equal("plain.txt", "restored.txt"));
    CHECK(remove("restored.txt") == 0);
    CHECK(nekokem_decrypt_file_verified_with_progress(
              "legacy.nkem", "restored.txt", "v2.enc", bytes, password_len,
              NULL, &verified, NULL, NULL) == NEKOKEM_ERROR_LEGACY_PRIVATE_KEY);
    CHECK(access("restored.txt", F_OK) != 0);
    CHECK(nekokem_decrypt_file("legacy.nkem", "restored.txt", "v2.enc", bytes,
                               password_len) == 0);
    memset(&own_key, 0, sizeof(own_key));
    own_key.struct_size = sizeof(own_key);
    own_key.flags = NEKOKEM_VERIFY_LEGACY_PRIVATE_KEY;
    CHECK(nekokem_decrypt_file_verified_with_progress(
              "legacy.nkem", "restored.txt", "v2.enc", bytes, password_len,
              &own_key, &verified, NULL, NULL) == NEKOKEM_OPERATION_SUCCESS &&
          files_equal("plain.txt", "restored.txt"));

    /* Exported for an older installation: NKPR v2 with the same password.
     * An NKPR v3 input needs no flag. */
    CHECK(nekokem_export_private_key_legacy("v3.enc", bytes, password_len,
                                            "exported.enc", 0U) == 1);
    CHECK(nkpr_version_of("exported.enc") == (int)NKPR_VERSION_IDENTITY);
    CHECK(nekokem_check_private_key_password_flags(
              "exported.enc", bytes, password_len,
              NEKOKEM_PRIVATE_KEY_LEGACY) == 1);
    CHECK(nekokem_export_private_key_legacy("v3.enc", bytes, password_len,
                                            "v3.enc", 0U) == 0);
    CHECK(nekokem_export_private_key_legacy("v3.enc", wrong, 14U,
                                            "wrong.enc", 0U) == 0);
    CHECK(access("wrong.enc", F_OK) != 0);
    CHECK(nekokem_export_private_key_legacy("v3.enc", bytes, password_len,
                                            "flags.enc", 0x2U) == 0);
    CHECK(access("flags.enc", F_OK) != 0);
    /* An NKPR v2 input opens only as the own key. */
    CHECK(nekokem_export_private_key_legacy("exported.enc", bytes,
                                            password_len, "again.enc", 0U) ==
          NEKOKEM_ERROR_LEGACY_PRIVATE_KEY);
    CHECK(access("again.enc", F_OK) != 0);
    CHECK(nekokem_export_private_key_legacy("exported.enc", bytes,
                                            password_len, "again.enc",
                                            NEKOKEM_PRIVATE_KEY_LEGACY) == 1);
    CHECK(nkpr_version_of("again.enc") == (int)NKPR_VERSION_IDENTITY);

    /* An NKPR v2 key is rewritten only as the own key, keeping its keys. */
    CHECK(nekokem_update_private_key_format("public.key", "v2.enc", bytes,
                                            password_len, 0U) ==
          NEKOKEM_ERROR_LEGACY_PRIVATE_KEY);
    CHECK(nkpr_version_of("v2.enc") == (int)NKPR_VERSION_IDENTITY);
    CHECK(nekokem_update_private_key_format("public.key", "v2.enc", bytes,
                                            password_len, 0x2U) == 0);
    CHECK(nkpr_version_of("v2.enc") == (int)NKPR_VERSION_IDENTITY);
    CHECK(nekokem_update_private_key_format("public.key", "v2.enc", bytes,
                                            password_len,
                                            NEKOKEM_PRIVATE_KEY_LEGACY) == 1);
    CHECK(nkpr_version_of("v2.enc") == (int)NKPR_VERSION_COMMITTED);
    CHECK(fingerprint_of("public.key", v2_fingerprint) &&
          strcmp(v2_fingerprint, v3_fingerprint) == 0);
    CHECK(nekokem_check_private_key_password("v2.enc", bytes, password_len) == 1);
    /* An NKPR v3 key is not written again: its bytes stay the same. */
    {
        FILE *source = fopen("v3.enc", "rb");
        FILE *copy = fopen("v3-before.enc", "wb");
        int byte;
        int copied = source != NULL && copy != NULL;

        while (copied && (byte = fgetc(source)) != EOF) {
            copied = fputc(byte, copy) != EOF;
        }
        if (source != NULL) {
            fclose(source);
        }
        if (copy != NULL && fclose(copy) != 0) {
            copied = 0;
        }
        CHECK(copied);
    }
    CHECK(nekokem_update_private_key_format("public.key", "v3.enc", bytes,
                                            password_len, 0U) == 1);
    CHECK(files_equal("v3.enc", "v3-before.enc"));
    /* Success on an NKPR v3 key still means the password and the pair are
     * right: a wrong password or another public key fails. */
    CHECK(nekokem_update_private_key_format("public.key", "v3.enc", wrong,
                                            14U, 0U) == 0);
    CHECK(nekokem_generate_keypair("other.pub", "other.enc", bytes,
                                   password_len));
    CHECK(nekokem_update_private_key_format("other.pub", "v3.enc", bytes,
                                            password_len, 0U) == 0);
    CHECK(files_equal("v3.enc", "v3-before.enc"));
    CHECK(nekokem_update_private_key_format("public.key", "exported.enc",
                                            wrong, 14U,
                                            NEKOKEM_PRIVATE_KEY_LEGACY) == 0);
    CHECK(nkpr_version_of("exported.enc") == (int)NKPR_VERSION_IDENTITY);

    /* NKPR v3 always holds four keys: two-component PEM in v3 is refused,
     * while the same PEM stays valid as NKPR v1, which is upgraded to an
     * identity key when its format is updated. */
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
        nekokem_check_private_key_password_flags(
            "legacy-v1.enc", bytes, password_len,
            NEKOKEM_PRIVATE_KEY_LEGACY) != 1 ||
        nekokem_check_private_key_password("legacy-v3.enc", bytes,
                                           password_len) != 0 ||
        !nekokem_export_legacy_public_key("public.key", "legacy.pub") ||
        !nekokem_public_key_legacy_fingerprint("legacy.pub", legacy_before,
                                               sizeof(legacy_before))) {
        fprintf(stderr, "NKPR v3 accepted a two-component key\n");
        goto cleanup;
    }
    /* A 4.x key at another path is refused; as the own key it is upgraded. */
    CHECK(nekokem_update_private_key_format("legacy.pub", "legacy-v1.enc",
                                            bytes, password_len, 0U) ==
          NEKOKEM_ERROR_LEGACY_PRIVATE_KEY);
    CHECK(nkpr_version_of("legacy-v1.enc") == (int)NKPR_VERSION);
    CHECK(nekokem_public_key_can_verify("legacy.pub") == 0);
    CHECK(nekokem_update_private_key_format("legacy.pub", "legacy-v1.enc",
                                            bytes, password_len,
                                            NEKOKEM_PRIVATE_KEY_LEGACY) == 1);
    CHECK(nkpr_version_of("legacy-v1.enc") == (int)NKPR_VERSION_COMMITTED);
    CHECK(nekokem_public_key_can_verify("legacy.pub") == 1);
    CHECK(nekokem_public_key_legacy_fingerprint("legacy.pub", legacy_after,
                                                sizeof(legacy_after)) &&
          strcmp(legacy_before, legacy_after) == 0);
    CHECK(nekokem_check_private_key_password("legacy-v1.enc", bytes,
                                             password_len) == 1);
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
        "legacy.nkem", "restored.txt", "exported.enc", "wrong.enc",
        "legacy-v1.enc", "legacy-v3.enc", "legacy.pub", "other.pub",
        "other.enc", "crafted-v2.enc", ".nekokem-pair.lock",
    };
    char directory[] = "/tmp/nekokem-nkpr-v3.XXXXXX";
    size_t index;
    int success;

    if (mkdtemp(directory) == NULL || chdir(directory) != 0) {
        perror("Cannot create test directory");
        return EXIT_FAILURE;
    }
    success = known_answer_and_commitment() && crafted_two_password_v2() &&
              identity_key_as_v3();
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
