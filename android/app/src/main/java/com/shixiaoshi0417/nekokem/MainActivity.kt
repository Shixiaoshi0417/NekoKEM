package com.shixiaoshi0417.nekokem

import android.content.Context
import com.shixiaoshi0417.nekokem.i18n.AppLanguages
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.nekokem.files.PageCache
import com.shixiaoshi0417.nekokem.files.PreparedDecryption
import com.shixiaoshi0417.nekokem.files.PreparedEncryption
import com.shixiaoshi0417.nekokem.files.SafFileWorkflow
import com.shixiaoshi0417.nekokem.files.SaveDocumentContract
import com.shixiaoshi0417.nekokem.files.SelectedDocument
import com.shixiaoshi0417.nekokem.files.decryptedDocumentRequest
import com.shixiaoshi0417.nekokem.files.encryptedDocumentRequest
import com.shixiaoshi0417.nekokem.files.encryptedPrivateKeyExportRequest
import com.shixiaoshi0417.nekokem.files.publicKeyExportRequest
import com.shixiaoshi0417.nekokem.files.legacyPublicKeyExportRequest
import com.shixiaoshi0417.nekokem.keys.SignatureReport
import com.shixiaoshi0417.nekokem.ui.SignatureSettings
import com.shixiaoshi0417.nekokem.ui.SignatureSettingsStore
import com.shixiaoshi0417.nekokem.ui.UnsignedEncryptionWarningDialog
import com.shixiaoshi0417.nekokem.ui.UpdateAvailableDialog
import com.shixiaoshi0417.nekokem.keys.LocalKeyManager
import com.shixiaoshi0417.nekokem.keys.LocalKeyState
import com.shixiaoshi0417.nekokem.keys.PublicKeyContact
import com.shixiaoshi0417.nekokem.keys.PublicKeyContacts
import com.shixiaoshi0417.nekokem.keys.PublicKeyContactsState
import com.shixiaoshi0417.nekokem.keys.PendingTemporaryPrivateKey
import com.shixiaoshi0417.nekokem.keys.TemporaryPrivateKey
import com.shixiaoshi0417.nekokem.keys.TemporaryPublicKey
import com.shixiaoshi0417.nekokem.nativecore.NativeBridge
import com.shixiaoshi0417.nekokem.progress.OperationProgressSnapshot
import com.shixiaoshi0417.nekokem.progress.OperationProgressTracker
import com.shixiaoshi0417.nekokem.ui.AppAlertDialog
import com.shixiaoshi0417.nekokem.ui.DialogButton
import com.shixiaoshi0417.nekokem.ui.GeneratePasswordDialog
import com.shixiaoshi0417.nekokem.ui.FileInputState
import com.shixiaoshi0417.nekokem.ui.InputSelectionEvent
import com.shixiaoshi0417.nekokem.ui.NekoKEMActions
import com.shixiaoshi0417.nekokem.ui.NekoKEMAppShell
import com.shixiaoshi0417.nekokem.ui.UiStyle
import com.shixiaoshi0417.nekokem.ui.UiStyles
import com.shixiaoshi0417.nekokem.ui.NekoKEMUiState
import com.shixiaoshi0417.nekokem.ui.OperationCompletedDialog
import com.shixiaoshi0417.nekokem.ui.OperationErrorDialog
import com.shixiaoshi0417.nekokem.ui.OperationProgressDialog
import com.shixiaoshi0417.nekokem.ui.OperationResultDetail
import com.shixiaoshi0417.nekokem.ui.SinglePasswordDialog
import com.shixiaoshi0417.nekokem.ui.PublicKeyContactPicker
import com.shixiaoshi0417.nekokem.ui.UnverifiedContactDialog
import com.shixiaoshi0417.nekokem.ui.PublicKeyContactNoteDialog
import com.shixiaoshi0417.nekokem.ui.DeletePublicKeyContactDialog
import com.shixiaoshi0417.nekokem.ui.FingerprintQrDialog
import com.shixiaoshi0417.nekokem.ui.FingerprintScanOutcome
import com.shixiaoshi0417.nekokem.ui.FingerprintScanResultDialog
import com.shixiaoshi0417.nekokem.ui.FingerprintScanner
import com.shixiaoshi0417.nekokem.ui.fingerprintScanOutcome
import com.shixiaoshi0417.nekokem.ui.runWithUiBusyReset
import com.shixiaoshi0417.nekokem.ui.theme.NekoKEMTheme
import com.shixiaoshi0417.nekokem.update.ReleaseInfo
import com.shixiaoshi0417.nekokem.update.Releases
import com.shixiaoshi0417.nekokem.update.UpdateResult
import com.shixiaoshi0417.nekokem.update.UpdateSettings
import com.shixiaoshi0417.nekokem.update.UpdateSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var pageCache: PageCache? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguages.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated page may start while the old page's Core call still runs;
        // each page stages files only in its own cache directory.
        val page = PageCache.open(cacheDir)
        pageCache = page
        val keyManager = LocalKeyManager(applicationContext)
        val fileWorkflow = SafFileWorkflow(this, keyManager, page.directory)
        val publicKeyContacts = PublicKeyContacts(applicationContext, keyManager, page.directory)

        setContent {
            var uiStyle by remember { mutableStateOf(UiStyles.selection(this)) }
            NekoKEMTheme(uiStyle = uiStyle) {
                val nativeStatus = remember { readNativeStatus() }
                NekoKEMRoute(
                    uiStyle = uiStyle,
                    onUiStyleChange = { style ->
                        if (UiStyles.setSelection(this, style)) uiStyle = style
                    },
                    nativeConnected = nativeStatus.connected,
                    nativeVersion = nativeStatus.version,
                    page = page,
                    keyManager = keyManager,
                    fileWorkflow = fileWorkflow,
                    publicKeyContacts = publicKeyContacts,
                )
            }
        }
    }

    override fun onDestroy() {
        // Cancels this page's operations; its files go once they have returned.
        pageCache?.close()
        pageCache = null
        super.onDestroy()
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
    EXPORT_LEGACY_PUBLIC_KEY,
}

