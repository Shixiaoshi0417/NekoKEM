/* Windows implementation of the filesystem boundary; crypto stays shared.
 * Included by file.c so read/write/translation and fault injection stay shared.
 * Local NTFS only: reject namespaces, ADS, devices, reparse points and weak ACLs.
 * Pin every ancestor against rename while using an output path. */
#include "windows_io.h"
#include <windows.h>
#include <aclapi.h>
#include <sddl.h>
#include <io.h>
#include <openssl/rand.h>
#include <stddef.h>
#include <wchar.h>

#if !defined(_WIN32_WINNT) || _WIN32_WINNT < 0x0A00
#error Windows build must set _WIN32_WINNT >= 0x0A00
#endif

typedef struct {
    wchar_t *full;
    HANDLE *parents;
    size_t count;
} WinPath;

typedef struct {
    WinPath path;
    HANDLE output;
} WinAtomic;

static int win_inspect_key_artifacts(const char *path, HANDLE key, int remove_own,
                                      const char *ignored_path);

static int win_error(void)
{
    DWORD error = GetLastError();
    errno = error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND ? ENOENT :
            error == ERROR_FILE_EXISTS || error == ERROR_ALREADY_EXISTS ? EEXIST :
            error == ERROR_DISK_FULL ? ENOSPC : EACCES;
    return 0;
}

static TOKEN_USER *win_user(void)
{
    HANDLE token = NULL;
    TOKEN_USER *user = NULL;
    DWORD size = 0;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return NULL;
    (void)GetTokenInformation(token, TokenUser, NULL, 0, &size);
    if (size != 0) user = malloc(size);
    if (user != NULL && !GetTokenInformation(token, TokenUser, user, size, &size)) {
        free(user);
        user = NULL;
    }
    CloseHandle(token);
    return user;
}

/* owner-only is the Windows equivalent of a private 0600 file / 0700 directory.
 * For ancestor directories, reads are allowed but writes by untrusted SIDs aren't. */
static int win_acl(HANDLE handle, int private_acl)
{
    PSECURITY_DESCRIPTOR descriptor = NULL;
    PSID owner = NULL;
    PACL acl = NULL;
    TOKEN_USER *user = win_user();
    SID_IDENTIFIER_AUTHORITY nt = SECURITY_NT_AUTHORITY;
    PSID system_sid = NULL;
    PSID admin_sid = NULL;
    PSID installer_sid = NULL;
    PSID owner_rights_sid = NULL;
    int success = 0;
    const DWORD writes = FILE_WRITE_DATA | FILE_APPEND_DATA | FILE_WRITE_EA |
                         FILE_WRITE_ATTRIBUTES | FILE_DELETE_CHILD | DELETE |
                         WRITE_DAC | WRITE_OWNER | GENERIC_WRITE | GENERIC_ALL;
    if (user == NULL || GetSecurityInfo(handle, SE_FILE_OBJECT,
            OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
            &owner, NULL, &acl, NULL, &descriptor) != ERROR_SUCCESS ||
        acl == NULL || !IsValidAcl(acl) || owner == NULL) goto cleanup;
    if (private_acl == 1 && !EqualSid(owner, user->User.Sid)) goto cleanup;
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (private_acl == 1 && test_fault_should_fail(FILE_TEST_FAULT_FOREIGN_OWNER)) goto cleanup;
#endif
    if (!AllocateAndInitializeSid(&nt, 1, SECURITY_LOCAL_SYSTEM_RID,
            0, 0, 0, 0, 0, 0, 0, &system_sid) ||
        !AllocateAndInitializeSid(&nt, 2, SECURITY_BUILTIN_DOMAIN_RID,
            DOMAIN_ALIAS_RID_ADMINS, 0, 0, 0, 0, 0, 0, &admin_sid)) goto cleanup;
    /* Exact built-in Windows Modules Installer service SID. Its token is
     * controlled by SYSTEM; do not trust arbitrary service/account names. */
    if (!ConvertStringSidToSidW(L"S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464",
                              &installer_sid) ||
        !ConvertStringSidToSidW(L"S-1-3-4", &owner_rights_sid)) goto cleanup;
    if (private_acl != 1 && !EqualSid(owner,user->User.Sid) &&
        !EqualSid(owner,system_sid) && !EqualSid(owner,admin_sid) &&
        !(private_acl == 2 && EqualSid(owner,installer_sid))) goto cleanup;
    for (DWORD index = 0; index < acl->AceCount; ++index) {
        void *entry = NULL;
        if (!GetAce(acl, index, &entry)) goto cleanup;
        ACE_HEADER *header = entry;
        /* No opaque/object/callback ACEs: fail closed rather than guess access. */
        if (header->AceType == ACCESS_DENIED_ACE_TYPE) continue;
        if (header->AceType != ACCESS_ALLOWED_ACE_TYPE) goto cleanup;
        ACCESS_ALLOWED_ACE *ace = entry;
        PSID sid = &ace->SidStart;
        if (!IsValidSid(sid)) goto cleanup;
        if ((header->AceFlags & INHERIT_ONLY_ACE) != 0) continue;
        /* OWNER RIGHTS grants only the already-validated object's owner.
         * This is used by Python 3.13+ for private Windows temp directories.
         * For private inputs the owner must still be the current user. */
        if (EqualSid(sid, user->User.Sid) || EqualSid(sid, owner_rights_sid)) continue;
        if (private_acl == 1) {
            if (ace->Mask != 0) goto cleanup;
        } else if ((ace->Mask & (private_acl == 2 ?
                    (writes & ~(DWORD)(FILE_WRITE_DATA | FILE_APPEND_DATA)) : writes)) != 0 &&
                   !EqualSid(sid, system_sid) && !EqualSid(sid, admin_sid) &&
                   !(private_acl == 2 && EqualSid(sid, installer_sid))) {
            goto cleanup;
        }
    }
    success = 1;
cleanup:
    if (owner_rights_sid != NULL) LocalFree(owner_rights_sid);
    if (installer_sid != NULL) LocalFree(installer_sid);
    if (system_sid != NULL) FreeSid(system_sid);
    if (admin_sid != NULL) FreeSid(admin_sid);
    if (descriptor != NULL) LocalFree(descriptor);
    free(user);
    if (!success) errno = EACCES;
    return success;
}

