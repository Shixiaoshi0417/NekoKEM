package com.shixiaoshi0417.nekokem.keys

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import com.shixiaoshi0417.nekokem.nativecore.NativeBridge
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import org.json.JSONObject

data class PublicKeyContact(
    val id: String,
    val fingerprint: String,
    val displayName: String,
    val note: String,
) {
    val label: String get() = note.ifBlank { displayName }
}

data class PublicKeyContactsState(
    val contacts: List<PublicKeyContact> = emptyList(),
    val unreadableEntries: Int = 0,
    val storageError: Boolean = false,
)

data class PublicKeyContactResult(val code: Int, val contact: PublicKeyContact? = null)

/** Snapshots for one encryption, or the code and id of the contact that failed. */
class ContactRecipientsResult internal constructor(
    val code: Int,
    val keys: List<TemporaryPublicKey>,
    val failedId: String?,
)

/**
 * Persistent public keys only. Each atomic record binds key bytes, identity and
 * note. Encryption snapshots are staged below [cacheRoot], the page's own cache.
 */
class PublicKeyContacts(
    context: Context,
    private val keyManager: LocalKeyManager,
    cacheRoot: File = context.cacheDir,
) {
    private val directory = File(context.filesDir, DIRECTORY_NAME)
    private val workDirectory = File(cacheRoot, WORK_DIRECTORY_NAME)
    private data class Record(val contact: PublicKeyContact, val publicKey: ByteArray)
    private class Failure(val code: Int) : Exception()

    fun readState(): PublicKeyContactsState = synchronized(lock) {
        try {
            if (!directory.exists()) return@synchronized PublicKeyContactsState()
            requirePrivateDirectory(directory)
            val entries = checkNotNull(directory.listFiles()).filter { it.name.endsWith(".json") }
            check(entries.size <= MAX_CONTACTS)
            var unreadable = 0
            val contacts = entries.mapNotNull { file ->
                try {
                    readRecord(file.name.removeSuffix(".json")).contact
                } catch (_: Exception) {
                    unreadable++
                    null
                }
            }.sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.id }))
            PublicKeyContactsState(contacts, unreadable)
        } catch (_: Exception) {
            PublicKeyContactsState(storageError = true)
        }
    }

    fun save(key: TemporaryPublicKey, note: String): PublicKeyContactResult = synchronized(lock) {
        var canonical: TemporaryPublicKey? = null
        try {
            require(validNote(note))
            requirePrivateDirectory(directory, create = true)
            val id = identity(key.fingerprint)
            val target = recordFile(id)
            if (!target.exists()) {
                check(checkNotNull(directory.listFiles()).count { it.name.endsWith(".json") } < MAX_CONTACTS)
            }
            canonical = verifiedSnapshot(key.file, key.fingerprint, key.displayName)
            val contact = PublicKeyContact(id, key.fingerprint, safeName(key.displayName), note.trim())
            writeRecord(Record(contact, readPrivateFile(canonical.file, MAX_PUBLIC_KEY_BYTES)))
            PublicKeyContactResult(NativeBridge.RESULT_SUCCESS, contact)
        } catch (error: Failure) {
            PublicKeyContactResult(error.code)
        } catch (_: IllegalArgumentException) {
            PublicKeyContactResult(NativeBridge.RESULT_INVALID_ARGUMENT)
        } catch (_: Exception) {
            PublicKeyContactResult(LocalKeyManager.RESULT_STORAGE_ERROR)
        } finally {
            canonical?.file?.delete()
        }
    }

    fun updateNote(id: String, note: String): PublicKeyContactResult = synchronized(lock) {
        try {
            require(validNote(note))
            requirePrivateDirectory(directory)
            val record = readRecord(id)
            val updated = record.copy(contact = record.contact.copy(note = note.trim()))
            writeRecord(updated)
            PublicKeyContactResult(NativeBridge.RESULT_SUCCESS, updated.contact)
        } catch (_: IllegalArgumentException) {
            PublicKeyContactResult(NativeBridge.RESULT_INVALID_ARGUMENT)
        } catch (_: Exception) {
            PublicKeyContactResult(LocalKeyManager.RESULT_STORAGE_ERROR)
        }
    }

    fun delete(id: String): Int = synchronized(lock) {
        try {
            requirePrivateDirectory(directory)
            val file = recordFile(id)
            readPrivateFile(file, MAX_RECORD_BYTES)
            check(file.delete())
            syncDirectory(directory)
            NativeBridge.RESULT_SUCCESS
        } catch (_: Exception) {
            LocalKeyManager.RESULT_STORAGE_ERROR
        }
    }

    /** Always re-read and validate; a missing/damaged entry never selects the default key. */
    fun stageForEncryption(id: String): TemporaryPublicKeyResult = synchronized(lock) {
        var raw: File? = null
        try {
            requirePrivateDirectory(directory)
            val record = readRecord(id)
            requirePrivateDirectory(workDirectory, create = true)
            raw = File.createTempFile("contact-source-", ".tmp", workDirectory)
            writePrivateFile(raw, record.publicKey)
            val selected = verifiedSnapshot(raw, record.contact.fingerprint, record.contact.displayName)
            TemporaryPublicKeyResult(NativeBridge.RESULT_SUCCESS, selected)
        } catch (error: Failure) {
            TemporaryPublicKeyResult(error.code, null)
        } catch (_: Exception) {
            TemporaryPublicKeyResult(LocalKeyManager.RESULT_STORAGE_ERROR, null)
        } finally {
            raw?.delete()
        }
    }

    /**
     * Stages every selected contact for one encryption, in order. A missing,
     * damaged or mismatched contact fails the whole selection and is named; the
     * snapshots already staged are deleted, and no contact is skipped.
     */
    fun stageRecipients(ids: List<String>): ContactRecipientsResult {
        if (ids.isEmpty() || ids.size > NativeBridge.MAX_RECIPIENTS || ids.toSet().size != ids.size) {
            return ContactRecipientsResult(NativeBridge.RESULT_INVALID_ARGUMENT, emptyList(), null)
        }
        val staged = mutableListOf<TemporaryPublicKey>()
        for (id in ids) {
            val result = stageForEncryption(id)
            val key = result.key
            if (result.code != NativeBridge.RESULT_SUCCESS || key == null) {
                staged.forEach { it.file.delete() }
                key?.file?.delete()
                val code = if (result.code == NativeBridge.RESULT_SUCCESS) LocalKeyManager.RESULT_STORAGE_ERROR else result.code
                return ContactRecipientsResult(code, emptyList(), id)
            }
            staged += key
        }
        return ContactRecipientsResult(NativeBridge.RESULT_SUCCESS, staged, null)
    }

    fun clearWorkCache(): Boolean = synchronized(lock) {
        try {
            if (workDirectory.exists()) {
                requirePrivateDirectory(workDirectory)
                checkNotNull(workDirectory.listFiles()).forEach { file ->
                    val status = Os.lstat(file.absolutePath)
                    check(OsConstants.S_ISREG(status.st_mode) && status.st_uid == Os.getuid() &&
                        status.st_nlink == 1L && (status.st_mode and PERMISSION_MASK) == PRIVATE_FILE_MODE)
                    // Interrupted writes can leave empty files; unlink without parsing them.
                    check(file.delete())
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun verifiedSnapshot(input: File, fingerprint: String, name: String): TemporaryPublicKey {
        identity(fingerprint)
        readPrivateFile(input, MAX_PUBLIC_KEY_BYTES)
        requirePrivateDirectory(workDirectory, create = true)
        val output = File.createTempFile("contact-key-", ".tmp", workDirectory)
        check(output.delete()) // Core creates its own atomic output; never pre-create it.
        var retained = false
        try {
            val code = keyManager.normalizePublicKey(input, output)
            if (code != NativeBridge.RESULT_SUCCESS) throw Failure(code)
            readPrivateFile(output, MAX_PUBLIC_KEY_BYTES)
            if (keyManager.publicKeyFingerprint(output) != fingerprint) {
                throw Failure(LocalKeyManager.RESULT_FINGERPRINT_MISMATCH)
            }
            retained = true
            return TemporaryPublicKey(output, safeName(name), fingerprint)
        } finally {
            if (!retained) output.delete()
        }
    }

    private fun readRecord(id: String): Record {
        val json = JSONObject(readPrivateFile(recordFile(id), MAX_RECORD_BYTES).toString(Charsets.UTF_8))
        check(json.get("version") == 1)
        val fingerprint = json.get("fingerprint") as String
        check(identity(fingerprint) == id)
        val name = json.get("displayName") as String
        val note = json.get("note") as String
        check(name == safeName(name) && validNote(note))
        val key = Base64.decode(json.get("publicKey") as String, Base64.NO_WRAP)
        check(key.isNotEmpty() && key.size <= MAX_PUBLIC_KEY_BYTES)
        return Record(PublicKeyContact(id, fingerprint, name, note), key)
    }

    private fun writeRecord(record: Record) {
        val contact = record.contact
        val target = recordFile(contact.id)
        if (target.exists()) readPrivateFile(target, MAX_RECORD_BYTES)
        val encoded = JSONObject()
            .put("version", 1)
            .put("fingerprint", contact.fingerprint)
            .put("displayName", contact.displayName)
            .put("note", contact.note)
            .put("publicKey", Base64.encodeToString(record.publicKey, Base64.NO_WRAP))
            .toString().toByteArray(Charsets.UTF_8)
        check(encoded.size <= MAX_RECORD_BYTES)
        val candidate = File.createTempFile(".contact-", ".tmp", directory)
        try {
            writePrivateFile(candidate, encoded)
            Os.rename(candidate.absolutePath, target.absolutePath)
            syncDirectory(directory)
        } finally {
            candidate.delete()
        }
    }

    private fun recordFile(id: String): File {
        require(id.matches(Regex("[0-9a-f]{64}")))
        return File(directory, "$id.json")
    }

    private fun identity(fingerprint: String): String {
        require(fingerprint.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){31}")))
        return fingerprint.replace(":", "").lowercase(Locale.ROOT)
    }

    private fun requirePrivateDirectory(file: File, create: Boolean = false) {
        if (!file.exists() && create) Os.mkdir(file.absolutePath, PRIVATE_DIRECTORY_MODE)
        val status = Os.lstat(file.absolutePath)
        check(OsConstants.S_ISDIR(status.st_mode) && status.st_uid == Os.getuid() &&
            (status.st_mode and PERMISSION_MASK) == PRIVATE_DIRECTORY_MODE)
    }

    private fun readPrivateFile(file: File, limit: Int): ByteArray {
        val status = Os.lstat(file.absolutePath)
        check(OsConstants.S_ISREG(status.st_mode) && status.st_uid == Os.getuid() &&
            status.st_nlink == 1L && (status.st_mode and PERMISSION_MASK) == PRIVATE_FILE_MODE &&
            status.st_size in 1L..limit.toLong())
        return FileInputStream(file).use { input ->
            val opened = Os.fstat(input.fd)
            check(opened.st_dev == status.st_dev && opened.st_ino == status.st_ino &&
                opened.st_nlink == 1L && opened.st_uid == Os.getuid() &&
                (opened.st_mode and PERMISSION_MASK) == PRIVATE_FILE_MODE)
            val bytes = ByteArray(status.st_size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                check(count > 0)
                offset += count
            }
            check(input.read() == -1)
            bytes
        }
    }

    private fun writePrivateFile(file: File, bytes: ByteArray) {
        val before = Os.lstat(file.absolutePath)
        check(OsConstants.S_ISREG(before.st_mode) && before.st_uid == Os.getuid() && before.st_nlink == 1L)
        val descriptor = Os.open(file.absolutePath, OsConstants.O_WRONLY or OsConstants.O_NOFOLLOW, 0)
        FileOutputStream(descriptor).use { output ->
            val status = Os.fstat(output.fd)
            check(OsConstants.S_ISREG(status.st_mode) && status.st_uid == Os.getuid() &&
                status.st_nlink == 1L && status.st_dev == before.st_dev && status.st_ino == before.st_ino)
            Os.fchmod(output.fd, PRIVATE_FILE_MODE)
            Os.ftruncate(output.fd, 0L)
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun syncDirectory(file: File) {
        val descriptor = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            val status = Os.fstat(descriptor)
            check(OsConstants.S_ISDIR(status.st_mode) && status.st_uid == Os.getuid() &&
                (status.st_mode and PERMISSION_MASK) == PRIVATE_DIRECTORY_MODE)
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }

    companion object {
        const val MAX_NOTE_LENGTH = 512
        internal const val DIRECTORY_NAME = "public-key-contacts"
        private const val WORK_DIRECTORY_NAME = "nekokem-contact-work"
        private const val MAX_CONTACTS = 500
        private const val MAX_PUBLIC_KEY_BYTES = 32 * 1024
        private const val MAX_RECORD_BYTES = 64 * 1024
        private const val PRIVATE_DIRECTORY_MODE = 0x1C0
        private const val PRIVATE_FILE_MODE = 0x180
        private const val PERMISSION_MASK = 0x1FF
        private val lock = Any()
        private const val MAX_NAME_LENGTH = 128
        // Bidirectional formatting characters could disguise a received file name.
        private const val BIDI_FORMATTING = "\u061C\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069"
        // Limits count Unicode code points, so an emoji is one character, not two UTF-16 units.
        fun validNote(note: String): Boolean = note.codePointCount(0, note.length) <= MAX_NOTE_LENGTH &&
            note.none { it.isISOControl() && it != '\n' && it != '\t' }
        private fun safeName(name: String): String {
            val visible = name.filterNot { it.isISOControl() || it in BIDI_FORMATTING }.trim()
            val end = if (visible.codePointCount(0, visible.length) > MAX_NAME_LENGTH) {
                visible.offsetByCodePoints(0, MAX_NAME_LENGTH)
            } else visible.length
            // Trim again so the stored name stays a fixed point of this function.
            return visible.substring(0, end).trimEnd().ifBlank { "public.key" }
        }
    }
}
