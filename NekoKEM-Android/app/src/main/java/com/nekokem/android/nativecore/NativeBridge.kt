package com.nekokem.android.nativecore

fun interface NativeProgressCallback {
    /** Return false to cancel the active Core operation. */
    fun onProgress(processedBytes: Long, totalBytes: Long): Boolean
}

object NativeBridge {
    const val RESULT_CORE_ERROR = 0
    const val RESULT_SUCCESS = 1
    const val RESULT_INVALID_ARGUMENT = -1
    const val RESULT_ALLOCATION_ERROR = -2
    const val RESULT_JAVA_EXCEPTION = -3
    const val RESULT_CANCELLED = -5

    init {
        System.loadLibrary("nekokem_jni")
    }

    external fun nativeVersion(): String

    external fun nativeCoreTest(): String

    external fun nativeGenerateKeypair(
        publicKeyPath: String,
        privateKeyPath: String,
        password: ByteArray,
    ): Int

    external fun nativeGenerateKeypairWithPassword(
        publicKeyPath: String,
        privateKeyPath: String,
        password: ByteArray,
    ): Int

    external fun nativeUnlockPrivateKey(
        privateKeyPath: String,
        password: ByteArray,
    ): Int

    external fun nativeCheckPassword(
        privateKeyPath: String,
        password: ByteArray,
    ): Int

    external fun nativeHasPrivateKey(privateKeyPath: String): Boolean

    external fun nativeExportPublicKey(
        publicKeyPath: String,
        outputPath: String,
    ): Int

    external fun nativeDeletePrivateKey(privateKeyPath: String): Int

    external fun nativeGetFingerprint(publicKeyPath: String): String?

    external fun nativeEncryptFile(
        inputPath: String,
        outputPath: String,
        publicKeyPath: String,
    ): Int

    external fun nativeDecryptFile(
        inputPath: String,
        outputPath: String,
        privateKeyPath: String,
        password: ByteArray?,
    ): Int

    external fun nativeEncryptFileWithProgress(
        inputPath: String,
        outputPath: String,
        publicKeyPath: String,
        callback: NativeProgressCallback,
    ): Int

    external fun nativeDecryptFileWithProgress(
        inputPath: String,
        outputPath: String,
        privateKeyPath: String,
        password: ByteArray,
        callback: NativeProgressCallback,
    ): Int

    external fun nativePublicKeyFingerprint(publicKeyPath: String): String?
}
