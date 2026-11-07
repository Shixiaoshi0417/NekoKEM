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
    fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
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
    val deadline = SystemClock.uptimeMillis() + 10000
    var target = clickable(find(instrumentation.uiAutomation.rootInActiveWindow))
    while (target == null) {
        check(SystemClock.uptimeMillis() < deadline) { "Missing enabled navigation tab: $label" }
        SystemClock.sleep(100)
        target = clickable(find(instrumentation.uiAutomation.rootInActiveWindow))
    }
    check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    instrumentation.waitForIdleSync()
}
