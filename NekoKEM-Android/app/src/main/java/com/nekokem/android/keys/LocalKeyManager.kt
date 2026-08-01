package com.nekokem.android.keys

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.nekokem.android.nativecore.NativeBridge
import com.nekokem.android.nativecore.NativeProgressCallback
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

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
    ): Int = try {
        NativeBridge.nativeEncryptFileWithProgress(
            input.absolutePath,
            output.absolutePath,
            publicKey.absolutePath,
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
    ): Int = try {
        if (password.isEmpty()) {
            NativeBridge.RESULT_INVALID_ARGUMENT
        } else {
            NativeBridge.nativeDecryptFileWithProgress(
                input.absolutePath,
                output.absolutePath,
                privateKey.absolutePath,
                password,
                progress,
            )
        }
    } catch (_: Exception) {
        RESULT_STORAGE_ERROR
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
    fun importPublicKey(input: File): Int {
        var candidate: File? = null
        var committed = false

        return try {
            if (!preparePrivateDirectory(keysDirectory)) {
                RESULT_STORAGE_ERROR
            } else {
                candidate = createPrivateCandidate(PUBLIC_IMPORT_PREFIX)
                val result = NativeBridge.nativeExportPublicKey(
                    input.absolutePath,
                    candidate.absolutePath,
                )
                if (result != NativeBridge.RESULT_SUCCESS) {
                    result
                } else if (!setAndVerifyRegularFileMode(
                        candidate,
                        PRIVATE_FILE_MODE,
                    )
                ) {
                    RESULT_STORAGE_ERROR
                } else {
                    Os.rename(candidate.absolutePath, publicKey.absolutePath)
                    committed = true
                    NativeBridge.RESULT_SUCCESS
                }
            }
        } catch (_: Exception) {
            RESULT_STORAGE_ERROR
        } finally {
            if (!committed) {
                clearAndDeleteRegularFile(candidate)
            }
        }
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
        Os.chmod(file.absolutePath, mode)
        val status = Os.lstat(file.absolutePath)
        return OsConstants.S_ISREG(status.st_mode) &&
            (status.st_mode and PERMISSION_MASK) == mode
    }

    companion object {
        const val RESULT_STORAGE_ERROR = -4
        const val WORK_CACHE_DIRECTORY_NAME = "nekokem-work"
        private const val CACHE_CLEAR_BUFFER_SIZE = 64 * 1024
        private const val KEY_COPY_BUFFER_SIZE = 64 * 1024
        private const val MAX_KEY_FILE_BYTES = 16L * 1024L * 1024L

        private const val KEYS_DIRECTORY_NAME = "keys"
        private const val PUBLIC_KEY_NAME = "public.key"
        private const val PRIVATE_KEY_NAME = "private.nkpr.enc"
        private const val PUBLIC_IMPORT_PREFIX = "nkem-pub-"
        private const val PRIVATE_IMPORT_PREFIX = "nkem-prv-"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val READ_WRITE_MODE = "rw"
        private const val PERMISSION_MASK = 0x1FF
        private const val PRIVATE_DIRECTORY_MODE = 0x1C0
        private const val PRIVATE_FILE_MODE = 0x180
    }
}
