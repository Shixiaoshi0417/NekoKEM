#include <openssl/crypto.h>
#include <openssl/opensslv.h>
#include <openssl/thread.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(void)
{
    if (OpenSSL_version_num() != OPENSSL_VERSION_NUMBER ||
        strcmp(OpenSSL_version(OPENSSL_VERSION_STRING),
               OPENSSL_VERSION_STR) != 0) {
        fputs("OpenSSL linked runtime does not match its headers\n", stderr);
        return EXIT_FAILURE;
    }
    if ((OSSL_get_thread_support_flags() &
         OSSL_THREAD_SUPPORT_FLAG_THREAD_POOL) == 0U) {
        fputs("OpenSSL Argon2 thread-pool support is unavailable\n", stderr);
        return EXIT_FAILURE;
    }
    printf("OpenSSL header/runtime and Argon2 thread support verified: %s\n",
           OpenSSL_version(OPENSSL_VERSION));
    return EXIT_SUCCESS;
}
