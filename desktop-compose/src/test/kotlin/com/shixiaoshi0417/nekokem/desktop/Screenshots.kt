package com.shixiaoshi0417.nekokem.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.unit.Density
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat

/**
 * Renders the real window offscreen, with the real bridge and Core, into PNG
 * files: `./gradlew screenshots -Pnekokem.bridge=… -Pnekokem.shots=…`. The
 * keys and contacts are made in a temporary directory first.
 */
@OptIn(InternalComposeUiApi::class)
// Everything runs on the Swing thread, where the scene also posts its own work.
fun main(args: Array<String>): Unit = runBlocking(Dispatchers.Main) {
    val out = File(args.getOrElse(0) { "build/screenshots" }).apply { mkdirs() }
    val work = kotlin.io.path.createTempDirectory("nekokem-shots").toFile()
    fun file(name: String) = File(work, name).absolutePath
    val password = "correct horse battery staple"
    for (name in listOf("me", "alice", "bob")) {
        withContext(Dispatchers.IO) { Backend.run(OperationRequest(id = "keygen-$name", kind = Kind.Keygen, publicPath = file("$name.public.key"), privatePath = file("$name.private.key.enc"), password = password, confirmation = password)) { _, _ -> } }
    }
    withContext(Dispatchers.IO) {
        runCatching { Backend.saveContact(file("alice.public.key"), "", false, "Alice") }.getOrNull()?.let { Backend.setVerified(it.id, it.fingerprint, true) }
        runCatching { Backend.saveContact(file("bob.public.key"), "", false, "Bob · laptop") }.getOrNull()?.let { Backend.setPinned(it.id, true) }
    }
    File(work, "report.pdf").writeBytes(ByteArray(3_000_000) { it.toByte() })
    // An identity key pair written by NekoKEM 5.2.0; Gradle runs this in desktop-compose.
    val fixture = File(System.getProperty("nekokem.fixtures") ?: "../core/tests/fixtures/nkpr-v2").absoluteFile
    check(fixture.resolve("private.key.enc").isFile) { "Missing NKPR v2 fixture: $fixture" }

    for (style in Style.entries) for (theme in listOf(SystemTheme.Light, SystemTheme.Dark)) {
        // The save dialog answers with a new file beside the input, as a user would.
        val saved = mutableListOf<String>()
        val outFolder = File(work, "${style.wire}-${theme.name}-out").apply { mkdirs() }
        val chooser = object : FileChooser {
            override fun open() = null
            override fun save(suggested: String, directory: String?) = File(directory ?: work.path, "${style.wire}-${theme.name}-$suggested").absolutePath.also(saved::add)
            override fun folder(directory: String?) = outFolder.absolutePath
        }
        val state = AppState(this, chooser) {}
        state.start()
        while (!state.loaded || !state.contactsLoaded) delay(20)
        state.changeStyle(style)
        val scene = ImageComposeScene(1180 * 2, 820 * 2, Density(2f)) {
            CompositionLocalProvider(LocalSystemTheme provides theme) { NekoKEMApp(state) }
        }
        suspend fun shot(name: String) {
            var time = 0L
            repeat(40) { Snapshot.sendApplyNotifications(); scene.render(time); time += 50_000_000L; delay(5) }
            val image = scene.render(time)
            File(out, "${style.wire}-${theme.name.lowercase()}-$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        suspend fun settle() { repeat(100) { delay(20); if (!state.busy && !state.contactBusy) return } }

        suspend fun done() { repeat(200) { delay(20); if (!state.locked && state.fileAction == null) return } }
        state.switchTab(View.Files); settle()
        shot("files-empty")
        state.setPath(PathField.OwnPublicKey, file("me.public.key"))
        state.setPath(PathField.SignKeyPath, file("me.private.key.enc"))
        state.setPath(PathField.Input, file("report.pdf"))
        shot("files")
        state.encrypt(); settle()
        state.contacts.first { it.note == "Alice" }.let(state::toggleContact)
        shot("encrypt-recipients")
        state.confirmRecipients()
        check(state.askingPassword) { "Signing asks for the password" }
        shot("encrypt-password")
        state.password.edit { append(password) }
        state.confirmPassword(); done()
        check(state.result?.output == saved.last() && File(saved.last()).length() > 3_000_000) { "Encrypted file was not saved: ${state.failure}" }
        shot("encrypt-done")

        // The file comes back through the same page: pick it, decrypt, save.
        val encrypted = saved.last()
        state.setPath(PathField.Input, encrypted)
        state.setPath(PathField.OtherPrivateKey, file("alice.private.key.enc"))
        state.decrypt()
        state.password.edit { append(password) }
        state.confirmPassword(); done()
        check(state.result?.signature != null && File(saved.last()).readBytes().contentEquals(File(work, "report.pdf").readBytes())) { "Decrypted file differs: ${state.failure}" }
        shot("decrypt-done")
        state.restoreDefaultKey()

        // With a decrypt folder set, files go there without the save dialog and
        // a taken name gets a number.
        val dialogs = saved.size
        state.changeDecryptFolder(true)
        while (state.choosing != null) delay(20)
        check(state.decryptFolder == outFolder.absolutePath) { "Decrypt folder was not set" }
        repeat(2) {
            state.setPath(PathField.Input, encrypted)
            state.setPath(PathField.OtherPrivateKey, file("alice.private.key.enc"))
            state.decrypt()
            state.password.edit { append(password) }
            state.confirmPassword(); done()
        }
        val restored = File(work, "report.pdf").readBytes()
        check(saved.size == dialogs) { "The save dialog was shown although a folder is set" }
        check(File(outFolder, "report.pdf").readBytes().contentEquals(restored) && File(outFolder, "report (1).pdf").readBytes().contentEquals(restored)) {
            "Decrypt folder output is wrong: ${outFolder.list()?.toList()} ${state.failure}"
        }
        check(state.result?.output == File(outFolder, "report (1).pdf").absolutePath) { "Result names ${state.result?.output}" }
        shot("decrypt-folder-done")
        state.restoreDefaultKey()

        // A private key from NekoKEM 5.2 (NKPR v2) that is not the user's own:
        // the password dialog says so, and the file still decrypts.
        // Core creates the two files, so they are owner-only on every system
        // (Windows Core reads no other private key); the fixture replaces
        // only their contents.
        val received = "${style.wire}-${theme.name}-received"
        val oldKey = File(file("$received.private.key.enc"))
        val oldPublic = File(file("$received.public.key"))
        withContext(Dispatchers.IO) {
            Backend.run(OperationRequest(id = "keygen-$received", kind = Kind.Keygen, publicPath = oldPublic.absolutePath, privatePath = oldKey.absolutePath, password = password, confirmation = password)) { _, _ -> }
            oldKey.writeBytes(fixture.resolve("private.key.enc").readBytes())
            oldPublic.writeBytes(fixture.resolve("public.key").readBytes())
        }
        check(Backend.keyFormat(oldKey.absolutePath) == 2) { "The fixture is not an NKPR v2 key" }
        state.setPath(PathField.Input, encrypted)
        state.setPath(PathField.OtherPrivateKey, oldKey.absolutePath)
        state.decrypt()
        for (attempt in 1..100) { if (state.oldKeyFormat) break; delay(20) }
        check(state.oldKeyFormat) { "An NKPR v2 key that is not the user's own was not announced" }
        shot("decrypt-old-key-password")
        state.cancelPassword()
        check(!state.oldKeyFormat) { "The old-format notice outlived its dialog" }
        state.restoreDefaultKey()

        state.switchTab(View.Keygen); settle()
        shot("keygen")

        // The upgrade page rewrites that key as NKPR v3 with the same password.
        state.changeKeyMode(KeyMode.Upgrade)
        state.setPath(PathField.PublicPath, oldPublic.absolutePath)
        state.setPath(PathField.PrivatePath, oldKey.absolutePath)
        shot("key-upgrade")
        state.password.edit { append("nkpr-v2-fixture-password") }
        state.submit(); settle()
        check(state.result?.keyFormat == 3 && Backend.keyFormat(oldKey.absolutePath) == 3) { "The key was not rewritten as NKPR v3: ${state.failure}" }
        shot("key-upgrade-done")
        state.password.edit { append("nkpr-v2-fixture-password") }
        state.submit(); settle()
        check(state.failure == "already-identity") { "A current key was upgraded again: ${state.failure}" }
        // The upgrade made these the user's own keys; the next shots use "me" again.
        state.setPath(PathField.OwnPublicKey, file("me.public.key"))
        state.setPath(PathField.SignKeyPath, file("me.private.key.enc"))
        state.changeKeyMode(KeyMode.Generate)

        state.switchTab(View.Contacts); settle()
        shot("contacts")

        state.switchTab(View.Fingerprint); settle()
        state.setPath(PathField.KeyPath, file("alice.public.key"))
        state.submit(); settle()
        shot("fingerprint")
        state.result?.fingerprint?.let(state::showQr)
        shot("fingerprint-qr")
        state.closeQr()

        state.switchTab(View.Settings); settle()
        shot("settings-decrypt-folder")
        // Cleared again, so the next style starts with the save dialog.
        state.changeDecryptFolder(false)
        shot("settings")
        scene.close()
    }
    work.deleteRecursively()
    println("Screenshots: ${out.absolutePath}")
    Unit
}
