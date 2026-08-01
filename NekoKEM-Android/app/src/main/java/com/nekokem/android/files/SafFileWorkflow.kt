package com.nekokem.android.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import com.nekokem.android.R
import com.nekokem.android.keys.LocalKeyManager
import com.nekokem.android.nativecore.NativeBridge
import com.nekokem.android.progress.CancellableProgressCallback
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

data class SelectedDocument(
    val uri: Uri,
    val displayName: String,
)

data class PreparedEncryptionResult(
    val code: Int,
    val prepared: PreparedEncryption?,
)

class PreparedEncryption internal constructor(
    private val encryptedFile: File,
) {
    private var active = true

    @Synchronized
    internal fun consume(): File? {
        if (!active) {
            return null
        }
        active = false
        return encryptedFile
    }
}

data class PreparedDecryptionResult(
    val code: Int,
    val prepared: PreparedDecryption?,
)

class PreparedDecryption internal constructor(
    private val plaintextFile: File,
) {
    private var active = true

    @Synchronized
    internal fun consume(): File? {
        if (!active) {
            return null
        }
        active = false
        return plaintextFile
    }
}

class SafFileWorkflow(
    context: Context,
    private val keyManager: LocalKeyManager,
) {
    private val contentResolver = context.contentResolver
    private val defaultSelectedFilename = context.getString(
        R.string.default_selected_filename,
    )
    private val workDirectory = File(
        context.cacheDir,
        LocalKeyManager.WORK_CACHE_DIRECTORY_NAME,
    )

    fun describe(uri: Uri): SelectedDocument = SelectedDocument(
        uri = uri,
        displayName = queryDisplayName(uri) ?: defaultSelectedFilename,
    )

    fun prepareEncryption(
        source: Uri,
        progress: CancellableProgressCallback,
    ): PreparedEncryptionResult {
        var stagedInput: File? = null
        var stagedOutput: File? = null
        var prepared: PreparedEncryption? = null
        var result = LocalKeyManager.RESULT_STORAGE_ERROR

        try {
            if (!prepareWorkDirectory()) {
                result = LocalKeyManager.RESULT_STORAGE_ERROR
            } else {
                stagedInput = createPrivateTemporaryFile(ENCRYPT_INPUT_PREFIX)
                stagedOutput = createPrivateTemporaryPath(ENCRYPT_OUTPUT_PREFIX)
                if (!copyUriToFile(
                        source,
                        stagedInput,
                        MAX_STAGED_FILE_BYTES,
                        progress,
                    )
                ) {
                    result = cancelledOrStorageError(progress)
                } else {
                    result = keyManager.encryptFile(
                        stagedInput,
                        stagedOutput,
                        progress,
                    )
                    if (result == NativeBridge.RESULT_SUCCESS &&
                        progress.isCancelled()
                    ) {
                        result = NativeBridge.RESULT_CANCELLED
                    } else if (result == NativeBridge.RESULT_SUCCESS) {
                        prepared = PreparedEncryption(stagedOutput)
                        stagedOutput = null
                    }
                }
            }
        } catch (_: Exception) {
            result = LocalKeyManager.RESULT_STORAGE_ERROR
        } finally {
            clearAndDelete(stagedInput)
            clearAndDelete(stagedOutput)
            if (prepared == null) {
                keyManager.clearWorkCache()
            }
        }
        return PreparedEncryptionResult(result, prepared)
    }

    fun commitPreparedEncryption(
        prepared: PreparedEncryption,
        destination: Uri,
        progress: CancellableProgressCallback,
    ): Int {
        val encrypted = prepared.consume()
            ?: return NativeBridge.RESULT_INVALID_ARGUMENT
        var outputCommitted = false

        return try {
            if (!copyFileToUri(encrypted, destination, progress, true)) {
                cancelledOrStorageError(progress)
            } else {
                outputCommitted = true
                NativeBridge.RESULT_SUCCESS
            }
        } catch (_: Exception) {
            LocalKeyManager.RESULT_STORAGE_ERROR
        } finally {
            if (!outputCommitted) {
                deleteDestinationQuietly(destination)
            }
            clearAndDelete(encrypted)
            keyManager.clearWorkCache()
        }
    }

    fun discardPreparedEncryption(prepared: PreparedEncryption?) {
        val encrypted = prepared?.consume() ?: return
        clearAndDelete(encrypted)
        keyManager.clearWorkCache()
    }


    /**
     * Takes ownership of [password]. Authentication and GCM verification are
     * completed in private storage before the UI creates a destination URI.
     */
    fun prepareDecryption(
        source: Uri,
        password: ByteArray,
        progress: CancellableProgressCallback,
    ): PreparedDecryptionResult {
        var stagedInput: File? = null
        var stagedOutput: File? = null
        var prepared: PreparedDecryption? = null
        var result = LocalKeyManager.RESULT_STORAGE_ERROR

        try {
            if (password.isEmpty()) {
                result = NativeBridge.RESULT_INVALID_ARGUMENT
            } else if (prepareWorkDirectory()) {
                stagedInput = createPrivateTemporaryFile(DECRYPT_INPUT_PREFIX)
                stagedOutput = createPrivateTemporaryPath(DECRYPT_OUTPUT_PREFIX)
                if (copyUriToFile(
                        source,
                        stagedInput,
                        MAX_STAGED_FILE_BYTES,
                        progress,
                    )
                ) {
                    result = keyManager.decryptFile(
                        stagedInput,
                        stagedOutput,
                        password,
                        progress,
                    )
                    if (result == NativeBridge.RESULT_SUCCESS &&
                        progress.isCancelled()
                    ) {
                        result = NativeBridge.RESULT_CANCELLED
                    } else if (result == NativeBridge.RESULT_SUCCESS) {
                        prepared = PreparedDecryption(stagedOutput)
                        stagedOutput = null
                    }
                } else {
                    result = cancelledOrStorageError(progress)
                }
            }
        } catch (_: Exception) {
            result = LocalKeyManager.RESULT_STORAGE_ERROR
        } finally {
            password.fill(0)
            clearAndDelete(stagedInput)
            clearAndDelete(stagedOutput)
            if (prepared == null) {
                keyManager.clearWorkCache()
            }
        }
        return PreparedDecryptionResult(result, prepared)
    }

    fun commitPreparedDecryption(
        prepared: PreparedDecryption,
        destination: Uri,
        progress: CancellableProgressCallback,
    ): Int {
        val plaintext = prepared.consume()
            ?: return NativeBridge.RESULT_INVALID_ARGUMENT
        var outputCommitted = false

        return try {
            if (!copyFileToUri(plaintext, destination, progress, true)) {
                cancelledOrStorageError(progress)
            } else {
                outputCommitted = true
                NativeBridge.RESULT_SUCCESS
            }
        } catch (_: Exception) {
            LocalKeyManager.RESULT_STORAGE_ERROR
        } finally {
            if (!outputCommitted) {
                deleteDestinationQuietly(destination)
            }
            clearAndDelete(plaintext)
            keyManager.clearWorkCache()
        }
    }

    fun discardPreparedDecryption(prepared: PreparedDecryption?) {
        val plaintext = prepared?.consume() ?: return
        clearAndDelete(plaintext)
        keyManager.clearWorkCache()
    }

    fun exportPublicKey(destination: Uri): Int = exportKey(destination) { output ->
        keyManager.exportPublicKey(output)
    }

    fun exportEncryptedPrivateKey(destination: Uri): Int =
        exportKey(destination) { output ->
            keyManager.exportEncryptedPrivateKey(output)
        }

    fun importPublicKey(source: Uri): Int {
        var stagedInput: File? = null

        return try {
            if (!prepareWorkDirectory()) {
                return LocalKeyManager.RESULT_STORAGE_ERROR
            }
            stagedInput = createPrivateTemporaryFile(PUBLIC_IMPORT_PREFIX)
            if (!copyUriToFile(
                    source,
                    stagedInput,
                    MAX_KEY_IMPORT_BYTES,
                    null,
                )
            ) {
                return LocalKeyManager.RESULT_STORAGE_ERROR
            }
            keyManager.importPublicKey(stagedInput)
        } catch (_: Exception) {
            LocalKeyManager.RESULT_STORAGE_ERROR
        } finally {
            clearAndDelete(stagedInput)
            keyManager.clearWorkCache()
        }
    }

    /** Takes ownership of [password] and clears it before returning. */
    fun importEncryptedPrivateKey(source: Uri, password: ByteArray): Int {
        var stagedInput: File? = null

        return try {
            if (password.isEmpty()) {
                return NativeBridge.RESULT_INVALID_ARGUMENT
            }
            if (!prepareWorkDirectory()) {
                return LocalKeyManager.RESULT_STORAGE_ERROR
            }
            stagedInput = createPrivateTemporaryFile(PRIVATE_IMPORT_PREFIX)
            if (!copyUriToFile(
                    source,
                    stagedInput,
                    MAX_KEY_IMPORT_BYTES,
                    null,
                )
            ) {
                return LocalKeyManager.RESULT_STORAGE_ERROR
            }
            keyManager.importEncryptedPrivateKey(stagedInput, password)
        } catch (_: Exception) {
            LocalKeyManager.RESULT_STORAGE_ERROR
        } finally {
            password.fill(0)
            clearAndDelete(stagedInput)
            keyManager.clearWorkCache()
        }
    }

    private fun exportKey(
        destination: Uri,
        producer: (File) -> Int,
    ): Int {
        var stagedOutput: File? = null
        var outputCommitted = false

        return try {
            if (!prepareWorkDirectory()) {
                return LocalKeyManager.RESULT_STORAGE_ERROR
            }
            stagedOutput = createPrivateTemporaryPath(KEY_EXPORT_PREFIX)
            val result = producer(stagedOutput)
            if (result != NativeBridge.RESULT_SUCCESS) {
                return result
            }
            if (!copyFileToUri(stagedOutput, destination, null, false)) {
                return LocalKeyManager.RESULT_STORAGE_ERROR
            }
            outputCommitted = true
            NativeBridge.RESULT_SUCCESS
        } catch (_: Exception) {
            LocalKeyManager.RESULT_STORAGE_ERROR
        } finally {
            if (!outputCommitted) {
                deleteDestinationQuietly(destination)
            }
            clearAndDelete(stagedOutput)
            keyManager.clearWorkCache()
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun prepareWorkDirectory(): Boolean {
        if (!workDirectory.exists() && !workDirectory.mkdir()) {
            return false
        }
        val initialStatus = Os.lstat(workDirectory.absolutePath)
        if (!OsConstants.S_ISDIR(initialStatus.st_mode)) {
            return false
        }
        Os.chmod(workDirectory.absolutePath, PRIVATE_DIRECTORY_MODE)
        val status = Os.lstat(workDirectory.absolutePath)
        return OsConstants.S_ISDIR(status.st_mode) &&
            (status.st_mode and PERMISSION_MASK) == PRIVATE_DIRECTORY_MODE
    }

    private fun createPrivateTemporaryFile(prefix: String): File {
        val file = File.createTempFile(prefix, TEMPORARY_SUFFIX, workDirectory)
        Os.chmod(file.absolutePath, PRIVATE_FILE_MODE)
        return file
    }

    private fun createPrivateTemporaryPath(prefix: String): File {
        val path = createPrivateTemporaryFile(prefix)
        if (!path.delete()) {
            throw IllegalStateException()
        }
        return path
    }

    private fun copyUriToFile(
        source: Uri,
        destination: File,
        maximumBytes: Long,
        progress: CancellableProgressCallback?,
    ): Boolean {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var total = 0L

        return try {
            val input = contentResolver.openInputStream(source) ?: return false
            input.use { sourceStream ->
                FileOutputStream(destination, false).use { destinationStream ->
                    while (true) {
                        if (progress?.isCancelled() == true) {
                            return false
                        }
                        val count = sourceStream.read(buffer)
                        if (count < 0) {
                            break
                        }
                        if (count == 0) {
                            continue
                        }
                        total += count.toLong()
                        if (total > maximumBytes) {
                            return false
                        }
                        destinationStream.write(buffer, 0, count)
                    }
                    destinationStream.fd.sync()
                }
            }
            total > 0L && setAndVerifyPrivateFile(destination)
        } finally {
            buffer.fill(0)
        }
    }

    private fun copyFileToUri(
        source: File,
        destination: Uri,
        progress: CancellableProgressCallback?,
        reportProgress: Boolean,
    ): Boolean {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        val expected = source.length().coerceAtLeast(0L)
        var processed = 0L

        return try {
            if (reportProgress && progress?.onProgress(0L, expected) == false) {
                return false
            }
            val output = contentResolver.openOutputStream(
                destination,
                WRITE_TRUNCATE_MODE,
            ) ?: return false
            FileInputStream(source).use { sourceStream ->
                output.use { destinationStream ->
                    while (true) {
                        if (progress?.isCancelled() == true) {
                            return false
                        }
                        val count = sourceStream.read(buffer)
                        if (count < 0) {
                            break
                        }
                        if (count > 0) {
                            destinationStream.write(buffer, 0, count)
                            processed += count.toLong()
                            if (reportProgress &&
                                progress?.onProgress(processed, expected) == false
                            ) {
                                return false
                            }
                        }
                    }
                    destinationStream.flush()
                }
            }
            true
        } finally {
            buffer.fill(0)
        }
    }

    private fun setAndVerifyPrivateFile(file: File): Boolean {
        Os.chmod(file.absolutePath, PRIVATE_FILE_MODE)
        val status = Os.lstat(file.absolutePath)
        return OsConstants.S_ISREG(status.st_mode) &&
            (status.st_mode and PERMISSION_MASK) == PRIVATE_FILE_MODE
    }

    private fun clearAndDelete(file: File?) {
        if (file == null || !file.exists()) {
            return
        }
        try {
            val status = Os.lstat(file.absolutePath)
            if (OsConstants.S_ISREG(status.st_mode)) {
                RandomAccessFile(file, READ_WRITE_MODE).use { stream ->
                    val zeros = ByteArray(COPY_BUFFER_SIZE)
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
            }
        } catch (_: Exception) {
            // Deletion is still attempted; no file contents are logged.
        } finally {
            file.delete()
        }
    }

    private fun deleteDestinationQuietly(destination: Uri) {
        try {
            contentResolver.delete(destination, null, null)
        } catch (_: Exception) {
            // Some providers cannot delete; no URI or contents are logged.
        }
    }

    private fun cancelledOrStorageError(
        progress: CancellableProgressCallback,
    ): Int = if (progress.isCancelled()) {
        NativeBridge.RESULT_CANCELLED
    } else {
        LocalKeyManager.RESULT_STORAGE_ERROR
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val MAX_STAGED_FILE_BYTES = 8L * 1024L * 1024L * 1024L
        const val MAX_KEY_IMPORT_BYTES = 16L * 1024L * 1024L
        const val PERMISSION_MASK = 0x1FF
        const val PRIVATE_DIRECTORY_MODE = 0x1C0
        const val PRIVATE_FILE_MODE = 0x180

        const val ENCRYPT_INPUT_PREFIX = "nkem-ei-"
        const val ENCRYPT_OUTPUT_PREFIX = "nkem-eo-"
        const val DECRYPT_INPUT_PREFIX = "nkem-di-"
        const val DECRYPT_OUTPUT_PREFIX = "nkem-do-"
        const val PUBLIC_IMPORT_PREFIX = "nkem-pi-"
        const val PRIVATE_IMPORT_PREFIX = "nkem-ki-"
        const val KEY_EXPORT_PREFIX = "nkem-ke-"
        const val TEMPORARY_SUFFIX = ".tmp"
        const val WRITE_TRUNCATE_MODE = "wt"
        const val READ_WRITE_MODE = "rw"
    }
}
