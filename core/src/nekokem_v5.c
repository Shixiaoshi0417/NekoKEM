/* NKEM v5 signed container; the format is specified in docs/NKEM-v5.md. */
#include "nekokem_v5.h"

#include "aes.h"
#include "hybrid.h"
#include "identity.h"
#include "nekokem_multi.h"
#include "payload_hash.h"
#include "secure_mem.h"

#include <openssl/crypto.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define V5_DIGEST_SIZE PAYLOAD_HASH_DIGEST_SIZE
#define V5_SIGNATURE_KEY_SIZE 32U

_Static_assert(IDENTITY_ENCODING_SIZE + IDENTITY_SIGNATURE_SIZE ==
                   NKEM_V5_SIGNATURE_PLAINTEXT_SIZE,
               "Signature block layout");
_Static_assert(NKEM_V5_SIGNATURE_BLOCK_SIZE == 9048U,
               "Signature block length in the header");

static const NkemMultiLabels v5_labels = {
    "NekoKEM v5 recipient wrap X448-MLKEM1024",
    "NekoKEM v5 payload AES-256-GCM",
    "NekoKEM v5 header HMAC-SHA512",
};
static const char signature_key_label[] =
    "NekoKEM v5 signature block AES-256-GCM";
static const char message_label[] = "NekoKEM v5 sender signature";
/* Each signature key encrypts exactly one block, so the nonce never repeats. */
static const unsigned char block_nonce[NKEM_NONCE_SIZE] = {0};

_Static_assert(NKEM_TAG_SIZE == PAYLOAD_HASH_TAG_SIZE, "Payload tag size");

static int hash_ciphertext(const unsigned char *ciphertext, size_t length,
                           void *user_data)
{
    return payload_hash_update((PayloadHash *)user_data, ciphertext, length);
}

static int valid_path(const char *path)
{
    return path != NULL && path[0] != '\0';
}

static int derive_signature_key(
    const unsigned char file_key[NKEM_V4_FILE_KEY_SIZE],
    const unsigned char salt[NKEM_V4_SALT_SIZE],
    unsigned char key[V5_SIGNATURE_KEY_SIZE])
{
    return nkem_multi_hkdf_sha512(
        file_key, NKEM_V4_FILE_KEY_SIZE, salt,
        (const unsigned char *)signature_key_label,
        sizeof(signature_key_label) - 1U, key, V5_SIGNATURE_KEY_SIZE);
}

/*
 * M = label || metadata || payload digest || identity encoding, where the
 * payload digest is the leaf/root SHA-512 tree of payload_hash.h and
 * metadata is header || salt || entries || header MAC || nonce.
 */
static unsigned char *build_message(const unsigned char *metadata,
                                    size_t metadata_len,
                                    const unsigned char digest[V5_DIGEST_SIZE],
                                    const unsigned char identity[IDENTITY_ENCODING_SIZE],
                                    size_t *message_len)
{
    size_t label_len = sizeof(message_label) - 1U;
    size_t length = label_len + metadata_len + V5_DIGEST_SIZE +
                    IDENTITY_ENCODING_SIZE;
    unsigned char *message = OPENSSL_malloc(length);

    if (message == NULL) {
        print_openssl_error("Cannot allocate the NKEM v5 signed message");
        return NULL;
    }
    memcpy(message, message_label, label_len);
    memcpy(message + label_len, metadata, metadata_len);
    memcpy(message + label_len + metadata_len, digest, V5_DIGEST_SIZE);
    memcpy(message + label_len + metadata_len + V5_DIGEST_SIZE, identity,
           IDENTITY_ENCODING_SIZE);
    *message_len = length;
    return message;
}

