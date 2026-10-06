/* Desktop-only adapter. All path, ACL, format and crypto checks remain in Core. */
#include "file.h"
#include "i18n.h"
#ifdef _WIN32
#include "windows_io.h"
#include <io.h>
#include <limits.h>
#include <windows.h>
#include <shlobj.h>
#include <openssl/rand.h>
#else
#include <errno.h>
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
char *desktop_stage_output(void);
int desktop_remove_staged_key(char *path);
int desktop_private_directory(const char *path);
int desktop_contacts_directory(char *path, size_t capacity);
int desktop_write_private(const char *path, const unsigned char *bytes, size_t length);
int desktop_remove_private(const char *directory_path, const char *name);

int desktop_private_directory(const char *path)
{
    return ensure_directory(path, 0700);
}

/* Public-key contacts live beside the CLI/GUI language preference:
 * LocalAppData/NekoKEM on Windows, otherwise $XDG_CONFIG_HOME/nekokem or
 * $HOME/.config/nekokem. Both NekoKEM directories must stay private. */
int desktop_contacts_directory(char *path, size_t capacity)
{
    static const char contacts[] = "/contacts";
    if (path == NULL || capacity == 0U) return 0;
    path[0] = '\0';
#ifdef _WIN32
    PWSTR base = NULL;
    int length = 0;
    if (SHGetKnownFolderPath(&FOLDERID_LocalAppData, 0, NULL, &base) == S_OK &&
        capacity <= INT_MAX) {
        length = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, base, -1,
                                     path, (int)capacity, NULL, NULL);
    }
    CoTaskMemFree(base);
    if (length <= 0 || strlen(path) + sizeof("/NekoKEM") + sizeof(contacts) > capacity) {
        path[0] = '\0';
        return 0;
    }
    strcat(path, "/NekoKEM");
#else
    const char *base = getenv("XDG_CONFIG_HOME");
    int length;
    if (base != NULL && base[0] == '/') {
        length = snprintf(path, capacity, "%s/nekokem", base);
    } else {
        base = getenv("HOME");
        if (base == NULL || base[0] != '/') return 0;
        length = snprintf(path, capacity, "%s/.config/nekokem", base);
    }
    if (length < 0 || (size_t)length + sizeof(contacts) > capacity) {
        path[0] = '\0';
        return 0;
    }
    /* Parents are user-selected XDG/HOME paths; do not alter existing permissions. */
    for (char *cursor = path + 1; *cursor != '\0'; ++cursor) {
        if (*cursor != '/') continue;
        *cursor = '\0';
        int result = mkdir(path, 0700);
        int saved_errno = errno;
        *cursor = '/';
        if (result != 0 && saved_errno != EEXIST) return 0;
    }
#endif
    if (!ensure_directory(path, 0700)) return 0;
    strcat(path, contacts);
    return ensure_directory(path, 0700);
}

/* Core's atomic private output: a 0600/owner-only temporary file, sync and
 * rename. Existing records must already satisfy Core's private output policy. */
int desktop_write_private(const char *path, const unsigned char *bytes, size_t length)
{
    AtomicFile output = {0};
    if (path == NULL || bytes == NULL || length == 0U) return 0;
    if (atomic_file_open(&output, path, 0600) &&
        file_write_all(output.stream, bytes, length) &&
        atomic_file_commit(&output)) return 1;
    atomic_file_abort(&output);
    return 0;
}

