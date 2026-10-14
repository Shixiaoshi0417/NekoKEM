#include "nekokem.h"
#include "file.h"
#include "windows_io.h"
#include "private_key.h"
#include "secure_mem.h"
#include <windows.h>
#include <aclapi.h>
#include <sddl.h>
#include <errno.h>
#include <io.h>
#include <openssl/crypto.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

void windows_test_path_trace(const wchar_t *expected, const wchar_t *actual)
{
    fprintf(stderr,"Native path expected=%ls actual=%ls\n",expected,actual);
}
void windows_test_read_trace(const char *stage, uint64_t size, size_t maximum, int error, unsigned long native_error)
{
    fprintf(stderr,"Native read stage=%s, size=%llu, maximum=%zu, errno=%d, win32=%lu\n",
            stage,(unsigned long long)size,maximum,error,native_error);
}
static const unsigned char password[] = "windows-test-only-password";
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"Check failed at line %d: %s (errno=%d, win32=%lu)\n",__LINE__,#x,errno,(unsigned long)GetLastError()); return EXIT_FAILURE; } } while (0)
static int write_private(const char *path, const void *bytes, size_t length)
{
    AtomicFile output = {0};
    int result = atomic_file_open(&output,path,0600) && file_write_all(output.stream,bytes,length) && atomic_file_commit(&output);
    atomic_file_abort(&output);
    return result;
}
static int equals(const char *path, const char *value)
{
    unsigned char *bytes = NULL;
    size_t length = 0;
    int result = file_read_regular(path,1048576,&bytes,&length) &&
                 length == strlen(value) && memcmp(bytes,value,length) == 0;
    OPENSSL_free(bytes);
    return result;
}
static int cancel(uint64_t done, uint64_t total, void *data)
{
    (void)done; (void)total; (void)data;
    return 0;
}
static int cancel_after_data(uint64_t done, uint64_t total, void *data)
{
    (void)total; (void)data;
    return done == 0;
}
static int no_artifacts(void)
{
    WIN32_FIND_DATAW info;
    HANDLE search = FindFirstFileW(L"*",&info);
    if (search == INVALID_HANDLE_VALUE) return 0;
    int success = 1;
    do {
        if (wcsstr(info.cFileName,L".tmp.") || wcsstr(info.cFileName,L".bak.")) success = 0;
    } while (FindNextFileW(search,&info));
    FindClose(search);
    return success;
}
static int restore_failure_retains_pair(unsigned int restore_call)
{
    AtomicFile first = {0}, second = {0};
    CHECK(write_private("restore-first", "first-old", 9));
    CHECK(write_private("restore-second", "second-old", 10));
    CHECK(atomic_file_open(&first, "restore-first", 0600) &&
          atomic_file_open(&second, "restore-second", 0600));
    CHECK(file_write_all(first.stream, "first-new", 9) &&
          file_write_all(second.stream, "second-new", 10));
    file_test_fault_set(FILE_TEST_FAULT_FSYNC, 3);
    file_test_set_restore_failure(restore_call);
    CHECK(!atomic_file_commit_pair(&first, &second));
    file_test_fault_reset();
    CHECK(equals("restore-first", "first-new") && equals("restore-second", "second-new"));
    WIN32_FIND_DATAW entry;
    HANDLE search = FindFirstFileW(L"restore-*.bak.*", &entry);
    CHECK(search != INVALID_HANDLE_VALUE);
    unsigned int backups = 0;
    do {
        char name[512];
        CHECK(WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, entry.cFileName, -1,
                                 name, sizeof(name), NULL, NULL) > 0);
        CHECK(equals(name, "first-old") || equals(name, "second-old"));
        CHECK(windows_delete_regular(name));
        ++backups;
    } while (FindNextFileW(search, &entry));
    FindClose(search);
    CHECK(backups == 2);
    CHECK(windows_delete_regular("restore-first") && windows_delete_regular("restore-second"));
    return EXIT_SUCCESS;
}

static unsigned int residue_warning_count;
static const char *record_residue_warning(const char *message)
{
    if (strcmp(message, "Key transaction residue at %s; inspect it before cleanup or recovery\n") == 0)
        ++residue_warning_count;
    return message;
}