/* AES-256-GCM over the signature block; AAD = metadata || payload tag. */
static int seal_signature_block(
    const unsigned char key[V5_SIGNATURE_KEY_SIZE],
    const unsigned char *metadata,
    size_t metadata_len,
    const unsigned char payload_tag[NKEM_TAG_SIZE],
    const unsigned char plaintext[NKEM_V5_SIGNATURE_PLAINTEXT_SIZE],
    unsigned char block[NKEM_V5_SIGNATURE_BLOCK_SIZE])
{
    EVP_CIPHER_CTX *context = EVP_CIPHER_CTX_new();
    unsigned char final_block[EVP_MAX_BLOCK_LENGTH];
    int length = 0;
    int success = 0;

    if (context == NULL ||
        EVP_EncryptInit_ex(context, EVP_aes_256_gcm(), NULL, NULL, NULL) <= 0 ||
        EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_SET_IVLEN,
                            (int)NKEM_NONCE_SIZE, NULL) <= 0 ||
        EVP_EncryptInit_ex(context, NULL, NULL, key, block_nonce) <= 0 ||
        EVP_EncryptUpdate(context, NULL, &length, metadata,
                          (int)metadata_len) <= 0 ||
        EVP_EncryptUpdate(context, NULL, &length, payload_tag,
                          (int)NKEM_TAG_SIZE) <= 0 ||
        EVP_EncryptUpdate(context, block, &length, plaintext,
                          (int)NKEM_V5_SIGNATURE_PLAINTEXT_SIZE) <= 0 ||
        length != (int)NKEM_V5_SIGNATURE_PLAINTEXT_SIZE ||
        EVP_EncryptFinal_ex(context, final_block, &length) <= 0 ||
        length != 0 ||
        EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_GET_TAG, (int)NKEM_TAG_SIZE,
                            block + NKEM_V5_SIGNATURE_PLAINTEXT_SIZE) <= 0) {
        print_openssl_error("Cannot encrypt the NKEM v5 signature block");
        goto cleanup;
    }
    success = 1;

cleanup:
    EVP_CIPHER_CTX_free(context);
    return success;
}

/* 1 when the block authenticates, 0 when it does not, -1 on an error. */
static int open_signature_block(
    const unsigned char key[V5_SIGNATURE_KEY_SIZE],
    const unsigned char *metadata,
    size_t metadata_len,
    const unsigned char payload_tag[NKEM_TAG_SIZE],
    const unsigned char block[NKEM_V5_SIGNATURE_BLOCK_SIZE],
    unsigned char plaintext[NKEM_V5_SIGNATURE_PLAINTEXT_SIZE])
{
    EVP_CIPHER_CTX *context = EVP_CIPHER_CTX_new();
    unsigned char tag[NKEM_TAG_SIZE];
    unsigned char final_block[EVP_MAX_BLOCK_LENGTH];
    int length = 0;
    int result = -1;

    memcpy(tag, block + NKEM_V5_SIGNATURE_PLAINTEXT_SIZE, sizeof(tag));
    if (context == NULL ||
        EVP_DecryptInit_ex(context, EVP_aes_256_gcm(), NULL, NULL, NULL) <= 0 ||
        EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_SET_IVLEN,
                            (int)NKEM_NONCE_SIZE, NULL) <= 0 ||
        EVP_DecryptInit_ex(context, NULL, NULL, key, block_nonce) <= 0 ||
        EVP_DecryptUpdate(context, NULL, &length, metadata,
                          (int)metadata_len) <= 0 ||
        EVP_DecryptUpdate(context, NULL, &length, payload_tag,
                          (int)NKEM_TAG_SIZE) <= 0 ||
        EVP_DecryptUpdate(context, plaintext, &length, block,
                          (int)NKEM_V5_SIGNATURE_PLAINTEXT_SIZE) <= 0 ||
        length != (int)NKEM_V5_SIGNATURE_PLAINTEXT_SIZE ||
        EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_SET_TAG, (int)sizeof(tag),
                            tag) <= 0) {
        print_openssl_error("Cannot decrypt the NKEM v5 signature block");
        goto cleanup;
    }
    (void)ERR_set_mark();
    result = EVP_DecryptFinal_ex(context, final_block, &length) > 0 &&
                     length == 0
                 ? 1
                 : 0;
    (void)ERR_pop_to_mark();

cleanup:
    if (result != 1) {
        secure_mem_clear(plaintext, NKEM_V5_SIGNATURE_PLAINTEXT_SIZE);
    }
    EVP_CIPHER_CTX_free(context);
    return result;
}

