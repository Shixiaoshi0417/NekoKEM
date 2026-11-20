package com.shixiaoshi0417.nekokem

import android.app.Instrumentation
import android.app.UiAutomation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * The roots to search, topmost application window on screen first, then the
 * active window. After a dialog or dropdown closes, the API 36 emulator (and
 * Android 8 before it) can keep reporting the closed window as active, and its
 * tree has no navigation bar and no page; the windows on screen are current.
 * The window list needs FLAG_RETRIEVE_INTERACTIVE_WINDOWS; without it only the
 * active window is searched, as before.
 */
internal fun screenRoots(automation: UiAutomation): List<AccessibilityNodeInfo> {
    val onScreen = runCatching {
        automation.windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .mapNotNull { it.root }
    }.getOrDefault(emptyList())
    return onScreen + listOfNotNull(automation.rootInActiveWindow)
}

/** One line per window on screen, for failure messages. */
internal fun describeWindows(automation: UiAutomation): String = runCatching {
    automation.windows.joinToString("; ") { window ->
        "type=${window.type} layer=${window.layer} active=${window.isActive} title=${window.title}"
    }
}.getOrDefault("unavailable")

/**
 * Opens a page from the bottom navigation bar of either interface style. The
 * label is matched exactly, so page text that merely contains it is skipped;
 * the icon-only MIUIX bar below Android 13 carries it as a content description.
 */
internal fun clickNavigationTab(instrumentation: Instrumentation, label: String) {
    // The API 35 and 36 emulators can serve cached nodes with an old child list after
    // the interface style switches, so each node is refreshed from the app before it
    // is read; refresh() fails for a node that no longer exists.
    fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null || !root.refresh()) return null
        if (root.text?.toString() == label || root.contentDescription?.toString() == label) return root
        for (index in 0 until root.childCount) find(root.getChild(index))?.let { return it }
        return null
    }
    // A tab can be found while its bar is still disabled, as during a running
    // operation, so wait for an enabled clickable ancestor as well.
    fun clickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null && !(current.isClickable && current.isEnabled)) current = current.parent
        return current
    }
    // A node read while the bar recomposes can go stale and refuse the click,
    // so look the tab up again until a click is accepted.
    val automation = instrumentation.uiAutomation
    val deadline = SystemClock.uptimeMillis() + 10000
    while (true) {
        val targets = screenRoots(automation).mapNotNull { clickable(find(it)) }
        if (targets.any { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }) break
        check(SystemClock.uptimeMillis() < deadline) {
            val reason = if (targets.isEmpty()) "Missing enabled navigation tab" else "Navigation tab refused the click"
            "$reason: $label (windows: ${describeWindows(automation)})"
        }
        SystemClock.sleep(100)
    }
    instrumentation.waitForIdleSync()
}
