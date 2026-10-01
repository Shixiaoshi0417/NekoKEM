#include <windows.h>
#include <fcntl.h>
#include <io.h>
#include <stdlib.h>
#include <stdio.h>
#include <wchar.h>

int cli_main(int argc, char **argv);

int wmain(int argc, wchar_t **arguments)
{
    char **utf8 = calloc((size_t)argc + 1, sizeof(*utf8));
    int result = EXIT_FAILURE;
    UINT input_cp = GetConsoleCP(), output_cp = GetConsoleOutputCP();
    if (utf8 == NULL) return result;
    for (int i = 0; i < argc; ++i) {
        int size = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, arguments[i], -1,
                                      NULL, 0, NULL, NULL);
        if (size <= 0) goto cleanup;
        utf8[i] = malloc((size_t)size);
        if (utf8[i] == NULL || WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS,
            arguments[i], -1, utf8[i], size, NULL, NULL) != size) goto cleanup;
    }
    if (input_cp != 0 && !SetConsoleCP(CP_UTF8)) goto cleanup;
    if (output_cp != 0 && !SetConsoleOutputCP(CP_UTF8)) goto cleanup;
    if (_setmode(_fileno(stdin), _O_BINARY) == -1 ||
        _setmode(_fileno(stdout), _O_BINARY) == -1 ||
        _setmode(_fileno(stderr), _O_BINARY) == -1) goto cleanup;
    /* cli_language_init compacts argv; preserve allocation ownership separately. */
    char **argv = malloc(((size_t)argc+1)*sizeof(*argv));
    if (argv == NULL) goto cleanup;
    for (int i = 0; i <= argc; ++i) argv[i] = utf8[i];
    result = cli_main(argc, argv);
    free(argv);
cleanup:
    if (input_cp != 0) (void)SetConsoleCP(input_cp);
    if (output_cp != 0) (void)SetConsoleOutputCP(output_cp);
    for (int i = 0; i < argc; ++i) free(utf8[i]);
    free(utf8);
    return result;
}
