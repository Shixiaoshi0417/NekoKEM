#include "nekokem.h"
#include "file.h"
#include "windows_io.h"
#include "private_key.h"
#include "secure_mem.h"
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const unsigned char password[] = "windows-test-only-password";
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"Check failed at line %d: %s\n",__LINE__,#x); return EXIT_FAILURE; } } while (0)
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
    CHECK(nekokem_private_key_exists("private.enc"));
    CHECK(nekokem_check_private_key_password("private.enc",password,sizeof(password)-1));
    CHECK(!nekokem_check_private_key_password("private.enc",(const unsigned char *)"wrong",5));
    CHECK(write_private("plain","binary\r\n\032",9));
    CHECK(nekokem_encrypt_file("plain","cipher.nkem","public.key"));
    CHECK(nekokem_decrypt_file("cipher.nkem","output","private.enc",password,sizeof(password)-1));
    CHECK(equals("output","binary\r\n\032"));
    CHECK(write_private("output","sentinel",8));
    CHECK(nekokem_encrypt_file_with_progress("plain","output","public.key",cancel,NULL) == NEKOKEM_OPERATION_CANCELLED);
    CHECK(equals("output","sentinel"));
    CHECK(nekokem_decrypt_file_with_progress("cipher.nkem","output","private.enc",password,sizeof(password)-1,cancel,NULL) == NEKOKEM_OPERATION_CANCELLED);
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
    CHECK(atomic_file_open(&first,"first",0600) && atomic_file_open(&second,"FIRST",0600));
    CHECK(!atomic_file_commit_pair(&first,&second));
    CHECK(equals("first","first-old"));
    /* Existing handles pin the output parent against replacement. */
    CHECK(atomic_file_open(&first,"private/result",0600));
    CHECK(!MoveFileW(L"private",L"moved"));
    atomic_file_abort(&first);
    CHECK(no_artifacts());
    CHECK(nekokem_delete_private_key("private.enc"));
    CHECK(nekokem_delete_private_key("private.enc"));
    puts("Windows native crypto, ACL/owner/link, paths, cancellation, rollback and flush tests passed");
    return EXIT_SUCCESS;
}
