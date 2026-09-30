#include "i18n.h"
#include "file.h"

#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <langinfo.h>
#include <limits.h>
#include <locale.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "messages.h"

static size_t selected_language;
static const char *const language_tags[] = {"en", "zh-CN", "zh-TW", "ja", "ko"};

static const char *translated_message(const char *message)
{
    for (size_t i = 0U; i < sizeof(messages) / sizeof(messages[0]); ++i) {
        if (strcmp(message, messages[i].source) == 0) {
            return messages[i].translated[selected_language];
        }
    }
    return message;
}

static const char *environment_value(const char *name)
{
    const char *value = getenv(name);
    return value != NULL && value[0] != '\0' ? value : NULL;
}

static const char *system_language(void)
{
    const char *value = environment_value("LC_ALL");
    if (value == NULL) value = environment_value("LC_MESSAGES");
    if (value == NULL) value = environment_value("LANG");
    return value != NULL ? value : "C";
}

static size_t match_language(const char *value)
{
    char normalized[64];
    size_t n = 0U;
    while (value[n] != '\0' && value[n] != '.' && value[n] != '@') {
        if (n >= sizeof(normalized) - 1U) return 0U;
        unsigned char character = (unsigned char)value[n];
        normalized[n] = character == (unsigned char)'_' ? '-' :
            (char)tolower(character);
        ++n;
    }
    normalized[n] = '\0';
    if (strcmp(normalized, "zh") == 0 || (n >= 3U && strncmp(normalized, "zh-", 3U) == 0)) {
        const char *last_separator = strrchr(normalized, '-');
        const char *region = last_separator != NULL ? last_separator + 1 : "";
        if (strcmp(region, "tw") == 0 || strcmp(region, "hk") == 0 ||
            strcmp(region, "mo") == 0) return 2U;
        if (strcmp(region, "cn") == 0 || strcmp(region, "sg") == 0) return 1U;
        return n >= 7U && strncmp(normalized + 3, "hant", 4U) == 0 ? 2U : 1U;
    }
    if (strcmp(normalized, "ja") == 0 || (n >= 3U && strncmp(normalized, "ja-", 3U) == 0)) return 3U;
    if (strcmp(normalized, "ko") == 0 || (n >= 3U && strncmp(normalized, "ko-", 3U) == 0)) return 4U;
    return 0U;
}

static int explicit_language(const char *tag, size_t *language)
{
    if (strcmp(tag, "system") == 0) {
        *language = match_language(system_language());
        return 1;
    }
    for (size_t i = 0U; i < sizeof(language_tags) / sizeof(language_tags[0]); ++i) {
        if (strcmp(tag, language_tags[i]) == 0) {
            *language = i;
            return 1;
        }
    }
    return 0;
}

/* No locale installation is required; C/POSIX and non-UTF-8 terminals use ASCII. */
static int utf8_output(void)
{
    const char *value = environment_value("LC_ALL");
    if (value == NULL) value = environment_value("LC_CTYPE");
    if (value == NULL) value = environment_value("LANG");
    if (value != NULL) {
        char normalized[128];
        size_t n = strlen(value);
        if (n >= sizeof(normalized)) return 0;
        for (size_t i = 0U; i <= n; ++i) normalized[i] = (char)tolower((unsigned char)value[i]);
        if (strstr(normalized, "utf-8") != NULL || strstr(normalized, "utf8") != NULL) return 1;
        if (strcmp(value, "C") == 0 || strcmp(value, "POSIX") == 0) return 0;
    }
    const char *codeset = nl_langinfo(CODESET);
    return strcmp(codeset, "UTF-8") == 0 || strcmp(codeset, "UTF8") == 0;
}

static int configuration_path(char *directory, size_t capacity)
{
    const char *base = environment_value("XDG_CONFIG_HOME");
    int length;
    if (base != NULL && base[0] == '/') {
        length = snprintf(directory, capacity, "%s/nekokem", base);
    } else {
        base = environment_value("HOME");
        if (base == NULL || base[0] != '/') return 0;
        length = snprintf(directory, capacity, "%s/.config/nekokem", base);
    }
    return length >= 0 && (size_t)length < capacity;
}

