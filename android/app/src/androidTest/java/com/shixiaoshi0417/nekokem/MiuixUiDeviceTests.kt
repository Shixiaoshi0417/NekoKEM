package com.shixiaoshi0417.nekokem

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.shixiaoshi0417.nekokem.files.SafFileWorkflow
import com.shixiaoshi0417.nekokem.i18n.AppLanguages
import com.shixiaoshi0417.nekokem.keys.LocalKeyManager
import com.shixiaoshi0417.nekokem.keys.PublicKeyContacts
import com.shixiaoshi0417.nekokem.nativecore.NativeBridge
import com.shixiaoshi0417.nekokem.ui.SignatureSettingsStore
import com.shixiaoshi0417.nekokem.update.Releases
import com.shixiaoshi0417.nekokem.update.UpdateSettingsStore
import com.shixiaoshi0417.nekokem.ui.UiStyle
import com.shixiaoshi0417.nekokem.ui.UiStyles
import java.io.File

/** Pins the Material style so the earlier device tests see the interface they expect. */
internal fun useMaterialStyle(context: Context) {
    check(UiStyles.setSelection(context, UiStyle.MATERIAL))
}

/**
 * A fresh install opens in MIUIX: every tab of the floating bar is reachable
 * and captured, dialogs open and close as MIUIX dialogs, and the interface
 * style switches to Material and back from either style's settings page.
 */
