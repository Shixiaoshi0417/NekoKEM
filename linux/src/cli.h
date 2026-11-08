#ifndef NEKOKEM_CLI_H
#define NEKOKEM_CLI_H

#include <stddef.h>

/*
 * Termination signals (Ctrl+C on Windows) restore terminal echo and remove a
 * pasted key and partial outputs before the process dies; Ctrl+Z shows input
 * again while stopped. Call once at start, before any prompt.
 */
void cli_install_interrupt_handlers(void);
/* Disable process dumps before reading key material or passwords. */
int cli_disable_core_dumps(void);
void cli_print_help(const char *program);
void cli_print_usage(const char *program);

int cli_run_interactive_menu(void);
/* replace != 0 rotates existing keys; otherwise they are never replaced. */
int cli_run_hybrid_keygen(int replace);
/*
 * One key writes v3 and more write v4 with unsigned_output; otherwise the
 * file is NKEM v5 signed with sign_key_path (keys/private.key.enc if NULL).
 */
int cli_run_hybrid_encrypt(const char *input_path,
                           const char *output_path,
                           const char *const *public_key_paths,
                           size_t public_key_count,
                           const char *sign_key_path,
                           int unsigned_output);

typedef struct {
    const char *const *trusted_paths;
    size_t trusted_count;
    int require_signature;
    int require_trusted;
    const char *save_signer_path;
} CliVerifyOptions;

/* verify may be NULL: report the signature status without a policy. */
int cli_run_hybrid_decrypt(const char *input_path,
                           const char *output_path,
                           const char *private_key_path,
                           const CliVerifyOptions *verify);
/* NULL paths mean keys/public.key and keys/private.key.enc. */
int cli_run_upgrade_key(const char *public_key_path,
                        const char *private_key_path);
int cli_run_export_legacy_public_key(const char *public_key_path,
                                     const char *output_path);
int cli_run_fingerprint(const char *public_key_path);

#endif
