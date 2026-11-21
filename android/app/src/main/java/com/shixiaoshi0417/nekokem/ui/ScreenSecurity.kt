package com.shixiaoshi0417.nekokem.ui

import android.content.Context
import android.view.Window
import android.view.WindowManager

/**
 * Whether the whole app is kept out of screenshots, screen recordings and the
 * recent-apps preview (FLAG_SECURE). On by default. Turned off, the password
 * dialogs still set it while they are open.
 */
object ScreenSecurityStore {
    private const val PREFERENCES = "screen_security"
    private const val SECURE = "secure_screen"

    fun read(context: Context): Boolean = try {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(SECURE, true)
    } catch (_: ClassCastException) {
        // A damaged value keeps the screen secure.
        true
    }

    /** Returns false when the setting could not be written; the old one then stays. */
    fun write(context: Context, secure: Boolean): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(SECURE, secure)
            .commit()

    fun apply(window: Window, secure: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        if (secure) window.addFlags(flag) else window.clearFlags(flag)
    }
}
