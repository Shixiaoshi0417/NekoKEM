/* Exercise the real Windows console mode and Unicode input without a GUI. */
#include "terminal.h"
#include <stdio.h>
#include <stdlib.h>
#include <wchar.h>
#include <string.h>
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"Console check failed at line %d\n",__LINE__); return EXIT_FAILURE; } } while (0)
int main(void)
{
    HANDLE stdout_handle = GetStdHandle(STD_OUTPUT_HANDLE);
    HANDLE stderr_handle = GetStdHandle(STD_ERROR_HANDLE);
    (void)FreeConsole();
    CHECK(AllocConsole());
    HANDLE input = CreateFileW(L"CONIN$",GENERIC_READ|GENERIC_WRITE,
                              FILE_SHARE_READ|FILE_SHARE_WRITE,NULL,OPEN_EXISTING,0,NULL);
    HANDLE output = CreateFileW(L"CONOUT$",GENERIC_READ|GENERIC_WRITE,
                               FILE_SHARE_READ|FILE_SHARE_WRITE,NULL,OPEN_EXISTING,0,NULL);
    CHECK(input != INVALID_HANDLE_VALUE && output != INVALID_HANDLE_VALUE);
    CHECK(SetStdHandle(STD_INPUT_HANDLE,input));
    CHECK(SetStdHandle(STD_ERROR_HANDLE,stderr_handle));
    CliTerminal original;
    int disabled = 0;
    DWORD mode;
    CHECK(GetConsoleMode(input,&mode));
    CHECK(SetConsoleMode(input,mode|ENABLE_LINE_INPUT|ENABLE_ECHO_INPUT));
    CHECK(cli_terminal_hide(&original,&disabled) && disabled);
    CHECK(GetConsoleMode(input,&mode) && !(mode & ENABLE_ECHO_INPUT));
    const wchar_t secret[] = L"console-secret-\x4e2d\x6587\xd83d\xde00\r";
    INPUT_RECORD events[sizeof(secret)/sizeof(secret[0])];
    memset(events,0,sizeof(events));
    DWORD count = (DWORD)(sizeof(secret)/sizeof(secret[0])-1), written;
    for (DWORD i = 0; i < count; ++i) {
        events[i].EventType = KEY_EVENT;
        events[i].Event.KeyEvent.bKeyDown = TRUE;
        events[i].Event.KeyEvent.wRepeatCount = 1;
        events[i].Event.KeyEvent.uChar.UnicodeChar = secret[i];
    }
    CHECK(WriteConsoleInputW(input,events,count,&written) && written == count);
    char received[128] = {0}; size_t used = 0; int value;
    do {
        value = cli_input_getc();
        CHECK(value != EOF && used < sizeof(received)-1);
        received[used++] = (char)value;
    } while (value != '\n');
    CHECK(!cli_input_error());
    CHECK(strcmp(received,"console-secret-中文😀\r\n") == 0);
    DWORD read;
    wchar_t screen[4096] = {0}; COORD start = {0,0};
    CHECK(ReadConsoleOutputCharacterW(output,screen,4095,start,&read));
    CHECK(wcsstr(screen,L"console-secret-") == NULL);
    CHECK(cli_terminal_restore(&original,1));
    CHECK(GetConsoleMode(input,&mode) && (mode & ENABLE_ECHO_INPUT));
    CloseHandle(input); CloseHandle(output);
    (void)FreeConsole();
    CHECK(SetStdHandle(STD_OUTPUT_HANDLE,stdout_handle));
    CHECK(SetStdHandle(STD_ERROR_HANDLE,stderr_handle));
    puts("Real Windows console: echo hidden/restored and Unicode input verified");
    return EXIT_SUCCESS;
}
