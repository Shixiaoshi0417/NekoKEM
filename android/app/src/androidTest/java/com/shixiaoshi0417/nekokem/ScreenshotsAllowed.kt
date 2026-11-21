package com.shixiaoshi0417.nekokem

import android.content.Context

/**
 * The app keeps itself out of screenshots by default (FLAG_SECURE); device
 * tests capture the screen, so they turn that setting off before starting it.
 */
internal fun allowScreenshots(context: Context) {
    check(
        context.getSharedPreferences("screen_security", Context.MODE_PRIVATE).edit()
            .putBoolean("secure_screen", false).commit(),
    )
}
