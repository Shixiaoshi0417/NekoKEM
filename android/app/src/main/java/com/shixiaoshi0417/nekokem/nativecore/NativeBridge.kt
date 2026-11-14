package com.shixiaoshi0417.nekokem.nativecore

fun interface NativeProgressCallback {
    /** Return false to cancel the active Core operation. */
    fun onProgress(processedBytes: Long, totalBytes: Long): Boolean
}

object NativeBridge {
    const val RESULT_CORE_ERROR = 0
    const val RESULT_SUCCESS = 1

    /**
     * A deletion removed the key, but Core kept a residue that may hold a
     * private key: a fresh staging file may still belong to another operation.
     */
    const val RESULT_RESIDUE_RETAINED = 2
    const val RESULT_INVALID_ARGUMENT = -1
    const val RESULT_ALLOCATION_ERROR = -2
    const val RESULT_JAVA_EXCEPTION = -3
    const val RESULT_CANCELLED = -5

    /** Core's signature policy failures; no output was committed. */
    const val RESULT_SIGNATURE_INVALID = -10
    const val RESULT_SIGNATURE_REQUIRED = -11
    const val RESULT_SIGNER_NOT_TRUSTED = -12

    /** Core's NEKOKEM_SIGNATURE_* values, as reported by verified decryption. */
    const val SIGNATURE_UNSIGNED = 0
    const val SIGNATURE_TRUSTED = 1
    const val SIGNATURE_VALID_UNTRUSTED = 2

    /** Core's NEKOKEM_MAX_RECIPIENTS: most recipients in one NKEM v4/v5 file. */
    const val MAX_RECIPIENTS = 64

    /**
     * Core's NEKOKEM_MAX_TRUSTED_SIGNERS: most trusted signers (this device
     * plus saved contacts) in one verified decryption. Separate from
     * [MAX_RECIPIENTS], and above the 500 saved contacts plus this device.
     */
    const val MAX_TRUSTED_SIGNERS = 1024

    init {
        System.loadLibrary("nekokem_jni")
    }

    external fun nativeVersion(): String

    external fun nativeCoreTest(): String

    /** Never overwrites: fails if either key file already exists. */
    external fun nativeGenerateKeypairWithPassword(
        publicKeyPath: String,
        privateKeyPath: String,
        password: ByteArray,
    ): Int

    /** Rotation after explicit confirmation: replaces both key files. */
    external fun nativeReplaceKeypairWithPassword(
        publicKeyPath: String,
        privateKeyPath: String,
        password: ByteArray,
    ): Int

    external fun nativeCheckPassword(
        privateKeyPath: String,
        password: ByteArray,
    ): Int

    external fun nativePrivateKeyFingerprint(
        privateKeyPath: String,
        password: ByteArray,
    ): String?

    external fun nativeHasPrivateKey(privateKeyPath: String): Boolean

    external fun nativeExportPublicKey(
        publicKeyPath: String,
        outputPath: String,
    ): Int

    /** A directory flock shared with Core commits; callers must release it. */
    external fun nativeAcquirePairLock(keyPath: String): Int

    external fun nativeReleasePairLock(descriptor: Int)

    /** Caller must hold nativeAcquirePairLock for this key's directory. */
    external fun nativeDeletePrivateKeyUnderPairLock(privateKeyPath: String): Int

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

    /**
     * Encrypts once for every key into one NKEM v4 file that each matching
     * private key decrypts. Core validates every key and rejects an empty
     * list, more than [MAX_RECIPIENTS] keys and duplicates.
     */
    external fun nativeEncryptFileMultiWithProgress(
        inputPath: String,
        outputPath: String,
        publicKeyPaths: Array<String>,
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

    /**
     * Adds Ed448 and ML-DSA-87 keys to a NekoKEM 4.x key pair. Its encryption
     * keys, password and fingerprint for old files stay the same.
     */
    external fun nativeUpgradeToIdentityKeys(
        publicKeyPath: String,
        privateKeyPath: String,
        password: ByteArray,
    ): Int

    /** True for a valid four-component identity key, which verifies signatures. */
    external fun nativePublicKeyCanVerify(publicKeyPath: String): Boolean

    /** The X448 + ML-KEM-1024 fingerprint that NekoKEM 4.x shows. */
    external fun nativePublicKeyLegacyFingerprint(publicKeyPath: String): String?

    /** Writes only the two encryption components, readable by NekoKEM 4.x. */
    external fun nativeExportLegacyPublicKey(
        publicKeyPath: String,
        outputPath: String,
    ): Int

    /**
     * Writes one NKEM v5 file for every key, signed with the identity private
     * key at [signKeyPath]. Never falls back to an unsigned file.
     */
    external fun nativeEncryptFileSignedWithProgress(
        inputPath: String,
        outputPath: String,
        publicKeyPaths: Array<String>,
        signKeyPath: String,
        password: ByteArray,
        callback: NativeProgressCallback,
    ): Int

    /**
     * Decrypts NKEM v3, v4 or v5 while Core enforces the signature policy.
     * [report] receives {format, status, trusted index}; [signer] the v5
     * signer's fingerprint. Returns a RESULT_* or RESULT_SIGNATURE_* code.
     */
    external fun nativeDecryptFileVerifiedWithProgress(
        inputPath: String,
        outputPath: String,
        privateKeyPath: String,
        password: ByteArray,
        trustedPaths: Array<String>,
        requireSignature: Boolean,
        signerOutputPath: String?,
        report: IntArray,
        signer: Array<String?>,
        callback: NativeProgressCallback,
    ): Int
}
