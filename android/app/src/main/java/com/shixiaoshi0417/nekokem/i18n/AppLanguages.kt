package com.shixiaoshi0417.nekokem.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Framework application locales are authoritative on Android 13 and newer. */
object AppLanguages {
    val tags = listOf("", "zh-CN", "zh-TW", "en", "ja", "ko")
    val names = listOf("", "简体中文", "繁體中文", "English", "日本語", "한국어")
    private const val PREFERENCES = "application_language"
    private const val LANGUAGE = "language"

    fun match(locale: Locale): String = when (locale.language.lowercase(Locale.ROOT)) {
        "zh" -> if (
            locale.country.uppercase(Locale.ROOT) in listOf("TW", "HK", "MO") ||
            (locale.country.uppercase(Locale.ROOT) !in listOf("CN", "SG") &&
                locale.script == "Hant")
        ) "zh-TW" else "zh-CN"
        "en" -> "en"
        "ja" -> "ja"
        "ko" -> "ko"
        else -> "en"
    }

    fun selection(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            return if (locales.isEmpty) "" else match(locales[0])
        }
        return try {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(LANGUAGE, "").orEmpty().takeIf { it in tags }.orEmpty()
        } catch (_: ClassCastException) {
            ""
        }
    }

    fun setSelection(context: Context, tag: String) {
        require(tag in tags)
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(tag)
        } else {
            check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(LANGUAGE, tag).commit())
        }
    }

    fun localizedContext(context: Context): Context {
        val selected = selection(context)
        val systemLocales = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).systemLocales
        } else {
            Resources.getSystem().configuration.locales
        }
        val tag = selected.ifEmpty {
            if (systemLocales.isEmpty) "en" else match(systemLocales[0])
        }
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return context.createConfigurationContext(configuration)
    }
}
