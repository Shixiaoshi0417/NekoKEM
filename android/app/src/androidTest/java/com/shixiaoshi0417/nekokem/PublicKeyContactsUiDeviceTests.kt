package com.shixiaoshi0417.nekokem

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
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
    fun find(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (index in 0 until root.childCount) find(root.getChild(index), predicate)?.let { return it }
        return null
    }
    fun text(value: String): AccessibilityNodeInfo? = find(instrumentation.uiAutomation.rootInActiveWindow) {
        it.text?.toString()?.contains(value) == true || it.contentDescription?.toString() == value
    }
    fun awaitText(value: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            text(value)?.let { return it }
            find(instrumentation.uiAutomation.rootInActiveWindow) { it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
        error("Missing contacts UI text: $value")
    }
    fun click(value: String) {
        awaitText(value)
        var target = checkNotNull(find(instrumentation.uiAutomation.rootInActiveWindow) {
            it.text?.toString() == value || it.contentDescription?.toString() == value
        }) { "Missing exact contacts action: $value" }
        while (!target.isClickable) target = checkNotNull(target.parent)
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
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
        click(checkNotNull(activity).getString(R.string.action_edit_contact_note))
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
        click(checkNotNull(activity).getString(R.string.action_choose_contact))
        awaitText(checkNotNull(activity).getString(R.string.contact_picker_title))
        var recipient = awaitText(contact.fingerprint)
        while (!recipient.isClickable) recipient = checkNotNull(recipient.parent)
        check(recipient.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        awaitText(editedNote)
        recreate()
        awaitText(checkNotNull(activity).getString(R.string.key_source_contacts))
        awaitText(editedNote)
        capture("contacts-selected")
        openContacts()
        click(checkNotNull(activity).getString(R.string.action_delete))
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
            fun dump(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 50 || count++ >= 512) return
                tree.append("  ".repeat(depth)).append(node.className).append(" | ")
                    .append(node.text).append(" | ").append(node.contentDescription).append('\n')
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
