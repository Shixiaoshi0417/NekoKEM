package com.shixiaoshi0417.nekokem

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.shixiaoshi0417.nekokem.files.PreparedDecryption
import com.shixiaoshi0417.nekokem.files.PreparedEncryption
import com.shixiaoshi0417.nekokem.files.SafFileWorkflow
import com.shixiaoshi0417.nekokem.files.SaveDocumentContract
import com.shixiaoshi0417.nekokem.files.SelectedDocument
import com.shixiaoshi0417.nekokem.files.decryptedDocumentRequest
import com.shixiaoshi0417.nekokem.files.encryptedDocumentRequest
import com.shixiaoshi0417.nekokem.files.encryptedPrivateKeyExportRequest
import com.shixiaoshi0417.nekokem.files.publicKeyExportRequest
import com.shixiaoshi0417.nekokem.keys.LocalKeyManager
import com.shixiaoshi0417.nekokem.keys.LocalKeyState
import com.shixiaoshi0417.nekokem.keys.PendingTemporaryPrivateKey
import com.shixiaoshi0417.nekokem.keys.TemporaryPrivateKey
import com.shixiaoshi0417.nekokem.keys.TemporaryPublicKey
import com.shixiaoshi0417.nekokem.nativecore.NativeBridge
import com.shixiaoshi0417.nekokem.progress.OperationProgressSnapshot
import com.shixiaoshi0417.nekokem.progress.OperationProgressTracker
import com.shixiaoshi0417.nekokem.ui.GeneratePasswordDialog
import com.shixiaoshi0417.nekokem.ui.FileInputState
import com.shixiaoshi0417.nekokem.ui.InputSelectionEvent
import com.shixiaoshi0417.nekokem.ui.NekoKEMActions
import com.shixiaoshi0417.nekokem.ui.NekoKEMAppShell
import com.shixiaoshi0417.nekokem.ui.NekoKEMUiState
import com.shixiaoshi0417.nekokem.ui.OperationCompletedDialog
import com.shixiaoshi0417.nekokem.ui.OperationErrorDialog
import com.shixiaoshi0417.nekokem.ui.OperationProgressDialog
import com.shixiaoshi0417.nekokem.ui.OperationResultDetail
import com.shixiaoshi0417.nekokem.ui.SinglePasswordDialog
import com.shixiaoshi0417.nekokem.ui.runWithUiBusyReset
import com.shixiaoshi0417.nekokem.ui.theme.NekoKEMTheme
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
    val details: List<OperationResultDetail>,
    val progress: OperationProgressSnapshot? = null,
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
    SELECT_TEMPORARY_PRIVATE_KEY,
    DECRYPT_FILE,
}

