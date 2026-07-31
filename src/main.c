#include "aes.h"
#include "file.h"
#include "hybrid.h"
#include "kem.h"
#include "private_key.h"
#include "secure_mem.h"

#include <errno.h>
#include <limits.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <openssl/x509.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <termios.h>
#include <unistd.h>

#define PUBLIC_KEY_PATH "keys/public.key"
#define PRIVATE_KEY_PATH "keys/private.key"
#define PROTECTED_PRIVATE_KEY_PATH "keys/private.key.enc"
#define MAX_PASSWORD_SIZE 1024U

typedef struct {
    unsigned char *data;
    size_t length;
    size_t capacity;
} PasswordBuffer;

static void print_usage(const char *program)
{
    fprintf(stderr,
            "Usage:\n"
            "  %s\n"
            "  %s keygen\n"
            "  %s keygen hybrid\n"
            "  %s encrypt <input_file> <output_file> <public.key>\n"
            "  %s decrypt <input_file> <output_file> <private.key>\n"
            "  %s encrypt hybrid <input_file> <output_file> <public.key>\n"
            "  %s decrypt hybrid <input_file> <output_file> "
            "<private.key|private.key.enc>\n",
            program, program, program, program, program, program, program);
}

static void password_buffer_cleanup(PasswordBuffer *password)
{
    if (password == NULL) {
        return;
    }
    secure_free(password->data, password->capacity);
    password->data = NULL;
    password->length = 0U;
    password->capacity = 0U;
}

static int read_password_line(const char *prompt,
                              PasswordBuffer *password)
{
    struct termios original_terminal;
    struct termios hidden_terminal;
    unsigned char *buffer = NULL;
    size_t length = 0U;
    size_t capacity = MAX_PASSWORD_SIZE + 1U;
    int character;
    int echo_disabled = 0;
    int too_long = 0;
    int success = 0;

    password_buffer_cleanup(password);
    buffer = OPENSSL_zalloc(capacity);
    if (buffer == NULL) {
        print_openssl_error("Cannot allocate password buffer");
        goto cleanup;
    }
    if (isatty(STDIN_FILENO) != 0) {
        if (tcgetattr(STDIN_FILENO, &original_terminal) != 0) {
            print_system_error("Cannot read terminal settings");
            goto cleanup;
        }
        hidden_terminal = original_terminal;
        hidden_terminal.c_lflag &= (tcflag_t)~ECHO;
        if (tcsetattr(STDIN_FILENO, TCSAFLUSH,
                      &hidden_terminal) != 0) {
            print_system_error("Cannot disable password echo");
            goto cleanup;
        }
        echo_disabled = 1;
    }
    if (fputs(prompt, stdout) == EOF || fflush(stdout) != 0) {
        print_system_error("Cannot display password prompt");
        goto cleanup;
    }

    for (;;) {
        character = fgetc(stdin);
        if (character == EOF || character == '\n') {
            break;
        }
        if (length < MAX_PASSWORD_SIZE) {
            buffer[length++] = (unsigned char)character;
        } else {
            too_long = 1;
        }
    }
    if (length > 0U && buffer[length - 1U] == '\r') {
        --length;
        buffer[length] = '\0';
    }
    if (character == EOF && ferror(stdin) != 0) {
        print_system_error("Cannot read password");
        goto cleanup;
    }
    if (too_long != 0) {
        fprintf(stderr, "Password exceeds %u bytes\n",
                MAX_PASSWORD_SIZE);
        goto cleanup;
    }
    if (length == 0U) {
        fprintf(stderr, "Password must not be empty\n");
        goto cleanup;
    }

    password->data = buffer;
    password->length = length;
    password->capacity = capacity;
    buffer = NULL;
    success = 1;

cleanup:
    if (echo_disabled != 0) {
        if (tcsetattr(STDIN_FILENO, TCSANOW,
                      &original_terminal) != 0) {
            print_system_error("Cannot restore terminal echo");
            success = 0;
        }
        if (fputc('\n', stdout) == EOF || fflush(stdout) != 0) {
            print_system_error("Cannot update terminal output");
            success = 0;
        }
    }
    secure_free(buffer, capacity);
    if (success == 0) {
        password_buffer_cleanup(password);
    }
    return success;
}

static int prompt_new_private_key_password(PasswordBuffer *password)
{
    PasswordBuffer confirmation = {0};
    int success = 0;

    if (!read_password_line("请输入私钥保护密码：", password) ||
        !read_password_line("请再次输入私钥保护密码：",
                            &confirmation)) {
        goto cleanup;
    }
    if (password->length != confirmation.length ||
        CRYPTO_memcmp(password->data, confirmation.data,
                      password->length) != 0) {
        fprintf(stderr, "两次输入的密码不一致\n");
        goto cleanup;
    }
    success = 1;

cleanup:
    password_buffer_cleanup(&confirmation);
    if (success == 0) {
        password_buffer_cleanup(password);
    }
    return success;
}

static unsigned char *build_aad(
    const unsigned char header[NKEM_HEADER_SIZE],
    const unsigned char *kem_ciphertext,
    size_t kem_ciphertext_len,
    const unsigned char nonce[NKEM_NONCE_SIZE],
    size_t *aad_len)
{
    unsigned char *aad;
    size_t length;

    if (kem_ciphertext_len >
        SIZE_MAX - NKEM_HEADER_SIZE - NKEM_NONCE_SIZE) {
        fprintf(stderr, "Authenticated metadata length overflows\n");
        return NULL;
    }
    length = NKEM_HEADER_SIZE + kem_ciphertext_len + NKEM_NONCE_SIZE;
    aad = OPENSSL_malloc(length);
    if (aad == NULL) {
        print_openssl_error("Cannot allocate authenticated metadata");
        return NULL;
    }
    memcpy(aad, header, NKEM_HEADER_SIZE);
    memcpy(aad + NKEM_HEADER_SIZE, kem_ciphertext, kem_ciphertext_len);
    memcpy(aad + NKEM_HEADER_SIZE + kem_ciphertext_len,
           nonce, NKEM_NONCE_SIZE);
    *aad_len = length;
    return aad;
}

