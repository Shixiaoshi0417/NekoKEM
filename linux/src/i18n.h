#ifndef NEKOKEM_CLI_I18N_H
#define NEKOKEM_CLI_I18N_H

/* Strip global language options; return 0 for an invalid command line. */
int cli_language_init(int *argc, char **argv);
int cli_language_save(const char *language);

#endif
