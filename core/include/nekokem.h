#ifndef NEKOKEM_CORE_NEKOKEM_H
#define NEKOKEM_CORE_NEKOKEM_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32) && defined(NEKOKEM_CORE_SHARED)
#if defined(NEKOKEM_CORE_BUILD)
#define NEKOKEM_API __declspec(dllexport)
#else
#define NEKOKEM_API __declspec(dllimport)
#endif
#else
#define NEKOKEM_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* 32 SHA-256 bytes as uppercase, colon-separated hex, plus NUL. */
#define NEKOKEM_FINGERPRINT_STRING_SIZE 96U

/* Results returned by the progress-enabled file APIs. */
#define NEKOKEM_OPERATION_ERROR 0
#define NEKOKEM_OPERATION_SUCCESS 1
#define NEKOKEM_OPERATION_CANCELLED (-1)

/* Return nonzero to continue or zero to request cancellation. */
typedef int (*NekoKEMProgressCallback)(uint64_t processed_bytes,
                                       uint64_t total_bytes,
                                       void *user_data);

/*
 * Functions return 1 on success and 0 on error. Password bytes
 * are borrowed for the duration of the call and are never retained by Core.
 *
 * Creates a new key pair and never overwrites: if either key file already
 * exists, nothing is written and 0 is returned.
 */
NEKOKEM_API int nekokem_generate_keypair(
    const char *public_key_path,
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len);

/*
 * Rotates keys: generates a key pair that atomically replaces any existing
 * key files. Files encrypted for the old public key then need the old
 * private key, so call this only after the user explicitly chose to replace
 * the keys, and remind them to back up the old private key first.
 */
NEKOKEM_API int nekokem_replace_keypair(
    const char *public_key_path,
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len);

/* Writes the default NKEM v3 Hybrid container. */
NEKOKEM_API int nekokem_encrypt_file(
    const char *input_path,
    const char *output_path,
    const char *public_key_path);

/*
 * Progress-enabled v3 encryption. The callback is invoked from the calling
 * thread and may cancel by returning zero. Atomic output is never committed
 * after an error or cancellation.
 */
NEKOKEM_API int nekokem_encrypt_file_with_progress(
    const char *input_path,
    const char *output_path,
    const char *public_key_path,
    NekoKEMProgressCallback progress_callback,
    void *progress_user_data);

/* Most recipients one NKEM v4 multi-recipient container may list. */
#define NEKOKEM_MAX_RECIPIENTS 64U

/*
 * Writes a container that each listed public key's private key can decrypt:
 * NKEM v4 (docs/NKEM-v4.md) for two or more keys and NKEM v3 for one. Every
 * public key is loaded and validated before output is created. An empty
 * list, more than NEKOKEM_MAX_RECIPIENTS keys, or two keys that are the same
 * or share either component fails; no key is ever skipped or substituted.
 * Returns NEKOKEM_OPERATION_SUCCESS, _ERROR or _CANCELLED, as above.
 */
NEKOKEM_API int nekokem_encrypt_file_multi_with_progress(
    const char *input_path,
    const char *output_path,
    const char *const *public_key_paths,
    size_t public_key_count,
    NekoKEMProgressCallback progress_callback,
    void *progress_user_data);

/*
 * Decrypts the NKEM v3 single-recipient container, the NKEM v4
 * multi-recipient container and the signed NKEM v5 container, which is
 * verified completely without a policy. Other versions are rejected.
 */
NEKOKEM_API int nekokem_decrypt_file(
    const char *input_path,
    const char *output_path,
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len);

/* Progress and cancellation equivalent of nekokem_decrypt_file(). */
NEKOKEM_API int nekokem_decrypt_file_with_progress(
    const char *input_path,
    const char *output_path,
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len,
    NekoKEMProgressCallback progress_callback,
    void *progress_user_data);

NEKOKEM_API int nekokem_public_key_fingerprint(
    const char *public_key_path,
    char *output,
    size_t output_size);

/*
 * Signed NKEM v5 containers and identity keys; see docs/NKEM-v5.md.
 *
 * Key generation writes four-component identity keys (X448, ML-KEM-1024,
 * Ed448, ML-DSA-87) with an NKPR v2 private key. Two-component keys stay
 * valid recipients and decryption keys, but cannot sign or be trusted
 * signers. nekokem_public_key_fingerprint() returns the identity
 * fingerprint for a four-component key and the legacy fingerprint for a
 * two-component key.
 */

/*
 * Adds Ed448 and ML-DSA-87 keys to an NKPR v1 key pair, keeping its X448
 * and ML-KEM-1024 keys, and atomically replaces both files (NKPR v2, same
 * password). The public file must hold this private key's public keys.
 */
NEKOKEM_API int nekokem_upgrade_to_identity_keys(
    const char *public_key_path,
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len);

/* 1 for a valid four-component identity public key, 0 otherwise. */
NEKOKEM_API int nekokem_public_key_can_verify(const char *public_key_path);

/*
 * The legacy fingerprint over only the X448 and ML-KEM-1024 components,
 * for both key kinds. Front ends deduplicate contacts by it.
 */
NEKOKEM_API int nekokem_public_key_legacy_fingerprint(
    const char *public_key_path,
    char *output,
    size_t output_size);

/* Writes only the two encryption components, readable by NekoKEM <= 4.4. */
NEKOKEM_API int nekokem_export_legacy_public_key(
    const char *public_key_path,
    const char *output_path);

