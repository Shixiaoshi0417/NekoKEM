package com.shixiaoshi0417.nekokem.verify

import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintQrTest {
    private val fingerprint = (0 until 32).joinToString(":") { "%02X".format((it * 37 + 11) and 0xFF) }

    @Test fun payloadIsCompactAndRoundTrips() {
        val payload = FingerprintQr.payload(fingerprint)
        assertEquals(FingerprintQr.PREFIX.length + 64, payload.length)
        assertTrue(payload.startsWith("NEKOKEM-FP1:"))
        assertEquals(fingerprint, FingerprintQr.parse(payload))
        assertEquals(fingerprint, FingerprintQr.parse("  $payload\n"))
    }

    @Test fun rejectsAnythingElse() {
        val digits = fingerprint.replace(":", "")
        assertNull(FingerprintQr.parse(digits))
        assertNull(FingerprintQr.parse(fingerprint))
        assertNull(FingerprintQr.parse("NEKOKEM-FP1:" + digits.lowercase()))
        assertNull(FingerprintQr.parse("NEKOKEM-FP1:" + digits.dropLast(1)))
        assertNull(FingerprintQr.parse("NEKOKEM-FP1:" + digits + "0"))
        assertNull(FingerprintQr.parse("NEKOKEM-FP2:$digits"))
        assertNull(FingerprintQr.parse("https://example.invalid/?NEKOKEM-FP1:$digits"))
        assertNull(FingerprintQr.parse(""))
    }

    @Test fun matchesOnlyTheSameFingerprint() {
        assertTrue(FingerprintQr.matches(fingerprint, fingerprint))
        val other = "00" + fingerprint.drop(2)
        assertFalse(FingerprintQr.matches(other, fingerprint))
        assertFalse(FingerprintQr.matches(fingerprint, "not a fingerprint"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesToEncodeMalformedFingerprints() {
        FingerprintQr.payload(fingerprint.lowercase())
    }

    @Test fun encodedCodeDecodesInBothPolarities() {
        val matrix = FingerprintQr.encode(fingerprint)
        // Version 4 (33 modules) holds the 76 alphanumeric characters at level M.
        assertEquals(33, matrix.width)
        for (inverted in listOf(false, true)) {
            val text = FingerprintQr.decode(render(matrix, scale = 6, inverted = inverted))
            assertEquals(fingerprint, FingerprintQr.parse(checkNotNull(text)))
        }
    }

    @Test fun blankImageHasNoCode() {
        val pixels = IntArray(200 * 200) { 0xFFFFFFFF.toInt() }
        assertNull(FingerprintQr.decode(RGBLuminanceSource(200, 200, pixels)))
    }

    private fun render(matrix: BitMatrix, scale: Int, inverted: Boolean): RGBLuminanceSource {
        val quiet = 4
        val size = (matrix.width + 2 * quiet) * scale
        val dark = if (inverted) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val light = if (inverted) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val pixels = IntArray(size * size) { index ->
            val x = index % size / scale - quiet
            val y = index / size / scale - quiet
            if (x in 0 until matrix.width && y in 0 until matrix.height && matrix[x, y]) dark else light
        }
        return RGBLuminanceSource(size, size, pixels)
    }
}
