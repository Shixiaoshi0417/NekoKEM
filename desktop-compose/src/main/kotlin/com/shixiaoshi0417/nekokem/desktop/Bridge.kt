package com.shixiaoshi0417.nekokem.desktop

import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The desktop backend (desktop/bridge): Core, the contact store and the update
 * check. Each call blocks, so run it off the UI
 * thread. Requests are written as UTF-8 into native memory that is zeroed
 * before it is freed; secrets never become Kotlin strings here.
 */
class BridgeFailure(val code: String, val contact: String? = null, val fingerprint: String? = null) : Exception(code)

object Bridge {
    private val linker = Linker.nativeLinker()
    private val library: SymbolLookup by lazy { SymbolLookup.libraryLookup(locate(), Arena.global()) }
    private fun function(name: String, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(library.find(name).orElseThrow { IllegalStateException("Bridge symbol $name missing") }, descriptor)

    private val call by lazy { function("nekokem_desktop_call", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)) }
    private val free by lazy { function("nekokem_desktop_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)) }
    private val cancel by lazy { function("nekokem_desktop_cancel", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)) }
    private val deferClose by lazy { function("nekokem_desktop_defer_close", FunctionDescriptor.of(ValueLayout.JAVA_INT)) }
    private val closeReady by lazy { function("nekokem_desktop_close_ready", FunctionDescriptor.of(ValueLayout.JAVA_INT)) }

    // One listener for the one running operation; the bridge allows only one.
    @Volatile private var progressListener: ((Long, Long) -> Unit)? = null
    private val progressStub: MemorySegment by lazy {
        val target = MethodHandles.lookup().findStatic(Bridge::class.java, "progress", MethodType.methodType(Void.TYPE, java.lang.Long.TYPE, java.lang.Long.TYPE))
        linker.upcallStub(target, FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), Arena.global())
    }

    @JvmStatic private fun progress(processed: Long, total: Long) {
        try { progressListener?.invoke(processed, total) } catch (_: Throwable) { /* never unwind into native code */ }
    }

    /** The library beside the packaged app, or the one named for tests and development. */
    private fun locate(): java.nio.file.Path {
        val name = System.mapLibraryName("nekokem_native")
        val candidates = listOfNotNull(
            System.getProperty("nekokem.bridge")?.let(::File),
            System.getProperty("compose.application.resources.dir")?.let { File(it, name) },
        )
        return candidates.firstOrNull(File::isFile)?.toPath() ?: throw IllegalStateException("NekoKEM bridge library $name not found")
    }

    /** Sends one request built by [write]; returns the reply's `ok` value or throws [BridgeFailure]. */
    fun request(onProgress: ((Long, Long) -> Unit)? = null, write: SecretJson.() -> Unit): JsonElement {
        val json = SecretJson()
        try {
            json.write()
            Arena.ofConfined().use { arena ->
                val segment = arena.allocate(maxOf(json.size, 1).toLong())
                try {
                    MemorySegment.copy(json.bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, json.size)
                    if (onProgress != null) progressListener = onProgress
                    val reply = call.invokeExact(segment, json.size.toLong(), if (onProgress != null) progressStub else MemorySegment.NULL) as MemorySegment
                    return parse(reply)
                } finally {
                    if (onProgress != null) progressListener = null
                    segment.fill(0)
                }
            }
        } finally { json.clear() }
    }

    private fun parse(reply: MemorySegment): JsonElement {
        if (reply == MemorySegment.NULL) throw BridgeFailure("internal")
        val text = try { reply.reinterpret(Long.MAX_VALUE).getString(0) } finally { free.invokeExact(reply) }
        val body = Json.parseToJsonElement(text).jsonObject
        body["error"]?.let { error ->
            val fields = error.jsonObject
            throw BridgeFailure(fields.string("code") ?: "internal", fields.string("contact"), fields.string("fingerprint"))
        }
        return body["ok"] ?: JsonNull
    }

    fun cancel(id: String): Boolean = Arena.ofConfined().use { arena -> (cancel.invokeExact(arena.allocateFrom(id)) as Int) == 1 }

    /** True while the window must stay open: the running operation was told to stop. */
    fun deferClose(): Boolean = (deferClose.invokeExact() as Int) == 1

    /** True once a deferred close may go ahead. */
    fun closeReady(): Boolean = (closeReady.invokeExact() as Int) == 1
}

fun JsonObject.string(name: String): String? = (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * A JSON writer over one growable byte buffer that is zeroed whenever it grows
 * and when the request is done. Values are written straight from character
 * sequences, so a password from a text field is never copied into a String.
 */
class SecretJson {
    var bytes = ByteArray(1024); private set
    var size = 0; private set
    // One flag per open object or array: whether a value was written in it.
    private val written = ArrayDeque<Boolean>()
    private var afterKey = false

    private fun byte(value: Int) {
        if (size == bytes.size) {
            val grown = bytes.copyOf(bytes.size * 2)
            bytes.fill(0)
            bytes = grown
        }
        bytes[size++] = value.toByte()
    }
    private fun raw(text: String) { for (c in text) byte(c.code) }
    private fun startValue() {
        if (afterKey) { afterKey = false; return }
        if (written.isNotEmpty()) { if (written.last()) byte(','.code) else { written.removeLast(); written.addLast(true) } }
    }

    fun clear() { bytes.fill(0); size = 0; written.clear(); afterKey = false }

    fun obj(block: SecretJson.() -> Unit) { startValue(); byte('{'.code); written.addLast(false); block(); written.removeLast(); byte('}'.code) }
    fun array(block: SecretJson.() -> Unit) { startValue(); byte('['.code); written.addLast(false); block(); written.removeLast(); byte(']'.code) }
    private fun key(name: String) { startValue(); quoted(name); byte(':'.code); afterKey = true }

    fun field(name: String, block: SecretJson.() -> Unit) { key(name); obj(block) }
    fun arrayField(name: String, block: SecretJson.() -> Unit) { key(name); array(block) }
    fun field(name: String, value: CharSequence) { key(name); value(value) }
    fun field(name: String, value: Boolean) { key(name); startValue(); raw(value.toString()) }
    fun field(name: String, value: Long) { key(name); startValue(); raw(value.toString()) }
    fun value(text: CharSequence) { startValue(); quoted(text) }
    /** A bare string request such as "list_contacts". */
    fun command(name: String) = value(name)

    private fun quoted(text: CharSequence) {
        byte('"'.code)
        var index = 0
        while (index < text.length) {
            val c = text[index]
            val code = if (Character.isHighSurrogate(c) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) {
                index++; Character.toCodePoint(c, text[index])
            } else if (Character.isSurrogate(c)) 0xFFFD else c.code
            when {
                code == '"'.code || code == '\\'.code -> { byte('\\'.code); byte(code) }
                code < 0x20 -> { raw("\\u"); raw(String.format("%04x", code)) }
                code < 0x80 -> byte(code)
                code < 0x800 -> { byte(0xC0 or (code shr 6)); byte(0x80 or (code and 0x3F)) }
                code < 0x10000 -> { byte(0xE0 or (code shr 12)); byte(0x80 or ((code shr 6) and 0x3F)); byte(0x80 or (code and 0x3F)) }
                else -> { byte(0xF0 or (code shr 18)); byte(0x80 or ((code shr 12) and 0x3F)); byte(0x80 or ((code shr 6) and 0x3F)); byte(0x80 or (code and 0x3F)) }
            }
            index++
        }
        byte('"'.code)
    }

    /** The request as text, for tests only: never call it on a request that holds a secret. */
    internal fun text(): String = String(bytes, 0, size, Charsets.UTF_8)
}
