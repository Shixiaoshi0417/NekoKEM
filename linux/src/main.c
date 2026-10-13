#include "cli.h"
#include "i18n.h"
#include "file.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define NEKOKEM_CLI_VERSION "4.1.0"

#ifdef _WIN32
int cli_main(int argc, char **argv)
#else
int main(int argc, char **argv)
#endif
{
    cli_install_interrupt_handlers();
    if (!cli_language_init(&argc, argv)) return EXIT_FAILURE;
    if (argc == 2 && strcmp(argv[1], "--help") == 0) {
        cli_print_help(argv[0]);
        return EXIT_SUCCESS;
    }
    if (argc == 3 && strcmp(argv[1], "--set-lang") == 0) {
        return cli_language_save(argv[2]) ? EXIT_SUCCESS : EXIT_FAILURE;
    }
    if (argc == 2 && strcmp(argv[1], "--version") == 0) {
        return puts("NekoKEM " NEKOKEM_CLI_VERSION) == EOF
                   ? EXIT_FAILURE
                   : EXIT_SUCCESS;
    }
    if (!file_disable_buffering(stdin)) {
        return EXIT_FAILURE;
    }
    if (argc == 1) {
        return cli_run_interactive_menu() ? EXIT_SUCCESS : EXIT_FAILURE;
    }
    if (argc == 2 && strcmp(argv[1], "keygen") == 0) {
        return cli_run_hybrid_keygen(0) ? EXIT_SUCCESS : EXIT_FAILURE;
    }
    if (argc == 3 && strcmp(argv[1], "keygen") == 0 &&
        strcmp(argv[2], "hybrid") == 0) {
        return cli_run_hybrid_keygen(0) ? EXIT_SUCCESS : EXIT_FAILURE;
    }
    if ((argc == 3 && strcmp(argv[1], "keygen") == 0 &&
         strcmp(argv[2], "--replace") == 0) ||
        (argc == 4 && strcmp(argv[1], "keygen") == 0 &&
         strcmp(argv[2], "hybrid") == 0 &&
         strcmp(argv[3], "--replace") == 0)) {
        return cli_run_hybrid_keygen(1) ? EXIT_SUCCESS : EXIT_FAILURE;
    }
    if (argc == 6 && strcmp(argv[1], "encrypt") == 0 &&
        strcmp(argv[2], "hybrid") == 0) {
        return cli_run_hybrid_encrypt(argv[3], argv[4], argv[5])
                   ? EXIT_SUCCESS
                   : EXIT_FAILURE;
    }
    if (argc > 6 && strcmp(argv[1], "encrypt") == 0 &&
        strcmp(argv[2], "hybrid") == 0) {
        return cli_run_hybrid_encrypt_multi(
                   argv[3], argv[4], (const char *const *)(argv + 5),
                   (size_t)(argc - 5))
                   ? EXIT_SUCCESS
                   : EXIT_FAILURE;
    }
    if (argc == 6 && strcmp(argv[1], "decrypt") == 0 &&
        strcmp(argv[2], "hybrid") == 0) {
        return cli_run_hybrid_decrypt(argv[3], argv[4], argv[5])
                   ? EXIT_SUCCESS
                   : EXIT_FAILURE;
    }

    cli_print_usage(argv[0]);
    return EXIT_FAILURE;
}
