package com.shixiaoshi0417.nekokem

import android.app.Instrumentation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

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
    val deadline = SystemClock.uptimeMillis() + 10000
    while (true) {
        val target = clickable(find(instrumentation.uiAutomation.rootInActiveWindow))
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) break
        check(SystemClock.uptimeMillis() < deadline) {
            if (target == null) "Missing enabled navigation tab: $label" else "Navigation tab refused the click: $label"
        }
        SystemClock.sleep(100)
    }
    instrumentation.waitForIdleSync()
}