static int win_regular(HANDLE handle, int private_acl)
{
    BY_HANDLE_FILE_INFORMATION info;
    if (GetFileType(handle) != FILE_TYPE_DISK ||
        !GetFileInformationByHandle(handle, &info) ||
        (info.dwFileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT)) != 0)
        return 0;
    return !private_acl || (info.nNumberOfLinks == 1 && win_acl(handle, 1));
}

wchar_t *windows_utf8_path(const char *path)
{
    wchar_t *raw = NULL;
    wchar_t *full = NULL;
    int length;
    DWORD capacity;
    if (path == NULL || path[0] == '\0') goto fail;
    length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, NULL, 0);
    if (length <= 0) goto fail;
    raw = malloc((size_t)length * sizeof(*raw));
    if (raw == NULL || MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
            path, -1, raw, length) != length) goto fail;
    for (wchar_t *cursor = raw; *cursor != L'\0'; ++cursor) {
        if (*cursor == L'/') *cursor = L'\\';
    }
    /* No UNC, device namespace, root-relative or drive-relative paths. */
    if (raw[0] == L'\\' || (wcslen(raw) >= 2 && raw[1] == L':' && raw[2] != L'\\')) goto fail;
    capacity = GetFullPathNameW(raw, 0, NULL, NULL);
    if (capacity == 0 || capacity > 32760) goto fail;
    full = malloc((size_t)capacity * sizeof(*full));
    if (full == NULL) goto fail;
    DWORD resolved = GetFullPathNameW(raw, capacity, full, NULL);
    if (resolved == 0 || resolved >= capacity) goto fail;
    if (wcslen(full) < 4 || full[1] != L':' || full[2] != L'\\') goto fail;
    for (wchar_t *start = full + 3; *start != L'\0';) {
        wchar_t *end = wcschr(start, L'\\');
        if (end == NULL) end = start + wcslen(start);
        if (end == start || end[-1] == L'.' || end[-1] == L' ') goto fail;
        for (wchar_t *c = start; c < end; ++c) {
            if (*c < 32 || wcschr(L":*?\"<>|", *c) != NULL) goto fail;
        }
        size_t stem = 0;
        while (start + stem < end && start[stem] != L'.') ++stem;
        if ((stem == 3 && (_wcsnicmp(start,L"CON",3)==0 || _wcsnicmp(start,L"PRN",3)==0 ||
                          _wcsnicmp(start,L"AUX",3)==0 || _wcsnicmp(start,L"NUL",3)==0)) ||
            (stem == 4 && (_wcsnicmp(start,L"COM",3)==0 || _wcsnicmp(start,L"LPT",3)==0) &&
             ((start[3] >= L'0' && start[3] <= L'9') || start[3] == L'\u00b9' ||
              start[3] == L'\u00b2' || start[3] == L'\u00b3')) ||
            (stem == 6 && _wcsnicmp(start,L"CONIN$",6) == 0) ||
            (stem == 7 && _wcsnicmp(start,L"CONOUT$",7) == 0)) goto fail;
        start = *end == L'\0' ? end : end + 1;
    }
    free(raw);
    return full;
fail:
    free(raw);
    free(full);
    errno = EINVAL;
    return NULL;
}

static void win_path_close(WinPath *path)
{
    for (size_t i = 0; i < path->count; ++i) CloseHandle(path->parents[i]);
    free(path->parents);
    free(path->full);
    memset(path, 0, sizeof(*path));
}

