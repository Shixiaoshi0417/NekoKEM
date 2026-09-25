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

data class LocalKeyState(
    val privateKeyExists: Boolean,
    val fingerprint: String?,
)

class LocalKeyManager(context: Context) {
    private val keysDirectory = File(context.filesDir, KEYS_DIRECTORY_NAME)
    private val workCacheDirectory = File(
        context.cacheDir,
        WORK_CACHE_DIRECTORY_NAME,
    )
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

    /** Takes ownership of [password] and clears it before returning. */
    fun generateKeypair(password: ByteArray): Int = try {
        if (password.isEmpty()) {
            NativeBridge.RESULT_INVALID_ARGUMENT
        } else if (!preparePrivateDirectory(keysDirectory)) {
            RESULT_STORAGE_ERROR
        } else {
            val result = NativeBridge.nativeGenerateKeypairWithPassword(
                publicKey.absolutePath,
                privateKey.absolutePath,
                password,
            )
            if (result != NativeBridge.RESULT_SUCCESS) {
                result
            } else if (!setAndVerifyRegularFileMode(publicKey, PRIVATE_FILE_MODE) ||
                !NativeBridge.nativeHasPrivateKey(privateKey.absolutePath)
            ) {
                NativeBridge.nativeDeletePrivateKey(privateKey.absolutePath)
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
        NativeBridge.nativeEncryptFileWithProgress(
            input.absolutePath,
            output.absolutePath,
            (temporaryKey?.file ?: publicKey).absolutePath,
            progress,
        )
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
     */
    fun importPublicKey(input: File): Int = importPublicKeyDetailed(input).code

    fun importPublicKeyDetailed(input: File): PublicKeyImportResult {
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
                if (stagedInput == null || inputFingerprint == null) {
                    result = RESULT_PUBLIC_KEY_PARSE_FAILED
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

    internal fun defaultPublicKeyRecord(): PublicKeyFileRecord? =
        publicKeyFileRecord(publicKey)

    internal fun deleteDefaultPublicKeyForTest(): Boolean =
        if (!publicKey.exists()) true else publicKey.delete()

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
     * and password against a private candidate before the atomic replacement.
     */
    fun importEncryptedPrivateKey(input: File, password: ByteArray): Int {
        var candidate: File? = null
        var committed = false

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
                    val result = NativeBridge.nativeCheckPassword(
                        candidate.absolutePath,
                        password,
                    )
                    if (result != NativeBridge.RESULT_SUCCESS) {
                        result
                    } else {
                        Os.rename(candidate.absolutePath, privateKey.absolutePath)
                        committed = true
                        NativeBridge.RESULT_SUCCESS
                    }
                }
            }
        } catch (_: Exception) {
            RESULT_STORAGE_ERROR
        } finally {
            password.fill(0)
            if (!committed) {
                clearAndDeleteRegularFile(candidate)
            }
        }
    }

    fun deletePrivateKey(): Int = try {
        val result = NativeBridge.nativeDeletePrivateKey(
            privateKey.absolutePath,
        )
        val cacheCleared = clearWorkCache()
        when {
            result != NativeBridge.RESULT_SUCCESS -> result
            !cacheCleared -> RESULT_STORAGE_ERROR
            else -> NativeBridge.RESULT_SUCCESS
        }
    } catch (_: Exception) {
        RESULT_STORAGE_ERROR
    }

    fun deletePublicKey(): Int = try {
        if (deleteOwnedRegularFile(publicKey)) {
            NativeBridge.RESULT_SUCCESS
        } else {
            RESULT_STORAGE_ERROR
        }
    } catch (_: Exception) {
        RESULT_STORAGE_ERROR
    }

    fun deleteKeypair(): Int {
        val privateResult = deletePrivateKey()
        if (privateResult != NativeBridge.RESULT_SUCCESS) {
            return privateResult
        }
        return deletePublicKey()
    }

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

    fun clearWorkCache(): Boolean {
        if (!workCacheDirectory.exists()) {
            return true
        }
        val status = Os.lstat(workCacheDirectory.absolutePath)
        if (!OsConstants.S_ISDIR(status.st_mode)) {
            return false
        }
        val entries = workCacheDirectory.listFiles() ?: return false
        for (entry in entries) {
            val entryStatus = Os.lstat(entry.absolutePath)
            if (OsConstants.S_ISDIR(entryStatus.st_mode)) {
                return false
            }
            val cleared = !OsConstants.S_ISREG(entryStatus.st_mode) ||
                overwriteRegularFile(entry)
            val deleted = entry.delete()
            if (!cleared || !deleted) {
                return false
            }
        }
        return workCacheDirectory.delete()
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
        if (!OsConstants.S_ISDIR(initialStatus.st_mode)) {
            return false
        }
        Os.chmod(directory.absolutePath, PRIVATE_DIRECTORY_MODE)
        val status = Os.lstat(directory.absolutePath)
        return OsConstants.S_ISDIR(status.st_mode) &&
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
        const val RESULT_STORAGE_ERROR = -4
        const val RESULT_FINGERPRINT_MISMATCH = -6
        const val RESULT_PUBLIC_KEY_COPY_FAILED = -10
        const val RESULT_PUBLIC_KEY_PARSE_FAILED = -11
        const val RESULT_PUBLIC_KEY_NORMALIZE_FAILED = -12
        const val RESULT_PUBLIC_KEY_PERMISSION_FAILED = -13
        const val RESULT_PUBLIC_KEY_COMMIT_FAILED = -14
        const val RESULT_PUBLIC_KEY_INTEGRITY_FAILED = -15
        const val WORK_CACHE_DIRECTORY_NAME = "nekokem-work"
        private const val CACHE_CLEAR_BUFFER_SIZE = 64 * 1024
        private const val KEY_COPY_BUFFER_SIZE = 64 * 1024
        private const val MAX_KEY_FILE_BYTES = 16L * 1024L * 1024L

        private const val KEYS_DIRECTORY_NAME = "keys"
        private const val PUBLIC_KEY_NAME = "public.key"
        private const val PRIVATE_KEY_NAME = "private.nkpr.enc"
        private const val PUBLIC_IMPORT_PREFIX = "nkem-pub-"
        private const val PUBLIC_BACKUP_PREFIX = "nkem-pub-backup-"
        private const val PRIVATE_IMPORT_PREFIX = "nkem-prv-"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val READ_WRITE_MODE = "rw"
        private const val PERMISSION_MASK = 0x1FF
        private const val PRIVATE_DIRECTORY_MODE = 0x1C0
        private const val PRIVATE_FILE_MODE = 0x180
        private const val SHA256_ALGORITHM = "SHA-256"
    }
}
