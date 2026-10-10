package com.shixiaoshi0417.nekokem.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.WorkerThread
import android.system.Os
import android.system.OsConstants
import com.shixiaoshi0417.nekokem.R
import com.shixiaoshi0417.nekokem.keys.LocalKeyManager
import com.shixiaoshi0417.nekokem.keys.PendingTemporaryPrivateKey
import com.shixiaoshi0417.nekokem.keys.PendingTemporaryPrivateKeyResult
import com.shixiaoshi0417.nekokem.keys.PublicKeyExportTrace
import com.shixiaoshi0417.nekokem.keys.PublicKeyFileRecord
import com.shixiaoshi0417.nekokem.keys.PublicKeyImportTrace
import com.shixiaoshi0417.nekokem.keys.TemporaryPrivateKey
import com.shixiaoshi0417.nekokem.keys.TemporaryPrivateKeyResult
import com.shixiaoshi0417.nekokem.keys.TemporaryPublicKey
import com.shixiaoshi0417.nekokem.keys.TemporaryPublicKeyResult
import com.shixiaoshi0417.nekokem.nativecore.NativeBridge
import com.shixiaoshi0417.nekokem.progress.CancellableProgressCallback
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class SelectedDocument(
    val uri: Uri,
    val displayName: String,
)

/**
 * A provider-chosen display name as one plain line: dialogs print it next to
 * key fingerprints, so it must not carry controls, line or paragraph breaks,
 * or invisible format characters (bidirectional overrides among them) that
 * could fake or reorder another line. A zero-width joiner or non-joiner
 * stays only between two non-ASCII characters, where scripts and emoji
 * sequences need it; elsewhere it only hides something. The name also
 * suggests output file names, so the cap leaves a real file name whole.
 */
internal fun displaySafeName(name: String?, fallback: String): String {
    // Joiners are judged by their neighbours after everything else unsafe is
    // gone, so a hidden character or a second joiner is never a neighbour.
    val kept = name.orEmpty().codePoints()
        .filter { isJoiner(it) || isSafeNameCodePoint(it) }
        .toArray()
    val visible = buildString {
        kept.forEachIndexed { index, codePoint ->
            if (!isJoiner(codePoint) ||
                (index > 0 && isJoinable(kept[index - 1]) &&
                    index + 1 < kept.size && isJoinable(kept[index + 1]))
            ) appendCodePoint(codePoint)
        }
    }.trim()
    val end = if (visible.codePointCount(0, visible.length) > MAX_DISPLAY_NAME_LENGTH) {
        visible.offsetByCodePoints(0, MAX_DISPLAY_NAME_LENGTH)
    } else visible.length
    var capped = visible.substring(0, end)
    // The cap may have cut the character a joiner was joining.
    if (capped.isNotEmpty() && isJoiner(capped.codePointBefore(capped.length))) capped = capped.dropLast(1)
    return capped.trimEnd().ifEmpty { fallback }
}

private fun isJoiner(codePoint: Int): Boolean =
    codePoint == ZERO_WIDTH_NON_JOINER || codePoint == ZERO_WIDTH_JOINER

private fun isJoinable(codePoint: Int): Boolean =
    codePoint > MAX_ASCII && !isJoiner(codePoint) &&
        !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)

private fun isSafeNameCodePoint(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.CONTROL.toInt(), Character.LINE_SEPARATOR.toInt(),
    Character.PARAGRAPH_SEPARATOR.toInt(), Character.SURROGATE.toInt() -> false
    Character.FORMAT.toInt() -> false
    else -> true
}

private const val ZERO_WIDTH_NON_JOINER = 0x200C
private const val ZERO_WIDTH_JOINER = 0x200D
private const val MAX_ASCII = 0x7F
private const val MAX_DISPLAY_NAME_LENGTH = 255

data class PreparedEncryptionResult(
    val code: Int,
    val prepared: PreparedEncryption?,
)

