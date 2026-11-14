#include "cli.h"
#include "i18n.h"
#include "file.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define NEKOKEM_CLI_VERSION "5.1.0"

/*
 * Options come right after "encrypt hybrid" or "decrypt hybrid". The first
 * argument that is not one of them starts the paths, so a path such as
 * "--lang" or "--" is still a path, as in NekoKEM 4.x.
 */
static int option_value(int count, char **args, int *index,
                        const char **value)
{
    if (*index + 1 >= count || *value != NULL) {
        return 0;
    }
    *value = args[++*index];
    return 1;
}

static int run_encrypt(int count, char **args, const char *program)
{
    const char *sign_key = NULL;
    int unsigned_output = 0;
    int index;

    for (index = 0; index < count; ++index) {
        if (strcmp(args[index], "--unsigned") == 0) {
            unsigned_output = 1;
        } else if (strcmp(args[index], "--sign-key") == 0) {
            if (!option_value(count, args, &index, &sign_key)) {
                goto usage;
            }
        } else {
            break;
        }
    }
    if (count - index < 3 || (unsigned_output != 0 && sign_key != NULL)) {
        goto usage;
    }
    return cli_run_hybrid_encrypt(args[index], args[index + 1],
                                  (const char *const *)(args + index + 2),
                                  (size_t)(count - index - 2), sign_key,
                                  unsigned_output)
               ? EXIT_SUCCESS
               : EXIT_FAILURE;

usage:
    cli_print_usage(program);
    return EXIT_FAILURE;
}

static int run_decrypt(int count, char **args, const char *program)
{
    CliVerifyOptions verify = {NULL, 0U, 0, 0, NULL};
    const char **trusted;
    int index;
    int result = EXIT_FAILURE;

    trusted = calloc(count > 0 ? (size_t)count : 1U, sizeof(*trusted));
    if (trusted == NULL) {
        return EXIT_FAILURE;
    }
    for (index = 0; index < count; ++index) {
        if (strcmp(args[index], "--trust") == 0) {
            if (!option_value(count, args, &index,
                              &trusted[verify.trusted_count])) {
                goto usage;
            }
            ++verify.trusted_count;
        } else if (strcmp(args[index], "--require-signature") == 0) {
            verify.require_signature = 1;
        } else if (strcmp(args[index], "--require-trusted") == 0) {
            verify.require_trusted = 1;
        } else if (strcmp(args[index], "--save-signer") == 0) {
            if (!option_value(count, args, &index,
                              &verify.save_signer_path)) {
                goto usage;
            }
        } else {
            break;
        }
    }
    if (count - index != 3) {
        goto usage;
    }
    verify.trusted_paths = trusted;
    result = cli_run_hybrid_decrypt(args[index], args[index + 1],
                                    args[index + 2], &verify)
                 ? EXIT_SUCCESS
                 : EXIT_FAILURE;
    free(trusted);
    return result;

usage:
    free(trusted);
    cli_print_usage(program);
    return EXIT_FAILURE;
}

#ifdef _WIN32
int cli_main(int argc, char **argv)
#else
int main(int argc, char **argv)
#endif
{
    if (!cli_disable_core_dumps()) return EXIT_FAILURE;
    cli_install_interrupt_handlers();
    if (!cli_language_init(&argc, argv)) return EXIT_FAILURE;
    if (argc == 2 && strcmp(argv[1], "--help") == 0) {
        cli_print_help(argv[0]);
        return fflush(stdout) == 0 && !ferror(stdout)
                   ? EXIT_SUCCESS : EXIT_FAILURE;
    }
    if (argc == 3 && strcmp(argv[1], "--set-lang") == 0) {
        return cli_language_save(argv[2]) ? EXIT_SUCCESS : EXIT_FAILURE;
    }
    if (argc == 2 && strcmp(argv[1], "--version") == 0) {
        int written = puts("NekoKEM " NEKOKEM_CLI_VERSION);
        return written != EOF && fflush(stdout) == 0 && !ferror(stdout)
                   ? EXIT_SUCCESS : EXIT_FAILURE;
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
    if (argc >= 3 && strcmp(argv[1], "encrypt") == 0 &&
        strcmp(argv[2], "hybrid") == 0) {
        return run_encrypt(argc - 3, argv + 3, argv[0]);
    }
    if (argc >= 3 && strcmp(argv[1], "decrypt") == 0 &&
        strcmp(argv[2], "hybrid") == 0) {
        return run_decrypt(argc - 3, argv + 3, argv[0]);
    }
    if (argc == 2 && strcmp(argv[1], "upgrade-key") == 0) {
        return cli_run_upgrade_key(NULL, NULL) ? EXIT_SUCCESS : EXIT_FAILURE;
    }
    if (argc == 4 && strcmp(argv[1], "upgrade-key") == 0) {
        return cli_run_upgrade_key(argv[2], argv[3]) ? EXIT_SUCCESS
                                                      : EXIT_FAILURE;
    }
    if (argc == 4 && strcmp(argv[1], "export-legacy-public-key") == 0) {
        return cli_run_export_legacy_public_key(argv[2], argv[3])
                   ? EXIT_SUCCESS
                   : EXIT_FAILURE;
    }
    if (argc == 3 && strcmp(argv[1], "fingerprint") == 0) {
        return cli_run_fingerprint(argv[2]) ? EXIT_SUCCESS : EXIT_FAILURE;
    }

    cli_print_usage(argv[0]);
    return EXIT_FAILURE;
}
