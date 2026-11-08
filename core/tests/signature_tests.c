/* NKEM v5 signed containers, identity keys and Core-enforced policy. */
#include "nekokem.h"
#include "file.h"
#include "hybrid.h"
#include "identity.h"
#include "nekokem_multi.h"
#include "private_key.h"

#include <openssl/bio.h>
#include <openssl/buffer.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifndef _WIN32
#include <unistd.h>
#endif

#define CHECK(condition)                                                  \
    do {                                                                  \
        if (!(condition)) {                                               \
            fprintf(stderr, "Check failed at line %d: %s\n", __LINE__,    \
                    #condition);                                          \
            return 0;                                                     \
        }                                                                 \
    } while (0)

#define DATA_SIZE (2U * 65536U + 777U)
#define OVERHEAD 9204U
#define SIG_KEY_SIZE 32U
/* Offsets inside the identity encoding (docs/NKEM-v5.md). */
#define ID_X448_RAW 9U
#define ID_MLKEM_HEADER 65U
#define ID_MLKEM_RAW 68U
#define ID_ED448_HEADER 1636U
#define ID_ED448_RAW 1639U
#define ID_MLDSA_HEADER 1696U

static const unsigned char password[] = "public-test-signature";
static const NkemMultiLabels v5_labels = {
    "NekoKEM v5 recipient wrap X448-MLKEM1024",
    "NekoKEM v5 payload AES-256-GCM",
    "NekoKEM v5 header HMAC-SHA512",
};
static const char signature_key_label[] =
    "NekoKEM v5 signature block AES-256-GCM";
static const char message_label[] = "NekoKEM v5 sender signature";

static const char *const fixtures[] = {
    "a.pub", "a.key", "b.pub", "b.key", "c.pub", "c.key", "d.pub", "d.key",
    "e.pub", "e.key", "plain.bin", "empty.bin", "a-legacy.pub", "signer.pub",
    "v5.nkem", "v5-multi.nkem", "v5-empty.nkem", "v3.nkem", "v4.nkem",
    "old-v4.nkem", "bad.nkem", "out.bin", "upgraded.nkem", "legacy-to.nkem",
    "b-key.copy", "c-pub.copy", "b-hard.key", "b-link.key", "here",
    "big.bin", "big.nkem",
};

static int write_bytes(const char *path, const void *bytes, size_t length)
{
    AtomicFile output = {0};
    int success = atomic_file_open(&output, path, 0600) &&
                  file_write_all(output.stream, bytes, length) &&
                  atomic_file_commit(&output);

    atomic_file_abort(&output);
    return success;
}

/* The payload digest of docs/NKEM-v5.md, written out independently of Core. */
static int payload_digest(const unsigned char *ciphertext, size_t length,
                          const unsigned char tag[NKEM_TAG_SIZE],
                          unsigned char digest[64])
{
    static const char leaf_label[] = "NekoKEM v5 payload leaf";
    static const char root_label[] = "NekoKEM v5 payload root";
    const size_t leaf_size = 1048576U;
    EVP_MD_CTX *root = EVP_MD_CTX_new();
    EVP_MD_CTX *leaf = EVP_MD_CTX_new();
    unsigned char encoded[8];
    unsigned char leaf_digest[64];
    unsigned int digest_len = 0U;
    uint64_t index = 0U;
    size_t offset;
    size_t shift;
    int success = root != NULL && leaf != NULL;

    for (shift = 0U; shift < 8U; ++shift) {
        encoded[7U - shift] = (unsigned char)((uint64_t)length >> (8U * shift));
    }
    success = success && EVP_DigestInit_ex(root, EVP_sha512(), NULL) == 1 &&
              EVP_DigestUpdate(root, root_label, sizeof(root_label) - 1U) == 1 &&
              EVP_DigestUpdate(root, encoded, sizeof(encoded)) == 1;
    for (offset = 0U; success && offset < length; offset += leaf_size, ++index) {
        size_t chunk = length - offset < leaf_size ? length - offset : leaf_size;

        for (shift = 0U; shift < 8U; ++shift) {
            encoded[7U - shift] = (unsigned char)(index >> (8U * shift));
        }
        success = EVP_DigestInit_ex(leaf, EVP_sha512(), NULL) == 1 &&
                  EVP_DigestUpdate(leaf, leaf_label, sizeof(leaf_label) - 1U) == 1 &&
                  EVP_DigestUpdate(leaf, encoded, sizeof(encoded)) == 1 &&
                  EVP_DigestUpdate(leaf, ciphertext + offset, chunk) == 1 &&
                  EVP_DigestFinal_ex(leaf, leaf_digest, &digest_len) == 1 &&
                  EVP_DigestUpdate(root, leaf_digest, sizeof(leaf_digest)) == 1;
    }
    success = success && EVP_DigestUpdate(root, tag, NKEM_TAG_SIZE) == 1 &&
              EVP_DigestFinal_ex(root, digest, &digest_len) == 1 &&
              digest_len == 64U;
    EVP_MD_CTX_free(root);
    EVP_MD_CTX_free(leaf);
    return success;
}

static int read_all(const char *path, unsigned char **bytes, size_t *length)
{
    return file_read_regular(path, 64U * 1024U * 1024U, bytes, length);
}

static int exists(const char *path)
{
    FILE *stream = fopen(path, "rb");

    if (stream == NULL) {
        return 0;
    }
    (void)fclose(stream);
    return 1;
}

static int file_is_empty(const char *path)
{
    FILE *stream = fopen(path, "rb");
    int empty = stream != NULL && fgetc(stream) == EOF && ferror(stream) == 0;

    if (stream != NULL) {
        (void)fclose(stream);
    }
    return empty;
}

static int same_file(const char *left, const char *right)
{
    unsigned char *a = NULL;
    unsigned char *b = NULL;
    size_t a_len = 0U;
    size_t b_len = 0U;
    int equal;

    if (file_is_empty(left) || file_is_empty(right)) {
        return file_is_empty(left) && file_is_empty(right);
    }
    equal = read_all(left, &a, &a_len) && read_all(right, &b, &b_len) &&
            a_len == b_len && memcmp(a, b, a_len) == 0;
    OPENSSL_free(a);
    OPENSSL_free(b);
    return equal;
}

static size_t file_size(const char *path)
{
    unsigned char *bytes = NULL;
    size_t length = 0U;

    if (file_is_empty(path) || !read_all(path, &bytes, &length)) {
        return 0U;
    }
    OPENSSL_free(bytes);
    return length;
}

static size_t count_text(const char *path, const char *needle)
{
    unsigned char *bytes = NULL;
    size_t length = 0U;
    size_t count = 0U;
    size_t needle_len = strlen(needle);
    size_t index;

    if (!read_all(path, &bytes, &length)) {
        return 0U;
    }
    for (index = 0U; index + needle_len <= length; ++index) {
        if (memcmp(bytes + index, needle, needle_len) == 0) {
            ++count;
        }
    }
    OPENSSL_free(bytes);
    return count;
}

/*
 * Plaintext-PEM keys keep the suite fast; identity adds Ed448 and ML-DSA.
 * With protect set, the private key goes into NKPR v1 (two components).
 */
static int make_keypair(const char *public_path, const char *private_path,
                        int identity, int protect)
{
    const char *names[4] = {"X448", "ML-KEM-1024", "ED448", "ML-DSA-87"};
    EVP_PKEY *keys[4] = {NULL, NULL, NULL, NULL};
    BIO *public_bio = BIO_new(BIO_s_mem());
    BIO *private_bio = BIO_new(BIO_s_mem());
    BUF_MEM *public_pem = NULL;
    BUF_MEM *private_pem = NULL;
    size_t count = identity ? 4U : 2U;
    size_t index;
    int success = public_bio != NULL && private_bio != NULL;

    for (index = 0U; success && index < count; ++index) {
        keys[index] = EVP_PKEY_Q_keygen(NULL, NULL, names[index]);
        success = keys[index] != NULL &&
                  PEM_write_bio_PUBKEY(public_bio, keys[index]) == 1 &&
                  PEM_write_bio_PrivateKey(private_bio, keys[index], NULL,
                                           NULL, 0, NULL, NULL) == 1;
    }
    success = success && BIO_get_mem_ptr(public_bio, &public_pem) > 0 &&
              BIO_get_mem_ptr(private_bio, &private_pem) > 0 &&
              write_bytes(public_path, public_pem->data, public_pem->length) &&
              (protect ? protected_private_key_write(
                             private_path,
                             (const unsigned char *)private_pem->data,
                             private_pem->length, password,
                             sizeof(password) - 1U)
                       : write_bytes(private_path, private_pem->data,
                                     private_pem->length));
    if (private_pem != NULL) {
        OPENSSL_cleanse(private_pem->data, private_pem->max);
    }
    BIO_free(public_bio);
    BIO_free(private_bio);
    for (index = 0U; index < 4U; ++index) {
        EVP_PKEY_free(keys[index]);
    }
    return success;
}

static NekoKEMEncryptOptions sign_with(const char *private_path,
                                       int protected_key)
{
    NekoKEMEncryptOptions options;

    memset(&options, 0, sizeof(options));
    options.struct_size = sizeof(options);
    options.sender_private_key_path = private_path;
    options.password = protected_key ? password : NULL;
    options.password_len = protected_key ? sizeof(password) - 1U : 0U;
    return options;
}

static int encrypt_signed(const char *input, const char *output,
                          const char *const *keys, size_t count,
                          const char *sender, int protected_key)
{
    NekoKEMEncryptOptions options = sign_with(sender, protected_key);
    NekoKEMEncryptResult result = {sizeof(result), 0, 0};

    return nekokem_encrypt_file_v5_with_progress(input, output, keys, count,
                                                 &options, &result, NULL,
                                                 NULL) ==
                   NEKOKEM_OPERATION_SUCCESS &&
           result.format_version == 5 && result.signed_by_sender == 1;
}

static NekoKEMVerifyPolicy policy_of(uint32_t flags,
                                     const char *const *trusted,
                                     size_t trusted_count)
{
    NekoKEMVerifyPolicy policy;

    memset(&policy, 0, sizeof(policy));
    policy.struct_size = sizeof(policy);
    policy.flags = flags;
    policy.trusted_signer_paths = trusted;
    policy.trusted_signer_count = trusted_count;
    return policy;
}

static int verify(const char *input, const char *private_path,
                  int protected_key, const NekoKEMVerifyPolicy *policy,
                  NekoKEMVerifyResult *result)
{
    (void)remove("out.bin");
    memset(result, 0, sizeof(*result));
    result->struct_size = sizeof(*result);
    return nekokem_decrypt_file_verified_with_progress(
        input, "out.bin", private_path, protected_key ? password : NULL,
        protected_key ? sizeof(password) - 1U : 0U, policy, result, NULL,
        NULL);
}

static int fingerprint_of(const char *public_path, char *output)
{
    return nekokem_public_key_fingerprint(public_path, output,
                                          NEKOKEM_FINGERPRINT_STRING_SIZE);
}

typedef int (*BlockEdit)(unsigned char *plaintext, const unsigned char *message,
                         size_t message_len);

static const char *resign_key_path;

/* Re-signs as the identity in resign_key_path, like a recipient could. */
static int edit_resign(unsigned char *plaintext, const unsigned char *message,
                       size_t message_len)
{
    HybridKeys keys = {0};
    unsigned char *copy = NULL;
    size_t label_len = sizeof(message_label) - 1U;
    int success = hybrid_load_private_keys(resign_key_path, &keys) &&
                  identity_encode(&keys, plaintext);

    if (success) {
        copy = OPENSSL_memdup(message, message_len);
        success = copy != NULL;
    }
    if (success) {
        memcpy(copy + message_len - IDENTITY_ENCODING_SIZE, plaintext,
               IDENTITY_ENCODING_SIZE);
        success = memcmp(copy, message_label, label_len) == 0 &&
                  identity_sign(&keys, copy, message_len,
                                plaintext + IDENTITY_ENCODING_SIZE);
    }
    OPENSSL_free(copy);
    hybrid_keys_cleanup(&keys);
    return success;
}

static int edit_none(unsigned char *plaintext, const unsigned char *message,
                     size_t message_len)
{
    (void)plaintext;
    (void)message;
    (void)message_len;
    return 1;
}

static int edit_ed448_signature(unsigned char *plaintext,
                                const unsigned char *message,
                                size_t message_len)
{
    (void)message;
    (void)message_len;
    plaintext[IDENTITY_ENCODING_SIZE + 7U] ^= 0x01U;
    return 1;
}

static int edit_mldsa_signature(unsigned char *plaintext,
                                const unsigned char *message,
                                size_t message_len)
{
    (void)message;
    (void)message_len;
    plaintext[IDENTITY_ENCODING_SIZE + IDENTITY_ED448_SIGNATURE_SIZE + 99U] ^=
        0x80U;
    return 1;
}

/* Swaps in another identity and keeps the original signatures. */
static int edit_identity_swap(unsigned char *plaintext,
                              const unsigned char *message,
                              size_t message_len)
{
    HybridKeys keys = {0};
    int success = hybrid_load_public_keys("b.pub", &keys) &&
                  identity_encode(&keys, plaintext);

    (void)message;
    (void)message_len;
    hybrid_keys_cleanup(&keys);
    return success;
}

static int edit_identity_magic(unsigned char *plaintext,
                               const unsigned char *message,
                               size_t message_len)
{
    (void)message;
    (void)message_len;
    plaintext[0] ^= 0x20U;
    return 1;
}

static int gcm_block(int encrypt, const unsigned char key[SIG_KEY_SIZE],
                     const unsigned char *metadata, size_t metadata_len,
                     const unsigned char *payload_tag, const unsigned char *in,
                     unsigned char *out, unsigned char *tag)
{
    static const unsigned char nonce[NKEM_NONCE_SIZE] = {0};
    EVP_CIPHER_CTX *context = EVP_CIPHER_CTX_new();
    unsigned char final_block[EVP_MAX_BLOCK_LENGTH];
    int length = 0;
    int success = context != NULL &&
                  EVP_CipherInit_ex(context, EVP_aes_256_gcm(), NULL, key,
                                    nonce, encrypt) == 1 &&
                  EVP_CipherUpdate(context, NULL, &length, metadata,
                                   (int)metadata_len) == 1 &&
                  EVP_CipherUpdate(context, NULL, &length, payload_tag,
                                   (int)NKEM_TAG_SIZE) == 1 &&
                  EVP_CipherUpdate(context, out, &length, in,
                                   (int)NKEM_V5_SIGNATURE_PLAINTEXT_SIZE) == 1;

    if (success && !encrypt) {
        success = EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_SET_TAG,
                                      (int)NKEM_TAG_SIZE, tag) == 1;
    }
    success = success && EVP_CipherFinal_ex(context, final_block, &length) == 1;
    if (success && encrypt) {
        success = EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_GET_TAG,
                                      (int)NKEM_TAG_SIZE, tag) == 1;
    }
    EVP_CIPHER_CTX_free(context);
    return success;
}

