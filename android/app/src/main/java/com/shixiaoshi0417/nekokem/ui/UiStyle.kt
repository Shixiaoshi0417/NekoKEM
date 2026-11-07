package com.shixiaoshi0417.nekokem.ui

import android.content.Context
import androidx.annotation.StringRes
import com.shixiaoshi0417.nekokem.R

/** The interface style the App is drawn in; MIUIX is the default for new installs. */
enum class UiStyle(val tag: String, @StringRes val labelResource: Int) {
    MIUIX("miuix", R.string.settings_ui_style_miuix),
    MATERIAL("material", R.string.settings_ui_style_material),
}

/** Stores the chosen [UiStyle] in this device's private preferences. */
object UiStyles {
    private const val PREFERENCES = "interface_style"
    private const val STYLE = "style"
    val default = UiStyle.MIUIX

    fun selection(context: Context): UiStyle {
        val tag = try {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(STYLE, null)
        } catch (_: ClassCastException) {
            null
        }
        return UiStyle.entries.firstOrNull { it.tag == tag } ?: default
    }

    /** Returns false when the choice could not be written; the previous style then stays. */
    fun setSelection(context: Context, style: UiStyle): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(STYLE, style.tag).commit()
}