static int win_path_open(WinPath *path, const char *utf8, int trusted_parent)
{
    int saved_errno = EACCES;
    memset(path, 0, sizeof(*path));
    path->full = windows_utf8_path(utf8);
    if (path->full == NULL) return 0;
    wchar_t root[4] = {path->full[0], L':', L'\\', L'\0'};
    /* Reject mapped network/removable devices before opening any handle. */
    if (GetDriveTypeW(root) != DRIVE_FIXED) goto fail;
    size_t capacity = wcslen(path->full);
    path->parents = calloc(capacity, sizeof(*path->parents));
    if (path->parents == NULL) goto fail;
    for (wchar_t *cursor = path->full + 3;; ++cursor) {
        /* Root and each existing ancestor, excluding the final component. */
        if (cursor != path->full + 3 && *cursor != L'\\') {
            if (*cursor == L'\0') break;
            continue;
        }
        wchar_t saved = *cursor;
        *cursor = L'\0';
        HANDLE handle = CreateFileW(path->full, FILE_READ_ATTRIBUTES | READ_CONTROL,
                FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_EXISTING,
                FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, NULL);
        *cursor = saved;
        BY_HANDLE_FILE_INFORMATION info;
        if (handle == INVALID_HANDLE_VALUE) {
            (void)win_error();
            saved_errno = errno;
            goto fail;
        }
        path->parents[path->count++] = handle;
        if (!GetFileInformationByHandle(handle, &info) ||
            (info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) == 0 ||
            (info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0 ||
            (trusted_parent && !win_acl(handle,
                cursor == wcsrchr(path->full,L'\\') ||
                (cursor == path->full+3 && wcsrchr(path->full,L'\\') == path->full+2) ? 0 : 2))) {
            goto fail;
        }
        if (path->count == 1) {
            wchar_t fs[32];
            if (!GetVolumeInformationByHandleW(handle, NULL, 0, NULL, NULL, NULL, fs, 32) ||
                wcscmp(fs, L"NTFS") != 0) goto fail;
        }
    }
    return 1;
fail:
    win_path_close(path);
    errno = saved_errno;
    return 0;
}

static HANDLE win_open(const char *path, DWORD access, DWORD sharing, int private_acl)
{
    WinPath checked;
    HANDLE handle = INVALID_HANDLE_VALUE;
    /* All paths need trusted ancestors: a pinned directory handle alone does
     * not justify trusting an untrusted writer to its reparse metadata. */
    if (!win_path_open(&checked, path, 1)) return handle;
    handle = CreateFileW(checked.full, access | READ_CONTROL, sharing, NULL,
            OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT, NULL);
    if (handle != INVALID_HANDLE_VALUE && !win_regular(handle, private_acl)) {
#ifdef NEKOKEM_TEST_FAULT_INJECTION
        windows_test_read_trace("regular handle rejection", 0, 0, errno, (unsigned long)GetLastError());
        windows_test_path_trace(checked.full, L"<regular handle rejection>");
#endif
        CloseHandle(handle);
        handle = INVALID_HANDLE_VALUE;
        errno = EACCES;
    } else if (handle == INVALID_HANDLE_VALUE) {
#ifdef NEKOKEM_TEST_FAULT_INJECTION
        windows_test_read_trace("native open", 0, 0, errno, (unsigned long)GetLastError());
        windows_test_path_trace(checked.full, L"<native open failed>");
#endif
        (void)win_error();
    }
    win_path_close(&checked);
    return handle;
}

static FILE *win_stream(HANDLE handle, int flags, const char *mode)
{
    int descriptor = _open_osfhandle((intptr_t)handle, flags | _O_BINARY | _O_NOINHERIT);
    if (descriptor < 0) {
        CloseHandle(handle);
        return NULL;
    }
    FILE *stream = _fdopen(descriptor, mode);
    if (stream == NULL) (void)_close(descriptor);
    else if (!file_disable_buffering(stream)) { (void)fclose(stream); stream = NULL; }
    return stream;
}

FILE *file_open_regular(const char *path)
{
    HANDLE handle = win_open(path, GENERIC_READ, FILE_SHARE_READ, 0);
    if (handle == INVALID_HANDLE_VALUE) {
        print_system_error("Cannot open regular input file");
        return NULL;
    }
    return win_stream(handle, _O_RDONLY, "rb");
}

int file_get_size(FILE *stream, uint64_t *size)
{
    if (stream == NULL || size == NULL) return 0;
    HANDLE handle = (HANDLE)_get_osfhandle(_fileno(stream));
    LARGE_INTEGER length;
    if (!win_regular(handle, 0) || !GetFileSizeEx(handle, &length) || length.QuadPart < 0) return 0;
    *size = (uint64_t)length.QuadPart;
    return 1;
}

static int win_read(const char *path, size_t maximum, unsigned char **buffer,
                    size_t *length, int sensitive, int quiet)
{
    FILE *stream = NULL;
    unsigned char *bytes = NULL;
    uint64_t size = 0;
    int success = 0;
    if (buffer == NULL || length == NULL || maximum == 0) return 0;
    *buffer = NULL;
    *length = 0;
    const char *stage = "open";
    if (sensitive && !quiet) (void)win_inspect_key_artifacts(path, INVALID_HANDLE_VALUE, 0, NULL);
    HANDLE handle = win_open(path, GENERIC_READ, FILE_SHARE_READ, sensitive);
    if (handle == INVALID_HANDLE_VALUE) goto cleanup;
    stage = "stream";
    stream = win_stream(handle, _O_RDONLY, "rb");
    if (stream == NULL) goto cleanup;
    /* Unbuffered, so no CRT buffer keeps a copy of a key after fclose. */
    if (sensitive && !file_disable_buffering(stream)) goto cleanup;
    stage = "size";
    if (!file_get_size(stream, &size)) goto cleanup;
    stage = "size limit";
    if (size == 0 || size > (uint64_t)maximum || size > (uint64_t)SIZE_MAX) goto cleanup;
    stage = "allocation";
    bytes = OPENSSL_malloc((size_t)size);
    if (bytes == NULL) goto cleanup;
    stage = "data";
    if (!file_read_exact(stream, bytes, (size_t)size)) goto cleanup;
    stage = "tail";
    if (fgetc(stream) != EOF || ferror(stream)) goto cleanup;
    stage = "close";
    if (fclose(stream) != 0) { stream = NULL; goto cleanup; }
    stream = NULL;
    *buffer = bytes;
    *length = (size_t)size;
    bytes = NULL;
    success = 1;
cleanup:
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (!success) windows_test_read_trace(stage, size, maximum, errno, (unsigned long)GetLastError());
#else
    (void)stage;
#endif
    if (stream != NULL) (void)fclose(stream);
    if (sensitive) secure_free(bytes, (size_t)size); else OPENSSL_free(bytes);
    if (!success && !quiet) print_system_error("Cannot read regular input file");
    return success;
}

int file_read_regular(const char *path, size_t maximum, unsigned char **buffer, size_t *length)
{
    return win_read(path, maximum, buffer, length, 0, 0);
}
int file_read_sensitive(const char *path, size_t maximum, unsigned char **buffer, size_t *length)
{
    return win_read(path, maximum, buffer, length, 1, 0);
}

int windows_read_sensitive_quiet(const char *path, size_t maximum, unsigned char **buffer, size_t *length)
{
    return win_read(path, maximum, buffer, length, 1, 1);
}

static HANDLE win_create(const wchar_t *path, int directory)
{
    TOKEN_USER *user = win_user();
    PACL acl = NULL;
    SECURITY_DESCRIPTOR descriptor;
    SECURITY_ATTRIBUTES attributes = {sizeof(attributes), &descriptor, FALSE};
    HANDLE handle = INVALID_HANDLE_VALUE;
    if (user == NULL) goto cleanup;
    DWORD size = (DWORD)sizeof(ACL) + (DWORD)sizeof(ACCESS_ALLOWED_ACE) +
                 GetLengthSid(user->User.Sid) - (DWORD)sizeof(DWORD);
    acl = malloc(size);
    if (acl == NULL || !InitializeAcl(acl, size, ACL_REVISION) ||
        !AddAccessAllowedAce(acl, ACL_REVISION, FILE_ALL_ACCESS, user->User.Sid) ||
        !InitializeSecurityDescriptor(&descriptor, SECURITY_DESCRIPTOR_REVISION) ||
        !SetSecurityDescriptorOwner(&descriptor, user->User.Sid, FALSE) ||
        !SetSecurityDescriptorDacl(&descriptor, TRUE, acl, FALSE) ||
        !SetSecurityDescriptorControl(&descriptor, SE_DACL_PROTECTED, SE_DACL_PROTECTED)) goto cleanup;
    if (directory) {
        if (!CreateDirectoryW(path, &attributes)) goto cleanup;
        handle = CreateFileW(path, READ_CONTROL | FILE_READ_ATTRIBUTES,
                FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_EXISTING,
                FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, NULL);
    } else {
        handle = CreateFileW(path, GENERIC_READ | GENERIC_WRITE | DELETE | READ_CONTROL,
                FILE_SHARE_READ | FILE_SHARE_DELETE, &attributes, CREATE_NEW,
                FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT, NULL);
    }
cleanup:
    free(acl);
    free(user);
    if (handle == INVALID_HANDLE_VALUE) (void)win_error();
    return handle;
}

int ensure_directory(const char *path, mode_t mode)
{
    WinPath checked;
    int success = 0;
    /* CLI uses private directories; do not silently approximate other modes. */
    if (mode != 0700 || !win_path_open(&checked, path, 1)) return 0;
    HANDLE handle = win_create(checked.full, 1);
    if (handle == INVALID_HANDLE_VALUE && errno == EEXIST) {
        handle = CreateFileW(checked.full, READ_CONTROL | FILE_READ_ATTRIBUTES,
                FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_EXISTING,
                FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, NULL);
    }
    BY_HANDLE_FILE_INFORMATION info;
    if (handle != INVALID_HANDLE_VALUE) {
        success = GetFileInformationByHandle(handle, &info) &&
                  (info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0 &&
                  (info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) == 0 && win_acl(handle, 1);
        CloseHandle(handle);
    }
    win_path_close(&checked);
    if (!success) print_system_error("Invalid private directory request");
    return success;
}

static char *win_random_path(const char *path, const char *suffix)
{
    unsigned char random[16];
    static const char hex[] = "0123456789abcdef";
    size_t length = strlen(path), extra = strlen(suffix);
    if (length > SIZE_MAX - extra - 33 || RAND_bytes(random, (int)sizeof(random)) != 1) return NULL;
    char *name = malloc(length + extra + 33);
    if (name == NULL) return NULL;
    memcpy(name, path, length);
    memcpy(name + length, suffix, extra);
    for (size_t i = 0; i < sizeof(random); ++i) {
        name[length + extra + i*2] = hex[random[i] >> 4];
        name[length + extra + i*2+1] = hex[random[i] & 15];
    }
    name[length + extra + 32] = '\0';
    return name;
}

static int win_dispose(HANDLE handle);

/*
 * Publishing clears an output's pending deletion and then renames it. Ctrl+C
 * between the two would keep the temporary file, so file_remove_temporary_
 * outputs waits for a publication in progress and blocks any later one.
 */
static SRWLOCK win_publication = SRWLOCK_INIT;

int atomic_file_open(AtomicFile *file, const char *final_path, mode_t mode)
{
    if (file == NULL || (mode != 0600 && mode != 0644)) return 0;
    memset(file, 0, sizeof(*file));
    WinAtomic *state = calloc(1, sizeof(*state));
    if (state == NULL) return 0;
    state->output = INVALID_HANDLE_VALUE;
    file->platform_state = state;
    if (!win_path_open(&state->path, final_path, 1)) goto fail;
    file->temporary_path = win_random_path(final_path, ".tmp.");
    wchar_t *temporary = file->temporary_path != NULL ? windows_utf8_path(file->temporary_path) : NULL;
    if (temporary == NULL) goto fail;
    state->output = win_create(temporary, 0);
    free(temporary);
    if (state->output == INVALID_HANDLE_VALUE || !win_regular(state->output, 1)) goto fail;
    /* Delete-pending until committed: however the process ends, the system
     * removes the partial output with its last handle. */
    if (!win_dispose(state->output)) goto fail;
    HANDLE stream_handle = INVALID_HANDLE_VALUE;
    if (!DuplicateHandle(GetCurrentProcess(), state->output, GetCurrentProcess(),
                         &stream_handle, 0, FALSE, DUPLICATE_SAME_ACCESS)) goto fail;
    file->stream = win_stream(stream_handle, _O_WRONLY, "wb");
    if (file->stream == NULL || !file_disable_buffering(file->stream)) goto fail;
    file->final_path = final_path;
    return 1;
fail:
    print_system_error("Cannot create temporary output file");
    atomic_file_abort(file);
    return 0;
}

static int win_flush(HANDLE handle)
{
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (test_fault_should_fail(FILE_TEST_FAULT_FSYNC)) return 0;
#endif
    return FlushFileBuffers(handle) != 0;
}

int atomic_file_prepare(AtomicFile *file)
{
    if (file == NULL || file->stream == NULL || file->platform_state == NULL) return 0;
    WinAtomic *state = file->platform_state;
    int success = fflush(file->stream) == 0;
    if (success && !win_flush(state->output)) success = 0;
    if (fclose(file->stream) != 0) success = 0;
    file->stream = NULL;
    return success;
}

static int win_rename(HANDLE handle, const wchar_t *destination, int replace, int inject)
{
#ifndef NEKOKEM_TEST_FAULT_INJECTION
    (void)inject;
#else
    if ((inject == 1 && test_fault_should_fail(FILE_TEST_FAULT_RENAME)) ||
        (inject == 2 && test_restore_should_fail())) { errno = EIO; return 0; }
#endif
    size_t length = wcslen(destination) * sizeof(wchar_t);
    /* FileNameLength excludes the NUL, but FileName must be terminated for
     * the Win32 layer that normalizes the path before the NT rename request. */
    size_t size = offsetof(FILE_RENAME_INFO, FileName) + length + sizeof(wchar_t);
    if (size > (size_t)MAXDWORD) return 0;
    FILE_RENAME_INFO *info = calloc(1, size);
    if (info == NULL) return 0;
    info->ReplaceIfExists = replace ? TRUE : FALSE;
    info->FileNameLength = (DWORD)length;
    memcpy(info->FileName, destination, length);
    int result = SetFileInformationByHandle(handle, FileRenameInfo, info, (DWORD)size) != 0;
#ifdef NEKOKEM_TEST_FAULT_INJECTION
    if (result) {
        wchar_t actual[32768];
        DWORD actual_length = GetFinalPathNameByHandleW(handle, actual, 32768, FILE_NAME_NORMALIZED);
        windows_test_path_trace(destination, actual_length > 0 && actual_length < 32768 ? actual : L"<unavailable>");
    }
#endif
    free(info);
    return result;
}

static int win_dispose(HANDLE handle)
{
    FILE_DISPOSITION_INFO disposition = {TRUE};
    return SetFileInformationByHandle(handle, FileDispositionInfo, &disposition, sizeof(disposition)) != 0;
}

/* Cancels a pending deletion set through the same handle. */
static int win_keep(HANDLE handle)
{
    FILE_DISPOSITION_INFO disposition = {FALSE};
    return SetFileInformationByHandle(handle, FileDispositionInfo, &disposition, sizeof(disposition)) != 0;
}

static int win_same(HANDLE a, HANDLE b)
{
    BY_HANDLE_FILE_INFORMATION first, second;
    return GetFileInformationByHandle(a, &first) && GetFileInformationByHandle(b, &second) &&
           first.dwVolumeSerialNumber == second.dwVolumeSerialNumber &&
           first.nFileIndexHigh == second.nFileIndexHigh && first.nFileIndexLow == second.nFileIndexLow;
}

static int win_artifact_suffix(const wchar_t *suffix)
{
    do {
        if (wcsncmp(suffix, L".bak.", 5) != 0 && wcsncmp(suffix, L".tmp.", 5) != 0) return 0;
        suffix += 5;
        for (size_t i = 0; i < 32; ++i) {
            if (!((*suffix >= L'0' && *suffix <= L'9') || (*suffix >= L'a' && *suffix <= L'f'))) return 0;
            ++suffix;
        }
    } while (*suffix != L'\0');
    return 1;
}

static char *win_utf8_display(const wchar_t *wide)
{
    int size = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, wide, -1, NULL, 0, NULL, NULL);
    char *result = size > 0 ? malloc((size_t)size) : NULL;
    if (result != NULL && WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, wide, -1,
                                             result, size, NULL, NULL) != size) {
        free(result);
        result = NULL;
    }
    return result;
}

