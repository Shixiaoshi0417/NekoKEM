package com.shixiaoshi0417.nekokem

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import com.shixiaoshi0417.nekokem.i18n.AppLanguages
import java.io.File

/**
 * Drive predictive back through the Activity's real OnBackPressedDispatcher.
 * The same started/progressed/cancelled/committed callbacks are delivered by
 * Android 14+ gestures; older releases deliver only the committed back.
 */
internal fun runPredictiveBackUiTests(instrumentation: Instrumentation) {
    val context = instrumentation.targetContext
    val priorLanguage = AppLanguages.selection(context)
    var activity: ComponentActivity? = null
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
            check(SystemClock.uptimeMillis() < deadline) { "Predictive back: $description" }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
    }
    fun string(resource: Int) = checkNotNull(activity).getString(resource)
    fun click(value: String) {
        await("missing $value") { exact(value) != null }
        var target = checkNotNull(exact(value))
        while (!target.isClickable) target = checkNotNull(target.parent)
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    fun handlesBack(): Boolean {
        var enabled = false
        instrumentation.runOnMainSync { enabled = checkNotNull(activity).onBackPressedDispatcher.hasEnabledCallbacks() }
        return enabled
    }
    fun back(event: (ComponentActivity) -> Unit) {
        instrumentation.runOnMainSync { event(checkNotNull(activity)) }
        instrumentation.waitForIdleSync()
    }
    fun gesture(progress: Float) = BackEventCompat(24f, 800f, progress, BackEventCompat.EDGE_LEFT)
    // Bypass the accessibility cache: layer transforms do not change semantics.
    fun bounds(value: String): Rect? = exact(value)?.let { node ->
        if (!node.refresh()) null else Rect().also(node::getBoundsInScreen)
    }
    fun onPage(title: Int, content: Int): Boolean = exact(string(title)) != null && exact(string(content)) != null
    // Drawer selection closes the drawer; let any close animation settle before gestures.
    fun navigate(item: Int) {
        click(string(R.string.navigation_open_menu))
        click(string(item))
        SystemClock.sleep(500)
        instrumentation.waitForIdleSync()
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
        instrumentation.runOnMainSync { AppLanguages.setSelection(context, "en") }
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as ComponentActivity
        instrumentation.waitForIdleSync()
        val files = R.string.navigation_files
        val selectFile = R.string.action_select_file
        await("Files page did not open") { onPage(files, selectFile) }
        // Files leaves back to the system, which shows its back-to-home animation.
        check(!handlesBack()) { "Files must not intercept system back" }

        // On Files only the open drawer can handle back: it closes instead of leaving the App.
        click(string(R.string.navigation_open_menu))
        await("open drawer did not handle back") { handlesBack() }
        back { it.onBackPressedDispatcher.onBackPressed() }
        await("drawer did not close on back") { !handlesBack() }
        check(onPage(files, selectFile) && !checkNotNull(activity).isFinishing)

        navigate(R.string.navigation_contacts)
        val contactsPage = string(R.string.action_import_contact)
        await("Contacts page did not open") { onPage(R.string.navigation_contacts, R.string.action_import_contact) && handlesBack() }
        val resting = checkNotNull(bounds(contactsPage))

        // The page follows the gesture: it shrinks and moves away from the left edge.
        back {
            it.onBackPressedDispatcher.dispatchOnBackStarted(gesture(0f))
            it.onBackPressedDispatcher.dispatchOnBackProgressed(gesture(0.6f))
        }
        await("page did not follow the back gesture") {
            val moved = bounds(contactsPage)
            moved != null && moved.width() < resting.width() && moved.height() < resting.height() &&
                moved.left > resting.left
        }
        // The Files preview beneath is visual only and never duplicates semantics.
        check(exact(string(selectFile)) == null) { "Files preview exposed duplicate semantics" }
        check(exact(string(R.string.navigation_contacts)) != null)
        capture("predictive-back")

        // Cancelling restores the page; nothing navigates.
        back { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
        await("cancelled gesture did not restore the page") { bounds(contactsPage) == resting }
        check(onPage(R.string.navigation_contacts, R.string.action_import_contact) && handlesBack())

        // Completing the gesture returns to Files without finishing the Activity.
        back {
            it.onBackPressedDispatcher.dispatchOnBackStarted(gesture(0f))
            it.onBackPressedDispatcher.dispatchOnBackProgressed(gesture(0.9f))
            it.onBackPressedDispatcher.onBackPressed()
        }
        await("completed gesture did not return to Files") { onPage(files, selectFile) && !handlesBack() }
        check(exact(contactsPage) == null && !checkNotNull(activity).isFinishing)

        // Releases before Android 14 send only the committed back without progress.
        navigate(R.string.navigation_settings)
        await("Settings page did not open") { exact(string(R.string.settings_language_title)) != null && handlesBack() }
        back { it.onBackPressedDispatcher.onBackPressed() }
        await("back without progress did not return to Files") { onPage(files, selectFile) && !handlesBack() }
        check(!checkNotNull(activity).isFinishing)
    } catch (error: Throwable) {
        runCatching { capture("predictive-back-failure") }
        throw error
    } finally {
        instrumentation.runOnMainSync {
            activity?.finish()
            AppLanguages.setSelection(context, priorLanguage)
        }
    }
}
