package com.shixiaoshi0417.nekokem.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The Tauri app's catalog (desktop/src/i18n.ts), exported to strings.json by
 * `npm run strings:compose`; a desktop test keeps the two the same.
 */
object Strings {
    /** Catalog order of each message's translations. */
    val languages = listOf("en", "zh-CN", "zh-TW", "ja", "ko")
    /** The language menu: "system" first, then each language in its own name. */
    val choices: List<Pair<String, String>>
    private val messages: Map<String, List<String>>

    init {
        val text = Strings::class.java.getResourceAsStream("/strings.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val root = Json.parseToJsonElement(text).jsonObject
        val names = root["names"]!!.jsonObject
        choices = root["languages"]!!.jsonArray.map { val tag = (it as JsonPrimitive).content; tag to (names[tag] as JsonPrimitive).content }
        messages = root["messages"]!!.jsonObject.mapValues { (_, value) -> value.jsonArray.map { (it as JsonPrimitive).content } }
    }

    fun has(key: String) = key in messages

    fun get(key: String, language: String): String {
        val values = messages[key] ?: return key
        return values.getOrNull(languages.indexOf(language).coerceAtLeast(0)) ?: values[0]
    }
}
