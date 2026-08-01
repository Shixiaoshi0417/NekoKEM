#include <jni.h>

#include "nekokem.h"

#include <openssl/crypto.h>

#include <stddef.h>

#define JNI_PASSWORD_MAX_SIZE (1024U * 1024U)

enum JniResult {
    JNI_RESULT_CORE_ERROR = 0,
    JNI_RESULT_SUCCESS = 1,
    JNI_RESULT_INVALID_ARGUMENT = -1,
    JNI_RESULT_ALLOCATION_ERROR = -2,
    JNI_RESULT_JAVA_EXCEPTION = -3
};

typedef struct UtfPath {
    jstring source;
    const char *value;
} UtfPath;

static int utf_path_acquire(JNIEnv *env, jstring source, UtfPath *path)
{
    path->source = source;
    path->value = NULL;
    if (source == NULL) {
        return JNI_RESULT_INVALID_ARGUMENT;
    }
    path->value = (*env)->GetStringUTFChars(env, source, NULL);
    if (path->value == NULL) {
        return (*env)->ExceptionCheck(env) == JNI_TRUE
                   ? JNI_RESULT_JAVA_EXCEPTION
                   : JNI_RESULT_ALLOCATION_ERROR;
    }
    return JNI_RESULT_SUCCESS;
}

static void utf_path_release(JNIEnv *env, UtfPath *path)
{
    if (path->value != NULL) {
        (*env)->ReleaseStringUTFChars(env, path->source, path->value);
    }
    path->source = NULL;
    path->value = NULL;
}

static int password_copy(JNIEnv *env,
                         jbyteArray source,
                         int required,
                         unsigned char **password,
                         size_t *password_len)
{
    unsigned char *copy = NULL;
    jsize java_len;
    size_t native_len;

    *password = NULL;
    *password_len = 0U;
    if (source == NULL) {
        return required != 0 ? JNI_RESULT_INVALID_ARGUMENT
                             : JNI_RESULT_SUCCESS;
    }
    java_len = (*env)->GetArrayLength(env, source);
    if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
        return JNI_RESULT_JAVA_EXCEPTION;
    }
    if (java_len <= 0) {
        return required != 0 ? JNI_RESULT_INVALID_ARGUMENT
                             : JNI_RESULT_SUCCESS;
    }
    native_len = (size_t)java_len;
    if (native_len > JNI_PASSWORD_MAX_SIZE) {
        return JNI_RESULT_INVALID_ARGUMENT;
    }
    copy = OPENSSL_malloc(native_len);
    if (copy == NULL) {
        return JNI_RESULT_ALLOCATION_ERROR;
    }
    (*env)->GetByteArrayRegion(env, source, 0, java_len, (jbyte *)copy);
    if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
        OPENSSL_clear_free(copy, native_len);
        return JNI_RESULT_JAVA_EXCEPTION;
    }
    *password = copy;
    *password_len = native_len;
    return JNI_RESULT_SUCCESS;
}

JNIEXPORT jstring JNICALL
Java_com_nekokem_android_nativecore_NativeBridge_nativeVersion(
    JNIEnv *env,
    jobject bridge)
{
    const char *version = OpenSSL_version(OPENSSL_VERSION);

    (void)bridge;
    return (*env)->NewStringUTF(env, version);
}

