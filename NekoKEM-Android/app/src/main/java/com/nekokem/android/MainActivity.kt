package com.nekokem.android

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nekokem.android.files.PreparedDecryption
import com.nekokem.android.files.PreparedEncryption
import com.nekokem.android.files.SafFileWorkflow
import com.nekokem.android.files.SaveDocumentContract
import com.nekokem.android.files.SelectedDocument
import com.nekokem.android.files.decryptedDocumentRequest
import com.nekokem.android.files.encryptedDocumentRequest
import com.nekokem.android.files.encryptedPrivateKeyExportRequest
import com.nekokem.android.files.publicKeyExportRequest
import com.nekokem.android.keys.LocalKeyManager
import com.nekokem.android.keys.LocalKeyState
import com.nekokem.android.nativecore.NativeBridge
import com.nekokem.android.progress.OperationProgressSnapshot
import com.nekokem.android.progress.OperationProgressTracker
import com.nekokem.android.ui.GeneratePasswordDialog
import com.nekokem.android.ui.NekoKEMActions
import com.nekokem.android.ui.NekoKEMAppShell
import com.nekokem.android.ui.NekoKEMUiState
import com.nekokem.android.ui.OperationCompletedDialog
import com.nekokem.android.ui.OperationErrorDialog
import com.nekokem.android.ui.OperationProgressDialog
import com.nekokem.android.ui.SinglePasswordDialog
import com.nekokem.android.ui.theme.NekoKEMTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keyManager = LocalKeyManager(applicationContext)
        val fileWorkflow = SafFileWorkflow(applicationContext, keyManager)

        setContent {
            NekoKEMTheme {
                val nativeStatus = remember { readNativeStatus() }
                NekoKEMRoute(
                    nativeConnected = nativeStatus.connected,
                    nativeVersion = nativeStatus.version,
                    keyManager = keyManager,
                    fileWorkflow = fileWorkflow,
                )
            }
        }
    }
}

private data class NativeStatus(
    val connected: Boolean,
    val version: String?,
)

private data class CompletedOperation(
    @StringRes val operationResource: Int,
    val fileName: String,
    val progress: OperationProgressSnapshot,
)

private data class FailedOperation(
    @StringRes val operationResource: Int,
    val reason: String,
)

private enum class PendingOutputAction {
    ENCRYPT_FILE,
    SAVE_DECRYPTED_FILE,
    EXPORT_PUBLIC_KEY,
    EXPORT_PRIVATE_KEY,
}

private enum class PendingKeyImport {
    PUBLIC_KEY,
    PRIVATE_KEY,
}

private enum class PasswordPrompt {
    CHECK_PRIVATE_KEY,
    IMPORT_PRIVATE_KEY,
    DECRYPT_FILE,
}

private fun readNativeStatus(): NativeStatus = try {
    NativeBridge.nativeCoreTest()
    NativeStatus(true, NativeBridge.nativeVersion())
} catch (_: LinkageError) {
    NativeStatus(false, null)
}

