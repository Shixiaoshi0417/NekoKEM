#ifndef NEKOKEM_CLI_H
#define NEKOKEM_CLI_H

void cli_print_usage(const char *program);

int cli_run_interactive_menu(void);
int cli_run_v1_keygen(void);
int cli_run_hybrid_keygen(void);
int cli_run_v1_encrypt(const char *input_path,
                       const char *output_path,
                       const char *public_key_path);
int cli_run_v1_decrypt(const char *input_path,
                       const char *output_path,
                       const char *private_key_path);
int cli_run_hybrid_encrypt(const char *input_path,
                           const char *output_path,
                           const char *public_key_path);
int cli_run_hybrid_decrypt(const char *input_path,
                           const char *output_path,
                           const char *private_key_path);

#endif