static void encrypt_result_set(NekoKEMEncryptResult *result,
                               int format_version)
{
    if (result != NULL && result->struct_size >= sizeof(*result)) {
        result->format_version = format_version;
        result->signed_by_sender = format_version == 5;
    }
}

int nekokem_encrypt_file_v5_with_progress(
    const char *input_path,
    const char *output_path,
    const char *const *public_key_paths,
    size_t public_key_count,
    const NekoKEMEncryptOptions *options,
    NekoKEMEncryptResult *encrypt_result,
    NekoKEMProgressCallback progress_callback,
    void *progress_user_data)
{
    FILE *input = NULL;
    AtomicFile output = {0};
    HybridKeys sender = {0};
    HybridKeys *recipients = NULL;
    const char **key_paths = NULL;
    unsigned char *metadata = NULL;
    unsigned char *message = NULL;
    PayloadHash *payload_hash = NULL;
    unsigned char identity[IDENTITY_ENCODING_SIZE];
    unsigned char block_plaintext[NKEM_V5_SIGNATURE_PLAINTEXT_SIZE];
    unsigned char block[NKEM_V5_SIGNATURE_BLOCK_SIZE];
    unsigned char digest[V5_DIGEST_SIZE];
    unsigned char file_key[NKEM_V4_FILE_KEY_SIZE] = {0};
    unsigned char payload_key[AES256_KEY_SIZE] = {0};
    unsigned char mac_key[NKEM_MULTI_MAC_KEY_SIZE] = {0};
    unsigned char signature_key[V5_SIGNATURE_KEY_SIZE] = {0};
    unsigned char tag[NKEM_TAG_SIZE];
    unsigned char *nonce;
    size_t message_len = 0U;
    size_t metadata_len = 0U;
    size_t mac_offset;
    size_t index;
    uint64_t plaintext_len = 0U;
    int aes_result;
    int result = NEKOKEM_OPERATION_ERROR;

    encrypt_result_set(encrypt_result, 0);
    if (options == NULL || options->struct_size < sizeof(*options) ||
        (options->flags & ~(uint32_t)NEKOKEM_ENCRYPT_UNSIGNED) != 0U ||
        (encrypt_result != NULL &&
         encrypt_result->struct_size < sizeof(*encrypt_result))) {
        fprintf(stderr, file_message("Invalid NekoKEM encryption options\n"));
        return NEKOKEM_OPERATION_ERROR;
    }
    if ((options->flags & NEKOKEM_ENCRYPT_UNSIGNED) != 0U) {
        result = nekokem_encrypt_file_multi_with_progress(
            input_path, output_path, public_key_paths, public_key_count,
            progress_callback, progress_user_data);
        if (result == NEKOKEM_OPERATION_SUCCESS) {
            encrypt_result_set(encrypt_result, public_key_count == 1U ? 3 : 4);
        }
        return result;
    }

    if (!valid_path(input_path) || !valid_path(output_path) ||
        !valid_path(options->sender_private_key_path)) {
        fprintf(stderr, file_message("NekoKEM Core received an empty file path\n"));
        goto cleanup;
    }
    if (public_key_paths == NULL || public_key_count == 0U ||
        public_key_count > NKEM_V4_MAX_RECIPIENTS) {
        fprintf(stderr, file_message("Invalid recipient public-key list\n"));
        goto cleanup;
    }
    key_paths = OPENSSL_zalloc((public_key_count + 1U) * sizeof(*key_paths));
    if (key_paths == NULL) {
        print_openssl_error("Cannot allocate NKEM v5 metadata");
        goto cleanup;
    }
    for (index = 0U; index < public_key_count; ++index) {
        if (!valid_path(public_key_paths[index])) {
            fprintf(stderr, file_message("NekoKEM Core received an empty file path\n"));
            goto cleanup;
        }
        key_paths[index] = public_key_paths[index];
    }
    key_paths[public_key_count] = options->sender_private_key_path;
    if (!file_output_spares_keys(output_path, key_paths,
                                 public_key_count + 1U)) {
        goto cleanup;
    }
    input = file_open_regular(input_path);
    if (input == NULL) {
        goto cleanup;
    }
    if (!file_disable_buffering(input) ||
        !file_get_size(input, &plaintext_len) ||
        !nkem_gcm_data_size_is_valid(plaintext_len)) {
        goto cleanup;
    }
    if (!hybrid_load_decryption_keys(options->sender_private_key_path,
                                     options->password,
                                     options->password_len, &sender)) {
        goto cleanup;
    }
    if (!hybrid_keys_have_identity(&sender)) {
        fprintf(stderr, file_message("Signing needs a four-component identity private key; upgrade the key first\n"));
        goto cleanup;
    }
    if (!identity_encode(&sender, identity)) {
        goto cleanup;
    }
    recipients = nkem_multi_load_recipients(public_key_paths,
                                            public_key_count);
    if (recipients == NULL) {
        goto cleanup;
    }

    metadata_len = (size_t)nkem_v5_metadata_size((uint16_t)public_key_count);
    mac_offset = NKEM_MULTI_PREFIX_SIZE + public_key_count * NKEM_V4_ENTRY_SIZE;
    metadata = OPENSSL_malloc(metadata_len);
    if (metadata == NULL) {
        print_openssl_error("Cannot allocate NKEM v5 metadata");
        goto cleanup;
    }
    nonce = metadata + mac_offset + NKEM_V4_MAC_SIZE;
    nkem_v5_header_encode(metadata, (uint16_t)public_key_count,
                          plaintext_len);
    if (RAND_bytes(file_key, (int)sizeof(file_key)) != 1 ||
        RAND_bytes(metadata + NKEM_V4_HEADER_SIZE,
                   (int)NKEM_V4_SALT_SIZE) != 1 ||
        RAND_bytes(nonce, (int)NKEM_NONCE_SIZE) != 1) {
        print_openssl_error("Cannot generate NKEM v5 random values");
        goto cleanup;
    }
    for (index = 0U; index < public_key_count; ++index) {
        if (!nkem_multi_seal_recipient(&v5_labels, &recipients[index],
                                       metadata, file_key,
                                       metadata + NKEM_MULTI_PREFIX_SIZE +
                                           index * NKEM_V4_ENTRY_SIZE)) {
            goto cleanup;
        }
    }
    nkem_multi_recipients_cleanup(recipients, public_key_count);
    recipients = NULL;

    if (!nkem_multi_derive_file_keys(&v5_labels, file_key,
                                     metadata + NKEM_V4_HEADER_SIZE,
                                     payload_key, mac_key) ||
        !derive_signature_key(file_key, metadata + NKEM_V4_HEADER_SIZE,
                              signature_key) ||
        !nkem_multi_header_mac(mac_key, metadata, mac_offset,
                               metadata + mac_offset)) {
        goto cleanup;
    }
    secure_mem_clear(file_key, sizeof(file_key));
    secure_mem_clear(mac_key, sizeof(mac_key));

    payload_hash = payload_hash_new(plaintext_len);
    if (payload_hash == NULL ||
        !atomic_file_open(&output, output_path, 0600) ||
        !file_write_all(output.stream, metadata, metadata_len)) {
        goto cleanup;
    }
    aes_result = aes_gcm_encrypt_file_hashed(
        input, output.stream, plaintext_len, payload_key, nonce,
        metadata, metadata_len, tag, hash_ciphertext, payload_hash,
        progress_callback, progress_user_data);
    if (aes_result == AES_GCM_FILE_CANCELLED) {
        result = NEKOKEM_OPERATION_CANCELLED;
        goto cleanup;
    }
    if (aes_result != AES_GCM_FILE_SUCCESS ||
        !file_write_all(output.stream, tag, sizeof(tag)) ||
        !payload_hash_final(payload_hash, tag, digest)) {
        goto cleanup;
    }
    message = build_message(metadata, metadata_len, digest, identity,
                            &message_len);
    if (message == NULL) {
        goto cleanup;
    }
    memcpy(block_plaintext, identity, IDENTITY_ENCODING_SIZE);
    if (!identity_sign(&sender, message, message_len,
                       block_plaintext + IDENTITY_ENCODING_SIZE)) {
        goto cleanup;
    }
    hybrid_keys_cleanup(&sender);
    if (!seal_signature_block(signature_key, metadata, metadata_len, tag,
                              block_plaintext, block) ||
        !file_write_all(output.stream, block, sizeof(block)) ||
        !atomic_file_commit(&output)) {
        goto cleanup;
    }
    encrypt_result_set(encrypt_result, 5);
    result = NEKOKEM_OPERATION_SUCCESS;

cleanup:
    atomic_file_abort(&output);
    if (input != NULL) {
        (void)fclose(input);
    }
    hybrid_keys_cleanup(&sender);
    nkem_multi_recipients_cleanup(recipients, public_key_count);
    OPENSSL_free(key_paths);
    OPENSSL_free(metadata);
    OPENSSL_free(message);
    payload_hash_free(payload_hash);
    secure_mem_clear(file_key, sizeof(file_key));
    secure_mem_clear(payload_key, sizeof(payload_key));
    secure_mem_clear(mac_key, sizeof(mac_key));
    secure_mem_clear(signature_key, sizeof(signature_key));
    return result;
}

