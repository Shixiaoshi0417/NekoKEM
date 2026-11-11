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
        runCatching { Backend.saveContact(file("alice.public.key"), "", false, "Alice") }
        runCatching { Backend.saveContact(file("bob.public.key"), "", false, "Bob · laptop") }
    }
    File(work, "report.pdf").writeBytes(ByteArray(3_000_000) { it.toByte() })

    for (style in Style.entries) for (theme in listOf(SystemTheme.Light, SystemTheme.Dark)) {
        val state = AppState(this, object : FileChooser { override fun open() = null; override fun save(suggested: String) = null }) {}
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

        state.switchTab(View.Encrypt); settle()
        state.setPath(PathField.KeyPath, file("alice.public.key"))
        state.setPath(PathField.Input, file("report.pdf"))
        state.setPath(PathField.Output, file("report-${style.wire}-${theme.name}.pdf.nkem"))
        state.setPath(PathField.SignKeyPath, file("me.private.key.enc"))
        shot("encrypt")
        state.password.edit { append(password) }
        state.submit(); settle()
        shot("encrypt-done")

        state.switchTab(View.Keygen); settle()
        shot("keygen")

        state.switchTab(View.Contacts); settle()
        state.contacts.firstOrNull()?.let(state::toggleContact)
        shot("contacts")

        state.switchTab(View.Fingerprint); settle()
        state.setPath(PathField.KeyPath, file("alice.public.key"))
        state.submit(); settle()
        state.result?.fingerprint?.let(state::showQr)
        shot("fingerprint-qr")
        state.closeQr()

        state.switchTab(View.Settings); settle()
        shot("settings")
        scene.close()
    }
    work.deleteRecursively()
    println("Screenshots: ${out.absolutePath}")
    Unit
}
