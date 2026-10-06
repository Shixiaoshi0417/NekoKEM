#ifndef NEKOKEM_CLI_H
#define NEKOKEM_CLI_H

#include <stddef.h>

void cli_print_help(const char *program);
void cli_print_usage(const char *program);

int cli_run_interactive_menu(void);
int cli_run_hybrid_keygen(void);
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