int nekokem_v5_policy_is_valid(const NekoKEMVerifyPolicy *policy)
{
    size_t index;

    if (policy == NULL) {
        return 1;
    }
    if (policy->struct_size < sizeof(*policy) ||
        (policy->flags & ~(uint32_t)(NEKOKEM_VERIFY_REQUIRE_SIGNATURE |
                                     NEKOKEM_VERIFY_REQUIRE_TRUSTED)) != 0U ||
        policy->trusted_signer_count > NEKOKEM_MAX_TRUSTED_SIGNERS ||
        (policy->trusted_signer_count > 0U &&
         policy->trusted_signer_paths == NULL) ||
        (policy->signer_public_key_output_path != NULL &&
         !valid_path(policy->signer_public_key_output_path))) {
        fprintf(stderr, file_message("Invalid signature verification policy\n"));
        return 0;
    }
    for (index = 0U; index < policy->trusted_signer_count; ++index) {
        if (!valid_path(policy->trusted_signer_paths[index])) {
            fprintf(stderr, file_message("Invalid signature verification policy\n"));
            return 0;
        }
    }
    return 1;
}

/*
 * Loads every trusted signer before decryption starts. A two-component key
 * cannot sign and stays unmatched (all-zero encoding); a missing or invalid
 * file fails, so a broken contact is never silently dropped.
 */