static int win_inspect_key_artifacts(const char *path, HANDLE key, int remove_own,
                                      const char *ignored_path)
{
    WinPath checked;
    HANDLE opened = INVALID_HANDLE_VALUE;
    HANDLE search = INVALID_HANDLE_VALUE;
    wchar_t *pattern = NULL;
    wchar_t *candidate = NULL;
    wchar_t *ignored = NULL;
    BY_HANDLE_FILE_INFORMATION key_info;
    WIN32_FIND_DATAW entry;
    int have_key = 0;
    int success = 1;

    if (!win_path_open(&checked, path, 1)) return 0;
    if (ignored_path != NULL) ignored = windows_utf8_path(ignored_path);
    if (key == INVALID_HANDLE_VALUE) {
        opened = CreateFileW(checked.full, FILE_READ_ATTRIBUTES | READ_CONTROL,
                FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL, OPEN_EXISTING,
                FILE_FLAG_OPEN_REPARSE_POINT, NULL);
        key = opened;
    }
    if (key != INVALID_HANDLE_VALUE && GetFileInformationByHandle(key, &key_info)) {
        have_key = 1;
        if (key_info.nNumberOfLinks > 1) {
            fprintf(stderr, file_message("Key %s has multiple hard links; other copies may remain\n"),
                    file_display_safe_path(path));
        }
    }
    struct { ULONG flags; } case_info = {1};
    int case_sensitive = !GetFileInformationByHandleEx(checked.parents[checked.count - 1],
            (FILE_INFO_BY_HANDLE_CLASS)23, &case_info, sizeof(case_info)) || (case_info.flags & 1) != 0;
    size_t length = wcslen(checked.full);
    const wchar_t *name = wcsrchr(checked.full, L'\\');
    if (name == NULL) { success = 0; goto cleanup; }
    ++name;
    size_t parent_length = (size_t)(name - checked.full);
    size_t name_length = wcslen(name);
    pattern = malloc((length + 3) * sizeof(*pattern));
    candidate = malloc(32768 * sizeof(*candidate));
    if (pattern == NULL || candidate == NULL) { success = 0; goto cleanup; }
    memcpy(pattern, checked.full, length * sizeof(*pattern));
    memcpy(pattern + length, L".*", 3 * sizeof(*pattern));
    search = FindFirstFileW(pattern, &entry);
    if (search == INVALID_HANDLE_VALUE) {
        if (GetLastError() != ERROR_FILE_NOT_FOUND) success = 0;
        goto cleanup;
    }
    do {
        const wchar_t *ignored_name = ignored != NULL ? wcsrchr(ignored, L'\\') : NULL;
        if (ignored_name != NULL &&
            (case_sensitive ? wcscmp(entry.cFileName, ignored_name + 1)
                            : _wcsicmp(entry.cFileName, ignored_name + 1)) == 0) continue;
        if ((case_sensitive ? wcsncmp(entry.cFileName, name, name_length)
                            : _wcsnicmp(entry.cFileName, name, name_length)) != 0 ||
            !win_artifact_suffix(entry.cFileName + name_length)) continue;
        size_t candidate_length = wcslen(entry.cFileName);
        if (parent_length + candidate_length >= 32768) { success = 0; continue; }
        memcpy(candidate, checked.full, parent_length * sizeof(*candidate));
        memcpy(candidate + parent_length, entry.cFileName, (candidate_length + 1) * sizeof(*candidate));
        char *display = win_utf8_display(candidate);
        fprintf(stderr, file_message("Key transaction residue at %s; inspect it before cleanup or recovery\n"),
                file_display_safe_path(display));
        free(display);
        HANDLE residue = CreateFileW(candidate, GENERIC_READ | DELETE | READ_CONTROL,
                FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL, OPEN_EXISTING,
                FILE_FLAG_OPEN_REPARSE_POINT, NULL);
        BY_HANDLE_FILE_INFORMATION info;
        unsigned char magic[27];
        DWORD count = 0;
        int valid = residue != INVALID_HANDLE_VALUE && win_regular(residue, 0) &&
                    win_acl(residue, 1) && GetFileInformationByHandle(residue, &info);
        int same = valid && have_key && info.dwVolumeSerialNumber == key_info.dwVolumeSerialNumber &&
                   info.nFileIndexHigh == key_info.nFileIndexHigh && info.nFileIndexLow == key_info.nFileIndexLow;
        /* A key generation stages its file before taking the pair lock, so a
         * recent temporary file may still belong to another process. */
        FILETIME now;
        GetSystemTimeAsFileTime(&now);
        ULONGLONG written = ((ULONGLONG)info.ftLastWriteTime.dwHighDateTime << 32) | info.ftLastWriteTime.dwLowDateTime;
        ULONGLONG current = ((ULONGLONG)now.dwHighDateTime << 32) | now.dwLowDateTime;
        int stale = valid && current > written && current - written > 600ULL * 10000000ULL;
        int ours = valid && (same || (info.nNumberOfLinks == 1 &&
            ((stale && wcsncmp(entry.cFileName + name_length, L".tmp.", 5) == 0) ||
             (ReadFile(residue, magic, sizeof(magic), &count, NULL) &&
              ((count >= 4 && memcmp(magic, "NKPR", 4) == 0) ||
               (count == sizeof(magic) && memcmp(magic, "-----BEGIN PRIVATE KEY-----", sizeof(magic)) == 0))))));
        if (remove_own && (!ours || !win_dispose(residue))) success = 0;
        if (residue != INVALID_HANDLE_VALUE) CloseHandle(residue);
    } while (FindNextFileW(search, &entry));
    if (GetLastError() != ERROR_NO_MORE_FILES) success = 0;
cleanup:
    if (search != INVALID_HANDLE_VALUE) FindClose(search);
    if (opened != INVALID_HANDLE_VALUE) CloseHandle(opened);
    free(pattern);
    free(candidate);
    free(ignored);
    win_path_close(&checked);
    return success;
}

