package com.shixiaoshi0417.nekokem.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.WorkerThread
import java.util.Locale

/**
 * The folder decrypted files go to without a save dialog, or none: then
 * every decryption asks where to save, as before. The app keeps a
 * persistable grant on the folder while it is set.
 */
object DecryptFolderStore {
    private const val PREFERENCES = "decrypt_folder"
    private const val TREE_URI = "tree_uri"

    fun read(context: Context): Uri? = try {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(TREE_URI, null)
            ?.let(Uri::parse)
    } catch (_: ClassCastException) {
        // A damaged value falls back to asking every time.
        null
    }

    /** Returns false when the choice could not be written; the old one then stays. */
    fun write(context: Context, treeUri: Uri?): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .apply { if (treeUri == null) remove(TREE_URI) else putString(TREE_URI, treeUri.toString()) }
            .commit()
}

/** Storage Access Framework work on the decrypt folder. */
class DecryptFolderAccess(context: Context) {
    private val contentResolver = context.contentResolver

    /** Keeps read and write access to [treeUri] across restarts. */
    fun take(treeUri: Uri): Boolean = try {
        contentResolver.takePersistableUriPermission(treeUri, GRANT_FLAGS)
        true
    } catch (_: SecurityException) {
        false
    }

    fun release(treeUri: Uri) {
        try {
            contentResolver.releasePersistableUriPermission(treeUri, GRANT_FLAGS)
        } catch (_: SecurityException) {
            // Already gone, for example after the user cleared the app's access.
        }
    }

    fun hasAccess(treeUri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }

    /** The folder's name as the provider shows it, made safe for one line. */
    @WorkerThread
    fun displayName(treeUri: Uri): String? = try {
        val folder = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        contentResolver.query(
            folder,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.let { displaySafeName(it, "") }?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    /**
     * Creates an empty file for [request] in the folder. A name that is
     * already taken gets a number, "name (1).ext", so nothing is overwritten.
     * Returns null when the folder cannot be written; the caller then asks.
     */
    @WorkerThread
    fun create(treeUri: Uri, request: SaveDocumentRequest): Uri? = try {
        if (!hasAccess(treeUri)) {
            null
        } else {
            val folderId = DocumentsContract.getTreeDocumentId(treeUri)
            val taken = childNames(treeUri, folderId)
            DocumentsContract.createDocument(
                contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(treeUri, folderId),
                request.mimeType,
                uniqueChildName(request.displayName) { it.lowercase(Locale.ROOT) in taken },
            )
        }
    } catch (_: Exception) {
        null
    }

    /** Removes a file [create] made, after its output failed or was cancelled. */
    @WorkerThread
    fun delete(document: Uri) {
        try {
            DocumentsContract.deleteDocument(contentResolver, document)
        } catch (_: Exception) {
            // An empty file is left; nothing secret was written to it.
        }
    }

    private fun childNames(treeUri: Uri, folderId: String): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
        val names = HashSet<String>()
        contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.let { names += it.lowercase(Locale.ROOT) }
            }
        } ?: throw IllegalStateException("folder cannot be listed")
        return names
    }

    private companion object {
        const val GRANT_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

/**
 * [name] itself when it is free, otherwise "name (1).ext", "name (2).ext"
 * and so on. The number goes before the last extension; a name that starts
 * with its only dot (".profile") has none. Names stay within 255 UTF-8
 * bytes, the usual file system limit, by shortening the stem.
 */
internal fun uniqueChildName(name: String, isTaken: (String) -> Boolean): String {
    if (!isTaken(name) && utf8Length(name) <= MAX_NAME_BYTES) return name
    // An "extension" too long to keep whole is part of the stem, so the stem
    // is shortened instead and the name never starts with its dot.
    val dot = name.lastIndexOf('.')
        .takeIf { it > 0 && utf8Length(name.substring(it)) <= MAX_EXTENSION_BYTES } ?: -1
    val stem = if (dot > 0) name.substring(0, dot) else name
    val extension = if (dot > 0) name.substring(dot) else ""
    val first = if (isTaken(name)) 1 else 0
    for (number in first..MAX_NUMBER) {
        val suffix = (if (number == 0) "" else " ($number)") + extension
        val fitted = fitStem(stem, MAX_NAME_BYTES - utf8Length(suffix))
        if (fitted.isEmpty()) continue
        val candidate = fitted + suffix
        if (!isTaken(candidate)) return candidate
    }
    throw IllegalStateException("no free name")
}

private fun fitStem(stem: String, maxBytes: Int): String {
    if (utf8Length(stem) <= maxBytes) return stem
    var end = stem.length
    while (end > 0 && utf8Length(stem.substring(0, end)) > maxBytes) {
        end = stem.offsetByCodePoints(end, -1)
    }
    return stem.substring(0, end)
}

private fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

private const val MAX_NAME_BYTES = 255
private const val MAX_EXTENSION_BYTES = 32
private const val MAX_NUMBER = 9999