/* Removes one regular file, or a link itself, from a verified private directory. */
int desktop_remove_private(const char *directory_path, const char *name)
{
    if (directory_path == NULL || name == NULL || name[0] == '\0' || name[0] == '.' ||
        strpbrk(name, "/\\") != NULL) return 0;
#ifdef _WIN32
    size_t capacity = strlen(directory_path) + strlen(name) + 2U;
    char *path = malloc(capacity);
    if (path == NULL) return 0;
    (void)snprintf(path, capacity, "%s/%s", directory_path, name);
    int success = windows_delete_regular(path);
    free(path);
    return success;
#else
    int directory = open(directory_path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat status;
    int success = directory >= 0 && fstat(directory, &status) == 0 &&
                  S_ISDIR(status.st_mode) && status.st_uid == geteuid() &&
                  (status.st_mode & (mode_t)0777) == (mode_t)0700 &&
                  file_private_acl_is_safe(directory) &&
                  fstatat(directory, name, &status, AT_SYMLINK_NOFOLLOW) == 0 &&
                  (S_ISREG(status.st_mode) || S_ISLNK(status.st_mode)) &&
                  status.st_uid == geteuid() &&
                  unlinkat(directory, name, 0) == 0 && fsync(directory) == 0;
    if (directory >= 0 && close(directory) != 0) success = 0;
    return success;
#endif
}

const char *desktop_language(void)
{
    int argc = 1;
    char name[] = "nekokem-gui";
    char *argv[] = {name, NULL};
    if (!cli_language_init(&argc, argv)) return "en";
    return cli_language_tag();
}
#ifndef _WIN32
/* Creates a new private 0700 staging directory and returns its descriptor. */
static int stage_directory(char *directory_path, size_t capacity)
{
    const char *base = getenv("TMPDIR");
    if (base == NULL || base[0] != '/') base = "/tmp";
    int size = snprintf(directory_path, capacity, "%s/nekokem-gui-XXXXXX", base);
    if (size < 0 || (size_t)size >= capacity || mkdtemp(directory_path) == NULL) return -1;
    int directory = open(directory_path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat status;
    if (directory >= 0 && fstat(directory, &status) == 0 &&
        S_ISDIR(status.st_mode) && status.st_uid == geteuid() &&
        fchmod(directory, 0700) == 0 && file_private_acl_is_safe(directory)) return directory;
    if (directory >= 0) (void)close(directory);
    (void)rmdir(directory_path);
    return -1;
}

static char *staged_name(const char *directory_path)
{
    size_t capacity = strlen(directory_path) + sizeof("/key.pem");
    char *path = malloc(capacity);
    if (path != NULL) (void)snprintf(path, capacity, "%s/key.pem", directory_path);
    return path;
}
#endif

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
        if (fstatat(directory, "key.pem", &status, AT_SYMLINK_NOFOLLOW) == 0) {
            success = S_ISREG(status.st_mode) && status.st_uid == geteuid() &&
                      status.st_nlink == 1 &&
                      (status.st_mode & (mode_t)0777) == (mode_t)0600 &&
                      unlinkat(directory, "key.pem", 0) == 0;
        } else {
            /* A staged Core output is absent when Core rejected its input. */
            success = errno == ENOENT;
        }
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
    char directory_path[PATH_MAX];
    int directory = stage_directory(directory_path, sizeof(directory_path));
    if (directory < 0) return NULL;
    struct stat status;
    int descriptor = openat(directory, "key.pem",
                            O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    int success = descriptor >= 0 && fchmod(descriptor, 0600) == 0 &&
                  fstat(descriptor, &status) == 0 && S_ISREG(status.st_mode) &&
                  status.st_uid == geteuid() && status.st_nlink == 1 &&
                  (status.st_mode & (mode_t)0777) == (mode_t)0600 &&
                  file_private_acl_is_safe(descriptor);
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
        path = staged_name(directory_path);
        success = path != NULL;
    }
    if (!success) (void)unlinkat(directory, "key.pem", 0);
    if (close(directory) != 0) success = 0;
    if (!success) {
        if (path != NULL) { (void)desktop_remove_staged_key(path); }
        else (void)rmdir(directory_path);
        return NULL;
    }
    return path;
#endif
}

/* Returns a not-yet-existing key.pem path in a new private staging directory.
 * Core creates it atomically; desktop_remove_staged_key() removes both. */
char *desktop_stage_output(void)
{
#ifdef _WIN32
    /* Same private TEMP layout as pasted keys, but key.pem is never pre-created. */
    static const char hex[] = "0123456789abcdef";
    static const char prefix[] = "nekokem-paste-";
    wchar_t base[32768];
    char directory[32768];
    unsigned char random[16];
    DWORD size = GetTempPathW(32768, base);
    if (size == 0 || size >= 32768 ||
        WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, base, -1, directory,
                            (int)(sizeof(directory) - sizeof(prefix) - 2U * sizeof(random)),
                            NULL, NULL) <= 0 ||
        RAND_bytes(random, (int)sizeof(random)) != 1) return NULL;
    size_t length = strlen(directory);
    memcpy(directory + length, prefix, sizeof(prefix) - 1U);
    length += sizeof(prefix) - 1U;
    for (size_t index = 0; index < sizeof(random); ++index) {
        directory[length++] = hex[random[index] >> 4];
        directory[length++] = hex[random[index] & 15];
    }
    directory[length] = '\0';
    if (!ensure_directory(directory, 0700)) return NULL;
    size_t capacity = length + sizeof("/key.pem");
    char *path = malloc(capacity);
    if (path == NULL) {
        (void)windows_remove_directory(directory);
        return NULL;
    }
    (void)snprintf(path, capacity, "%s/key.pem", directory);
    return path;
#else
    char directory_path[PATH_MAX];
    int directory = stage_directory(directory_path, sizeof(directory_path));
    if (directory < 0) return NULL;
    char *path = close(directory) == 0 ? staged_name(directory_path) : NULL;
    if (path == NULL) (void)rmdir(directory_path);
    return path;
#endif
}