int file_delete_private_key_under_lock(const char *path)
{
    WinPath checked;
    if (!win_path_open(&checked, path, 1)) return errno == ENOENT;
    HANDLE key = CreateFileW(checked.full, GENERIC_READ | GENERIC_WRITE | DELETE | READ_CONTROL,
            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL, OPEN_EXISTING,
            FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_WRITE_THROUGH, NULL);
    DWORD error = GetLastError();
    int success = 0;
    if (key == INVALID_HANDLE_VALUE && error != ERROR_FILE_NOT_FOUND) goto cleanup;
    if (key != INVALID_HANDLE_VALUE && (!win_regular(key, 0) || !win_acl(key, 1))) goto cleanup;
    int retained = !win_inspect_key_artifacts(path, key, 1, NULL);
    /* Windows has no supported directory fsync. Flush the retained file
     * handle and submit its synchronous delete disposition before closing. */
    if (key != INVALID_HANDLE_VALUE && (!win_flush(key) || !win_dispose(key))) {
        print_system_error("Cannot sync the private-key directory after deletion");
        goto cleanup;
    }
    /* The key itself is gone: a retained residue is reported, not a failure. */
    success = 1;
    if (retained) fprintf(stderr, file_message("Some key transaction residues were retained; inspect them before cleanup\n"));
cleanup:
    if (key != INVALID_HANDLE_VALUE) CloseHandle(key);
    win_path_close(&checked);
    return success;
}