JNIEXPORT jstring JNICALL
Java_com_nekokem_android_nativecore_NativeBridge_nativeCoreTest(
    JNIEnv *env,
    jobject bridge)
{
    static const char protected_key_path[] = "private.key.enc";
    static const char success[] = "Success: NekoKEM Core API connected";
    static const char failure[] = "Error: NekoKEM Core API test failed";
    const char *result;

    (void)bridge;
    result = nekokem_private_key_requires_password(protected_key_path) == 1
                 ? success
                 : failure;
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jint JNICALL
Java_com_nekokem_android_nativecore_NativeBridge_nativeGenerateKeypair(
    JNIEnv *env,
    jobject bridge,
    jstring public_key_path,
    jstring private_key_path,
    jbyteArray password_array)
{
    UtfPath public_path = {0};
    UtfPath private_path = {0};
    unsigned char *password = NULL;
    size_t password_len = 0U;
    int result = JNI_RESULT_INVALID_ARGUMENT;

    (void)bridge;
    result = utf_path_acquire(env, public_key_path, &public_path);
    if (result != JNI_RESULT_SUCCESS) {
        goto cleanup;
    }
    result = utf_path_acquire(env, private_key_path, &private_path);
    if (result != JNI_RESULT_SUCCESS) {
        goto cleanup;
    }
    result = password_copy(env, password_array, 1,
                           &password, &password_len);
    if (result != JNI_RESULT_SUCCESS) {
        goto cleanup;
    }
    result = nekokem_generate_keypair(public_path.value,
                                      private_path.value,
                                      password,
                                      password_len) == 1
                 ? JNI_RESULT_SUCCESS
                 : JNI_RESULT_CORE_ERROR;

cleanup:
    OPENSSL_clear_free(password, password_len);
    utf_path_release(env, &private_path);
    utf_path_release(env, &public_path);
    return (jint)result;
}

JNIEXPORT jint JNICALL
Java_com_nekokem_android_nativecore_NativeBridge_nativeEncryptFile(
    JNIEnv *env,
    jobject bridge,
    jstring input_path,
    jstring output_path,
    jstring public_key_path)
{
    UtfPath input = {0};
    UtfPath output = {0};
    UtfPath public_path = {0};
    int result = JNI_RESULT_INVALID_ARGUMENT;

    (void)bridge;
    result = utf_path_acquire(env, input_path, &input);
    if (result != JNI_RESULT_SUCCESS) {
        goto cleanup;
    }
    result = utf_path_acquire(env, output_path, &output);
    if (result != JNI_RESULT_SUCCESS) {
        goto cleanup;
    }
    result = utf_path_acquire(env, public_key_path, &public_path);
    if (result != JNI_RESULT_SUCCESS) {
        goto cleanup;
    }
    result = nekokem_encrypt_file(input.value, output.value,
                                  public_path.value) == 1
                 ? JNI_RESULT_SUCCESS
                 : JNI_RESULT_CORE_ERROR;

cleanup:
    utf_path_release(env, &public_path);
    utf_path_release(env, &output);
    utf_path_release(env, &input);
    return (jint)result;
}

JNIEXPORT jint JNICALL
Java_com_nekokem_android_nativecore_NativeBridge_nativeDecryptFile(
    JNIEnv *env,
    jobject bridge,
    jstring input_path,
    jstring output_path,
    jstring private_key_path,
    jbyteArray password_array)
{
    UtfPath input = {0};
    UtfPath output = {0};
    UtfPath private_path = {0};
    unsigned char *password = NULL;
    size_t password_len = 0U;
    int result = JNI_RESULT_INVALID_ARGUMENT;

    (void)bridge;
    result = utf_path_acquire(env, input_path, &input);
    if (result != JNI_RESULT_SUCCESS) {
        goto cleanup;
    }
    result = utf_path_acquire(env, output_path, &output);
    if (result != JNI_RESULT_SUCCESS) {
        goto cleanup;
    }
    result = utf_path_acquire(env, private_key_path, &private_path);
    if (result != JNI_RESULT_SUCCESS) {
        goto cleanup;
    }
    result = password_copy(env, password_array, 0,
                           &password, &password_len);
    if (result != JNI_RESULT_SUCCESS) {
        goto cleanup;
    }
    result = nekokem_decrypt_file(input.value, output.value,
                                  private_path.value,
                                  password, password_len) == 1
                 ? JNI_RESULT_SUCCESS
                 : JNI_RESULT_CORE_ERROR;

cleanup:
    OPENSSL_clear_free(password, password_len);
    utf_path_release(env, &private_path);
    utf_path_release(env, &output);
    utf_path_release(env, &input);
    return (jint)result;
}

JNIEXPORT jstring JNICALL
Java_com_nekokem_android_nativecore_NativeBridge_nativePublicKeyFingerprint(
    JNIEnv *env,
    jobject bridge,
    jstring public_key_path)
{
    UtfPath public_path = {0};
    char fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE] = {0};
    jstring result = NULL;

    (void)bridge;
    if (utf_path_acquire(env, public_key_path, &public_path) !=
        JNI_RESULT_SUCCESS) {
        goto cleanup;
    }
    if (nekokem_public_key_fingerprint(
            public_path.value, fingerprint, sizeof(fingerprint)) != 1) {
        goto cleanup;
    }
    result = (*env)->NewStringUTF(env, fingerprint);

cleanup:
    OPENSSL_cleanse(fingerprint, sizeof(fingerprint));
    utf_path_release(env, &public_path);
    return result;
}
