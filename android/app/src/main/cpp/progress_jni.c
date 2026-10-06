#include <jni.h>

#include "nekokem.h"

#include <openssl/crypto.h>

#include <stddef.h>
#include <stdint.h>

#define PROGRESS_JNI_PASSWORD_MAX_SIZE 1024U

enum ProgressJniResult {
    PROGRESS_JNI_CORE_ERROR = 0,
    PROGRESS_JNI_SUCCESS = 1,
    PROGRESS_JNI_INVALID_ARGUMENT = -1,
    PROGRESS_JNI_ALLOCATION_ERROR = -2,
    PROGRESS_JNI_JAVA_EXCEPTION = -3,
    PROGRESS_JNI_CANCELLED = -5
};

typedef struct ProgressJniPath {
    jstring source;
    const char *value;
} ProgressJniPath;

typedef struct ProgressJniCallback {
    JNIEnv *env;
    jobject callback;
    jmethodID method;
    int java_exception;
} ProgressJniCallback;

static int progress_path_acquire(JNIEnv *env,
                                 jstring source,
                                 ProgressJniPath *path)
{
    path->source = source;
    path->value = NULL;
    if (source == NULL) {
        return PROGRESS_JNI_INVALID_ARGUMENT;
    }
    path->value = (*env)->GetStringUTFChars(env, source, NULL);
    if (path->value == NULL) {
        return (*env)->ExceptionCheck(env) == JNI_TRUE
                   ? PROGRESS_JNI_JAVA_EXCEPTION
                   : PROGRESS_JNI_ALLOCATION_ERROR;
    }
    return PROGRESS_JNI_SUCCESS;
}

static void progress_path_release(JNIEnv *env, ProgressJniPath *path)
{
    if (path->value != NULL) {
        (*env)->ReleaseStringUTFChars(env, path->source, path->value);
    }
    path->source = NULL;
    path->value = NULL;
}

static int progress_password_copy(JNIEnv *env,
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
        return PROGRESS_JNI_INVALID_ARGUMENT;
    }
    java_len = (*env)->GetArrayLength(env, source);
    if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
        return PROGRESS_JNI_JAVA_EXCEPTION;
    }
    if (java_len <= 0) {
        return PROGRESS_JNI_INVALID_ARGUMENT;
    }
    native_len = (size_t)java_len;
    if (native_len > PROGRESS_JNI_PASSWORD_MAX_SIZE) {
        return PROGRESS_JNI_INVALID_ARGUMENT;
    }
    copy = OPENSSL_malloc(native_len);
    if (copy == NULL) {
        return PROGRESS_JNI_ALLOCATION_ERROR;
    }
    (*env)->GetByteArrayRegion(env, source, 0, java_len, (jbyte *)copy);
    if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
        OPENSSL_clear_free(copy, native_len);
        return PROGRESS_JNI_JAVA_EXCEPTION;
    }
    *password = copy;
    *password_len = native_len;
    return PROGRESS_JNI_SUCCESS;
}

static int progress_callback_prepare(JNIEnv *env,
                                     jobject callback,
                                     ProgressJniCallback *context)
{
    jclass callback_class = NULL;

    context->env = env;
    context->callback = callback;
    context->method = NULL;
    context->java_exception = 0;
    if (callback == NULL) {
        return PROGRESS_JNI_INVALID_ARGUMENT;
    }
    callback_class = (*env)->GetObjectClass(env, callback);
    if (callback_class == NULL) {
        return (*env)->ExceptionCheck(env) == JNI_TRUE
                   ? PROGRESS_JNI_JAVA_EXCEPTION
                   : PROGRESS_JNI_ALLOCATION_ERROR;
    }
    context->method = (*env)->GetMethodID(
        env, callback_class, "onProgress", "(JJ)Z");
    (*env)->DeleteLocalRef(env, callback_class);
    if (context->method == NULL) {
        return (*env)->ExceptionCheck(env) == JNI_TRUE
                   ? PROGRESS_JNI_JAVA_EXCEPTION
                   : PROGRESS_JNI_INVALID_ARGUMENT;
    }
    return PROGRESS_JNI_SUCCESS;
}

