package com.shixiaoshi0417.nekokem.ui

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SignatureSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("signature_settings", Context.MODE_PRIVATE)

    @Test fun newInstallSignsAndAcceptsUnsignedFiles() {
        assertEquals(SignatureSettings(unsignedEncryption = false, requireSignature = false), SignatureSettingsStore.read(context))
    }

    @Test fun choicesSurviveAndSwitchBack() {
        assertTrue(SignatureSettingsStore.write(context, SignatureSettings(unsignedEncryption = true, requireSignature = true)))
        assertEquals(SignatureSettings(true, true), SignatureSettingsStore.read(context))
        assertTrue(preferences.getBoolean("unsigned_encryption", false))
        assertTrue(SignatureSettingsStore.write(context, SignatureSettings()))
        assertFalse(SignatureSettingsStore.read(context).unsignedEncryption)
    }

    @Test fun mistypedValueNeverTurnsSigningOff() {
        preferences.edit().putString("unsigned_encryption", "true").commit()
        assertEquals(SignatureSettings(), SignatureSettingsStore.read(context))
    }
}