static void read_preference(size_t *language)
{
    char directory[PATH_MAX];
    char path[PATH_MAX];
    char value[64];
    struct stat status;
    if (!configuration_path(directory, sizeof(directory))) return;
    int length = snprintf(path, sizeof(path), "%s/language", directory);
    if (length < 0 || (size_t)length >= sizeof(path)) return;
    int descriptor = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW);
    if (descriptor < 0) return;
    if (fstat(descriptor, &status) != 0 || !S_ISREG(status.st_mode) ||
        status.st_uid != getuid() || status.st_nlink != 1 ||
        (status.st_mode & (mode_t)0777) != (mode_t)0600 ||
        status.st_size <= 0 || (uintmax_t)status.st_size >= sizeof(value)) {
        (void)close(descriptor);
        return;
    }
    ssize_t count = read(descriptor, value, sizeof(value) - 1U);
    int closed = close(descriptor);
    if (count <= 0 || closed != 0 || count != status.st_size) return;
    value[(size_t)count] = '\0';
    if (memchr(value, '\0', (size_t)count) != NULL) return;
    if (value[(size_t)count - 1U] == '\n') value[(size_t)count - 1U] = '\0';
    size_t preference;
    if (explicit_language(value, &preference)) *language = preference;
}

static int make_parent_directories(char *path)
{
    /* Parents are user-selected XDG/HOME paths; do not alter existing permissions. */
    for (char *cursor = path + 1; *cursor != '\0'; ++cursor) {
        if (*cursor != '/') continue;
        *cursor = '\0';
        int result = mkdir(path, 0700);
        int saved_errno = errno;
        *cursor = '/';
        if (result != 0 && saved_errno != EEXIST) return 0;
    }
    return ensure_directory(path, 0700);
}

int cli_language_save(const char *language)
{
    size_t ignored;
    char directory[PATH_MAX];
    char path[PATH_MAX];
    AtomicFile output = {0};
    if (!explicit_language(language, &ignored)) {
        fprintf(stderr, file_message("Invalid language; use system, en, zh-CN, zh-TW, ja or ko\n"));
        return 0;
    }
    if (!configuration_path(directory, sizeof(directory)) ||
        !make_parent_directories(directory)) goto failure;
    int length = snprintf(path, sizeof(path), "%s/language", directory);
    if (length < 0 || (size_t)length >= sizeof(path) ||
        !atomic_file_open(&output, path, 0600) ||
        fputs(language, output.stream) == EOF ||
        fputc('\n', output.stream) == EOF || !atomic_file_commit(&output)) goto failure;
    return 1;
failure:
    atomic_file_abort(&output);
    fprintf(stderr, file_message("Cannot save language preference\n"));
    return 0;
}

int cli_language_init(int *argc, char **argv)
{
    (void)setlocale(LC_CTYPE, "");
    selected_language = match_language(system_language());
    read_preference(&selected_language);
    int output = 1;
    int options = 1;
    int valid = 1;
    for (int input = 1; input < *argc; ++input) {
        const char *value = NULL;
        if (options && strcmp(argv[input], "--") == 0) { options = 0; continue; }
        if (options && strcmp(argv[input], "--lang") == 0) {
            if (++input >= *argc) { valid = 0; break; }
            value = argv[input];
        } else if (options && strncmp(argv[input], "--lang=", 7U) == 0) {
            value = argv[input] + 7;
        }
        if (value != NULL) {
            if (!explicit_language(value, &selected_language)) valid = 0;
        } else {
            argv[output++] = argv[input];
            options = 0; /* Preserve all positional filenames, even --lang or --. */
        }
    }
    argv[output] = NULL;
    *argc = output;
    if (!utf8_output()) selected_language = 0U;
    file_set_message_translator(translated_message);
    if (!valid) fprintf(stderr, file_message("Invalid language; use system, en, zh-CN, zh-TW, ja or ko\n"));
    return valid;
}
