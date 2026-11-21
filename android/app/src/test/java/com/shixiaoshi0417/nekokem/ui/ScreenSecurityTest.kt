package com.shixiaoshi0417.nekokem.ui

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScreenSecurityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun newInstallKeepsTheScreenSecure() {
        assertTrue(ScreenSecurityStore.read(context))
    }

    @Test fun choiceSurvivesAndADamagedValueStaysSecure() {
        assertTrue(ScreenSecurityStore.write(context, false))
        assertFalse(ScreenSecurityStore.read(context))
        context.getSharedPreferences("screen_security", Context.MODE_PRIVATE).edit()
            .putString("secure_screen", "no").commit()
        assertTrue(ScreenSecurityStore.read(context))
    }

    @Test fun applySetsAndClearsTheWindowFlag() {
        val window = Robolectric.buildActivity(Activity::class.java).setup().get().window
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        ScreenSecurityStore.apply(window, true)
        assertEquals(flag, window.attributes.flags and flag)
        ScreenSecurityStore.apply(window, false)
        assertEquals(0, window.attributes.flags and flag)
    }
}