/*
 * Acts as a malicious recipient: opens its entry, decrypts the signature
 * block, edits it, and re-encrypts it so only the signature checks remain.
 */
static int reseal(const char *source, const char *destination,
                  const char *recipient_key, BlockEdit edit)
{
    HybridKeys keys = {0};
    NkemV5Header header;
    unsigned char *bytes = NULL;
    unsigned char *message = NULL;
    unsigned char plaintext[NKEM_V5_SIGNATURE_PLAINTEXT_SIZE];
    unsigned char file_key[NKEM_V4_FILE_KEY_SIZE];
    unsigned char payload_key[AES256_KEY_SIZE];
    unsigned char sig_key[SIG_KEY_SIZE];
    unsigned char digest[64];
    size_t length = 0U;
    size_t metadata_len;
    size_t block_offset;
    size_t label_len = sizeof(message_label) - 1U;
    size_t message_len;
    int success = 0;

    if (!read_all(source, &bytes, &length) ||
        !nkem_v5_header_decode(bytes, &header) ||
        !hybrid_load_private_keys(recipient_key, &keys)) {
        goto cleanup;
    }
    metadata_len = (size_t)nkem_v5_metadata_size(header.recipient_count);
    block_offset = length - NKEM_V5_SIGNATURE_BLOCK_SIZE;
    message_len = label_len + metadata_len + sizeof(digest) +
                  IDENTITY_ENCODING_SIZE;
    message = OPENSSL_malloc(message_len);
    if (message == NULL ||
        !nkem_multi_open_metadata(&v5_labels, &keys, bytes,
                                  header.recipient_count, payload_key,
                                  file_key) ||
        !nkem_multi_hkdf_sha512(file_key, sizeof(file_key),
                                bytes + NKEM_V4_HEADER_SIZE,
                                (const unsigned char *)signature_key_label,
                                sizeof(signature_key_label) - 1U, sig_key,
                                sizeof(sig_key)) ||
        !gcm_block(0, sig_key, bytes, metadata_len,
                   bytes + block_offset - NKEM_TAG_SIZE, bytes + block_offset,
                   plaintext,
                   bytes + block_offset + NKEM_V5_SIGNATURE_PLAINTEXT_SIZE) ||
        !payload_digest(bytes + metadata_len,
                        block_offset - metadata_len - NKEM_TAG_SIZE,
                        bytes + block_offset - NKEM_TAG_SIZE, digest)) {
        goto cleanup;
    }
    memcpy(message, message_label, label_len);
    memcpy(message + label_len, bytes, metadata_len);
    memcpy(message + label_len + metadata_len, digest, sizeof(digest));
    memcpy(message + label_len + metadata_len + sizeof(digest), plaintext,
           IDENTITY_ENCODING_SIZE);
    success = edit(plaintext, message, message_len) &&
              gcm_block(1, sig_key, bytes, metadata_len,
                        bytes + block_offset - NKEM_TAG_SIZE, plaintext,
                        bytes + block_offset,
                        bytes + block_offset +
                            NKEM_V5_SIGNATURE_PLAINTEXT_SIZE) &&
              write_bytes(destination, bytes, length);

cleanup:
    hybrid_keys_cleanup(&keys);
    OPENSSL_free(bytes);
    OPENSSL_free(message);
    return success;
}

