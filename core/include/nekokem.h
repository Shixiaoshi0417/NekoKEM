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
 * All legacy functions return 1 on success and 0 on error. Password bytes
 * are borrowed for the duration of the call and are never retained by Core.
 */
NEKOKEM_API int nekokem_generate_keypair(
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

/* Strictly dispatches v1/v2 legacy paths or the v3 split-salt path. */
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

NEKOKEM_API int nekokem_delete_private_key(
    const char *private_key_path);

/* Compatibility entry points for the existing NKEM v1 CLI commands. */
NEKOKEM_API int nekokem_generate_v1_keypair(
    const char *public_key_path,
    const char *private_key_path);

NEKOKEM_API int nekokem_encrypt_file_v1(
    const char *input_path,
    const char *output_path,
    const char *public_key_path);

NEKOKEM_API int nekokem_decrypt_file_v1(
    const char *input_path,
    const char *output_path,
    const char *private_key_path);

/* Compatibility entry points for producing and consuming NKEM v2. */
NEKOKEM_API int nekokem_encrypt_file_v2(
    const char *input_path,
    const char *output_path,
    const char *public_key_path);
NEKOKEM_API int nekokem_decrypt_file_v2(
    const char *input_path,
    const char *output_path,
    const char *private_key_path,
    const unsigned char *password,
    size_t password_len);

/* Lets a UI decide whether it must request a password. */
NEKOKEM_API int nekokem_private_key_requires_password(
    const char *private_key_path);

#ifdef __cplusplus
}
#endif

#endif
