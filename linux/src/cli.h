#ifndef NEKOKEM_CLI_H
#define NEKOKEM_CLI_H

#include <stddef.h>

/*
 * Termination signals (Ctrl+C on Windows) restore terminal echo and remove a
 * pasted key and partial outputs before the process dies; Ctrl+Z shows input
 * again while stopped. Call once at start, before any prompt.
 */
void cli_install_interrupt_handlers(void);
void cli_print_help(const char *program);
void cli_print_usage(const char *program);

int cli_run_interactive_menu(void);
/* replace != 0 rotates existing keys; otherwise they are never replaced. */
int cli_run_hybrid_keygen(int replace);
int cli_run_hybrid_encrypt(const char *input_path,
                           const char *output_path,
                           const char *public_key_path);
/* Two or more keys: one NKEM v4 file that every listed key decrypts. */
int cli_run_hybrid_encrypt_multi(const char *input_path,
                                 const char *output_path,
                                 const char *const *public_key_paths,
                                 size_t public_key_count);
int cli_run_hybrid_decrypt(const char *input_path,
                           const char *output_path,
                           const char *private_key_path);

#endif
