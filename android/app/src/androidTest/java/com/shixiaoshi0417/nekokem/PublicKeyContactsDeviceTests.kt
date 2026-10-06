package com.shixiaoshi0417.nekokem

import android.app.Instrumentation
import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Base64
import com.shixiaoshi0417.nekokem.files.SafFileWorkflow
import com.shixiaoshi0417.nekokem.keys.LocalKeyManager
import com.shixiaoshi0417.nekokem.keys.PublicKeyContacts
import com.shixiaoshi0417.nekokem.keys.TemporaryPublicKey
import com.shixiaoshi0417.nekokem.nativecore.NativeBridge
import com.shixiaoshi0417.nekokem.progress.CancellableProgressCallback
import java.io.File
import org.json.JSONObject

/** Real device/Core tests: no mock fingerprints, keys, crypto or filesystem calls. */
internal fun runPublicKeyContactsTests(
    context: Context,
    manager: LocalKeyManager,
    workflow: SafFileWorkflow,
    plaintext: File,
    defaultPassword: String,
) {
    val contacts = PublicKeyContacts(context, manager)
    val defaultState = manager.readState()
    val defaultPublic = File(context.filesDir, "keys/public.key").readBytes()
    val defaultPrivate = File(context.filesDir, "keys/private.nkpr.enc").readBytes()
    val recipient = File(context.cacheDir, "contact-recipient").apply {
        check(mkdir())
        Os.chmod(absolutePath, 0x1C0)
    }
    val publicKey = File(recipient, "recipient-public.key")
    val privateKey = File(recipient, "recipient-private.nkpr.enc")
    generateContactTestKeypair(publicKey, privateKey)
    val candidate = checkNotNull(workflow.stageTemporaryPublicKey(Uri.fromFile(publicKey), "recipient.key").key)
    val saved = contacts.save(candidate, "收件人 / Recipient\n备注 🐈")
    check(saved.code == NativeBridge.RESULT_SUCCESS)
    val contact = checkNotNull(saved.contact)
    workflow.discardTemporaryPublicKey(candidate)
    check(contact.fingerprint != defaultState.fingerprint)
    val record = File(context.filesDir, "${PublicKeyContacts.DIRECTORY_NAME}/${contact.id}.json")
    check(Os.lstat(record.parentFile!!.absolutePath).st_mode and 0x1FF == 0x1C0)
    check(Os.lstat(record.absolutePath).st_mode and 0x1FF == 0x180)
    check(PublicKeyContacts(context, manager).readState().contacts.single() == contact)
    val storedPublic = Base64.decode(JSONObject(record.readText()).getString("publicKey"), Base64.NO_WRAP)
    check(storedPublic.toString(Charsets.US_ASCII).contains("BEGIN PUBLIC KEY"))
    check(!storedPublic.toString(Charsets.US_ASCII).contains("PRIVATE KEY"))
    check(contacts.updateNote(contact.id, "updated 联系人").code == NativeBridge.RESULT_SUCCESS)
    val editedBytes = record.readBytes()
    check(PublicKeyContacts(context, manager).readState().contacts.single().note == "updated 联系人")
    for (invalidNote in listOf("x".repeat(513), "🐈".repeat(513), "bad\u0000note")) {
        check(contacts.updateNote(contact.id, invalidNote).code != NativeBridge.RESULT_SUCCESS)
        check(record.readBytes().contentEquals(editedBytes))
    }
    // The 512-character note limit counts code points: 512 emoji are accepted.
    check(contacts.updateNote(contact.id, "🐈".repeat(512)).code == NativeBridge.RESULT_SUCCESS)
    check(PublicKeyContacts(context, manager).readState().contacts.single().note == "🐈".repeat(512))
    // Received names lose bidirectional formatting and are cut without splitting an emoji.
    val longName = checkNotNull(workflow.stageTemporaryPublicKey(Uri.fromFile(publicKey), "🐈".repeat(200)).key)
    check(contacts.save(longName, "long name").code == NativeBridge.RESULT_SUCCESS)
    workflow.discardTemporaryPublicKey(longName)
    check(PublicKeyContacts(context, manager).readState().contacts.single().displayName == "🐈".repeat(128))
    val duplicate = checkNotNull(workflow.stageTemporaryPublicKey(
        Uri.fromFile(publicKey), "\u2067renamed\u202E.key\u200F\u061C").key)
    check(contacts.save(duplicate, "same recipient").code == NativeBridge.RESULT_SUCCESS)
    workflow.discardTemporaryPublicKey(duplicate)
    check(contacts.readState().contacts.single().displayName == "renamed.key")
    check(contacts.readState().contacts.size == 1)
    val continueProgress = contactProgress(cancelled = false)
    val pendingPrivate = checkNotNull(workflow.stageTemporaryPrivateKey(Uri.fromFile(privateKey), "recipient.nkpr").key)
    val recipientPrivate = checkNotNull(workflow.validateTemporaryPrivateKey(pendingPrivate, contactPassword()).key)
    // Repeated operations consume only snapshots. Only the chosen recipient can decrypt.
    repeat(2) { index ->
        val selected = checkNotNull(contacts.stageForEncryption(contact.id).key)
        val ciphertext = File(context.cacheDir, "contact-$index.nkem")
        val prepared = workflow.prepareEncryption(Uri.fromFile(plaintext), continueProgress, selected)
        check(prepared.code == NativeBridge.RESULT_SUCCESS)
        check(!selected.file.exists())
        check(ciphertext.createNewFile())
        check(workflow.commitPreparedEncryption(checkNotNull(prepared.prepared), Uri.fromFile(ciphertext), continueProgress) == NativeBridge.RESULT_SUCCESS)
        val decrypted = File(context.cacheDir, "contact-$index-decrypted")
        val password = contactPassword()
        check(manager.decryptFile(ciphertext, decrypted, recipientPrivate, password, continueProgress) == NativeBridge.RESULT_SUCCESS)
        check(password.all { it == 0.toByte() })
        check(decrypted.readBytes().contentEquals(plaintext.readBytes()))
        val wrongOutput = File(context.cacheDir, "contact-$index-wrong-recipient")
        check(manager.decryptFile(ciphertext, wrongOutput, defaultPassword.toByteArray(), continueProgress) != NativeBridge.RESULT_SUCCESS)
        check(!wrongOutput.exists())
        check(contacts.readState().contacts.size == 1)
    }
    workflow.discardTemporaryPrivateKey(recipientPrivate)
    val cancelledSelection = checkNotNull(contacts.stageForEncryption(contact.id).key)
    val cancelled = workflow.prepareEncryption(Uri.fromFile(plaintext), contactProgress(cancelled = true), cancelledSelection)
    check(cancelled.code == NativeBridge.RESULT_CANCELLED && cancelled.prepared == null)
    check(!cancelledSelection.file.exists() && record.exists())

    // A replaced snapshot with a different, valid public key fails before encryption.
    val replaced = checkNotNull(contacts.stageForEncryption(contact.id).key)
    replaced.file.writeBytes(defaultPublic)
    val rejectedOutput = File(context.cacheDir, "contact-replaced.nkem")
    check(manager.encryptFile(plaintext, rejectedOutput, replaced, continueProgress) == LocalKeyManager.RESULT_FINGERPRINT_MISMATCH)
    check(!rejectedOutput.exists())
    workflow.discardTemporaryPublicKey(replaced)
    val expectedRecord = record.readBytes()
    val mismatched = JSONObject(record.readText()).put("publicKey", Base64.encodeToString(defaultPublic, Base64.NO_WRAP))
    record.writeText(mismatched.toString())
    check(contacts.stageForEncryption(contact.id).code == LocalKeyManager.RESULT_FINGERPRINT_MISMATCH)
    record.writeBytes(expectedRecord)
    record.writeText("{broken")
    check(contacts.readState().unreadableEntries == 1 && contacts.readState().contacts.isEmpty())
    check(contacts.stageForEncryption(contact.id).key == null)
    record.writeBytes(expectedRecord)
    record.writeBytes(ByteArray(65537))
    check(contacts.readState().unreadableEntries == 1)
    check(contacts.stageForEncryption(contact.id).key == null)
    record.writeBytes(expectedRecord)
    for (invalidId in listOf("../keys/public.key", "", "../${contact.id}")) {
        check(contacts.stageForEncryption(invalidId).key == null)
        check(contacts.updateNote(invalidId, "unsafe").code != NativeBridge.RESULT_SUCCESS)
        check(contacts.delete(invalidId) != NativeBridge.RESULT_SUCCESS)
    }
    // Refuse links and weakened permissions without following or modifying their targets.
    Os.chmod(record.absolutePath, 0x1A4)
    check(contacts.stageForEncryption(contact.id).key == null)
    check(contacts.updateNote(contact.id, "unsafe").code != NativeBridge.RESULT_SUCCESS)
    Os.chmod(record.absolutePath, 0x180)
    val alias = File(context.cacheDir, "contact-record-alias")
    try {
        Os.link(record.absolutePath, alias.absolutePath)
        check(contacts.stageForEncryption(contact.id).key == null)
        check(contacts.delete(contact.id) != NativeBridge.RESULT_SUCCESS)
        check(alias.delete())
    } catch (error: ErrnoException) {
        // Android app SELinux policies prohibit creating hard links, including own files.
        check(error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM)
        check(!alias.exists() && record.readBytes().contentEquals(expectedRecord))
    }
    val backing = File(context.cacheDir, "contact-record-backing")
    check(record.renameTo(backing))
    Os.symlink(backing.absolutePath, record.absolutePath)
    check(contacts.stageForEncryption(contact.id).key == null)
    check(contacts.updateNote(contact.id, "unsafe").code != NativeBridge.RESULT_SUCCESS)
    check(contacts.delete(contact.id) != NativeBridge.RESULT_SUCCESS)
    check(backing.readBytes().contentEquals(expectedRecord))
    check(record.delete() && backing.renameTo(record))
    val invalid = File(context.cacheDir, "invalid-contact.key").apply {
        writeText("not a key")
        Os.chmod(absolutePath, 0x180)
    }
    check(contacts.save(TemporaryPublicKey(invalid, "invalid", contact.fingerprint), "invalid").code != NativeBridge.RESULT_SUCCESS)
    check(workflow.stageTemporaryPublicKey(Uri.fromFile(privateKey), "private.nkpr").key == null)
    check(record.readBytes().contentEquals(expectedRecord))
    check(contacts.delete(contact.id) == NativeBridge.RESULT_SUCCESS)
    check(contacts.stageForEncryption(contact.id).key == null)
    check(contacts.readState().contacts.isEmpty())
    check(manager.readState() == defaultState)
    check(File(context.filesDir, "keys/public.key").readBytes().contentEquals(defaultPublic))
    check(File(context.filesDir, "keys/private.nkpr.enc").readBytes().contentEquals(defaultPrivate))
    // Keep one contact to prove deletion of the local keypair does not delete recipients.
    val finalCandidate = checkNotNull(workflow.stageTemporaryPublicKey(Uri.fromFile(publicKey), "recipient.key").key)
    check(contacts.save(finalCandidate, "survives local key deletion").code == NativeBridge.RESULT_SUCCESS)
    workflow.discardTemporaryPublicKey(finalCandidate)
    val interrupted = File(context.cacheDir, "nekokem-contact-work/interrupted.tmp")
    check(interrupted.createNewFile())
    Os.chmod(interrupted.absolutePath, 0x180)
    check(contacts.clearWorkCache() && !interrupted.exists())
    check(contacts.readState().contacts.size == 1)
}

