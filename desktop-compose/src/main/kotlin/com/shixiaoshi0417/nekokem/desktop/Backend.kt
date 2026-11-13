package com.shixiaoshi0417.nekokem.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** The bridge's commands (desktop/bridge/src/lib.rs), typed for Kotlin. */
enum class Kind(val wire: String) { Keygen("keygen"), Encrypt("encrypt"), Decrypt("decrypt"), Fingerprint("fingerprint"), Upgrade("upgrade"), ExportLegacy("export-legacy") }

/** One operation. The secret fields are read from the text fields when the request is written. */
class OperationRequest(
    val id: String, val kind: Kind,
    val input: String = "", val output: String = "", val keyPath: String = "",
    val publicPath: String = "", val privatePath: String = "",
    val password: CharSequence = "", val confirmation: CharSequence = "", val keyText: CharSequence = "",
    val paste: Boolean = false, val contacts: List<String> = emptyList(), val keyPaths: List<String> = emptyList(),
    val replace: Boolean = false, val overwrite: Boolean = false,
    val signKeyPath: String = "", val unsigned: Boolean = false, val requireSignature: Boolean = false,
    // Encryption and decryption write to a private staged file; saveOutput moves it to the chosen place.
    val stage: Boolean = false,
)

data class Signature(val status: String, val fingerprint: String?, val contact: String?, val signerKey: String?)
data class Outcome(
    val output: String? = null, val fingerprint: String? = null, val recipients: List<String> = emptyList(),
    val format: Int? = null, val signature: Signature? = null, val legacyFingerprint: String? = null, val canVerify: Boolean? = null,
)
data class Contact(val id: String, val fingerprint: String, val name: String, val note: String, val canVerify: Boolean, val legacyFingerprint: String?, val pinned: Boolean = false, val verified: Boolean = false)
data class ContactList(val contacts: List<Contact>, val unreadable: Int)
data class Settings(val language: String, val selection: String, val version: String)
sealed interface UpdateStatus {
    data class Available(val version: String) : UpdateStatus
    data object Current : UpdateStatus
    data object Failed : UpdateStatus
}
data class UpdatePreferences(val auto: Boolean = true, val lastCheck: Long = 0, val ignored: String = "")
/** The choices kept between launches. Unsigned output is never saved. */
data class DesktopPreferences(val requireSignature: Boolean = false, val signKeyPath: String = "", val publicKeyPath: String = "", val sidebarOpen: Boolean = true, val style: String = "miuix")

// Same limit as Core's NEKOKEM_MAX_RECIPIENTS for one NKEM v4/v5 file.
const val MAX_RECIPIENTS = 64

object Backend {
    private fun JsonElement.obj() = jsonObject
    private fun JsonObject.text(name: String) = string(name)
    private fun JsonObject.flag(name: String) = (this[name] as? JsonPrimitive)?.booleanOrNull

    fun settings(): Settings = Bridge.request { command("get_settings") }.obj().let {
        Settings(it.text("language") ?: "en", it.text("selection") ?: "system", it.text("version") ?: "")
    }

    fun setLanguage(language: String): String =
        (Bridge.request { obj { field("set_language") { field("language", language) } } } as JsonPrimitive).content

    fun run(request: OperationRequest, onProgress: (Long, Long) -> Unit): Outcome = outcome(Bridge.request(onProgress) {
        obj {
            field("run_operation") {
                field("request") {
                    field("id", request.id); field("kind", request.kind.wire)
                    field("input", request.input); field("output", request.output); field("keyPath", request.keyPath)
                    field("publicPath", request.publicPath); field("privatePath", request.privatePath)
                    field("password", request.password); field("confirmation", request.confirmation); field("keyText", request.keyText)
                    field("paste", request.paste)
                    arrayField("contacts") { request.contacts.forEach { value(it) } }
                    arrayField("keyPaths") { request.keyPaths.forEach { value(it) } }
                    field("replace", request.replace); field("overwrite", request.overwrite)
                    field("signKeyPath", request.signKeyPath); field("unsigned", request.unsigned); field("requireSignature", request.requireSignature)
                    field("stage", request.stage)
                }
            }
        }
    })

