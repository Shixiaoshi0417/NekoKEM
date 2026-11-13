package com.shixiaoshi0417.nekokem.desktop

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class View(val key: String) { Keygen("keygen"), Files("navFiles"), Fingerprint("fingerprint"), Contacts("contacts"), Settings("settings") }
enum class KeySource { Path, Paste }
// The key page generates identity keys, upgrades a 4.x key pair or exports the
// two-component public key that NekoKEM 4.x and earlier read.
enum class KeyMode { Generate, Upgrade, Export }
enum class PathField { Input, Output, KeyPath, PublicPath, PrivatePath, ContactKeyPath, SignKeyPath, OwnPublicKey, OtherPrivateKey }
enum class Feedback { Form, List }

/** A finished encryption or decryption whose staged output waits for the place to save it. */
private class Unsaved(val outcome: Outcome, val kind: Kind, val source: String, val contacts: List<Contact>, val own: Boolean, val others: List<String>)

class UpgradePrompt(val keyPath: String, val keyText: TextFieldState?, val note: String, val contact: String, val fingerprint: String, val fromSigner: Boolean)

/**
 * The window's state and actions. The file page works as on Android: choose a
 * file, press Encrypt or Decrypt, answer what is asked, and choose where to
 * save once the work is done. Secrets live only in [TextFieldState]s, which
 * are cleared (with their undo history) as soon as a request is written.
 */
class AppState(private val scope: CoroutineScope, private val files: FileChooser, private val exit: () -> Unit) {
    var view by mutableStateOf(View.Files); private set
    var language by mutableStateOf("en"); private set
    var selectedLanguage by mutableStateOf("system"); private set
    var version by mutableStateOf("5.0.0"); private set
    var loaded by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var cancelling by mutableStateOf(false); private set
    private var cancelPending by mutableStateOf(false)
    private var jobId = ""
    var result by mutableStateOf<Outcome?>(null); private set
    var successMessage by mutableStateOf("success"); private set
    var failure by mutableStateOf<String?>(null); private set
    var feedback by mutableStateOf(Feedback.Form); private set
    var progress by mutableStateOf<Pair<Long, Long>?>(null); private set

    var input by mutableStateOf(""); private set
    var output by mutableStateOf(""); private set
    var keyPath by mutableStateOf(""); private set
    var publicPath by mutableStateOf(""); private set
    var privatePath by mutableStateOf(""); private set
    val password = TextFieldState()
    val confirmation = TextFieldState()
    val keyText = TextFieldState()
    var source by mutableStateOf(KeySource.Path); private set
    // Saved-contact recipients, in selection order: shared by the Contacts and Encrypt pages.
    val selected = mutableStateListOf<String>()
    var replace by mutableStateOf(false)
    var overwrite by mutableStateOf(false)
    var contactKeyPath by mutableStateOf(""); private set
    val contactKeyText = TextFieldState()
    var contactPaste by mutableStateOf(false); private set
    var note by mutableStateOf("")

    // Unsigned output is never saved: every launch signs again.
    var unsigned by mutableStateOf(false); private set
    var requireSignature by mutableStateOf(false); private set
    // The user's own key pair. The private key is the only default: it signs and
    // decrypts. The public key is offered first when choosing recipients, never ticked.
    var signKeyPath by mutableStateOf(""); private set
    var ownPublicKey by mutableStateOf(""); private set
    // Decryption uses the user's own private key unless another is chosen for now.
    var otherPrivateKey by mutableStateOf(""); private set
    val decryptKey get() = otherPrivateKey.ifEmpty { signKeyPath }
    // Encrypt or Decrypt was pressed and its questions are being answered, or it runs.
    var fileAction by mutableStateOf<Kind?>(null); private set
    var askingPassword by mutableStateOf(false); private set
    // The output is written to a private staged file first. It stays there, and
    // can be saved again, when the chosen place could not be written.
    private var staged: Unsaved? = null
    var unsaved by mutableStateOf(false); private set
    // Pressing Encrypt asks for the recipients.
    var choosingRecipients by mutableStateOf(false); private set
    var ownSelected by mutableStateOf(false); private set
    // Public key files added in the dialog for this file alone; ticked when added.
    val otherKeys = mutableStateListOf<String>()
    // Unverified contacts are encrypted to only after a second, warned press.
    var unverifiedWarned by mutableStateOf(false); private set
    var sidebarOpen by mutableStateOf(true); private set
    var style by mutableStateOf(Style.Miuix); private set
    var keyMode by mutableStateOf(KeyMode.Generate); private set
    // Turning off signing needs an explicit, red-marked confirmation.
    var confirmingUnsigned by mutableStateOf(false); private set

