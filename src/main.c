#include "cli.h"
#include "file.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv)
{
    if (!file_disable_buffering(stdin)) {
        return EXIT_FAILURE;
    }
    if (argc == 1) {
        return cli_run_interactive_menu() ? EXIT_SUCCESS : EXIT_FAILURE;
    }
    if (argc == 2 && strcmp(argv[1], "keygen") == 0) {
        return cli_run_v1_keygen() ? EXIT_SUCCESS : EXIT_FAILURE;
    }
    if (argc == 3 && strcmp(argv[1], "keygen") == 0 &&
        strcmp(argv[2], "hybrid") == 0) {
        return cli_run_hybrid_keygen() ? EXIT_SUCCESS : EXIT_FAILURE;
    }
    if (argc == 5 && strcmp(argv[1], "encrypt") == 0) {
        return cli_run_v1_encrypt(argv[2], argv[3], argv[4])
                   ? EXIT_SUCCESS
                   : EXIT_FAILURE;
    }
    if (argc == 5 && strcmp(argv[1], "decrypt") == 0) {
        return cli_run_v1_decrypt(argv[2], argv[3], argv[4])
                   ? EXIT_SUCCESS
                   : EXIT_FAILURE;
    }
    if (argc == 6 && strcmp(argv[1], "encrypt") == 0 &&
        strcmp(argv[2], "hybrid") == 0) {
        return cli_run_hybrid_encrypt(argv[3], argv[4], argv[5])
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
