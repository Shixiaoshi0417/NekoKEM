package com.shixiaoshi0417.nekokem

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.shixiaoshi0417.nekokem.i18n.AppLanguages
import com.shixiaoshi0417.nekokem.ui.SignatureSettingsStore
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
internal fun runMiuixUiTests(instrumentation: Instrumentation, screenshotPrefix: String) {
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
    instrumentation.runOnMainSync { check(AppLanguages.setSelection(context, "en")) }
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
    fun string(resource: Int) = checkNotNull(activity).getString(resource)
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
    fun reveal(value: String) {
        // On small screens the row can sit below the fold or under the floating bar.
        // A lazy list drops rows scrolled past, so look back up once the end is reached.
        for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            repeat(6) {
                if (exact(value) != null) return
                val scrollable = find(automation.rootInActiveWindow) { it.isScrollable } ?: return
                if (!scrollable.performAction(action)) return@repeat
                instrumentation.waitForIdleSync()
                SystemClock.sleep(300)
            }
        }
    }
    fun click(value: String) {
        // Keep the node the wait found; a recomposition can drop it before a second lookup.
        var found: AccessibilityNodeInfo? = null
        await("missing $value") { reveal(value); found = exact(value); found != null }
        var target = checkNotNull(found)
        while (!target.isClickable) target = checkNotNull(target.parent) { "$value is not clickable" }
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
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
    try {
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()
        await("Files page") { exact(string(R.string.navigation_files)) != null }
        capture("files")
        // Dialogs follow the style: the saved-key picker is a MIUIX dialog.
        reveal(string(R.string.action_choose_contact))
        click(string(R.string.action_choose_contact))
        await("contact picker") { exact(string(R.string.action_cancel)) != null }
        capture("dialog")
        click(string(R.string.action_cancel))
        await("contact picker closed") { exact(string(R.string.action_cancel)) == null }
        val pages = listOf(
            R.string.tab_keys to R.string.navigation_keys,
            R.string.tab_contacts to R.string.navigation_contacts,
            R.string.tab_settings to R.string.navigation_settings,
            R.string.tab_about to R.string.navigation_about,
        )
        for ((tab, title) in pages) {
            clickNavigationTab(instrumentation, string(tab))
            await("page ${string(title)}") { exact(string(title)) != null }
            capture(string(tab).lowercase())
        }
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
        click(string(R.string.settings_ui_style_title))
        click(string(R.string.settings_ui_style_material))
        await("Material style stored") { UiStyles.selection(context) == UiStyle.MATERIAL }
        // The Material shell opens on Files; its settings picker switches back to MIUIX.
        await("Material Files page") { exact(string(R.string.navigation_files)) != null }
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
            var row = exact(string(R.string.settings_unsigned_title))
            while (row != null && !row.isCheckable) row = row.parent
            row != null && !row.isChecked
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
        click(string(R.string.settings_ui_style_title))
        click(string(R.string.settings_ui_style_miuix))
        await("MIUIX style stored") { UiStyles.selection(context) == UiStyle.MIUIX }
        await("MIUIX Files page") { exact(string(R.string.navigation_files)) != null }
    } finally {
        instrumentation.runOnMainSync {
            activity?.finish()
            AppLanguages.setSelection(context, priorLanguage)
        }
        automation.serviceInfo = priorServiceInfo
        useMaterialStyle(context)
        context.getSharedPreferences("signature_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }
    // Below Android 13 the language is stored by the App; it must not leak into later phases.
    if (Build.VERSION.SDK_INT < 33) check(AppLanguages.selection(context) == priorLanguage)
}