static int run_keygen(void)
{
    if (!ensure_directory("keys", 0700)) {
        return 0;
    }
    if (!kem_generate_keypair(PUBLIC_KEY_PATH, PRIVATE_KEY_PATH)) {
        return 0;
    }
    printf("Generated %s and %s\n", PUBLIC_KEY_PATH, PRIVATE_KEY_PATH);
    return 1;
}

static unsigned char *build_v2_aad(
    const unsigned char header[NKEM_V2_HEADER_SIZE],
    const unsigned char *ephemeral_public,
    size_t ephemeral_public_len,
    const unsigned char *kem_ciphertext,
    size_t kem_ciphertext_len,
    const unsigned char nonce[NKEM_NONCE_SIZE],
    size_t *aad_len)
{
    unsigned char *aad;
    size_t length = NKEM_V2_HEADER_SIZE;

    if (ephemeral_public_len > SIZE_MAX - length) {
        fprintf(stderr, "Authenticated metadata length overflows\n");
        return NULL;
    }
    length += ephemeral_public_len;
    if (kem_ciphertext_len > SIZE_MAX - length) {
        fprintf(stderr, "Authenticated metadata length overflows\n");
        return NULL;
    }
    length += kem_ciphertext_len;
    if (NKEM_NONCE_SIZE > SIZE_MAX - length) {
        fprintf(stderr, "Authenticated metadata length overflows\n");
        return NULL;
    }
    length += NKEM_NONCE_SIZE;

    aad = OPENSSL_malloc(length);
    if (aad == NULL) {
        print_openssl_error("Cannot allocate v2 authenticated metadata");
        return NULL;
    }
    memcpy(aad, header, NKEM_V2_HEADER_SIZE);
    memcpy(aad + NKEM_V2_HEADER_SIZE,
           ephemeral_public, ephemeral_public_len);
    memcpy(aad + NKEM_V2_HEADER_SIZE + ephemeral_public_len,
           kem_ciphertext, kem_ciphertext_len);
    memcpy(aad + NKEM_V2_HEADER_SIZE + ephemeral_public_len +
               kem_ciphertext_len,
           nonce, NKEM_NONCE_SIZE);
    *aad_len = length;
    return aad;
}

static int run_hybrid_keygen(void)
{
    PasswordBuffer password = {0};
    int success = 0;

    if (!ensure_directory("keys", 0700)) {
        goto cleanup;
    }
    if (!prompt_new_private_key_password(&password)) {
        goto cleanup;
    }
    if (!hybrid_generate_keypair(PUBLIC_KEY_PATH,
                                 PROTECTED_PRIVATE_KEY_PATH,
                                 password.data, password.length)) {
        goto cleanup;
    }
    password_buffer_cleanup(&password);
    printf("Generated hybrid X448 + ML-KEM-1024 keys: %s and %s\n",
           PUBLIC_KEY_PATH, PROTECTED_PRIVATE_KEY_PATH);
    success = 1;

cleanup:
    password_buffer_cleanup(&password);
    return success;
}

static int run_encrypt(const char *input_path,
                       const char *output_path,
                       const char *public_key_path)
{
    FILE *input = NULL;
    AtomicFile output = {0};
    EVP_PKEY *public_key = NULL;
    unsigned char *kem_ciphertext = NULL;
    unsigned char *shared_secret = NULL;
    unsigned char *aad = NULL;
    unsigned char header[NKEM_HEADER_SIZE];
    unsigned char nonce[NKEM_NONCE_SIZE];
    unsigned char tag[NKEM_TAG_SIZE];
    unsigned char aes_key[AES256_KEY_SIZE] = {0};
    size_t kem_ciphertext_len = 0U;
    size_t shared_secret_len = 0U;
    size_t aad_len = 0U;
    uint64_t plaintext_len = 0U;
    int success = 0;

    input = fopen(input_path, "rb");
    if (input == NULL) {
        print_system_error("Cannot open plaintext");
        goto cleanup;
    }
    if (!file_disable_buffering(input)) {
        goto cleanup;
    }
    if (!file_get_size(input, &plaintext_len)) {
        goto cleanup;
    }
    public_key = kem_load_public_key(public_key_path);
    if (public_key == NULL) {
        goto cleanup;
    }
    if (!kem_encapsulate(public_key, &kem_ciphertext,
                         &kem_ciphertext_len, &shared_secret,
                         &shared_secret_len)) {
        goto cleanup;
    }
    EVP_PKEY_free(public_key);
    public_key = NULL;
    if (kem_ciphertext_len > UINT32_MAX) {
        fprintf(stderr, "ML-KEM ciphertext is too large for NKEM v1\n");
        goto cleanup;
    }
    if (RAND_bytes(nonce, (int)sizeof(nonce)) != 1) {
        print_openssl_error("Cannot generate AES-GCM nonce");
        goto cleanup;
    }
    if (!derive_aes256_key(shared_secret, shared_secret_len,
                           nonce, sizeof(nonce), aes_key)) {
        goto cleanup;
    }
    secure_free(shared_secret, shared_secret_len);
    shared_secret = NULL;
    shared_secret_len = 0U;

    nkem_header_encode(header, (uint32_t)kem_ciphertext_len,
                       plaintext_len);
    aad = build_aad(header, kem_ciphertext, kem_ciphertext_len,
                    nonce, &aad_len);
    if (aad == NULL) {
        goto cleanup;
    }
    if (!atomic_file_open(&output, output_path, 0600)) {
        goto cleanup;
    }
    if (!file_write_all(output.stream, header, sizeof(header)) ||
        !file_write_all(output.stream, kem_ciphertext,
                        kem_ciphertext_len) ||
        !file_write_all(output.stream, nonce, sizeof(nonce))) {
        goto cleanup;
    }
    if (!aes_gcm_encrypt_file(input, output.stream, plaintext_len,
                              aes_key, nonce, aad, aad_len, tag)) {
        goto cleanup;
    }
    if (!file_write_all(output.stream, tag, sizeof(tag))) {
        goto cleanup;
    }
    if (!atomic_file_commit(&output)) {
        goto cleanup;
    }

    printf("Encrypted %s -> %s\n", input_path, output_path);
    success = 1;

cleanup:
    atomic_file_abort(&output);
    if (input != NULL) {
        (void)fclose(input);
    }
    EVP_PKEY_free(public_key);
    OPENSSL_free(kem_ciphertext);
    secure_free(shared_secret, shared_secret_len);
    OPENSSL_free(aad);
    secure_mem_clear(aes_key, sizeof(aes_key));
    return success;
}

