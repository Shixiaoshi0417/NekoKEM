package com.shixiaoshi0417.nekokem.verify

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.Locale

/**
 * The fingerprint QR code: `NEKOKEM-FP1:` followed by the 64 hexadecimal
 * digits of a SHA-256 key fingerprint, upper case. Every character is in the
 * QR alphanumeric set, so the code stays small (version 4) and easy to scan.
 *
 * The code carries only the fingerprint, never a key: scanning it checks a
 * contact that is already saved, and cannot add or change one.
 */
object FingerprintQr {
    const val PREFIX = "NEKOKEM-FP1:"
    private val displayed = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){31}")
    private val payload = Regex("${Regex.escape(PREFIX)}([0-9A-F]{64})")

    /** The payload for a fingerprint as Core formats it (`AB:CD:…`). */
    fun payload(fingerprint: String): String {
        require(displayed.matches(fingerprint)) { "Not a NekoKEM fingerprint" }
        return PREFIX + fingerprint.replace(":", "")
    }

    /** The fingerprint in Core's display form, or null for any other text. */
    fun parse(text: String): String? {
        val digits = payload.matchEntire(text.trim())?.groupValues?.get(1) ?: return null
        return digits.chunked(2).joinToString(":")
    }

    /** True when [scanned] is exactly [fingerprint]; letter case is fixed by Core. */
    fun matches(scanned: String, fingerprint: String): Boolean =
        displayed.matches(fingerprint) && scanned == fingerprint.uppercase(Locale.ROOT)

    fun encode(fingerprint: String): BitMatrix = QRCodeWriter().encode(
        payload(fingerprint),
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
        ),
    )

    /** The text of a QR code in [source], or null when none is readable. */
    fun decode(source: LuminanceSource): String? {
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )
        val reader = QRCodeReader()
        for (candidate in listOf(source, source.invert())) {
            try {
                return reader.decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text
            } catch (_: NotFoundException) {
            } catch (_: ChecksumException) {
            } catch (_: FormatException) {
            } finally {
                reader.reset()
            }
        }
        return null
    }
}