    private var update = UpdatePreferences()
    var autoUpdate by mutableStateOf(true); private set
    var checkingUpdate by mutableStateOf(false); private set
    var updateNotice by mutableStateOf<String?>(null); private set
    var availableVersion by mutableStateOf(""); private set
    var qrFingerprint by mutableStateOf(""); private set
    var signerSaved by mutableStateOf(false); private set
    // Replacing keys is offered only after Core reported existing keys at the
    // chosen paths, must be ticked for each run and is withdrawn when they change.
    var replaceOffered by mutableStateOf(false); private set
    var overwriteOffered by mutableStateOf(false); private set
    var invalidField by mutableStateOf<String?>(null); private set
    var choosing by mutableStateOf<PathField?>(null); private set
    var contacts by mutableStateOf<List<Contact>>(emptyList()); private set
    var unreadable by mutableStateOf(0); private set
    var contactsFailure by mutableStateOf<String?>(null); private set
    var contactsLoaded by mutableStateOf(false); private set
    var contactBusy by mutableStateOf(false); private set
    var editing by mutableStateOf<String?>(null); private set
    var noteDraft by mutableStateOf("")
    var confirmingDelete by mutableStateOf<String?>(null); private set
    var highlighted by mutableStateOf<String?>(null); private set
    var recipients by mutableStateOf<List<Contact>>(emptyList()); private set
    var ownRecipient by mutableStateOf(false); private set
    var otherRecipients by mutableStateOf<List<String>>(emptyList()); private set
    // The saved contact that made the last encryption fail, named beside the error.
    var failedContact by mutableStateOf<Contact?>(null); private set
    var upgradePrompt by mutableStateOf<UpgradePrompt?>(null); private set
    var upgradeChecked by mutableStateOf(false)
    private var closeRequested = false

    val locked get() = busy || contactBusy || choosing != null
    fun t(key: String) = Strings.get(key, language)
    fun label(contact: Contact) = contact.note.ifEmpty { contact.name.ifEmpty { t("pastedKey") } }

    val kind get() = when (view) {
        View.Keygen -> when (keyMode) { KeyMode.Generate -> Kind.Keygen; KeyMode.Upgrade -> Kind.Upgrade; KeyMode.Export -> Kind.ExportLegacy }
        else -> Kind.Fingerprint
    }
    val signing get() = fileAction == Kind.Encrypt && !unsigned
    val passwordRequired get() = view == View.Keygen && keyMode != KeyMode.Export
    val percent get() = progress?.takeIf { it.second > 0 }?.let { (it.first * 100 / it.second).toInt().coerceIn(0, 100) }
    val selectedContacts get() = selected.mapNotNull { id -> contacts.firstOrNull { it.id == id } }
    val recipientCount get() = selected.size + otherKeys.size + if (ownSelected) 1 else 0
    val unverifiedChosen get() = selectedContacts.filter { !it.verified }
    val atRecipientLimit get() = recipientCount >= MAX_RECIPIENTS
    val signerContact get() = contacts.firstOrNull { it.id == result?.signature?.contact }

    private suspend fun <T> native(work: () -> T): T = withContext(Dispatchers.IO) { work() }
    private fun code(error: Throwable) = (error as? BridgeFailure)?.code?.takeIf(Strings::has) ?: "internal"

    fun start() = scope.launch {
        try {
            val settings = native { Backend.settings() }
            language = settings.language; selectedLanguage = settings.selection; version = settings.version
            runCatching { native { Backend.desktopPreferences() } }.getOrNull()?.let {
                requireSignature = it.requireSignature; signKeyPath = it.signKeyPath; ownPublicKey = it.publicKeyPath; sidebarOpen = it.sidebarOpen
                style = Style.entries.firstOrNull { entry -> entry.wire == it.style } ?: Style.Miuix
            }
            loaded = true
        } catch (error: Throwable) { failure = code(error); return@launch }
        runCatching { native { Backend.updatePreferences() } }.getOrNull()?.let { update = it; autoUpdate = it.auto }
        val now = System.currentTimeMillis()
        if (update.auto && (now - update.lastCheck >= DAY || now < update.lastCheck)) checkForUpdate(false)
        refreshContacts()
    }

