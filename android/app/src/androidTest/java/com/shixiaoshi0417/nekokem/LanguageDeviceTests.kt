package com.shixiaoshi0417.nekokem

import android.graphics.Bitmap
import java.io.File
import android.app.Activity
import android.app.Instrumentation
import android.app.LocaleManager
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.shixiaoshi0417.nekokem.i18n.AppLanguages

/** Runs inside the existing device runner, with the real Compose Activity. */
internal fun runLanguageDeviceTests(
    instrumentation: Instrumentation, phase: String?, expectedSystemLanguage: String?,
    screenshotPrefix: String,
) {
    val context = instrumentation.targetContext
    fun findText(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.text?.toString()?.contains(text) == true ||
            root.contentDescription?.toString()?.contains(text) == true) return root
        for (i in 0 until root.childCount) findText(root.getChild(i), text)?.let { return it }
        return null
    }
    fun node(text: String): AccessibilityNodeInfo? =
        findText(instrumentation.uiAutomation.rootInActiveWindow, text)
    fun scrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) scrollable(root.getChild(i))?.let { return it }
        return null
    }
    fun awaitNode(text: String, scrollAction: Int? = null): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            node(text)?.let { return it }
            if (scrollAction != null) {
                scrollable(instrumentation.uiAutomation.rootInActiveWindow)?.performAction(scrollAction)
                instrumentation.waitForIdleSync()
            }
            SystemClock.sleep(100)
        }
        return null
    }
    fun capture(name: String) {
        instrumentation.uiAutomation.waitForIdle(500, 5000)
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            val captured = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val screenshot = if (captured.config == Bitmap.Config.HARDWARE) {
                checkNotNull(captured.copy(Bitmap.Config.ARGB_8888, false)).also { captured.recycle() }
            } else captured
            try {
                val colors = mutableSetOf<Int>()
                for (y in 0 until screenshot.height step 24) {
                    for (x in 0 until screenshot.width step 24) colors.add(screenshot.getPixel(x, y))
                }
                // Reject a blank transition frame, even when Activity resources are ready.
                if (colors.size > 24) {
                    val directory = File(context.cacheDir, "i18n-screens")
                    check(directory.isDirectory || directory.mkdir())
                    File(directory, "$screenshotPrefix-$name.png").outputStream().use {
                        check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    return
                }
            } finally {
                screenshot.recycle()
            }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        error("No rendered UI screenshot for $name")
    }
    if (phase == "system") {
        check(AppLanguages.selection(context).isEmpty())
        var following = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        val ready = File(context.cacheDir, "i18n-system-ready")
        try {
            ready.writeText("ready")
            following = checkNotNull(monitor.waitForActivityWithTimeout(30000)) {
                "Activity did not rebuild after real system-language change"
            }
            instrumentation.waitForIdleSync()
            val locales = if (Build.VERSION.SDK_INT >= 33) {
                context.getSystemService(LocaleManager::class.java).systemLocales
            } else Resources.getSystem().configuration.locales
            check(!locales.isEmpty && locales[0].language == expectedSystemLanguage) {
                "Framework system locale did not change: ${locales.toLanguageTags()}"
            }
            val expected = if (expectedSystemLanguage == "ja") "ファイルの暗号化・復号" else "File Encryption"
            check(following.getString(R.string.navigation_files) == expected)
            check(awaitNode(expected) != null)
            capture("system-$expectedSystemLanguage")
        } finally {
            ready.delete()
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { following.finish() }
        }
        return
    }
    if (phase == "persist") {
        instrumentation.runOnMainSync { AppLanguages.setSelection(context, "ja") }
        check(AppLanguages.selection(context) == "ja")
        return
    }
    if (phase == "restart") {
        check(AppLanguages.selection(context) == "ja") { "Language lost across process restart" }
        val restarted = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()
        check(restarted.getString(R.string.navigation_files) == "ファイルの暗号化・復号")
        instrumentation.runOnMainSync { restarted.finish() }
        return
    }
    instrumentation.runOnMainSync { AppLanguages.setSelection(context, "") }
    var activity = instrumentation.startActivitySync(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    try {
        val labels = mapOf("zh-CN" to "文件加解密", "zh-TW" to "檔案加密與解密",
            "en" to "File Encryption", "ja" to "ファイルの暗号化・復号", "ko" to "파일 암호화·복호화")
        for ((tag, label) in labels) {
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            instrumentation.runOnMainSync {
                AppLanguages.setSelection(activity, tag)
                if (Build.VERSION.SDK_INT < 33) activity.recreate()
            }
            val recreated = monitor.waitForActivityWithTimeout(10000)
            instrumentation.removeMonitor(monitor)
            check(recreated != null) { "Activity did not rebuild for $tag" }
            activity = recreated
            instrumentation.waitForIdleSync()
            check(activity.getString(R.string.navigation_files) == label)
            check(AppLanguages.selection(activity) == tag)
            check(awaitNode(label) != null) { "Localized Compose title did not render: $tag" }
            capture(tag)

        }
        if (Build.VERSION.SDK_INT >= 33) {
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            instrumentation.runOnMainSync {
                context.getSystemService(LocaleManager::class.java).applicationLocales =
                    LocaleList.forLanguageTags("zh-TW")
            }
            activity = checkNotNull(monitor.waitForActivityWithTimeout(10000))
            instrumentation.removeMonitor(monitor)
            instrumentation.waitForIdleSync()
            check(AppLanguages.selection(activity) == "zh-TW")
        }
        // Exercise the actual settings control and its accessible option labels.
        instrumentation.waitForIdleSync()
        fun click(text: String) {
            var target = checkNotNull(awaitNode(text)) { "Missing accessible text: $text" }
            while (!target.isClickable) target = checkNotNull(target.parent)
            check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
        }
        // Navigation control uses a content description rather than visible text.
        val description = activity.getString(R.string.navigation_open_menu)
        fun described(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (root == null) return null
            if (root.contentDescription?.toString() == description) return root
            for (i in 0 until root.childCount) described(root.getChild(i))?.let { return it }
            return null
        }
        var menu = checkNotNull(described(instrumentation.uiAutomation.rootInActiveWindow))
        while (!menu.isClickable) menu = checkNotNull(menu.parent)
        check(menu.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        click(activity.getString(R.string.navigation_settings))
        check(awaitNode(activity.getString(R.string.settings_language_title)) != null)
        capture("settings")
        click(activity.getString(R.string.settings_language_title))
        AppLanguages.names(context).drop(1).forEach { check(awaitNode(it, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != null) { "Missing language option $it" } }
        check(awaitNode(activity.getString(R.string.settings_language_system), AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != null)
        capture("language-picker")
        click(activity.getString(R.string.settings_language_system))
        val deadline = SystemClock.uptimeMillis() + 5000
        while (AppLanguages.selection(context).isNotEmpty() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
        }
        check(AppLanguages.selection(context).isEmpty()) { "UI did not restore follow-system mode" }
    } finally {
        instrumentation.runOnMainSync {
            AppLanguages.setSelection(context, "")
            activity.finish()
        }
    }
}
