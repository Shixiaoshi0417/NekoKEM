/* Exercise the real Windows console mode and Unicode input without a GUI. */
#include "terminal.h"
#include <stdio.h>
#include <stdlib.h>
#include <wchar.h>
#include <string.h>
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"Console check failed at line %d\n",__LINE__); return EXIT_FAILURE; } } while (0)

/* Explorer starts a console-subsystem EXE with no arguments in a new console. */
static int check_no_argument_launch(void)
{
    wchar_t path[32768], command[32772];
    DWORD length = GetModuleFileNameW(NULL, path, 32768);
    if (length == 0 || length >= 32768) return 0;
    wchar_t *name = wcsrchr(path, L'\\');
    if (name == NULL) return 0;
    wcscpy(name + 1, L"nekokem.exe");
    if (swprintf(command, 32772, L"\"%ls\"", path) < 0) return 0;
    STARTUPINFOW startup = {0};
    PROCESS_INFORMATION process = {0};
    startup.cb = sizeof(startup);
    if (!CreateProcessW(path, command, NULL, NULL, FALSE, CREATE_NEW_CONSOLE,
                        NULL, NULL, &startup, &process)) return 0;
    HANDLE input = INVALID_HANDLE_VALUE, output = INVALID_HANDLE_VALUE;
    int success = 0, attached = 0;
    ULONGLONG deadline = GetTickCount64() + 15000;
    while (GetTickCount64() < deadline) {
        if (AttachConsole(process.dwProcessId)) { attached = 1; break; }
        if (WaitForSingleObject(process.hProcess, 50) == WAIT_OBJECT_0) goto cleanup;
    }
    if (!attached) goto cleanup;
    input = CreateFileW(L"CONIN$", GENERIC_READ|GENERIC_WRITE,
        FILE_SHARE_READ|FILE_SHARE_WRITE, NULL, OPEN_EXISTING, 0, NULL);
    output = CreateFileW(L"CONOUT$", GENERIC_READ,
        FILE_SHARE_READ|FILE_SHARE_WRITE, NULL, OPEN_EXISTING, 0, NULL);
    if (input == INVALID_HANDLE_VALUE || output == INVALID_HANDLE_VALUE) goto cleanup;
    int menu = 0;
    while (GetTickCount64() < deadline) {
        wchar_t screen[4096] = {0}; DWORD read = 0; COORD origin = {0,0};
        if (!ReadConsoleOutputCharacterW(output, screen, 4095, origin, &read)) goto cleanup;
        if (wcsstr(screen, L"NekoKEM") != NULL && wcsstr(screen, L"5.") != NULL) {
            menu = 1; break;
        }
        Sleep(50);
    }
    if (!menu || WaitForSingleObject(process.hProcess, 0) != WAIT_TIMEOUT) goto cleanup;
    INPUT_RECORD events[2] = {0}; DWORD written;
    for (size_t i = 0; i < 2; ++i) {
        events[i].EventType = KEY_EVENT;
        events[i].Event.KeyEvent.bKeyDown = TRUE;
        events[i].Event.KeyEvent.wRepeatCount = 1;
        events[i].Event.KeyEvent.uChar.UnicodeChar = i == 0 ? L'5' : L'\r';
    }
    DWORD code;
    success = WriteConsoleInputW(input, events, 2, &written) && written == 2 &&
        WaitForSingleObject(process.hProcess, 10000) == WAIT_OBJECT_0 &&
        GetExitCodeProcess(process.hProcess, &code) && code == 0;
cleanup:
    if (input != INVALID_HANDLE_VALUE) CloseHandle(input);
    if (output != INVALID_HANDLE_VALUE) CloseHandle(output);
    if (attached) (void)FreeConsole();
    if (!success) (void)TerminateProcess(process.hProcess, 1);
    CloseHandle(process.hThread); CloseHandle(process.hProcess);
    return success;
}
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
    CHECK(check_no_argument_launch());
    CHECK(SetStdHandle(STD_OUTPUT_HANDLE,stdout_handle));
    CHECK(SetStdHandle(STD_ERROR_HANDLE,stderr_handle));
    puts("Real Windows console: echo hidden/restored and Unicode input verified");
    puts("No-argument new-console EXE launch: menu remains open until option 5 exits");
    return EXIT_SUCCESS;
}