static int run_decrypt(const char *input_path,
                       const char *output_path,
                       const char *private_key_path)
{
    FILE *input = NULL;
    AtomicFile output = {0};
    EVP_PKEY *private_key = NULL;
    unsigned char *kem_ciphertext = NULL;
    unsigned char *shared_secret = NULL;
    unsigned char *aad = NULL;
    unsigned char raw_header[NKEM_HEADER_SIZE];
    unsigned char nonce[NKEM_NONCE_SIZE];
    unsigned char aes_key[AES256_KEY_SIZE] = {0};
    NkemHeader header;
    size_t shared_secret_len = 0U;
    size_t aad_len = 0U;
    size_t kem_ciphertext_len;
    uint64_t container_size = 0U;
    int success = 0;

    input = fopen(input_path, "rb");
    if (input == NULL) {
        print_system_error("Cannot open NKEM input");
        goto cleanup;
    }
    if (!file_disable_buffering(input)) {
        goto cleanup;
    }
    if (!file_get_size(input, &container_size) ||
        !file_read_exact(input, raw_header, sizeof(raw_header)) ||
        !nkem_header_decode(raw_header, &header) ||
        !nkem_container_size_is_valid(&header, container_size)) {
        goto cleanup;
    }

    kem_ciphertext_len = (size_t)header.kem_ciphertext_len;
    kem_ciphertext = OPENSSL_malloc(kem_ciphertext_len);
    if (kem_ciphertext == NULL) {
        print_openssl_error("Cannot allocate ML-KEM ciphertext buffer");
        goto cleanup;
    }
    if (!file_read_exact(input, kem_ciphertext, kem_ciphertext_len) ||
        !file_read_exact(input, nonce, sizeof(nonce))) {
        goto cleanup;
    }
    private_key = kem_load_private_key(private_key_path);
    if (private_key == NULL) {
        goto cleanup;
    }
    if (!kem_decapsulate(private_key, kem_ciphertext,
                         kem_ciphertext_len, &shared_secret,
                         &shared_secret_len)) {
        goto cleanup;
    }
    EVP_PKEY_free(private_key);
    private_key = NULL;
    if (!derive_aes256_key(shared_secret, shared_secret_len,
                           nonce, sizeof(nonce), aes_key)) {
        goto cleanup;
    }
    secure_free(shared_secret, shared_secret_len);
    shared_secret = NULL;
    shared_secret_len = 0U;
    aad = build_aad(raw_header, kem_ciphertext, kem_ciphertext_len,
                    nonce, &aad_len);
    if (aad == NULL) {
        goto cleanup;
    }
    if (!atomic_file_open(&output, output_path, 0600)) {
        goto cleanup;
    }
    if (!aes_gcm_decrypt_file(input, output.stream,
                              header.ciphertext_len, aes_key, nonce,
                              aad, aad_len)) {
        goto cleanup;
    }
    if (fgetc(input) != EOF || ferror(input) != 0) {
        fprintf(stderr, "NKEM input has unexpected trailing data\n");
        goto cleanup;
    }
    if (!atomic_file_commit(&output)) {
        goto cleanup;
    }

    printf("Decrypted %s -> %s\n", input_path, output_path);
    success = 1;

cleanup:
    atomic_file_abort(&output);
    if (input != NULL) {
        (void)fclose(input);
    }
    EVP_PKEY_free(private_key);
    OPENSSL_free(kem_ciphertext);
    secure_free(shared_secret, shared_secret_len);
    OPENSSL_free(aad);
    secure_mem_clear(aes_key, sizeof(aes_key));
    return success;
}