@Composable
private fun NekoKEMRoute(
    nativeConnected: Boolean,
    nativeVersion: String?,
    keyManager: LocalKeyManager,
    fileWorkflow: SafFileWorkflow,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var keyState by remember { mutableStateOf(LocalKeyState(false, null)) }
    var selectedDocument by remember { mutableStateOf<SelectedDocument?>(null) }
    var pendingOutputAction by remember { mutableStateOf<PendingOutputAction?>(null) }
    var pendingKeyImport by remember { mutableStateOf<PendingKeyImport?>(null) }
    var pendingPrivateImportUri by remember { mutableStateOf<Uri?>(null) }
    var preparedEncryption by remember { mutableStateOf<PreparedEncryption?>(null) }
    var encryptionPreparationProgress by remember {
        mutableStateOf<OperationProgressSnapshot?>(null)
    }
    var preparedDecryption by remember { mutableStateOf<PreparedDecryption?>(null) }
    var decryptionPreparationProgress by remember {
        mutableStateOf<OperationProgressSnapshot?>(null)
    }
    var running by remember { mutableStateOf(false) }
    var showGeneratePasswordDialog by remember { mutableStateOf(false) }
    var passwordPrompt by remember { mutableStateOf<PasswordPrompt?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmPrivateKeyReplacement by remember { mutableStateOf(false) }
    var activeProgressOperation by remember { mutableStateOf<Int?>(null) }
    var activeProgressTracker by remember {
        mutableStateOf<OperationProgressTracker?>(null)
    }
    var progressSnapshot by remember { mutableStateOf(OperationProgressSnapshot()) }
    var progressCancelRequested by remember { mutableStateOf(false) }
    var completedOperation by remember { mutableStateOf<CompletedOperation?>(null) }
    var failedOperation by remember { mutableStateOf<FailedOperation?>(null) }

    fun showSnackbar(@StringRes messageResource: Int) {
        val message = context.getString(messageResource)
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    suspend fun refreshState() {
        keyState = withContext(Dispatchers.IO) {
            try {
                keyManager.readState()
            } catch (_: Exception) {
                LocalKeyState(false, null)
            }
        }
    }

    fun reportFailure(@StringRes operationResource: Int, code: Int) {
        failedOperation = FailedOperation(
            operationResource,
            resultReason(context, operationResource, code),
        )
    }

    fun runKeyOperation(
        @StringRes operationResource: Int,
        sensitiveInput: ByteArray? = null,
        operation: () -> Int,
    ) {
        if (running) {
            sensitiveInput?.fill(0)
            return
        }
        running = true
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { operation() }
            } catch (_: Exception) {
                LocalKeyManager.RESULT_STORAGE_ERROR
            } finally {
                sensitiveInput?.fill(0)
            }
            refreshState()
            if (result == NativeBridge.RESULT_SUCCESS) {
                snackbarHostState.showSnackbar(
                    context.getString(
                        R.string.snackbar_operation_success,
                        context.getString(operationResource),
                    ),
                )
            } else {
                reportFailure(operationResource, result)
            }
            running = false
        }
    }

    fun beginProgress(@StringRes operationResource: Int): OperationProgressTracker {
        lateinit var tracker: OperationProgressTracker
        tracker = OperationProgressTracker { update ->
            scope.launch {
                if (activeProgressTracker === tracker) {
                    progressSnapshot = update
                }
            }
        }
        activeProgressTracker = tracker
        activeProgressOperation = operationResource
        progressSnapshot = OperationProgressSnapshot()
        progressCancelRequested = false
        running = true
        return tracker
    }

    fun endProgress(tracker: OperationProgressTracker) {
        if (activeProgressTracker === tracker) {
            activeProgressTracker = null
            activeProgressOperation = null
            progressCancelRequested = false
        }
        running = false
    }

    fun reportFileResult(
        @StringRes operationResource: Int,
        code: Int,
        progress: OperationProgressSnapshot,
        fileName: String,
    ) {
        when (code) {
            NativeBridge.RESULT_SUCCESS -> completedOperation = CompletedOperation(
                operationResource,
                fileName,
                progress,
            )

            NativeBridge.RESULT_CANCELLED -> showSnackbar(
                R.string.snackbar_operation_cancelled,
            )

            else -> reportFailure(operationResource, code)
        }
    }

    val selectFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            selectedDocument = fileWorkflow.describe(uri)
            showSnackbar(R.string.snackbar_file_selected)
        }
    }

    val importKeyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val action = pendingKeyImport
        pendingKeyImport = null
        if (uri == null || action == null) {
            pendingPrivateImportUri = null
            showSnackbar(R.string.snackbar_key_selection_cancelled)
        } else if (action == PendingKeyImport.PUBLIC_KEY) {
            runKeyOperation(R.string.operation_import_public_key) {
                fileWorkflow.importPublicKey(uri)
            }
        } else {
            pendingPrivateImportUri = uri
            if (keyState.privateKeyExists) {
                confirmPrivateKeyReplacement = true
            } else {
                passwordPrompt = PasswordPrompt.IMPORT_PRIVATE_KEY
            }
        }
    }

    val createOutputLauncher = rememberLauncherForActivityResult(
        SaveDocumentContract(),
    ) { destination ->
        val action = pendingOutputAction
        pendingOutputAction = null

        if (destination == null || action == null) {
            when (action) {
                PendingOutputAction.ENCRYPT_FILE -> {
                    val abandoned = preparedEncryption
                    preparedEncryption = null
                    encryptionPreparationProgress = null
                    running = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                fileWorkflow.discardPreparedEncryption(abandoned)
                            }
                        } finally {
                            running = false
                            showSnackbar(R.string.snackbar_output_selection_cancelled)
                        }
                    }
                }

                PendingOutputAction.SAVE_DECRYPTED_FILE -> {
                    val abandoned = preparedDecryption
                    preparedDecryption = null
                    decryptionPreparationProgress = null
                    running = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                fileWorkflow.discardPreparedDecryption(abandoned)
                            }
                        } finally {
                            running = false
                            showSnackbar(R.string.snackbar_output_selection_cancelled)
                        }
                    }
                }

                PendingOutputAction.EXPORT_PUBLIC_KEY,
                PendingOutputAction.EXPORT_PRIVATE_KEY,
                null -> showSnackbar(R.string.snackbar_output_selection_cancelled)
            }
            return@rememberLauncherForActivityResult
        }

        val destinationName = try {
            fileWorkflow.describe(destination).displayName
        } catch (_: Exception) {
            selectedDocument?.displayName
                ?: context.getString(R.string.default_selected_filename)
        }

        when (action) {
            PendingOutputAction.ENCRYPT_FILE -> {
                val prepared = preparedEncryption
                preparedEncryption = null
                if (prepared == null) {
                    encryptionPreparationProgress = null
                    deleteDocumentQuietly(context, destination)
                    reportFailure(
                        R.string.operation_encrypt_file,
                        LocalKeyManager.RESULT_STORAGE_ERROR,
                    )
                } else {
                    val initial = encryptionPreparationProgress
                    encryptionPreparationProgress = null
                    val tracker = beginProgress(R.string.operation_encrypt_file)
                    scope.launch {
                        val result = try {
                            withContext(Dispatchers.IO) {
                                fileWorkflow.commitPreparedEncryption(
                                    prepared,
                                    destination,
                                    tracker,
                                )
                            }
                        } catch (_: Exception) {
                            deleteDocumentQuietly(context, destination)
                            LocalKeyManager.RESULT_STORAGE_ERROR
                        }
                        val combined = combineProgress(initial, tracker.snapshot())
                        endProgress(tracker)
                        reportFileResult(
                            R.string.operation_encrypt_file,
                            result,
                            combined,
                            destinationName,
                        )
                    }
                }
            }

            PendingOutputAction.SAVE_DECRYPTED_FILE -> {
                val prepared = preparedDecryption
                preparedDecryption = null
                if (prepared == null) {
                    decryptionPreparationProgress = null
                    deleteDocumentQuietly(context, destination)
                    reportFailure(
                        R.string.operation_decrypt_file,
                        LocalKeyManager.RESULT_STORAGE_ERROR,
                    )
                } else {
                    val initial = decryptionPreparationProgress
                    decryptionPreparationProgress = null
                    val tracker = beginProgress(R.string.operation_decrypt_file)
                    scope.launch {
                        val result = try {
                            withContext(Dispatchers.IO) {
                                fileWorkflow.commitPreparedDecryption(
                                    prepared,
                                    destination,
                                    tracker,
                                )
                            }
                        } catch (_: Exception) {
                            deleteDocumentQuietly(context, destination)
                            LocalKeyManager.RESULT_STORAGE_ERROR
                        }
                        val combined = combineProgress(initial, tracker.snapshot())
                        endProgress(tracker)
                        reportFileResult(
                            R.string.operation_decrypt_file,
                            result,
                            combined,
                            destinationName,
                        )
                    }
                }
            }

            PendingOutputAction.EXPORT_PUBLIC_KEY -> {
                runKeyOperation(R.string.operation_export_public_key) {
                    fileWorkflow.exportPublicKey(destination)
                }
            }

            PendingOutputAction.EXPORT_PRIVATE_KEY -> {
                runKeyOperation(R.string.operation_export_private_key) {
                    fileWorkflow.exportEncryptedPrivateKey(destination)
                }
            }
        }
    }

    fun prepareEncryption(source: SelectedDocument) {
        val tracker = beginProgress(R.string.operation_encrypt_file)
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    fileWorkflow.prepareEncryption(source.uri, tracker)
                }
                val finalProgress = tracker.snapshot()
                if (outcome.code == NativeBridge.RESULT_SUCCESS &&
                    outcome.prepared != null
                ) {
                    preparedEncryption = outcome.prepared
                    encryptionPreparationProgress = finalProgress
                    pendingOutputAction = PendingOutputAction.ENCRYPT_FILE
                    endProgress(tracker)
                    createOutputLauncher.launch(
                        encryptedDocumentRequest(
                            source.displayName,
                            context.getString(R.string.default_selected_filename),
                        ),
                    )
                } else {
                    withContext(Dispatchers.IO) {
                        fileWorkflow.discardPreparedEncryption(outcome.prepared)
                    }
                    endProgress(tracker)
                    reportFileResult(
                        R.string.operation_encrypt_file,
                        outcome.code,
                        finalProgress,
                        source.displayName,
                    )
                }
            } catch (_: Exception) {
                val abandoned = preparedEncryption
                preparedEncryption = null
                encryptionPreparationProgress = null
                withContext(Dispatchers.IO) {
                    fileWorkflow.discardPreparedEncryption(abandoned)
                }
                endProgress(tracker)
                reportFailure(
                    R.string.operation_encrypt_file,
                    LocalKeyManager.RESULT_STORAGE_ERROR,
                )
            }
        }
    }

    fun prepareDecryption(password: ByteArray) {
        val source = selectedDocument
        if (source == null) {
            password.fill(0)
            showSnackbar(R.string.error_select_file_first)
            return
        }
        val tracker = beginProgress(R.string.operation_decrypt_file)
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    fileWorkflow.prepareDecryption(source.uri, password, tracker)
                }
                val finalProgress = tracker.snapshot()
                if (outcome.code == NativeBridge.RESULT_SUCCESS &&
                    outcome.prepared != null
                ) {
                    preparedDecryption = outcome.prepared
                    decryptionPreparationProgress = finalProgress
                    pendingOutputAction = PendingOutputAction.SAVE_DECRYPTED_FILE
                    endProgress(tracker)
                    createOutputLauncher.launch(
                        decryptedDocumentRequest(
                            source.displayName,
                            context.getString(R.string.default_selected_filename),
                            context.getString(R.string.default_decrypted_filename),
                        ),
                    )
                } else {
                    withContext(Dispatchers.IO) {
                        fileWorkflow.discardPreparedDecryption(outcome.prepared)
                    }
                    endProgress(tracker)
                    reportFileResult(
                        R.string.operation_decrypt_file,
                        outcome.code,
                        finalProgress,
                        source.displayName,
                    )
                }
            } catch (_: Exception) {
                val abandoned = preparedDecryption
                preparedDecryption = null
                decryptionPreparationProgress = null
                withContext(Dispatchers.IO) {
                    fileWorkflow.discardPreparedDecryption(abandoned)
                }
                endProgress(tracker)
                reportFailure(
                    R.string.operation_decrypt_file,
                    LocalKeyManager.RESULT_STORAGE_ERROR,
                )
            } finally {
                password.fill(0)
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { keyManager.clearWorkCache() }
        refreshState()
    }

    if (showGeneratePasswordDialog) {
        GeneratePasswordDialog(
            onDismiss = { showGeneratePasswordDialog = false },
            onConfirm = { password ->
                showGeneratePasswordDialog = false
                runKeyOperation(
                    operationResource = R.string.operation_generate_keypair,
                    sensitiveInput = password,
                ) {
                    keyManager.generateKeypair(password)
                }
            },
        )
    }

    passwordPrompt?.let { prompt ->
        val title = when (prompt) {
            PasswordPrompt.CHECK_PRIVATE_KEY -> R.string.check_password_title
            PasswordPrompt.IMPORT_PRIVATE_KEY -> R.string.import_password_title
            PasswordPrompt.DECRYPT_FILE -> R.string.decrypt_password_title
        }
        val message = when (prompt) {
            PasswordPrompt.CHECK_PRIVATE_KEY -> R.string.check_password_message
            PasswordPrompt.IMPORT_PRIVATE_KEY -> R.string.import_password_message
            PasswordPrompt.DECRYPT_FILE -> R.string.decrypt_password_message
        }
        val label = if (prompt == PasswordPrompt.DECRYPT_FILE) {
            R.string.decrypt_password_label
        } else {
            R.string.password_label
        }
        SinglePasswordDialog(
            titleResource = title,
            messageResource = message,
            labelResource = label,
            onDismiss = {
                if (prompt == PasswordPrompt.IMPORT_PRIVATE_KEY) {
                    pendingPrivateImportUri = null
                }
                passwordPrompt = null
            },
            onConfirm = { password ->
                passwordPrompt = null
                when (prompt) {
                    PasswordPrompt.CHECK_PRIVATE_KEY -> runKeyOperation(
                        operationResource = R.string.operation_check_password,
                        sensitiveInput = password,
                    ) {
                        keyManager.checkPassword(password)
                    }

                    PasswordPrompt.IMPORT_PRIVATE_KEY -> {
                        val uri = pendingPrivateImportUri
                        pendingPrivateImportUri = null
                        if (uri == null) {
                            password.fill(0)
                            showSnackbar(R.string.snackbar_key_selection_cancelled)
                        } else {
                            runKeyOperation(
                                operationResource =
                                    R.string.operation_import_private_key,
                                sensitiveInput = password,
                            ) {
                                fileWorkflow.importEncryptedPrivateKey(uri, password)
                            }
                        }
                    }

                    PasswordPrompt.DECRYPT_FILE -> prepareDecryption(password)
                }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_private_key_title)) },
            text = { Text(stringResource(R.string.delete_private_key_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        runKeyOperation(R.string.operation_delete_private_key) {
                            keyManager.deletePrivateKey()
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (confirmPrivateKeyReplacement) {
        AlertDialog(
            onDismissRequest = {
                confirmPrivateKeyReplacement = false
                pendingPrivateImportUri = null
            },
            title = { Text(stringResource(R.string.replace_private_key_title)) },
            text = { Text(stringResource(R.string.replace_private_key_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmPrivateKeyReplacement = false
                        if (pendingPrivateImportUri != null) {
                            passwordPrompt = PasswordPrompt.IMPORT_PRIVATE_KEY
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_replace))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmPrivateKeyReplacement = false
                        pendingPrivateImportUri = null
                    },
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    val progressOperation = activeProgressOperation
    val tracker = activeProgressTracker
    if (progressOperation != null && tracker != null) {
        OperationProgressDialog(
            operationResource = progressOperation,
            progress = progressSnapshot,
            cancelRequested = progressCancelRequested,
            onCancel = {
                progressCancelRequested = true
                tracker.cancel()
            },
        )
    }

    completedOperation?.let { result ->
        OperationCompletedDialog(
            operationResource = result.operationResource,
            fileName = result.fileName,
            progress = result.progress,
            onDismiss = { completedOperation = null },
        )
    }

    failedOperation?.let { failure ->
        OperationErrorDialog(
            operationResource = failure.operationResource,
            reason = failure.reason,
            onDismiss = { failedOperation = null },
        )
    }

    NekoKEMAppShell(
        state = NekoKEMUiState(
            nativeConnected = nativeConnected,
            nativeVersion = nativeVersion,
            privateKeyExists = keyState.privateKeyExists,
            fingerprint = keyState.fingerprint,
            selectedFileName = selectedDocument?.displayName,
            running = running,
        ),
        actions = NekoKEMActions(
            onGenerate = { showGeneratePasswordDialog = true },
            onCheckPassword = {
                passwordPrompt = PasswordPrompt.CHECK_PRIVATE_KEY
            },
            onExportPublicKey = {
                pendingOutputAction = PendingOutputAction.EXPORT_PUBLIC_KEY
                createOutputLauncher.launch(publicKeyExportRequest())
            },
            onExportPrivateKey = {
                pendingOutputAction = PendingOutputAction.EXPORT_PRIVATE_KEY
                createOutputLauncher.launch(encryptedPrivateKeyExportRequest())
            },
            onImportPublicKey = {
                pendingKeyImport = PendingKeyImport.PUBLIC_KEY
                importKeyLauncher.launch(arrayOf(ANY_MIME_TYPE))
            },
            onImportPrivateKey = {
                pendingKeyImport = PendingKeyImport.PRIVATE_KEY
                importKeyLauncher.launch(arrayOf(ANY_MIME_TYPE))
            },
            onDeletePrivateKey = { confirmDelete = true },
            onSelectFile = {
                selectFileLauncher.launch(arrayOf(ANY_MIME_TYPE))
            },
            onEncrypt = {
                val source = selectedDocument
                when {
                    source == null -> showSnackbar(R.string.error_select_file_first)
                    keyState.fingerprint == null -> showSnackbar(
                        R.string.error_public_key_not_found,
                    )
                    else -> prepareEncryption(source)
                }
            },
            onDecrypt = {
                when {
                    selectedDocument == null -> showSnackbar(
                        R.string.error_select_file_first,
                    )
                    !keyState.privateKeyExists -> showSnackbar(
                        R.string.error_private_key_not_found,
                    )
                    else -> passwordPrompt = PasswordPrompt.DECRYPT_FILE
                }
            },
        ),
        snackbarHostState = snackbarHostState,
    )
}

private fun combineProgress(
    first: OperationProgressSnapshot?,
    second: OperationProgressSnapshot,
): OperationProgressSnapshot {
    if (first == null) {
        return second
    }
    return OperationProgressSnapshot(
        processedBytes = first.processedBytes + second.processedBytes,
        totalBytes = first.totalBytes + second.totalBytes,
        elapsedMillis = first.elapsedMillis + second.elapsedMillis,
    )
}

private fun deleteDocumentQuietly(context: Context, destination: Uri) {
    try {
        context.contentResolver.delete(destination, null, null)
    } catch (_: Exception) {
        // File names and provider details are intentionally not logged.
    }
}

private fun resultReason(
    context: Context,
    @StringRes operationResource: Int,
    code: Int,
): String {
    val reasonResource = when (code) {
        LocalKeyManager.RESULT_STORAGE_ERROR -> R.string.error_reason_storage
        NativeBridge.RESULT_INVALID_ARGUMENT -> R.string.error_reason_invalid_argument
        NativeBridge.RESULT_ALLOCATION_ERROR -> R.string.error_reason_memory
        NativeBridge.RESULT_JAVA_EXCEPTION -> R.string.error_reason_jni
        else -> when (operationResource) {
            R.string.operation_generate_keypair ->
                R.string.error_reason_key_generation
            R.string.operation_check_password,
            R.string.operation_import_private_key ->
                R.string.error_reason_authentication
            R.string.operation_encrypt_file -> R.string.error_reason_encrypt
            R.string.operation_decrypt_file -> R.string.error_reason_decrypt
            R.string.operation_import_public_key ->
                R.string.error_reason_public_key
            R.string.operation_export_public_key,
            R.string.operation_export_private_key -> R.string.error_reason_export
            R.string.operation_delete_private_key -> R.string.error_reason_delete
            else -> R.string.error_reason_core
        }
    }
    return context.getString(reasonResource)
}

private const val ANY_MIME_TYPE = "*/*"