static unsigned char *load_trusted_identities(
    const NekoKEMVerifyPolicy *policy,
    unsigned char **present)
{
    unsigned char *encodings;
    size_t count = policy == NULL ? 0U : policy->trusted_signer_count;
    size_t index;

    *present = NULL;
    encodings = OPENSSL_zalloc((count == 0U ? 1U : count) *
                               IDENTITY_ENCODING_SIZE);
    *present = OPENSSL_zalloc(count == 0U ? 1U : count);
    if (encodings == NULL || *present == NULL) {
        print_openssl_error("Cannot allocate trusted signers");
        goto failure;
    }
    for (index = 0U; index < count; ++index) {
        HybridKeys keys = {0};
        int encoded;

        if (!hybrid_load_public_keys(policy->trusted_signer_paths[index],
                                     &keys)) {
            fprintf(stderr, file_message("Cannot load trusted signer %s\n"),
                    file_display_safe_path(policy->trusted_signer_paths[index]));
            goto failure;
        }
        encoded = !hybrid_keys_have_identity(&keys) ||
                  identity_encode(&keys,
                                  encodings + index * IDENTITY_ENCODING_SIZE);
        (*present)[index] = (unsigned char)hybrid_keys_have_identity(&keys);
        hybrid_keys_cleanup(&keys);
        if (!encoded) {
            goto failure;
        }
    }
    return encodings;

failure:
    OPENSSL_free(encodings);
    OPENSSL_free(*present);
    *present = NULL;
    return NULL;
}

