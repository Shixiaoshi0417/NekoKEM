package com.shixiaoshi0417.nekokem.ui

import android.graphics.Bitmap
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.shixiaoshi0417.nekokem.R
import com.shixiaoshi0417.nekokem.ui.theme.NekoKEMTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the settings page with the decrypt folder option at 1080 x 2400
 * (420 dpi, default font size). The images go to the unit test results.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h914dp-420dpi")
class DecryptFolderSettingsScreenshots {
    @get:Rule
    val compose = createComposeRule()

    private val directory = File("build/test-results/decrypt-folder-screens").apply { mkdirs() }

    @Test fun miuixLightOff() = settings(UiStyle.MIUIX, dark = false, folder = null)

    @Test fun miuixLightOn() = settings(UiStyle.MIUIX, dark = false, folder = "Download")

    @Test fun miuixDarkOn() = settings(UiStyle.MIUIX, dark = true, folder = "Download")

    @Test fun miuixLightUnavailable() = settings(UiStyle.MIUIX, dark = false, folder = "", reachable = false)

    @Test fun materialLightOn() = settings(UiStyle.MATERIAL, dark = false, folder = "Download")

    @Test fun materialDarkOn() = settings(UiStyle.MATERIAL, dark = true, folder = "Download")

    private fun settings(style: UiStyle, dark: Boolean, folder: String?, reachable: Boolean = true) {
        val state = NekoKEMUiState(
            nativeConnected = true,
            nativeVersion = "5.2.0",
            privateKeyExists = true,
            fingerprint = "0123 4567 89AB CDEF",
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
            decryptFolderSet = folder != null,
            decryptFolderName = folder?.takeIf { reachable },
        )
        val actions = NekoKEMActions(
            onGenerate = {}, onCheckPassword = {}, onExportPublicKey = {}, onExportPrivateKey = {},
            onImportPublicKey = {}, onImportPrivateKey = {}, onDeletePublicKey = {},
            onDeletePrivateKey = {}, onDeleteKeypair = {}, onSelectFile = {},
            onClearSelectedFile = {}, onSelectTemporaryPublicKey = {}, onRestoreDefaultPublicKey = {},
            onSelectTemporaryPrivateKey = {}, onRestoreDefaultPrivateKey = {}, onEncrypt = {},
            onDecrypt = {}, onImportContact = {}, onChooseContact = {},
            onSelectContact = { _, _ -> }, onEditContact = {}, onDeleteContact = {},
        )
        compose.setContent {
            NekoKEMTheme(uiStyle = style, darkTheme = dark, dynamicColor = false) {
                NekoKEMAppShell(state, actions, remember { SnackbarHostState() })
            }
        }
        compose.onAllNodesWithText(string(R.string.tab_settings)).onFirst().performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(
                hasText(
                    string(
                        if (folder == null) R.string.settings_decrypt_folder_title
                        else R.string.settings_decrypt_folder_location,
                    ),
                ),
            )
        compose.waitForIdle()
        val name = "${style.tag}-${if (dark) "dark" else "light"}-" + when {
            folder == null -> "off"
            !reachable -> "unavailable"
            else -> "on"
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun string(resource: Int): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(resource)
}