static int progress_callback_bridge(uint64_t processed_bytes,
                                    uint64_t total_bytes,
                                    void *user_data)
{
    ProgressJniCallback *context = user_data;
    jboolean should_continue;

    if (context == NULL || context->env == NULL ||
        context->callback == NULL || context->method == NULL ||
        context->java_exception != 0) {
        return 0;
    }
    should_continue = (*context->env)->CallBooleanMethod(
        context->env, context->callback, context->method,
        (jlong)processed_bytes, (jlong)total_bytes);
    if ((*context->env)->ExceptionCheck(context->env) == JNI_TRUE) {
        context->java_exception = 1;
        return 0;
    }
    return should_continue == JNI_TRUE;
}

static jint progress_result_to_jni(int core_result,
                                   const ProgressJniCallback *callback)
{
    if (callback->java_exception != 0) {
        return (jint)PROGRESS_JNI_JAVA_EXCEPTION;
    }
    if (core_result == NEKOKEM_OPERATION_SUCCESS) {
        return (jint)PROGRESS_JNI_SUCCESS;
    }
    if (core_result == NEKOKEM_OPERATION_CANCELLED) {
        return (jint)PROGRESS_JNI_CANCELLED;
    }
    return (jint)PROGRESS_JNI_CORE_ERROR;
}

JNIEXPORT jint JNICALL
Java_com_shixiaoshi0417_nekokem_nativecore_NativeBridge_nativeEncryptFileWithProgress(
    JNIEnv *env,
    jobject bridge,
    jstring input_path,
    jstring output_path,
    jstring public_key_path,
    jobject callback)
{
    ProgressJniPath input = {0};
    ProgressJniPath output = {0};
    ProgressJniPath public_path = {0};
    ProgressJniCallback progress = {0};
    int result = PROGRESS_JNI_INVALID_ARGUMENT;
    int core_result;

    (void)bridge;
    result = progress_path_acquire(env, input_path, &input);
    if (result != PROGRESS_JNI_SUCCESS) {
        goto cleanup;
    }
    result = progress_path_acquire(env, output_path, &output);
    if (result != PROGRESS_JNI_SUCCESS) {
        goto cleanup;
    }
    result = progress_path_acquire(env, public_key_path, &public_path);
    if (result != PROGRESS_JNI_SUCCESS) {
        goto cleanup;
    }
    result = progress_callback_prepare(env, callback, &progress);
    if (result != PROGRESS_JNI_SUCCESS) {
        goto cleanup;
    }
    core_result = nekokem_encrypt_file_with_progress(
        input.value, output.value, public_path.value,
        progress_callback_bridge, &progress);
    result = (int)progress_result_to_jni(core_result, &progress);

cleanup:
    progress_path_release(env, &public_path);
    progress_path_release(env, &output);
    progress_path_release(env, &input);
    return (jint)result;
}

/*
 * Encrypts once for every listed public key (an NKEM v4 container). Core
 * validates each key and rejects an empty list, more than
 * NEKOKEM_MAX_RECIPIENTS keys and duplicates; JNI only converts the paths.
 */
