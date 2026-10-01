/* Desktop-only adapter. All path, ACL, format and crypto checks remain in Core. */
#include "file.h"
#include "i18n.h"
#include "windows_io.h"
#include <io.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

const char *desktop_language(void);
char *desktop_stage_key(const unsigned char *bytes, size_t length);
int desktop_remove_staged_key(char *path);
int desktop_private_directory(const char *path);

int desktop_private_directory(const char *path)
{
    return ensure_directory(path, 0700);
}

const char *desktop_language(void)
{
    int argc = 1;
    char name[] = "nekokem-gui";
    char *argv[] = {name, NULL};
    if (!cli_language_init(&argc, argv)) return "en";
    return cli_language_tag();
}
int desktop_remove_staged_key(char *path)
{
    if (path == NULL) return 1;
    int success = windows_delete_regular(path);
    char *separator = strrchr(path, '/');
    if (separator != NULL) {
        *separator = '\0';
        if (!windows_remove_directory(path)) success = 0;
    }
    free(path);
    return success;
}
char *desktop_stage_key(const unsigned char *bytes, size_t length)
{
    if (bytes == NULL || length == 0 || length > 1048576U) return NULL;
    char *path = NULL;
    FILE *stream = NULL;
    if (!windows_create_private_temp(&path, &stream)) return NULL;
    int success = fwrite(bytes, 1, length, stream) == length && fflush(stream) == 0 &&
                  _commit(_fileno(stream)) == 0;
    if (fclose(stream) != 0) success = 0;
    if (!success) { (void)desktop_remove_staged_key(path); return NULL; }
    return path;
}