/* Owner-only, like nekokem_export_public_key(): front ends stage it privately. */
static int write_signer_public_key(const char *path, const HybridKeys *signer)
{
    AtomicFile output = {0};
    int success = 0;

    if (atomic_file_open(&output, path, 0600) &&
        hybrid_write_public_keys(output.stream, signer, 1) &&
        atomic_file_commit(&output)) {
        success = 1;
    }
    atomic_file_abort(&output);
    return success;
}

int nekokem_v5_decrypt_opened(
    FILE *input,
    uint64_t container_size,
    const unsigned char raw_header[NKEM_V4_HEADER_SIZE],
    const char *output_path,
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len,
    const NekoKEMVerifyPolicy *policy,
    NekoKEMVerifyResult *verify_result,
    NekoKEMProgressCallback progress_callback,
    void *progress_user_data)
{
    AtomicFile output = {0};
    HybridKeys private_keys = {0};
    HybridKeys signer = {0};
    NkemV5Header header;
    PayloadHash *payload_hash = NULL;
    unsigned char *metadata = NULL;
    unsigned char *message = NULL;
    unsigned char *trusted = NULL;
    unsigned char *trusted_present = NULL;
    unsigned char block[NKEM_V5_SIGNATURE_BLOCK_SIZE];
    unsigned char block_plaintext[NKEM_V5_SIGNATURE_PLAINTEXT_SIZE];
    unsigned char digest[V5_DIGEST_SIZE];
    unsigned char file_key[NKEM_V4_FILE_KEY_SIZE] = {0};
    unsigned char payload_key[AES256_KEY_SIZE] = {0};
    unsigned char signature_key[V5_SIGNATURE_KEY_SIZE] = {0};
    unsigned char tag[NKEM_TAG_SIZE];
    char fingerprint[IDENTITY_FINGERPRINT_STRING_SIZE] = {0};
    uint32_t flags = policy == NULL ? 0U : policy->flags;
    size_t trusted_count = policy == NULL ? 0U : policy->trusted_signer_count;
    size_t trusted_index = 0U;
    size_t message_len = 0U;
    size_t metadata_len = 0U;
    size_t mac_offset;
    size_t index;
    int is_trusted = 0;
    int opened;
    int aes_result;
    int result = NEKOKEM_OPERATION_ERROR;

    if (!nekokem_v5_policy_is_valid(policy) ||
        !nkem_v5_header_decode(raw_header, &header) ||
        !nkem_v5_container_size_is_valid(&header, container_size)) {
        goto cleanup;
    }
    trusted = load_trusted_identities(policy, &trusted_present);
    if (trusted == NULL) {
        goto cleanup;
    }
    metadata_len = (size_t)nkem_v5_metadata_size(header.recipient_count);
    mac_offset = NKEM_MULTI_PREFIX_SIZE +
                 (size_t)header.recipient_count * NKEM_V4_ENTRY_SIZE;
    metadata = OPENSSL_malloc(metadata_len);
    if (metadata == NULL) {
        print_openssl_error("Cannot allocate NKEM v5 metadata");
        goto cleanup;
    }
    memcpy(metadata, raw_header, NKEM_V4_HEADER_SIZE);
    if (!file_read_exact(input, metadata + NKEM_V4_HEADER_SIZE,
                         metadata_len - NKEM_V4_HEADER_SIZE) ||
        !hybrid_load_decryption_keys(private_key_path, password,
                                     password_len, &private_keys)) {
        goto cleanup;
    }
    if (!nkem_multi_open_metadata(&v5_labels, &private_keys, metadata,
                                  header.recipient_count, payload_key,
                                  file_key)) {
        goto cleanup;
    }
    hybrid_keys_cleanup(&private_keys);
    if (!derive_signature_key(file_key, metadata + NKEM_V4_HEADER_SIZE,
                              signature_key)) {
        goto cleanup;
    }
    secure_mem_clear(file_key, sizeof(file_key));

    payload_hash = payload_hash_new(header.ciphertext_len);
    if (payload_hash == NULL ||
        !atomic_file_open(&output, output_path, 0600)) {
        goto cleanup;
    }
    aes_result = aes_gcm_decrypt_file_hashed(
        input, output.stream, header.ciphertext_len, payload_key,
        metadata + mac_offset + NKEM_V4_MAC_SIZE, metadata, metadata_len,
        hash_ciphertext, payload_hash, tag, progress_callback, progress_user_data);
    if (aes_result == AES_GCM_FILE_CANCELLED) {
        result = NEKOKEM_OPERATION_CANCELLED;
        goto cleanup;
    }
    if (aes_result != AES_GCM_FILE_SUCCESS ||
        !file_read_exact(input, block, sizeof(block))) {
        goto cleanup;
    }
    if (fgetc(input) != EOF || ferror(input) != 0) {
        if (ferror(input) != 0) {
            print_system_error("Cannot finish reading NKEM v5 input");
        }
        goto cleanup;
    }
    if (!payload_hash_final(payload_hash, tag, digest)) {
        goto cleanup;
    }

    opened = open_signature_block(signature_key, metadata, metadata_len, tag,
                                  block, block_plaintext);
    if (opened < 0) {
        goto cleanup;
    }
    if (opened == 0 || !identity_decode(block_plaintext, &signer)) {
        result = NEKOKEM_ERROR_SIGNATURE_INVALID;
        goto invalid;
    }
    message = build_message(metadata, metadata_len, digest, block_plaintext,
                            &message_len);
    if (message == NULL) {
        goto cleanup;
    }
    if (!identity_verify(&signer, message, message_len,
                         block_plaintext + IDENTITY_ENCODING_SIZE)) {
        result = NEKOKEM_ERROR_SIGNATURE_INVALID;
        goto invalid;
    }
    if (!identity_fingerprint(block_plaintext, fingerprint,
                              sizeof(fingerprint))) {
        goto cleanup;
    }

    /* Core alone decides whether the signer is trusted. */
    for (index = 0U; index < trusted_count; ++index) {
        if (trusted_present[index] != 0U &&
            memcmp(trusted + index * IDENTITY_ENCODING_SIZE, block_plaintext,
                   IDENTITY_ENCODING_SIZE) == 0) {
            is_trusted = 1;
            trusted_index = index;
            break;
        }
    }
    if ((flags & NEKOKEM_VERIFY_REQUIRE_TRUSTED) != 0U && !is_trusted) {
        fprintf(stderr, file_message("The file is signed by an identity that is not trusted: %s\n"),
                fingerprint);
        result = NEKOKEM_ERROR_SIGNER_NOT_TRUSTED;
        goto cleanup;
    }
    if (policy != NULL && policy->signer_public_key_output_path != NULL &&
        !write_signer_public_key(policy->signer_public_key_output_path,
                                 &signer)) {
        goto cleanup;
    }
    if (!atomic_file_commit(&output)) {
        goto cleanup;
    }
    if (verify_result != NULL) {
        verify_result->format_version = 5;
        verify_result->signature_status =
            is_trusted ? NEKOKEM_SIGNATURE_TRUSTED
                       : NEKOKEM_SIGNATURE_VALID_UNTRUSTED;
        verify_result->trusted_signer_index = trusted_index;
        memcpy(verify_result->signer_fingerprint, fingerprint,
               sizeof(fingerprint));
    }
    result = NEKOKEM_OPERATION_SUCCESS;
    goto cleanup;

invalid:
    fprintf(stderr, file_message("The sender signature is invalid; the file may have been modified\n"));

cleanup:
    atomic_file_abort(&output);
    hybrid_keys_cleanup(&private_keys);
    hybrid_keys_cleanup(&signer);
    payload_hash_free(payload_hash);
    OPENSSL_free(metadata);
    OPENSSL_free(message);
    OPENSSL_free(trusted);
    OPENSSL_free(trusted_present);
    secure_mem_clear(block_plaintext, sizeof(block_plaintext));
    secure_mem_clear(file_key, sizeof(file_key));
    secure_mem_clear(payload_key, sizeof(payload_key));
    secure_mem_clear(signature_key, sizeof(signature_key));
    return result;
}