static HANDLE win_open_identity(const char *utf8)
{
    wchar_t *wide = windows_utf8_path(utf8);
    /* A refused path is an error, never a missing file. */
    if (wide == NULL) {
        SetLastError(ERROR_INVALID_NAME);
        return INVALID_HANDLE_VALUE;
    }
    HANDLE handle = CreateFileW(wide, FILE_READ_ATTRIBUTES,
            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL, OPEN_EXISTING,
            FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, NULL);
    DWORD error = GetLastError();
    free(wide);
    SetLastError(error);
    return handle;
}

static int win_missing(DWORD error)
{
    return error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND;
}

int file_path_exists(const char *path, int *exists)
{
    if (path == NULL || exists == NULL) return 0;
    *exists = 0;
    HANDLE handle = win_open_identity(path);
    if (handle == INVALID_HANDLE_VALUE) return win_missing(GetLastError());
    CloseHandle(handle);
    *exists = 1;
    return 1;
}

int file_paths_are_same_file(const char *first, const char *second, int *same)
{
    if (first == NULL || second == NULL || same == NULL) return 0;
    *same = 0;
    HANDLE a = win_open_identity(first);
    if (a == INVALID_HANDLE_VALUE) return win_missing(GetLastError());
    HANDLE b = win_open_identity(second);
    if (b == INVALID_HANDLE_VALUE) {
        DWORD error = GetLastError();
        CloseHandle(a);
        return win_missing(error);
    }
    *same = win_same(a, b);
    CloseHandle(a);
    CloseHandle(b);
    return 1;
}

int file_peek_regular(const char *path, unsigned char *prefix, size_t length)
{
    if (path == NULL || prefix == NULL || length == 0 || length > MAXDWORD) return 0;
    wchar_t *wide = windows_utf8_path(path);
    if (wide == NULL) return 0;
    HANDLE handle = CreateFileW(wide, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING,
            FILE_FLAG_OPEN_REPARSE_POINT, NULL);
    free(wide);
    if (handle == INVALID_HANDLE_VALUE) return 0;
    BY_HANDLE_FILE_INFORMATION info;
    DWORD count = 0;
    int success = GetFileType(handle) == FILE_TYPE_DISK &&
        GetFileInformationByHandle(handle, &info) &&
        (info.dwFileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT)) == 0 &&
        ReadFile(handle, prefix, (DWORD)length, &count, NULL) && count == (DWORD)length;
    CloseHandle(handle);
    return success;
}

