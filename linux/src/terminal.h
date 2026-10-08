#ifndef NEKOKEM_CLI_TERMINAL_H
#define NEKOKEM_CLI_TERMINAL_H
#include <stdio.h>
#include <errno.h>
#ifdef _WIN32
#include <windows.h>
#include <io.h>
#include "windows_io.h"
typedef DWORD CliTerminal;
/* The console mode to restore when Ctrl+C or a closed console ends a hidden prompt. */
static DWORD cli_visible_mode;
static volatile LONG cli_echo_hidden;
/* Read native console UTF-16 explicitly: one-byte CRT reads are unreliable for
 * UTF-8 console code points. Pipes remain raw UTF-8 bytes for automation. */
static unsigned char cli_console_bytes[4];
static int cli_console_position, cli_console_count;
static int cli_console_input, cli_console_error, cli_console_eof;
static inline int cli_input_getc(void)
{
    HANDLE input = GetStdHandle(STD_INPUT_HANDLE);
    DWORD mode;
    if (!GetConsoleMode(input, &mode)) {
        cli_console_input = 0;
        return fgetc(stdin);
    }
    cli_console_input = 1;
    if (cli_console_position == cli_console_count) {
        wchar_t wide[2] = {0, 0};
        DWORD count = 0;
        int units = 1;
        cli_console_error = cli_console_eof = 0;
        cli_console_position = cli_console_count = 0;
        if (!ReadConsoleW(input, wide, 1, &count, NULL)) goto failed;
        if (count == 0) { cli_console_eof = 1; return EOF; }
        if (wide[0] >= 0xd800 && wide[0] <= 0xdbff) {
            if (!ReadConsoleW(input, wide+1, 1, &count, NULL) || count != 1 ||
                wide[1] < 0xdc00 || wide[1] > 0xdfff) goto failed;
            units = 2;
        }
        cli_console_count = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS,
                wide, units, (char *)cli_console_bytes, 4, NULL, NULL);
        SecureZeroMemory(wide, sizeof(wide));
        if (cli_console_count <= 0) goto failed;
        goto converted;
failed:
        SecureZeroMemory(wide, sizeof(wide));
        SecureZeroMemory(cli_console_bytes, sizeof(cli_console_bytes));
        cli_console_position = cli_console_count = 0;
        cli_console_error = 1;
        errno = EIO;
        return EOF;
    }
converted:;
    int value = cli_console_bytes[cli_console_position];
    cli_console_bytes[cli_console_position++] = 0;
    return value;
}
static inline int cli_input_error(void)
{
    return cli_console_input ? cli_console_error : ferror(stdin);
}
static inline int cli_input_eof(void)
{
    return cli_console_input ? cli_console_eof : feof(stdin);
}
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
    if (!GetConsoleMode(input, original)) return 0;
    cli_visible_mode = *original;
    InterlockedExchange(&cli_echo_hidden, 1);
    if (!SetConsoleMode(input, *original & ~(DWORD)ENABLE_ECHO_INPUT)) {
        InterlockedExchange(&cli_echo_hidden, 0);
        return 0;
    }
    if (!FlushConsoleInputBuffer(input)) {
        (void)SetConsoleMode(input, *original);
        InterlockedExchange(&cli_echo_hidden, 0);
        return 0;
    }
    *disabled = 1;
    return 1;
}
static inline int cli_terminal_restore(CliTerminal *original, int discard)
{
    HANDLE input = GetStdHandle(STD_INPUT_HANDLE);
    int restored = SetConsoleMode(input, *original) != 0;
    InterlockedExchange(&cli_echo_hidden, 0);
    if (discard && !FlushConsoleInputBuffer(input)) return 0;
    return restored;
}
#else
#include <signal.h>
#include <stdatomic.h>
#include <termios.h>
#include <unistd.h>
static inline int cli_input_getc(void) { return fgetc(stdin); }
static inline int cli_input_error(void) { return ferror(stdin); }
static inline int cli_input_eof(void) { return feof(stdin); }
typedef struct termios CliTerminal;
/* What the signal handlers in cli.c apply while a prompt hides input. */
static struct termios cli_visible_terminal;
static struct termios cli_hidden_terminal;
static volatile sig_atomic_t cli_echo_hidden;
static inline int cli_terminal_hide(CliTerminal *original, int *disabled)
{
    if (!isatty(STDIN_FILENO)) return 1;
    if (tcgetattr(STDIN_FILENO, original) != 0) return 0;
    struct termios hidden = *original;
    hidden.c_lflag &= (tcflag_t)~ECHO;
    cli_visible_terminal = *original;
    cli_hidden_terminal = hidden;
    atomic_signal_fence(memory_order_seq_cst);
    cli_echo_hidden = 1;
    if (tcsetattr(STDIN_FILENO, TCSAFLUSH, &hidden) != 0) {
        cli_echo_hidden = 0;
        return 0;
    }
    *disabled = 1;
    return 1;
}
static inline int cli_terminal_restore(CliTerminal *original, int discard)
{
    int restored = tcsetattr(STDIN_FILENO, discard ? TCSAFLUSH : TCSANOW, original) == 0;
    cli_echo_hidden = 0;
    return restored;
}
#endif
#endif