static int mutate(const char *source, const char *destination, size_t offset,
                  unsigned char mask, long resize)
{
    unsigned char *bytes = NULL;
    size_t length = 0U;
    int success = 0;

    if (!read_all(source, &bytes, &length)) {
        return 0;
    }
    if (resize < 0) {
        length -= (size_t)(-resize);
    } else if (resize > 0) {
        unsigned char *grown = OPENSSL_realloc(bytes, length + (size_t)resize);

        if (grown == NULL) {
            goto cleanup;
        }
        bytes = grown;
        memset(bytes + length, 0, (size_t)resize);
        length += (size_t)resize;
    }
    if (offset < length) {
        bytes[offset] ^= mask;
    }
    success = write_bytes(destination, bytes, length);

cleanup:
    OPENSSL_free(bytes);
    return success;
}

static int test_identity_keys(void)
{
    unsigned char *bytes = NULL;
    size_t length = 0U;
    char identity[NEKOKEM_FINGERPRINT_STRING_SIZE];
    char legacy[NEKOKEM_FINGERPRINT_STRING_SIZE];
    int nkpr_version;

    CHECK(nekokem_generate_keypair("a.pub", "a.key", password,
                                   sizeof(password) - 1U));
    CHECK(count_text("a.pub", "-----BEGIN PUBLIC KEY-----") == 4U);
    CHECK(read_all("a.key", &bytes, &length));
    nkpr_version = length > 4U && memcmp(bytes, NKPR_MAGIC, 4U) == 0
                       ? bytes[4]
                       : -1;
    OPENSSL_free(bytes);
    CHECK(nkpr_version == (int)NKPR_VERSION_IDENTITY);
    CHECK(nekokem_public_key_can_verify("a.pub") == 1);
    CHECK(fingerprint_of("a.pub", identity));
    CHECK(strlen(identity) == 95U);
    CHECK(nekokem_public_key_legacy_fingerprint("a.pub", legacy,
                                                sizeof(legacy)));
    CHECK(strcmp(identity, legacy) != 0);

    CHECK(make_keypair("b.pub", "b.key", 1, 0));
    CHECK(make_keypair("c.pub", "c.key", 0, 0));
    CHECK(nekokem_public_key_can_verify("b.pub") == 1);
    CHECK(nekokem_public_key_can_verify("c.pub") == 0);
    CHECK(nekokem_public_key_can_verify("missing.pub") == 0);
    CHECK(fingerprint_of("c.pub", identity));
    CHECK(nekokem_public_key_legacy_fingerprint("c.pub", legacy,
                                                sizeof(legacy)));
    CHECK(strcmp(identity, legacy) == 0);

    /* The legacy export drops the signing keys and keeps the old identity. */
    CHECK(nekokem_export_legacy_public_key("a.pub", "a-legacy.pub"));
    CHECK(count_text("a-legacy.pub", "-----BEGIN PUBLIC KEY-----") == 2U);
    CHECK(nekokem_public_key_can_verify("a-legacy.pub") == 0);
    CHECK(fingerprint_of("a-legacy.pub", identity));
    CHECK(nekokem_public_key_legacy_fingerprint("a.pub", legacy,
                                                sizeof(legacy)));
    CHECK(strcmp(identity, legacy) == 0);
    return 1;
}

