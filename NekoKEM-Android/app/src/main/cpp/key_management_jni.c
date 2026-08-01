#include <jni.h>

#include "nekokem.h"

#include <openssl/crypto.h>

#include <stddef.h>

enum ManagedJniResult {
    MANAGED_JNI_CORE_ERROR = 0,
    MANAGED_JNI_SUCCESS = 1,
    MANAGED_JNI_INVALID_ARGUMENT = -1,
    MANAGED_JNI_ALLOCATION_ERROR = -2,
    MANAGED_JNI_JAVA_EXCEPTION = -3
};

typedef struct ManagedUtfPath {
    jstring source;
    const char *value;
} ManagedUtfPath;

static int managed_utf_path_acquire(JNIEnv *env,
                                    jstring source,
                                    ManagedUtfPath *path)
{
    path->source = source;
    path->value = NULL;
    if (source == NULL) {
        return MANAGED_JNI_INVALID_ARGUMENT;
    }
    path->value = (*env)->GetStringUTFChars(env, source, NULL);
    if (path->value == NULL) {
        return (*env)->ExceptionCheck(env) == JNI_TRUE
                   ? MANAGED_JNI_JAVA_EXCEPTION
                   : MANAGED_JNI_ALLOCATION_ERROR;
    }
    return MANAGED_JNI_SUCCESS;
}

static void managed_utf_path_release(JNIEnv *env, ManagedUtfPath *path)
{
    if (path->value != NULL) {
        (*env)->ReleaseStringUTFChars(env, path->source, path->value);
    }
    path->source = NULL;
    path->value = NULL;
}

JNIEXPORT jboolean JNICALL
Java_com_shixiaoshi0417_nekokem_nativecore_NativeBridge_nativeHasPrivateKey(
    JNIEnv *env,
    jobject bridge,
    jstring private_key_path)
{
    ManagedUtfPath private_path = {0};
    jboolean result = JNI_FALSE;

    (void)bridge;
    if (managed_utf_path_acquire(env, private_key_path, &private_path) !=
        MANAGED_JNI_SUCCESS) {
        goto cleanup;
    }
    result = nekokem_private_key_exists(private_path.value) == 1
                 ? JNI_TRUE
                 : JNI_FALSE;

cleanup:
    managed_utf_path_release(env, &private_path);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_shixiaoshi0417_nekokem_nativecore_NativeBridge_nativeExportPublicKey(
    JNIEnv *env,
    jobject bridge,
    jstring public_key_path,
    jstring output_path)
{
    ManagedUtfPath public_path = {0};
    ManagedUtfPath output = {0};
    int result = MANAGED_JNI_INVALID_ARGUMENT;

    (void)bridge;
    result = managed_utf_path_acquire(env, public_key_path,
                                      &public_path);
    if (result != MANAGED_JNI_SUCCESS) {
        goto cleanup;
    }
    result = managed_utf_path_acquire(env, output_path, &output);
    if (result != MANAGED_JNI_SUCCESS) {
        goto cleanup;
    }
    result = nekokem_export_public_key(public_path.value,
                                       output.value) == 1
                 ? MANAGED_JNI_SUCCESS
                 : MANAGED_JNI_CORE_ERROR;

cleanup:
    managed_utf_path_release(env, &output);
    managed_utf_path_release(env, &public_path);
    return (jint)result;
}

JNIEXPORT jint JNICALL
Java_com_shixiaoshi0417_nekokem_nativecore_NativeBridge_nativeDeletePrivateKey(
    JNIEnv *env,
    jobject bridge,
    jstring private_key_path)
{
    ManagedUtfPath private_path = {0};
    int result = MANAGED_JNI_INVALID_ARGUMENT;

    (void)bridge;
    result = managed_utf_path_acquire(env, private_key_path,
                                      &private_path);
    if (result != MANAGED_JNI_SUCCESS) {
        goto cleanup;
    }
    result = nekokem_delete_private_key(private_path.value) == 1
                 ? MANAGED_JNI_SUCCESS
                 : MANAGED_JNI_CORE_ERROR;

cleanup:
    managed_utf_path_release(env, &private_path);
    return (jint)result;
}

JNIEXPORT jstring JNICALL
Java_com_shixiaoshi0417_nekokem_nativecore_NativeBridge_nativeGetFingerprint(
    JNIEnv *env,
    jobject bridge,
    jstring public_key_path)
{
    ManagedUtfPath public_path = {0};
    char fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE] = {0};
    jstring result = NULL;

    (void)bridge;
    if (managed_utf_path_acquire(env, public_key_path, &public_path) !=
        MANAGED_JNI_SUCCESS) {
        goto cleanup;
    }
    if (nekokem_public_key_fingerprint(
            public_path.value, fingerprint, sizeof(fingerprint)) != 1) {
        goto cleanup;
    }
    result = (*env)->NewStringUTF(env, fingerprint);

cleanup:
    OPENSSL_cleanse(fingerprint, sizeof(fingerprint));
    managed_utf_path_release(env, &public_path);
    return result;
}