    fun saveOutput(destination: String) { Bridge.request { obj { field("save_output") { field("destination", destination) } } } }
    fun discardOutput() { Bridge.request { command("discard_output") } }

    private fun outcome(value: JsonElement): Outcome {
        val it = value.obj()
        val signature = (it["signature"] as? JsonObject)?.let { s -> Signature(s.text("status") ?: "unsigned", s.text("fingerprint"), s.text("contact"), s.text("signerKey")) }
        return Outcome(
            output = it.text("output"), fingerprint = it.text("fingerprint"),
            recipients = (it["recipients"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
            format = (it["format"] as? JsonPrimitive)?.intOrNull, signature = signature,
            legacyFingerprint = it.text("legacyFingerprint"), canVerify = it.flag("canVerify"),
        )
    }

    private fun contact(value: JsonElement): Contact = value.obj().let {
        Contact(it.text("id") ?: "", it.text("fingerprint") ?: "", it.text("name") ?: "", it.text("note") ?: "", it.flag("canVerify") ?: false, it.text("legacyFingerprint"), it.flag("pinned") ?: false, it.flag("verified") ?: false)
    }

    fun contacts(): ContactList = Bridge.request { command("list_contacts") }.obj().let {
        ContactList(it["contacts"]?.jsonArray?.map(::contact) ?: emptyList(), (it["unreadable"] as? JsonPrimitive)?.intOrNull ?: 0)
    }

    fun saveContact(keyPath: String, keyText: CharSequence, paste: Boolean, note: String, confirmUpgrade: String = ""): Contact = contact(Bridge.request {
        obj { field("save_contact") { field("contact") { field("keyPath", keyPath); field("keyText", keyText); field("paste", paste); field("note", note); field("confirmUpgrade", confirmUpgrade) } } }
    })

    fun updateNote(id: String, note: String): Contact = contact(Bridge.request { obj { field("update_contact_note") { field("id", id); field("note", note) } } })

    fun setPinned(id: String, pinned: Boolean): Contact = contact(Bridge.request { obj { field("set_contact_pinned") { field("id", id); field("pinned", pinned) } } })
    fun setVerified(id: String, fingerprint: String, verified: Boolean): Contact = contact(Bridge.request { obj { field("set_contact_verified") { field("id", id); field("fingerprint", fingerprint); field("verified", verified) } } })
    fun deleteContact(id: String) { Bridge.request { obj { field("delete_contact") { field("id", id) } } } }

    fun checkUpdate(): UpdateStatus = try {
        val it = Bridge.request { command("check_update") }.obj()
        when (it.text("status")) { "available" -> it.text("version")?.let(UpdateStatus::Available) ?: UpdateStatus.Failed; "current" -> UpdateStatus.Current; else -> UpdateStatus.Failed }
    } catch (_: BridgeFailure) { UpdateStatus.Failed }

    fun openReleasePage(version: String) { Bridge.request { obj { field("open_release_page") { field("version", version) } } } }

    fun updatePreferences(): UpdatePreferences = Bridge.request { command("update_preferences") }.obj().let {
        UpdatePreferences(it.flag("auto") ?: true, (it["lastCheck"] as? JsonPrimitive)?.longOrNull ?: 0, it.text("ignored") ?: "")
    }

    fun saveUpdatePreferences(value: UpdatePreferences) {
        Bridge.request { obj { field("save_update_preferences") { field("preferences") { field("auto", value.auto); field("lastCheck", value.lastCheck); field("ignored", value.ignored) } } } }
    }

    fun desktopPreferences(): DesktopPreferences = Bridge.request { command("desktop_preferences") }.obj().let {
        DesktopPreferences(it.flag("requireSignature") ?: false, it.text("signKeyPath") ?: "", it.text("publicKeyPath") ?: "", it.flag("sidebarOpen") ?: true, it.text("style") ?: "miuix")
    }

    fun saveDesktopPreferences(value: DesktopPreferences) {
        Bridge.request { obj { field("save_desktop_preferences") { field("preferences") { field("requireSignature", value.requireSignature); field("signKeyPath", value.signKeyPath); field("publicKeyPath", value.publicKeyPath); field("sidebarOpen", value.sidebarOpen); field("style", value.style) } } } }
    }
}