static int run_hybrid_encrypt(const char *input_path,
                              const char *output_path,
                              const char *public_key_path)
{
    FILE *input = NULL;
    AtomicFile output = {0};
    HybridKeys public_keys = {0};
    unsigned char *x448_secret = NULL;
    unsigned char *kem_ciphertext = NULL;
    unsigned char *mlkem_secret = NULL;
    unsigned char *aad = NULL;
    unsigned char raw_header[NKEM_V2_HEADER_SIZE];
    unsigned char ephemeral_public[X448_PUBLIC_KEY_SIZE];
    unsigned char nonce[NKEM_NONCE_SIZE];
    unsigned char tag[NKEM_TAG_SIZE];
    unsigned char aes_key[AES256_KEY_SIZE] = {0};
    size_t x448_secret_len = 0U;
    size_t kem_ciphertext_len = 0U;
    size_t mlkem_secret_len = 0U;
    size_t aad_len = 0U;
    uint64_t plaintext_len = 0U;
    int success = 0;

    input = fopen(input_path, "rb");
    if (input == NULL) {
        print_system_error("Cannot open plaintext");
        goto cleanup;
    }
    if (!file_disable_buffering(input)) {
        goto cleanup;
    }
    if (!file_get_size(input, &plaintext_len)) {
        goto cleanup;
    }
    if (!hybrid_load_public_keys(public_key_path, &public_keys)) {
        goto cleanup;
    }
    if (!hybrid_x448_encapsulate(public_keys.x448,
                                 ephemeral_public,
                                 &x448_secret,
                                 &x448_secret_len)) {
        goto cleanup;
    }
    if (!kem_encapsulate(public_keys.mlkem, &kem_ciphertext,
                         &kem_ciphertext_len, &mlkem_secret,
                         &mlkem_secret_len)) {
        goto cleanup;
    }
    hybrid_keys_cleanup(&public_keys);
    if (kem_ciphertext_len > UINT32_MAX) {
        fprintf(stderr, "ML-KEM ciphertext is too large for NKEM v2\n");
        goto cleanup;
    }
    if (RAND_bytes(nonce, (int)sizeof(nonce)) != 1) {
        print_openssl_error("Cannot generate AES-GCM nonce");
        goto cleanup;
    }
    if (!hybrid_derive_aes256_key(
            x448_secret, x448_secret_len,
            mlkem_secret, mlkem_secret_len,
            nonce, sizeof(nonce), aes_key)) {
        goto cleanup;
    }
    secure_free(x448_secret, x448_secret_len);
    x448_secret = NULL;
    x448_secret_len = 0U;
    secure_free(mlkem_secret, mlkem_secret_len);
    mlkem_secret = NULL;
    mlkem_secret_len = 0U;

    nkem_v2_header_encode(
        raw_header, (uint16_t)sizeof(ephemeral_public),
        (uint32_t)kem_ciphertext_len, plaintext_len);
    aad = build_v2_aad(raw_header,
                       ephemeral_public, sizeof(ephemeral_public),
                       kem_ciphertext, kem_ciphertext_len,
                       nonce, &aad_len);
    if (aad == NULL) {
        goto cleanup;
    }
    if (!atomic_file_open(&output, output_path, 0600)) {
        goto cleanup;
    }
    if (!file_write_all(output.stream, raw_header, sizeof(raw_header)) ||
        !file_write_all(output.stream, ephemeral_public,
                        sizeof(ephemeral_public)) ||
        !file_write_all(output.stream, kem_ciphertext,
                        kem_ciphertext_len) ||
        !file_write_all(output.stream, nonce, sizeof(nonce))) {
        goto cleanup;
    }
    if (!aes_gcm_encrypt_file(input, output.stream, plaintext_len,
                              aes_key, nonce, aad, aad_len, tag)) {
        goto cleanup;
    }
    if (!file_write_all(output.stream, tag, sizeof(tag)) ||
        !atomic_file_commit(&output)) {
        goto cleanup;
    }

    printf("Encrypted hybrid NKEM v2 %s -> %s\n",
           input_path, output_path);
    success = 1;

cleanup:
    atomic_file_abort(&output);
    if (input != NULL) {
        (void)fclose(input);
    }
    hybrid_keys_cleanup(&public_keys);
    secure_free(x448_secret, x448_secret_len);
    OPENSSL_free(kem_ciphertext);
    secure_free(mlkem_secret, mlkem_secret_len);
    OPENSSL_free(aad);
    secure_mem_clear(aes_key, sizeof(aes_key));
    return success;
}

