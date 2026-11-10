package com.shixiaoshi0417.nekokem.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactFingerprintFormatTest {
    private val fingerprint = (0 until 32).joinToString(":") { "%02X".format(it * 8) }

    @Test fun gridHasFourRowsOfFourGroups() {
        val rows = fingerprintRows(fingerprint)
        assertEquals(4, rows.size)
        assertEquals("0008 1018 2028 3038", rows.first())
        assertEquals("C0C8 D0D8 E0E8 F0F8", rows.last())
        assertEquals(fingerprint.replace(":", ""), rows.joinToString("").replace(" ", ""))
    }

    @Test fun shortFormKeepsBothEnds() {
        assertEquals("0008 1018 … F0F8", shortFingerprint(fingerprint))
        assertEquals("ABCD", shortFingerprint("AB:CD"))
    }
}
