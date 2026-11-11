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

enum class View(val key: String) { Keygen("keygen"), Encrypt("encrypt"), Decrypt("decrypt"), Fingerprint("fingerprint"), Contacts("contacts"), Settings("settings") }
enum class KeySource { Path, Paste, Contact }
// The key page generates identity keys, upgrades a 4.x key pair or exports the
// two-component public key that NekoKEM 4.x and earlier read.
enum class KeyMode { Generate, Upgrade, Export }
enum class PathField { Input, Output, KeyPath, PublicPath, PrivatePath, ContactKeyPath, SignKeyPath }
enum class Feedback { Form, List }

class UpgradePrompt(val keyPath: String, val keyText: TextFieldState?, val note: String, val contact: String, val fingerprint: String, val fromSigner: Boolean)

/**
 * The window's state and actions: desktop/src/App.vue, step for step, so the
 * two desktop apps behave the same. Secrets live only in [TextFieldState]s,
 * which are cleared (with their undo history) as soon as a request is written.
 */
class AppState(private val scope: CoroutineScope, private val files: FileChooser, private val exit: () -> Unit) {
    var view by mutableStateOf(View.Encrypt); private set
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
    var signKeyPath by mutableStateOf(""); private set
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
        View.Encrypt -> Kind.Encrypt
        View.Decrypt -> Kind.Decrypt
        else -> Kind.Fingerprint
    }
    val signing get() = view == View.Encrypt && !unsigned
    val passwordRequired get() = (view == View.Keygen && keyMode != KeyMode.Export) || view == View.Decrypt || signing
    val description get() = t(if (view == View.Keygen && keyMode != KeyMode.Generate) (if (keyMode == KeyMode.Upgrade) "upgradeHelp" else "exportHelp") else view.key + "Help")
    val percent get() = progress?.takeIf { it.second > 0 }?.let { (it.first * 100 / it.second).toInt().coerceIn(0, 100) }
    val selectedContacts get() = selected.mapNotNull { id -> contacts.firstOrNull { it.id == id } }
    val atRecipientLimit get() = selected.size >= MAX_RECIPIENTS
    // Signed files are NKEM v5. Unsigned output keeps v3 for one recipient and v4 for several.
    val formatLabel get() = when {
        view == View.Decrypt -> "NKEM v3 · v4 · v5"
        view == View.Encrypt && unsigned -> (if (source == KeySource.Contact && selected.size > 1) "NKEM v4" else "NKEM v3") + " · " + t("unsignedMark")
        view == View.Keygen && keyMode == KeyMode.Export -> "NekoKEM 4.x"
        else -> "NKEM v5"
    }
    val algorithms get() = if (signing || (view == View.Keygen && keyMode != KeyMode.Export)) "X448 + ML-KEM-1024 · Ed448 + ML-DSA-87" else "X448 + ML-KEM-1024"
    val signerContact get() = contacts.firstOrNull { it.id == result?.signature?.contact }
    val status get() = when {
        !loaded -> t("loading"); choosing != null -> t("choosing"); cancelling -> t("cancelling")
        busy || contactBusy -> t("working"); result != null -> t("success"); else -> t("ready")
    }

    private suspend fun <T> native(work: () -> T): T = withContext(Dispatchers.IO) { work() }
    private fun code(error: Throwable) = (error as? BridgeFailure)?.code?.takeIf(Strings::has) ?: "internal"

    fun start() = scope.launch {
        try {
            val settings = native { Backend.settings() }
            language = settings.language; selectedLanguage = settings.selection; version = settings.version
            runCatching { native { Backend.desktopPreferences() } }.getOrNull()?.let {
                requireSignature = it.requireSignature; signKeyPath = it.signKeyPath; sidebarOpen = it.sidebarOpen
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
        val value = DesktopPreferences(requireSignature, signKeyPath, sidebarOpen, style.wire)
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
        PathField.ContactKeyPath -> contactKeyPath; PathField.SignKeyPath -> signKeyPath
    }
    fun setPath(field: PathField, value: String) {
        when (field) {
            PathField.Input -> input = value
            PathField.Output -> { if (value != output) withdrawOverwrite(); output = value }
            PathField.KeyPath -> keyPath = value
            PathField.PublicPath -> { if (value != publicPath) withdrawReplace(); publicPath = value }
            PathField.PrivatePath -> { if (value != privatePath) withdrawReplace(); privatePath = value }
            PathField.ContactKeyPath -> contactKeyPath = value
            PathField.SignKeyPath -> signKeyPath = value
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
        recipients = emptyList(); failedContact = null; signerSaved = false
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
        confirmingUnsigned = false
        if ((value == View.Decrypt || value == View.Fingerprint) && source == KeySource.Contact) source = KeySource.Path
        view = value
        if (value == View.Encrypt || value == View.Decrypt || value == View.Contacts) scope.launch { refreshContacts() }
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
                    val name = input.split('/', '\\').last().ifEmpty { "file" }
                    files.save(when {
                        field == PathField.PublicPath -> "public.key"
                        field == PathField.PrivatePath -> "private.key.enc"
                        view == View.Keygen -> "legacy-public.key"
                        view == View.Encrypt -> "$name.nkem"
                        else -> name.removeSuffix(".nkem")
                    })
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
        } else {
            when (source) {
                KeySource.Paste -> if (keyText.text.isEmpty() || bytes(keyText.text) > 1048576) return invalid("key-limit", "keyText")
                // Explicit, currently listed contacts are required; never pick one implicitly.
                KeySource.Contact -> {
                    if (selected.isEmpty()) return invalid("contact-required", "contacts")
                    if (selected.size > MAX_RECIPIENTS) return invalid("recipient-limit", "contacts")
                    if (selectedContacts.size != selected.size) return invalid("contact-missing", "contacts")
                }
                KeySource.Path -> if (keyPath.isEmpty()) return invalid("invalid-path", "keyPath")
            }
            if (view != View.Fingerprint) {
                if (input.isEmpty()) return invalid("invalid-path", "input")
                if (output.isEmpty()) return invalid("invalid-path", "output")
            }
            // Signing is never skipped silently: it needs the sender's key and password.
            if (signing) {
                if (signKeyPath.isEmpty()) return invalid("sign-key-missing", "signKeyPath")
                if (password.text.isEmpty()) return invalid("password-empty", "password")
            }
        }
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
            if (view == View.Encrypt && source == KeySource.Contact && failure == null) failure = invalid("contact-missing", "contacts")
        }
    }

    fun submit() {
        if (locked || !loaded) return
        if (view == View.Contacts) return saveContact()
        if (view == View.Settings) return
        resetFeedback()
        failure = validate()
        if (failure != null) return
        val chosen = if (source == KeySource.Contact && view == View.Encrypt) selectedContacts else emptyList()
        jobId = UUID.randomUUID().toString()
        busy = true; cancelling = false
        val kind = kind
        val exporting = kind == Kind.ExportLegacy
        val keys = kind == Kind.Keygen || kind == Kind.Upgrade
        val request = OperationRequest(
            id = jobId, kind = kind, input = if (keys || exporting) "" else input, output = if (keys) "" else output,
            keyPath = if (exporting || (!keys && source == KeySource.Path)) keyPath else "",
            publicPath = if (keys) publicPath else "", privatePath = if (keys) privatePath else "",
            password = if (passwordRequired) password.text else "", confirmation = if (kind == Kind.Keygen) confirmation.text else "",
            keyText = if (!keys && !exporting && source == KeySource.Paste) keyText.text else "",
            paste = !keys && !exporting && source == KeySource.Paste, contacts = chosen.map { it.id },
            replace = kind == Kind.Keygen && replaceOffered && replace,
            overwrite = kind in listOf(Kind.Encrypt, Kind.Decrypt, Kind.ExportLegacy) && overwriteOffered && overwrite,
            signKeyPath = if (signing) signKeyPath else "", unsigned = view == View.Encrypt && unsigned,
            requireSignature = view == View.Decrypt && requireSignature,
        )
        if (signing) storePreferences()
        withdrawReplace(); withdrawOverwrite()
        val id = jobId
        scope.launch {
            var unavailable = false
            try {
                // The request is written on the worker thread, then the fields are cleared.
                val outcome = native { Backend.run(request, ::reportProgress) }
                result = outcome
                recipients = chosen
                successMessage = when (kind) {
                    Kind.Encrypt -> if (outcome.format == 5) "encryptedSigned" else "encryptedUnsigned"
                    Kind.Upgrade -> "keyUpgraded"; Kind.ExportLegacy -> "legacyExported"; else -> "success"
                }
            } catch (error: Throwable) {
                val message = code(error)
                failure = message
                if (kind == Kind.Keygen && message == "key-exists") replaceOffered = true
                if (kind in listOf(Kind.Encrypt, Kind.Decrypt, Kind.ExportLegacy) && message == "output-exists") overwriteOffered = true
                // Nothing was encrypted. Deselect the contact that could not be used and
                // require the user to start again with the recipients that remain.
                unavailable = chosen.isNotEmpty() && message in CONTACT_FAILURES
                if (unavailable) {
                    val failed = chosen.firstOrNull { it.id == (error as? BridgeFailure)?.contact }
                    failedContact = failed
                    if (failed != null) selected.remove(failed.id) else selected.clear()
                    invalidField = "contacts"
                }
            } finally {
                // The native layer zeroized its copy; the fields are cleared here.
                clearSecrets()
                busy = false; cancelling = false
            }
            if (unavailable) refreshContacts()
            closeIfRequested()
        }
    }

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
        source = KeySource.Contact
        switchTab(View.Encrypt)
    }
    fun toggleContact(contact: Contact) {
        if (locked) return
        if (contact.id in selected) selected.remove(contact.id) else if (!atRecipientLimit) selected.add(contact.id)
        edited("contacts")
    }
    fun encryptSelected() {
        if (locked || selected.isEmpty()) return
        source = KeySource.Contact
        switchTab(View.Encrypt)
    }
    fun clearSelection() { if (!locked) selected.clear() }

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

    /** The sidebar's Exit: only while nothing runs, like the Tauri app. */
    fun exitApp() {
        if (locked) return
        if (Bridge.deferClose()) { failure = "busy"; return }
        clearSecrets(); exit()
    }

    /** The window's close button: a running operation is stopped and finishes first. */
    fun requestClose() {
        if (Bridge.deferClose()) { closeRequested = true; return }
        clearSecrets(); exit()
    }
    private fun closeIfRequested() { if (closeRequested && Bridge.closeReady()) { clearSecrets(); exit() } }

    companion object {
        private const val DAY = 24L * 60 * 60 * 1000
        private val CONTACT_FAILURES = listOf("contact-missing", "contact-invalid", "contact-mismatch", "contact-storage")
    }
}