static int run_hybrid_decrypt(const char *input_path,
                              const char *output_path,
                              const char *private_key_path)
{
    FILE *input = NULL;
    AtomicFile output = {0};
    HybridKeys private_keys = {0};
    unsigned char *x448_secret = NULL;
    unsigned char *kem_ciphertext = NULL;
    unsigned char *mlkem_secret = NULL;
    unsigned char *aad = NULL;
    unsigned char raw_header[NKEM_V2_HEADER_SIZE];
    unsigned char ephemeral_public[X448_PUBLIC_KEY_SIZE];
    unsigned char nonce[NKEM_NONCE_SIZE];
    unsigned char aes_key[AES256_KEY_SIZE] = {0};
    NkemV2Header header;
    PasswordBuffer password = {0};
    size_t x448_secret_len = 0U;
    size_t kem_ciphertext_len;
    size_t mlkem_secret_len = 0U;
    size_t aad_len = 0U;
    uint64_t container_size = 0U;
    int success = 0;

    input = fopen(input_path, "rb");
    if (input == NULL) {
        print_system_error("Cannot open NKEM v2 input");
        goto cleanup;
    }
    if (!file_disable_buffering(input)) {
        goto cleanup;
    }
    if (!file_get_size(input, &container_size) ||
        !file_read_exact(input, raw_header, sizeof(raw_header)) ||
        !nkem_v2_header_decode(raw_header, &header) ||
        !nkem_v2_container_size_is_valid(&header, container_size)) {
        goto cleanup;
    }

    kem_ciphertext_len = (size_t)header.kem_ciphertext_len;
    kem_ciphertext = OPENSSL_malloc(kem_ciphertext_len);
    if (kem_ciphertext == NULL) {
        print_openssl_error("Cannot allocate ML-KEM ciphertext buffer");
        goto cleanup;
    }
    if (!file_read_exact(input, ephemeral_public,
                         sizeof(ephemeral_public)) ||
        !file_read_exact(input, kem_ciphertext, kem_ciphertext_len) ||
        !file_read_exact(input, nonce, sizeof(nonce))) {
        goto cleanup;
    }
    if (private_key_path_is_encrypted(private_key_path)) {
        int loaded;

        if (!read_password_line("请输入私钥密码：", &password)) {
            goto cleanup;
        }
        loaded = hybrid_load_protected_private_keys(
            private_key_path, password.data, password.length,
            &private_keys);
        password_buffer_cleanup(&password);
        if (!loaded) {
            goto cleanup;
        }
    } else if (!hybrid_load_private_keys(private_key_path,
                                         &private_keys)) {
        goto cleanup;
    }
    if (!hybrid_x448_decapsulate(
            private_keys.x448, ephemeral_public,
            (size_t)header.x448_ephemeral_len,
            &x448_secret, &x448_secret_len)) {
        goto cleanup;
    }
    if (!kem_decapsulate(private_keys.mlkem, kem_ciphertext,
                         kem_ciphertext_len, &mlkem_secret,
                         &mlkem_secret_len)) {
        goto cleanup;
    }
    hybrid_keys_cleanup(&private_keys);
    if (!hybrid_derive_aes256_key(
            x448_secret, x448_secret_len,
            mlkem_secret, mlkem_secret_len,
            nonce, sizeof(nonce), aes_key)) {
        goto cleanup;
    }
    secure_free(x448_secret, x448_secret_len);
    x448_secret = NULL;
    x448_secret_len = 0U;
    secure_free(mlkem_secret, mlkem_secret_len);
    mlkem_secret = NULL;
    mlkem_secret_len = 0U;
    aad = build_v2_aad(raw_header,
                       ephemeral_public, sizeof(ephemeral_public),
                       kem_ciphertext, kem_ciphertext_len,
                       nonce, &aad_len);
    if (aad == NULL) {
        goto cleanup;
    }
    if (!atomic_file_open(&output, output_path, 0600)) {
        goto cleanup;
    }
    if (!aes_gcm_decrypt_file(input, output.stream,
                              header.ciphertext_len, aes_key, nonce,
                              aad, aad_len)) {
        goto cleanup;
    }
    if (fgetc(input) != EOF || ferror(input) != 0) {
        fprintf(stderr, "NKEM v2 input has unexpected trailing data\n");
        goto cleanup;
    }
    if (!atomic_file_commit(&output)) {
        goto cleanup;
    }

    printf("Decrypted hybrid NKEM v2 %s -> %s\n",
           input_path, output_path);
    success = 1;

cleanup:
    password_buffer_cleanup(&password);
    atomic_file_abort(&output);
    if (input != NULL) {
        (void)fclose(input);
    }
    hybrid_keys_cleanup(&private_keys);
    secure_free(x448_secret, x448_secret_len);
    OPENSSL_free(kem_ciphertext);
    secure_free(mlkem_secret, mlkem_secret_len);
    OPENSSL_free(aad);
    secure_mem_clear(aes_key, sizeof(aes_key));
    return success;
}

#define MAX_PASTED_KEY_SIZE (1024U * 1024U)

static char *read_prompt_line(const char *prompt)
{
    char *line = NULL;
    size_t capacity = 0U;
    ssize_t length;

    if (fputs(prompt, stdout) == EOF || fflush(stdout) != 0) {
        print_system_error("Cannot display prompt");
        return NULL;
    }
    errno = 0;
    length = getline(&line, &capacity, stdin);
    if (length < 0) {
        if (ferror(stdin) != 0) {
            print_system_error("Cannot read input");
        }
        free(line);
        return NULL;
    }
    while (length > 0 &&
           (line[(size_t)length - 1U] == '\n' ||
            line[(size_t)length - 1U] == '\r')) {
        line[(size_t)length - 1U] = '\0';
        --length;
    }
    return line;
}

