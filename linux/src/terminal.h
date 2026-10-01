#ifndef NEKOKEM_CLI_TERMINAL_H
#define NEKOKEM_CLI_TERMINAL_H
#ifdef _WIN32
#include <windows.h>
#include <io.h>
#include "windows_io.h"
typedef DWORD CliTerminal;
#define fsync _commit
#define fileno _fileno
#define close _close
static inline int cli_unlink(const char *path) { return windows_delete_regular(path) ? 0 : -1; }
static inline int cli_rmdir(const char *path) { return windows_remove_directory(path) ? 0 : -1; }
#define unlink cli_unlink
#define rmdir cli_rmdir
static inline int cli_terminal_hide(CliTerminal *original, int *disabled)
{
    HANDLE input = GetStdHandle(STD_INPUT_HANDLE);
    if (GetFileType(input) != FILE_TYPE_CHAR) return 1;
    if (!GetConsoleMode(input, original) ||
        !SetConsoleMode(input, *original & ~(DWORD)ENABLE_ECHO_INPUT)) return 0;
    *disabled = 1;
    return 1;
}
static inline int cli_terminal_restore(CliTerminal *original, int discard)
{
    (void)discard;
    return SetConsoleMode(GetStdHandle(STD_INPUT_HANDLE), *original) != 0;
}
#else
#include <termios.h>
#include <unistd.h>
typedef struct termios CliTerminal;
static inline int cli_terminal_hide(CliTerminal *original, int *disabled)
{
    if (!isatty(STDIN_FILENO)) return 1;
    if (tcgetattr(STDIN_FILENO, original) != 0) return 0;
    struct termios hidden = *original;
    hidden.c_lflag &= (tcflag_t)~ECHO;
    if (tcsetattr(STDIN_FILENO, TCSAFLUSH, &hidden) != 0) return 0;
    *disabled = 1;
    return 1;
}
static inline int cli_terminal_restore(CliTerminal *original, int discard)
{
    return tcsetattr(STDIN_FILENO, discard ? TCSAFLUSH : TCSANOW, original) == 0;
}
#endif
#endif
