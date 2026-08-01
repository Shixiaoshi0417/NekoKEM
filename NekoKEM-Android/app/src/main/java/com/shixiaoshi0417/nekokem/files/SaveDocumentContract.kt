package com.shixiaoshi0417.nekokem.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContract
import java.util.Locale

data class SaveDocumentRequest(
    val displayName: String,
    val mimeType: String,
)

class SaveDocumentContract : ActivityResultContract<SaveDocumentRequest, Uri?>() {
    override fun createIntent(context: Context, input: SaveDocumentRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = input.mimeType
            putExtra(Intent.EXTRA_TITLE, input.displayName)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

fun encryptedDocumentRequest(
    inputName: String,
    defaultName: String,
): SaveDocumentRequest = SaveDocumentRequest(
    displayName = encryptedOutputName(inputName, defaultName),
    mimeType = BINARY_MIME_TYPE,
)

fun decryptedDocumentRequest(
    inputName: String,
    defaultName: String,
    defaultDecryptedName: String,
): SaveDocumentRequest {
    val displayName = decryptedOutputName(
        inputName,
        defaultName,
        defaultDecryptedName,
    )
    return SaveDocumentRequest(
        displayName = displayName,
        mimeType = mimeTypeForFilename(displayName),
    )
}

fun publicKeyExportRequest(): SaveDocumentRequest = SaveDocumentRequest(
    displayName = PUBLIC_KEY_EXPORT_NAME,
    mimeType = mimeTypeForFilename(PUBLIC_KEY_EXPORT_NAME),
)

fun encryptedPrivateKeyExportRequest(): SaveDocumentRequest = SaveDocumentRequest(
    displayName = PRIVATE_KEY_EXPORT_NAME,
    mimeType = BINARY_MIME_TYPE,
)

internal fun encryptedOutputName(inputName: String, defaultName: String): String =
    safeDocumentName(inputName, defaultName) + NKEM_EXTENSION

internal fun decryptedOutputName(
    inputName: String,
    defaultName: String,
    defaultDecryptedName: String,
): String {
    val safeName = safeDocumentName(inputName, defaultName)
    return if (safeName.endsWith(NKEM_EXTENSION, ignoreCase = true)) {
        safeName.dropLast(NKEM_EXTENSION.length).ifEmpty { defaultDecryptedName }
    } else {
        DECRYPTED_PREFIX + safeName
    }
}

internal fun mimeTypeForFilename(filename: String): String {
    val extension = filename.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
    commonMimeType(extension)?.let { return it }
    if (extension.isEmpty()) {
        return BINARY_MIME_TYPE
    }
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: BINARY_MIME_TYPE
}

private fun commonMimeType(extension: String): String? = when (extension) {
    JPEG_EXTENSION, JPG_EXTENSION -> JPEG_MIME_TYPE
    MP4_EXTENSION -> MP4_MIME_TYPE
    TEXT_EXTENSION -> TEXT_MIME_TYPE
    else -> null
}

private fun safeDocumentName(inputName: String, defaultName: String): String {
    val cleaned = inputName
        .substringAfterLast('/')
        .filter { character -> character.code >= 0x20 && character != '\u007f' }
        .take(MAX_DOCUMENT_NAME_LENGTH)
    return cleaned.ifEmpty { defaultName }
}

internal const val BINARY_MIME_TYPE = "application/octet-stream"
private const val PUBLIC_KEY_EXPORT_NAME = "public.key"
private const val PRIVATE_KEY_EXPORT_NAME = "private.nkpr"
private const val NKEM_EXTENSION = ".nkem"
private const val DECRYPTED_PREFIX = "decrypted_"
private const val JPEG_EXTENSION = "jpeg"
private const val JPG_EXTENSION = "jpg"
private const val MP4_EXTENSION = "mp4"
private const val TEXT_EXTENSION = "txt"
private const val JPEG_MIME_TYPE = "image/jpeg"
private const val MP4_MIME_TYPE = "video/mp4"
private const val TEXT_MIME_TYPE = "text/plain"
private const val MAX_DOCUMENT_NAME_LENGTH = 200
