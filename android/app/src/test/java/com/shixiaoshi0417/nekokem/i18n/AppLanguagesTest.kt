package com.shixiaoshi0417.nekokem.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import com.shixiaoshi0417.nekokem.MainActivity
import com.shixiaoshi0417.nekokem.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppLanguagesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun regionMatchingAndEnglishFallback() {
        mapOf(
            "zh-CN" to "zh-CN", "zh-SG" to "zh-CN", "zh-Hans" to "zh-CN",
            "zh-TW" to "zh-TW", "zh-HK" to "zh-TW", "zh-MO" to "zh-TW",
            "zh-Hant" to "zh-TW", "en-GB" to "en", "ja-JP" to "ja",
            "ko-KR" to "ko", "de-DE" to "en", "und" to "en",
        ).forEach { (input, expected) ->
            assertEquals(input, expected, AppLanguages.match(Locale.forLanguageTag(input)))
        }
    }

    @Test fun persistedSelectionAndReturnToSystem() {
        AppLanguages.tags.forEach { tag ->
            AppLanguages.setSelection(context, tag)
            assertEquals(tag, AppLanguages.selection(context.createConfigurationContext(Configuration())))
        }
    }

    @Test fun corruptPreferenceFollowsSystem() {
        val preferences = context.getSharedPreferences("application_language", Context.MODE_PRIVATE)
        preferences.edit().putString("language", "invalid").commit()
        assertEquals("", AppLanguages.selection(context))
        preferences.edit().putInt("language", 7).commit()
        assertEquals("", AppLanguages.selection(context))
        preferences.edit().clear().commit()
    }

    @Test fun allFiveResourcesAndActivityRecreation() {
        val expected = mapOf(
            "en" to "Settings", "zh-CN" to "设置", "zh-TW" to "設定",
            "ja" to "設定", "ko" to "설정",
        )
        expected.forEach { (tag, label) ->
            AppLanguages.setSelection(context, tag)
            assertEquals(label, AppLanguages.localizedContext(context).getString(R.string.navigation_settings))
        }
        AppLanguages.setSelection(context, "ja")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals("設定", controller.get().getString(R.string.navigation_settings))
        AppLanguages.setSelection(context, "ko")
        controller.recreate()
        assertEquals("설정", controller.get().getString(R.string.navigation_settings))
        controller.pause().stop().destroy()
        val restarted = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals("설정", restarted.get().getString(R.string.navigation_settings))
        restarted.pause().stop().destroy()
        AppLanguages.setSelection(context, "")
    }

    @Suppress("DEPRECATION")
    @Test fun followingSystemReadsChangesAndCanonicalizesRegions() {
        AppLanguages.setSelection(context, "")
        val resources = Resources.getSystem()
        val original = Configuration(resources.configuration)
        try {
            listOf("zh-SG" to "文件加解密", "zh-HK" to "檔案加密與解密",
                "fr-FR" to "File Encryption", "ko-KR" to "파일 암호화·복호화").forEach { (tag, label) ->
                val configuration = Configuration(original)
                configuration.setLocales(LocaleList.forLanguageTags(tag))
                resources.updateConfiguration(configuration, resources.displayMetrics)
                assertEquals(label, AppLanguages.localizedContext(context).getString(R.string.navigation_files))
            }
        } finally {
            resources.updateConfiguration(original, resources.displayMetrics)
        }
    }

    @Config(sdk = [33])
    @Test fun frameworkAndInAppSelectionsShareOnePersistentSource() {
        val manager = context.getSystemService(LocaleManager::class.java)
        AppLanguages.setSelection(context, "ja")
        assertEquals("ja", manager.applicationLocales.toLanguageTags())
        manager.applicationLocales = LocaleList.forLanguageTags("zh-TW")
        assertEquals("zh-TW", AppLanguages.selection(context))
        assertEquals("檔案加密與解密", AppLanguages.localizedContext(context).getString(R.string.navigation_files))
        AppLanguages.setSelection(context, "")
        assertEquals(true, manager.applicationLocales.isEmpty)
    }
}