static int test_round_trips(void)
{
    unsigned char data[DATA_SIZE];
    const char *one[] = {"b.pub"};
    const char *many[] = {"b.pub", "c.pub", "a-legacy.pub"};
    const char *trusted[] = {"c.pub", "a.pub"};
    char expected[NEKOKEM_FINGERPRINT_STRING_SIZE];
    NekoKEMVerifyPolicy policy;
    NekoKEMVerifyResult result;
    size_t index;

    for (index = 0U; index < sizeof(data); ++index) {
        data[index] = (unsigned char)(index * 131U + 7U);
    }
    CHECK(write_bytes("plain.bin", data, sizeof(data)));
    CHECK(write_bytes("empty.bin", data, 0U));
    CHECK(fingerprint_of("a.pub", expected));

    CHECK(encrypt_signed("plain.bin", "v5.nkem", one, 1U, "a.key", 1));
    CHECK(file_size("v5.nkem") == OVERHEAD + NKEM_V4_ENTRY_SIZE + DATA_SIZE);
    CHECK(verify("v5.nkem", "b.key", 0, NULL, &result) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(same_file("out.bin", "plain.bin"));
    CHECK(result.format_version == 5);
    CHECK(result.signature_status == NEKOKEM_SIGNATURE_VALID_UNTRUSTED);
    CHECK(strcmp(result.signer_fingerprint, expected) == 0);

    /* The old API verifies the signature completely too. */
    (void)remove("out.bin");
    CHECK(nekokem_decrypt_file("v5.nkem", "out.bin", "b.key", NULL, 0U));
    CHECK(same_file("out.bin", "plain.bin"));

    /* A two-component trusted key is ignored, not an error. */
    policy = policy_of(NEKOKEM_VERIFY_REQUIRE_TRUSTED, trusted, 2U);
    CHECK(verify("v5.nkem", "b.key", 0, &policy, &result) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(result.signature_status == NEKOKEM_SIGNATURE_TRUSTED);
    CHECK(result.trusted_signer_index == 1U);

    policy = policy_of(NEKOKEM_VERIFY_REQUIRE_TRUSTED, trusted, 1U);
    CHECK(verify("v5.nkem", "b.key", 0, &policy, &result) ==
          NEKOKEM_ERROR_SIGNER_NOT_TRUSTED);
    CHECK(!exists("out.bin"));
    policy = policy_of(NEKOKEM_VERIFY_REQUIRE_TRUSTED, NULL, 0U);
    CHECK(verify("v5.nkem", "b.key", 0, &policy, &result) ==
          NEKOKEM_ERROR_SIGNER_NOT_TRUSTED);
    CHECK(!exists("out.bin"));

    /* Without REQUIRE_TRUSTED an unlisted signer is reported, not refused. */
    policy = policy_of(NEKOKEM_VERIFY_REQUIRE_SIGNATURE, one, 1U);
    CHECK(verify("v5.nkem", "b.key", 0, &policy, &result) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(result.signature_status == NEKOKEM_SIGNATURE_VALID_UNTRUSTED);

    /* A broken trusted entry fails before anything is decrypted. */
    trusted[0] = "missing.pub";
    policy = policy_of(0U, trusted, 2U);
    CHECK(verify("v5.nkem", "b.key", 0, &policy, &result) ==
          NEKOKEM_OPERATION_ERROR);
    CHECK(!exists("out.bin"));
    trusted[0] = "c.pub";

    policy = policy_of(0U, NULL, 0U);
    policy.signer_public_key_output_path = "signer.pub";
    CHECK(verify("v5.nkem", "b.key", 0, &policy, &result) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(nekokem_public_key_can_verify("signer.pub") == 1);
    CHECK(fingerprint_of("signer.pub", expected));
    CHECK(strcmp(result.signer_fingerprint, expected) == 0);

    /* Several recipients, including legacy two-component keys. */
    CHECK(encrypt_signed("plain.bin", "v5-multi.nkem", many, 3U, "a.key", 1));
    CHECK(file_size("v5-multi.nkem") ==
          OVERHEAD + 3U * NKEM_V4_ENTRY_SIZE + DATA_SIZE);
    CHECK(verify("v5-multi.nkem", "b.key", 0, NULL, &result) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(same_file("out.bin", "plain.bin"));
    CHECK(verify("v5-multi.nkem", "c.key", 0, NULL, &result) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(same_file("out.bin", "plain.bin"));
    CHECK(verify("v5-multi.nkem", "a.key", 1, NULL, &result) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(same_file("out.bin", "plain.bin"));

    CHECK(encrypt_signed("empty.bin", "v5-empty.nkem", one, 1U, "b.key", 0));
    CHECK(file_size("v5-empty.nkem") == OVERHEAD + NKEM_V4_ENTRY_SIZE);
    CHECK(verify("v5-empty.nkem", "b.key", 0, NULL, &result) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(file_is_empty("out.bin"));
    CHECK(verify("v5-empty.nkem", "c.key", 0, NULL, &result) ==
          NEKOKEM_OPERATION_ERROR);
    CHECK(!exists("out.bin"));
    return 1;
}

static int copy_file(const char *source, const char *destination)
{
    unsigned char *bytes = NULL;
    size_t length = 0U;
    int success = read_all(source, &bytes, &length) &&
                  write_bytes(destination, bytes, length);

    OPENSSL_free(bytes);
    return success;
}

/* Fails, leaves out.bin absent and every key unchanged. */
static int signer_path_refused(const char *save_path)
{
    const char *trusted[] = {"c.pub"};
    NekoKEMVerifyPolicy policy = policy_of(0U, trusted, 1U);
    NekoKEMVerifyResult result;

    policy.signer_public_key_output_path = save_path;
    if (verify("v5.nkem", "b.key", 0, &policy, &result) !=
            NEKOKEM_OPERATION_ERROR ||
        exists("out.bin") || !same_file("b.key", "b-key.copy") ||
        !same_file("c.pub", "c-pub.copy")) {
        fprintf(stderr, "The signer key was saved over %s\n", save_path);
        return 0;
    }
    return 1;
}

static int test_signer_path_conflicts(void)
{
    const char *trusted[] = {"c.pub"};
    NekoKEMVerifyPolicy policy;
    NekoKEMVerifyResult result;

    CHECK(copy_file("b.key", "b-key.copy"));
    CHECK(copy_file("c.pub", "c-pub.copy"));
    /* The private key, the input, the output and a trusted key, however
     * they are spelled. */
    CHECK(signer_path_refused("b.key"));
    CHECK(signer_path_refused("./b.key"));
    CHECK(signer_path_refused("v5.nkem"));
    CHECK(signer_path_refused("out.bin"));
    CHECK(signer_path_refused("./out.bin"));
    CHECK(signer_path_refused("c.pub"));
    CHECK(signer_path_refused("./c.pub"));
#ifndef _WIN32
    CHECK(link("b.key", "b-hard.key") == 0);
    CHECK(signer_path_refused("b-hard.key"));
    CHECK(remove("b-hard.key") == 0);
    CHECK(symlink("b.key", "b-link.key") == 0);
    CHECK(signer_path_refused("b-link.key"));
    CHECK(symlink(".", "here") == 0);
    CHECK(signer_path_refused("here/b.key"));
    CHECK(signer_path_refused("here/out.bin"));
#endif

    /* The output must not replace a trusted key either. */
    policy = policy_of(0U, trusted, 1U);
    memset(&result, 0, sizeof(result));
    result.struct_size = sizeof(result);
    CHECK(nekokem_decrypt_file_verified_with_progress(
              "v5.nkem", "./c.pub", "b.key", NULL, 0U, &policy, &result,
              NULL, NULL) == NEKOKEM_OPERATION_ERROR);
    CHECK(same_file("c.pub", "c-pub.copy"));

    /* Core has its own trusted-signer limit, separate from recipients. */
    {
        static const char *repeated[NEKOKEM_MAX_TRUSTED_SIGNERS + 1U];
        size_t index;

        for (index = 0U; index < NEKOKEM_MAX_TRUSTED_SIGNERS + 1U; ++index) {
            repeated[index] = "c.pub";
        }
        policy = policy_of(NEKOKEM_VERIFY_REQUIRE_TRUSTED, repeated,
                           NEKOKEM_MAX_RECIPIENTS + 1U);
        CHECK(verify("v5.nkem", "b.key", 0, &policy, &result) ==
              NEKOKEM_ERROR_SIGNER_NOT_TRUSTED);
        policy = policy_of(0U, repeated, NEKOKEM_MAX_TRUSTED_SIGNERS);
        CHECK(verify("v5.nkem", "b.key", 0, &policy, &result) ==
              NEKOKEM_OPERATION_SUCCESS);
        policy = policy_of(0U, repeated, NEKOKEM_MAX_TRUSTED_SIGNERS + 1U);
        CHECK(verify("v5.nkem", "b.key", 0, &policy, &result) ==
              NEKOKEM_OPERATION_ERROR);
        CHECK(!exists("out.bin"));
    }

    /* A separate path still works. */
    (void)remove("signer.pub");
    policy = policy_of(0U, trusted, 1U);
    policy.signer_public_key_output_path = "signer.pub";
    CHECK(verify("v5.nkem", "b.key", 0, &policy, &result) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(nekokem_public_key_can_verify("signer.pub") == 1);
    CHECK(same_file("b.key", "b-key.copy"));
    return 1;
}

static int test_tampering(void)
{
    const size_t length = file_size("v5.nkem");
    const size_t metadata = NKEM_V4_HEADER_SIZE + NKEM_V4_SALT_SIZE +
                            NKEM_V4_ENTRY_SIZE + NKEM_V4_MAC_SIZE +
                            NKEM_NONCE_SIZE;
    const size_t block = length - NKEM_V5_SIGNATURE_BLOCK_SIZE;
    const size_t offsets[] = {
        0U, 4U, 5U, 12U, 14U, 20U, NKEM_V4_HEADER_SIZE,
        NKEM_V4_HEADER_SIZE + NKEM_V4_SALT_SIZE + 3U,
        NKEM_V4_HEADER_SIZE + NKEM_V4_SALT_SIZE + NKEM_V4_ENTRY_SIZE - 1U,
        metadata - NKEM_NONCE_SIZE - 1U, metadata - 1U, metadata,
        block - NKEM_TAG_SIZE - 1U, block - 1U, block, block + 100U,
        block + IDENTITY_ENCODING_SIZE + 5U, length - 1U,
    };
    NekoKEMVerifyResult result;
    size_t index;

    for (index = 0U; index < sizeof(offsets) / sizeof(offsets[0]); ++index) {
        CHECK(mutate("v5.nkem", "bad.nkem", offsets[index], 0x01U, 0));
        if (verify("bad.nkem", "b.key", 0, NULL, &result) ==
                NEKOKEM_OPERATION_SUCCESS ||
            exists("out.bin")) {
            fprintf(stderr, "Tampered offset %zu was accepted\n",
                    offsets[index]);
            return 0;
        }
    }
    /* Signature-block tampering is reported as an invalid signature. */
    CHECK(mutate("v5.nkem", "bad.nkem", block + 100U, 0x01U, 0));
    CHECK(verify("bad.nkem", "b.key", 0, NULL, &result) ==
          NEKOKEM_ERROR_SIGNATURE_INVALID);
    CHECK(mutate("v5.nkem", "bad.nkem", length, 0U, -1));
    CHECK(verify("bad.nkem", "b.key", 0, NULL, &result) !=
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(!exists("out.bin"));
    CHECK(mutate("v5.nkem", "bad.nkem", length + 1U, 0U, 1));
    CHECK(verify("bad.nkem", "b.key", 0, NULL, &result) !=
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(!exists("out.bin"));
    CHECK(mutate("v5.nkem", "bad.nkem", length, 0U,
                 -(long)NKEM_V5_SIGNATURE_BLOCK_SIZE));
    CHECK(verify("bad.nkem", "b.key", 0, NULL, &result) !=
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(!exists("out.bin"));

    /* Downgrade: a v5 container relabelled as v4 is not a v4 container. */
    CHECK(mutate("v5.nkem", "bad.nkem", 4U, 5U ^ 4U, 0));
    CHECK(verify("bad.nkem", "b.key", 0, NULL, &result) !=
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(!exists("out.bin"));
    return 1;
}

static int test_forgeries(void)
{
    const char *trusted[] = {"a.pub"};
    const char *recipient_b[] = {"b.pub"};
    char expected[NEKOKEM_FINGERPRINT_STRING_SIZE];
    NekoKEMVerifyPolicy policy;
    NekoKEMVerifyResult result;

    /* The helper itself round-trips unchanged blocks. */
    CHECK(reseal("v5.nkem", "bad.nkem", "b.key", edit_none));
    CHECK(verify("bad.nkem", "b.key", 0, NULL, &result) ==
          NEKOKEM_OPERATION_SUCCESS);

    CHECK(reseal("v5.nkem", "bad.nkem", "b.key", edit_ed448_signature));
    CHECK(verify("bad.nkem", "b.key", 0, NULL, &result) ==
          NEKOKEM_ERROR_SIGNATURE_INVALID);
    CHECK(!exists("out.bin"));
    CHECK(reseal("v5.nkem", "bad.nkem", "b.key", edit_mldsa_signature));
    CHECK(verify("bad.nkem", "b.key", 0, NULL, &result) ==
          NEKOKEM_ERROR_SIGNATURE_INVALID);
    CHECK(!exists("out.bin"));
    CHECK(reseal("v5.nkem", "bad.nkem", "b.key", edit_identity_swap));
    CHECK(verify("bad.nkem", "b.key", 0, NULL, &result) ==
          NEKOKEM_ERROR_SIGNATURE_INVALID);
    CHECK(!exists("out.bin"));
    CHECK(reseal("v5.nkem", "bad.nkem", "b.key", edit_identity_magic));
    CHECK(verify("bad.nkem", "b.key", 0, NULL, &result) ==
          NEKOKEM_ERROR_SIGNATURE_INVALID);
    CHECK(!exists("out.bin"));

    /*
     * A recipient can re-sign with its own identity; the file then shows
     * the recipient as the signer and fails a policy that trusts A.
     */
    resign_key_path = "b.key";
    CHECK(reseal("v5.nkem", "bad.nkem", "b.key", edit_resign));
    CHECK(verify("bad.nkem", "b.key", 0, NULL, &result) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(fingerprint_of("b.pub", expected));
    CHECK(strcmp(result.signer_fingerprint, expected) == 0);
    policy = policy_of(NEKOKEM_VERIFY_REQUIRE_TRUSTED, trusted, 1U);
    CHECK(verify("bad.nkem", "b.key", 0, &policy, &result) ==
          NEKOKEM_ERROR_SIGNER_NOT_TRUSTED);
    CHECK(!exists("out.bin"));

    /*
     * Several leaves: Core hashes them on worker threads, and signatures
     * over the independently computed tree verify.
     */
    {
        size_t big_size = 3U * 1048576U + 777U;
        unsigned char *big = malloc(big_size);
        size_t index;
        int written;

        CHECK(big != NULL);
        for (index = 0U; index < big_size; ++index) {
            big[index] = (unsigned char)(index * 131U + 7U);
        }
        written = write_bytes("big.bin", big, big_size);
        free(big);
        CHECK(written);
    }
    CHECK(encrypt_signed("big.bin", "big.nkem", recipient_b, 1U, "a.key", 1));
    CHECK(verify("big.nkem", "b.key", 0, NULL, &result) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(same_file("big.bin", "out.bin"));
    CHECK(reseal("big.nkem", "bad.nkem", "b.key", edit_none));
    CHECK(verify("bad.nkem", "b.key", 0, NULL, &result) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(reseal("big.nkem", "bad.nkem", "b.key", edit_resign));
    CHECK(verify("bad.nkem", "b.key", 0, NULL, &result) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(strcmp(result.signer_fingerprint, expected) == 0);
    return 1;
}

static int test_unsigned_and_options(void)
{
    const char *one[] = {"b.pub"};
    const char *two[] = {"b.pub", "c.pub"};
    NekoKEMEncryptOptions options = sign_with("a.key", 1);
    NekoKEMEncryptResult encrypted = {sizeof(encrypted), 0, 0};
    NekoKEMVerifyPolicy policy;
    NekoKEMVerifyResult result;

    (void)remove("bad.nkem");
    CHECK(nekokem_encrypt_file_v5_with_progress("plain.bin", "bad.nkem", one,
                                                1U, NULL, NULL, NULL, NULL) ==
          NEKOKEM_OPERATION_ERROR);
    options.flags = 0x80U;
    CHECK(nekokem_encrypt_file_v5_with_progress("plain.bin", "bad.nkem", one,
                                                1U, &options, NULL, NULL,
                                                NULL) ==
          NEKOKEM_OPERATION_ERROR);
    options = sign_with("a.key", 1);
    options.struct_size = sizeof(options) - 1U;
    CHECK(nekokem_encrypt_file_v5_with_progress("plain.bin", "bad.nkem", one,
                                                1U, &options, NULL, NULL,
                                                NULL) ==
          NEKOKEM_OPERATION_ERROR);
    /* A legacy sender key is an error, never a silent unsigned file. */
    options = sign_with("c.key", 0);
    CHECK(nekokem_encrypt_file_v5_with_progress("plain.bin", "bad.nkem", one,
                                                1U, &options, &encrypted,
                                                NULL, NULL) ==
          NEKOKEM_OPERATION_ERROR);
    CHECK(encrypted.format_version == 0);
    options = sign_with("a.key", 0);
    CHECK(nekokem_encrypt_file_v5_with_progress("plain.bin", "bad.nkem", one,
                                                1U, &options, NULL, NULL,
                                                NULL) ==
          NEKOKEM_OPERATION_ERROR);
    /* The output must not overwrite the sender's private key. */
    options = sign_with("b.key", 0);
    CHECK(nekokem_encrypt_file_v5_with_progress("plain.bin", "b.key", one, 1U,
                                                &options, NULL, NULL, NULL) ==
          NEKOKEM_OPERATION_ERROR);
    CHECK(nekokem_public_key_can_verify("b.pub") == 1);
    CHECK(!exists("bad.nkem"));

    options = sign_with(NULL, 0);
    options.flags = NEKOKEM_ENCRYPT_UNSIGNED;
    CHECK(nekokem_encrypt_file_v5_with_progress("plain.bin", "v3.nkem", one,
                                                1U, &options, &encrypted,
                                                NULL, NULL) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(encrypted.format_version == 3 && encrypted.signed_by_sender == 0);
    CHECK(nekokem_encrypt_file_v5_with_progress("plain.bin", "v4.nkem", two,
                                                2U, &options, &encrypted,
                                                NULL, NULL) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(encrypted.format_version == 4);

    CHECK(verify("v3.nkem", "b.key", 0, NULL, &result) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(result.format_version == 3 &&
          result.signature_status == NEKOKEM_SIGNATURE_UNSIGNED);
    CHECK(verify("v4.nkem", "c.key", 0, NULL, &result) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(result.format_version == 4 &&
          result.signature_status == NEKOKEM_SIGNATURE_UNSIGNED);
    CHECK(same_file("out.bin", "plain.bin"));

    policy = policy_of(NEKOKEM_VERIFY_REQUIRE_SIGNATURE, NULL, 0U);
    CHECK(verify("v3.nkem", "b.key", 0, &policy, &result) ==
          NEKOKEM_ERROR_SIGNATURE_REQUIRED);
    CHECK(!exists("out.bin"));
    policy = policy_of(NEKOKEM_VERIFY_REQUIRE_TRUSTED, NULL, 0U);
    CHECK(verify("v4.nkem", "b.key", 0, &policy, &result) ==
          NEKOKEM_ERROR_SIGNATURE_REQUIRED);
    CHECK(!exists("out.bin"));

    policy = policy_of(0x40U, NULL, 0U);
    CHECK(verify("v5.nkem", "b.key", 0, &policy, &result) ==
          NEKOKEM_OPERATION_ERROR);
    policy = policy_of(0U, NULL, 1U);
    CHECK(verify("v5.nkem", "b.key", 0, &policy, &result) ==
          NEKOKEM_OPERATION_ERROR);
    policy = policy_of(0U, NULL, 0U);
    policy.struct_size = 8U;
    CHECK(verify("v5.nkem", "b.key", 0, &policy, &result) ==
          NEKOKEM_OPERATION_ERROR);
    result.struct_size = 1U;
    CHECK(nekokem_decrypt_file_verified_with_progress(
              "v5.nkem", "out.bin", "b.key", NULL, 0U, NULL, &result, NULL,
              NULL) == NEKOKEM_OPERATION_ERROR);
    CHECK(!exists("out.bin"));
    return 1;
}

static int test_upgrade(void)
{
    const char *self[] = {"d.pub"};
    const char *one[] = {"b.pub"};
    char before[NEKOKEM_FINGERPRINT_STRING_SIZE];
    char after[NEKOKEM_FINGERPRINT_STRING_SIZE];
    char identity[NEKOKEM_FINGERPRINT_STRING_SIZE];
    unsigned char *bytes = NULL;
    size_t length = 0U;
    int nkpr_version;
    NekoKEMVerifyResult result;

    CHECK(make_keypair("d.pub", "d.key", 0, 1));
    CHECK(make_keypair("e.pub", "e.key", 0, 1));
    CHECK(nekokem_encrypt_file_multi_with_progress("plain.bin", "old-v4.nkem",
                                                   self, 1U, NULL, NULL) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(nekokem_public_key_legacy_fingerprint("d.pub", before,
                                                sizeof(before)));

    /* Wrong partners and wrong inputs change nothing. */
    CHECK(!nekokem_upgrade_to_identity_keys("e.pub", "d.key", password,
                                            sizeof(password) - 1U));
    CHECK(!nekokem_upgrade_to_identity_keys("d.pub", "d.key",
                                            (const unsigned char *)"wrong",
                                            5U));
    CHECK(!nekokem_upgrade_to_identity_keys("c.pub", "c.key", NULL, 0U));
    CHECK(nekokem_public_key_can_verify("d.pub") == 0);

    CHECK(nekokem_upgrade_to_identity_keys("d.pub", "d.key", password,
                                           sizeof(password) - 1U));
    CHECK(nekokem_public_key_can_verify("d.pub") == 1);
    CHECK(read_all("d.key", &bytes, &length));
    nkpr_version = length > 4U ? bytes[4] : -1;
    OPENSSL_free(bytes);
    CHECK(nkpr_version == (int)NKPR_VERSION_IDENTITY);
    CHECK(nekokem_public_key_legacy_fingerprint("d.pub", after,
                                                sizeof(after)));
    CHECK(strcmp(before, after) == 0);
    CHECK(fingerprint_of("d.pub", identity));
    CHECK(strcmp(identity, before) != 0);
    CHECK(!nekokem_upgrade_to_identity_keys("d.pub", "d.key", password,
                                            sizeof(password) - 1U));

    /* Files encrypted before the upgrade still open; the key now signs. */
    CHECK(verify("old-v4.nkem", "d.key", 1, NULL, &result) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(same_file("out.bin", "plain.bin"));
    CHECK(encrypt_signed("plain.bin", "upgraded.nkem", one, 1U, "d.key", 1));
    CHECK(verify("upgraded.nkem", "b.key", 0, NULL, &result) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(strcmp(result.signer_fingerprint, identity) == 0);

    /* A contact holding only the legacy export still receives files. */
    CHECK(encrypt_signed("plain.bin", "legacy-to.nkem",
                         (const char *[]){"a-legacy.pub"}, 1U, "b.key", 0));
    CHECK(verify("legacy-to.nkem", "a.key", 1, NULL, &result) ==
          NEKOKEM_OPERATION_SUCCESS);
    CHECK(same_file("out.bin", "plain.bin"));
    return 1;
}

static int test_identity_decode(void)
{
    HybridKeys keys = {0};
    HybridKeys decoded = {0};
    unsigned char valid[IDENTITY_ENCODING_SIZE];
    unsigned char bad[IDENTITY_ENCODING_SIZE];
    /* p = 2^448 - 2^224 - 1, little endian: a non-canonical y. */
    unsigned char p[56];
    struct {
        size_t offset;
        unsigned char value;
        int is_xor;
    } edits[] = {
        {0U, 0x01U, 1}, {4U, 0x02U, 0}, {5U, 0x03U, 0}, {6U, 0x02U, 0},
        {7U, 0x01U, 1}, {8U, 0x01U, 1}, {ID_MLKEM_HEADER, 0x05U, 0},
        {ID_MLKEM_HEADER + 2U, 0x01U, 1}, {ID_ED448_HEADER, 0x04U, 0},
        {ID_ED448_HEADER + 2U, 0x01U, 1}, {ID_MLDSA_HEADER, 0x03U, 0},
        {ID_MLDSA_HEADER + 1U, 0x01U, 1},
        /* Ed448 top byte must keep its seven low bits clear. */
        {ID_ED448_RAW + 56U, 0x01U, 1},
    };
    size_t index;
    int ok;

    CHECK(hybrid_load_public_keys("a.pub", &keys));
    ok = identity_encode(&keys, valid);
    hybrid_keys_cleanup(&keys);
    CHECK(ok);
    CHECK(identity_decode(valid, &decoded));
    hybrid_keys_cleanup(&decoded);

    for (index = 0U; index < sizeof(edits) / sizeof(edits[0]); ++index) {
        memcpy(bad, valid, sizeof(bad));
        if (edits[index].is_xor) {
            bad[edits[index].offset] ^= edits[index].value;
        } else {
            bad[edits[index].offset] = edits[index].value;
        }
        if (identity_decode(bad, &decoded)) {
            hybrid_keys_cleanup(&decoded);
            fprintf(stderr, "Identity edit %zu was accepted\n", index);
            return 0;
        }
    }

    /* Ed448 identity point (y = 1) and y = p - 1 have small order. */
    memcpy(bad, valid, sizeof(bad));
    memset(bad + ID_ED448_RAW, 0, IDENTITY_ED448_PUBLIC_SIZE);
    bad[ID_ED448_RAW] = 1U;
    CHECK(!identity_decode(bad, &decoded));
    memset(p, 0xff, sizeof(p));
    p[28] = 0xfeU;
    memcpy(bad + ID_ED448_RAW, p, sizeof(p));
    bad[ID_ED448_RAW] = 0xfeU;
    CHECK(!identity_decode(bad, &decoded));
    bad[ID_ED448_RAW] = 0xffU;
    CHECK(!identity_decode(bad, &decoded));
    /* X448 u = 0 has small order. */
    memcpy(bad, valid, sizeof(bad));
    memset(bad + ID_X448_RAW, 0, X448_PUBLIC_KEY_SIZE);
    CHECK(!identity_decode(bad, &decoded));
    /* ML-KEM coefficient 4095 is not reduced modulo q = 3329. */
    memcpy(bad, valid, sizeof(bad));
    bad[ID_MLKEM_RAW] = 0xffU;
    bad[ID_MLKEM_RAW + 1U] |= 0x0fU;
    CHECK(!identity_decode(bad, &decoded));
    return 1;
}

static void remove_fixtures(void)
{
    size_t index;

    for (index = 0U; index < sizeof(fixtures) / sizeof(fixtures[0]); ++index) {
        (void)remove(fixtures[index]);
    }
}

static int run(void)
{
    return test_identity_keys() && test_round_trips() &&
           test_signer_path_conflicts() && test_tampering() &&
           test_forgeries() && test_unsigned_and_options() && test_upgrade() &&
           test_identity_decode();
}

int main(void)
{
#ifndef _WIN32
    /* Windows CI runs each test in its own empty directory. */
    char directory[] = "/tmp/nekokem-signature.XXXXXX";

    if (mkdtemp(directory) == NULL || chdir(directory) != 0) {
        perror("Cannot create test directory");
        return EXIT_FAILURE;
    }
#endif
    int success = run();

    remove_fixtures();
#ifndef _WIN32
    if (chdir("/") == 0) {
        (void)rmdir(directory);
    }
#endif
    if (!success) {
        fprintf(stderr, "NKEM v5 signature tests failed\n");
        return EXIT_FAILURE;
    }
    puts("NKEM v5 signature tests passed");
    return EXIT_SUCCESS;
}
