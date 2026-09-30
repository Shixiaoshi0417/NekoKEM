#include "hybrid.h"
#include "private_key.h"
#include "secure_mem.h"

#include <openssl/buffer.h>
#include <openssl/pem.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const unsigned char password[] = "pem-interaction-test-password";

static int write_fixtures(const char *directory)
{
    EVP_PKEY *x448 = NULL;
    EVP_PKEY *mlkem = NULL;
    BIO *bio = NULL;
    BUF_MEM *buffer = NULL;
    FILE *output = NULL;
    char raw_path[512];
    char protected_path[512];
    unsigned int encrypted_component;
    int success = 0;

    x448 = EVP_PKEY_Q_keygen(NULL, NULL, X448_ALGORITHM_NAME);
    mlkem = EVP_PKEY_Q_keygen(NULL, NULL, KEM_ALGORITHM_NAME);
    if (x448 == NULL || mlkem == NULL) {
        goto cleanup;
    }
    for (encrypted_component = 0U; encrypted_component < 2U;
         ++encrypted_component) {
        int raw_count = snprintf(raw_path, sizeof(raw_path), "%s/raw-%u.pem",
                                 directory, encrypted_component);
        int protected_count = snprintf(protected_path, sizeof(protected_path),
                                       "%s/protected-%u.enc",
                                       directory, encrypted_component);
        if (raw_count < 0 || (size_t)raw_count >= sizeof(raw_path) ||
            protected_count < 0 ||
            (size_t)protected_count >= sizeof(protected_path)) {
            goto cleanup;
        }
        bio = BIO_new(BIO_s_mem());
        if (bio == NULL ||
            PEM_write_bio_PrivateKey(
                bio, x448, encrypted_component == 0U ? EVP_aes_256_cbc() : NULL,
                password, (int)(sizeof(password) - 1U), NULL, NULL) != 1 ||
            PEM_write_bio_PrivateKey(
                bio, mlkem, encrypted_component == 1U ? EVP_aes_256_cbc() : NULL,
                password, (int)(sizeof(password) - 1U), NULL, NULL) != 1 ||
            BIO_get_mem_ptr(bio, &buffer) <= 0 || buffer == NULL ||
            buffer->data == NULL) {
            goto cleanup;
        }
        output = fopen(raw_path, "wb");
        if (output == NULL ||
            fwrite(buffer->data, 1U, buffer->length, output) != buffer->length) {
            goto cleanup;
        }
        if (fclose(output) != 0) {
            output = NULL;
            goto cleanup;
        }
        output = NULL;
        if (!protected_private_key_write(
                protected_path, (const unsigned char *)buffer->data,
                buffer->length, password, sizeof(password) - 1U)) {
            goto cleanup;
        }
        secure_mem_clear(buffer->data, buffer->max);
        BIO_free(bio);
        bio = NULL;
        buffer = NULL;
    }
    success = 1;

cleanup:
    if (output != NULL) {
        (void)fclose(output);
    }
    if (bio != NULL && BIO_get_mem_ptr(bio, &buffer) > 0 && buffer != NULL) {
        secure_mem_clear(buffer->data, buffer->max);
    }
    BIO_free(bio);
    EVP_PKEY_free(mlkem);
    EVP_PKEY_free(x448);
    return success;
}

int main(int argc, char **argv)
{
    HybridKeys keys = {0};
    int result;

    if (argc != 3) {
        return EXIT_FAILURE;
    }
    if (strcmp(argv[1], "fixtures") == 0) {
        return write_fixtures(argv[2]) != 0 ? EXIT_SUCCESS : EXIT_FAILURE;
    }
    if (strcmp(argv[1], "raw") == 0) {
        result = hybrid_load_private_keys(argv[2], &keys);
    } else if (strcmp(argv[1], "protected") == 0) {
        result = hybrid_load_protected_private_keys(
            argv[2], password, sizeof(password) - 1U, &keys);
    } else {
        return EXIT_FAILURE;
    }
    hybrid_keys_cleanup(&keys);
    return result == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
