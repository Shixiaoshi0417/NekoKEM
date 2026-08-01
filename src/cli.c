#include "cli.h"

#include "file.h"
#include "nekokem.h"
#include "secure_mem.h"

#include <errno.h>
#include <fcntl.h>
#include <openssl/crypto.h>
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
#define MAX_PASTED_KEY_SIZE (1024U * 1024U)
#define MAX_PROMPT_LINE_SIZE 4096U
#define MAX_PASTED_KEY_LINE_SIZE 16384U

typedef struct {
    unsigned char *data;
    size_t length;
    size_t capacity;
} PasswordBuffer;

void cli_print_usage(const char *program)
{
    fprintf(stderr,
            "Usage:\n"
            "  %s\n"
            "  %s keygen\n"
            "  %s keygen hybrid\n"
            "  %s keygen legacy-v1\n"
            "  %s encrypt <input_file> <output_file> <public.key>\n"
            "  %s decrypt <input_file> <output_file> <private.key>\n"
            "  %s encrypt hybrid <input_file> <output_file> <public.key>\n"
            "  %s decrypt hybrid <input_file> <output_file> "
            "<private.key|private.key.enc>\n",
            program, program, program, program, program, program, program,
            program);
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
    const size_t capacity = MAX_PASSWORD_SIZE + 1U;
    int character = EOF;
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

int cli_run_v1_keygen(void)
{
    fprintf(stderr,
            "WARNING: legacy-v1 writes an unencrypted plaintext private "
            "key. Use only for explicit compatibility testing.\n");
    if (!ensure_directory("keys", 0700) ||
        !nekokem_generate_v1_keypair(PUBLIC_KEY_PATH,
                                     PRIVATE_KEY_PATH)) {
        return 0;
    }
    printf("Generated %s and %s\n", PUBLIC_KEY_PATH, PRIVATE_KEY_PATH);
    return 1;
}

static int generate_hybrid_keypair(int interactive)
{
    PasswordBuffer password = {0};
    int success = 0;

    if (!ensure_directory("keys", 0700) ||
        !prompt_new_private_key_password(&password)) {
        goto cleanup;
    }
    if (!nekokem_generate_keypair(PUBLIC_KEY_PATH,
                                  PROTECTED_PRIVATE_KEY_PATH,
                                  password.data, password.length)) {
        goto cleanup;
    }
    password_buffer_cleanup(&password);
    if (interactive != 0) {
        printf("已生成：\n  %s\n  %s\n",
               PUBLIC_KEY_PATH, PROTECTED_PRIVATE_KEY_PATH);
    } else {
        printf("Generated hybrid X448 + ML-KEM-1024 keys: %s and %s\n",
               PUBLIC_KEY_PATH, PROTECTED_PRIVATE_KEY_PATH);
    }
    success = 1;

cleanup:
    password_buffer_cleanup(&password);
    return success;
}

int cli_run_hybrid_keygen(void)
{
    return generate_hybrid_keypair(0);
}

int cli_run_v1_encrypt(const char *input_path,
                       const char *output_path,
                       const char *public_key_path)
{
    if (!nekokem_encrypt_file_v1(input_path, output_path,
                                 public_key_path)) {
        return 0;
    }
    printf("Encrypted %s -> %s\n", input_path, output_path);
    return 1;
}

int cli_run_v1_decrypt(const char *input_path,
                       const char *output_path,
                       const char *private_key_path)
{
    if (!nekokem_decrypt_file_v1(input_path, output_path,
                                 private_key_path)) {
        return 0;
    }
    printf("Decrypted %s -> %s\n", input_path, output_path);
    return 1;
}

int cli_run_hybrid_encrypt(const char *input_path,
                           const char *output_path,
                           const char *public_key_path)
{
    if (!nekokem_encrypt_file(input_path, output_path,
                              public_key_path)) {
        return 0;
    }
    printf("Encrypted hybrid NKEM v3 %s -> %s\n",
           input_path, output_path);
    return 1;
}

int cli_run_hybrid_decrypt(const char *input_path,
                           const char *output_path,
                           const char *private_key_path)
{
    PasswordBuffer password = {0};
    int success = 0;

    if (nekokem_private_key_requires_password(private_key_path) != 0 &&
        !read_password_line("请输入私钥密码：", &password)) {
        goto cleanup;
    }
    if (!nekokem_decrypt_file(input_path, output_path, private_key_path,
                              password.data, password.length)) {
        goto cleanup;
    }
    password_buffer_cleanup(&password);
    printf("Decrypted NKEM v1/v2/v3 %s -> %s\n",
           input_path, output_path);
    success = 1;

cleanup:
    password_buffer_cleanup(&password);
    return success;
}

static char *read_prompt_line(const char *prompt)
{
    char *line;
    size_t length = 0U;
    int character = EOF;
    int too_long = 0;

    if (fputs(prompt, stdout) == EOF || fflush(stdout) != 0) {
        print_system_error("Cannot display prompt");
        return NULL;
    }
    line = calloc(MAX_PROMPT_LINE_SIZE + 1U, 1U);
    if (line == NULL) {
        print_system_error("Cannot allocate input buffer");
        return NULL;
    }
    for (;;) {
        character = fgetc(stdin);
        if (character == EOF || character == '\n') {
            break;
        }
        if (length < MAX_PROMPT_LINE_SIZE) {
            line[length++] = (char)character;
        } else {
            too_long = 1;
        }
    }
    if (character == EOF && ferror(stdin) != 0) {
        print_system_error("Cannot read input");
        free(line);
        return NULL;
    }
    if (character == EOF && length == 0U && too_long == 0) {
        free(line);
        return NULL;
    }
    if (too_long != 0) {
        fprintf(stderr, "Input exceeds %u bytes and was discarded\n",
                MAX_PROMPT_LINE_SIZE);
        line[0] = '\0';
        return line;
    }
    if (length > 0U && line[length - 1U] == '\r') {
        line[--length] = '\0';
    }
    return line;
}

static int read_pasted_key_line(char **line,
                                size_t *line_length,
                                size_t *line_capacity)
{
    char *buffer = NULL;
    size_t length = 0U;
    const size_t capacity = MAX_PASTED_KEY_LINE_SIZE + 2U;
    int character = EOF;
    int too_long = 0;

    if (line == NULL || line_length == NULL || line_capacity == NULL) {
        errno = EINVAL;
        return 0;
    }
    *line = NULL;
    *line_length = 0U;
    *line_capacity = 0U;
    buffer = OPENSSL_zalloc(capacity);
    if (buffer == NULL) {
        print_openssl_error("Cannot allocate pasted-key line buffer");
        return 0;
    }
    for (;;) {
        character = fgetc(stdin);
        if (character == EOF || character == '\n') {
            break;
        }
        if (length < MAX_PASTED_KEY_LINE_SIZE) {
            buffer[length++] = (char)character;
        } else {
            too_long = 1;
        }
    }
    if (character == EOF && ferror(stdin) != 0) {
        print_system_error("Cannot read pasted key");
        goto cleanup;
    }
    if (character == EOF && length == 0U && too_long == 0) {
        fprintf(stderr, "Pasted key ended before two PEM blocks\n");
        goto cleanup;
    }
    if (too_long != 0) {
        fprintf(stderr, "Pasted-key line exceeds %u bytes and was discarded\n",
                MAX_PASTED_KEY_LINE_SIZE);
        goto cleanup;
    }
    if (character == '\n') {
        buffer[length++] = '\n';
    }
    buffer[length] = '\0';
    *line = buffer;
    *line_length = length;
    *line_capacity = capacity;
    return 1;

cleanup:
    secure_free(buffer, capacity);
    return 0;
}

static int collect_pasted_key(int private_key, char **temporary_path)
{
    static const char public_end[] = "-----END PUBLIC KEY-----";
    static const char private_end[] = "-----END PRIVATE KEY-----";
    static const char filename[] = "/key.pem";
    char directory_template[] = "/tmp/nekokem-paste.XXXXXX";
    const char *end_marker = private_key != 0 ? private_end : public_end;
    struct termios original_terminal;
    struct termios hidden_terminal;
    FILE *output = NULL;
    char *line = NULL;
    char *path = NULL;
    char *directory = NULL;
    size_t line_capacity = 0U;
    size_t line_length = 0U;
    size_t total_size = 0U;
    unsigned int end_markers = 0U;
    int descriptor = -1;
    int path_result;
    int echo_disabled = 0;
    int success = 0;

    *temporary_path = NULL;
    directory = mkdtemp(directory_template);
    if (directory == NULL) {
        print_system_error("Cannot create private temporary directory");
        goto cleanup;
    }
    if (!ensure_directory(directory, 0700)) {
        goto cleanup;
    }
    if (strlen(directory) > SIZE_MAX - sizeof(filename)) {
        fprintf(stderr, "Temporary key path is too long\n");
        goto cleanup;
    }
    path = malloc(strlen(directory) + sizeof(filename));
    if (path == NULL) {
        print_system_error("Cannot allocate temporary key path");
        goto cleanup;
    }
    path_result = snprintf(path, strlen(directory) + sizeof(filename),
                           "%s%s", directory, filename);
    if (path_result < 0 ||
        (size_t)path_result >= strlen(directory) + sizeof(filename)) {
        fprintf(stderr, "Cannot construct temporary key path\n");
        goto cleanup;
    }
    descriptor = open(path, O_WRONLY | O_CREAT | O_EXCL |
                      O_NOFOLLOW | O_CLOEXEC, 0600);
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
        if (!read_pasted_key_line(&line, &line_length,
                                  &line_capacity)) {
            goto cleanup;
        }
        if (line_length > MAX_PASTED_KEY_SIZE - total_size) {
            fprintf(stderr, "Pasted key exceeds the size limit\n");
            goto cleanup;
        }
        if (!file_write_all(output, line, line_length)) {
            goto cleanup;
        }
        total_size += line_length;
        if (strstr(line, end_marker) != NULL) {
            ++end_markers;
        }
        secure_free(line, line_capacity);
        line = NULL;
        line_length = 0U;
        line_capacity = 0U;
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

    *temporary_path = strdup(path);
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
    secure_free(line, line_capacity);
    if (output != NULL) {
        (void)fclose(output);
    } else if (descriptor >= 0) {
        (void)close(descriptor);
    }
    if (success == 0) {
        if (path != NULL) {
            (void)unlink(path);
        }
        free(*temporary_path);
        *temporary_path = NULL;
    }
    free(path);
    if (success == 0 && directory != NULL) {
        (void)rmdir(directory);
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
    if (temporary != 0 && key_path != NULL) {
        char *directory = strdup(key_path);
        char *separator;

        if (unlink(key_path) != 0) {
            print_system_error("Cannot remove temporary key file");
        }
        if (directory != NULL) {
            separator = strrchr(directory, '/');
            if (separator != NULL) {
                *separator = '\0';
                if (rmdir(directory) != 0) {
                    print_system_error(
                        "Cannot remove private temporary directory");
                }
            }
            free(directory);
        }
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
    success = cli_run_hybrid_encrypt(input_path, output_path, key_path);

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
    success = cli_run_hybrid_decrypt(input_path, output_path, key_path);

cleanup:
    cleanup_key_input(key_path, temporary);
    free(input_path);
    free(output_path);
    return success;
}

static int interactive_fingerprint(void)
{
    char fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE];
    char *key_path = NULL;
    int temporary = 0;
    int success = 0;

    if (select_key_input(0, &key_path, &temporary) &&
        nekokem_public_key_fingerprint(key_path, fingerprint,
                                       sizeof(fingerprint))) {
        printf("SHA-256 fingerprint:\n%s\n", fingerprint);
        success = 1;
    }
    cleanup_key_input(key_path, temporary);
    secure_mem_clear(fingerprint, sizeof(fingerprint));
    return success;
}

int cli_run_interactive_menu(void)
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
            (void)generate_hybrid_keypair(1);
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
