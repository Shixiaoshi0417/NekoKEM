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
 * Draws the password dialogs with the password manager choices at
 * 1080 x 2400 (420 dpi). The dialogs are secure windows, so a device or
 * emulator screenshot of them is blank; these images go to the unit test
 * results for review instead.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h914dp-420dpi")
class PasswordManagerDialogScreenshots {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val directory = File("build/test-results/password-manager-screens").apply { mkdirs() }

    @Test
    fun checkPasswordDialogMaterialLight() = checkPassword(UiStyle.MATERIAL, dark = false)

    @Test
    fun checkPasswordDialogMaterialDark() = checkPassword(UiStyle.MATERIAL, dark = true)

    @Test
    fun checkPasswordDialogMiuixLight() = checkPassword(UiStyle.MIUIX, dark = false)

    @Test
    fun checkPasswordDialogMiuixDark() = checkPassword(UiStyle.MIUIX, dark = true)

    @Test
    fun generatePasswordDialogMaterialLight() = generate(UiStyle.MATERIAL, dark = false)

    @Test
    fun generatePasswordDialogMiuixLight() = generate(UiStyle.MIUIX, dark = false)

    @Test
    fun decryptPasswordDialogMiuixDark() {
        show(UiStyle.MIUIX, dark = true) {
            SinglePasswordDialog(
                titleResource = R.string.decrypt_password_title,
                messageResource = R.string.decrypt_password_message,
                labelResource = R.string.decrypt_password_label,
                passwordManager = PasswordManagerOptions(credentialId = "NekoKEM 0123 4567 89AB CDEF"),
                onDismiss = {},
                onConfirm = { password, _ -> password.fill(0) },
            )
        }
        capture("miuix-dark-decrypt-fill")
    }

    private fun checkPassword(style: UiStyle, dark: Boolean) {
        show(style, dark) {
            SinglePasswordDialog(
                titleResource = R.string.check_password_title,
                messageResource = R.string.check_password_message,
                passwordManager = PasswordManagerOptions(
                    credentialId = "NekoKEM 0123 4567 89AB CDEF",
                    offerSave = true,
                ),
                onDismiss = {},
                onConfirm = { password, _ -> password.fill(0) },
            )
        }
        // Ticking the box needs an idle window; see show().
        if (style == UiStyle.MIUIX) compose.onNodeWithText(string(R.string.password_manager_save)).performClick()
        capture("${style.tag}-${if (dark) "dark" else "light"}-check-password")
    }

    private fun generate(style: UiStyle, dark: Boolean) {
        show(style, dark) {
            GeneratePasswordDialog(onDismiss = {}, onConfirm = { password, _ -> password.fill(0) })
        }
        capture("${style.tag}-${if (dark) "dark" else "light"}-generate")
    }

    private var material = false

    private fun show(style: UiStyle, dark: Boolean, dialog: @Composable () -> Unit) {
        val content: @Composable () -> Unit = {
            NekoKEMTheme(uiStyle = style, darkTheme = dark, dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                dialog()
            }
        }
        material = style == UiStyle.MATERIAL
        if (material) {
            // A Material dialog has a window of its own, which never reports
            // idle here, so the test rule's setContent would not return.
            compose.activityRule.scenario.onActivity { it.setContent(content = content) }
            repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        } else {
            compose.setContent(content)
        }
    }

    private fun capture(name: String) {
        val bitmap = if (material) {
            // Drawn straight from the dialog window's view, for the same reason.
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