static int file_audit_regressions(void)
{
    FILE *input = file_open_regular("plain");
    unsigned char prefix[2];
    /* A bulk read avoids the CRT's internal single-character input slot. */
    CHECK(input != NULL && fread(prefix, 1, sizeof(prefix), input) == sizeof(prefix) &&
          memcmp(prefix, "bi", sizeof(prefix)) == 0 && _telli64(_fileno(input)) == 2);
    CHECK(fclose(input) == 0);
    AtomicFile output = {0};
    CHECK(atomic_file_open(&output, "unbuffered-output", 0600));
    CHECK(fputc('x', output.stream) != EOF && _filelengthi64(_fileno(output.stream)) == 1);
    atomic_file_abort(&output);
    int previous = file_set_output_no_replace(1);
    CHECK(!write_private("plain", "replacement", 11));
    CHECK(equals("plain", "binary\r\n\032"));
    (void)file_set_output_no_replace(previous);
    file_set_message_translator(record_residue_warning);
    residue_warning_count = 0;
    CHECK(write_private("plain", "binary\r\n\032", 9) && residue_warning_count == 0);
    CHECK(write_private("plain.tmp.0123456789abcdef0123456789abcdef", "old-staging", 11));
    CHECK(write_private("plain", "binary\r\n\032", 9) && residue_warning_count == 1);
    file_set_message_translator(NULL);
    CHECK(windows_delete_regular("plain.tmp.0123456789abcdef0123456789abcdef"));
    CHECK(restore_failure_retains_pair(1) == EXIT_SUCCESS);
    CHECK(restore_failure_retains_pair(2) == EXIT_SUCCESS);
    CHECK(restore_failure_retains_pair(3) == EXIT_SUCCESS);
    CHECK(restore_failure_retains_pair(4) == EXIT_SUCCESS);
    CHECK(CreateHardLinkW(L"private.enc.bak.0123456789abcdef0123456789abcdef", L"private.enc", NULL));
    CHECK(!nekokem_private_key_exists("private.enc"));
    CHECK(nekokem_delete_private_key("private.enc"));
    CHECK(GetFileAttributesW(L"private.enc.bak.0123456789abcdef0123456789abcdef") == INVALID_FILE_ATTRIBUTES);
    CHECK(GetFileAttributesW(L"private.enc") == INVALID_FILE_ATTRIBUTES);
    return EXIT_SUCCESS;
}

