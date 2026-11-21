package com.shixiaoshi0417.nekokem.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.shixiaoshi0417.nekokem.R
import com.shixiaoshi0417.nekokem.ui.theme.NekoKEMTheme
import com.shixiaoshi0417.nekokem.update.ReleaseInfo
import com.shixiaoshi0417.nekokem.update.Releases
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Draws the update prompt with the published 5.2.0 release notes, folded and
 * opened, at 1080 x 2400 (420 dpi). The images go to the unit test results.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h914dp-420dpi")
class UpdateDialogScreenshots {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val directory = File("build/test-results/update-notes-screens").apply { mkdirs() }

    private val release = ReleaseInfo(
        version = "5.3.0",
        pageUrl = Releases.PAGE_PREFIX + "v5.3.0",
        notes = checkNotNull(javaClass.getResource("/release-notes-v5.2.0.md")).readText(),
    )

    @Test fun miuixLightFolded() = shot(UiStyle.MIUIX, dark = false, open = false)

    @Test fun miuixLightOpen() = shot(UiStyle.MIUIX, dark = false, open = true)

    @Test fun miuixDarkOpen() = shot(UiStyle.MIUIX, dark = true, open = true)

    @Test fun materialLightFolded() = shot(UiStyle.MATERIAL, dark = false, open = false)

    @Test fun materialLightOpen() = shot(UiStyle.MATERIAL, dark = false, open = true)

    @Test fun materialDarkOpen() = shot(UiStyle.MATERIAL, dark = true, open = true)

    @Test
    @Config(qualifiers = "en-w411dp-h914dp-420dpi")
    fun miuixLightOpenEnglish() = shot(UiStyle.MIUIX, dark = false, open = true, suffix = "-en")

    private var material = false

    private fun shot(style: UiStyle, dark: Boolean, open: Boolean, suffix: String = "") {
        material = style == UiStyle.MATERIAL
        // The Material dialog's window never reports idle here, so it cannot be
        // clicked through the test rule and starts opened instead.
        show(style, dark) {
            UpdateAvailableDialog(
                release = release,
                currentVersion = "5.2.0",
                onOpen = {},
                onIgnore = {},
                onLater = {},
                notesInitiallyExpanded = open && material,
            )
        }
        if (open && !material) {
            compose.onNodeWithText(string(R.string.update_notes_show)).performClick()
            compose.waitForIdle()
            compose.onNodeWithText(string(R.string.update_notes_hide)).assertExists()
        }
        capture("${style.tag}-${if (dark) "dark" else "light"}-${if (open) "open" else "folded"}$suffix")
    }

    private fun show(style: UiStyle, dark: Boolean, dialog: @Composable () -> Unit) {
        val content: @Composable () -> Unit = {
            NekoKEMTheme(uiStyle = style, darkTheme = dark, dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                dialog()
            }
        }
        if (material) {
            compose.activityRule.scenario.onActivity { it.setContent(content = content) }
            repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        } else {
            compose.setContent(content)
        }
    }

    private fun capture(name: String) {
        val bitmap = if (material) {
            val view = checkNotNull(ShadowDialog.getLatestDialog().window).decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        } else {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        }
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun string(resource: Int): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(resource)
}
