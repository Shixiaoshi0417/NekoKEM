package com.shixiaoshi0417.nekokem.desktop

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

// The fingerprint QR code shared with the Android and macOS apps: NEKOKEM-FP1:
// and the 64 hexadecimal digits, upper case, at level M. It carries only the
// fingerprint, never a key.
object FingerprintQr {
    const val PREFIX = "NEKOKEM-FP1:"
    private val displayed = Regex("^[0-9A-F]{2}(?::[0-9A-F]{2}){31}$")

    fun isFingerprint(value: String) = displayed.matches(value)

    fun payload(fingerprint: String): String {
        require(isFingerprint(fingerprint)) { "Not a NekoKEM fingerprint" }
        return PREFIX + fingerprint.replace(":", "")
    }

    /** Dark modules, without a quiet zone (the dialog draws it). */
    fun modules(fingerprint: String): Array<BooleanArray> {
        val matrix = QRCodeWriter().encode(payload(fingerprint), BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
        return Array(matrix.height) { row -> BooleanArray(matrix.width) { column -> matrix.get(column, row) } }
    }
}