    private fun storePreferences() {
        val value = DesktopPreferences(requireSignature, signKeyPath, ownPublicKey, sidebarOpen, style.wire)
        scope.launch { runCatching { native { Backend.saveDesktopPreferences(value) } } }
    }
    private fun storeUpdate() {
        val value = update
        scope.launch { try { native { Backend.saveUpdatePreferences(value) } } catch (error: Throwable) { updateNotice = code(error) } }
    }

    fun toggleSidebar() { sidebarOpen = !sidebarOpen; storePreferences() }
    fun changeStyle(value: Style) { style = value; storePreferences() }
    fun changeRequireSignature(value: Boolean) { requireSignature = value; storePreferences() }
    fun changeAutoUpdate(value: Boolean) { autoUpdate = value; update = update.copy(auto = value); storeUpdate() }

    fun toggleUnsigned(value: Boolean) {
        if (!value) { unsigned = false; confirmingUnsigned = false; return }
        // The switch stays off until the warning is confirmed.
        confirmingUnsigned = true
    }
    fun confirmUnsigned(enable: Boolean) { unsigned = enable; confirmingUnsigned = false }

    fun checkForUpdate(manual: Boolean) {
        if (checkingUpdate) return
        checkingUpdate = true
        if (manual) updateNotice = null
        scope.launch {
            val status = runCatching { native { Backend.checkUpdate() } }.getOrDefault(UpdateStatus.Failed)
            checkingUpdate = false
            if (status != UpdateStatus.Failed) { update = update.copy(lastCheck = System.currentTimeMillis()); storeUpdate() }
            if (status is UpdateStatus.Available && (manual || status.version != update.ignored)) availableVersion = status.version
            else if (manual) updateNotice = if (status == UpdateStatus.Current) "upToDate" else "updateFailed"
        }
    }
    fun openRelease() {
        val target = availableVersion
        availableVersion = ""
        scope.launch { try { native { Backend.openReleasePage(target) } } catch (error: Throwable) { updateNotice = code(error) } }
    }
    fun ignoreRelease() { update = update.copy(ignored = availableVersion); availableVersion = ""; storeUpdate() }
    fun dismissUpdate() { availableVersion = "" }
    fun showQr(fingerprint: String) { qrFingerprint = fingerprint }
    fun closeQr() { qrFingerprint = "" }

    private fun withdrawReplace() { replaceOffered = false; replace = false }
    private fun withdrawOverwrite() { overwriteOffered = false; overwrite = false }

    fun path(field: PathField) = when (field) {
        PathField.Input -> input; PathField.Output -> output; PathField.KeyPath -> keyPath
        PathField.PublicPath -> publicPath; PathField.PrivatePath -> privatePath
        PathField.ContactKeyPath -> contactKeyPath; PathField.SignKeyPath -> signKeyPath; PathField.OwnPublicKey -> ownPublicKey
        PathField.OtherPrivateKey -> otherPrivateKey
    }
    fun setPath(field: PathField, value: String) {
        when (field) {
            // Another file starts again: the last result belonged to the previous one.
            PathField.Input -> { if (value != input) { discardStaged(); resetFeedback() }; input = value }
            PathField.OtherPrivateKey -> otherPrivateKey = value
            PathField.Output -> { if (value != output) withdrawOverwrite(); output = value }
            PathField.KeyPath -> keyPath = value
            PathField.PublicPath -> { if (value != publicPath) withdrawReplace(); publicPath = value }
            PathField.PrivatePath -> { if (value != privatePath) withdrawReplace(); privatePath = value }
            PathField.ContactKeyPath -> contactKeyPath = value
            PathField.SignKeyPath -> { signKeyPath = value; if (view == View.Settings) storePreferences() }
            PathField.OwnPublicKey -> { ownPublicKey = value; storePreferences() }
        }
        edited(field.name.replaceFirstChar(Char::lowercase))
    }
    /** Editing the field that an error points at clears the error. */
    fun edited(field: String) { if (invalidField == field) { invalidField = null; failure = null } }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    private fun clear(state: TextFieldState) { state.clearText(); state.undoState.clearHistory() }
    // Pasted contact text should be public, but is scrubbed like key text in
    // case a private key was pasted by mistake.
    fun clearSecrets() { clear(password); clear(confirmation); clear(keyText); clear(contactKeyText) }

