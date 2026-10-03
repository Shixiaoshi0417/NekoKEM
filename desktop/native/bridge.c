/* Desktop-only adapter. All path, ACL, format and crypto checks remain in Core. */
#include "file.h"
#include "i18n.h"
#ifdef _WIN32
#include "windows_io.h"
#include <io.h>
#else
#include <fcntl.h>
#include <limits.h>
#include <sys/stat.h>
#include <unistd.h>
#endif
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
#ifdef _WIN32
    int success = windows_delete_regular(path);
    char *separator = strrchr(path, '/');
    if (separator != NULL) {
        *separator = '\0';
        if (!windows_remove_directory(path)) success = 0;
    }
    free(path);
    return success;
#else
    char *separator = strrchr(path, '/');
    if (separator == NULL || strcmp(separator + 1, "key.pem") != 0) {
        free(path);
        return 0;
    }
    *separator = '\0';
    int directory = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat status;
    int success = directory >= 0 && fstat(directory, &status) == 0 &&
                  S_ISDIR(status.st_mode) && status.st_uid == geteuid() &&
                  (status.st_mode & (mode_t)0777) == (mode_t)0700 &&
                  file_private_acl_is_safe(directory);
    if (success) {
        success = fstatat(directory, "key.pem", &status, AT_SYMLINK_NOFOLLOW) == 0 &&
                  S_ISREG(status.st_mode) && status.st_uid == geteuid() &&
                  status.st_nlink == 1 &&
                  (status.st_mode & (mode_t)0777) == (mode_t)0600 &&
                  unlinkat(directory, "key.pem", 0) == 0;
    }
    if (directory >= 0 && close(directory) != 0) success = 0;
    if (success && rmdir(path) != 0) success = 0;
    free(path);
    return success;
#endif
}
char *desktop_stage_key(const unsigned char *bytes, size_t length)
{
    if (bytes == NULL || length == 0 || length > 1048576U) return NULL;
    char *path = NULL;
    FILE *stream = NULL;
#ifdef _WIN32
    if (!windows_create_private_temp(&path, &stream)) return NULL;
    int success = fwrite(bytes, 1, length, stream) == length && fflush(stream) == 0 &&
                  _commit(_fileno(stream)) == 0;
    if (fclose(stream) != 0) success = 0;
    if (!success) { (void)desktop_remove_staged_key(path); return NULL; }
    return path;
#else
    const char *base = getenv("TMPDIR");
    if (base == NULL || base[0] != '/') base = "/tmp";
    char directory_path[PATH_MAX];
    int size = snprintf(directory_path, sizeof(directory_path),
                        "%s/nekokem-gui-XXXXXX", base);
    if (size < 0 || (size_t)size >= sizeof(directory_path) ||
        mkdtemp(directory_path) == NULL) return NULL;
    int directory = open(directory_path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat status;
    int descriptor = -1;
    int success = directory >= 0 && fstat(directory, &status) == 0 &&
                  S_ISDIR(status.st_mode) && status.st_uid == geteuid() &&
                  fchmod(directory, 0700) == 0 && file_private_acl_is_safe(directory);
    if (success) {
        descriptor = openat(directory, "key.pem",
                            O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
        success = descriptor >= 0 && fchmod(descriptor, 0600) == 0 &&
                  fstat(descriptor, &status) == 0 && S_ISREG(status.st_mode) &&
                  status.st_uid == geteuid() && status.st_nlink == 1 &&
                  (status.st_mode & (mode_t)0777) == (mode_t)0600 &&
                  file_private_acl_is_safe(descriptor);
    }
    if (success) {
        stream = fdopen(descriptor, "wb");
        success = stream != NULL;
    }
    if (success) {
        success = fwrite(bytes, 1, length, stream) == length &&
                  fflush(stream) == 0 && file_sync_regular_fd(fileno(stream)) == 0;
        if (fclose(stream) != 0) success = 0;
        descriptor = -1;
    } else if (descriptor >= 0) {
        (void)close(descriptor);
    }
    if (success) {
        size_t capacity = strlen(directory_path) + sizeof("/key.pem");
        path = malloc(capacity);
        success = path != NULL;
        if (success) (void)snprintf(path, capacity, "%s/key.pem", directory_path);
    }
    if (!success && directory >= 0) (void)unlinkat(directory, "key.pem", 0);
    if (directory >= 0 && close(directory) != 0) success = 0;
    if (!success) {
        if (path != NULL) { (void)desktop_remove_staged_key(path); }
        else (void)rmdir(directory_path);
        return NULL;
    }
    return path;
#endif
}
