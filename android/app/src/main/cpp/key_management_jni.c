#include <jni.h>

#include "nekokem.h"
#include "file.h"

#include <openssl/crypto.h>

#include <stddef.h>

enum ManagedJniResult {
    MANAGED_JNI_CORE_ERROR = 0,
    MANAGED_JNI_SUCCESS = 1,
    /* The key is deleted, but Core kept a residue that may hold a key. */
    MANAGED_JNI_RESIDUE_RETAINED = 2,
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

/* Keeps a partial deletion distinct from both success and failure. */
static int deletion_result(int core_result)
{
    if (core_result == FILE_KEY_DELETED) return MANAGED_JNI_SUCCESS;
    if (core_result == FILE_KEY_DELETED_RESIDUE_RETAINED) return MANAGED_JNI_RESIDUE_RETAINED;
    return MANAGED_JNI_CORE_ERROR;
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
    result = deletion_result(nekokem_delete_private_key(private_path.value));

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

JNIEXPORT jint JNICALL
Java_com_shixiaoshi0417_nekokem_nativecore_NativeBridge_nativeAcquirePairLock(
    JNIEnv *env, jobject bridge, jstring key_path)
{
    ManagedUtfPath path = {0};
    int descriptor = -1;
    (void)bridge;
    if (managed_utf_path_acquire(env, key_path, &path) == MANAGED_JNI_SUCCESS) {
        descriptor = file_pair_lock_acquire(path.value);
    }
    managed_utf_path_release(env, &path);
    return (jint)descriptor;
}

JNIEXPORT void JNICALL
Java_com_shixiaoshi0417_nekokem_nativecore_NativeBridge_nativeReleasePairLock(
    JNIEnv *env, jobject bridge, jint descriptor)
{
    (void)env;
    (void)bridge;
    if (descriptor >= 0) file_pair_lock_release((int)descriptor);
}

JNIEXPORT jint JNICALL
Java_com_shixiaoshi0417_nekokem_nativecore_NativeBridge_nativeDeletePrivateKeyUnderPairLock(
    JNIEnv *env, jobject bridge, jstring key_path)
{
    ManagedUtfPath path = {0};
    int result = MANAGED_JNI_INVALID_ARGUMENT;
    (void)bridge;
    if (managed_utf_path_acquire(env, key_path, &path) == MANAGED_JNI_SUCCESS) {
        result = deletion_result(file_delete_private_key_under_lock(path.value));
    }
    managed_utf_path_release(env, &path);
    return (jint)result;
}

/* 1 for a valid four-component identity public key that verifies signatures. */
JNIEXPORT jboolean JNICALL
Java_com_shixiaoshi0417_nekokem_nativecore_NativeBridge_nativePublicKeyCanVerify(
    JNIEnv *env,
    jobject bridge,
    jstring public_key_path)
{
    ManagedUtfPath public_path = {0};
    jboolean result = JNI_FALSE;

    (void)bridge;
    if (managed_utf_path_acquire(env, public_key_path, &public_path) ==
            MANAGED_JNI_SUCCESS &&
        nekokem_public_key_can_verify(public_path.value) == 1) {
        result = JNI_TRUE;
    }
    managed_utf_path_release(env, &public_path);
    return result;
}

/* The X448 + ML-KEM-1024 fingerprint NekoKEM 4.x shows, for either key kind. */
JNIEXPORT jstring JNICALL
Java_com_shixiaoshi0417_nekokem_nativecore_NativeBridge_nativePublicKeyLegacyFingerprint(
    JNIEnv *env,
    jobject bridge,
    jstring public_key_path)
{
    ManagedUtfPath public_path = {0};
    char fingerprint[NEKOKEM_FINGERPRINT_STRING_SIZE] = {0};
    jstring result = NULL;

    (void)bridge;
    if (managed_utf_path_acquire(env, public_key_path, &public_path) ==
            MANAGED_JNI_SUCCESS &&
        nekokem_public_key_legacy_fingerprint(
            public_path.value, fingerprint, sizeof(fingerprint)) == 1) {
        result = (*env)->NewStringUTF(env, fingerprint);
    }
    OPENSSL_cleanse(fingerprint, sizeof(fingerprint));
    managed_utf_path_release(env, &public_path);
    return result;
}

/* Writes only the two encryption components, readable by NekoKEM <= 4.4. */
JNIEXPORT jint JNICALL
Java_com_shixiaoshi0417_nekokem_nativecore_NativeBridge_nativeExportLegacyPublicKey(
    JNIEnv *env,
    jobject bridge,
    jstring public_key_path,
    jstring output_path)
{
    ManagedUtfPath public_path = {0};
    ManagedUtfPath output = {0};
    int result;

    (void)bridge;
    result = managed_utf_path_acquire(env, public_key_path, &public_path);
    if (result == MANAGED_JNI_SUCCESS) {
        result = managed_utf_path_acquire(env, output_path, &output);
    }
    if (result == MANAGED_JNI_SUCCESS) {
        result = nekokem_export_legacy_public_key(public_path.value,
                                                  output.value) == 1
                     ? MANAGED_JNI_SUCCESS
                     : MANAGED_JNI_CORE_ERROR;
    }
    managed_utf_path_release(env, &output);
    managed_utf_path_release(env, &public_path);
    return (jint)result;
}