static int collect_pasted_key(int private_key, char **temporary_path)
{
    static const char public_end[] = "-----END PUBLIC KEY-----";
    static const char private_end[] = "-----END PRIVATE KEY-----";
    char path_template[] = "/tmp/nekokem-pasted-key.XXXXXX";
    const char *end_marker = private_key != 0 ? private_end : public_end;
    struct termios original_terminal;
    struct termios hidden_terminal;
    FILE *output = NULL;
    char *line = NULL;
    size_t line_capacity = 0U;
    size_t total_size = 0U;
    unsigned int end_markers = 0U;
    int descriptor = -1;
    int echo_disabled = 0;
    int success = 0;

    *temporary_path = NULL;
    descriptor = mkstemp(path_template);
    if (descriptor < 0) {
        print_system_error("Cannot create temporary key file");
        goto cleanup;
    }
    if (fchmod(descriptor, 0600) != 0) {
        print_system_error("Cannot protect temporary key file");
        goto cleanup;
    }
    output = fdopen(descriptor, "wb");
    if (output == NULL) {
        print_system_error("Cannot open temporary key stream");
        goto cleanup;
    }
    descriptor = -1;
    if (!file_disable_buffering(output)) {
        goto cleanup;
    }

    if (private_key != 0) {
        if (fputs("请粘贴两个 PEM 私钥块；输入过程不会回显。\n",
                  stdout) == EOF ||
            fflush(stdout) != 0) {
            print_system_error("Cannot display private-key prompt");
            goto cleanup;
        }
        if (isatty(STDIN_FILENO) != 0) {
            if (tcgetattr(STDIN_FILENO, &original_terminal) != 0) {
                print_system_error("Cannot read terminal settings");
                goto cleanup;
            }
            hidden_terminal = original_terminal;
            hidden_terminal.c_lflag &= (tcflag_t)~ECHO;
            if (tcsetattr(STDIN_FILENO, TCSAFLUSH,
                          &hidden_terminal) != 0) {
                print_system_error("Cannot disable private-key echo");
                goto cleanup;
            }
            echo_disabled = 1;
        }
    } else if (fputs("请粘贴两个 PEM 公钥块：\n", stdout) == EOF ||
               fflush(stdout) != 0) {
        print_system_error("Cannot display public-key prompt");
        goto cleanup;
    }

    while (end_markers < 2U) {
        ssize_t line_length;

        errno = 0;
        line_length = getline(&line, &line_capacity, stdin);
        if (line_length < 0) {
            if (ferror(stdin) != 0) {
                print_system_error("Cannot read pasted key");
            } else {
                fprintf(stderr, "Pasted key ended before two PEM blocks\n");
            }
            goto cleanup;
        }
        if ((size_t)line_length > MAX_PASTED_KEY_SIZE - total_size) {
            fprintf(stderr, "Pasted key exceeds the size limit\n");
            goto cleanup;
        }
        if (!file_write_all(output, line, (size_t)line_length)) {
            goto cleanup;
        }
        total_size += (size_t)line_length;
        if (strstr(line, end_marker) != NULL) {
            ++end_markers;
        }
    }

    if (echo_disabled != 0) {
        if (tcsetattr(STDIN_FILENO, TCSAFLUSH,
                      &original_terminal) != 0) {
            print_system_error("Cannot restore terminal echo");
            goto cleanup;
        }
        echo_disabled = 0;
        if (fputc('\n', stdout) == EOF || fflush(stdout) != 0) {
            print_system_error("Cannot update terminal output");
            goto cleanup;
        }
    }
    if (fflush(output) != 0 || fsync(fileno(output)) != 0) {
        print_system_error("Cannot flush temporary key file");
        goto cleanup;
    }
    if (fclose(output) != 0) {
        output = NULL;
        print_system_error("Cannot close temporary key file");
        goto cleanup;
    }
    output = NULL;

    *temporary_path = strdup(path_template);
    if (*temporary_path == NULL) {
        print_system_error("Cannot allocate temporary key path");
        goto cleanup;
    }
    success = 1;

cleanup:
    if (echo_disabled != 0) {
        if (tcsetattr(STDIN_FILENO, TCSAFLUSH,
                      &original_terminal) != 0) {
            print_system_error("Cannot restore terminal echo");
        }
        (void)fputc('\n', stdout);
        (void)fflush(stdout);
    }
    if (line != NULL) {
        secure_mem_clear(line, line_capacity);
        free(line);
    }
    if (output != NULL) {
        (void)fclose(output);
    } else if (descriptor >= 0) {
        (void)close(descriptor);
    }
    if (success == 0) {
        (void)unlink(path_template);
        free(*temporary_path);
        *temporary_path = NULL;
    }
    return success;
}

static int select_key_input(int private_key,
                            char **key_path,
                            int *temporary)
{
    char *choice;

    *key_path = NULL;
    *temporary = 0;
    printf("\n1. 选择%s输入方式：\n"
           "[1] %s文件路径\n"
           "[2] 粘贴%s内容\n",
           private_key != 0 ? "私钥" : "公钥",
           private_key != 0 ? "私钥" : "公钥",
           private_key != 0 ? "私钥" : "公钥");
    choice = read_prompt_line("请选择 [1/2]：");
    if (choice == NULL) {
        return 0;
    }
    if (strcmp(choice, "1") == 0) {
        free(choice);
        *key_path = read_prompt_line(
            private_key != 0 ? "请输入私钥文件路径（支持 .enc）："
                             : "请输入公钥文件路径：");
        if (*key_path == NULL || (*key_path)[0] == '\0') {
            fprintf(stderr, "密钥文件路径不能为空\n");
            free(*key_path);
            *key_path = NULL;
            return 0;
        }
        return 1;
    }
    if (strcmp(choice, "2") == 0) {
        free(choice);
        if (!collect_pasted_key(private_key, key_path)) {
            return 0;
        }
        *temporary = 1;
        return 1;
    }

    fprintf(stderr, "无效选择\n");
    free(choice);
    return 0;
}

static void cleanup_key_input(char *key_path, int temporary)
{
    if (temporary != 0 && key_path != NULL &&
        unlink(key_path) != 0) {
        print_system_error("Cannot remove temporary key file");
    }
    free(key_path);
}

static const char *path_basename(const char *path)
{
    const char *separator = strrchr(path, '/');

    return separator != NULL ? separator + 1 : path;
}