internal fun runMiuixUiTests(
    instrumentation: Instrumentation,
    screenshotPrefix: String,
    language: String = "en",
    pagesOnly: Boolean = false,
) {
    val context = instrumentation.targetContext
    val priorLanguage = AppLanguages.selection(context)
    val automation = instrumentation.uiAutomation
    val priorServiceInfo = automation.serviceInfo
    automation.serviceInfo = automation.serviceInfo.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
    // No stored choice, as after installation.
    check(context.getSharedPreferences("interface_style", Context.MODE_PRIVATE).edit().clear().commit())
    check(UiStyles.selection(context) == UiStyle.MIUIX) { "MIUIX is not the default interface style" }
    // Signing is on after installation.
    check(context.getSharedPreferences("signature_settings", Context.MODE_PRIVATE).edit().clear().commit())
    check(!SignatureSettingsStore.read(context).unsignedEncryption) { "Unsigned encryption is on by default" }
    // Automatic update checks are on after installation; a local stub announces a newer release.
    check(context.getSharedPreferences("update_settings", Context.MODE_PRIVATE).edit().clear().commit())
    check(UpdateSettingsStore.read(context).autoCheck) { "Automatic update checks are off by default" }
    Releases.source = releaseStub("99.0.0")
    instrumentation.runOnMainSync { check(AppLanguages.setSelection(context, language)) }
    var activity: Activity? = null
    fun find(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (index in 0 until root.childCount) find(root.getChild(index), predicate)?.let { return it }
        return null
    }
    fun exact(value: String): AccessibilityNodeInfo? {
        // Android 8 can keep reporting a closed dialog as the active window, so
        // every window on screen is searched, not only the active one.
        val roots = listOfNotNull(automation.rootInActiveWindow) +
            runCatching { automation.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        return roots.firstNotNullOfOrNull { root ->
            find(root) { it.text?.toString() == value || it.contentDescription?.toString() == value }
        }
    }
    // Every copy on screen. A window left over from the other shell can hold one that
    // cannot be pressed, so a press tries each of them.
    fun copies(value: String): List<AccessibilityNodeInfo> {
        val roots = listOfNotNull(automation.rootInActiveWindow) +
            runCatching { automation.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.text?.toString() == value || node.contentDescription?.toString() == value) found += node
            for (index in 0 until node.childCount) collect(node.getChild(index))
        }
        roots.forEach(::collect)
        return found
    }
    fun containing(value: String): AccessibilityNodeInfo? {
        val roots = listOfNotNull(automation.rootInActiveWindow) +
            runCatching { automation.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        return roots.firstNotNullOfOrNull { root -> find(root) { it.text?.toString()?.contains(value) == true } }
    }
    fun string(resource: Int) = checkNotNull(activity).getString(resource)
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
        // Let the bar's spring and the page fade settle before the frame is taken.
        SystemClock.sleep(800)
        instrumentation.uiAutomation.waitForIdle(500, 5000)
        val directory = File(context.cacheDir, "i18n-screens").apply { check(isDirectory || mkdir()) }
        val captured = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val screenshot = if (captured.config == Bitmap.Config.HARDWARE) {
            checkNotNull(captured.copy(Bitmap.Config.ARGB_8888, false)).also { captured.recycle() }
        } else captured
        try {
            File(directory, "$screenshotPrefix-miuix-$name.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
    }
    fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= deadline) {
                // Keep what the screen showed, since the accessibility tree may disagree with it.
                runCatching { capture("timeout") }
                error("MIUIX: $description")
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
    }
    // [past] scrolls one step before looking: the copy already found refused the press.
    fun reveal(value: String, past: Boolean = false) {
        var skip = past
        // On small screens the row can sit below the fold or under the floating bar.
        // A lazy list drops rows scrolled past, so look back up once the end is reached.
        for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            var dragged = false
            repeat(6) {
                if (!skip && exact(value) != null) return
                skip = false
                // The page is not always the first scrollable node, so each one is asked in turn.
                val scrollables = mutableListOf<AccessibilityNodeInfo>()
                fun collect(node: AccessibilityNodeInfo?) {
                    if (node == null) return
                    if (node.isScrollable) scrollables += node
                    for (index in 0 until node.childCount) collect(node.getChild(index))
                }
                collect(automation.rootInActiveWindow)
                if (scrollables.none { it.performAction(action) }) {
                    // A row scrolled out of view leaves the tree; one drag brings the page back when no node scrolls.
                    if (dragged) return@repeat
                    dragged = true
                    scrollPage(action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    return@repeat
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(300)
            }
        }
    }
    // False when the node is gone or has no clickable parent by the time it is pressed.
    fun tap(node: AccessibilityNodeInfo): Boolean {
        var target = node
        while (!target.isClickable) target = target.parent ?: return false
        if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return false
        instrumentation.waitForIdleSync()
        return true
    }
    fun click(value: String) {
        // A recomposition after the scroll can drop the node between the lookup and the press,
        // so a refused press looks the row up again.
        // A copy in a leftover window is found without scrolling and refuses the press while
        // the real row is still below the fold, so a refused press scrolls on before the next try.
        var refused = false
        await("missing or unpressable $value") {
            reveal(value, refused)
            val pressed = copies(value).any(::tap)
            refused = !pressed
            pressed
        }
    }
    // A dropdown that closes again before its menu is read is opened once more.
    fun choose(title: String, option: String) {
        repeat(3) {
            click(title)
            val deadline = SystemClock.uptimeMillis() + 3_000L
            while (SystemClock.uptimeMillis() < deadline) {
                // An entry still animating in can refuse the press; it is looked up again.
                // No scrolling here: that would close the menu.
                if (exact(option)?.let(::tap) == true) return
                instrumentation.waitForIdleSync()
                SystemClock.sleep(100)
            }
        }
        runCatching { capture("timeout") }
        error("MIUIX: $title did not offer $option")
    }
    fun secureWindowShown(): Boolean {
        val dump = automation.executeShellCommand("dumpsys window windows").let { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        // Newer releases name the flags (fl=... SECURE ...); Android 8 prints them in hex.
        val hexFlags = Regex("""\bfl=#([0-9a-fA-F]+)""")
        val secureName = Regex("""\bSECURE\b""")
        // Both the dialog and the screen behind it must be secure.
        return dump.split("Window #").count { window ->
            window.contains(context.packageName) && (
                secureName.containsMatchIn(window) ||
                    hexFlags.findAll(window).any {
                        it.groupValues[1].toLong(16) and android.view.WindowManager.LayoutParams.FLAG_SECURE.toLong() != 0L
                    }
                )
        } >= 2
    }
    // Two saved contacts, one verified, show the small cards and the detail page.
    val contacts = PublicKeyContacts(context, LocalKeyManager(context))
    val savedContacts = mutableListOf<String>()
    try {
        val keyDir = File.createTempFile("ci-miuix-contacts-", ".tmp", context.cacheDir).apply { check(delete() && mkdir()) }
        try {
            val workflow = SafFileWorkflow(context, LocalKeyManager(context))
            for ((note, verified) in listOf("Alice" to true, "Bob" to false, "Carol" to false)) {
                val publicKey = File(keyDir, "$note.key")
                generateContactTestKeypair(publicKey, File(keyDir, "$note.nkpr"))
                val key = checkNotNull(workflow.stageTemporaryPublicKey(Uri.fromFile(publicKey), "$note.key").key)
                val contact = try { checkNotNull(contacts.save(key, note).contact) } finally { workflow.discardTemporaryPublicKey(key) }
                savedContacts += contact.id
                if (verified) check(contacts.setVerified(contact.id, contact.fingerprint, true).code == NativeBridge.RESULT_SUCCESS)
                // Bob is pinned above Alice; Carol waits in the archive.
                if (note == "Bob") check(contacts.setPinned(contact.id, true).code == NativeBridge.RESULT_SUCCESS)
                if (note == "Carol") check(contacts.setArchived(contact.id, true).code == NativeBridge.RESULT_SUCCESS)
            }
        } finally { keyDir.deleteRecursively() }
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()
        // The automatic check runs at launch and offers the newer release.
        val updateTitle = string(R.string.update_available_title).replace("%1\$s", "99.0.0")
        try {
            await("automatic update prompt") { exact(updateTitle) != null }
        } catch (failure: IllegalStateException) {
            // Says whether the check ran at all when the prompt stays away.
            val stored = UpdateSettingsStore.read(context)
            error("${failure.message} (auto=${stored.autoCheck}, last=${stored.lastCheckMillis}, ignored=${stored.ignoredVersion})")
        }
        capture("update-available")
        click(string(R.string.action_later))
        await("update prompt closed") { exact(updateTitle) == null }
        check(UpdateSettingsStore.read(context).lastCheckMillis > 0L) { "The automatic check was not recorded" }
        await("Files page") { exact(string(R.string.navigation_files)) != null }
        capture("files")
        val pages = listOf(
            Triple(R.string.tab_keys, R.string.navigation_keys, "keys"),
            Triple(R.string.tab_contacts, R.string.navigation_contacts, "contacts"),
            Triple(R.string.tab_settings, R.string.navigation_settings, "settings"),
        )
        // The file names stay the same in every language.
        for ((tab, title, name) in pages) {
            clickNavigationTab(instrumentation, string(tab))
            await("page ${string(title)}") { exact(string(title)) != null }
            capture(name)
            if (tab == R.string.tab_contacts) {
                // A card opens the contact's page; back returns to the list.
                var card: AccessibilityNodeInfo? = null
                await("contact card") { card = containing("Alice"); card != null }
                var target = checkNotNull(card)
                while (!target.isClickable) target = checkNotNull(target.parent) { "Contact card is not clickable" }
                check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await("contact page") { exact(string(R.string.contact_verify_title)) != null }
                capture("contact-detail")
                // Verification is a page of its own under the contact.
                click(string(R.string.contact_verify_title))
                await("verify page") { exact(string(R.string.action_scan_fingerprint)) != null }
                capture("contact-verify")
                scrollPage(true)
                capture("contact-verify-2")
                instrumentation.runOnMainSync {
                    (checkNotNull(activity) as androidx.activity.ComponentActivity).onBackPressedDispatcher.onBackPressed()
                }
                await("contact page again") { exact(string(R.string.action_use_contact)) != null }
                instrumentation.runOnMainSync {
                    (checkNotNull(activity) as androidx.activity.ComponentActivity).onBackPressedDispatcher.onBackPressed()
                }
                await("contact list again") { exact(string(title)) != null }
            }
        }
        // About is opened from the end of Settings, not from the bar.
        click(string(R.string.tab_about))
        await("About page") { reveal(string(R.string.action_check_update)); exact(string(R.string.action_check_update)) != null }
        capture("about")
        // A screenshot run ends here; dialogs and the style switch are covered by the full run.
        if (pagesOnly) return
        // About checks on demand; skipping the version is remembered.
        click(string(R.string.action_check_update))
        await("manual update prompt") { exact(updateTitle) != null }
        click(string(R.string.action_ignore_version))
        await("update prompt skipped") { exact(updateTitle) == null }
        await("skipped version stored") { UpdateSettingsStore.read(context).ignoredVersion == "99.0.0" }
        // The password dialog is a MIUIX dialog, and it and the screen behind it are secure.
        clickNavigationTab(instrumentation, string(R.string.tab_keys))
        click(string(R.string.action_generate_keypair))
        await("password dialog") { exact(string(R.string.generate_password_title)) != null }
        // Secure windows cannot be captured (Android 10 returns no screenshot at all).
        check(secureWindowShown()) { "MIUIX password dialog window is not secure" }
        click(string(R.string.action_cancel))
        await("password dialog closed") { exact(string(R.string.generate_password_title)) == null }
        // Back from another tab returns to Files.
        instrumentation.runOnMainSync {
            (checkNotNull(activity) as androidx.activity.ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        await("back to Files") { exact(string(R.string.navigation_files)) != null }

        // Turning signing off needs the red warning; Cancel keeps signing on.
        clickNavigationTab(instrumentation, string(R.string.tab_settings))
        reveal(string(R.string.settings_unsigned_title))
        click(string(R.string.settings_unsigned_title))
        await("unsigned warning") { exact(string(R.string.unsigned_confirm_title)) != null }
        capture("unsigned-warning")
        click(string(R.string.action_cancel))
        await("unsigned warning closed") { exact(string(R.string.unsigned_confirm_title)) == null }
        check(!SignatureSettingsStore.read(context).unsignedEncryption) { "Cancel turned signing off" }
        click(string(R.string.settings_unsigned_title))
        click(string(R.string.action_turn_off_signing))
        await("unsigned encryption stored") { SignatureSettingsStore.read(context).unsignedEncryption }
        await("settings red warning") { reveal(string(R.string.unsigned_warning)); exact(string(R.string.unsigned_warning)) != null }
        capture("settings-unsigned")
        clickNavigationTab(instrumentation, string(R.string.tab_files))
        await("Files red warning") { reveal(string(R.string.unsigned_warning)); exact(string(R.string.unsigned_warning)) != null }
        capture("files-unsigned")

        // MIUIX settings: the dropdown switches to Material at once.
        clickNavigationTab(instrumentation, string(R.string.tab_settings))
        choose(string(R.string.settings_ui_style_title), string(R.string.settings_ui_style_material))
        await("Material style stored") { UiStyles.selection(context) == UiStyle.MATERIAL }
        // The Material shell opens on Files; its settings picker switches back to MIUIX.
        await("Material Files page") { exact(string(R.string.navigation_files)) != null }
        // Material About checks on demand too.
        clickNavigationTab(instrumentation, string(R.string.tab_settings))
        click(string(R.string.tab_about))
        click(string(R.string.action_check_update))
        await("Material update prompt") { exact(updateTitle) != null }
        capture("material-update-available")
        click(string(R.string.action_later))
        await("Material update prompt closed") { exact(updateTitle) == null }
        clickNavigationTab(instrumentation, string(R.string.tab_files))
        await("Material Files red warning") { reveal(string(R.string.unsigned_warning)); exact(string(R.string.unsigned_warning)) != null }
        capture("material-files")
        clickNavigationTab(instrumentation, string(R.string.tab_settings))
        // Turning signing back on never asks.
        reveal(string(R.string.settings_unsigned_title))
        capture("material-settings-unsigned")
        click(string(R.string.settings_unsigned_title))
        await("signing back on") { !SignatureSettingsStore.read(context).unsignedEncryption }
        // The row toggles from the value it last drew, so wait for the redraw before toggling again.
        await("Material switch redrawn off") {
            // A window left over from the MIUIX shell can still hold a checked copy of
            // the row, so look at every copy on screen and refresh each before reading.
            reveal(string(R.string.settings_unsigned_title))
            val roots = listOfNotNull(automation.rootInActiveWindow) +
                runCatching { automation.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
            val title = string(R.string.settings_unsigned_title)
            roots.any { root ->
                val rows = mutableListOf<AccessibilityNodeInfo>()
                fun collect(node: AccessibilityNodeInfo?) {
                    if (node == null) return
                    if (node.text?.toString() == title || node.contentDescription?.toString() == title) rows += node
                    for (index in 0 until node.childCount) collect(node.getChild(index))
                }
                collect(root)
                rows.any { match ->
                    var row: AccessibilityNodeInfo? = match
                    while (row != null && !row.isCheckable) row = row.parent
                    row != null && row.refresh() && !row.isChecked
                }
            }
        }
        check(exact(string(R.string.unsigned_confirm_title)) == null) { "Turning signing on asked for confirmation" }
        click(string(R.string.settings_unsigned_title))
        await("Material unsigned warning") { exact(string(R.string.unsigned_confirm_title)) != null }
        capture("material-unsigned-warning")
        click(string(R.string.action_cancel))
        await("Material unsigned warning closed") { exact(string(R.string.unsigned_confirm_title)) == null }
        check(!SignatureSettingsStore.read(context).unsignedEncryption) { "Material Cancel turned signing off" }
        instrumentation.runOnMainSync {
            (checkNotNull(activity) as androidx.activity.ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        await("Material Files page again") { exact(string(R.string.navigation_files)) != null }
        clickNavigationTab(instrumentation, string(R.string.tab_settings))
        // Looking for a menu entry that is not drawn yet would scroll the page and close the menu.
        choose(string(R.string.settings_ui_style_title), string(R.string.settings_ui_style_miuix))
        await("MIUIX style stored") { UiStyles.selection(context) == UiStyle.MIUIX }
        await("MIUIX Files page") { exact(string(R.string.navigation_files)) != null }
    } finally {
        instrumentation.runOnMainSync {
            activity?.finish()
            AppLanguages.setSelection(context, priorLanguage)
        }
        automation.serviceInfo = priorServiceInfo
        savedContacts.forEach { contacts.delete(it) }
        useMaterialStyle(context)
        context.getSharedPreferences("signature_settings", Context.MODE_PRIVATE).edit().clear().commit()
        quietUpdateChecks(context)
    }
    // Below Android 13 the language is stored by the App; it must not leak into later phases.
    if (Build.VERSION.SDK_INT < 33) check(AppLanguages.selection(context) == priorLanguage)
}