int main(int argc, char **argv)
{
    if (argc == 4 && strcmp(argv[1],"secure-copy") == 0) {
        unsigned char *data = NULL; size_t length = 0;
        CHECK(file_read_regular(argv[2],1048576,&data,&length));
        CHECK(write_private(argv[3],data,length));
        secure_free(data,length);
        return EXIT_SUCCESS;
    }
    CHECK(argc == 1);
    CHECK(ensure_directory("private",0700));
    CHECK(nekokem_generate_keypair("public.key","private.enc",password,sizeof(password)-1));
    /* OWNER RIGHTS is bound to the verified owner, not an additional user. */
    PSECURITY_DESCRIPTOR owner_descriptor = NULL;
    PACL owner_acl = NULL;
    BOOL present = FALSE, defaulted = FALSE;
    CHECK(ConvertStringSecurityDescriptorToSecurityDescriptorW(L"D:P(A;;FA;;;OW)",
          SDDL_REVISION_1,&owner_descriptor,NULL));
    CHECK(GetSecurityDescriptorDacl(owner_descriptor,&present,&owner_acl,&defaulted) && present);
    wchar_t private_name[] = L"private.enc";
    CHECK(SetNamedSecurityInfoW(private_name,SE_FILE_OBJECT,
          DACL_SECURITY_INFORMATION|PROTECTED_DACL_SECURITY_INFORMATION,
          NULL,NULL,owner_acl,NULL) == ERROR_SUCCESS);
    LocalFree(owner_descriptor);
    CHECK(GetFileAttributesW(L"public.key") != INVALID_FILE_ATTRIBUTES);
    CHECK(GetFileAttributesW(L"private.enc") != INVALID_FILE_ATTRIBUTES);
    CHECK(nekokem_private_key_exists("private.enc"));
    CHECK(nekokem_check_private_key_password("private.enc",password,sizeof(password)-1));
    CHECK(!nekokem_check_private_key_password("private.enc",(const unsigned char *)"wrong",5));
    CHECK(write_private("plain","binary\r\n\032",9));
    CHECK(nekokem_encrypt_file("plain","cipher.nkem","public.key"));
    CHECK(GetFileAttributesW(L"cipher.nkem") != INVALID_FILE_ATTRIBUTES);
    CHECK(nekokem_decrypt_file("cipher.nkem","output","private.enc",password,sizeof(password)-1));
    CHECK(equals("output","binary\r\n\032"));
    CHECK(write_private("output","sentinel",8));
    CHECK(nekokem_encrypt_file_with_progress("plain","output","public.key",cancel,NULL) == NEKOKEM_OPERATION_CANCELLED);
    CHECK(equals("output","sentinel"));
    CHECK(nekokem_decrypt_file_with_progress("cipher.nkem","output","private.enc",password,sizeof(password)-1,cancel,NULL) == NEKOKEM_OPERATION_CANCELLED);
    CHECK(equals("output","sentinel"));
    CHECK(nekokem_encrypt_file_with_progress("plain","output","public.key",cancel_after_data,NULL) == NEKOKEM_OPERATION_CANCELLED);
    CHECK(equals("output","sentinel"));
    CHECK(nekokem_decrypt_file_with_progress("cipher.nkem","output","private.enc",password,sizeof(password)-1,cancel_after_data,NULL) == NEKOKEM_OPERATION_CANCELLED);
    CHECK(equals("output","sentinel"));
    CHECK(!nekokem_decrypt_file("cipher.nkem","output","private.enc",(const unsigned char *)"wrong",5));
    CHECK(equals("output","sentinel"));
    unsigned char *cipher = NULL; size_t cipher_len = 0;
    CHECK(file_read_regular("cipher.nkem",1048576,&cipher,&cipher_len));
    cipher[cipher_len-1] ^= 1;
    CHECK(write_private("bad.nkem",cipher,cipher_len));
    OPENSSL_free(cipher);
    CHECK(!nekokem_decrypt_file("bad.nkem","output","private.enc",password,sizeof(password)-1));
    CHECK(equals("output","sentinel"));
    file_test_fault_set(FILE_TEST_FAULT_FOREIGN_OWNER,0);
    CHECK(!nekokem_private_key_exists("private.enc"));
    file_test_fault_reset();
    CHECK(CreateHardLinkW(L"alias.enc",L"private.enc",NULL));
    CHECK(!nekokem_private_key_exists("private.enc"));
    CHECK(DeleteFileW(L"alias.enc"));
    CHECK(nekokem_private_key_exists("private.enc"));
    CHECK(file_open_regular("NUL") == NULL);
    CHECK(file_open_regular("\\\\.\\pipe\\nekokem-no-writer") == NULL);
    CHECK(file_open_regular("plain:stream") == NULL);
    CHECK(file_open_regular("private") == NULL);
    CHECK(write_private("first","first-old",9) && write_private("second","second-old",10));
    AtomicFile first = {0}, second = {0};
    CHECK(atomic_file_open(&first,"first",0600) && atomic_file_open(&second,"second",0600));
    CHECK(file_write_all(first.stream,"first-new",9) && file_write_all(second.stream,"second-new",10));
    file_test_fault_set(FILE_TEST_FAULT_RENAME,2);
    CHECK(!atomic_file_commit_pair(&first,&second));
    file_test_fault_reset();
    CHECK(equals("first","first-old") && equals("second","second-old"));
    CHECK(atomic_file_open(&first,"first",0600) && file_write_all(first.stream,"new",3));
    file_test_fault_set(FILE_TEST_FAULT_FSYNC,1);
    CHECK(!atomic_file_commit(&first));
    file_test_fault_reset();
    CHECK(equals("first","first-old"));
    CHECK(atomic_file_open(&first,"first",0600) && atomic_file_open(&second,"second",0600));
    CHECK(file_write_all(first.stream,"first-new",9) && file_write_all(second.stream,"second-new",10));
    file_test_fault_set(FILE_TEST_FAULT_FSYNC,3);
    CHECK(!atomic_file_commit_pair(&first,&second));
    file_test_fault_reset();
    CHECK(equals("first","first-old") && equals("second","second-old"));
    file_test_fault_set(FILE_TEST_FAULT_ENOSPC,1);
    CHECK(!nekokem_encrypt_file("plain","output","public.key"));
    file_test_fault_reset();
    CHECK(equals("output","sentinel"));
    file_test_fault_set(FILE_TEST_FAULT_SHORT_WRITE,0);
    CHECK(nekokem_encrypt_file("plain","short.nkem","public.key"));
    file_test_fault_reset();
    CHECK(nekokem_decrypt_file("short.nkem","short-output","private.enc",password,sizeof(password)-1));
    CHECK(equals("short-output","binary\r\n\032"));
    CHECK(atomic_file_open(&first,"first",0600) && atomic_file_open(&second,"FIRST",0600));
    CHECK(!atomic_file_commit_pair(&first,&second));
    CHECK(equals("first","first-old"));
    /* Existing handles pin the output parent against replacement. */
    CHECK(atomic_file_open(&first,"private/result",0600));
    CHECK(!MoveFileW(L"private",L"moved"));
    atomic_file_abort(&first);
    CHECK(no_artifacts());
    CHECK(file_audit_regressions() == EXIT_SUCCESS);
    CHECK(no_artifacts());
    CHECK(nekokem_delete_private_key("private.enc"));
    CHECK(nekokem_delete_private_key("private.enc"));
    CHECK(nekokem_delete_private_key("absent-directory/private.enc"));
    puts("Windows native crypto, ACL/owner/link, paths, cancellation, rollback and flush tests passed");
    return EXIT_SUCCESS;
}
