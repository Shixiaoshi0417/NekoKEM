package com.shixiaoshi0417.nekokem.ui

import android.graphics.Bitmap
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.shixiaoshi0417.nekokem.R
import com.shixiaoshi0417.nekokem.keys.PublicKeyContact
import com.shixiaoshi0417.nekokem.keys.PublicKeyContactsState
import com.shixiaoshi0417.nekokem.ui.theme.NekoKEMTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the four tabs on a phone (1080 x 2400, 420 dpi) and on a tablet in
 * portrait and landscape (800 x 1280 dp), in both interface styles. The
 * images go to build/test-results/adaptive-layout-screens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AdaptiveLayoutScreenshots {
    @get:Rule
    val compose = createComposeRule()

    private val directory = File("build/test-results/adaptive-layout-screens").apply { mkdirs() }

    @Test @Config(qualifiers = PHONE)
    fun phoneMiuixLight() = tabs(UiStyle.MIUIX, dark = false, "phone")

    @Test @Config(qualifiers = PHONE)
    fun phoneMiuixDark() = tabs(UiStyle.MIUIX, dark = true, "phone")

    @Test @Config(qualifiers = PHONE)
    fun phoneMaterialLight() = tabs(UiStyle.MATERIAL, dark = false, "phone")

    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortraitMiuixLight() = tabs(UiStyle.MIUIX, dark = false, "tablet-portrait")

    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortraitMaterialDark() = tabs(UiStyle.MATERIAL, dark = true, "tablet-portrait")

    @Test @Config(qualifiers = TABLET_LANDSCAPE)
    fun tabletLandscapeMiuixLight() = tabs(UiStyle.MIUIX, dark = false, "tablet-landscape")

    @Test @Config(qualifiers = TABLET_LANDSCAPE)
    fun tabletLandscapeMiuixDark() = tabs(UiStyle.MIUIX, dark = true, "tablet-landscape")

    @Test @Config(qualifiers = TABLET_LANDSCAPE)
    fun tabletLandscapeMaterialLight() = tabs(UiStyle.MATERIAL, dark = false, "tablet-landscape")

    private fun tabs(style: UiStyle, dark: Boolean, device: String) {
        val state = NekoKEMUiState(
            nativeConnected = true,
            nativeVersion = "6.0.0",
            privateKeyExists = true,
            fingerprint = FINGERPRINT,
            selectedFileName = "report.pdf",
            publicKeyTemporary = false,
            publicKeyFileName = "public.key",
            publicKeyFingerprint = FINGERPRINT,
            privateKeyTemporary = false,
            privateKeyFileName = "private.nkpr.enc",
            privateKeyFingerprint = FINGERPRINT,
            running = false,
            uiStyle = style,
            canSign = true,
            contactsState = PublicKeyContactsState(
                contacts = listOf(
                    contact("bob", "Bob", "65D1 11FC 9A0B 7C2E 4D5F 6A7B 8C9D F1F2", pinned = true),
                    contact("alice", "Alice", "8FAB 3E98 1C2D 3E4F 5A6B 7C8D 9E0F E59C", verified = true),
                    contact("carol", "Carol", "1A2B 3C4D 5E6F 7A8B 9C0D 1E2F 3A4B 5C6D", archived = true),
                ),
            ),
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
        val prefix = "$device-${style.tag}-${if (dark) "dark" else "light"}"
        capture("$prefix-files")
        for ((tab, name) in listOf(
            R.string.tab_keys to "keys",
            R.string.tab_contacts to "contacts",
            R.string.tab_settings to "settings",
        )) {
            compose.onAllNodesWithText(string(tab)).onFirst().performClick()
            capture("$prefix-$name")
        }
        // Settings scrolled half a screen: the rows pass under the title bar.
        compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput {
            swipeUp(startY = bottom * 0.8f, endY = bottom * 0.3f)
        }
        capture("$prefix-settings-scrolled")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun contact(
        id: String,
        name: String,
        fingerprint: String,
        verified: Boolean = false,
        pinned: Boolean = false,
        archived: Boolean = false,
    ) = PublicKeyContact(
        id = id,
        fingerprint = fingerprint,
        displayName = "$id.nkpk",
        note = name,
        legacyFingerprint = fingerprint,
        verified = verified,
        pinned = pinned,
        archived = archived,
    )

    private fun string(resource: Int): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(resource)

    private companion object {
        const val PHONE = "zh-rCN-w411dp-h914dp-420dpi"
        const val TABLET_PORTRAIT = "zh-rCN-w800dp-h1280dp-port-240dpi"
        const val TABLET_LANDSCAPE = "zh-rCN-w1280dp-h800dp-land-240dpi"
        const val FINGERPRINT = "0123 4567 89AB CDEF 0123 4567 89AB CDEF"
    }
}