class PreparedEncryption internal constructor(
    private val encryptedFile: File,
    internal val workspace: File? = null,
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
    internal val workspace: File? = null,
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

/**
 * [cacheRoot] is the page's own cache directory; every operation stages its
 * files in a private workspace below it and removes only that workspace.
 */
class SafFileWorkflow(
    context: Context,
    private val keyManager: LocalKeyManager,
    cacheRoot: File = context.cacheDir,
) {
    private val contentResolver = context.contentResolver
    private val defaultSelectedFilename = context.getString(
        R.string.default_selected_filename,
    )
    private val workDirectory = File(cacheRoot, WORK_DIRECTORY_NAME)
    private val outputBackupDirectory = File(
        context.cacheDir,
        OUTPUT_BACKUP_DIRECTORY_NAME,
    )
    private val temporaryKeyDirectory = File(
        cacheRoot,
        TEMPORARY_KEY_DIRECTORY_NAME,
    )
    private val temporaryPublicKeyDirectory = File(
        temporaryKeyDirectory,
        TEMPORARY_PUBLIC_KEY_DIRECTORY_NAME,
    )
    private val temporaryPrivateKeyDirectory = File(
        temporaryKeyDirectory,
        TEMPORARY_PRIVATE_KEY_DIRECTORY_NAME,
    )

    private enum class OutputState { UNMODIFIED, MODIFYING, FAILED, WRITTEN, FINISHED }

    private inner class UriOutputTransaction(private val destination: Uri) {
        private var state = OutputState.UNMODIFIED
        private var backup: File? = null
        val success: Boolean get() = state == OutputState.WRITTEN

        fun snapshot() {
            check(state == OutputState.UNMODIFIED && backup == null)
            backup = backupUriToFile(destination)
        }

        fun beginModification() {
            check(state == OutputState.UNMODIFIED && backup != null)
            // Opening "wt" may truncate before returning OR throwing.
            state = OutputState.MODIFYING
        }

        fun written() {
            check(state == OutputState.MODIFYING)
            state = OutputState.WRITTEN
        }

        fun fail(): UriOutputTransaction {
            if (state == OutputState.MODIFYING) state = OutputState.FAILED
            return this
        }

        fun commit() {
            check(state == OutputState.WRITTEN)
            state = OutputState.FINISHED
            clearAndDelete(backup)
            backup = null
        }

        fun rollback() {
            if (state == OutputState.FINISHED) return
            val saved = backup
            val needsRestore = state != OutputState.UNMODIFIED
            state = OutputState.FINISHED
            if (needsRestore && saved != null && !restoreFileToUri(saved, destination)) {
                // Recovery files survive startup cleanup until manually recovered.
                val recovery = File(outputBackupDirectory, saved.name.removeSuffix(TEMPORARY_SUFFIX) + RECOVERY_SUFFIX)
                try {
                    Os.rename(saved.absolutePath, recovery.absolutePath)
                    activeOutputBackups.remove(saved.absolutePath)
                    backup = recovery
                } catch (_: Exception) {
                    // The original backup is also recognized as recovery material.
                    activeOutputBackups.remove(saved.absolutePath)
                }
                return
            }
            clearAndDelete(saved)
            backup = null
        }
    }

    @WorkerThread
    fun describe(uri: Uri): SelectedDocument = SelectedDocument(
        uri = uri,
        displayName = displaySafeName(queryDisplayName(uri), defaultSelectedFilename),
    )

    fun prepareEncryption(
        source: Uri,
        progress: CancellableProgressCallback,
        temporaryKey: TemporaryPublicKey? = null,
    ): PreparedEncryptionResult = prepareEncryptionWith(
        source,
        progress,
        listOfNotNull(temporaryKey),
    ) { input, output -> keyManager.encryptFile(input, output, temporaryKey, progress) }

    /**
     * Saved-contact recipients, in order: one keeps NKEM v3, several share one
     * NKEM v4 file. Takes ownership of every snapshot and deletes it.
     */
    fun prepareEncryptionForRecipients(
        source: Uri,
        progress: CancellableProgressCallback,
        recipients: List<TemporaryPublicKey>,
    ): PreparedEncryptionResult = prepareEncryptionWith(
        source,
        progress,
        recipients,
    ) { input, output -> keyManager.encryptFileForRecipients(input, output, recipients, progress) }

    private fun prepareEncryptionWith(
        source: Uri,
        progress: CancellableProgressCallback,
        temporaryKeys: List<TemporaryPublicKey>,
        encrypt: (File, File) -> Int,
    ): PreparedEncryptionResult {
        var workspace: File? = null
        var stagedInput: File? = null
        var stagedOutput: File? = null
        var prepared: PreparedEncryption? = null
        var result = LocalKeyManager.RESULT_STORAGE_ERROR

        try {
            workspace = createWorkspace()
            if (workspace == null) {
                result = LocalKeyManager.RESULT_STORAGE_ERROR
            } else {
                val input = createPrivateTemporaryFile(ENCRYPT_INPUT_PREFIX, workspace)
                stagedInput = input
                val output = createPrivateTemporaryPath(ENCRYPT_OUTPUT_PREFIX, workspace)
                stagedOutput = output
                if (!copyUriToFile(
                        source,
                        input,
                        MAX_PLAINTEXT_BYTES,
                        progress,
                        allowEmpty = true,
                    )
                ) {
                    result = cancelledOrStorageError(progress)
                } else {
                    result = encrypt(input, output)
                    if (result == NativeBridge.RESULT_SUCCESS &&
                        progress.isCancelled()
                    ) {
                        result = NativeBridge.RESULT_CANCELLED
                    } else if (result == NativeBridge.RESULT_SUCCESS) {
                        prepared = PreparedEncryption(output, workspace)
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
                removeWorkspace(workspace)
            }
            temporaryKeys.forEach { discardTemporaryPublicKey(it) }
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
        var transfer: UriOutputTransaction? = null

        return try {
            val currentTransfer = copyFileToUri(
                encrypted,
                destination,
                progress,
                true,
            )
            transfer = currentTransfer
            if (!currentTransfer.success) {
                cancelledOrStorageError(progress)
            } else {
                currentTransfer.commit()
                outputCommitted = true
                NativeBridge.RESULT_SUCCESS
            }
        } catch (_: Exception) {
            LocalKeyManager.RESULT_STORAGE_ERROR
        } finally {
            if (!outputCommitted) transfer?.rollback()
            clearAndDelete(encrypted)
            removeWorkspace(prepared.workspace)
        }
    }

    fun discardPreparedEncryption(prepared: PreparedEncryption?) {
        val encrypted = prepared?.consume() ?: return
        clearAndDelete(encrypted)
        removeWorkspace(prepared.workspace)
    }


    /**
     * Takes ownership of [password]. Authentication and GCM verification are
     * completed in private storage before the UI creates a destination URI.
     */
    fun prepareDecryption(
        source: Uri,
        password: ByteArray,
        progress: CancellableProgressCallback,
        temporaryKey: TemporaryPrivateKey? = null,
    ): PreparedDecryptionResult {
        var workspace: File? = null
        var stagedInput: File? = null
        var stagedOutput: File? = null
        var prepared: PreparedDecryption? = null
        var result = LocalKeyManager.RESULT_STORAGE_ERROR

        try {
            val directory = if (password.isEmpty()) null else createWorkspace()
            workspace = directory
            if (password.isEmpty()) {
                result = NativeBridge.RESULT_INVALID_ARGUMENT
            } else if (directory != null) {
                stagedInput = createPrivateTemporaryFile(DECRYPT_INPUT_PREFIX, directory)
                stagedOutput = createPrivateTemporaryPath(DECRYPT_OUTPUT_PREFIX, directory)
                if (copyUriToFile(
                        source,
                        stagedInput,
                        MAX_CONTAINER_BYTES,
                        progress,
                    )
                ) {
                    result = keyManager.decryptFile(
                        stagedInput,
                        stagedOutput,
                        temporaryKey,
                        password,
                        progress,
                    )
                    if (result == NativeBridge.RESULT_SUCCESS &&
                        progress.isCancelled()
                    ) {
                        result = NativeBridge.RESULT_CANCELLED
                    } else if (result == NativeBridge.RESULT_SUCCESS) {
                        prepared = PreparedDecryption(stagedOutput, workspace)
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
                removeWorkspace(workspace)
            }
            discardTemporaryPrivateKey(temporaryKey)
        }
        return PreparedDecryptionResult(result, prepared)
    }

    /**
     * Copies a SAF public key into a 0700 App-private directory, then asks
     * Core to parse and re-serialize it. Only the canonical 0600 file is
     * retained for a single encryption operation.
     */
    fun stageTemporaryPublicKey(
        source: Uri,
        displayName: String,
    ): TemporaryPublicKeyResult {
        var raw: File? = null
        var canonical: File? = null
        var selected: TemporaryPublicKey? = null
        var result = LocalKeyManager.RESULT_STORAGE_ERROR

        try {
            if (!prepareTemporaryKeyDirectory(temporaryPublicKeyDirectory)) {
                result = LocalKeyManager.RESULT_STORAGE_ERROR
            } else {
                raw = createPrivateTemporaryFile(
                    TEMPORARY_PUBLIC_RAW_PREFIX,
                    temporaryPublicKeyDirectory,
                )
                canonical = createPrivateTemporaryPath(
                    TEMPORARY_PUBLIC_CANONICAL_PREFIX,
                    temporaryPublicKeyDirectory,
                )
                if (!copyUriToFile(source, raw, MAX_KEY_IMPORT_BYTES, null)) {
                    result = LocalKeyManager.RESULT_STORAGE_ERROR
                } else {
                    result = keyManager.normalizePublicKey(raw, canonical)
                    if (result == NativeBridge.RESULT_SUCCESS &&
                        setAndVerifyPrivateFile(canonical)
                    ) {
                        val fingerprint = keyManager.publicKeyFingerprint(canonical)
                        if (fingerprint == null) {
                            result = NativeBridge.RESULT_CORE_ERROR
                        } else {
                            selected = TemporaryPublicKey(
                                canonical,
                                displayName,
                                fingerprint,
                            )
                            canonical = null
                        }
                    } else if (result == NativeBridge.RESULT_SUCCESS) {
                        result = LocalKeyManager.RESULT_STORAGE_ERROR
                    }
                }
            }
        } catch (_: Exception) {
            result = LocalKeyManager.RESULT_STORAGE_ERROR
        } finally {
            clearAndDelete(raw)
            clearAndDelete(canonical)
            removeTemporaryDirectoryIfEmpty(temporaryPublicKeyDirectory)
        }
        return TemporaryPublicKeyResult(result, selected)
    }

    /** Stages an NKPR candidate; no parsing or password handling occurs here. */
    fun stageTemporaryPrivateKey(
        source: Uri,
        displayName: String,
    ): PendingTemporaryPrivateKeyResult {
        var candidate: File? = null
        var pending: PendingTemporaryPrivateKey? = null
        var result = LocalKeyManager.RESULT_STORAGE_ERROR

        try {
            if (!prepareTemporaryKeyDirectory(temporaryPrivateKeyDirectory)) {
                result = LocalKeyManager.RESULT_STORAGE_ERROR
            } else {
                candidate = createPrivateTemporaryFile(
                    TEMPORARY_PRIVATE_PREFIX,
                    temporaryPrivateKeyDirectory,
                    suffix = ".enc",
                )
                if (!copyUriToFile(
                        source,
                        candidate,
                        MAX_KEY_IMPORT_BYTES,
                        null,
                    )
                ) {
                    result = LocalKeyManager.RESULT_STORAGE_ERROR
                } else {
                    pending = PendingTemporaryPrivateKey(
                        candidate,
                        displayName,
                    )
                    candidate = null
                    result = NativeBridge.RESULT_SUCCESS
                }
            }
        } catch (_: Exception) {
            result = LocalKeyManager.RESULT_STORAGE_ERROR
        } finally {
            clearAndDelete(candidate)
            removeTemporaryDirectoryIfEmpty(temporaryPrivateKeyDirectory)
        }
        return PendingTemporaryPrivateKeyResult(result, pending)
    }

    /** Takes ownership of [password] and of the pending candidate. */
    fun validateTemporaryPrivateKey(
        pending: PendingTemporaryPrivateKey,
        password: ByteArray,
    ): TemporaryPrivateKeyResult {
        val candidate = pending.consume()
            ?: run {
                password.fill(0)
                return TemporaryPrivateKeyResult(
                    NativeBridge.RESULT_INVALID_ARGUMENT,
                    null,
                )
            }
        var selected: TemporaryPrivateKey? = null
        var result = NativeBridge.RESULT_CORE_ERROR

        try {
            if (!setAndVerifyPrivateFile(candidate)) {
                result = LocalKeyManager.RESULT_STORAGE_ERROR
            } else {
                val fingerprint = keyManager.privateKeyFingerprint(
                    candidate,
                    password,
                )
                if (fingerprint != null) {
                    selected = TemporaryPrivateKey(
                        candidate,
                        pending.displayName,
                        fingerprint,
                    )
                    result = NativeBridge.RESULT_SUCCESS
                }
            }
        } catch (_: Exception) {
            result = LocalKeyManager.RESULT_STORAGE_ERROR
        } finally {
            password.fill(0)
            if (selected == null) {
                clearAndDelete(candidate)
                removeTemporaryDirectoryIfEmpty(
                    temporaryPrivateKeyDirectory,
                )
            }
        }
        return TemporaryPrivateKeyResult(result, selected)
    }

    fun discardTemporaryPublicKey(key: TemporaryPublicKey?) {
        clearAndDelete(key?.file)
        removeTemporaryDirectoryIfEmpty(temporaryPublicKeyDirectory)
    }

    fun discardPendingTemporaryPrivateKey(
        key: PendingTemporaryPrivateKey?,
    ) {
        clearAndDelete(key?.consume())
        removeTemporaryDirectoryIfEmpty(temporaryPrivateKeyDirectory)
    }

    fun discardTemporaryPrivateKey(key: TemporaryPrivateKey?) {
        clearAndDelete(key?.file)
        removeTemporaryDirectoryIfEmpty(temporaryPrivateKeyDirectory)
    }

    fun clearTemporaryKeyCache(): Boolean {
        val publicCleared = clearPrivateTemporaryDirectory(
            temporaryPublicKeyDirectory,
        )
        val privateCleared = clearPrivateTemporaryDirectory(
            temporaryPrivateKeyDirectory,
        )
        val rootRemoved = removeTemporaryDirectoryIfEmpty(
            temporaryKeyDirectory,
        )
        return publicCleared && privateCleared && rootRemoved
    }

    internal fun hasTemporaryKeyCache(): Boolean =
        temporaryKeyDirectory.exists()

    fun commitPreparedDecryption(
        prepared: PreparedDecryption,
        destination: Uri,
        progress: CancellableProgressCallback,
    ): Int {
        val plaintext = prepared.consume()
            ?: return NativeBridge.RESULT_INVALID_ARGUMENT
        var outputCommitted = false
        var transfer: UriOutputTransaction? = null

        return try {
            val currentTransfer = copyFileToUri(
                plaintext,
                destination,
                progress,
                true,
            )
            transfer = currentTransfer
            if (!currentTransfer.success) {
                cancelledOrStorageError(progress)
            } else {
                currentTransfer.commit()
                outputCommitted = true
                NativeBridge.RESULT_SUCCESS
            }
        } catch (_: Exception) {
            LocalKeyManager.RESULT_STORAGE_ERROR
        } finally {
            if (!outputCommitted) transfer?.rollback()
            clearAndDelete(plaintext)
            removeWorkspace(prepared.workspace)
        }
    }

    fun discardPreparedDecryption(prepared: PreparedDecryption?) {
        val plaintext = prepared?.consume() ?: return
        clearAndDelete(plaintext)
        removeWorkspace(prepared.workspace)
    }

    fun exportPublicKey(destination: Uri): Int =
        exportPublicKeyDetailed(destination).code

    fun exportPublicKeyDetailed(destination: Uri): PublicKeyExportTrace {
        var stagedOutput: File? = null
        var defaultRecord: PublicKeyFileRecord? = null
        var normalizedRecord: PublicKeyFileRecord? = null
        var safRecord: PublicKeyFileRecord? = null
        var outputCommitted = false
        var transfer: UriOutputTransaction? = null
        var result = LocalKeyManager.RESULT_STORAGE_ERROR
        var workspace: File? = null

        try {
            workspace = createWorkspace()
            if (workspace == null) {
                result = LocalKeyManager.RESULT_STORAGE_ERROR
            } else {
                defaultRecord = keyManager.defaultPublicKeyRecord()
                stagedOutput = createPrivateTemporaryPath(KEY_EXPORT_PREFIX, workspace)
                result = keyManager.exportPublicKey(stagedOutput)
                if (result == NativeBridge.RESULT_SUCCESS) {
                    normalizedRecord = keyManager.publicKeyFileRecord(stagedOutput)
                    if (defaultRecord == null || normalizedRecord == null) {
                        result = LocalKeyManager.RESULT_STORAGE_ERROR
                    } else {
                        val currentTransfer = copyFileToUri(
                            stagedOutput,
                            destination,
                            null,
                            false,
                        )
                        transfer = currentTransfer
                        if (!currentTransfer.success) {
                            result = LocalKeyManager.RESULT_PUBLIC_KEY_COPY_FAILED
                        } else {
                            safRecord = publicKeyUriRecord(destination)
                            if (safRecord == null ||
                                safRecord != normalizedRecord
                            ) {
                                result =
                                    LocalKeyManager.RESULT_PUBLIC_KEY_INTEGRITY_FAILED
                            } else {
                                currentTransfer.commit()
                                outputCommitted = true
                                result = NativeBridge.RESULT_SUCCESS
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            result = LocalKeyManager.RESULT_STORAGE_ERROR
        } finally {
            if (!outputCommitted) transfer?.rollback()
            clearAndDelete(stagedOutput)
            removeWorkspace(workspace)
        }
        return PublicKeyExportTrace(
            code = result,
            defaultPublicKey = defaultRecord,
            privateNormalizedOutput = normalizedRecord,
            safOutput = safRecord,
        )
    }

    fun exportEncryptedPrivateKey(destination: Uri): Int =
        exportKey(destination) { output ->
            keyManager.exportEncryptedPrivateKey(output)
        }

    /**
     * Takes ownership of [privateKeyPassword], which proves the key belongs
     * to the existing private key, and clears it before returning.
     */
    fun importPublicKey(source: Uri, privateKeyPassword: ByteArray? = null): Int =
        importPublicKeyDetailed(source, privateKeyPassword).code

    fun importPublicKeyDetailed(
        source: Uri,
        privateKeyPassword: ByteArray? = null,
    ): PublicKeyImportTrace {
        var stagedInput: File? = null
        var safCandidate: PublicKeyFileRecord? = null
        var normalizedCandidate: PublicKeyFileRecord? = null
        var coreParseResult = NativeBridge.RESULT_CORE_ERROR
        var coreNormalizeResult = NativeBridge.RESULT_CORE_ERROR
        var permissionResult =
            LocalKeyManager.RESULT_PUBLIC_KEY_PERMISSION_FAILED
        var commitResult = LocalKeyManager.RESULT_PUBLIC_KEY_COMMIT_FAILED
        var commitErrno: Int? = null
        var result = LocalKeyManager.RESULT_STORAGE_ERROR
        var workspace: File? = null

        try {
            workspace = createWorkspace()
            if (workspace == null) {
                result = LocalKeyManager.RESULT_STORAGE_ERROR
            } else {
                stagedInput = createPrivateTemporaryFile(PUBLIC_IMPORT_PREFIX, workspace)
                if (!copyUriToFile(
                        source,
                        stagedInput,
                        MAX_KEY_IMPORT_BYTES,
                        null,
                    )
                ) {
                    result = LocalKeyManager.RESULT_PUBLIC_KEY_COPY_FAILED
                } else {
                    safCandidate = keyManager.publicKeyFileRecord(stagedInput)
                    val imported = keyManager.importPublicKeyDetailed(stagedInput, privateKeyPassword)
                    result = imported.code
                    normalizedCandidate = imported.normalizedCandidate
                    coreParseResult = imported.coreParseResult
                    coreNormalizeResult = imported.coreNormalizeResult
                    permissionResult = imported.permissionResult
                    commitResult = imported.commitResult
                    commitErrno = imported.commitErrno
                }
            }
        } catch (_: Exception) {
            result = LocalKeyManager.RESULT_STORAGE_ERROR
        } finally {
            privateKeyPassword?.fill(0)
            clearAndDelete(stagedInput)
            removeWorkspace(workspace)
        }
        return PublicKeyImportTrace(
            code = result,
            safCandidate = safCandidate,
            normalizedCandidate = normalizedCandidate,
            coreParseResult = coreParseResult,
            coreNormalizeResult = coreNormalizeResult,
            permissionResult = permissionResult,
            commitResult = commitResult,
            commitErrno = commitErrno,
        )
    }

    /** Takes ownership of [password] and clears it before returning. */
    fun importEncryptedPrivateKey(source: Uri, password: ByteArray): Int {
        var stagedInput: File? = null
        var workspace: File? = null

        return try {
            if (password.isEmpty()) {
                return NativeBridge.RESULT_INVALID_ARGUMENT
            }
            val directory = createWorkspace()
                ?: return LocalKeyManager.RESULT_STORAGE_ERROR
            workspace = directory
            stagedInput = createPrivateTemporaryFile(PRIVATE_IMPORT_PREFIX, directory)
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
            removeWorkspace(workspace)
        }
    }

    private fun exportKey(
        destination: Uri,
        producer: (File) -> Int,
    ): Int {
        var stagedOutput: File? = null
        var outputCommitted = false
        var transfer: UriOutputTransaction? = null
        var workspace: File? = null

        return try {
            val directory = createWorkspace()
                ?: return LocalKeyManager.RESULT_STORAGE_ERROR
            workspace = directory
            stagedOutput = createPrivateTemporaryPath(KEY_EXPORT_PREFIX, directory)
            val result = producer(stagedOutput)
            if (result != NativeBridge.RESULT_SUCCESS) {
                return result
            }
            val currentTransfer = copyFileToUri(
                stagedOutput,
                destination,
                null,
                false,
            )
            transfer = currentTransfer
            if (!currentTransfer.success) {
                return LocalKeyManager.RESULT_STORAGE_ERROR
            }
            currentTransfer.commit()
            outputCommitted = true
            NativeBridge.RESULT_SUCCESS
        } catch (_: Exception) {
            LocalKeyManager.RESULT_STORAGE_ERROR
        } finally {
            if (!outputCommitted) transfer?.rollback()
            clearAndDelete(stagedOutput)
            removeWorkspace(workspace)
        }
    }

    /** Old empty staging backups are disposable; every nonempty backup may need recovery. */
    @WorkerThread
    fun cleanupExpiredOutputBackups(now: Long = System.currentTimeMillis()): Int {
        if (!ownedPrivateDirectory(outputBackupDirectory)) return 0
        outputBackupDirectory.listFiles().orEmpty().forEach { file ->
            if (isExpiredEmptyOutputBackup(file, now)) clearAndDelete(file)
        }
        return recoveryBackupCount()
    }

    @WorkerThread
    fun recoveryBackupCount(): Int {
        if (!ownedPrivateDirectory(outputBackupDirectory)) return 0
        return outputBackupDirectory.listFiles().orEmpty().count { file ->
            file.name.startsWith(OUTPUT_BACKUP_PREFIX) &&
                !activeOutputBackups.contains(file.absolutePath) &&
                (file.name.endsWith(RECOVERY_SUFFIX) ||
                    (file.name.endsWith(TEMPORARY_SUFFIX) && file.length() > 0L)) &&
                ownedSingleRegularFile(file)
        }
    }

    private fun isExpiredEmptyOutputBackup(file: File, now: Long): Boolean =
        file.name.startsWith(OUTPUT_BACKUP_PREFIX) && file.name.endsWith(TEMPORARY_SUFFIX) &&
            !activeOutputBackups.contains(file.absolutePath) && ownedSingleRegularFile(file) && file.length() == 0L &&
            file.lastModified() > 0L && now >= file.lastModified() &&
            now - file.lastModified() >= TEMPORARY_MAX_AGE_MILLIS

    private fun ownedSingleRegularFile(file: File): Boolean = try {
        val status = Os.lstat(file.absolutePath)
        OsConstants.S_ISREG(status.st_mode) && status.st_uid == Os.getuid() && status.st_nlink == 1L
    } catch (_: Exception) { false }

    private fun ownedPrivateDirectory(directory: File): Boolean = try {
        val status = Os.lstat(directory.absolutePath)
        OsConstants.S_ISDIR(status.st_mode) && status.st_uid == Os.getuid() &&
            (status.st_mode and PERMISSION_MASK) == PRIVATE_DIRECTORY_MODE
    } catch (_: Exception) { false }

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

    private fun publicKeyUriRecord(uri: Uri): PublicKeyFileRecord? {
        val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var length = 0L

        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { stream ->
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) {
                        break
                    }
                    if (count > 0) {
                        length += count.toLong()
                        if (length > MAX_KEY_IMPORT_BYTES) {
                            return null
                        }
                        digest.update(buffer, 0, count)
                    }
                }
            }
            PublicKeyFileRecord(
                length = length,
                sha256 = digest.digest().joinToString("") { byte ->
                    "%02x".format(byte.toInt() and 0xFF)
                },
            )
        } catch (_: Exception) {
            null
        } finally {
            buffer.fill(0)
        }
    }

    /** A new private directory used by one operation only, or null. */
    private fun createWorkspace(): File? {
        if (!preparePrivateDirectory(workDirectory)) return null
        val workspace = java.nio.file.Files.createTempDirectory(
            workDirectory.toPath(),
            WORKSPACE_PREFIX,
        ).toFile()
        if (!preparePrivateDirectory(workspace)) {
            removeWorkspace(workspace)
            return null
        }
        return workspace
    }

    /** Clears and removes one operation's workspace, never anything else. */
    private fun removeWorkspace(workspace: File?) {
        if (workspace == null) return
        workspace.listFiles().orEmpty().forEach(::clearAndDelete)
        workspace.delete()
    }

    private fun prepareTemporaryKeyDirectory(directory: File): Boolean =
        preparePrivateDirectory(temporaryKeyDirectory) &&
            preparePrivateDirectory(directory)

    private fun preparePrivateDirectory(directory: File): Boolean {
        if (!directory.exists() && !directory.mkdir()) {
            return false
        }
        val initialStatus = Os.lstat(directory.absolutePath)
        if (!OsConstants.S_ISDIR(initialStatus.st_mode) ||
            initialStatus.st_uid != Os.getuid()
        ) {
            return false
        }
        Os.chmod(directory.absolutePath, PRIVATE_DIRECTORY_MODE)
        val status = Os.lstat(directory.absolutePath)
        return OsConstants.S_ISDIR(status.st_mode) &&
            status.st_uid == Os.getuid() &&
            (status.st_mode and PERMISSION_MASK) == PRIVATE_DIRECTORY_MODE
    }

    private fun createPrivateTemporaryFile(
        prefix: String,
        directory: File,
        suffix: String = TEMPORARY_SUFFIX,
    ): File {
        val file = File.createTempFile(prefix, suffix, directory)
        Os.chmod(file.absolutePath, PRIVATE_FILE_MODE)
        if (!setAndVerifyPrivateFile(file)) {
            clearAndDelete(file)
            throw IllegalStateException()
        }
        return file
    }

    private fun createPrivateTemporaryPath(
        prefix: String,
        directory: File,
    ): File {
        val path = createPrivateTemporaryFile(prefix, directory)
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
        allowEmpty: Boolean = false,
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
            (allowEmpty || total > 0L) && setAndVerifyPrivateFile(destination)
        } finally {
            buffer.fill(0)
        }
    }

    private fun backupUriToFile(destination: Uri): File {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var backup: File? = null
        return try {
            // Include ALL initialization and stream closing in the cleanup scope.
            val input = contentResolver.openInputStream(destination)
                ?: throw IOException("Cannot read output before replacement")
            input.use { sourceStream ->
                if (!preparePrivateDirectory(outputBackupDirectory)) {
                    throw IOException("Cannot prepare output backup directory")
                }
                val saved = createPrivateTemporaryFile(
                    OUTPUT_BACKUP_PREFIX,
                    outputBackupDirectory,
                )
                backup = saved
                activeOutputBackups.add(saved.absolutePath)
                FileOutputStream(saved, false).use { backupStream ->
                    var total = 0L
                    while (true) {
                        val count = sourceStream.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            total += count.toLong()
                            if (total > MAX_OUTPUT_BACKUP_BYTES) {
                                throw IOException("Output backup exceeds size limit")
                            }
                            backupStream.write(buffer, 0, count)
                        }
                    }
                    backupStream.fd.sync()
                }
                saved
            }
        } catch (error: Exception) {
            clearAndDelete(backup)
            throw error
        } finally {
            buffer.fill(0)
        }
    }

    private fun restoreFileToUri(backup: File, destination: Uri): Boolean {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        return try {
            // Open the source first: missing backups must not truncate the target.
            FileInputStream(backup).use { sourceStream ->
                val output = contentResolver.openOutputStream(destination, WRITE_TRUNCATE_MODE)
                    ?: return false
                output.use { destinationStream ->
                    while (true) {
                        val count = sourceStream.read(buffer)
                        if (count < 0) break
                        if (count > 0) destinationStream.write(buffer, 0, count)
                    }
                    destinationStream.flush()
                }
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            buffer.fill(0)
        }
    }

    private fun copyFileToUri(
        source: File,
        destination: Uri,
        progress: CancellableProgressCallback?,
        reportProgress: Boolean,
    ): UriOutputTransaction {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        val expected = source.length().coerceAtLeast(0L)
        var processed = 0L
        val transaction = UriOutputTransaction(destination)

        return try {
            if (progress?.isCancelled() == true) return transaction
            transaction.snapshot()
            if (reportProgress && progress?.onProgress(0L, expected) == false) {
                return transaction
            }
            FileInputStream(source).use { sourceStream ->
                if (progress?.isCancelled() == true) return transaction
                transaction.beginModification()
                val output = contentResolver.openOutputStream(destination, WRITE_TRUNCATE_MODE)
                    ?: throw IOException("Cannot open output")
                output.use { destinationStream ->
                    while (true) {
                        if (progress?.isCancelled() == true) return transaction.fail()
                        val count = sourceStream.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            destinationStream.write(buffer, 0, count)
                            processed += count.toLong()
                            if (reportProgress && progress?.onProgress(processed, expected) == false) {
                                return transaction.fail()
                            }
                        }
                    }
                    destinationStream.flush()
                }
            }
            // A failed close is also a failed transfer, so transition only after use.
            transaction.written()
            transaction
        } catch (_: Exception) {
            transaction.fail()
        } finally {
            buffer.fill(0)
        }
    }

    private fun setAndVerifyPrivateFile(file: File): Boolean {
        val initialStatus = Os.lstat(file.absolutePath)
        if (!OsConstants.S_ISREG(initialStatus.st_mode) ||
            initialStatus.st_uid != Os.getuid() ||
            initialStatus.st_nlink != 1L
        ) {
            return false
        }
        Os.chmod(file.absolutePath, PRIVATE_FILE_MODE)
        val status = Os.lstat(file.absolutePath)
        return OsConstants.S_ISREG(status.st_mode) &&
            status.st_uid == Os.getuid() &&
            status.st_nlink == 1L &&
            (status.st_mode and PERMISSION_MASK) == PRIVATE_FILE_MODE
    }

    private fun clearPrivateTemporaryDirectory(directory: File): Boolean {
        if (!directory.exists()) {
            return true
        }
        val status = try {
            Os.lstat(directory.absolutePath)
        } catch (_: Exception) {
            return false
        }
        if (!OsConstants.S_ISDIR(status.st_mode) ||
            status.st_uid != Os.getuid()
        ) {
            return false
        }
        val entries = directory.listFiles() ?: return false
        for (entry in entries) {
            val entryStatus = try {
                Os.lstat(entry.absolutePath)
            } catch (_: Exception) {
                return false
            }
            if (OsConstants.S_ISDIR(entryStatus.st_mode)) {
                return false
            }
            clearAndDelete(entry)
            if (entry.exists()) {
                return false
            }
        }
        return removeTemporaryDirectoryIfEmpty(directory)
    }

    private fun removeTemporaryDirectoryIfEmpty(directory: File): Boolean {
        if (!directory.exists()) {
            return true
        }
        val entries = directory.listFiles() ?: return false
        if (entries.isNotEmpty()) {
            return true
        }
        val removed = directory.delete()
        if (directory != temporaryKeyDirectory) {
            removeTemporaryDirectoryIfEmpty(temporaryKeyDirectory)
        }
        return removed
    }

    private fun clearAndDelete(file: File?) {
        if (file == null) {
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
        } catch (_: android.system.ErrnoException) {
            // Missing files are already clean; no path or contents are logged.
        } catch (_: Exception) {
            // Deletion is still attempted; no path or contents are logged.
        } finally {
            file.delete()
            activeOutputBackups.remove(file.absolutePath)
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
        val activeOutputBackups = ConcurrentHashMap.newKeySet<String>()
        const val COPY_BUFFER_SIZE = 64 * 1024
        // Plaintext and containers have separate limits, so every file that
        // can be encrypted can also be decrypted again: a container adds
        // 1,716 bytes (v3) or 156 + 1,672 per recipient (v4, up to 64).
        const val MAX_PLAINTEXT_BYTES = 8L * 1024L * 1024L * 1024L
        const val MAX_CONTAINER_OVERHEAD_BYTES = 156L + 1_672L * NativeBridge.MAX_RECIPIENTS
        const val MAX_CONTAINER_BYTES = MAX_PLAINTEXT_BYTES + MAX_CONTAINER_OVERHEAD_BYTES
        const val MAX_OUTPUT_BACKUP_BYTES = MAX_CONTAINER_BYTES
        const val MAX_KEY_IMPORT_BYTES = 16L * 1024L * 1024L
        const val PERMISSION_MASK = 0x1FF
        const val PRIVATE_DIRECTORY_MODE = 0x1C0
        const val PRIVATE_FILE_MODE = 0x180

        const val WORK_DIRECTORY_NAME = "nekokem-work"
        const val WORKSPACE_PREFIX = "op-"
        const val ENCRYPT_INPUT_PREFIX = "nkem-ei-"
        const val ENCRYPT_OUTPUT_PREFIX = "nkem-eo-"
        const val DECRYPT_INPUT_PREFIX = "nkem-di-"
        const val DECRYPT_OUTPUT_PREFIX = "nkem-do-"
        const val PUBLIC_IMPORT_PREFIX = "nkem-pi-"
        const val PRIVATE_IMPORT_PREFIX = "nkem-ki-"
        const val KEY_EXPORT_PREFIX = "nkem-ke-"
        const val OUTPUT_BACKUP_PREFIX = "nkem-output-backup-"
        const val OUTPUT_BACKUP_DIRECTORY_NAME = "nekokem-output-backups"
        const val TEMPORARY_KEY_DIRECTORY_NAME = "nekokem-key-selection"
        const val TEMPORARY_PUBLIC_KEY_DIRECTORY_NAME = "public"
        const val TEMPORARY_PRIVATE_KEY_DIRECTORY_NAME = "private"
        const val TEMPORARY_PUBLIC_RAW_PREFIX = "nkem-selected-pub-raw-"
        const val TEMPORARY_PUBLIC_CANONICAL_PREFIX = "nkem-selected-pub-"
        const val TEMPORARY_PRIVATE_PREFIX = "nkem-selected-prv-"
        const val TEMPORARY_SUFFIX = ".tmp"
        const val RECOVERY_SUFFIX = ".recovery"
        const val TEMPORARY_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
        const val WRITE_TRUNCATE_MODE = "wt"
        const val READ_WRITE_MODE = "rw"
        const val SHA256_ALGORITHM = "SHA-256"
    }
}
