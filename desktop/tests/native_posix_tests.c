/* Real POSIX pasted-key staging tests; public fixtures only, no crypto mocks. */
#include "file.h"
#include <assert.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

char *desktop_stage_key(const unsigned char *bytes, size_t length);
int desktop_remove_staged_key(char *path);

static void check_private_staging(const char *path, const unsigned char *bytes, size_t length)
{
    struct stat status;
    assert(lstat(path, &status) == 0 && S_ISREG(status.st_mode));
    assert(status.st_uid == geteuid() && status.st_nlink == 1);
    assert((status.st_mode & (mode_t)0777) == (mode_t)0600);
    char *directory = strdup(path);
    assert(directory != NULL);
    *strrchr(directory, '/') = '\0';
    assert(lstat(directory, &status) == 0 && S_ISDIR(status.st_mode));
    assert(status.st_uid == geteuid());
    assert((status.st_mode & (mode_t)0777) == (mode_t)0700);
    unsigned char result[64];
    assert(length <= sizeof(result));
    FILE *stream = fopen(path, "rb");
    assert(stream != NULL);
    assert(fread(result, 1, sizeof(result), stream) == length);
    assert(fclose(stream) == 0 && memcmp(result, bytes, length) == 0);
    free(directory);
}

int main(void)
{
    const unsigned char fixture[] = "public staging test\0fixture\n";
    assert(desktop_stage_key(NULL, 1) == NULL);
    assert(desktop_stage_key(fixture, 0) == NULL);
    assert(desktop_stage_key(fixture, 1048577U) == NULL);
    char *path = desktop_stage_key(fixture, sizeof(fixture));
    assert(path != NULL);
    check_private_staging(path, fixture, sizeof(fixture));
    char *saved = strdup(path);
    assert(saved != NULL && desktop_remove_staged_key(path) == 1);
    assert(access(saved, F_OK) != 0);
    *strrchr(saved, '/') = '\0';
    assert(access(saved, F_OK) != 0);
    free(saved);

    /* An added hard link makes cleanup reject the file without deleting data. */
    path = desktop_stage_key(fixture, sizeof(fixture));
    assert(path != NULL);
    saved = strdup(path);
    assert(saved != NULL);
    char linked[PATH_MAX];
    int size = snprintf(linked, sizeof(linked), "%s.link", saved);
    assert(size >= 0 && (size_t)size < sizeof(linked));
    assert(link(saved, linked) == 0);
    assert(desktop_remove_staged_key(path) == 0);
    assert(access(saved, F_OK) == 0 && access(linked, F_OK) == 0);
    assert(unlink(linked) == 0);
    assert(desktop_remove_staged_key(saved) == 1);

    /* A symlink replacement is never followed or removed as a private key. */
    path = desktop_stage_key(fixture, sizeof(fixture));
    assert(path != NULL);
    saved = strdup(path);
    assert(saved != NULL);
    size = snprintf(linked, sizeof(linked), "%s.target", saved);
    assert(size >= 0 && (size_t)size < sizeof(linked));
    assert(rename(saved, linked) == 0 && symlink(linked, saved) == 0);
    assert(desktop_remove_staged_key(path) == 0);
    assert(access(linked, F_OK) == 0);
    assert(unlink(saved) == 0 && unlink(linked) == 0);
    *strrchr(saved, '/') = '\0';
    assert(rmdir(saved) == 0);
    free(saved);
    assert(desktop_remove_staged_key(NULL) == 1);
    puts("Real POSIX GUI staging: private modes, exact bytes, cleanup, links and bounds passed");
    return 0;
}