static int win_commit_pair(AtomicFile *first, AtomicFile *second, int replace)
{
    AtomicFile *files[2] = {first, second};
    HANDLE old[2] = {INVALID_HANDLE_VALUE, INVALID_HANDLE_VALUE};
    char *backups[2] = {NULL, NULL};
    int published[2] = {0, 0};
    int preserve[2] = {0, 0};
    int keep_new[2] = {0, 0};
    int restored[2] = {0, 0};
    size_t count = second == NULL ? 1 : 2;
    int publishing = 0;
    int success = 0;
    if (first == NULL || first == second || first->platform_state == NULL ||
        (second != NULL && second->platform_state == NULL)) return 0;
    if (second != NULL) {
        WinAtomic *a = first->platform_state, *b = second->platform_state;
        if (_wcsicmp(a->path.full, b->path.full) == 0 ||
            (win_same(a->path.parents[a->path.count-1], b->path.parents[b->path.count-1]) &&
             _wcsicmp(wcsrchr(a->path.full,L'\\'),wcsrchr(b->path.full,L'\\')) == 0)) goto cleanup;
    }
    for (size_t i = 0; i < count; ++i) {
        WinAtomic *state = files[i]->platform_state;
        if (files[i]->stream != NULL && !atomic_file_prepare(files[i])) goto cleanup;
        old[i] = CreateFileW(state->path.full, GENERIC_READ | DELETE | READ_CONTROL,
                FILE_SHARE_READ | FILE_SHARE_DELETE, NULL, OPEN_EXISTING,
                FILE_FLAG_OPEN_REPARSE_POINT, NULL);
        if (old[i] == INVALID_HANDLE_VALUE) {
            DWORD error = GetLastError();
            if (error != ERROR_FILE_NOT_FOUND) goto cleanup;
        } else if (!replace) {
            /* Create-only: the rename below also refuses a name that appears. */
            SetLastError(ERROR_FILE_EXISTS);
            errno = EEXIST;
            goto cleanup;
        } else {
            /* Never overwrite a reparse point, hard-linked or broadly accessible output. */
            (void)win_inspect_key_artifacts(files[i]->final_path, old[i], 0, files[i]->temporary_path);
            if (!win_regular(old[i], 1)) goto cleanup;

        }
    }
    if (count == 2 && old[0] != INVALID_HANDLE_VALUE && old[1] != INVALID_HANDLE_VALUE &&
        win_same(old[0], old[1])) goto cleanup;
    /* Cover late backups, publication, rollback and all recovery cleanup. */
    AcquireSRWLockExclusive(&win_publication);
    publishing = 1;
    for (size_t i = 0; i < count; ++i) {
        WinAtomic *state = files[i]->platform_state;
        if (old[i] != INVALID_HANDLE_VALUE) {
            backups[i] = win_random_path(files[i]->final_path, ".bak.");
            wchar_t *backup = backups[i] != NULL ? windows_utf8_path(backups[i]) : NULL;
            if (backup == NULL) goto cleanup;
            int linked = CreateHardLinkW(backup, state->path.full, NULL) != 0;
            HANDLE saved = linked ? CreateFileW(backup, GENERIC_READ | DELETE | READ_CONTROL,
                    FILE_SHARE_READ | FILE_SHARE_DELETE, NULL, OPEN_EXISTING,
                    FILE_FLAG_OPEN_REPARSE_POINT, NULL) : INVALID_HANDLE_VALUE;
            free(backup);
            if (linked && saved != INVALID_HANDLE_VALUE && win_same(old[i], saved)) {
                CloseHandle(old[i]);
                old[i] = saved;
            } else {
                if (saved != INVALID_HANDLE_VALUE) CloseHandle(saved);
                if (linked) goto cleanup;
            }
            if (!linked) { free(backups[i]); backups[i] = NULL; goto cleanup; }
        }

        if (!win_keep(state->output) ||
            !win_rename(state->output, state->path.full, old[i] != INVALID_HANDLE_VALUE, 1)) goto cleanup;
        published[i] = 1;
        /* Verify the published name identifies the retained output handle. */
        HANDLE named = CreateFileW(state->path.full, FILE_READ_ATTRIBUTES,
                FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL,
                OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT, NULL);
        int same = named != INVALID_HANDLE_VALUE && win_same(state->output, named);
        if (named != INVALID_HANDLE_VALUE) CloseHandle(named);
        if (!same) goto cleanup;
    }
    /* Flush again with the new name before reporting commit success. This is
     * supported file-handle flushing, not a claim of POSIX directory fsync. */
    for (size_t i = 0; i < count; ++i) {
        WinAtomic *state = files[i]->platform_state;
        if (!win_flush(state->output)) goto cleanup;
    }
    success = 1;
cleanup:
    if (!success) {
        int rollback_failed = 0;

        for (size_t i = count; i-- > 0;) {
            if (!published[i]) continue;
            WinAtomic *state = files[i]->platform_state;
            HANDLE named = win_open_identity(files[i]->final_path);
            int ours = named != INVALID_HANDLE_VALUE && win_same(state->output, named);
            if (named != INVALID_HANDLE_VALUE) CloseHandle(named);
            if (!ours) {
                preserve[i] = backups[i] != NULL;
                continue;
            }
            if (old[i] != INVALID_HANDLE_VALUE) {
                wchar_t *staging = windows_utf8_path(files[i]->temporary_path);
                int moved = staging != NULL && win_rename(state->output, staging, 0, 2);
                free(staging);
                if (!moved || !win_rename(old[i], state->path.full, 0, 2)) {
                    rollback_failed = 1;
                    print_system_error("Cannot restore atomic output backup");
                    break;
                }
                restored[i] = 1;
            } else if (!win_dispose(state->output)) {
                rollback_failed = 1;
                break;
            }
        }
        if (rollback_failed) {
            fprintf(stderr, file_message("Key-pair rollback stopped; retaining the new outputs where possible\n"));
            for (size_t i = 0; i < count; ++i) {
                WinAtomic *state = files[i]->platform_state;
                if (state == NULL || state->output == INVALID_HANDLE_VALUE) continue;
                preserve[i] = backups[i] != NULL;
                int available = win_keep(state->output);
                HANDLE named = CreateFileW(state->path.full, DELETE | FILE_READ_ATTRIBUTES,
                        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL, OPEN_EXISTING,
                        FILE_FLAG_OPEN_REPARSE_POINT, NULL);
                DWORD named_error = GetLastError();
                int ours = named != INVALID_HANDLE_VALUE && win_same(state->output, named);
                int previous = named != INVALID_HANDLE_VALUE && old[i] != INVALID_HANDLE_VALUE && win_same(old[i], named);
                int vacant = named == INVALID_HANDLE_VALUE && win_missing(named_error);
                int ready = ours || vacant;
                if (previous) {
                    if (backups[i] == NULL) {
                        backups[i] = win_random_path(files[i]->final_path, ".bak.");
                        wchar_t *backup = backups[i] != NULL ? windows_utf8_path(backups[i]) : NULL;
                        int linked = backup != NULL && CreateHardLinkW(backup, state->path.full, NULL);
                        HANDLE saved = linked ? CreateFileW(backup, GENERIC_READ | DELETE | READ_CONTROL,
                                FILE_SHARE_READ | FILE_SHARE_DELETE, NULL, OPEN_EXISTING,
                                FILE_FLAG_OPEN_REPARSE_POINT, NULL) : INVALID_HANDLE_VALUE;
                        free(backup);
                        if (saved != INVALID_HANDLE_VALUE && win_same(old[i], saved)) {
                            CloseHandle(old[i]);
                            old[i] = saved;
                            preserve[i] = 1;
                        } else {
                            if (saved != INVALID_HANDLE_VALUE) CloseHandle(saved);
                            previous = 0;
                            ready = 0;
                            if (!linked) { free(backups[i]); backups[i] = NULL; }
                        }
                    }
                    if (previous && restored[i]) {
                        wchar_t *backup = backups[i] != NULL ? windows_utf8_path(backups[i]) : NULL;
                        ready = backup != NULL && win_rename(old[i], backup, 0, 0);
                        free(backup);
                    } else if (previous) {
                        /* The old backup handle pins the other link, so
                         * deleting this named old entry keeps its backup. */
                        ready = win_dispose(named);
                    }
                }
                if (named != INVALID_HANDLE_VALUE) CloseHandle(named);
                int retained = available && ready &&
                    (ours || win_rename(state->output, state->path.full, 0, 0));
                /* The output handle also keeps a failed-recovery staging
                 * file available. Never let abort delete that recovery copy. */
                keep_new[i] = win_keep(state->output);
                if (!retained) {
                    wchar_t actual[32768];
                    DWORD actual_length = GetFinalPathNameByHandleW(state->output, actual, 32768, FILE_NAME_NORMALIZED);
                    char *location = actual_length > 0 && actual_length < 32768 ? win_utf8_display(actual) : NULL;
                    fprintf(stderr, file_message("New contents for %s are staged at %s\n"),
                            file_display_safe_path(files[i]->final_path),
                            file_display_safe_path(location != NULL ? location : files[i]->temporary_path));
                    free(location);
                }
                fprintf(stderr, file_message("New output at %s: %s\n"),
                        file_display_safe_path(files[i]->final_path),
                        file_message(retained ? "retained" : "recovery required"));
                if (retained) (void)win_flush(state->output);
            }
        }
        for (size_t i = 0; i < count; ++i) {
            if (preserve[i] && backups[i] != NULL) {
                fprintf(stderr, file_message("The previous contents of %s are kept as %s\n"),
                        file_display_safe_path(files[i]->final_path),
                        file_display_safe_path(backups[i]));
            }
        }
    }
    for (size_t i = 0; i < count; ++i) {
        if (old[i] != INVALID_HANDLE_VALUE) CloseHandle(old[i]);
        if (backups[i] != NULL) {
            if (!preserve[i]) (void)windows_delete_regular(backups[i]);
            free(backups[i]);
        }
        if (success || keep_new[i]) {
            WinAtomic *state = files[i]->platform_state;
            CloseHandle(state->output);
            win_path_close(&state->path);
            free(state);
            free(files[i]->temporary_path);
            memset(files[i], 0, sizeof(*files[i]));
        } else atomic_file_abort(files[i]);
    }
    if (publishing) ReleaseSRWLockExclusive(&win_publication);
    if (!success) print_system_error("Cannot commit atomic output transaction");
    return success;
}