    private fun resetFeedback() {
        result = null; failure = null; invalidField = null; progress = null
        recipients = emptyList(); ownRecipient = false; otherRecipients = emptyList(); failedContact = null; signerSaved = false
        upgradePrompt?.keyText?.let(::clear)
        upgradePrompt = null
        feedback = Feedback.Form; successMessage = "success"
    }

    fun switchTab(value: View) {
        if (locked || !loaded || value == view) return
        clearSecrets()
        setPath(PathField.Output, "")
        resetFeedback()
        editing = null; confirmingDelete = null; highlighted = null
        withdrawReplace(); withdrawOverwrite()
        confirmingUnsigned = false; closeRecipientsNow(); discardStaged()
        view = value
        if (value == View.Files || value == View.Contacts) scope.launch { refreshContacts() }
    }

    fun changeSource(value: KeySource) {
        if (locked || !loaded || value == source) return
        clear(keyText); source = value; resetFeedback()
    }
    fun changeKeyMode(mode: KeyMode) {
        if (locked || !loaded || mode == keyMode) return
        clear(password); clear(confirmation)
        setPath(PathField.Output, "")
        keyMode = mode
        withdrawReplace(); withdrawOverwrite(); resetFeedback()
    }
    fun changeContactSource(paste: Boolean) {
        if (locked || !loaded || paste == contactPaste) return
        clear(contactKeyText); contactPaste = paste; resetFeedback()
    }

    fun browse(field: PathField) {
        if (locked || !loaded) return
        choosing = field
        scope.launch {
            try {
                // Upgrading changes existing key files, so it opens them.
                val saving = field in listOf(PathField.Output, PathField.PublicPath, PathField.PrivatePath) && !(view == View.Keygen && keyMode == KeyMode.Upgrade)
                val value = if (saving) {
                    files.save(when (field) {
                        PathField.PublicPath -> "public.key"
                        PathField.PrivatePath -> "private.key.enc"
                        else -> "legacy-public.key"
                    }, null)
                } else files.open()
                if (value != null) setPath(field, value)
            } catch (error: Throwable) { failure = code(error) } finally { choosing = null }
        }
    }

    fun changeLanguage(tag: String) {
        selectedLanguage = tag
        scope.launch { try { language = native { Backend.setLanguage(tag) } } catch (error: Throwable) { failure = code(error) } }
    }

