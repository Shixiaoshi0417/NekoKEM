package com.shixiaoshi0417.nekokem

import android.app.Activity
import android.app.Instrumentation
import android.app.LocaleManager
import android.content.Intent
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.shixiaoshi0417.nekokem.i18n.AppLanguages

/** Runs inside the existing device runner, with the real Compose Activity. */
internal fun runLanguageDeviceTests(instrumentation: Instrumentation, phase: String?) {
    val context = instrumentation.targetContext
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
            check(instrumentation.uiAutomation.takeScreenshot() != null)
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
        fun node(text: String): AccessibilityNodeInfo? {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
            return root.findAccessibilityNodeInfosByText(text).firstOrNull()
        }
        fun awaitNode(text: String): AccessibilityNodeInfo? {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline) {
                node(text)?.let { return it }
                SystemClock.sleep(100)
            }
            return null
        }
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
        click(activity.getString(R.string.settings_language_title))
        AppLanguages.names(context).drop(1).forEach { check(awaitNode(it) != null) { "Missing language option $it" } }
        check(awaitNode(activity.getString(R.string.settings_language_system)) != null)
        click(activity.getString(R.string.settings_language_system))
    } finally {
        instrumentation.runOnMainSync {
            AppLanguages.setSelection(context, "")
            activity.finish()
        }
    }
}
