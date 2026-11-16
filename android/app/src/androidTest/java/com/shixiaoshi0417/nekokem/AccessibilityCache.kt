package com.shixiaoshi0417.nekokem

import android.app.UiAutomation
import android.os.Build

/**
 * Drops the nodes this process has cached from the accessibility tree.
 *
 * UiAutomation answers lookups from a cache that only accessibility events
 * invalidate. When Compose swaps a whole page, as on a style switch, a closing
 * dialog or a back press, an event can be lost and the cache keeps the old page
 * until the screen changes again. A still screen never does, so a poll can see
 * stale nodes for its whole timeout while the screenshot shows the right page,
 * and a stale node refuses every click. A poll that failed calls this first.
 */
internal fun UiAutomation.dropAccessibilityCache() {
    if (Build.VERSION.SDK_INT >= 34) {
        clearCache()
    } else {
        // Before Android 14, setting the service info clears the cache, and is
        // public API; the info is set back unchanged.
        val info = serviceInfo
        serviceInfo = info
    }
}
