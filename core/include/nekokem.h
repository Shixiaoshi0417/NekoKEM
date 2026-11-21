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
 * Private keys: NKPR v3 commits to its password, so a file someone else
 * wrote cannot be built to open under several passwords. NKPR v1 and v2 do
 * not; calls that unlock a private key refuse them unless the caller says
 * the file is the installation's own key (the *_LEGACY_* flags below), and
 * the refusal comes before the password is used. See docs/key-commitment.md.
 *
 * Creates a new identity key pair (NKPR v3) and never overwrites: if either
 * key file already exists, nothing is written and 0 is returned.
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
 * verified completely without a policy. Other versions are rejected. With
 * no policy, an NKPR v1 or v2 private key is refused; use
 * nekokem_decrypt_file_verified_with_progress() with
 * NEKOKEM_VERIFY_LEGACY_PRIVATE_KEY for the installation's own older key.
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
 * Ed448, ML-DSA-87) with an NKPR v3 private key. Two-component keys stay
 * valid recipients and decryption keys, but cannot sign or be trusted
 * signers. nekokem_public_key_fingerprint() returns the identity
 * fingerprint for a four-component key and the legacy fingerprint for a
 * two-component key.
 */

/*
 * Adds Ed448 and ML-DSA-87 keys to an NKPR v1 key pair, keeping its X448
 * and ML-KEM-1024 keys, and atomically replaces both files (NKPR v3, same
 * password). The public file must hold this private key's public keys.
 * The key is the caller's own, so NKPR v1 is accepted.
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
/* The sender key is the installation's own: NKPR v1 and v2 are accepted. */
#define NEKOKEM_ENCRYPT_LEGACY_SENDER_KEY 0x2U

/* Longest original file name a v5 container records, in UTF-8 bytes. */
#define NEKOKEM_ORIGINAL_NAME_MAX 1016U
#define NEKOKEM_ORIGINAL_NAME_SIZE (NEKOKEM_ORIGINAL_NAME_MAX + 1U)

typedef struct {
    size_t struct_size;               /* sizeof(NekoKEMEncryptOptions) */
    uint32_t flags;                   /* NEKOKEM_ENCRYPT_* */
    const char *sender_private_key_path;
    const unsigned char *password;    /* borrowed for the call */
    size_t password_len;
    /* Optional UTF-8 file name (no directory) that signed v5 encryption
     * records inside the signature block, so decryption can restore it.
     * NULL writes no file information. It must pass
     * nekokem_original_name_is_valid(); unsigned v3/v4 never record it. */
    const char *original_name;
} NekoKEMEncryptOptions;

/*
 * 1 when name can be recorded as an original file name: 1 to
 * NEKOKEM_ORIGINAL_NAME_MAX bytes of well-formed UTF-8, not "." or "..",
 * without '/', '\\', control characters or bidirectional formatting
 * characters. Front ends still adapt it to their own file system.
 */
NEKOKEM_API int nekokem_original_name_is_valid(const char *name);

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

/* Most trusted signers one verified decryption accepts. Separate from the
 * recipient limit: a user may trust every saved contact plus their own key. */
#define NEKOKEM_MAX_TRUSTED_SIGNERS 1024U

/* Reject v3 and v4 containers. */
#define NEKOKEM_VERIFY_REQUIRE_SIGNATURE 0x1U
/* Reject v5 signers outside trusted_signer_paths (implies a signature). */
#define NEKOKEM_VERIFY_REQUIRE_TRUSTED 0x2U
/* The private key is the installation's own: NKPR v1 and v2 are accepted. */
#define NEKOKEM_VERIFY_LEGACY_PRIVATE_KEY 0x4U

typedef struct {
    size_t struct_size;               /* sizeof(NekoKEMVerifyPolicy) */
    uint32_t flags;                   /* NEKOKEM_VERIFY_* */
    /* Identity public keys the user trusts, such as contacts and their own;
     * two-component keys are ignored, a missing or invalid file fails. */
    const char *const *trusted_signer_paths;
    size_t trusted_signer_count;      /* at most NEKOKEM_MAX_TRUSTED_SIGNERS */
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
    /* The signed original file name, or "" when the file records none. */
    char original_name[NEKOKEM_ORIGINAL_NAME_SIZE];
} NekoKEMVerifyResult;

/* Policy failures; no output is committed for any of them. */
#define NEKOKEM_ERROR_SIGNATURE_INVALID (-10)
#define NEKOKEM_ERROR_SIGNATURE_REQUIRED (-11)
#define NEKOKEM_ERROR_SIGNER_NOT_TRUSTED (-12)
/*
 * The private key is NKPR v1 or v2 and the call did not accept older formats.
 * Nothing was unlocked; front ends offer the explicit legacy import instead.
 */
#define NEKOKEM_ERROR_LEGACY_PRIVATE_KEY (-13)

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

/*
 * Decrypts and validates an NKPR key once, then immediately frees it. An
 * NKPR v1 or v2 key is refused; see nekokem_check_private_key_password_flags.
 */
NEKOKEM_API int nekokem_check_private_key_password(
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len);

/* The key is the installation's own: NKPR v1 and v2 are accepted. */
#define NEKOKEM_PRIVATE_KEY_LEGACY 0x1U

/* As above with NEKOKEM_PRIVATE_KEY_* flags. Returns 1, 0 or
 * NEKOKEM_ERROR_LEGACY_PRIVATE_KEY. */
NEKOKEM_API int nekokem_check_private_key_password_flags(
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len,
    uint32_t flags);

/*
 * 1 when the file is an NKPR v1 or v2 private key, judged from its header
 * without a password, so front ends can explain the legacy rule before
 * asking for one. 0 for NKPR v3, plaintext PEM or anything else.
 */
NEKOKEM_API int nekokem_private_key_is_legacy(const char *private_key_path);

/*
 * Rewrites a key pair as NKPR v3 with the same password: an NKPR v2 key
 * keeps its keys, an NKPR v1 key is upgraded to an identity key first, as
 * nekokem_upgrade_to_identity_keys() does. Both files are replaced
 * atomically under the pair lock. An NKPR v3 key is not rewritten; its
 * password and pair are still checked, so 1 always means the password is
 * right and the public file belongs to the private key.
 * NKPR v1 and v2 are opened only with NEKOKEM_PRIVATE_KEY_LEGACY (the
 * installation's own key, or one the user confirmed as their own); without
 * it they are refused before the password is used. The check runs on the
 * bytes that are decrypted. Returns 1, 0 or NEKOKEM_ERROR_LEGACY_PRIVATE_KEY.
 */
NEKOKEM_API int nekokem_update_private_key_format(
    const char *public_key_path,
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len,
    uint32_t flags);

/*
 * Writes a private key to output_path in the format NekoKEM 5.2 and earlier
 * read (NKPR v2, or v1 for a two-component key), protected by the same
 * password, for moving it to an older installation. An NKPR v1 or v2 input
 * is opened only with NEKOKEM_PRIVATE_KEY_LEGACY, as above. Returns 1, 0 or
 * NEKOKEM_ERROR_LEGACY_PRIVATE_KEY.
 */
NEKOKEM_API int nekokem_export_private_key_legacy(
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len,
    const char *output_path,
    uint32_t flags);

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