static char *interactive_encrypt_output_path(const char *input_path)
{
    static const char directory[] = "encrypted";
    static const char suffix[] = ".nkem";
    const char *filename = path_basename(input_path);
    size_t filename_len = strlen(filename);
    size_t output_len;
    char *output_path;

    if (filename_len == 0U) {
        fprintf(stderr, "输入路径不包含文件名\n");
        return NULL;
    }
    if (filename_len >
        SIZE_MAX - sizeof(directory) - sizeof(suffix)) {
        fprintf(stderr, "输出路径长度溢出\n");
        return NULL;
    }
    output_len = sizeof(directory) + filename_len + sizeof(suffix);
    output_path = malloc(output_len);
    if (output_path == NULL) {
        print_system_error("Cannot allocate output path");
        return NULL;
    }
    if (snprintf(output_path, output_len, "%s/%s%s",
                 directory, filename, suffix) < 0) {
        fprintf(stderr, "无法构造输出路径\n");
        free(output_path);
        return NULL;
    }
    return output_path;
}

static char *interactive_decrypt_output_path(const char *input_path)
{
    static const char directory[] = "plaintext";
    static const char suffix[] = ".nkem";
    const char *filename = path_basename(input_path);
    size_t filename_len = strlen(filename);
    const size_t suffix_len = sizeof(suffix) - 1U;
    size_t plaintext_filename_len;
    size_t allocation_size;
    char *output_path;

    if (filename_len <= suffix_len ||
        strcmp(filename + filename_len - suffix_len, suffix) != 0) {
        fprintf(stderr, "输入文件必须以 .nkem 结尾\n");
        return NULL;
    }
    plaintext_filename_len = filename_len - suffix_len;
    if (plaintext_filename_len >
        SIZE_MAX - sizeof(directory) - 1U) {
        fprintf(stderr, "输出路径长度溢出\n");
        return NULL;
    }
    allocation_size = sizeof(directory) +
                      plaintext_filename_len + 1U;
    output_path = malloc(allocation_size);
    if (output_path == NULL) {
        print_system_error("Cannot allocate output path");
        return NULL;
    }
    memcpy(output_path, directory, sizeof(directory) - 1U);
    output_path[sizeof(directory) - 1U] = '/';
    memcpy(output_path + sizeof(directory), filename,
           plaintext_filename_len);
    output_path[sizeof(directory) + plaintext_filename_len] = '\0';
    return output_path;
}

static int interactive_keygen(void)
{
    PasswordBuffer password = {0};
    int success = 0;

    if (!ensure_directory("keys", 0700) ||
        !prompt_new_private_key_password(&password)) {
        goto cleanup;
    }
    if (!hybrid_generate_keypair(PUBLIC_KEY_PATH,
                                 PROTECTED_PRIVATE_KEY_PATH,
                                 password.data, password.length)) {
        goto cleanup;
    }
    password_buffer_cleanup(&password);
    printf("已生成：\n  %s\n  %s\n",
           PUBLIC_KEY_PATH, PROTECTED_PRIVATE_KEY_PATH);
    success = 1;

cleanup:
    password_buffer_cleanup(&password);
    return success;
}

static int interactive_encrypt(void)
{
    char *key_path = NULL;
    char *input_path = NULL;
    char *output_path = NULL;
    int temporary = 0;
    int success = 0;

    if (!select_key_input(0, &key_path, &temporary)) {
        goto cleanup;
    }
    input_path = read_prompt_line("请输入待加密文件路径：");
    if (input_path == NULL || input_path[0] == '\0') {
        fprintf(stderr, "输入文件路径不能为空\n");
        goto cleanup;
    }
    if (!ensure_directory("encrypted", 0700)) {
        goto cleanup;
    }
    output_path = interactive_encrypt_output_path(input_path);
    if (output_path == NULL) {
        goto cleanup;
    }
    printf("输出文件：%s\n", output_path);
    success = run_hybrid_encrypt(input_path, output_path, key_path);

cleanup:
    cleanup_key_input(key_path, temporary);
    free(input_path);
    free(output_path);
    return success;
}

static int interactive_decrypt(void)
{
    char *key_path = NULL;
    char *input_path = NULL;
    char *output_path = NULL;
    int temporary = 0;
    int success = 0;

    if (!select_key_input(1, &key_path, &temporary)) {
        goto cleanup;
    }
    input_path = read_prompt_line("请输入 .nkem 文件路径：");
    if (input_path == NULL || input_path[0] == '\0') {
        fprintf(stderr, "输入文件路径不能为空\n");
        goto cleanup;
    }
    output_path = interactive_decrypt_output_path(input_path);
    if (output_path == NULL) {
        goto cleanup;
    }
    if (!ensure_directory("plaintext", 0700)) {
        goto cleanup;
    }
    printf("输出文件：%s\n", output_path);
    success = run_hybrid_decrypt(input_path, output_path, key_path);

cleanup:
    cleanup_key_input(key_path, temporary);
    free(input_path);
    free(output_path);
    return success;
}

static int digest_public_key_component(EVP_MD_CTX *context,
                                       EVP_PKEY *key)
{
    unsigned char length_prefix[4];
    unsigned char *der = NULL;
    unsigned char *cursor;
    int encoded_len;
    int result_len;
    int success = 0;

    encoded_len = i2d_PUBKEY(key, NULL);
    if (encoded_len <= 0 || (uint64_t)encoded_len > UINT32_MAX) {
        print_openssl_error("Cannot query public-key DER length");
        goto cleanup;
    }
    der = OPENSSL_malloc((size_t)encoded_len);
    if (der == NULL) {
        print_openssl_error("Cannot allocate public-key DER buffer");
        goto cleanup;
    }
    cursor = der;
    result_len = i2d_PUBKEY(key, &cursor);
    if (result_len != encoded_len ||
        cursor != der + (size_t)encoded_len) {
        print_openssl_error("Cannot encode public key as DER");
        goto cleanup;
    }

    length_prefix[0] = (unsigned char)((uint32_t)encoded_len >> 24);
    length_prefix[1] = (unsigned char)((uint32_t)encoded_len >> 16);
    length_prefix[2] = (unsigned char)((uint32_t)encoded_len >> 8);
    length_prefix[3] = (unsigned char)(uint32_t)encoded_len;
    if (EVP_DigestUpdate(context, length_prefix,
                         sizeof(length_prefix)) != 1 ||
        EVP_DigestUpdate(context, der, (size_t)encoded_len) != 1) {
        print_openssl_error("Cannot update public-key fingerprint");
        goto cleanup;
    }
    success = 1;

cleanup:
    OPENSSL_free(der);
    return success;
}