private enum class PendingKeyImport {
    PUBLIC_KEY,
    PRIVATE_KEY,
}

private enum class PasswordPrompt {
    CHECK_PRIVATE_KEY,
    IMPORT_PRIVATE_KEY,
    IMPORT_PUBLIC_KEY,
    SELECT_TEMPORARY_PRIVATE_KEY,
    DECRYPT_FILE,
    SIGN_ENCRYPTION,
    UPGRADE_KEY,
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
    uiStyle: UiStyle,
    onUiStyleChange: (UiStyle) -> Unit,
    nativeConnected: Boolean,
    nativeVersion: String?,
    page: PageCache,
    keyManager: LocalKeyManager,
    fileWorkflow: SafFileWorkflow,
    publicKeyContacts: PublicKeyContacts,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Blocking key and file work of this page; the page cache outlives it.
    suspend fun <T> pageWork(block: () -> T): T =
        withContext(Dispatchers.IO) { page.run(block) }

    val snackbarHostState = remember { SnackbarHostState() }
    var keyState by remember { mutableStateOf(LocalKeyState(false, null)) }
    var contactsState by remember { mutableStateOf(PublicKeyContactsState()) }
    // Saved-contact recipients, in order: one keeps NKEM v3, several share NKEM v4.
    var selectedContactIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    // Checkboxes on the contacts page; applied only by an explicit action.
    var contactSelection by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var contactAwaitingSave by remember { mutableStateOf<TemporaryPublicKey?>(null) }
    var contactUpgrades by remember { mutableStateOf<PublicKeyContact?>(null) }
    var contactToEdit by remember { mutableStateOf<PublicKeyContact?>(null) }
    var contactToDelete by remember { mutableStateOf<PublicKeyContact?>(null) }
    var showFingerprintQr by remember { mutableStateOf(false) }
    var contactToScan by remember { mutableStateOf<PublicKeyContact?>(null) }
    var scanResult by remember { mutableStateOf<Pair<PublicKeyContact, FingerprintScanOutcome>?>(null) }
    var showContactPicker by remember { mutableStateOf(false) }
    // Using a contact whose fingerprint was never verified asks first.
    var unverifiedUse by remember { mutableStateOf<Pair<List<PublicKeyContact>, () -> Unit>?>(null) }
    val selectedContacts = selectedContactIds.mapNotNull { id -> contactsState.contacts.firstOrNull { it.id == id } }
    val selectedContactId = selectedContactIds.singleOrNull()
    val selectedContact = if (selectedContactId != null) selectedContacts.firstOrNull() else null
    var selectedInputState by rememberSaveable(
        stateSaver = FileInputState.Saver,
    ) {
        mutableStateOf(FileInputState())
    }
    var pendingOutputAction by remember { mutableStateOf<PendingOutputAction?>(null) }
    var pendingKeyImport by remember { mutableStateOf<PendingKeyImport?>(null) }
    var pendingPrivateImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPublicImportUri by remember { mutableStateOf<Uri?>(null) }
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
    var confirmPublicKeyReplacement by remember { mutableStateOf(false) }
    var confirmKeypairReplacement by remember { mutableStateOf(false) }
    var replaceKeypair by remember { mutableStateOf(false) }
    var activeProgressOperation by remember { mutableStateOf<Int?>(null) }
    var activeProgressTracker by remember {
        mutableStateOf<OperationProgressTracker?>(null)
    }
    var progressSnapshot by remember { mutableStateOf(OperationProgressSnapshot()) }
    var progressCancelRequested by remember { mutableStateOf(false) }
    var completedOperation by remember { mutableStateOf<CompletedOperation?>(null) }
    var failedOperation by remember { mutableStateOf<FailedOperation?>(null) }
    // Saved across recreation, so a dismissed warning returns only for new backups.
    var recoveryBackupCount by rememberSaveable { mutableStateOf(0) }
    var recoveryWarningDismissed by rememberSaveable { mutableStateOf(false) }
    // Signing is on unless the user turned it off behind the red warning.
    var signatureSettings by remember { mutableStateOf(SignatureSettingsStore.read(context)) }
    var confirmUnsignedEncryption by remember { mutableStateOf(false) }
    // Update checks: automatic once a day unless turned off; the dialog only links to the release page.
    var updateSettings by remember { mutableStateOf(UpdateSettingsStore.read(context)) }
    var updateChecking by remember { mutableStateOf(false) }
    var availableRelease by remember { mutableStateOf<ReleaseInfo?>(null) }
    val currentVersion = remember { installedVersion(context) }
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
        keyState = pageWork {
            try {
                keyManager.readState()
            } catch (_: Exception) {
                LocalKeyState(false, null)
            }
        }
        contactsState = pageWork { publicKeyContacts.readState() }
        val backups = pageWork { keyManager.recoveryBackupCount() + fileWorkflow.recoveryBackupCount() }
        if (backups > recoveryBackupCount) recoveryWarningDismissed = false
        recoveryBackupCount = backups
        // Checkbox state only: deleted entries leave the pending selection. Chosen
        // recipients are kept and reported as unavailable, never dropped silently.
        contactSelection = contactSelection.filter { id -> contactsState.contacts.any { it.id == id } }
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

    // Names the saved contact that stopped an encryption; nothing was encrypted.
    fun reportRecipientFailure(code: Int, failedId: String?) {
        val reason = resultReason(context, R.string.operation_select_contact, code)
        val failed = contactsState.contacts.firstOrNull { it.id == failedId }
        failedOperation = FailedOperation(
            R.string.operation_select_contact,
            if (failed == null) reason
            else reason + "\n" + context.getString(R.string.contact_failed_recipient, failed.label),
        )
    }

    fun selectContact(contact: PublicKeyContact) {
        val previous = temporaryPublicKey
        temporaryPublicKey = null
        selectedContactIds = listOf(contact.id)
        transitionInputSelection(InputSelectionEvent.KEY_SELECTION_CHANGED)
        scope.launch { pageWork { fileWorkflow.discardTemporaryPublicKey(previous) } }
        showSnackbar(R.string.contact_selected)
    }

