package com.shixiaoshi0417.nekokem.ui

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UiStylesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("interface_style", Context.MODE_PRIVATE)

    @Test fun newInstallOpensInMiuix() {
        assertEquals(UiStyle.MIUIX, UiStyles.selection(context))
    }

    @Test fun choiceSurvivesAndSwitchesBack() {
        assertTrue(UiStyles.setSelection(context, UiStyle.MATERIAL))
        assertEquals(UiStyle.MATERIAL, UiStyles.selection(context))
        assertEquals("material", preferences.getString("style", null))
        assertTrue(UiStyles.setSelection(context, UiStyle.MIUIX))
        assertEquals(UiStyle.MIUIX, UiStyles.selection(context))
    }

    @Test fun unknownOrMistypedValueFallsBackToDefault() {
        preferences.edit().putString("style", "holo").commit()
        assertEquals(UiStyle.MIUIX, UiStyles.selection(context))
        preferences.edit().putInt("style", 1).commit()
        assertEquals(UiStyle.MIUIX, UiStyles.selection(context))
    }
}
