package com.shixiaoshi0417.nekokem

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.shixiaoshi0417.nekokem.files.SafFileWorkflow
import com.shixiaoshi0417.nekokem.i18n.AppLanguages
import com.shixiaoshi0417.nekokem.keys.LocalKeyManager
import com.shixiaoshi0417.nekokem.keys.PublicKeyContacts
import com.shixiaoshi0417.nekokem.nativecore.NativeBridge
import java.io.File

/** Exercise actual Compose navigation, remark editing, selection and Activity recreation. */
internal fun runPublicKeyContactsUiTests(instrumentation: Instrumentation) {
    val context = instrumentation.targetContext
    val manager = LocalKeyManager(context)
    val contacts = PublicKeyContacts(context, manager)
    val workflow = SafFileWorkflow(context, manager)
    val root = File.createTempFile("ci-contact-ui-", ".tmp", context.cacheDir).apply {
        check(delete() && mkdir())
        Os.chmod(absolutePath, 0x1C0)
    }
    val publicKey = File(root, "public.key")
    val privateKey = File(root, "private.nkpr")
    var activity: Activity? = null
    var savedId: String? = null
    var generatedDeviceKey = false
    val priorLanguage = AppLanguages.selection(context)
    // On the API 35 emulator no scroll event from Compose reached UiAutomation, and its
    // cached nodes kept an old child list after the page scrolled: a failure dump
    // lacked buttons the screenshot showed. Refresh every node from the app before
    // trusting it; refresh() fails for a node that no longer exists.
    fun fresh(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? = node?.takeIf { it.refresh() }
    // Android 8 can keep reporting a closed dialog as the active window. Search the
    // topmost application window on screen instead: an open dialog when there is one,
    // otherwise the activity.
    val automation = instrumentation.uiAutomation
    val priorServiceInfo = automation.serviceInfo
    automation.serviceInfo = automation.serviceInfo.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
    fun roots(): List<AccessibilityNodeInfo> = runCatching {
        automation.windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .mapNotNull { it.root }
    }.getOrDefault(emptyList()).ifEmpty { listOfNotNull(automation.rootInActiveWindow) }
    fun screen(): AccessibilityNodeInfo? = roots().firstOrNull()
    fun find(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val node = fresh(root) ?: return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }
    fun text(value: String): AccessibilityNodeInfo? = roots().firstNotNullOfOrNull { root ->
        find(root) { it.text?.toString()?.contains(value) == true || it.contentDescription?.toString() == value }
    }
    fun layout(node: AccessibilityNodeInfo): String = buildString {
        for (index in 0 until node.childCount) {
            val child = fresh(node.getChild(index)) ?: continue
            append(Rect().also { child.getBoundsInScreen(it) }.toShortString()).append(child.text).append(';')
        }
    }
    // Three steps forward, three back: reaches content on either side of the viewport.
    fun scroll(step: Int) {
        val scrollable = roots().firstNotNullOfOrNull { root -> find(root) { it.isScrollable } } ?: return
        val action = if ((step / 3) % 2 == 0)
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        // Compose offers only the directions it can still scroll.
        if (scrollable.actionList.none { it.id == action }) return
        val before = layout(scrollable)
        if (!scrollable.performAction(action)) return
        // Compose applies the scroll on a later frame, which a loaded emulator can
        // delay by seconds. Wait until the content has moved so no lookup sees, and no
        // later scroll queues behind, a position that is about to change.
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            if (!scrollable.refresh() || layout(scrollable) != before) return
        }
    }
    fun awaitText(value: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 30000
        var attempts = 0
        while (SystemClock.uptimeMillis() < deadline) {
            text(value)?.let { return it }
            scroll(attempts++)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
        error("Missing contacts UI text: $value")
    }
    fun click(value: String) {
        awaitText(value)
        // The page may recompose between lookup and click, leaving a node that refuses
        // the action or briefly lacks the exact label, or move the target out of view,
        // where Compose leaves it out of the accessibility tree. Retry, and keep
        // scrolling while it fails so the target comes back into view.
        val deadline = SystemClock.uptimeMillis() + 30000
        var attempts = 0
        while (true) {
            var target = roots().firstNotNullOfOrNull { root ->
                find(root) { it.text?.toString() == value || it.contentDescription?.toString() == value }
            }
            var observed = if (target == null) "not in tree" else "label found"
            while (target != null && !target.isClickable) target = fresh(target.parent)
            if (target != null) {
                observed = "enabled=${target.isEnabled}, visible=${target.isVisibleToUser}, " +
                    "click=${target.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }}"
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) break
            }
            check(SystemClock.uptimeMillis() < deadline) { "Missing exact contacts action: $value ($observed)" }
            if (++attempts % 5 == 0) scroll(attempts / 5 - 1)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
        instrumentation.waitForIdleSync()
    }
    fun openDialog(value: String) {
        val previousWindow = checkNotNull(screen()).windowId
        click(value)
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            val current = screen()
            if (current != null && current.windowId != previousWindow) return
            SystemClock.sleep(100)
        }
        error("Contact dialog did not open: $value")
    }
    // A contact card merges its texts into one clickable node; open it by its label.
    fun openContact(label: String) {
        awaitText(label)
        val deadline = SystemClock.uptimeMillis() + 30000
        while (true) {
            var card = text(label)
            while (card != null && !card.isClickable) card = fresh(card.parent)
            if (card != null && card.performAction(AccessibilityNodeInfo.ACTION_CLICK)) break
            check(SystemClock.uptimeMillis() < deadline) { "Contact card did not open: $label" }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
        instrumentation.waitForIdleSync()
        awaitText(checkNotNull(activity).getString(R.string.contact_verify_title))
    }
    // The swipe actions are offered to accessibility services as custom actions on the card.
    fun swipeAction(label: String) {
        val deadline = SystemClock.uptimeMillis() + 30000
        while (true) {
            val node = roots().firstNotNullOfOrNull { root ->
                find(root) { candidate -> candidate.actionList.any { it.label?.toString() == label } }
            }
            val action = node?.actionList?.firstOrNull { it.label?.toString() == label }
            if (node != null && action != null && node.performAction(action.id)) break
            check(SystemClock.uptimeMillis() < deadline) { "Missing contact card action: $label" }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
        instrumentation.waitForIdleSync()
    }
    fun awaitVerified(id: String, verified: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (contacts.readState().contacts.single { it.id == id }.verified != verified &&
            SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        check(contacts.readState().contacts.single { it.id == id }.verified == verified)
    }
    fun recreate() {
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync { checkNotNull(activity).recreate() }
            activity = checkNotNull(monitor.waitForActivityWithTimeout(10000))
            instrumentation.waitForIdleSync()
        } finally { instrumentation.removeMonitor(monitor) }
    }
    fun openContacts() {
        clickNavigationTab(instrumentation, checkNotNull(activity).getString(R.string.tab_contacts))
    }
    // Drags the page by half a screen (down when [forward]) so long pages are captured whole.
    fun scrollPage(forward: Boolean) {
        val metrics = context.resources.displayMetrics
        val x = metrics.widthPixels / 2
        val (from, to) = if (forward) metrics.heightPixels * 3 / 4 to metrics.heightPixels / 4 else metrics.heightPixels / 4 to metrics.heightPixels * 3 / 4
        instrumentation.uiAutomation.executeShellCommand("input swipe $x $from $x $to 600").close()
        SystemClock.sleep(1000)
        instrumentation.uiAutomation.waitForIdle(500, 5000)
    }
    fun capture(name: String) {
        val directory = File(context.cacheDir, "i18n-screens").apply { check(isDirectory || mkdir()) }
        val captured = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val screenshot = if (captured.config == Bitmap.Config.HARDWARE) {
            checkNotNull(captured.copy(Bitmap.Config.ARGB_8888, false)).also { captured.recycle() }
        } else captured
        try {
            File(directory, "$name.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
    }
    try {
        generateContactTestKeypair(publicKey, privateKey)
        val key = checkNotNull(workflow.stageTemporaryPublicKey(Uri.fromFile(publicKey), "ui-recipient.key").key)
        val contact = try { checkNotNull(contacts.save(key, "CI recipient").contact) }
        finally { workflow.discardTemporaryPublicKey(key) }
        savedId = contact.id
        // A matching scan marks the contact; a stale fingerprint marks nothing.
        check(contacts.setVerified(contact.id, "00" + contact.fingerprint.drop(2), true).code != NativeBridge.RESULT_SUCCESS)
        check(!contacts.readState().contacts.single { it.id == contact.id }.verified)
        check(contacts.setVerified(contact.id, contact.fingerprint, true).code == NativeBridge.RESULT_SUCCESS)
        check(contacts.readState().contacts.single { it.id == contact.id }.verified)
        // Pinning and archiving persist; archiving unpins.
        check(contacts.setPinned(contact.id, true).code == NativeBridge.RESULT_SUCCESS)
        check(contacts.readState().contacts.single { it.id == contact.id }.pinned)
        check(contacts.setArchived(contact.id, true).code == NativeBridge.RESULT_SUCCESS)
        contacts.readState().contacts.single { it.id == contact.id }.let { check(it.archived && !it.pinned) }
        check(contacts.setArchived(contact.id, false).code == NativeBridge.RESULT_SUCCESS)
        check(!contacts.readState().contacts.single { it.id == contact.id }.archived)
        // The fingerprint QR code needs this device's own key.
        if (manager.readState().fingerprint == null) {
            val password = "fingerprint-qr-device-test".toByteArray()
            try { check(manager.generateKeypair(password) == NativeBridge.RESULT_SUCCESS) } finally { password.fill(0) }
            generatedDeviceKey = true
        }
        instrumentation.runOnMainSync { AppLanguages.setSelection(context, "en") }
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        openContacts()
        awaitText("CI recipient")
        // Let the page transition finish before the frame is taken.
        SystemClock.sleep(800)
        instrumentation.uiAutomation.waitForIdle(500, 5000)
        capture("contacts-list")
        // Swiping right pins the card; the same action unpins it again.
        swipeAction(checkNotNull(activity).getString(R.string.action_pin))
        val pinDeadline = SystemClock.uptimeMillis() + 10000
        while (!contacts.readState().contacts.single { it.id == contact.id }.pinned &&
            SystemClock.uptimeMillis() < pinDeadline) SystemClock.sleep(100)
        check(contacts.readState().contacts.single { it.id == contact.id }.pinned)
        awaitText(checkNotNull(activity).getString(R.string.contact_pinned_label))
        swipeAction(checkNotNull(activity).getString(R.string.action_unpin))
        val unpinDeadline = SystemClock.uptimeMillis() + 10000
        while (contacts.readState().contacts.single { it.id == contact.id }.pinned &&
            SystemClock.uptimeMillis() < unpinDeadline) SystemClock.sleep(100)
        check(!contacts.readState().contacts.single { it.id == contact.id }.pinned)
        openContact("CI recipient")
        // Wait out the pin snackbar so the whole page is visible.
        SystemClock.sleep(4500)
        instrumentation.uiAutomation.waitForIdle(500, 5000)
        capture("contacts-detail")
        awaitText(contact.fingerprint)
        awaitText(checkNotNull(activity).getString(R.string.contact_verified))
        awaitText(checkNotNull(activity).getString(R.string.action_scan_fingerprint))
        capture("contacts-verified")
        // The rest of the page, then back to where the test continues.
        scrollPage(true)
        capture("contacts-detail-end")
        scrollPage(false)
        // Clearing the mark makes the next use of the contact ask first.
        click(checkNotNull(activity).getString(R.string.action_unmark_verified))
        awaitVerified(contact.id, false)
        awaitText(checkNotNull(activity).getString(R.string.action_mark_verified))
        openDialog(checkNotNull(activity).getString(R.string.action_use_contact))
        awaitText(checkNotNull(activity).getString(R.string.contact_unverified_title))
        capture("contacts-unverified-warning")
        click(checkNotNull(activity).getString(R.string.action_cancel))
        check(contacts.readState().contacts.single { it.id == contact.id }.verified.not())
        click(checkNotNull(activity).getString(R.string.action_mark_verified))
        awaitVerified(contact.id, true)
        awaitText(checkNotNull(activity).getString(R.string.action_unmark_verified))
        openDialog(checkNotNull(activity).getString(R.string.action_edit_contact_note))
        val editorDeadline = SystemClock.uptimeMillis() + 10000
        var editor: AccessibilityNodeInfo? = null
        while (editor == null && SystemClock.uptimeMillis() < editorDeadline) {
            editor = find(screen()) { it.isEditable }
            if (editor == null) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(100)
            }
        }
        val field = checkNotNull(editor) { "Contact note editor did not render" }
        val editedNote = "CI updated / 备注 🐈"
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, editedNote)
        }))
        click(checkNotNull(activity).getString(R.string.action_save_contact))
        val deadline = SystemClock.uptimeMillis() + 10000
        while (contacts.readState().contacts.single { it.id == contact.id }.note != editedNote &&
            SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        awaitText(editedNote)
        check(contacts.readState().contacts.single { it.id == contact.id }.note == editedNote)
        // Using a contact opens Files; Encrypt's dialog then starts with it ticked.
        click(checkNotNull(activity).getString(R.string.action_use_contact))
        awaitText(checkNotNull(activity).getString(R.string.action_select_file))
        recreate()
        awaitText(checkNotNull(activity).getString(R.string.action_select_file))
        capture("contacts-selected")
        // Checkboxes on the contacts page change nothing until the explicit action.
        openContacts()
        click("${checkNotNull(activity).getString(R.string.contact_select_recipient)} · $editedNote")
        awaitText(checkNotNull(activity).getString(R.string.contact_selection_count, 1))
        awaitText(checkNotNull(activity).getString(R.string.contact_recipients_hint))
        capture("contacts-multi-select")
        click(checkNotNull(activity).getString(R.string.action_encrypt_for_selected))
        awaitText(checkNotNull(activity).getString(R.string.action_select_file))
        openContacts()
        click(checkNotNull(activity).getString(R.string.action_clear_selection))
        val encryptSelected = checkNotNull(activity).getString(R.string.action_encrypt_for_selected)
        val clearedDeadline = SystemClock.uptimeMillis() + 10000
        while (text(encryptSelected) != null && SystemClock.uptimeMillis() < clearedDeadline) SystemClock.sleep(100)
        check(text(encryptSelected) == null) { "Contact selection was not cleared" }
        openContact(editedNote)
        openDialog(checkNotNull(activity).getString(R.string.action_delete))
        awaitText(checkNotNull(activity).getString(R.string.contact_delete_title))
        click(checkNotNull(activity).getString(R.string.action_delete))
        awaitText(checkNotNull(activity).getString(R.string.contact_empty))
        check(contacts.stageForEncryption(contact.id).key == null)
        // Keys shows this device's fingerprint as a QR code. This runs last: Android 8 can
        // keep reporting a closed dialog as the active window, hiding the page behind it.
        clickNavigationTab(instrumentation, checkNotNull(activity).getString(R.string.tab_keys))
        openDialog(checkNotNull(activity).getString(R.string.action_show_fingerprint_qr))
        awaitText(checkNotNull(activity).getString(R.string.fingerprint_qr_hint))
        SystemClock.sleep(500)
        capture("keys-fingerprint-qr")
        click(checkNotNull(activity).getString(R.string.action_close))
    } catch (error: Throwable) {
        runCatching {
            capture("contacts-failure")
            val tree = StringBuilder()
            var count = 0
            fun dump(cached: AccessibilityNodeInfo?, depth: Int) {
                val node = fresh(cached)
                if (node == null || depth > 50 || count++ >= 512) return
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                tree.append("  ".repeat(depth)).append(node.className).append(" | ")
                    .append(node.text).append(" | ").append(node.contentDescription).append(" | ")
                    .append(bounds.toShortString()).append(" clickable=").append(node.isClickable)
                    .append(" enabled=").append(node.isEnabled).append(" visible=").append(node.isVisibleToUser)
                    .append('\n')
                for (index in 0 until node.childCount) dump(node.getChild(index), depth + 1)
            }
            dump(screen(), 0)
            File(context.cacheDir, "i18n-screens/contacts-failure.txt").writeText(tree.toString())
        }
        throw error
    } finally {
        savedId?.let { if (contacts.readState().contacts.any { contact -> contact.id == it }) contacts.delete(it) }
        if (generatedDeviceKey) manager.deleteKeypair()
        automation.serviceInfo = priorServiceInfo
        instrumentation.runOnMainSync {
            activity?.finish()
            AppLanguages.setSelection(context, priorLanguage)
        }
        publicKey.delete()
        privateKey.delete()
        root.delete()
    }
}
