package com.shixiaoshi0417.nekokem

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.shixiaoshi0417.nekokem.i18n.AppLanguages
import com.shixiaoshi0417.nekokem.ui.UiStyle
import com.shixiaoshi0417.nekokem.ui.UiStyles
import java.io.File

/** Pins the Material style so the drawer-driven device tests see the interface they expect. */
internal fun useMaterialStyle(context: Context) {
    check(UiStyles.setSelection(context, UiStyle.MATERIAL))
}

/**
 * A fresh install opens in MIUIX: every tab of the floating bar is reachable
 * and captured, and the interface style switches to Material and back from
 * either style's settings page.
 */
internal fun runMiuixUiTests(instrumentation: Instrumentation, screenshotPrefix: String) {
    val context = instrumentation.targetContext
    val priorLanguage = AppLanguages.selection(context)
    // No stored choice, as after installation.
    check(context.getSharedPreferences("interface_style", Context.MODE_PRIVATE).edit().clear().commit())
    check(UiStyles.selection(context) == UiStyle.MIUIX) { "MIUIX is not the default interface style" }
    instrumentation.runOnMainSync { check(AppLanguages.setSelection(context, "en")) }
    var activity: Activity? = null
    fun find(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (index in 0 until root.childCount) find(root.getChild(index), predicate)?.let { return it }
        return null
    }
    fun exact(value: String): AccessibilityNodeInfo? = find(instrumentation.uiAutomation.rootInActiveWindow) {
        it.text?.toString() == value || it.contentDescription?.toString() == value
    }
    fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "MIUIX: $description" }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
    }
    fun string(resource: Int) = checkNotNull(activity).getString(resource)
    fun click(value: String) {
        await("missing $value") { exact(value) != null }
        var target = checkNotNull(exact(value))
        while (!target.isClickable) target = checkNotNull(target.parent) { "$value is not clickable" }
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
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
    try {
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()
        // MIUIX has no navigation drawer: the menu button belongs to the Material style.
        await("Files page") { exact(string(R.string.navigation_files)) != null }
        check(exact(string(R.string.navigation_open_menu)) == null) { "Material drawer shown in MIUIX" }
        capture("files")
        val pages = listOf(
            R.string.tab_keys to R.string.navigation_keys,
            R.string.tab_contacts to R.string.navigation_contacts,
            R.string.tab_settings to R.string.navigation_settings,
            R.string.tab_about to R.string.navigation_about,
        )
        for ((tab, title) in pages) {
            click(string(tab))
            await("page ${string(title)}") { exact(string(title)) != null }
            capture(string(tab).lowercase())
        }
        // Back from another tab returns to Files.
        instrumentation.runOnMainSync {
            (checkNotNull(activity) as androidx.activity.ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        await("back to Files") { exact(string(R.string.navigation_files)) != null }

        // MIUIX settings: the dropdown switches to Material at once.
        click(string(R.string.tab_settings))
        click(string(R.string.settings_ui_style_title))
        click(string(R.string.settings_ui_style_material))
        await("Material style stored") { UiStyles.selection(context) == UiStyle.MATERIAL }
        await("Material drawer button") { exact(string(R.string.navigation_open_menu)) != null }
        // Material settings: the picker switches back to MIUIX.
        click(string(R.string.navigation_open_menu))
        click(string(R.string.navigation_settings))
        click(string(R.string.settings_ui_style_title))
        click(string(R.string.settings_ui_style_miuix))
        await("MIUIX style stored") { UiStyles.selection(context) == UiStyle.MIUIX }
        await("MIUIX tab bar") { exact(string(R.string.tab_files)) != null }
        check(exact(string(R.string.navigation_open_menu)) == null)
    } finally {
        instrumentation.runOnMainSync {
            activity?.finish()
            AppLanguages.setSelection(context, priorLanguage)
        }
        useMaterialStyle(context)
    }
    // Below Android 13 the language is stored by the App; it must not leak into later phases.
    if (Build.VERSION.SDK_INT < 33) check(AppLanguages.selection(context) == priorLanguage)
}
