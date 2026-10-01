#ifndef NEKOKEM_WINDOWS_IO_H
#define NEKOKEM_WINDOWS_IO_H
#ifdef _WIN32
#include <wchar.h>
#include <stdio.h>
/* All Core paths are strict UTF-8. Windows accepts local NTFS paths only. */
wchar_t *windows_utf8_path(const char *path);
int windows_read_sensitive_quiet(const char *path, size_t maximum, unsigned char **buffer, size_t *length);
int windows_delete_regular(const char *path);
int windows_remove_directory(const char *path);
int windows_create_private_temp(char **path, FILE **stream);
#endif
#endif