/** The existing script force-stops the process between these two phases. */
internal fun runContactProcessRestartTests(instrumentation: Instrumentation, phase: String?) {
    if (phase != "persist" && phase != "restart") return
    val context = instrumentation.targetContext
    val manager = LocalKeyManager(context)
    val contacts = PublicKeyContacts(context, manager)
    if (phase == "persist") {
        val root = File.createTempFile("ci-contact-restart-", ".tmp", context.cacheDir).apply {
            check(delete() && mkdir())
            Os.chmod(absolutePath, 0x1C0)
        }
        val publicKey = File(root, "public.key")
        val privateKey = File(root, "private.nkpr")
        try {
            generateContactTestKeypair(publicKey, privateKey)
            val workflow = SafFileWorkflow(context, manager)
            val key = checkNotNull(workflow.stageTemporaryPublicKey(Uri.fromFile(publicKey), "restart.key").key)
            try { check(contacts.save(key, RESTART_NOTE).code == NativeBridge.RESULT_SUCCESS) }
            finally { workflow.discardTemporaryPublicKey(key) }
        } finally {
            publicKey.delete()
            privateKey.delete()
            root.delete()
        }
    } else {
        val saved = contacts.readState().contacts.single { it.note == RESTART_NOTE }
        val selected = checkNotNull(contacts.stageForEncryption(saved.id).key)
        check(selected.fingerprint == saved.fingerprint)
        SafFileWorkflow(context, manager).discardTemporaryPublicKey(selected)
        check(contacts.delete(saved.id) == NativeBridge.RESULT_SUCCESS)
    }
}

private const val RESTART_NOTE = "CI process restart / 备注持久化"
private fun contactProgress(cancelled: Boolean) = object : CancellableProgressCallback {
    override fun onProgress(processedBytes: Long, totalBytes: Long) = !cancelled
    override fun isCancelled() = cancelled
}
private fun contactPassword() = "contact-device-test-password".toByteArray()
internal fun generateContactTestKeypair(publicKey: File, privateKey: File) {
    val password = contactPassword()
    try {
        check(NativeBridge.nativeGenerateKeypairWithPassword(publicKey.absolutePath, privateKey.absolutePath, password) == NativeBridge.RESULT_SUCCESS)
    } finally { password.fill(0) }
}