JNIEXPORT jint JNICALL
Java_com_shixiaoshi0417_nekokem_nativecore_NativeBridge_nativeEncryptFileMultiWithProgress(
    JNIEnv *env,
    jobject bridge,
    jstring input_path,
    jstring output_path,
    jobjectArray public_key_paths,
    jobject callback)
{
    ProgressJniPath input = {0};
    ProgressJniPath output = {0};
    ProgressJniPath keys[NEKOKEM_MAX_RECIPIENTS];
    const char *key_values[NEKOKEM_MAX_RECIPIENTS];
    ProgressJniCallback progress = {0};
    jsize count = 0;
    jsize acquired = 0;
    jsize index;
    int result = PROGRESS_JNI_INVALID_ARGUMENT;
    int core_result;

    (void)bridge;
    for (index = 0; index < (jsize)NEKOKEM_MAX_RECIPIENTS; ++index) {
        keys[index].source = NULL;
        keys[index].value = NULL;
        key_values[index] = NULL;
    }
    if (public_key_paths == NULL) {
        goto cleanup;
    }
    count = (*env)->GetArrayLength(env, public_key_paths);
    if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
        result = PROGRESS_JNI_JAVA_EXCEPTION;
        goto cleanup;
    }
    if (count <= 0 || (size_t)count > NEKOKEM_MAX_RECIPIENTS) {
        result = PROGRESS_JNI_INVALID_ARGUMENT;
        goto cleanup;
    }
    /* One local reference per recipient path, plus the method lookup. */
    if ((*env)->EnsureLocalCapacity(env, count + 8) != 0) {
        result = (*env)->ExceptionCheck(env) == JNI_TRUE
                     ? PROGRESS_JNI_JAVA_EXCEPTION
                     : PROGRESS_JNI_ALLOCATION_ERROR;
        goto cleanup;
    }
    result = progress_path_acquire(env, input_path, &input);
    if (result != PROGRESS_JNI_SUCCESS) {
        goto cleanup;
    }
    result = progress_path_acquire(env, output_path, &output);
    if (result != PROGRESS_JNI_SUCCESS) {
        goto cleanup;
    }
    for (index = 0; index < count; ++index) {
        jstring path = (jstring)(*env)->GetObjectArrayElement(
            env, public_key_paths, index);

        if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
            if (path != NULL) {
                (*env)->DeleteLocalRef(env, path);
            }
            result = PROGRESS_JNI_JAVA_EXCEPTION;
            goto cleanup;
        }
        /* Owns the local reference from here; cleanup releases both. */
        acquired = index + 1;
        result = progress_path_acquire(env, path, &keys[index]);
        if (result != PROGRESS_JNI_SUCCESS) {
            goto cleanup;
        }
        key_values[index] = keys[index].value;
    }
    result = progress_callback_prepare(env, callback, &progress);
    if (result != PROGRESS_JNI_SUCCESS) {
        goto cleanup;
    }
    core_result = nekokem_encrypt_file_multi_with_progress(
        input.value, output.value, key_values, (size_t)count,
        progress_callback_bridge, &progress);
    result = (int)progress_result_to_jni(core_result, &progress);

cleanup:
    for (index = 0; index < acquired; ++index) {
        jstring source = keys[index].source;

        progress_path_release(env, &keys[index]);
        if (source != NULL) {
            (*env)->DeleteLocalRef(env, source);
        }
    }
    progress_path_release(env, &output);
    progress_path_release(env, &input);
    return (jint)result;
}

JNIEXPORT jint JNICALL
Java_com_shixiaoshi0417_nekokem_nativecore_NativeBridge_nativeDecryptFileWithProgress(
    JNIEnv *env,
    jobject bridge,
    jstring input_path,
    jstring output_path,
    jstring private_key_path,
    jbyteArray password_array,
    jobject callback)
{
    ProgressJniPath input = {0};
    ProgressJniPath output = {0};
    ProgressJniPath private_path = {0};
    ProgressJniCallback progress = {0};
    unsigned char *password = NULL;
    size_t password_len = 0U;
    int result = PROGRESS_JNI_INVALID_ARGUMENT;
    int core_result;

    (void)bridge;
    result = progress_path_acquire(env, input_path, &input);
    if (result != PROGRESS_JNI_SUCCESS) {
        goto cleanup;
    }
    result = progress_path_acquire(env, output_path, &output);
    if (result != PROGRESS_JNI_SUCCESS) {
        goto cleanup;
    }
    result = progress_path_acquire(env, private_key_path, &private_path);
    if (result != PROGRESS_JNI_SUCCESS) {
        goto cleanup;
    }
    result = progress_password_copy(env, password_array,
                                    &password, &password_len);
    if (result != PROGRESS_JNI_SUCCESS) {
        goto cleanup;
    }
    result = progress_callback_prepare(env, callback, &progress);
    if (result != PROGRESS_JNI_SUCCESS) {
        goto cleanup;
    }
    core_result = nekokem_decrypt_file_with_progress(
        input.value, output.value, private_path.value,
        password, password_len,
        progress_callback_bridge, &progress);
    result = (int)progress_result_to_jni(core_result, &progress);

cleanup:
    OPENSSL_clear_free(password, password_len);
    progress_path_release(env, &private_path);
    progress_path_release(env, &output);
    progress_path_release(env, &input);
    return (jint)result;
}