static int display_public_key_fingerprint(const char *key_path)
{
    static const unsigned char domain[] =
        "NekoKEM v2 hybrid public-key fingerprint";
    HybridKeys keys = {0};
    EVP_MD_CTX *context = NULL;
    unsigned char fingerprint[EVP_MAX_MD_SIZE];
    unsigned int fingerprint_len = 0U;
    size_t index;
    int success = 0;

    if (!hybrid_load_public_keys(key_path, &keys)) {
        goto cleanup;
    }
    context = EVP_MD_CTX_new();
    if (context == NULL) {
        print_openssl_error("Cannot create fingerprint context");
        goto cleanup;
    }
    if (EVP_DigestInit_ex(context, EVP_sha256(), NULL) != 1 ||
        EVP_DigestUpdate(context, domain, sizeof(domain) - 1U) != 1 ||
        !digest_public_key_component(context, keys.x448) ||
        !digest_public_key_component(context, keys.mlkem) ||
        EVP_DigestFinal_ex(context, fingerprint,
                           &fingerprint_len) != 1) {
        print_openssl_error("Cannot calculate public-key fingerprint");
        goto cleanup;
    }
    if (fingerprint_len != 32U) {
        fprintf(stderr, "Unexpected SHA-256 fingerprint length\n");
        goto cleanup;
    }

    printf("SHA-256 fingerprint:\n");
    for (index = 0U; index < fingerprint_len; ++index) {
        printf("%02X%s", fingerprint[index],
               index + 1U == fingerprint_len ? "\n" : ":");
    }
    success = 1;

cleanup:
    EVP_MD_CTX_free(context);
    hybrid_keys_cleanup(&keys);
    return success;
}

static int interactive_fingerprint(void)
{
    char *key_path = NULL;
    int temporary = 0;
    int success = 0;

    if (select_key_input(0, &key_path, &temporary)) {
        success = display_public_key_fingerprint(key_path);
    }
    cleanup_key_input(key_path, temporary);
    return success;
}

static int run_interactive_menu(void)
{
    for (;;) {
        char *choice;

        printf("====================\n"
               "      NekoKEM\n"
               "====================\n"
               "\n"
               "1. 生成密钥\n"
               "2. 加密文件\n"
               "3. 解密文件\n"
               "4. 查看公钥指纹\n"
               "5. 退出\n"
               "\n");
        choice = read_prompt_line("请选择 [1-5]：");
        if (choice == NULL) {
            return feof(stdin) != 0 ? 1 : 0;
        }
        if (strcmp(choice, "1") == 0) {
            (void)interactive_keygen();
        } else if (strcmp(choice, "2") == 0) {
            (void)interactive_encrypt();
        } else if (strcmp(choice, "3") == 0) {
            (void)interactive_decrypt();
        } else if (strcmp(choice, "4") == 0) {
            (void)interactive_fingerprint();
        } else if (strcmp(choice, "5") == 0) {
            free(choice);
            return 1;
        } else {
            fprintf(stderr, "无效选择，请输入 1 到 5。\n");
        }
        free(choice);
    }
}

int main(int argc, char **argv)
{
    if (!file_disable_buffering(stdin)) {
        return EXIT_FAILURE;
    }
    if (argc == 1) {
        return run_interactive_menu() ? EXIT_SUCCESS : EXIT_FAILURE;
    }
    if (argc == 2 && strcmp(argv[1], "keygen") == 0) {
        return run_keygen() ? EXIT_SUCCESS : EXIT_FAILURE;
    }
    if (argc == 3 && strcmp(argv[1], "keygen") == 0 &&
        strcmp(argv[2], "hybrid") == 0) {
        return run_hybrid_keygen() ? EXIT_SUCCESS : EXIT_FAILURE;
    }
    if (argc == 5 && strcmp(argv[1], "encrypt") == 0) {
        return run_encrypt(argv[2], argv[3], argv[4])
                   ? EXIT_SUCCESS
                   : EXIT_FAILURE;
    }
    if (argc == 5 && strcmp(argv[1], "decrypt") == 0) {
        return run_decrypt(argv[2], argv[3], argv[4])
                   ? EXIT_SUCCESS
                   : EXIT_FAILURE;
    }
    if (argc == 6 && strcmp(argv[1], "encrypt") == 0 &&
        strcmp(argv[2], "hybrid") == 0) {
        return run_hybrid_encrypt(argv[3], argv[4], argv[5])
                   ? EXIT_SUCCESS
                   : EXIT_FAILURE;
    }
    if (argc == 6 && strcmp(argv[1], "decrypt") == 0 &&
        strcmp(argv[2], "hybrid") == 0) {
        return run_hybrid_decrypt(argv[3], argv[4], argv[5])
                   ? EXIT_SUCCESS
                   : EXIT_FAILURE;
    }

    print_usage(argv[0]);
    return EXIT_FAILURE;
}
