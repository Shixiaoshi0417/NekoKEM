package com.shixiaoshi0417.nekokem

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
import com.shixiaoshi0417.nekokem.files.SafFileWorkflow
import com.shixiaoshi0417.nekokem.i18n.AppLanguages
import com.shixiaoshi0417.nekokem.keys.LocalKeyManager
import com.shixiaoshi0417.nekokem.keys.PublicKeyContacts
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
    val priorLanguage = AppLanguages.selection(context)
    // On the API 35 emulator no scroll event from Compose reached UiAutomation, and its
    // cached nodes kept an old child list after the page scrolled: a failure dump
    // lacked buttons the screenshot showed. Refresh every node from the app before
    // trusting it; refresh() fails for a node that no longer exists.
    fun fresh(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? = node?.takeIf { it.refresh() }
    fun find(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val node = fresh(root) ?: return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }
    fun text(value: String): AccessibilityNodeInfo? = find(instrumentation.uiAutomation.rootInActiveWindow) {
        it.text?.toString()?.contains(value) == true || it.contentDescription?.toString() == value
    }
    fun layout(node: AccessibilityNodeInfo): String = buildString {
        for (index in 0 until node.childCount) {
            val child = fresh(node.getChild(index)) ?: continue
            append(Rect().also { child.getBoundsInScreen(it) }.toShortString()).append(child.text).append(';')
        }
    }
    // Three steps forward, three back: reaches content on either side of the viewport.
    fun scroll(step: Int) {
        val scrollable = find(instrumentation.uiAutomation.rootInActiveWindow) { it.isScrollable } ?: return
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
            var target = find(instrumentation.uiAutomation.rootInActiveWindow) {
                it.text?.toString() == value || it.contentDescription?.toString() == value
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
        val previousWindow = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow).windowId
        click(value)
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            val current = instrumentation.uiAutomation.rootInActiveWindow
            if (current != null && current.windowId != previousWindow) return
            SystemClock.sleep(100)
        }
        error("Contact dialog did not open: $value")
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
        click(checkNotNull(activity).getString(R.string.navigation_open_menu))
        click(checkNotNull(activity).getString(R.string.navigation_contacts))
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
        instrumentation.runOnMainSync { AppLanguages.setSelection(context, "en") }
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        openContacts()
        awaitText("CI recipient")
        awaitText(contact.fingerprint)
        openDialog(checkNotNull(activity).getString(R.string.action_edit_contact_note))
        val editorDeadline = SystemClock.uptimeMillis() + 10000
        var editor: AccessibilityNodeInfo? = null
        while (editor == null && SystemClock.uptimeMillis() < editorDeadline) {
            editor = find(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable }
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
        click(checkNotNull(activity).getString(R.string.action_use_contact))
        awaitText(checkNotNull(activity).getString(R.string.key_source_contacts))
        awaitText(editedNote)
        // A selected radio item is deliberately not exposed as clickable by Compose.
        // Exercise the picker by selecting this recipient from the default-key state.
        click(checkNotNull(activity).getString(R.string.action_restore_default_public_key))
        openDialog(checkNotNull(activity).getString(R.string.action_choose_contact))
        awaitText(checkNotNull(activity).getString(R.string.contact_picker_title))
        awaitText(contact.fingerprint)
        val pickerDeadline = SystemClock.uptimeMillis() + 10000
        var recipient: AccessibilityNodeInfo? = null
        while (recipient == null && SystemClock.uptimeMillis() < pickerDeadline) {
            var option = text(contact.fingerprint)
            while (option != null && !option.isClickable) option = fresh(option.parent)
            recipient = option
            if (recipient == null) SystemClock.sleep(100)
        }
        check(checkNotNull(recipient) { "Saved recipient radio option is not clickable" }
            .performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        awaitText(checkNotNull(activity).getString(R.string.key_source_contacts))
        awaitText(editedNote)
        recreate()
        awaitText(checkNotNull(activity).getString(R.string.key_source_contacts))
        awaitText(editedNote)
        capture("contacts-selected")
        // Checkboxes on the contacts page change nothing until the explicit action.
        openContacts()
        click("${checkNotNull(activity).getString(R.string.contact_select_recipient)} · $editedNote")
        awaitText(checkNotNull(activity).getString(R.string.contact_selection_count, 1))
        awaitText(checkNotNull(activity).getString(R.string.contact_recipients_hint))
        capture("contacts-multi-select")
        click(checkNotNull(activity).getString(R.string.action_encrypt_for_selected))
        awaitText(checkNotNull(activity).getString(R.string.key_source_contacts))
        awaitText(editedNote)
        openContacts()
        click(checkNotNull(activity).getString(R.string.action_clear_selection))
        val encryptSelected = checkNotNull(activity).getString(R.string.action_encrypt_for_selected)
        val clearedDeadline = SystemClock.uptimeMillis() + 10000
        while (text(encryptSelected) != null && SystemClock.uptimeMillis() < clearedDeadline) SystemClock.sleep(100)
        check(text(encryptSelected) == null) { "Contact selection was not cleared" }
        openDialog(checkNotNull(activity).getString(R.string.action_delete))
        awaitText(checkNotNull(activity).getString(R.string.contact_delete_title))
        click(checkNotNull(activity).getString(R.string.action_delete))
        awaitText(checkNotNull(activity).getString(R.string.contact_empty))
        check(contacts.stageForEncryption(contact.id).key == null)
        click(checkNotNull(activity).getString(R.string.navigation_open_menu))
        click(checkNotNull(activity).getString(R.string.navigation_files))
        awaitText(checkNotNull(activity).getString(R.string.contact_unavailable))
        click(checkNotNull(activity).getString(R.string.action_restore_default_public_key))
        awaitText(checkNotNull(activity).getString(R.string.key_source_app_default))
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
            dump(instrumentation.uiAutomation.rootInActiveWindow, 0)
            File(context.cacheDir, "i18n-screens/contacts-failure.txt").writeText(tree.toString())
        }
        throw error
    } finally {
        savedId?.let { if (contacts.readState().contacts.any { contact -> contact.id == it }) contacts.delete(it) }
        instrumentation.runOnMainSync {
            activity?.finish()
            AppLanguages.setSelection(context, priorLanguage)
        }
        publicKey.delete()
        privateKey.delete()
        root.delete()
    }
}
