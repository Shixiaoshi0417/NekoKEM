#include <jni.h>

#include "nekokem.h"
#include "nekokem_internal.h"

#include <openssl/crypto.h>

#include <stddef.h>

#define PASSWORD_JNI_MAX_SIZE 1024U

enum PasswordJniResult {
    PASSWORD_JNI_CORE_ERROR = 0,
    PASSWORD_JNI_SUCCESS = 1,
    PASSWORD_JNI_INVALID_ARGUMENT = -1,
    PASSWORD_JNI_ALLOCATION_ERROR = -2,
    PASSWORD_JNI_JAVA_EXCEPTION = -3
};

typedef struct PasswordJniPath {
    jstring source;
    const char *value;
} PasswordJniPath;

static int password_path_acquire(JNIEnv *env,
                                 jstring source,
                                 PasswordJniPath *path)
{
    path->source = source;
    path->value = NULL;
    if (source == NULL) {
        return PASSWORD_JNI_INVALID_ARGUMENT;
    }
    path->value = (*env)->GetStringUTFChars(env, source, NULL);
    if (path->value == NULL) {
        return (*env)->ExceptionCheck(env) == JNI_TRUE
                   ? PASSWORD_JNI_JAVA_EXCEPTION
                   : PASSWORD_JNI_ALLOCATION_ERROR;
    }
    return PASSWORD_JNI_SUCCESS;
}

static void password_path_release(JNIEnv *env, PasswordJniPath *path)
{
    if (path->value != NULL) {
        (*env)->ReleaseStringUTFChars(env, path->source, path->value);
    }
    path->source = NULL;
    path->value = NULL;
}

static int password_bytes_copy(JNIEnv *env,
                               jbyteArray source,
                               unsigned char **password,
                               size_t *password_len)
{
    unsigned char *copy = NULL;
    jsize java_len;
    size_t native_len;

    *password = NULL;
    *password_len = 0U;
    if (source == NULL) {
        return PASSWORD_JNI_INVALID_ARGUMENT;
    }
    java_len = (*env)->GetArrayLength(env, source);
    if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
        return PASSWORD_JNI_JAVA_EXCEPTION;
    }
    if (java_len <= 0) {
        return PASSWORD_JNI_INVALID_ARGUMENT;
    }
    native_len = (size_t)java_len;
    if (native_len > PASSWORD_JNI_MAX_SIZE) {
        return PASSWORD_JNI_INVALID_ARGUMENT;
    }
    copy = OPENSSL_malloc(native_len);
    if (copy == NULL) {
        return PASSWORD_JNI_ALLOCATION_ERROR;
    }
    (*env)->GetByteArrayRegion(env, source, 0, java_len, (jbyte *)copy);
    if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
        OPENSSL_clear_free(copy, native_len);
        return PASSWORD_JNI_JAVA_EXCEPTION;
    }
    *password = copy;
    *password_len = native_len;
    return PASSWORD_JNI_SUCCESS;
}

JNIEXPORT jint JNICALL
Java_com_shixiaoshi0417_nekokem_nativecore_NativeBridge_nativeGenerateKeypairWithPassword(
    JNIEnv *env,
    jobject bridge,
    jstring public_key_path,
    jstring private_key_path,
    jbyteArray password_array)
{
    PasswordJniPath public_path = {0};
    PasswordJniPath private_path = {0};
    unsigned char *password = NULL;
    size_t password_len = 0U;
    int result = PASSWORD_JNI_INVALID_ARGUMENT;

    (void)bridge;
    result = password_path_acquire(env, public_key_path, &public_path);
    if (result != PASSWORD_JNI_SUCCESS) {
        goto cleanup;
    }
    result = password_path_acquire(env, private_key_path, &private_path);
    if (result != PASSWORD_JNI_SUCCESS) {
        goto cleanup;
    }
    result = password_bytes_copy(env, password_array,
                                 &password, &password_len);
    if (result != PASSWORD_JNI_SUCCESS) {
        goto cleanup;
    }
    result = nekokem_generate_keypair(public_path.value,
                                      private_path.value,
                                      password,
                                      password_len) == 1
                 ? PASSWORD_JNI_SUCCESS
                 : PASSWORD_JNI_CORE_ERROR;

cleanup:
    OPENSSL_clear_free(password, password_len);
    password_path_release(env, &private_path);
    password_path_release(env, &public_path);
    return (jint)result;
}

static jint check_private_key_password(JNIEnv *env,
                                       jstring private_key_path,
                                       jbyteArray password_array)
{
    PasswordJniPath private_path = {0};
    unsigned char *password = NULL;
    size_t password_len = 0U;
    int result = PASSWORD_JNI_INVALID_ARGUMENT;

    result = password_path_acquire(env, private_key_path, &private_path);
    if (result != PASSWORD_JNI_SUCCESS) {
        goto cleanup;
    }
    result = password_bytes_copy(env, password_array,
                                 &password, &password_len);
    if (result != PASSWORD_JNI_SUCCESS) {
        goto cleanup;
    }
    result = nekokem_check_private_key_password(
                 private_path.value, password, password_len) == 1
                 ? PASSWORD_JNI_SUCCESS
                 : PASSWORD_JNI_CORE_ERROR;

cleanup:
    OPENSSL_clear_free(password, password_len);
    password_path_release(env, &private_path);
    return (jint)result;
}

JNIEXPORT jint JNICALL
Java_com_shixiaoshi0417_nekokem_nativecore_NativeBridge_nativeUnlockPrivateKey(
    JNIEnv *env,
    jobject bridge,
    jstring private_key_path,
    jbyteArray password_array)
{
    (void)bridge;
    return check_private_key_password(env, private_key_path,
                                      password_array);
}

JNIEXPORT jint JNICALL
Java_com_shixiaoshi0417_nekokem_nativecore_NativeBridge_nativeCheckPassword(
    JNIEnv *env,
    jobject bridge,
    jstring private_key_path,
    jbyteArray password_array)
{
    (void)bridge;
    return check_private_key_password(env, private_key_path,
                                      password_array);
}

JNIEXPORT jstring JNICALL
Java_com_shixiaoshi0417_nekokem_nativecore_NativeBridge_nativePrivateKeyFingerprint(
    JNIEnv *env,
    jobject bridge,
    jstring private_key_path,
    jbyteArray password_array)
{
    PasswordJniPath private_path = {0};
    unsigned char *password = NULL;
    size_t password_len = 0U;
    char fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE] = {0};
    jstring result = NULL;
    int status;

    (void)bridge;
    status = password_path_acquire(env, private_key_path,
                                   &private_path);
    if (status != PASSWORD_JNI_SUCCESS) {
        goto cleanup;
    }
    status = password_bytes_copy(env, password_array,
                                 &password, &password_len);
    if (status != PASSWORD_JNI_SUCCESS) {
        goto cleanup;
    }
    if (!nekokem_internal_private_key_fingerprint(
            private_path.value, password, password_len,
            fingerprint, sizeof(fingerprint))) {
        goto cleanup;
    }
    result = (*env)->NewStringUTF(env, fingerprint);

cleanup:
    OPENSSL_cleanse(fingerprint, sizeof(fingerprint));
    OPENSSL_clear_free(password, password_len);
    password_path_release(env, &private_path);
    return result;
}
