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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.shixiaoshi0417.nekokem.R
import com.shixiaoshi0417.nekokem.ui.theme.NekoKEMTheme
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Draws what NKPR v3 changed at 1080 x 2400 (420 dpi, default font size):
 * the key page of an identity key whose private key file is still NKPR v2,
 * and the password dialogs for a key file in a format without key
 * commitment. The images go to the unit test results.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h914dp-420dpi")
class KeyCommitmentScreenshots {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val directory = File("build/test-results/key-commitment-screens").apply { mkdirs() }

    @Test fun keysOutdatedMiuixLight() = keys(UiStyle.MIUIX, dark = false, outdated = true)

    @Test fun keysOutdatedMiuixDark() = keys(UiStyle.MIUIX, dark = true, outdated = true)

    @Test fun keysOutdatedMaterialLight() = keys(UiStyle.MATERIAL, dark = false, outdated = true)

    @Test fun keysCurrentMiuixLight() = keys(UiStyle.MIUIX, dark = false, outdated = false)

    @Test fun importOldFormatMiuixLight() = importDialog(UiStyle.MIUIX, dark = false)

    @Test fun importOldFormatMiuixDark() = importDialog(UiStyle.MIUIX, dark = true)

    @Test fun importOldFormatMaterialLight() = importDialog(UiStyle.MATERIAL, dark = false)

    @Test
    fun temporaryKeyOldFormatMiuixLight() {
        show(UiStyle.MIUIX, dark = false) {
            SinglePasswordDialog(
                titleResource = R.string.select_temporary_private_key_password_title,
                messageResource = R.string.select_temporary_private_key_password_message,
                noteResource = R.string.old_key_format_note,
                onDismiss = {},
                onConfirm = { password, _ -> password.fill(0) },
            )
        }
        compose.onNodeWithText(string(R.string.old_key_format_note)).assertIsDisplayed()
        capture("miuix-light-temporary-key-old-format")
    }

    @Test
    fun upgradeFormatDialogMiuixLight() {
        show(UiStyle.MIUIX, dark = false) {
            SinglePasswordDialog(
                titleResource = R.string.upgrade_format_password_title,
                messageResource = R.string.upgrade_format_password_message,
                onDismiss = {},
                onConfirm = { password, _ -> password.fill(0) },
            )
        }
        capture("miuix-light-upgrade-format")
    }

    private fun keys(style: UiStyle, dark: Boolean, outdated: Boolean) {
        var pressed = 0
        val state = NekoKEMUiState(
            nativeConnected = true,
            nativeVersion = "5.2.0",
            privateKeyExists = true,
            fingerprint = "0123 4567 89AB CDEF 0123 4567 89AB CDEF 0123 4567 89AB CDEF 0123 4567 89AB CDEF",
            selectedFileName = null,
            publicKeyTemporary = false,
            publicKeyFileName = "public.key",
            publicKeyFingerprint = "0123 4567 89AB CDEF",
            privateKeyTemporary = false,
            privateKeyFileName = "private.key",
            privateKeyFingerprint = "0123 4567 89AB CDEF",
            running = false,
            uiStyle = style,
            canSign = true,
            privateKeyOutdated = outdated,
        )
        val actions = NekoKEMActions(
            onGenerate = {}, onCheckPassword = {}, onExportPublicKey = {}, onExportPrivateKey = {},
            onImportPublicKey = {}, onImportPrivateKey = {}, onDeletePublicKey = {},
            onDeletePrivateKey = {}, onDeleteKeypair = {}, onSelectFile = {},
            onClearSelectedFile = {}, onSelectTemporaryPublicKey = {}, onRestoreDefaultPublicKey = {},
            onSelectTemporaryPrivateKey = {}, onRestoreDefaultPrivateKey = {}, onEncrypt = {},
            onDecrypt = {}, onImportContact = {}, onChooseContact = {},
            onSelectContact = { _, _ -> }, onEditContact = {}, onDeleteContact = {},
            onUpgradeKeyFormat = { pressed += 1 },
        )
        material = false
        compose.setContent {
            NekoKEMTheme(uiStyle = style, darkTheme = dark, dynamicColor = false) {
                NekoKEMAppShell(state, actions, remember { SnackbarHostState() })
            }
        }
        compose.onAllNodesWithText(string(R.string.tab_keys)).onFirst().performClick()
        compose.waitForIdle()
        val action = string(R.string.action_upgrade_key_format)
        if (outdated) {
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(action))
            compose.waitForIdle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            save(bitmap, "${style.tag}-${if (dark) "dark" else "light"}-keys-old-format")
            compose.onAllNodesWithText(action).onFirst().performClick()
            compose.waitForIdle()
            assertEquals(1, pressed)
        } else {
            // A key already in the current format offers nothing to upgrade.
            assertEquals(0, compose.onAllNodesWithText(action).fetchSemanticsNodes().size)
            assertEquals(
                0,
                compose.onAllNodesWithText(string(R.string.key_old_format_status), substring = true)
                    .fetchSemanticsNodes().size,
            )
            save(compose.onRoot().captureToImage().asAndroidBitmap(), "${style.tag}-light-keys-current")
        }
    }

    private fun importDialog(style: UiStyle, dark: Boolean) {
        show(style, dark) {
            SinglePasswordDialog(
                titleResource = R.string.import_password_title,
                messageResource = R.string.import_password_message,
                noteResource = R.string.old_key_format_note,
                passwordManager = PasswordManagerOptions(credentialIds = null, offerSave = true),
                onDismiss = {},
                onConfirm = { password, _ -> password.fill(0) },
            )
        }
        capture("${style.tag}-${if (dark) "dark" else "light"}-import-old-format")
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
        save(bitmap, name)
    }

    private fun save(bitmap: Bitmap, name: String) {
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun string(resource: Int): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(resource)
}