private enum class DeleteTarget(
    @StringRes val titleResource: Int,
    @StringRes val messageResource: Int,
    @StringRes val operationResource: Int,
) {
    PUBLIC_KEY(
        R.string.delete_public_key_title,
        R.string.delete_public_key_message,
        R.string.operation_delete_public_key,
    ),
    PRIVATE_KEY(
        R.string.delete_private_key_title,
        R.string.delete_private_key_message,
        R.string.operation_delete_private_key,
    ),
    KEYPAIR(
        R.string.delete_keypair_title,
        R.string.delete_keypair_message,
        R.string.operation_delete_keypair,
    ),
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
    var selectedInputState by rememberSaveable(
        stateSaver = FileInputState.Saver,
    ) {
        mutableStateOf(FileInputState())
    }
    var pendingOutputAction by remember { mutableStateOf<PendingOutputAction?>(null) }
    var pendingKeyImport by remember { mutableStateOf<PendingKeyImport?>(null) }
    var pendingPrivateImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingTemporaryPrivateKey by remember {
        mutableStateOf<PendingTemporaryPrivateKey?>(null)
    }
    var temporaryPublicKey by remember {
        mutableStateOf<TemporaryPublicKey?>(null)
    }
    var temporaryPrivateKey by remember {
        mutableStateOf<TemporaryPrivateKey?>(null)
    }
    var publicKeyAwaitingConfirmation by remember {
        mutableStateOf<TemporaryPublicKey?>(null)
    }
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
    var pendingDelete by remember { mutableStateOf<DeleteTarget?>(null) }
    var confirmPrivateKeyReplacement by remember { mutableStateOf(false) }
    var activeProgressOperation by remember { mutableStateOf<Int?>(null) }
    var activeProgressTracker by remember {
        mutableStateOf<OperationProgressTracker?>(null)
    }
    var progressSnapshot by remember { mutableStateOf(OperationProgressSnapshot()) }
    var progressCancelRequested by remember { mutableStateOf(false) }
    var completedOperation by remember { mutableStateOf<CompletedOperation?>(null) }
    var failedOperation by remember { mutableStateOf<FailedOperation?>(null) }
    val selectedInputUri = selectedInputState.uriString?.let(Uri::parse)
    val selectedInputName = selectedInputState.displayName
    val selectedDocument = if (
        selectedInputState.isSelected && selectedInputUri != null
    ) {
        SelectedDocument(
            uri = selectedInputUri,
            displayName = checkNotNull(selectedInputName),
        )
    } else {
        null
    }

    fun transitionInputSelection(
        event: InputSelectionEvent,
        uri: Uri? = null,
        displayName: String? = null,
    ) {
        selectedInputState = selectedInputState.transition(
            event = event,
            newUriString = uri?.toString(),
            newDisplayName = displayName,
        )
    }

    fun clearFileSelection(
        event: InputSelectionEvent = InputSelectionEvent.EXPLICIT_CLEAR,
    ) {
        transitionInputSelection(event)
    }

    fun resetOperationState() {
        completedOperation = null
        failedOperation = null
    }

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

    fun reportFailureReason(
        @StringRes operationResource: Int,
        @StringRes reasonResource: Int,
    ) {
        failedOperation = FailedOperation(
            operationResource,
            context.getString(reasonResource),
        )
    }

    fun keyCompletionDetails(
        fileName: String,
        fingerprint: String? = null,
    ): List<OperationResultDetail> = buildList {
        add(OperationResultDetail(R.string.completion_file, fileName))
        fingerprint?.let { value ->
            add(
                OperationResultDetail(
                    R.string.completion_fingerprint,
                    value,
                ),
            )
        }
    }

    fun runKeyOperation(
        @StringRes operationResource: Int,
        sensitiveInput: ByteArray? = null,
        completionDetails: (LocalKeyState) -> List<OperationResultDetail> = {
            emptyList()
        },
        operation: () -> Int,
    ) {
        if (running) {
            sensitiveInput?.fill(0)
            return
        }
        scope.launch {
            val result = try {
                runWithUiBusyReset(
                    setBusy = { running = it },
                    cleanup = { sensitiveInput?.fill(0) },
                ) {
                    withContext(Dispatchers.IO) { operation() }
                }
            } catch (_: Exception) {
                LocalKeyManager.RESULT_STORAGE_ERROR
            }
            if (result == NativeBridge.RESULT_SUCCESS) {
                completedOperation = CompletedOperation(
                    operationResource = operationResource,
                    details = emptyList(),
                )
            } else {
                reportFailure(operationResource, result)
            }
            refreshState()
            if (result == NativeBridge.RESULT_SUCCESS) {
                completedOperation = CompletedOperation(
                    operationResource = operationResource,
                    details = completionDetails(keyState),
                )
            }
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
            NativeBridge.RESULT_SUCCESS -> clearFileSelection(
                if (operationResource == R.string.operation_encrypt_file) {
                    InputSelectionEvent.ENCRYPTION_SUCCEEDED
                } else {
                    InputSelectionEvent.DECRYPTION_SUCCEEDED
                },
            )
            NativeBridge.RESULT_CANCELLED -> transitionInputSelection(
                InputSelectionEvent.OPERATION_CANCELLED,
            )
            else -> transitionInputSelection(
                InputSelectionEvent.OPERATION_FAILED,
            )
        }
        when (code) {
            NativeBridge.RESULT_SUCCESS -> completedOperation = CompletedOperation(
                operationResource = operationResource,
                details = listOf(
                    OperationResultDetail(
                        R.string.completion_file,
                        fileName,
                    ),
                ),
                progress = progress,
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
            val document = fileWorkflow.describe(uri)
            transitionInputSelection(
                InputSelectionEvent.SELECT_NEW,
                document.uri,
                document.displayName,
            )
        }
    }

    val importKeyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val action = pendingKeyImport
        pendingKeyImport = null
        if (uri == null || action == null) {
            pendingPrivateImportUri = null
            showSnackbar(R.string.snackbar_operation_cancelled)
        } else if (action == PendingKeyImport.PUBLIC_KEY) {
            val displayName = try {
                fileWorkflow.describe(uri).displayName
            } catch (_: Exception) {
                context.getString(R.string.default_public_key_filename)
            }
            runKeyOperation(
                operationResource = R.string.operation_import_public_key,
                completionDetails = { state ->
                    keyCompletionDetails(displayName, state.fingerprint)
                },
            ) {
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

    val selectTemporaryPublicKeyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            showSnackbar(R.string.snackbar_operation_cancelled)
        } else if (!running) {
            val document = fileWorkflow.describe(uri)
            running = true
            scope.launch {
                val outcome = try {
                    withContext(Dispatchers.IO) {
                        fileWorkflow.stageTemporaryPublicKey(
                            uri,
                            document.displayName,
                        )
                    }
                } catch (_: Exception) {
                    null
                } finally {
                    running = false
                }
                if (outcome?.code == NativeBridge.RESULT_SUCCESS &&
                    outcome.key != null
                ) {
                    publicKeyAwaitingConfirmation = outcome.key
                } else {
                    outcome?.key?.let { key ->
                        withContext(Dispatchers.IO) {
                            fileWorkflow.discardTemporaryPublicKey(key)
                        }
                    }
                    reportFailure(
                        R.string.operation_select_temporary_public_key,
                        outcome?.code ?: LocalKeyManager.RESULT_STORAGE_ERROR,
                    )
                }
            }
        }
    }

    val selectTemporaryPrivateKeyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            showSnackbar(R.string.snackbar_operation_cancelled)
        } else if (!running) {
            val document = fileWorkflow.describe(uri)
            running = true
            scope.launch {
                val outcome = try {
                    withContext(Dispatchers.IO) {
                        fileWorkflow.stageTemporaryPrivateKey(
                            uri,
                            document.displayName,
                        )
                    }
                } catch (_: Exception) {
                    null
                } finally {
                    running = false
                }
                if (outcome?.code == NativeBridge.RESULT_SUCCESS &&
                    outcome.key != null
                ) {
                    pendingTemporaryPrivateKey = outcome.key
                    passwordPrompt = PasswordPrompt.SELECT_TEMPORARY_PRIVATE_KEY
                } else {
                    outcome?.key?.let { key ->
                        withContext(Dispatchers.IO) {
                            fileWorkflow.discardPendingTemporaryPrivateKey(key)
                        }
                    }
                    reportFailure(
                        R.string.operation_select_temporary_private_key,
                        outcome?.code ?: LocalKeyManager.RESULT_STORAGE_ERROR,
                    )
                }
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
                    transitionInputSelection(
                        InputSelectionEvent.OPERATION_CANCELLED,
                    )
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
                            showSnackbar(R.string.snackbar_operation_cancelled)
                        }
                    }
                }

                PendingOutputAction.SAVE_DECRYPTED_FILE -> {
                    transitionInputSelection(
                        InputSelectionEvent.OPERATION_CANCELLED,
                    )
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
                            showSnackbar(R.string.snackbar_operation_cancelled)
                        }
                    }
                }

                PendingOutputAction.EXPORT_PUBLIC_KEY,
                PendingOutputAction.EXPORT_PRIVATE_KEY,
                null -> showSnackbar(R.string.snackbar_operation_cancelled)
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
                        } finally {
                            endProgress(tracker)
                        }
                        val combined = combineProgress(initial, tracker.snapshot())
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
                        } finally {
                            endProgress(tracker)
                        }
                        val combined = combineProgress(initial, tracker.snapshot())
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
                runKeyOperation(
                    operationResource = R.string.operation_export_public_key,
                    completionDetails = { state ->
                        keyCompletionDetails(
                            destinationName,
                            state.fingerprint,
                        )
                    },
                ) {
                    fileWorkflow.exportPublicKey(destination)
                }
            }

            PendingOutputAction.EXPORT_PRIVATE_KEY -> {
                runKeyOperation(
                    operationResource = R.string.operation_export_private_key,
                    completionDetails = {
                        keyCompletionDetails(destinationName)
                    },
                ) {
                    fileWorkflow.exportEncryptedPrivateKey(destination)
                }
            }
        }
    }

    fun prepareEncryption(source: SelectedDocument) {
        val selectedKey = temporaryPublicKey
        val tracker = beginProgress(R.string.operation_encrypt_file)
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    fileWorkflow.prepareEncryption(
                        source.uri,
                        tracker,
                        selectedKey,
                    )
                }
                if (temporaryPublicKey === selectedKey) {
                    temporaryPublicKey = null
                }
                val finalProgress = tracker.snapshot()
                if (outcome.code == NativeBridge.RESULT_SUCCESS &&
                    outcome.prepared != null
                ) {
                    preparedEncryption = outcome.prepared
                    encryptionPreparationProgress = finalProgress
                    pendingOutputAction = PendingOutputAction.ENCRYPT_FILE
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
                    reportFileResult(
                        R.string.operation_encrypt_file,
                        outcome.code,
                        finalProgress,
                        source.displayName,
                    )
                }
            } catch (_: Exception) {
                withContext(Dispatchers.IO) {
                    fileWorkflow.discardTemporaryPublicKey(selectedKey)
                }
                if (temporaryPublicKey === selectedKey) {
                    temporaryPublicKey = null
                }
                val abandoned = preparedEncryption
                preparedEncryption = null
                encryptionPreparationProgress = null
                withContext(Dispatchers.IO) {
                    fileWorkflow.discardPreparedEncryption(abandoned)
                }
                reportFailure(
                    R.string.operation_encrypt_file,
                    LocalKeyManager.RESULT_STORAGE_ERROR,
                )
            } finally {
                endProgress(tracker)
            }
        }
    }

    fun prepareDecryption(password: ByteArray) {
        val source = selectedDocument
        val selectedKey = temporaryPrivateKey
        if (source == null) {
            password.fill(0)
            reportFailureReason(
                R.string.operation_decrypt_file,
                R.string.error_select_file_first,
            )
            return
        }
        val tracker = beginProgress(R.string.operation_decrypt_file)
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    fileWorkflow.prepareDecryption(
                        source.uri,
                        password,
                        tracker,
                        selectedKey,
                    )
                }
                if (temporaryPrivateKey === selectedKey) {
                    temporaryPrivateKey = null
                }
                val finalProgress = tracker.snapshot()
                if (outcome.code == NativeBridge.RESULT_SUCCESS &&
                    outcome.prepared != null
                ) {
                    preparedDecryption = outcome.prepared
                    decryptionPreparationProgress = finalProgress
                    pendingOutputAction = PendingOutputAction.SAVE_DECRYPTED_FILE
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
                    reportFileResult(
                        R.string.operation_decrypt_file,
                        outcome.code,
                        finalProgress,
                        source.displayName,
                    )
                }
            } catch (_: Exception) {
                withContext(Dispatchers.IO) {
                    fileWorkflow.discardTemporaryPrivateKey(selectedKey)
                }
                if (temporaryPrivateKey === selectedKey) {
                    temporaryPrivateKey = null
                }
                val abandoned = preparedDecryption
                preparedDecryption = null
                decryptionPreparationProgress = null
                withContext(Dispatchers.IO) {
                    fileWorkflow.discardPreparedDecryption(abandoned)
                }
                reportFailure(
                    R.string.operation_decrypt_file,
                    LocalKeyManager.RESULT_STORAGE_ERROR,
                )
            } finally {
                password.fill(0)
                endProgress(tracker)
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            keyManager.clearWorkCache()
            fileWorkflow.clearTemporaryKeyCache()
        }
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
                    completionDetails = { state ->
                        keyCompletionDetails(
                            context.getString(
                                R.string.completion_keypair_files,
                                context.getString(
                                    R.string.default_public_key_filename,
                                ),
                                context.getString(
                                    R.string.default_private_key_filename,
                                ),
                            ),
                            state.fingerprint,
                        )
                    },
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
            PasswordPrompt.SELECT_TEMPORARY_PRIVATE_KEY ->
                R.string.select_temporary_private_key_password_title
            PasswordPrompt.DECRYPT_FILE -> R.string.decrypt_password_title
        }
        val message = when (prompt) {
            PasswordPrompt.CHECK_PRIVATE_KEY -> R.string.check_password_message
            PasswordPrompt.IMPORT_PRIVATE_KEY -> R.string.import_password_message
            PasswordPrompt.SELECT_TEMPORARY_PRIVATE_KEY ->
                R.string.select_temporary_private_key_password_message
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
                } else if (
                    prompt == PasswordPrompt.SELECT_TEMPORARY_PRIVATE_KEY
                ) {
                    val abandoned = pendingTemporaryPrivateKey
                    pendingTemporaryPrivateKey = null
                    scope.launch(Dispatchers.IO) {
                        fileWorkflow.discardPendingTemporaryPrivateKey(abandoned)
                    }
                }
                passwordPrompt = null
            },
            onConfirm = { password ->
                passwordPrompt = null
                when (prompt) {
                    PasswordPrompt.CHECK_PRIVATE_KEY -> runKeyOperation(
                        operationResource = R.string.operation_check_password,
                        sensitiveInput = password,
                        completionDetails = {
                            keyCompletionDetails(
                                context.getString(
                                    R.string.default_private_key_filename,
                                ),
                            )
                        },
                    ) {
                        keyManager.checkPassword(password)
                    }

                    PasswordPrompt.IMPORT_PRIVATE_KEY -> {
                        val uri = pendingPrivateImportUri
                        pendingPrivateImportUri = null
                        if (uri == null) {
                            password.fill(0)
                            showSnackbar(R.string.snackbar_operation_cancelled)
                        } else {
                            val displayName = try {
                                fileWorkflow.describe(uri).displayName
                            } catch (_: Exception) {
                                context.getString(
                                    R.string.default_private_key_filename,
                                )
                            }
                            runKeyOperation(
                                operationResource =
                                    R.string.operation_import_private_key,
                                sensitiveInput = password,
                                completionDetails = {
                                    keyCompletionDetails(displayName)
                                },
                            ) {
                                fileWorkflow.importEncryptedPrivateKey(uri, password)
                            }
                        }
                    }

                    PasswordPrompt.SELECT_TEMPORARY_PRIVATE_KEY -> {
                        val pending = pendingTemporaryPrivateKey
                        pendingTemporaryPrivateKey = null
                        if (pending == null) {
                            password.fill(0)
                            showSnackbar(R.string.snackbar_operation_cancelled)
                        } else {
                            running = true
                            scope.launch {
                                val outcome = try {
                                    withContext(Dispatchers.IO) {
                                        fileWorkflow.validateTemporaryPrivateKey(
                                            pending,
                                            password,
                                        )
                                    }
                                } catch (_: Exception) {
                                    null
                                } finally {
                                    password.fill(0)
                                    running = false
                                }
                                if (outcome?.code ==
                                    NativeBridge.RESULT_SUCCESS &&
                                    outcome.key != null
                                ) {
                                    val previous = temporaryPrivateKey
                                    withContext(Dispatchers.IO) {
                                        fileWorkflow.discardTemporaryPrivateKey(
                                            previous,
                                        )
                                    }
                                    temporaryPrivateKey = outcome.key
                                    transitionInputSelection(
                                        InputSelectionEvent.KEY_SELECTION_CHANGED,
                                    )
                                    showSnackbar(
                                        R.string.snackbar_temporary_private_key_selected,
                                    )
                                } else {
                                    outcome?.key?.let { key ->
                                        withContext(Dispatchers.IO) {
                                            fileWorkflow.discardTemporaryPrivateKey(
                                                key,
                                            )
                                        }
                                    }
                                    reportFailure(
                                        R.string.operation_select_temporary_private_key,
                                        outcome?.code
                                            ?: LocalKeyManager.RESULT_STORAGE_ERROR,
                                    )
                                }
                            }
                        }
                    }

                    PasswordPrompt.DECRYPT_FILE -> prepareDecryption(password)
                }
            },
        )
    }

    publicKeyAwaitingConfirmation?.let { candidate ->
        AlertDialog(
            onDismissRequest = {
                publicKeyAwaitingConfirmation = null
                scope.launch(Dispatchers.IO) {
                    fileWorkflow.discardTemporaryPublicKey(candidate)
                }
            },
            title = {
                Text(stringResource(R.string.confirm_temporary_public_key_title))
            },
            text = {
                Text(
                    stringResource(
                        R.string.confirm_temporary_public_key_message,
                        candidate.displayName,
                        candidate.fingerprint,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        publicKeyAwaitingConfirmation = null
                        val previous = temporaryPublicKey
                        temporaryPublicKey = candidate
                        transitionInputSelection(
                            InputSelectionEvent.KEY_SELECTION_CHANGED,
                        )
                        scope.launch(Dispatchers.IO) {
                            fileWorkflow.discardTemporaryPublicKey(previous)
                        }
                        showSnackbar(
                            R.string.snackbar_temporary_public_key_selected,
                        )
                    },
                ) {
                    Text(stringResource(R.string.action_use_key))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        publicKeyAwaitingConfirmation = null
                        scope.launch(Dispatchers.IO) {
                            fileWorkflow.discardTemporaryPublicKey(candidate)
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(target.titleResource)) },
            text = { Text(stringResource(target.messageResource)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        val fileName = when (target) {
                            DeleteTarget.PUBLIC_KEY -> context.getString(
                                R.string.default_public_key_filename,
                            )
                            DeleteTarget.PRIVATE_KEY -> context.getString(
                                R.string.default_private_key_filename,
                            )
                            DeleteTarget.KEYPAIR -> context.getString(
                                R.string.completion_keypair_files,
                                context.getString(
                                    R.string.default_public_key_filename,
                                ),
                                context.getString(
                                    R.string.default_private_key_filename,
                                ),
                            )
                        }
                        runKeyOperation(
                            operationResource = target.operationResource,
                            completionDetails = {
                                keyCompletionDetails(fileName)
                            },
                        ) {
                            when (target) {
                                DeleteTarget.PUBLIC_KEY ->
                                    keyManager.deletePublicKey()
                                DeleteTarget.PRIVATE_KEY ->
                                    keyManager.deletePrivateKey()
                                DeleteTarget.KEYPAIR ->
                                    keyManager.deleteKeypair()
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
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
            details = result.details,
            progress = result.progress,
            onDismiss = { resetOperationState() },
        )
    }

    failedOperation?.let { failure ->
        OperationErrorDialog(
            operationResource = failure.operationResource,
            reason = failure.reason,
            onDismiss = { resetOperationState() },
        )
    }

    NekoKEMAppShell(
        state = NekoKEMUiState(
            nativeConnected = nativeConnected,
            nativeVersion = nativeVersion,
            privateKeyExists = keyState.privateKeyExists,
            fingerprint = keyState.fingerprint,
            selectedFileName = selectedInputName,
            publicKeyTemporary = temporaryPublicKey != null,
            publicKeyFileName = temporaryPublicKey?.displayName
                ?: context.getString(R.string.default_public_key_filename),
            publicKeyFingerprint = temporaryPublicKey?.fingerprint
                ?: keyState.fingerprint,
            privateKeyTemporary = temporaryPrivateKey != null,
            privateKeyFileName = temporaryPrivateKey?.displayName
                ?: context.getString(R.string.default_private_key_filename),
            privateKeyFingerprint = temporaryPrivateKey?.fingerprint
                ?: keyState.fingerprint,
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
            onDeletePublicKey = {
                pendingDelete = DeleteTarget.PUBLIC_KEY
            },
            onDeletePrivateKey = {
                pendingDelete = DeleteTarget.PRIVATE_KEY
            },
            onDeleteKeypair = {
                pendingDelete = DeleteTarget.KEYPAIR
            },
            onSelectFile = {
                selectFileLauncher.launch(arrayOf(ANY_MIME_TYPE))
            },
            onClearSelectedFile = { clearFileSelection() },
            onSelectTemporaryPublicKey = {
                selectTemporaryPublicKeyLauncher.launch(
                    arrayOf(ANY_MIME_TYPE),
                )
            },
            onRestoreDefaultPublicKey = {
                val abandoned = temporaryPublicKey
                temporaryPublicKey = null
                transitionInputSelection(
                    InputSelectionEvent.KEY_SELECTION_CHANGED,
                )
                scope.launch(Dispatchers.IO) {
                    fileWorkflow.discardTemporaryPublicKey(abandoned)
                }
                showSnackbar(R.string.snackbar_default_public_key_restored)
            },
            onSelectTemporaryPrivateKey = {
                selectTemporaryPrivateKeyLauncher.launch(
                    arrayOf(ANY_MIME_TYPE),
                )
            },
            onRestoreDefaultPrivateKey = {
                val abandoned = temporaryPrivateKey
                temporaryPrivateKey = null
                transitionInputSelection(
                    InputSelectionEvent.KEY_SELECTION_CHANGED,
                )
                scope.launch(Dispatchers.IO) {
                    fileWorkflow.discardTemporaryPrivateKey(abandoned)
                }
                showSnackbar(R.string.snackbar_default_private_key_restored)
            },
            onEncrypt = {
                val source = selectedDocument
                when {
                    source == null -> reportFailureReason(
                        R.string.operation_encrypt_file,
                        R.string.error_select_file_first,
                    )
                    temporaryPublicKey == null &&
                        keyState.fingerprint == null -> reportFailureReason(
                        R.string.operation_encrypt_file,
                        R.string.error_public_key_not_found,
                    )
                    else -> prepareEncryption(source)
                }
            },
            onDecrypt = {
                when {
                    selectedDocument == null -> reportFailureReason(
                        R.string.operation_decrypt_file,
                        R.string.error_select_file_first,
                    )
                    temporaryPrivateKey == null &&
                        !keyState.privateKeyExists -> reportFailureReason(
                        R.string.operation_decrypt_file,
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
        LocalKeyManager.RESULT_STORAGE_ERROR -> when (operationResource) {
            R.string.operation_delete_public_key,
            R.string.operation_delete_private_key,
            R.string.operation_delete_keypair -> R.string.error_reason_delete
            else -> R.string.error_reason_storage
        }
        LocalKeyManager.RESULT_FINGERPRINT_MISMATCH ->
            R.string.error_reason_fingerprint_mismatch
        LocalKeyManager.RESULT_PUBLIC_KEY_COPY_FAILED ->
            R.string.error_reason_public_key_copy
        LocalKeyManager.RESULT_PUBLIC_KEY_PARSE_FAILED ->
            R.string.error_reason_public_key_parse
        LocalKeyManager.RESULT_PUBLIC_KEY_NORMALIZE_FAILED ->
            R.string.error_reason_public_key_normalize
        LocalKeyManager.RESULT_PUBLIC_KEY_PERMISSION_FAILED ->
            R.string.error_reason_public_key_permission
        LocalKeyManager.RESULT_PUBLIC_KEY_COMMIT_FAILED ->
            R.string.error_reason_public_key_commit
        LocalKeyManager.RESULT_PUBLIC_KEY_INTEGRITY_FAILED ->
            R.string.error_reason_public_key_integrity
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
            R.string.operation_select_temporary_public_key ->
                R.string.error_reason_public_key
            R.string.operation_select_temporary_private_key ->
                R.string.error_reason_authentication
            R.string.operation_export_public_key,
            R.string.operation_export_private_key -> R.string.error_reason_export
            R.string.operation_delete_public_key,
            R.string.operation_delete_private_key,
            R.string.operation_delete_keypair -> R.string.error_reason_delete
            else -> R.string.error_reason_core
        }
    }
    return context.getString(reasonResource)
}

private const val ANY_MIME_TYPE = "*/*"