    /** Runs [proceed] now if every contact is verified, otherwise after the warning. */
    fun confirmVerified(contacts: List<PublicKeyContact>, proceed: () -> Unit) {
        val unverified = contacts.filterNot { it.verified }
        if (unverified.isEmpty()) proceed() else unverifiedUse = unverified to proceed
    }

    fun runContactOperation(@StringRes operation: Int, @StringRes success: Int, action: () -> Int) {
        if (running) return
        running = true
        scope.launch {
            try {
                val code = pageWork { action() }
                refreshState()
                if (code == NativeBridge.RESULT_SUCCESS) showSnackbar(success)
                else reportFailure(operation, code)
            } catch (_: Exception) {
                reportFailure(operation, LocalKeyManager.RESULT_STORAGE_ERROR)
            } finally {
                running = false
            }
        }
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
                    pageWork { operation() }
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

    /** Takes ownership of [privateKeyPassword]. */
    fun importPublicKeyFrom(uri: Uri, privateKeyPassword: ByteArray?) {
        var displayName = context.getString(R.string.default_public_key_filename)
        runKeyOperation(
            operationResource = R.string.operation_import_public_key,
            sensitiveInput = privateKeyPassword,
            completionDetails = { state ->
                keyCompletionDetails(displayName, state.fingerprint)
            },
        ) {
            displayName = fileWorkflow.describe(uri).displayName
            fileWorkflow.importPublicKey(uri, privateKeyPassword)
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
        page.track(tracker)
        activeProgressTracker = tracker
        activeProgressOperation = operationResource
        progressSnapshot = OperationProgressSnapshot()
        progressCancelRequested = false
        running = true
        return tracker
    }

    fun endProgress(tracker: OperationProgressTracker) {
        page.untrack(tracker)
        if (activeProgressTracker === tracker) {
            activeProgressTracker = null
            activeProgressOperation = null
            progressCancelRequested = false
        }
        running = false
    }

    suspend fun reportFileResult(
        @StringRes operationResource: Int,
        code: Int,
        progress: OperationProgressSnapshot,
        fileName: String,
        extraDetails: List<OperationResultDetail> = emptyList(),
    ) {
        // The selection and the result dialog come first: the page may already
        // be idle, and refreshing can wait on the key lock for a while.
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
                ) + extraDetails,
                progress = progress,
            )

            NativeBridge.RESULT_CANCELLED -> showSnackbar(
                R.string.snackbar_operation_cancelled,
            )

            else -> reportFailure(operationResource, code)
        }
        refreshState()
    }

    val selectFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // Busy until the name is known: no operation starts on the previous
        // file, and no slower lookup replaces a newer selection.
        if (uri != null && !running) {
            running = true
            scope.launch {
                try {
                    val document = pageWork { fileWorkflow.describe(uri) }
                    transitionInputSelection(
                        InputSelectionEvent.SELECT_NEW,
                        document.uri,
                        document.displayName,
                    )
                } finally {
                    running = false
                }
            }
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
            // This replaces the device's own key, not a contact: confirm it.
            if (keyState.fingerprint != null || keyState.privateKeyExists) {
                pendingPublicImportUri = uri
                confirmPublicKeyReplacement = true
            } else {
                importPublicKeyFrom(uri, null)
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
            running = true
            scope.launch {
                val outcome = try {
                    pageWork {
                        fileWorkflow.stageTemporaryPublicKey(
                            uri,
                            fileWorkflow.describe(uri).displayName,
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
                        pageWork {
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

    val importContactLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && !running) {
            running = true
            scope.launch {
                try {
                    val outcome = pageWork {
                        fileWorkflow.stageTemporaryPublicKey(uri, fileWorkflow.describe(uri).displayName)
                    }
                    val staged = outcome.key
                    if (outcome.code == NativeBridge.RESULT_SUCCESS && staged != null) {
                        contactUpgrades = pageWork { publicKeyContacts.upgradeTarget(staged) }
                        contactAwaitingSave = staged
                    } else {
                        reportFailure(R.string.operation_save_contact, outcome.code)
                    }
                } catch (_: Exception) {
                    reportFailure(R.string.operation_save_contact, LocalKeyManager.RESULT_STORAGE_ERROR)
                } finally {
                    running = false
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
            running = true
            scope.launch {
                val outcome = try {
                    pageWork {
                        fileWorkflow.stageTemporaryPrivateKey(
                            uri,
                            fileWorkflow.describe(uri).displayName,
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
                        pageWork {
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
                            pageWork {
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
                            pageWork {
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
                PendingOutputAction.EXPORT_LEGACY_PUBLIC_KEY,
                null -> showSnackbar(R.string.snackbar_operation_cancelled)
            }
            return@rememberLauncherForActivityResult
        }

        var destinationName = selectedDocument?.displayName
            ?: context.getString(R.string.default_selected_filename)

        when (action) {
            PendingOutputAction.ENCRYPT_FILE -> {
                val prepared = preparedEncryption
                preparedEncryption = null
                if (prepared == null) {
                    encryptionPreparationProgress = null
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
                            pageWork {
                                destinationName = fileWorkflow.describe(destination).displayName
                                fileWorkflow.commitPreparedEncryption(
                                    prepared,
                                    destination,
                                    tracker,
                                )
                            }
                        } catch (_: Exception) {
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
                            listOf(
                                if (prepared.signed) {
                                    OperationResultDetail(
                                        R.string.completion_signature,
                                        context.getString(R.string.signature_signed),
                                    )
                                } else {
                                    OperationResultDetail(
                                        R.string.completion_signature,
                                        context.getString(R.string.signature_unsigned) + " · " +
                                            context.getString(R.string.unsigned_warning),
                                        warning = true,
                                    )
                                },
                            ),
                        )
                    }
                }
            }

            PendingOutputAction.SAVE_DECRYPTED_FILE -> {
                val prepared = preparedDecryption
                preparedDecryption = null
                if (prepared == null) {
                    decryptionPreparationProgress = null
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
                            pageWork {
                                destinationName = fileWorkflow.describe(destination).displayName
                                fileWorkflow.commitPreparedDecryption(
                                    prepared,
                                    destination,
                                    tracker,
                                )
                            }
                        } catch (_: Exception) {
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
                            signatureDetails(context, prepared.signature, prepared.signerLabel),
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
                    destinationName = fileWorkflow.describe(destination).displayName
                    fileWorkflow.exportPublicKey(destination)
                }
            }

            PendingOutputAction.EXPORT_LEGACY_PUBLIC_KEY -> {
                runKeyOperation(
                    operationResource = R.string.operation_export_legacy_public_key,
                    completionDetails = { state ->
                        keyCompletionDetails(destinationName, state.legacyFingerprint)
                    },
                ) {
                    destinationName = fileWorkflow.describe(destination).displayName
                    fileWorkflow.exportLegacyPublicKey(destination)
                }
            }

            PendingOutputAction.EXPORT_PRIVATE_KEY -> {
                runKeyOperation(
                    operationResource = R.string.operation_export_private_key,
                    completionDetails = {
                        keyCompletionDetails(destinationName)
                    },
                ) {
                    destinationName = fileWorkflow.describe(destination).displayName
                    fileWorkflow.exportEncryptedPrivateKey(destination)
                }
            }
        }
    }

    /** Signs with this device's identity key when [signingPassword] is given; takes ownership of it. */
    fun prepareEncryption(source: SelectedDocument, signingPassword: ByteArray?) {
        val selectedKey = temporaryPublicKey
        val contactIds = selectedContactIds
        val tracker = beginProgress(R.string.operation_encrypt_file)
        scope.launch {
            var recipients = emptyList<TemporaryPublicKey>()
            try {
                if (contactIds.isNotEmpty()) {
                    // Every selected contact is re-read and verified; one failure stops all.
                    val staged = pageWork { publicKeyContacts.stageRecipients(contactIds) }
                    if (staged.code != NativeBridge.RESULT_SUCCESS || staged.keys.isEmpty()) {
                        reportRecipientFailure(staged.code, staged.failedId)
                        return@launch
                    }
                    recipients = staged.keys
                }
                val outcome = pageWork {
                    if (recipients.isNotEmpty()) {
                        fileWorkflow.prepareEncryptionForRecipients(
                            source.uri,
                            tracker,
                            recipients,
                            signingPassword?.copyOf(),
                        )
                    } else {
                        fileWorkflow.prepareEncryption(
                            source.uri,
                            tracker,
                            selectedKey,
                            signingPassword?.copyOf(),
                        )
                    }
                }
                if (temporaryPublicKey === selectedKey) {
                    temporaryPublicKey = null
                }
                val finalProgress = tracker.snapshot()
                if (outcome.code == NativeBridge.RESULT_SUCCESS &&
                    outcome.prepared != null
                ) {
                    outcome.prepared.signed = signingPassword != null
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
                    pageWork {
                        fileWorkflow.discardPreparedEncryption(outcome.prepared)
                    }
                    reportFileResult(
                        R.string.operation_encrypt_file,
                        outcome.code,
                        finalProgress,
                        source.displayName,
                    )
                    // Core reports a wrong signing password as a general error.
                    if (signingPassword != null && outcome.code == NativeBridge.RESULT_CORE_ERROR) {
                        reportFailureReason(R.string.operation_encrypt_file, R.string.error_reason_sign)
                    }
                }
            } catch (_: Exception) {
                pageWork {
                    fileWorkflow.discardTemporaryPublicKey(selectedKey)
                    recipients.forEach { fileWorkflow.discardTemporaryPublicKey(it) }
                }
                if (temporaryPublicKey === selectedKey) {
                    temporaryPublicKey = null
                }
                val abandoned = preparedEncryption
                preparedEncryption = null
                encryptionPreparationProgress = null
                pageWork {
                    fileWorkflow.discardPreparedEncryption(abandoned)
                }
                reportFailure(
                    R.string.operation_encrypt_file,
                    LocalKeyManager.RESULT_STORAGE_ERROR,
                )
            } finally {
                signingPassword?.fill(0)
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
        val requireSignature = signatureSettings.requireSignature
        scope.launch {
            try {
                var signerIds = emptyList<String>()
                val outcome = pageWork {
                    // Saved identity contacts are the trusted signers; Core decides.
                    val signers = try {
                        publicKeyContacts.stageSigners()
                    } catch (_: Exception) {
                        null
                    }
                    signerIds = signers?.ids.orEmpty()
                    fileWorkflow.prepareDecryption(
                        source.uri,
                        password,
                        tracker,
                        selectedKey,
                        signers?.keys.orEmpty(),
                        requireSignature,
                    )
                }
                if (temporaryPrivateKey === selectedKey) {
                    temporaryPrivateKey = null
                }
                val finalProgress = tracker.snapshot()
                if (outcome.code == NativeBridge.RESULT_SUCCESS &&
                    outcome.prepared != null
                ) {
                    outcome.prepared.signature = outcome.signature
                    outcome.prepared.signerLabel = outcome.signature?.trustedContact
                        ?.let { signerIds.getOrNull(it) }
                        ?.let { id -> contactsState.contacts.firstOrNull { it.id == id }?.label }
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
                    pageWork {
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
                pageWork {
                    fileWorkflow.discardTemporaryPrivateKey(selectedKey)
                }
                if (temporaryPrivateKey === selectedKey) {
                    temporaryPrivateKey = null
                }
                val abandoned = preparedDecryption
                preparedDecryption = null
                decryptionPreparationProgress = null
                pageWork {
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

    // This page's cache starts empty; PageCache removes earlier pages' files
    // only after their operations have returned.
    LaunchedEffect(Unit) {
        pageWork {
            keyManager.cleanupExpiredCandidates()
            fileWorkflow.cleanupExpiredOutputBackups()
        }
        refreshState()
    }

    if (showGeneratePasswordDialog) {
        GeneratePasswordDialog(
            onDismiss = {
                showGeneratePasswordDialog = false
                replaceKeypair = false
            },
            onConfirm = { password ->
                showGeneratePasswordDialog = false
                val replace = replaceKeypair
                replaceKeypair = false
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
                    keyManager.generateKeypair(password, replace)
                }
            },
        )
    }

    passwordPrompt?.let { prompt ->
        val title = when (prompt) {
            PasswordPrompt.CHECK_PRIVATE_KEY -> R.string.check_password_title
            PasswordPrompt.IMPORT_PRIVATE_KEY -> R.string.import_password_title
            PasswordPrompt.IMPORT_PUBLIC_KEY -> R.string.import_public_key_password_title
            PasswordPrompt.SELECT_TEMPORARY_PRIVATE_KEY ->
                R.string.select_temporary_private_key_password_title
            PasswordPrompt.DECRYPT_FILE -> R.string.decrypt_password_title
            PasswordPrompt.SIGN_ENCRYPTION -> R.string.sign_password_title
            PasswordPrompt.UPGRADE_KEY -> R.string.upgrade_password_title
        }
        val message = when (prompt) {
            PasswordPrompt.CHECK_PRIVATE_KEY -> R.string.check_password_message
            PasswordPrompt.IMPORT_PRIVATE_KEY -> R.string.import_password_message
            PasswordPrompt.IMPORT_PUBLIC_KEY -> R.string.import_public_key_password_message
            PasswordPrompt.SELECT_TEMPORARY_PRIVATE_KEY ->
                R.string.select_temporary_private_key_password_message
            PasswordPrompt.DECRYPT_FILE -> R.string.decrypt_password_message
            PasswordPrompt.SIGN_ENCRYPTION -> R.string.sign_password_message
            PasswordPrompt.UPGRADE_KEY -> R.string.upgrade_password_message
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
                } else if (prompt == PasswordPrompt.IMPORT_PUBLIC_KEY) {
                    pendingPublicImportUri = null
                } else if (
                    prompt == PasswordPrompt.SELECT_TEMPORARY_PRIVATE_KEY
                ) {
                    val abandoned = pendingTemporaryPrivateKey
                    pendingTemporaryPrivateKey = null
                    scope.launch { pageWork { fileWorkflow.discardPendingTemporaryPrivateKey(abandoned) } }
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
                            var displayName = context.getString(R.string.default_private_key_filename)
                            runKeyOperation(
                                operationResource =
                                    R.string.operation_import_private_key,
                                sensitiveInput = password,
                                completionDetails = {
                                    keyCompletionDetails(displayName)
                                },
                            ) {
                                displayName = fileWorkflow.describe(uri).displayName
                                fileWorkflow.importEncryptedPrivateKey(uri, password)
                            }
                        }
                    }

                    PasswordPrompt.IMPORT_PUBLIC_KEY -> {
                        val uri = pendingPublicImportUri
                        pendingPublicImportUri = null
                        if (uri == null) {
                            password.fill(0)
                            showSnackbar(R.string.snackbar_operation_cancelled)
                        } else {
                            importPublicKeyFrom(uri, password)
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
                                    pageWork {
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
                                    pageWork {
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
                                        pageWork {
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

                    PasswordPrompt.SIGN_ENCRYPTION -> {
                        val source = selectedDocument
                        if (source == null) {
                            password.fill(0)
                            reportFailureReason(R.string.operation_encrypt_file, R.string.error_select_file_first)
                        } else {
                            prepareEncryption(source, password)
                        }
                    }

                    PasswordPrompt.UPGRADE_KEY -> runKeyOperation(
                        operationResource = R.string.operation_upgrade_key,
                        sensitiveInput = password,
                        completionDetails = { state ->
                            keyCompletionDetails(
                                context.getString(R.string.default_public_key_filename),
                                state.fingerprint,
                            )
                        },
                    ) {
                        keyManager.upgradeToIdentityKeys(password.copyOf())
                    }
                }
            },
        )
    }

    publicKeyAwaitingConfirmation?.let { candidate ->
        AppAlertDialog(
            onDismissRequest = {
                publicKeyAwaitingConfirmation = null
                scope.launch { pageWork { fileWorkflow.discardTemporaryPublicKey(candidate) } }
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
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                DialogButton(
                    text = stringResource(R.string.action_use_key),
                    onClick = {
                        publicKeyAwaitingConfirmation = null
                        val previous = temporaryPublicKey
                        temporaryPublicKey = candidate
                        selectedContactIds = emptyList()
                        transitionInputSelection(
                            InputSelectionEvent.KEY_SELECTION_CHANGED,
                        )
                        scope.launch { pageWork { fileWorkflow.discardTemporaryPublicKey(previous) } }
                        showSnackbar(
                            R.string.snackbar_temporary_public_key_selected,
                        )
                    },
                )
            },
            dismissButton = {
                DialogButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = {
                        publicKeyAwaitingConfirmation = null
                        scope.launch { pageWork { fileWorkflow.discardTemporaryPublicKey(candidate) } }
                    },
                )
            },
        )
    }

    contactAwaitingSave?.let { candidate ->
        val existing = contactsState.contacts.firstOrNull { it.fingerprint == candidate.fingerprint }
        PublicKeyContactNoteDialog(
            displayName = candidate.displayName,
            fingerprint = candidate.fingerprint,
            initialNote = existing?.note.orEmpty(),
            editing = false,
            duplicate = existing != null,
            upgrades = contactUpgrades,
            onSave = { note ->
                val upgradeConfirmed = contactUpgrades != null
                contactAwaitingSave = null
                contactUpgrades = null
                runContactOperation(R.string.operation_save_contact, R.string.contact_saved) {
                    try { publicKeyContacts.save(candidate, note, upgradeConfirmed).code }
                    finally { fileWorkflow.discardTemporaryPublicKey(candidate) }
                }
            },
            onDismiss = {
                contactAwaitingSave = null
                contactUpgrades = null
                scope.launch { pageWork { fileWorkflow.discardTemporaryPublicKey(candidate) } }
            },
        )
    }

    contactToEdit?.let { contact ->
        PublicKeyContactNoteDialog(
            displayName = contact.displayName,
            fingerprint = contact.fingerprint,
            initialNote = contact.note,
            editing = true,
            onSave = { note ->
                contactToEdit = null
                runContactOperation(R.string.operation_update_contact, R.string.contact_updated) {
                    publicKeyContacts.updateNote(contact.id, note).code
                }
            },
            onDismiss = { contactToEdit = null },
        )
    }

    if (showFingerprintQr) {
        val fingerprint = keyState.fingerprint
        if (fingerprint == null) {
            showFingerprintQr = false
        } else {
            FingerprintQrDialog(fingerprint, onDismiss = { showFingerprintQr = false })
        }
    }

    contactToScan?.let { contact ->
        FingerprintScanner(
            contact,
            onResult = { text ->
                contactToScan = null
                val outcome = fingerprintScanOutcome(text, contact)
                scanResult = contact to outcome
                if (outcome != FingerprintScanOutcome.NotNekoKEM) {
                    val matched = outcome == FingerprintScanOutcome.Match
                    scope.launch {
                        val code = pageWork { publicKeyContacts.setVerified(contact.id, contact.fingerprint, matched).code }
                        if (code != NativeBridge.RESULT_SUCCESS) {
                            scanResult = null
                            reportFailure(R.string.operation_verify_contact, code)
                        }
                        refreshState()
                    }
                }
            },
            onDismiss = { contactToScan = null },
        )
    }

    scanResult?.let { (contact, outcome) ->
        FingerprintScanResultDialog(
            contact,
            outcome,
            onRetry = {
                scanResult = null
                contactToScan = contact
            },
            onDismiss = { scanResult = null },
        )
    }

    contactToDelete?.let { contact ->
        DeletePublicKeyContactDialog(
            contact,
            onConfirm = {
                contactToDelete = null
                runContactOperation(R.string.operation_delete_contact, R.string.contact_deleted) {
                    publicKeyContacts.delete(contact.id)
                }
            },
            onDismiss = { contactToDelete = null },
        )
    }

    unverifiedUse?.let { (contacts, proceed) ->
        UnverifiedContactDialog(
            contacts,
            onConfirm = {
                unverifiedUse = null
                proceed()
            },
            onDismiss = { unverifiedUse = null },
        )
    }

    if (showContactPicker) {
        PublicKeyContactPicker(
            state = contactsState,
            selectedId = selectedContactId,
            onSelect = { contact ->
                showContactPicker = false
                confirmVerified(listOf(contact)) { selectContact(contact) }
            },
            onImport = {
                showContactPicker = false
                importContactLauncher.launch(arrayOf(ANY_MIME_TYPE))
            },
            onDismiss = { showContactPicker = false },
        )
    }

    pendingDelete?.let { target ->
        AppAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(target.titleResource)) },
            text = { Text(stringResource(target.messageResource)) },
            confirmButton = {
                DialogButton(
                    text = stringResource(R.string.action_delete),
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
                )
            },
            dismissButton = {
                DialogButton(stringResource(R.string.action_cancel), onClick = { pendingDelete = null })
            },
        )
    }

    // Generation never replaces keys silently; replacing needs this confirmation.
    if (confirmKeypairReplacement) {
        AppAlertDialog(
            onDismissRequest = { confirmKeypairReplacement = false },
            title = { Text(stringResource(R.string.replace_keypair_title)) },
            text = { Text(stringResource(R.string.replace_keypair_message)) },
            confirmButton = {
                DialogButton(
                    text = stringResource(R.string.action_replace),
                    onClick = {
                        confirmKeypairReplacement = false
                        replaceKeypair = true
                        showGeneratePasswordDialog = true
                    },
                )
            },
            dismissButton = {
                DialogButton(stringResource(R.string.action_cancel), onClick = { confirmKeypairReplacement = false })
            },
        )
    }

    if (confirmPublicKeyReplacement) {
        val cancelPublicImport = {
            confirmPublicKeyReplacement = false
            pendingPublicImportUri = null
        }
        AppAlertDialog(
            onDismissRequest = cancelPublicImport,
            title = { Text(stringResource(R.string.replace_public_key_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.replace_public_key_message))
                    keyState.fingerprint?.let { current ->
                        Text(
                            stringResource(R.string.key_fingerprint_line, current),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (keyState.privateKeyExists) {
                        Text(stringResource(R.string.replace_public_key_pair_note))
                    }
                }
            },
            confirmButton = {
                DialogButton(
                    text = stringResource(R.string.action_replace),
                    onClick = {
                        confirmPublicKeyReplacement = false
                        val uri = pendingPublicImportUri
                        if (uri != null && keyState.privateKeyExists) {
                            passwordPrompt = PasswordPrompt.IMPORT_PUBLIC_KEY
                        } else if (uri != null) {
                            pendingPublicImportUri = null
                            importPublicKeyFrom(uri, null)
                        }
                    },
                )
            },
            dismissButton = {
                DialogButton(stringResource(R.string.action_cancel), onClick = cancelPublicImport)
            },
        )
    }

    if (confirmPrivateKeyReplacement) {
        AppAlertDialog(
            onDismissRequest = {
                confirmPrivateKeyReplacement = false
                pendingPrivateImportUri = null
            },
            title = { Text(stringResource(R.string.replace_private_key_title)) },
            text = { Text(stringResource(R.string.replace_private_key_message)) },
            confirmButton = {
                DialogButton(
                    text = stringResource(R.string.action_replace),
                    onClick = {
                        confirmPrivateKeyReplacement = false
                        if (pendingPrivateImportUri != null) {
                            passwordPrompt = PasswordPrompt.IMPORT_PRIVATE_KEY
                        }
                    },
                )
            },
            dismissButton = {
                DialogButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = {
                        confirmPrivateKeyReplacement = false
                        pendingPrivateImportUri = null
                    },
                )
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

    if (recoveryBackupCount > 0 && !recoveryWarningDismissed && !running) {
        AppAlertDialog(
            onDismissRequest = { recoveryWarningDismissed = true },
            title = { Text(stringResource(R.string.recovery_backups_title)) },
            text = { Text(stringResource(R.string.recovery_backups_message, recoveryBackupCount)) },
            confirmButton = {
                DialogButton(stringResource(R.string.action_close), onClick = { recoveryWarningDismissed = true })
            },
        )
    }

    fun updateSignatureSettings(updated: SignatureSettings) {
        if (SignatureSettingsStore.write(context, updated)) {
            signatureSettings = updated
        } else {
            showSnackbar(R.string.error_settings_save)
        }
    }

    fun storeUpdateSettings(updated: UpdateSettings): Boolean {
        if (!UpdateSettingsStore.write(context, updated)) return false
        updateSettings = updated
        return true
    }

    /** [manual] reports every outcome; an automatic check speaks only about a new version. */
    fun checkForUpdate(manual: Boolean) {
        if (updateChecking) return
        updateChecking = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { Releases.check(currentVersion) }
            updateChecking = false
            val checked = UpdateSettingsStore.read(context)
            if (result != UpdateResult.Failed) {
                storeUpdateSettings(checked.copy(lastCheckMillis = System.currentTimeMillis()))
            }
            when (result) {
                is UpdateResult.Available ->
                    if (manual || result.release.version != checked.ignoredVersion) availableRelease = result.release
                UpdateResult.UpToDate -> if (manual) showSnackbar(R.string.update_up_to_date)
                UpdateResult.Failed -> if (manual) showSnackbar(R.string.update_failed)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (UpdateSettingsStore.due(updateSettings, System.currentTimeMillis())) checkForUpdate(manual = false)
    }

    availableRelease?.let { release ->
        UpdateAvailableDialog(
            release = release,
            currentVersion = currentVersion,
            onOpen = {
                availableRelease = null
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl))
                            .addCategory(Intent.CATEGORY_BROWSABLE),
                    )
                } catch (_: ActivityNotFoundException) {
                    showSnackbar(R.string.error_open_release)
                }
            },
            onIgnore = {
                availableRelease = null
                if (!storeUpdateSettings(UpdateSettingsStore.read(context).copy(ignoredVersion = release.version))) {
                    showSnackbar(R.string.error_settings_save)
                }
            },
            onLater = { availableRelease = null },
        )
    }

    if (confirmUnsignedEncryption) {
        UnsignedEncryptionWarningDialog(
            onConfirm = {
                confirmUnsignedEncryption = false
                updateSignatureSettings(signatureSettings.copy(unsignedEncryption = true))
            },
            onDismiss = { confirmUnsignedEncryption = false },
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
                ?: if (selectedContactIds.isNotEmpty()) selectedContact?.displayName
                    ?: context.getString(R.string.not_available)
                else context.getString(R.string.default_public_key_filename),
            publicKeyFingerprint = if (selectedContactIds.isNotEmpty()) selectedContact?.fingerprint
                else temporaryPublicKey?.fingerprint ?: keyState.fingerprint,
            privateKeyTemporary = temporaryPrivateKey != null,
            privateKeyFileName = temporaryPrivateKey?.displayName
                ?: context.getString(R.string.default_private_key_filename),
            privateKeyFingerprint = temporaryPrivateKey?.fingerprint
                ?: keyState.fingerprint,
            running = running,
            contactsState = contactsState,
            publicKeyContactId = selectedContactId,
            publicKeyNote = selectedContact?.note,
            publicKeyRecipients = selectedContacts,
            publicKeyRecipientCount = selectedContactIds.size,
            contactSelection = contactSelection,
            uiStyle = uiStyle,
            canSign = keyState.canSign,
            legacyFingerprint = keyState.legacyFingerprint,
            signatureSettings = signatureSettings,
            autoUpdateCheck = updateSettings.autoCheck,
            updateChecking = updateChecking,
        ),
        actions = NekoKEMActions(
            onGenerate = {
                scope.launch {
                    val existing = try {
                        pageWork { keyManager.hasKeyFiles() }
                    } catch (_: Exception) {
                        true
                    }
                    replaceKeypair = false
                    if (existing) {
                        confirmKeypairReplacement = true
                    } else {
                        showGeneratePasswordDialog = true
                    }
                }
            },
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
                selectedContactIds = emptyList()
                transitionInputSelection(
                    InputSelectionEvent.KEY_SELECTION_CHANGED,
                )
                scope.launch { pageWork { fileWorkflow.discardTemporaryPublicKey(abandoned) } }
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
                scope.launch { pageWork { fileWorkflow.discardTemporaryPrivateKey(abandoned) } }
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
                        selectedContactIds.isEmpty() &&
                        keyState.fingerprint == null -> reportFailureReason(
                        R.string.operation_encrypt_file,
                        R.string.error_public_key_not_found,
                    )
                    signatureSettings.unsignedEncryption -> prepareEncryption(source, null)
                    // Signing needs this device's identity key pair.
                    !keyState.privateKeyExists || !keyState.canSign -> reportFailure(
                        R.string.operation_encrypt_file,
                        LocalKeyManager.RESULT_SIGNING_KEY_LEGACY,
                    )
                    else -> passwordPrompt = PasswordPrompt.SIGN_ENCRYPTION
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
            onImportContact = { importContactLauncher.launch(arrayOf(ANY_MIME_TYPE)) },
            onChooseContact = { showContactPicker = true },
            onSelectContact = { contact, onSelected ->
                confirmVerified(listOf(contact)) {
                    selectContact(contact)
                    onSelected()
                }
            },
            onEditContact = { contactToEdit = it },
            onDeleteContact = { contactToDelete = it },
            onShowFingerprintQr = { showFingerprintQr = true },
            onScanContact = { contactToScan = it },
            onToggleContactSelection = { contact ->
                contactSelection = when {
                    contact.id in contactSelection -> contactSelection - contact.id
                    contactSelection.size < NativeBridge.MAX_RECIPIENTS -> contactSelection + contact.id
                    else -> contactSelection
                }
            },
            onClearContactSelection = { contactSelection = emptyList() },
            onEncryptForSelectedContacts = { onSelected ->
                // Only contacts still listed are applied; the choice stays explicit.
                val chosen = contactSelection.mapNotNull { id -> contactsState.contacts.firstOrNull { it.id == id } }
                if (chosen.isNotEmpty()) {
                    confirmVerified(chosen) {
                        val ids = chosen.map { it.id }
                        val previous = temporaryPublicKey
                        temporaryPublicKey = null
                        selectedContactIds = ids
                        transitionInputSelection(InputSelectionEvent.KEY_SELECTION_CHANGED)
                        scope.launch { pageWork { fileWorkflow.discardTemporaryPublicKey(previous) } }
                        showSnackbar(if (ids.size > 1) R.string.contact_recipients_selected else R.string.contact_selected)
                        onSelected()
                    }
                }
            },
            onSetContactPinned = { contact, pinned ->
                runContactOperation(
                    R.string.operation_update_contact,
                    if (pinned) R.string.contact_pinned_done else R.string.contact_unpinned_done,
                ) { publicKeyContacts.setPinned(contact.id, pinned).code }
            },
            onSetContactArchived = { contact, archived ->
                // An archived contact is hidden from the list, so it leaves the selection.
                if (archived) contactSelection = contactSelection - contact.id
                runContactOperation(
                    R.string.operation_update_contact,
                    if (archived) R.string.contact_archived_done else R.string.contact_unarchived_done,
                ) { publicKeyContacts.setArchived(contact.id, archived).code }
            },
            onSetContactVerified = { contact, verified ->
                runContactOperation(
                    R.string.operation_verify_contact,
                    if (verified) R.string.contact_verified else R.string.contact_unverified,
                ) { publicKeyContacts.setVerified(contact.id, contact.fingerprint, verified).code }
            },
            onUiStyleChange = onUiStyleChange,
            onUpgradeKey = { passwordPrompt = PasswordPrompt.UPGRADE_KEY },
            onExportLegacyPublicKey = {
                pendingOutputAction = PendingOutputAction.EXPORT_LEGACY_PUBLIC_KEY
                createOutputLauncher.launch(legacyPublicKeyExportRequest())
            },
            onUnsignedEncryptionChange = { enabled ->
                // Turning signing off always asks first; turning it back on never does.
                if (enabled) {
                    confirmUnsignedEncryption = true
                } else {
                    updateSignatureSettings(signatureSettings.copy(unsignedEncryption = false))
                }
            },
            onRequireSignatureChange = { required ->
                updateSignatureSettings(signatureSettings.copy(requireSignature = required))
            },
            onCheckUpdate = { checkForUpdate(manual = true) },
            onAutoUpdateChange = { enabled ->
                if (!storeUpdateSettings(UpdateSettingsStore.read(context).copy(autoCheck = enabled))) {
                    showSnackbar(R.string.error_settings_save)
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

private fun resultReason(
    context: Context,
    @StringRes operationResource: Int,
    code: Int,
): String {
    val reasonResource = when (code) {
        LocalKeyManager.RESULT_STORAGE_ERROR -> when (operationResource) {
            R.string.operation_delete_public_key,
            R.string.operation_delete_private_key,
            R.string.operation_delete_contact,
            R.string.operation_delete_keypair -> R.string.error_reason_delete
            else -> R.string.error_reason_storage
        }
        LocalKeyManager.RESULT_DELETED_RESIDUE_RETAINED -> R.string.error_reason_delete_residue
        LocalKeyManager.RESULT_FINGERPRINT_MISMATCH ->
            R.string.error_reason_fingerprint_mismatch
        LocalKeyManager.RESULT_KEY_PAIR_MISMATCH -> R.string.error_reason_key_pair_mismatch
        LocalKeyManager.RESULT_KEY_PAIR_PASSWORD_FAILED -> R.string.error_reason_authentication
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
        LocalKeyManager.RESULT_SIGNING_KEY_LEGACY -> R.string.error_reason_signing_key
        LocalKeyManager.RESULT_ALREADY_IDENTITY -> R.string.error_reason_already_identity
        LocalKeyManager.RESULT_SIGNATURE_INVALID -> R.string.error_reason_signature_invalid
        LocalKeyManager.RESULT_SIGNATURE_REQUIRED -> R.string.error_reason_signature_required
        LocalKeyManager.RESULT_SIGNER_NOT_TRUSTED -> R.string.error_reason_signer_not_trusted
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
            R.string.operation_select_temporary_public_key,
            R.string.operation_save_contact,
            R.string.operation_select_contact ->
                R.string.error_reason_public_key
            R.string.operation_select_temporary_private_key ->
                R.string.error_reason_authentication
            R.string.operation_upgrade_key -> R.string.error_reason_upgrade
            R.string.operation_export_public_key,
            R.string.operation_export_legacy_public_key,
            R.string.operation_export_private_key -> R.string.error_reason_export
            R.string.operation_delete_public_key,
            R.string.operation_delete_private_key,
            R.string.operation_delete_contact,
            R.string.operation_delete_keypair -> R.string.error_reason_delete
            else -> R.string.error_reason_core
        }
    }
    return context.getString(reasonResource)
}

/** Core's signature report as completion lines; an unsigned file is marked red. */
private fun signatureDetails(
    context: Context,
    signature: SignatureReport?,
    signerLabel: String?,
): List<OperationResultDetail> {
    if (signature == null) return emptyList()
    val format = OperationResultDetail(
        R.string.completion_format,
        context.getString(R.string.completion_format_value, signature.format),
    )
    val status = when (signature.status) {
        NativeBridge.SIGNATURE_TRUSTED -> OperationResultDetail(
            R.string.completion_signature,
            when {
                signature.signedBySelf -> context.getString(R.string.signature_trusted_self)
                signerLabel != null -> context.getString(R.string.signature_trusted_contact, signerLabel)
                else -> context.getString(R.string.signature_trusted_self)
            },
        )
        NativeBridge.SIGNATURE_VALID_UNTRUSTED -> OperationResultDetail(
            R.string.completion_signature,
            context.getString(R.string.signature_untrusted),
            warning = true,
        )
        else -> OperationResultDetail(
            R.string.completion_signature,
            context.getString(R.string.signature_none),
            warning = true,
        )
    }
    val signer = signature.signerFingerprint?.let {
        OperationResultDetail(R.string.completion_signer_fingerprint, it)
    }
    return listOfNotNull(format, status, signer)
}

private const val ANY_MIME_TYPE = "*/*"

/** This App's versionName, such as "5.0.0". */
private fun installedVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
} catch (_: android.content.pm.PackageManager.NameNotFoundException) {
    ""
}