/* Encryption signs unless this flag is set; then it writes v3 or v4. */
#define NEKOKEM_ENCRYPT_UNSIGNED 0x1U

typedef struct {
    size_t struct_size;               /* sizeof(NekoKEMEncryptOptions) */
    uint32_t flags;                   /* NEKOKEM_ENCRYPT_* */
    const char *sender_private_key_path;
    const unsigned char *password;    /* borrowed for the call */
    size_t password_len;
} NekoKEMEncryptOptions;

typedef struct {
    size_t struct_size;               /* sizeof(NekoKEMEncryptResult) */
    int format_version;               /* 3, 4 or 5 as written */
    int signed_by_sender;             /* 1 for v5 */
} NekoKEMEncryptResult;

/*
 * Writes NKEM v5 signed by the sender's identity key for 1 to 64
 * recipients. With NEKOKEM_ENCRYPT_UNSIGNED it writes v3 or v4 exactly as
 * nekokem_encrypt_file_multi_with_progress(). A missing or two-component
 * sender key is an error, never a silent unsigned fallback. Returns
 * NEKOKEM_OPERATION_SUCCESS, _ERROR or _CANCELLED.
 */
NEKOKEM_API int nekokem_encrypt_file_v5_with_progress(
    const char *input_path,
    const char *output_path,
    const char *const *public_key_paths,
    size_t public_key_count,
    const NekoKEMEncryptOptions *options,
    NekoKEMEncryptResult *result,
    NekoKEMProgressCallback progress_callback,
    void *progress_user_data);

/* Reject v3 and v4 containers. */
#define NEKOKEM_VERIFY_REQUIRE_SIGNATURE 0x1U
/* Reject v5 signers outside trusted_signer_paths (implies a signature). */
#define NEKOKEM_VERIFY_REQUIRE_TRUSTED 0x2U

typedef struct {
    size_t struct_size;               /* sizeof(NekoKEMVerifyPolicy) */
    uint32_t flags;                   /* NEKOKEM_VERIFY_* */
    /* Identity public keys the user trusts, such as contacts and their own;
     * two-component keys are ignored, a missing or invalid file fails. */
    const char *const *trusted_signer_paths;
    size_t trusted_signer_count;
    /* When set, a v5 signer's identity is written there as a public key. */
    const char *signer_public_key_output_path;
} NekoKEMVerifyPolicy;

#define NEKOKEM_SIGNATURE_UNSIGNED 0          /* v3 or v4 */
#define NEKOKEM_SIGNATURE_TRUSTED 1           /* v5, signer is trusted */
#define NEKOKEM_SIGNATURE_VALID_UNTRUSTED 2   /* v5, signer is not listed */

typedef struct {
    size_t struct_size;               /* sizeof(NekoKEMVerifyResult) */
    int format_version;               /* 3, 4 or 5 */
    int signature_status;             /* NEKOKEM_SIGNATURE_* */
    size_t trusted_signer_index;      /* valid for _TRUSTED */
    char signer_fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE];
} NekoKEMVerifyResult;

/* Policy failures; no output is committed for any of them. */
#define NEKOKEM_ERROR_SIGNATURE_INVALID (-10)
#define NEKOKEM_ERROR_SIGNATURE_REQUIRED (-11)
#define NEKOKEM_ERROR_SIGNER_NOT_TRUSTED (-12)

/*
 * Decrypts v3, v4 and v5 and enforces policy (NULL means no flags). Core
 * decides the signature status and matches the signer; output is committed
 * only after the payload and every check pass. Returns
 * NEKOKEM_OPERATION_SUCCESS, _ERROR, _CANCELLED or a NEKOKEM_ERROR_* code.
 */
NEKOKEM_API int nekokem_decrypt_file_verified_with_progress(
    const char *input_path,
    const char *output_path,
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len,
    const NekoKEMVerifyPolicy *policy,
    NekoKEMVerifyResult *result,
    NekoKEMProgressCallback progress_callback,
    void *progress_user_data);

/*
 * Managed-key helpers for application front ends. The existence check only
 * accepts a structurally valid NKPR regular file with mode 0600 and never
 * derives a protection key. Public-key export validates and re-serializes
 * the two Hybrid public components. Deletion is idempotent.
 */
NEKOKEM_API int nekokem_private_key_exists(
    const char *private_key_path);

/* Decrypts and validates an NKPR key once, then immediately frees it. */
NEKOKEM_API int nekokem_check_private_key_password(
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len);

NEKOKEM_API int nekokem_export_public_key(
    const char *public_key_path,
    const char *output_path);

/*
 * Returns 1 when the key and every residue of its interrupted transactions
 * are gone (or there was no key), NEKOKEM_DELETE_RESIDUE_RETAINED when the
 * key is gone but a residue file that may still hold a private key was kept
 * and reported on stderr, and 0 when the key could not be deleted. A fresh
 * staging file is kept because another operation may still be writing it.
 */
#define NEKOKEM_DELETE_RESIDUE_RETAINED 2

NEKOKEM_API int nekokem_delete_private_key(
    const char *private_key_path);


/*
 * Lets a UI decide whether it must request a password: 1 when the file is an
 * NKPR container, judged by its contents rather than its name.
 */
NEKOKEM_API int nekokem_private_key_requires_password(
    const char *private_key_path);

#ifdef __cplusplus
}
#endif

#endif
