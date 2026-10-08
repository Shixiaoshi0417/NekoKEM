package com.shixiaoshi0417.nekokem.keys

import android.annotation.SuppressLint
import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.shixiaoshi0417.nekokem.nativecore.NativeBridge
import com.shixiaoshi0417.nekokem.nativecore.NativeProgressCallback
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class LocalKeyState(
    val privateKeyExists: Boolean,
    val fingerprint: String?,
)

class LocalKeyManager internal constructor(
    context: Context,
    private val keyGeneration: KeyGeneration,
) {
    constructor(context: Context) : this(context, NativeKeyGeneration)

    private val keysDirectory = File(context.filesDir, KEYS_DIRECTORY_NAME)
    private val publicKey = File(keysDirectory, PUBLIC_KEY_NAME)
    private val privateKey = File(keysDirectory, PRIVATE_KEY_NAME)

    fun readState(): LocalKeyState = LocalKeyState(
        privateKeyExists = NativeBridge.nativeHasPrivateKey(
            privateKey.absolutePath,
        ),
        fingerprint = if (publicKey.isFile) {
            NativeBridge.nativeGetFingerprint(publicKey.absolutePath)
        } else {
            null
        },
    )

    /** Whether either key file exists, valid or not; generation never replaces one. */
    fun hasKeyFiles(): Boolean = publicKey.exists() || privateKey.exists()

    /**
     * Takes ownership of [password] and clears it before returning. Existing
     * key files are replaced only with [replace], after the user confirmed it.
     */
    fun generateKeypair(password: ByteArray, replace: Boolean = false): Int =
        keyMutationLock.withLock { generateKeypairLocked(password, replace) }

    private fun generateKeypairLocked(password: ByteArray, replace: Boolean): Int = try {
        if (password.isEmpty()) {
            NativeBridge.RESULT_INVALID_ARGUMENT
        } else if (!preparePrivateDirectory(keysDirectory)) {
            RESULT_STORAGE_ERROR
        } else {
            val result = keyGeneration.generate(
                publicKey.absolutePath,
                privateKey.absolutePath,
                password,
                replace,
            )
            if (result != NativeBridge.RESULT_SUCCESS) {
                result
            } else if (!setAndVerifyRegularFileMode(publicKey, PRIVATE_FILE_MODE) ||
                !keyGeneration.hasPrivateKey(privateKey.absolutePath)
            ) {
                // Core has already committed both files and removed its backups.
                // A failed post-check must not destroy the committed private key.
                RESULT_STORAGE_ERROR
            } else {
                NativeBridge.RESULT_SUCCESS
            }
        }
    } catch (_: Exception) {
        RESULT_STORAGE_ERROR
    } finally {
        password.fill(0)
    }

    /** One-shot validation; no password or unlocked key is retained. */
    fun checkPassword(password: ByteArray): Int = try {
        if (password.isEmpty()) {
            NativeBridge.RESULT_INVALID_ARGUMENT
        } else {
            NativeBridge.nativeCheckPassword(
                privateKey.absolutePath,
                password,
            )
        }
    } catch (_: Exception) {
        RESULT_STORAGE_ERROR
    } finally {
        password.fill(0)
    }

    /** One-shot unlock check; Core frees the parsed EVP keys before return. */
    fun unlockPrivateKey(password: ByteArray): Int = try {
        if (password.isEmpty()) {
            NativeBridge.RESULT_INVALID_ARGUMENT
        } else {
            NativeBridge.nativeUnlockPrivateKey(
                privateKey.absolutePath,
                password,
            )
        }
    } catch (_: Exception) {
        RESULT_STORAGE_ERROR
    } finally {
        password.fill(0)
    }

    fun encryptFile(input: File, output: File): Int = try {
        NativeBridge.nativeEncryptFile(
            input.absolutePath,
            output.absolutePath,
            publicKey.absolutePath,
        )
    } catch (_: Exception) {
        RESULT_STORAGE_ERROR
    }

    fun encryptFile(
        input: File,
        output: File,
        progress: NativeProgressCallback,
    ): Int = encryptFile(input, output, null, progress)

    fun encryptFile(
        input: File,
        output: File,
        temporaryKey: TemporaryPublicKey?,
        progress: NativeProgressCallback,
    ): Int = try {
        val currentFingerprint = temporaryKey?.let { publicKeyFingerprint(it.file) }
        if (temporaryKey != null && currentFingerprint != temporaryKey.fingerprint) {
            RESULT_FINGERPRINT_MISMATCH
        } else NativeBridge.nativeEncryptFileWithProgress(
            input.absolutePath,
            output.absolutePath,
            (temporaryKey?.file ?: publicKey).absolutePath,
            progress,
        )
    } catch (_: Exception) {
        RESULT_STORAGE_ERROR
    }

    /**
     * Encrypts once for saved-contact snapshots: one key keeps writing NKEM v3,
     * several share one NKEM v4 file. Every snapshot must still have the
     * fingerprint confirmed when it was staged; none is skipped or replaced.
     */
    fun encryptFileForRecipients(
        input: File,
        output: File,
        keys: List<TemporaryPublicKey>,
        progress: NativeProgressCallback,
    ): Int = try {
        when {
            keys.isEmpty() || keys.size > NativeBridge.MAX_RECIPIENTS ||
                keys.map { it.fingerprint }.toSet().size != keys.size -> NativeBridge.RESULT_INVALID_ARGUMENT
            keys.size == 1 -> encryptFile(input, output, keys.single(), progress)
            keys.any { publicKeyFingerprint(it.file) != it.fingerprint } -> RESULT_FINGERPRINT_MISMATCH
            else -> NativeBridge.nativeEncryptFileMultiWithProgress(
                input.absolutePath,
                output.absolutePath,
                keys.map { it.file.absolutePath }.toTypedArray(),
                progress,
            )
        }
    } catch (_: Exception) {
        RESULT_STORAGE_ERROR
    }

    /** Takes ownership of [password] and clears it before returning. */
    fun decryptFile(input: File, output: File, password: ByteArray): Int = try {
        if (password.isEmpty()) {
            NativeBridge.RESULT_INVALID_ARGUMENT
        } else {
            NativeBridge.nativeDecryptFile(
                input.absolutePath,
                output.absolutePath,
                privateKey.absolutePath,
                password,
            )
        }
    } catch (_: Exception) {
        RESULT_STORAGE_ERROR
    } finally {
        password.fill(0)
    }

    /** Takes ownership of [password] and clears it before returning. */
    fun decryptFile(
        input: File,
        output: File,
        password: ByteArray,
        progress: NativeProgressCallback,
    ): Int = decryptFile(input, output, null, password, progress)

    /**
     * Takes ownership of [password]. A temporary NKPR is revalidated by Core
     * immediately before use and must retain the fingerprint confirmed when
     * it was selected.
     */
    fun decryptFile(
        input: File,
        output: File,
        temporaryKey: TemporaryPrivateKey?,
        password: ByteArray,
        progress: NativeProgressCallback,
    ): Int = try {
        if (password.isEmpty()) {
            NativeBridge.RESULT_INVALID_ARGUMENT
        } else {
            val selectedPath = temporaryKey?.file ?: privateKey
            val currentFingerprint = if (temporaryKey == null) {
                null
            } else {
                NativeBridge.nativePrivateKeyFingerprint(
                    selectedPath.absolutePath,
                    password,
                )
            }
            when {
                temporaryKey != null && currentFingerprint == null ->
                    NativeBridge.RESULT_CORE_ERROR
                temporaryKey != null &&
                    currentFingerprint != temporaryKey.fingerprint ->
                    RESULT_FINGERPRINT_MISMATCH
                else -> NativeBridge.nativeDecryptFileWithProgress(
                    input.absolutePath,
                    output.absolutePath,
                    selectedPath.absolutePath,
                    password,
                    progress,
                )
            }
        }
    } catch (_: Exception) {
        RESULT_STORAGE_ERROR
    } finally {
        password.fill(0)
    }

    /** Core validates and serializes a SAF public-key candidate canonically. */
    fun normalizePublicKey(input: File, output: File): Int = try {
        NativeBridge.nativeExportPublicKey(
            input.absolutePath,
            output.absolutePath,
        )
    } catch (_: Exception) {
        clearAndDeleteRegularFile(output)
        RESULT_STORAGE_ERROR
    }

    fun publicKeyFingerprint(input: File): String? = try {
        NativeBridge.nativePublicKeyFingerprint(input.absolutePath)
    } catch (_: Exception) {
        null
    }

    /** Takes ownership of [password] and never retains an unlocked key. */
    fun privateKeyFingerprint(
        input: File,
        password: ByteArray,
    ): String? = try {
        if (password.isEmpty()) {
            null
        } else {
            NativeBridge.nativePrivateKeyFingerprint(
                input.absolutePath,
                password,
            )
        }
    } catch (_: Exception) {
        null
    } finally {
        password.fill(0)
    }

    /** Core validates and re-serializes the Hybrid public key. */
    fun exportPublicKey(output: File): Int = try {
        val result = NativeBridge.nativeExportPublicKey(
            publicKey.absolutePath,
            output.absolutePath,
        )
        if (result != NativeBridge.RESULT_SUCCESS) {
            result
        } else if (!setAndVerifyRegularFileMode(output, PRIVATE_FILE_MODE)) {
            clearAndDeleteRegularFile(output)
            RESULT_STORAGE_ERROR
        } else {
            NativeBridge.RESULT_SUCCESS
        }
    } catch (_: Exception) {
        clearAndDeleteRegularFile(output)
        RESULT_STORAGE_ERROR
    }

    /** Copies only the structurally valid encrypted NKPR container. */
    fun exportEncryptedPrivateKey(output: File): Int = try {
        if (!NativeBridge.nativeHasPrivateKey(privateKey.absolutePath)) {
            NativeBridge.RESULT_CORE_ERROR
        } else if (!copySensitiveRegularFile(privateKey, output)) {
            RESULT_STORAGE_ERROR
        } else {
            NativeBridge.RESULT_SUCCESS
        }
    } catch (_: Exception) {
        clearAndDeleteRegularFile(output)
        RESULT_STORAGE_ERROR
    }

    /**
     * Core parses the staged public key and writes a canonical candidate.
     * The candidate replaces the live key only after validation succeeds.
     * While a private key exists, the public key must belong to it:
     * [privateKeyPassword] unlocks the private key to prove that. Takes
     * ownership of [privateKeyPassword] and clears it before returning.
     */
    fun importPublicKey(input: File, privateKeyPassword: ByteArray? = null): Int =
        importPublicKeyDetailed(input, privateKeyPassword).code

    fun importPublicKeyDetailed(
        input: File,
        privateKeyPassword: ByteArray? = null,
    ): PublicKeyImportResult = keyMutationLock.withLock {
        try {
            withPairDirectoryLock { importPublicKeyDetailedLocked(input, privateKeyPassword) }
        } catch (_: Exception) {
            privateKeyPassword?.fill(0)
            PublicKeyImportResult(
                RESULT_STORAGE_ERROR, null, null, NativeBridge.RESULT_CORE_ERROR,
                NativeBridge.RESULT_CORE_ERROR, RESULT_PUBLIC_KEY_PERMISSION_FAILED,
                RESULT_PUBLIC_KEY_COMMIT_FAILED, null,
            )
        }
    }

    private fun importPublicKeyDetailedLocked(
        input: File,
        privateKeyPassword: ByteArray?,
    ): PublicKeyImportResult {
        var candidate: File? = null
        var stagedInput: PublicKeyFileRecord? = null
        var normalizedCandidate: PublicKeyFileRecord? = null
        var coreParseResult = NativeBridge.RESULT_CORE_ERROR
        var coreNormalizeResult = NativeBridge.RESULT_CORE_ERROR
        var permissionResult = RESULT_PUBLIC_KEY_PERMISSION_FAILED
        var commitResult = RESULT_PUBLIC_KEY_COMMIT_FAILED
        var commitErrno: Int? = null
        var result = RESULT_STORAGE_ERROR

        try {
            if (!preparePrivateDirectory(keysDirectory)) {
                result = RESULT_STORAGE_ERROR
            } else {
                stagedInput = publicKeyFileRecord(input)
                val inputFingerprint = publicKeyFingerprint(input)
                val pairing = if (inputFingerprint == null) {
                    NativeBridge.RESULT_SUCCESS
                } else {
                    checkPublicKeyPairing(inputFingerprint, privateKeyPassword)
                }
                if (stagedInput == null || inputFingerprint == null) {
                    result = RESULT_PUBLIC_KEY_PARSE_FAILED
                } else if (pairing != NativeBridge.RESULT_SUCCESS) {
                    coreParseResult = NativeBridge.RESULT_SUCCESS
                    result = pairing
                } else {
                    coreParseResult = NativeBridge.RESULT_SUCCESS
                    candidate = createPrivateCandidatePath(
                        PUBLIC_IMPORT_PREFIX,
                    )
                    coreNormalizeResult = NativeBridge.nativeExportPublicKey(
                        input.absolutePath,
                        candidate.absolutePath,
                    )
                    if (coreNormalizeResult != NativeBridge.RESULT_SUCCESS) {
                        result = RESULT_PUBLIC_KEY_NORMALIZE_FAILED
                    } else {
                        normalizedCandidate = publicKeyFileRecord(candidate)
                        val normalizedFingerprint = publicKeyFingerprint(candidate)
                        if (normalizedCandidate == null ||
                            normalizedFingerprint == null ||
                            normalizedFingerprint != inputFingerprint
                        ) {
                            result = RESULT_PUBLIC_KEY_INTEGRITY_FAILED
                        } else if (!setAndVerifyRegularFileMode(
                                candidate,
                                PRIVATE_FILE_MODE,
                            )
                        ) {
                            result = RESULT_PUBLIC_KEY_PERMISSION_FAILED
                        } else {
                            permissionResult = NativeBridge.RESULT_SUCCESS
                            val commit = commitPublicKeyCandidate(candidate)
                            candidate = null
                            commitResult = commit.code
                            commitErrno = commit.errno
                            result = commit.code
                        }
                    }
                }
            }
        } catch (error: android.system.ErrnoException) {
            commitErrno = error.errno
            result = RESULT_PUBLIC_KEY_COMMIT_FAILED
        } catch (_: Exception) {
            result = RESULT_STORAGE_ERROR
        } finally {
            privateKeyPassword?.fill(0)
            clearAndDeleteRegularFile(candidate)
        }
        return PublicKeyImportResult(
            code = result,
            stagedInput = stagedInput,
            normalizedCandidate = normalizedCandidate,
            coreParseResult = coreParseResult,
            coreNormalizeResult = coreNormalizeResult,
            permissionResult = permissionResult,
            commitResult = commitResult,
            commitErrno = commitErrno,
        )
    }

    /** Does not clear [password]; the caller owns it. */
    private fun checkPublicKeyPairing(fingerprint: String, password: ByteArray?): Int {
        if (!privateKey.exists()) {
            return NativeBridge.RESULT_SUCCESS
        }
        if (password == null || password.isEmpty()) {
            return NativeBridge.RESULT_INVALID_ARGUMENT
        }
        val pair = NativeBridge.nativePrivateKeyFingerprint(privateKey.absolutePath, password)
        return when (pair) {
            null -> RESULT_KEY_PAIR_PASSWORD_FAILED
            fingerprint -> NativeBridge.RESULT_SUCCESS
            else -> RESULT_KEY_PAIR_MISMATCH
        }
    }

    internal fun defaultPublicKeyRecord(): PublicKeyFileRecord? =
        publicKeyFileRecord(publicKey)

    internal fun deleteDefaultPublicKeyForTest(): Boolean = keyMutationLock.withLock {
        withPairDirectoryLock { if (!publicKey.exists()) true else publicKey.delete() }
    }

    internal fun publicKeyFileRecord(file: File): PublicKeyFileRecord? {
        val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
        val buffer = ByteArray(KEY_COPY_BUFFER_SIZE)
        var length = 0L

        return try {
            val status = Os.lstat(file.absolutePath)
            if (!OsConstants.S_ISREG(status.st_mode) ||
                status.st_uid != Os.getuid() ||
                status.st_nlink != 1L ||
                status.st_size < 0L ||
                status.st_size > MAX_KEY_FILE_BYTES
            ) {
                null
            } else {
                FileInputStream(file).use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) {
                            break
                        }
                        if (count > 0) {
                            length += count.toLong()
                            if (length > MAX_KEY_FILE_BYTES) {
                                return null
                            }
                            digest.update(buffer, 0, count)
                        }
                    }
                }
                if (length != status.st_size) {
                    null
                } else {
                    PublicKeyFileRecord(
                        length = length,
                        sha256 = digest.digest().joinToString("") { byte ->
                            "%02x".format(byte.toInt() and 0xFF)
                        },
                    )
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            buffer.fill(0)
        }
    }

    private data class PublicKeyCommitResult(
        val code: Int,
        val errno: Int?,
    )

    private fun commitPublicKeyCandidate(
        candidate: File,
    ): PublicKeyCommitResult {
        var backup: File? = null
        var published = false
        var preserveBackup = false
        var failureErrno: Int? = null
        var success = false

        try {
            if (publicKey.exists()) {
                if (!regularFileHasMode(publicKey, PRIVATE_FILE_MODE)) {
                    return PublicKeyCommitResult(
                        RESULT_PUBLIC_KEY_PERMISSION_FAILED,
                        null,
                    )
                }
                backup = createPrivateCandidate(PUBLIC_BACKUP_PREFIX)
                if (!copySensitiveRegularFile(publicKey, backup)) {
                    return PublicKeyCommitResult(
                        RESULT_PUBLIC_KEY_COMMIT_FAILED,
                        null,
                    )
                }
            }
            Os.rename(candidate.absolutePath, publicKey.absolutePath)
            published = true
            if (!fsyncDirectory(keysDirectory)) {
                throw IllegalStateException()
            }
            success = true
            clearAndDeleteRegularFile(backup)
            backup = null
            fsyncDirectory(keysDirectory)
            return PublicKeyCommitResult(
                NativeBridge.RESULT_SUCCESS,
                null,
            )
        } catch (error: android.system.ErrnoException) {
            failureErrno = error.errno
        } catch (_: Exception) {
            failureErrno = android.system.OsConstants.EIO
        } finally {
            if (!success && published) {
                if (backup != null && backup.exists()) {
                    try {
                        Os.rename(backup.absolutePath, publicKey.absolutePath)
                        backup = null
                    } catch (_: Exception) {
                        preserveBackup = true
                        // Preserve the old public key for manual recovery.
                    }
                } else {
                    clearAndDeleteRegularFile(publicKey)
                }
                fsyncDirectory(keysDirectory)
            }
            clearAndDeleteRegularFile(candidate)
            if (!preserveBackup) {
                clearAndDeleteRegularFile(backup)
            }
        }
        return PublicKeyCommitResult(
            RESULT_PUBLIC_KEY_COMMIT_FAILED,
            failureErrno,
        )
    }

    @SuppressLint("NewApi")
    private fun fsyncDirectory(directory: File): Boolean {
        var descriptor: java.io.FileDescriptor? = null
        return try {
            descriptor = Os.open(
                directory.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC,
                0,
            )
            Os.fsync(descriptor)
            true
        } catch (_: Exception) {
            false
        } finally {
            descriptor?.let { value ->
                try {
                    Os.close(value)
                } catch (_: Exception) {
                    // The primary result is retained.
                }
            }
        }
    }

    private fun regularFileHasMode(file: File, mode: Int): Boolean = try {
        val status = Os.lstat(file.absolutePath)
        OsConstants.S_ISREG(status.st_mode) &&
            status.st_uid == Os.getuid() &&
            status.st_nlink == 1L &&
            (status.st_mode and PERMISSION_MASK) == mode
    } catch (_: Exception) {
        false
    }

    /**
     * Takes ownership of [password]. The Core validates both the NKPR format
     * and password against a private candidate before the atomic replacement,
     * and a private key that does not belong to the current public key is
     * refused, so the two key files always form one pair.
     */
    fun importEncryptedPrivateKey(input: File, password: ByteArray): Int =
        keyMutationLock.withLock {
            try {
                withPairDirectoryLock { importEncryptedPrivateKeyLocked(input, password) }
            } catch (_: Exception) {
                password.fill(0)
                RESULT_STORAGE_ERROR
            }
        }

    private fun importEncryptedPrivateKeyLocked(input: File, password: ByteArray): Int {
        var candidate: File? = null

        return try {
            if (password.isEmpty()) {
                NativeBridge.RESULT_INVALID_ARGUMENT
            } else if (!preparePrivateDirectory(keysDirectory)) {
                RESULT_STORAGE_ERROR
            } else {
                candidate = createPrivateCandidate(PRIVATE_IMPORT_PREFIX)
                if (!copySensitiveRegularFile(input, candidate)) {
                    RESULT_STORAGE_ERROR
                } else {
                    val pair = NativeBridge.nativePrivateKeyFingerprint(
                        candidate.absolutePath,
                        password,
                    )
                    if (pair == null) {
                        // Invalid data and wrong passwords share the authentication failure.
                        NativeBridge.nativeCheckPassword(candidate.absolutePath, password)
                            .takeIf { it != NativeBridge.RESULT_SUCCESS }
                            ?: NativeBridge.RESULT_CORE_ERROR
                    } else if (publicKey.exists() && publicKeyFingerprint(publicKey) != pair) {
                        RESULT_KEY_PAIR_MISMATCH
                    } else if (commitPrivateKeyCandidate(candidate)) {
                        NativeBridge.RESULT_SUCCESS
                    } else {
                        RESULT_STORAGE_ERROR
                    }
                }
            }
        } catch (_: Exception) {
            RESULT_STORAGE_ERROR
        } finally {
            password.fill(0)
            clearAndDeleteRegularFile(candidate)
        }
    }

    /**
     * Replaces the private key with a validated candidate and reports success
     * only after the key directory is synced, so a crash or power loss after
     * the success message cannot bring back the old key or lose the new one.
     * A synced copy of the old key is kept until then; if the sync fails the
     * old key is put back, and if that also fails the copy stays in the key
     * directory for recovery.
     */
    private fun commitPrivateKeyCandidate(candidate: File): Boolean {
        var backup: File? = null
        var published = false
        var success = false
        var preserveBackup = false

        try {
            if (privateKey.exists()) {
                backup = createPrivateCandidate(PRIVATE_BACKUP_PREFIX)
                if (!copySensitiveRegularFile(privateKey, backup) ||
                    !fsyncDirectory(keysDirectory)
                ) {
                    return false
                }
            }
            Os.rename(candidate.absolutePath, privateKey.absolutePath)
            published = true
            success = fsyncDirectory(keysDirectory)
            return success
        } catch (_: Exception) {
            return false
        } finally {
            if (!success && published) {
                if (backup != null) {
                    try {
                        Os.rename(backup.absolutePath, privateKey.absolutePath)
                        backup = null
                    } catch (_: Exception) {
                        // Keep the old key's copy for manual recovery.
                        preserveBackup = true
                    }
                } else {
                    clearAndDeleteRegularFile(privateKey)
                }
                if (!fsyncDirectory(keysDirectory)) {
                    preserveBackup = backup != null
                }
            }
            if (!preserveBackup) {
                clearAndDeleteRegularFile(backup)
                if (success && backup != null) fsyncDirectory(keysDirectory)
            }
        }
    }

    // Staged operation files belong to their own operations and pages, which
    // remove them; deleting the key must not clean files another page uses.
    fun deletePrivateKey(): Int = keyMutationLock.withLock {
        try {
            NativeBridge.nativeDeletePrivateKey(privateKey.absolutePath)
        } catch (_: Exception) {
            RESULT_STORAGE_ERROR
        }
    }

    fun deletePublicKey(): Int = keyMutationLock.withLock {
        try {
            withPairDirectoryLock { deletePublicKeyLocked() }
        } catch (_: Exception) {
            RESULT_STORAGE_ERROR
        }
    }

    private fun deletePublicKeyLocked(): Int =
        if (deleteOwnedRegularFile(publicKey)) NativeBridge.RESULT_SUCCESS else RESULT_STORAGE_ERROR

    fun deleteKeypair(): Int = keyMutationLock.withLock {
        try {
            withPairDirectoryLock {
                val result = NativeBridge.nativeDeletePrivateKeyUnderPairLock(privateKey.absolutePath)
                if (result == NativeBridge.RESULT_SUCCESS) deletePublicKeyLocked() else result
            }
        } catch (_: Exception) {
            RESULT_STORAGE_ERROR
        }
    }

    private fun <T> withPairDirectoryLock(block: () -> T): T {
        check(preparePrivateDirectory(keysDirectory))
        val descriptor = NativeBridge.nativeAcquirePairLock(privateKey.absolutePath)
        check(descriptor >= 0)
        try {
            return block()
        } finally {
            NativeBridge.nativeReleasePairLock(descriptor)
        }
    }

    /** Deletes only old import candidates; recovery backups remain available. */
    fun cleanupExpiredCandidates(now: Long = System.currentTimeMillis()): Int =
        keyMutationLock.withLock {
            try {
                if (!keysDirectory.exists()) return@withLock 0
                withPairDirectoryLock {
                    keysDirectory.listFiles().orEmpty().forEach { file ->
                        if (isExpiredImportCandidate(file.name, file.lastModified(), now)) {
                            val status = Os.lstat(file.absolutePath)
                            if (OsConstants.S_ISREG(status.st_mode) && status.st_uid == Os.getuid() &&
                                status.st_nlink == 1L
                            ) clearAndDeleteRegularFile(file)
                        }
                    }
                    recoveryBackupCount()
                }
            } catch (_: Exception) {
                recoveryBackupCount()
            }
        }

    fun recoveryBackupCount(): Int = keysDirectory.listFiles().orEmpty().count { file ->
        (file.name.startsWith(PUBLIC_BACKUP_PREFIX) || file.name.startsWith(PRIVATE_BACKUP_PREFIX)) &&
            ownedRegularFile(file)
    }

    private fun ownedRegularFile(file: File): Boolean = try {
        val status = Os.lstat(file.absolutePath)
        OsConstants.S_ISREG(status.st_mode) && status.st_uid == Os.getuid() && status.st_nlink == 1L
    } catch (_: Exception) { false }

    internal fun isExpiredImportCandidate(name: String, modified: Long, now: Long): Boolean =
        name.endsWith(TEMPORARY_SUFFIX) &&
            (name.startsWith(PUBLIC_IMPORT_PREFIX) || name.startsWith(PRIVATE_IMPORT_PREFIX)) &&
            !name.startsWith(PUBLIC_BACKUP_PREFIX) && !name.startsWith(PRIVATE_BACKUP_PREFIX) &&
            modified > 0L && now >= modified && now - modified >= TEMPORARY_MAX_AGE_MILLIS

    private fun deleteOwnedRegularFile(file: File): Boolean {
        val status = try {
            Os.lstat(file.absolutePath)
        } catch (error: android.system.ErrnoException) {
            return error.errno == OsConstants.ENOENT
        }
        if (!OsConstants.S_ISREG(status.st_mode) ||
            status.st_uid != Os.getuid() ||
            status.st_nlink != 1L
        ) {
            return false
        }
        if (!overwriteRegularFile(file) || !file.delete()) {
            return false
        }
        return fsyncDirectory(keysDirectory)
    }

    private fun createPrivateCandidate(prefix: String): File {
        val candidate = File.createTempFile(prefix, TEMPORARY_SUFFIX, keysDirectory)
        Os.chmod(candidate.absolutePath, PRIVATE_FILE_MODE)
        if (!setAndVerifyRegularFileMode(candidate, PRIVATE_FILE_MODE)) {
            clearAndDeleteRegularFile(candidate)
            throw IllegalStateException()
        }
        return candidate
    }

    private fun createPrivateCandidatePath(prefix: String): File {
        val candidate = createPrivateCandidate(prefix)
        if (!candidate.delete()) {
            clearAndDeleteRegularFile(candidate)
            throw IllegalStateException()
        }
        return candidate
    }

    private fun copySensitiveRegularFile(source: File, destination: File): Boolean {
        val buffer = ByteArray(KEY_COPY_BUFFER_SIZE)
        var total = 0L

        return try {
            val sourceStatus = Os.lstat(source.absolutePath)
            if (!OsConstants.S_ISREG(sourceStatus.st_mode)) {
                return false
            }
            FileInputStream(source).use { input ->
                FileOutputStream(destination, false).use { output ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) {
                            break
                        }
                        if (count == 0) {
                            continue
                        }
                        total += count.toLong()
                        if (total > MAX_KEY_FILE_BYTES) {
                            return false
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            total > 0L && setAndVerifyRegularFileMode(
                destination,
                PRIVATE_FILE_MODE,
            )
        } catch (_: Exception) {
            false
        } finally {
            buffer.fill(0)
            if (total == 0L || total > MAX_KEY_FILE_BYTES) {
                clearAndDeleteRegularFile(destination)
            }
        }
    }

    private fun clearAndDeleteRegularFile(file: File?) {
        if (file == null || !file.exists()) {
            return
        }
        try {
            val status = Os.lstat(file.absolutePath)
            if (OsConstants.S_ISREG(status.st_mode)) {
                overwriteRegularFile(file)
            }
        } catch (_: Exception) {
            // Deletion is still attempted; no key path or contents are logged.
        } finally {
            file.delete()
        }
    }

    private fun overwriteRegularFile(file: File): Boolean = try {
        RandomAccessFile(file, READ_WRITE_MODE).use { stream ->
            val zeros = ByteArray(CACHE_CLEAR_BUFFER_SIZE)
            try {
                var remaining = stream.length()
                stream.seek(0L)
                while (remaining > 0L) {
                    val count = minOf(
                        remaining,
                        zeros.size.toLong(),
                    ).toInt()
                    stream.write(zeros, 0, count)
                    remaining -= count.toLong()
                }
                stream.fd.sync()
            } finally {
                zeros.fill(0)
            }
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun preparePrivateDirectory(directory: File): Boolean {
        if (!directory.exists() && !directory.mkdir()) {
            return false
        }
        val initialStatus = Os.lstat(directory.absolutePath)
        if (!OsConstants.S_ISDIR(initialStatus.st_mode) || initialStatus.st_uid != Os.getuid()) {
            return false
        }
        Os.chmod(directory.absolutePath, PRIVATE_DIRECTORY_MODE)
        val status = Os.lstat(directory.absolutePath)
        return OsConstants.S_ISDIR(status.st_mode) && status.st_uid == Os.getuid() &&
            (status.st_mode and PERMISSION_MASK) == PRIVATE_DIRECTORY_MODE
    }

    private fun setAndVerifyRegularFileMode(file: File, mode: Int): Boolean {
        val initialStatus = Os.lstat(file.absolutePath)
        if (!OsConstants.S_ISREG(initialStatus.st_mode) ||
            initialStatus.st_uid != Os.getuid() ||
            initialStatus.st_nlink != 1L
        ) {
            return false
        }
        Os.chmod(file.absolutePath, mode)
        val status = Os.lstat(file.absolutePath)
        return OsConstants.S_ISREG(status.st_mode) &&
            status.st_uid == Os.getuid() &&
            status.st_nlink == 1L &&
            (status.st_mode and PERMISSION_MASK) == mode
    }

    companion object {
        private val keyMutationLock = ReentrantLock()
        private const val TEMPORARY_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
        const val RESULT_STORAGE_ERROR = -4
        const val RESULT_FINGERPRINT_MISMATCH = -6
        const val RESULT_PUBLIC_KEY_COPY_FAILED = -10
        const val RESULT_PUBLIC_KEY_PARSE_FAILED = -11
        const val RESULT_PUBLIC_KEY_NORMALIZE_FAILED = -12
        const val RESULT_PUBLIC_KEY_PERMISSION_FAILED = -13
        const val RESULT_PUBLIC_KEY_COMMIT_FAILED = -14
        const val RESULT_PUBLIC_KEY_INTEGRITY_FAILED = -15
        const val RESULT_KEY_PAIR_MISMATCH = -16
        const val RESULT_KEY_PAIR_PASSWORD_FAILED = -17
        private const val CACHE_CLEAR_BUFFER_SIZE = 64 * 1024
        private const val KEY_COPY_BUFFER_SIZE = 64 * 1024
        private const val MAX_KEY_FILE_BYTES = 16L * 1024L * 1024L

        private const val KEYS_DIRECTORY_NAME = "keys"
        private const val PUBLIC_KEY_NAME = "public.key"
        private const val PRIVATE_KEY_NAME = "private.nkpr.enc"
        private const val PUBLIC_IMPORT_PREFIX = "nkem-pub-"
        private const val PUBLIC_BACKUP_PREFIX = "nkem-pub-backup-"
        private const val PRIVATE_IMPORT_PREFIX = "nkem-prv-"
        private const val PRIVATE_BACKUP_PREFIX = "nkem-prv-backup-"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val READ_WRITE_MODE = "rw"
        private const val PERMISSION_MASK = 0x1FF
        private const val PRIVATE_DIRECTORY_MODE = 0x1C0
        private const val PRIVATE_FILE_MODE = 0x180
        private const val SHA256_ALGORITHM = "SHA-256"
    }
}