int atomic_file_commit_pair(AtomicFile *first, AtomicFile *second)
{
    return win_commit_pair(first, second, 1);
}

int atomic_file_commit_pair_new(AtomicFile *first, AtomicFile *second)
{
    return win_commit_pair(first, second, 0);
}

int atomic_file_commit(AtomicFile *file)
{
    return output_no_replace != 0
        ? atomic_file_commit_pair_new(file, NULL)
        : atomic_file_commit_pair(file, NULL);
}

void file_remove_temporary_outputs(void)
{
    /* Never released: the process is ending. */
    AcquireSRWLockExclusive(&win_publication);
}

void atomic_file_abort(AtomicFile *file)
{
    if (file == NULL) return;
    if (file->stream != NULL) (void)fclose(file->stream);
    WinAtomic *state = file->platform_state;
    if (state != NULL) {
        if (state->output != INVALID_HANDLE_VALUE) {
            (void)win_dispose(state->output);
            CloseHandle(state->output);
        }
        win_path_close(&state->path);
        free(state);
    }
    free(file->temporary_path);
    memset(file, 0, sizeof(*file));
}

int windows_delete_regular(const char *path)
{
    HANDLE handle = win_open(path, DELETE, FILE_SHARE_READ | FILE_SHARE_DELETE, 0);
    if (handle == INVALID_HANDLE_VALUE) return errno == ENOENT;
    int result = win_dispose(handle);
    CloseHandle(handle);
    return result;
}

int windows_remove_directory(const char *path)
{
    WinPath checked;
    if (!win_path_open(&checked, path, 1)) return 0;
    HANDLE handle = CreateFileW(checked.full, DELETE | READ_CONTROL,
            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL, OPEN_EXISTING,
            FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, NULL);
    BY_HANDLE_FILE_INFORMATION info;
    int result = handle != INVALID_HANDLE_VALUE && GetFileInformationByHandle(handle, &info) &&
                 (info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) == 0 && win_acl(handle,1) &&
                 win_dispose(handle);
    if (handle != INVALID_HANDLE_VALUE) CloseHandle(handle);
    win_path_close(&checked);
    return result;
}

int windows_create_private_temp(char **path, FILE **stream)
{
    wchar_t base[32768];
    char utf8[32768];
    DWORD size = GetTempPathW(32768, base);
    if (size == 0 || size >= 32768 ||
        WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, base, -1, utf8, 32768, NULL, NULL) == 0) return 0;
    char *directory = win_random_path(utf8, "nekokem-paste-");
    if (directory == NULL || !ensure_directory(directory, 0700)) { free(directory); return 0; }
    size_t length = strlen(directory);
    char *name = malloc(length + sizeof("/key.pem"));
    if (name == NULL) { (void)windows_remove_directory(directory); free(directory); return 0; }
    (void)snprintf(name, length + sizeof("/key.pem"), "%s/key.pem", directory);
    wchar_t *wide = windows_utf8_path(name);
    HANDLE handle = wide != NULL ? win_create(wide, 0) : INVALID_HANDLE_VALUE;
    free(wide);
    *stream = handle != INVALID_HANDLE_VALUE ? win_stream(handle, _O_WRONLY, "wb") : NULL;
    if (*stream == NULL) {
        (void)windows_delete_regular(name);
        (void)windows_remove_directory(directory);
        free(name);
    } else *path = name;
    free(directory);
    return *stream != NULL;
}