    private fun invalid(message: String, field: String): String { invalidField = field; return message }
    // Same rule as the native store: 512 code points, no control characters.
    private fun invalidNote(text: String) = text.trim().codePointCount(0, text.trim().length) > 512 || text.any { it.code in 0..8 || it.code in 0x0b..0x1f || it.code in 0x7f..0x9f }
    private fun bytes(text: CharSequence): Int {
        var count = 0; var index = 0
        while (index < text.length) {
            val c = text[index]
            count += when { c.code < 0x80 -> 1; c.code < 0x800 -> 2; Character.isHighSurrogate(c) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1]) -> { index++; 4 }; else -> 3 }
            index++
        }
        return count
    }
    private fun same(first: CharSequence, second: CharSequence) = first.length == second.length && first.indices.all { first[it] == second[it] }

    private fun validate(): String? {
        invalidField = null
        if (view == View.Contacts) {
            if (contactPaste) { if (contactKeyText.text.isEmpty() || bytes(contactKeyText.text) > 1048576) return invalid("key-limit", "contactKeyText") }
            else if (contactKeyPath.isEmpty()) return invalid("invalid-path", "contactKeyPath")
            if (invalidNote(note)) return invalid("note-limit", "note")
            return null
        }
        if (bytes(password.text) > 1024) return invalid("password-limit", "password")
        if (bytes(confirmation.text) > 1024) return invalid("password-limit", "confirmation")
        if (view == View.Keygen && keyMode == KeyMode.Export) {
            if (keyPath.isEmpty()) return invalid("invalid-path", "keyPath")
            if (output.isEmpty()) return invalid("invalid-path", "output")
        } else if (view == View.Keygen) {
            if (publicPath.isEmpty()) return invalid("invalid-path", "publicPath")
            if (privatePath.isEmpty()) return invalid("invalid-path", "privatePath")
            if (password.text.isEmpty()) return invalid("password-empty", "password")
            if (keyMode == KeyMode.Generate && !same(password.text, confirmation.text)) return invalid("password-mismatch", "confirmation")
        } else if (source == KeySource.Paste) {
            if (keyText.text.isEmpty() || bytes(keyText.text) > 1048576) return invalid("key-limit", "keyText")
        } else if (keyPath.isEmpty()) return invalid("invalid-path", "keyPath")
        return null
    }

    suspend fun refreshContacts() {
        try {
            val list = native { Backend.contacts() }
            contacts = list.contacts; unreadable = list.unreadable; contactsFailure = null
        } catch (error: Throwable) {
            contacts = emptyList(); unreadable = 0; contactsFailure = code(error)
        } finally { contactsLoaded = true }
        // A selected contact that disappeared is deselected and reported, never replaced.
        val listed = selected.filter { id -> contacts.any { it.id == id } }
        if (listed.size != selected.size && !busy) {
            selected.retainAll(listed.toSet())
            if (choosingRecipients && failure == null) failure = invalid("contact-missing", "contacts")
        }
    }

    /** The key page's and the fingerprint page's button. */
    fun submit() {
        if (locked || !loaded) return
        if (view == View.Contacts) return saveContact()
        if (view == View.Settings || view == View.Files) return
        resetFeedback()
        failure = validate()
        if (failure != null) return
        jobId = UUID.randomUUID().toString()
        busy = true; cancelling = false
        val kind = kind
        val exporting = kind == Kind.ExportLegacy
        val keys = kind == Kind.Keygen || kind == Kind.Upgrade
        val pasted = kind == Kind.Fingerprint && source == KeySource.Paste
        val request = OperationRequest(
            id = jobId, kind = kind, output = if (exporting) output else "",
            keyPath = if (exporting || (kind == Kind.Fingerprint && !pasted)) keyPath else "",
            publicPath = if (keys) publicPath else "", privatePath = if (keys) privatePath else "",
            password = if (passwordRequired) password.text else "", confirmation = if (kind == Kind.Keygen) confirmation.text else "",
            keyText = if (pasted) keyText.text else "", paste = pasted,
            replace = kind == Kind.Keygen && replaceOffered && replace,
            overwrite = exporting && overwriteOffered && overwrite,
        )
        withdrawReplace(); withdrawOverwrite()
        scope.launch {
            try {
                // The request is written on the worker thread, then the fields are cleared.
                result = native { Backend.run(request, ::reportProgress) }
                // A new or upgraded key pair becomes the user's own keys.
                if (keys) { ownPublicKey = request.publicPath; signKeyPath = request.privatePath; storePreferences() }
                successMessage = when (kind) { Kind.Upgrade -> "keyUpgraded"; Kind.ExportLegacy -> "legacyExported"; else -> "success" }
            } catch (error: Throwable) {
                val message = code(error)
                failure = message
                if (kind == Kind.Keygen && message == "key-exists") replaceOffered = true
                if (exporting && message == "output-exists") overwriteOffered = true
            } finally {
                // The native layer zeroized its copy; the fields are cleared here.
                clearSecrets()
                busy = false; cancelling = false
            }
            closeIfRequested()
        }
    }

    fun clearFile() { if (!locked) setPath(PathField.Input, "") }
    fun restoreDefaultKey() { if (!locked) otherPrivateKey = "" }
    fun encrypt() = begin(Kind.Encrypt)
    fun decrypt() = begin(Kind.Decrypt)

    private fun begin(action: Kind) {
        if (locked || !loaded || view != View.Files) return
        discardStaged(); resetFeedback(); closeRecipientsNow(); clear(password)
        if (input.isEmpty()) { failure = invalid("invalid-path", "input"); return }
        if (action == Kind.Encrypt) {
            // Signing is never skipped silently: it needs the user's own private key.
            if (!unsigned && signKeyPath.isEmpty()) { failure = invalid("sign-key-missing", "signKeyPath"); return }
            fileAction = action
            choosingRecipients = true
            scope.launch { refreshContacts() }
        } else {
            if (decryptKey.isEmpty()) { failure = invalid("invalid-path", "otherPrivateKey"); return }
            fileAction = action
            askingPassword = true
        }
    }

    /** The recipient dialog's button. Nothing is ticked for the user; unverified contacts need a second, warned press. */
    fun confirmRecipients() {
        if (locked || !choosingRecipients) return
        failure = when {
            recipientCount == 0 -> invalid("contact-required", "contacts")
            recipientCount > MAX_RECIPIENTS -> invalid("recipient-limit", "contacts")
            selectedContacts.size != selected.size -> invalid("contact-missing", "contacts")
            ownSelected && ownPublicKey.isEmpty() -> invalid("invalid-path", "contacts")
            else -> { invalidField = null; null }
        }
        if (failure != null) return
        if (unverifiedChosen.isNotEmpty() && !unverifiedWarned) { unverifiedWarned = true; return }
        choosingRecipients = false
        if (signing) askingPassword = true else runFile()
    }

    fun confirmPassword() {
        if (locked || !askingPassword) return
        failure = when {
            password.text.isEmpty() -> invalid("password-empty", "password")
            bytes(password.text) > 1024 -> invalid("password-limit", "password")
            else -> { invalidField = null; null }
        }
        if (failure != null) return
        askingPassword = false
        runFile()
    }
    fun cancelPassword() { if (!locked) { askingPassword = false; clear(password); closeRecipients() } }

    private fun runFile() {
        val action = fileAction ?: return
        val encrypting = action == Kind.Encrypt
        val sign = encrypting && !unsigned
        val chosen = if (encrypting) selectedContacts else emptyList()
        val own = encrypting && ownSelected
        val others = if (encrypting) otherKeys.toList() else emptyList()
        val source = input
        closeRecipientsNow()
        jobId = UUID.randomUUID().toString()
        busy = true; cancelling = false
        val request = OperationRequest(
            id = jobId, kind = action, input = source,
            keyPath = if (encrypting) (if (own) ownPublicKey else "") else decryptKey,
            password = if (sign || !encrypting) password.text else "",
            contacts = chosen.map { it.id }, keyPaths = others,
            signKeyPath = if (sign) signKeyPath else "", unsigned = encrypting && unsigned,
            requireSignature = !encrypting && requireSignature, stage = true,
        )
        scope.launch {
            var unavailable = false
            try {
                // The request is written on the worker thread, then the fields are cleared.
                val outcome = native { Backend.run(request, ::reportProgress) }
                staged = Unsaved(outcome, action, source, chosen, own, others)
                // Every encryption starts with nothing ticked.
                if (encrypting) selected.clear()
            } catch (error: Throwable) {
                val message = code(error)
                failure = message
                // Nothing was encrypted. Deselect the contact that could not be used and
                // require the user to start again with the recipients that remain.
                unavailable = chosen.isNotEmpty() && message in CONTACT_FAILURES
                if (unavailable) {
                    val failed = chosen.firstOrNull { it.id == (error as? BridgeFailure)?.contact }
                    failedContact = failed
                    if (failed != null) selected.remove(failed.id) else selected.clear()
                }
            } finally {
                // The native layer zeroized its copy; the field is cleared here.
                clearSecrets()
                busy = false; cancelling = false; fileAction = null
            }
            save()
            if (unavailable) refreshContacts()
            closeIfRequested()
        }
    }

    /** Asks where the finished file goes, as Android does. Closing the dialog gives the result up. */
    private suspend fun save() {
        val item = staged ?: return
        if (closeRequested) return
        choosing = PathField.Output
        try {
            val name = fileName(item.source).ifEmpty { "file" }
            val plain = name.removeSuffix(".nkem").ifEmpty { "file" }
            val suggested = if (item.kind == Kind.Encrypt) "$name.nkem" else if (plain != name) plain else "decrypted-$name"
            val destination = files.save(suggested, java.io.File(item.source).parent)
            if (destination == null) {
                staged = null; unsaved = false
                native { Backend.discardOutput() }
                failure = "cancelled"
                return
            }
            native { Backend.saveOutput(destination) }
            staged = null; unsaved = false
            result = item.outcome.copy(output = destination)
            recipients = item.contacts; ownRecipient = item.own; otherRecipients = item.others
            successMessage = when {
                item.kind != Kind.Encrypt -> "success"
                item.outcome.format == 5 -> "encryptedSigned"
                else -> "encryptedUnsigned"
            }
        } catch (error: Throwable) {
            failure = code(error)
            // The staged file is still there when only the chosen place failed.
            if (failure == "save-failed") unsaved = true else { staged = null; unsaved = false }
        } finally { choosing = null }
    }
    fun saveAgain() {
        if (locked || staged == null) return
        failure = null
        scope.launch { save(); closeIfRequested() }
    }
    private fun discardStaged() {
        if (staged == null) return
        staged = null; unsaved = false
        scope.launch { runCatching { native { Backend.discardOutput() } } }
    }
    private fun fileName(path: String) = path.split('/', '\\').last()

    // Core reports progress often; the UI takes the latest value once per frame.
    private val latestProgress = java.util.concurrent.atomic.AtomicReference<Pair<Long, Long>?>(null)
    private fun reportProgress(processed: Long, total: Long) {
        val id = jobId
        if (latestProgress.getAndSet(processed to total) == null) scope.launch {
            val value = latestProgress.getAndSet(null)
            if (value != null && busy && jobId == id) progress = value
        }
    }

    private fun copyOf(state: TextFieldState) = TextFieldState().apply { edit { append(state.text) } }

    private fun saveContact() {
        resetFeedback()
        highlighted = null
        failure = validate()
        if (failure != null) return
        val path = if (contactPaste) "" else contactKeyPath
        val paste = contactPaste
        val noteText = note
        contactBusy = true
        scope.launch {
            try {
                val contact = native { Backend.saveContact(path, if (paste) contactKeyText.text else "", paste, noteText) }
                contactKeyPath = ""; note = ""
                successMessage = "contactSaved"
                result = Outcome(fingerprint = contact.fingerprint)
                highlighted = contact.id
            } catch (error: Throwable) {
                if (!askUpgrade(error, path, if (paste) copyOf(contactKeyText) else null, noteText, false)) {
                    val message = code(error)
                    failure = message
                    highlighted = (error as? BridgeFailure)?.contact
                    if (message == "note-limit") invalidField = "note"
                    else if (message in listOf("public-key-invalid", "invalid-path", "key-limit", "key-line-limit")) invalidField = if (paste) "contactKeyText" else "contactKeyPath"
                }
            } finally {
                clear(contactKeyText)
                contactBusy = false
            }
            refreshContacts()
            closeIfRequested()
        }
    }

    // An identity key replacing a legacy contact waits for the user to check its
    // full fingerprint: anyone with the legacy key could attach signing keys.
    private fun askUpgrade(error: Throwable, path: String, text: TextFieldState?, noteText: String, fromSigner: Boolean): Boolean {
        val details = error as? BridgeFailure
        if (details?.code != "upgrade-unconfirmed" || details.contact == null || details.fingerprint == null) { text?.let(::clear); return false }
        upgradePrompt = UpgradePrompt(path, text, noteText, details.contact, details.fingerprint, fromSigner)
        upgradeChecked = false
        return true
    }
    val upgradeName get() = contacts.firstOrNull { it.id == upgradePrompt?.contact }?.let(::label) ?: t("savedContact")

    fun confirmUpgrade(accept: Boolean) {
        val prompt = upgradePrompt ?: return
        upgradePrompt = null
        upgradeChecked = false
        if (!accept || locked) { prompt.keyText?.let(::clear); return }
        contactBusy = true
        failure = null
        scope.launch {
            try {
                val contact = native { Backend.saveContact(prompt.keyPath, prompt.keyText?.text ?: "", prompt.keyText != null, prompt.note, prompt.fingerprint) }
                if (prompt.fromSigner) signerSaved = true
                else {
                    contactKeyPath = ""; note = ""
                    successMessage = "contactSaved"
                    result = Outcome(fingerprint = contact.fingerprint)
                }
                highlighted = contact.id
            } catch (error: Throwable) { failure = code(error) } finally {
                prompt.keyText?.let(::clear)
                contactBusy = false
            }
            refreshContacts()
            closeIfRequested()
        }
    }

    // Saves the public key Core extracted from a valid signature. The user is
    // asked to compare the fingerprint first; later files then show as trusted.
    fun saveSigner() {
        val key = result?.signature?.signerKey
        if (locked || key == null) return
        contactBusy = true
        failure = null
        scope.launch {
            try {
                val contact = native { Backend.saveContact("", key, true, "") }
                signerSaved = true
                highlighted = contact.id
            } catch (error: Throwable) {
                if (!askUpgrade(error, "", TextFieldState(key), "", true)) {
                    failure = code(error)
                    if (failure == "contact-exists") { failure = null; signerSaved = true }
                }
            } finally { contactBusy = false }
            refreshContacts()
            closeIfRequested()
        }
    }

    fun startEdit(contact: Contact) {
        if (locked) return
        resetFeedback()
        confirmingDelete = null
        editing = contact.id
        noteDraft = contact.note
    }
    fun cancelEdit() { editing = null }
    fun editNoteDraft(value: String) { noteDraft = value; edited("noteDraft") }

    fun saveNote(contact: Contact) {
        if (locked) return
        resetFeedback()
        feedback = Feedback.List
        if (invalidNote(noteDraft)) { failure = invalid("note-limit", "noteDraft"); return }
        val draft = noteDraft
        contactBusy = true
        scope.launch {
            try {
                native { Backend.updateNote(contact.id, draft) }
                editing = null
                successMessage = "noteSaved"
                result = Outcome()
                highlighted = contact.id
            } catch (error: Throwable) { failure = code(error) } finally { contactBusy = false }
            refreshContacts()
            closeIfRequested()
        }
    }

    fun askDelete(contact: Contact) {
        if (locked) return
        resetFeedback()
        editing = null
        confirmingDelete = contact.id
    }
    fun cancelDelete() { confirmingDelete = null }

    fun removeContact(contact: Contact) {
        if (locked) return
        resetFeedback()
        feedback = Feedback.List
        contactBusy = true
        scope.launch {
            try {
                native { Backend.deleteContact(contact.id) }
                successMessage = "contactDeleted"
                result = Outcome()
            } catch (error: Throwable) { failure = code(error) } finally {
                confirmingDelete = null
                contactBusy = false
            }
            refreshContacts()
            closeIfRequested()
        }
    }

    fun useContact(contact: Contact) {
        if (locked) return
        selected.clear(); selected.add(contact.id)
        switchTab(View.Files)
    }
    fun toggleContact(contact: Contact) {
        if (locked) return
        if (contact.id in selected) selected.remove(contact.id) else if (!atRecipientLimit) selected.add(contact.id)
        unverifiedWarned = false
        edited("contacts")
    }
    fun encryptSelected() {
        if (locked || selected.isEmpty()) return
        switchTab(View.Files)
    }
    fun clearSelection() { if (!locked) selected.clear() }
    fun toggleOwn() { if (!locked && (ownSelected || !atRecipientLimit)) { ownSelected = !ownSelected; unverifiedWarned = false; edited("contacts") } }
    fun closeRecipients() { if (!locked) { closeRecipientsNow(); fileAction = null; invalidField = null; failure = null } }
    private fun closeRecipientsNow() { choosingRecipients = false; askingPassword = false; ownSelected = false; otherKeys.clear(); unverifiedWarned = false }
    fun removeOtherKey(path: String) { if (!locked) otherKeys.remove(path) }
    /** "Other public key file…": a one-off recipient for this file, never saved. */
    fun addOtherKey() {
        if (locked || atRecipientLimit) return
        choosing = PathField.KeyPath
        scope.launch {
            try {
                val value = files.open()
                if (value != null && value != ownPublicKey && value !in otherKeys) { otherKeys.add(value); edited("contacts") }
            } catch (error: Throwable) { failure = code(error) } finally { choosing = null }
        }
    }

    fun toggleVerified(contact: Contact) {
        if (locked) return
        resetFeedback()
        feedback = Feedback.List
        contactBusy = true
        scope.launch {
            try { native { Backend.setVerified(contact.id, contact.fingerprint, !contact.verified) } } catch (error: Throwable) { failure = code(error) } finally { contactBusy = false }
            refreshContacts()
            closeIfRequested()
        }
    }

    fun togglePin(contact: Contact) {
        if (locked) return
        resetFeedback()
        feedback = Feedback.List
        contactBusy = true
        scope.launch {
            try { native { Backend.setPinned(contact.id, !contact.pinned) } } catch (error: Throwable) { failure = code(error) } finally { contactBusy = false }
            refreshContacts()
            closeIfRequested()
        }
    }

    fun cancel() {
        if (!busy || cancelling || cancelPending) return
        val id = jobId
        cancelPending = true
        scope.launch {
            try { if (native { Bridge.cancel(id) } && busy && jobId == id) cancelling = true }
            catch (error: Throwable) { failure = code(error) }
            finally { cancelPending = false }
        }
    }

    /** The window's close button: a running operation is stopped and finishes first. */
    fun requestClose() {
        if (Bridge.deferClose()) { closeRequested = true; return }
        leave()
    }
    private fun closeIfRequested() { if (closeRequested && Bridge.closeReady()) leave() }
    // An output that was never saved is deleted before the window goes.
    private fun leave() {
        clearSecrets()
        if (staged != null) runCatching { Backend.discardOutput() }
        exit()
    }

    companion object {
        private const val DAY = 24L * 60 * 60 * 1000
        private val CONTACT_FAILURES = listOf("contact-missing", "contact-invalid", "contact-mismatch", "contact-storage")
    }
}
