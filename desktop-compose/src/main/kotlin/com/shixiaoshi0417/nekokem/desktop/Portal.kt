package com.shixiaoshi0417.nekokem.desktop

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.URI
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The open, save and folder dialogs of the desktop's file chooser portal
 * (org.freedesktop.portal.FileChooser), for the Flatpak. Its sandbox sees no
 * user files; the portal shows the desktop's own dialog outside it and makes
 * just the chosen file or folder visible inside, under /run/user/UID/doc.
 *
 * Java has no D-Bus library, so this speaks the few messages it needs over the
 * session bus's Unix socket itself: Hello, AddMatch, one portal call, and the
 * Request.Response signal that carries the result.
 */
class PortalFileChooser(private val bus: () -> DBus = { DBus.session() }) : FileChooser {
    override suspend fun open() = choose("OpenFile", mapOf("multiple" to false))
    override suspend fun save(suggested: String, directory: String?) =
        choose("SaveFile", buildMap {
            put("current_name", suggested)
            startFolder(directory)?.let { put("current_folder", it) }
        })
    override suspend fun folder(directory: String?) =
        choose("OpenFile", buildMap {
            put("directory", true)
            startFolder(directory)?.let { put("current_folder", it) }
        })

    // Only a folder outside the sandbox means anything to the dialog; the
    // portal's own /run/user/UID/doc paths do not exist there.
    private fun startFolder(directory: String?): ByteArray? =
        directory?.takeIf { it.startsWith("/") && !isDocument(it) }?.let { (it + "\u0000").toByteArray() }

    // The dialog belongs to another process; waiting for it off the UI thread keeps the window painting.
    private suspend fun choose(method: String, extra: Map<String, Any>): String? = withContext(Dispatchers.IO) { request(method, extra) }

    private fun request(method: String, extra: Map<String, Any>): String? = bus().use { connection ->
        val token = "nekokem_" + UUID.randomUUID().toString().replace("-", "")
        val sender = connection.uniqueName.removePrefix(":").replace('.', '_')
        val expected = "/org/freedesktop/portal/desktop/request/$sender/$token"
        // Subscribing before the call means the response cannot be missed.
        connection.addMatch(expected)
        val options = buildMap<String, Any> { put("handle_token", token); put("modal", true); putAll(extra) }
        val reply = connection.call(
            "org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop",
            "org.freedesktop.portal.FileChooser", method, "ssa{sv}", listOf("", "", options),
        )
        val handle = reply.body.firstOrNull() as? String ?: throw IOException("No request handle")
        if (handle != expected) connection.addMatch(handle)
        val response = connection.awaitSignal(handle, "org.freedesktop.portal.Request", "Response")
        val code = response.body.getOrNull(0) as? Int ?: throw IOException("Malformed response")
        if (code != 0) return@use null
        @Suppress("UNCHECKED_CAST")
        val results = response.body.getOrNull(1) as? Map<String, Any?> ?: return@use null
        @Suppress("UNCHECKED_CAST")
        val uri = (results["uris"] as? List<String>)?.firstOrNull() ?: return@use null
        fromUri(uri)
    }

    companion object {
        /** True inside a Flatpak sandbox, where the file chooser portal is the way to the user's files. */
        fun inFlatpak(): Boolean = File("/.flatpak-info").isFile && System.getenv("DBUS_SESSION_BUS_ADDRESS") != null

        fun isDocument(path: String) = Regex("^/run/user/[0-9]+/doc/").containsMatchIn(path)

        /** A local file:// URI as a path; anything else is refused. */
        fun fromUri(uri: String): String? {
            val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
            if (parsed.scheme != "file" || !(parsed.host.isNullOrEmpty() || parsed.host == "localhost")) return null
            return parsed.path?.takeIf { it.startsWith("/") }
        }
    }
}

/** One D-Bus message as this client needs it. */
class DBusMessage(
    val type: Int,
    val serial: Int,
    val replySerial: Int?,
    val path: String?,
    val iface: String?,
    val member: String?,
    val error: String?,
    val body: List<Any?>,
)

/** A minimal D-Bus client over a Unix socket: little-endian, EXTERNAL authentication. */
class DBus private constructor(private val channel: SocketChannel) : AutoCloseable {
    private var serial = 0
    // Signals that arrive while a method reply is awaited, for awaitSignal.
    private val signals = ArrayDeque<DBusMessage>()
    val uniqueName: String

    init {
        authenticate()
        uniqueName = call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "Hello", "", emptyList())
            .body.firstOrNull() as? String ?: throw IOException("No unique name")
    }

    override fun close() = channel.close()

    fun addMatch(path: String) {
        call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "AddMatch", "s",
            listOf("type='signal',interface='org.freedesktop.portal.Request',member='Response',path='$path'"))
    }

    fun call(destination: String, path: String, iface: String, member: String, signature: String, args: List<Any>): DBusMessage {
        val sent = ++serial
        write(Wire.message(sent, destination, path, iface, member, signature, args))
        while (true) {
            val message = read()
            if (message.type == Wire.SIGNAL) { if (signals.size < 64) signals.addLast(message); continue }
            if (message.replySerial != sent) continue
            if (message.type == Wire.ERROR) throw IOException("${message.error}: ${message.body.firstOrNull()}")
            if (message.type == Wire.RETURN) return message
        }
    }

    /** Waits, as long as the user takes in the dialog, for the signal on [path]. */
    fun awaitSignal(path: String, iface: String, member: String): DBusMessage {
        while (true) {
            val message = signals.removeFirstOrNull() ?: read()
            if (message.type == Wire.SIGNAL && message.path == path && message.iface == iface && message.member == member) return message
        }
    }

    private fun authenticate() {
        val uid = File("/proc/self/status").readLines().firstOrNull { it.startsWith("Uid:") }
            ?.split(Regex("\\s+"))?.getOrNull(1) ?: throw IOException("Unknown user")
        val hex = uid.toByteArray().joinToString("") { "%02x".format(it) }
        write("\u0000AUTH EXTERNAL $hex\r\n".toByteArray())
        val answer = readLine()
        if (!answer.startsWith("OK ")) throw IOException("D-Bus authentication refused")
        write("BEGIN\r\n".toByteArray())
    }

    private fun readLine(): String {
        val line = ByteArrayOutputStream()
        val one = ByteBuffer.allocate(1)
        while (true) {
            one.clear()
            if (channel.read(one) < 0) throw IOException("D-Bus closed")
            val byte = one.get(0).toInt()
            if (byte == '\n'.code) return line.toString(Charsets.US_ASCII).trimEnd('\r')
            line.write(byte)
            if (line.size() > 4096) throw IOException("D-Bus line too long")
        }
    }

    private fun write(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    private fun readExactly(count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count)
        while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw IOException("D-Bus closed")
        return buffer.array()
    }

    private fun read(): DBusMessage {
        val fixed = readExactly(16)
        val order = ByteBuffer.wrap(fixed).order(if (fixed[0] == 'B'.code.toByte()) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val bodyLength = order.getInt(4)
        val fieldsLength = order.getInt(12)
        if (bodyLength !in 0..Wire.MAX_MESSAGE || fieldsLength !in 0..Wire.MAX_MESSAGE) throw IOException("D-Bus message too large")
        val headerLength = Wire.align(16 + fieldsLength, 8)
        return Wire.parse(fixed + readExactly(headerLength - 16 + bodyLength))
    }

    companion object {
        fun session(): DBus {
            val address = System.getenv("DBUS_SESSION_BUS_ADDRESS") ?: throw IOException("No session bus")
            // Flatpak gives the sandbox a socket path; abstract sockets are not supported by Java.
            val path = address.split(';').firstNotNullOfOrNull { entry ->
                entry.takeIf { it.startsWith("unix:") }?.removePrefix("unix:")?.split(',')
                    ?.firstOrNull { it.startsWith("path=") }?.removePrefix("path=")
            } ?: throw IOException("Unsupported session bus address")
            val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                channel.connect(UnixDomainSocketAddress.of(unescape(path)))
                return DBus(channel)
            } catch (error: Throwable) {
                channel.close(); throw error
            }
        }

        private fun unescape(value: String) = Regex("%([0-9a-fA-F]{2})").replace(value) { it.groupValues[1].toInt(16).toChar().toString() }
    }
}

/** D-Bus wire format: marshalling of the types the portal calls use, and a parser for any message. */
object Wire {
    const val RETURN = 2
    const val ERROR = 3
    const val SIGNAL = 4
    const val MAX_MESSAGE = 1 shl 24

    fun align(position: Int, boundary: Int) = (position + boundary - 1) / boundary * boundary

    private class Writer {
        val out = ByteArrayOutputStream()
        val size get() = out.size()
        fun pad(boundary: Int) { while (out.size() % boundary != 0) out.write(0) }
        fun byte(value: Int) = out.write(value)
        fun u32(value: Int) { pad(4); for (shift in 0 until 32 step 8) out.write((value ushr shift) and 0xff) }
        fun string(value: String) { val bytes = value.toByteArray(); u32(bytes.size); out.write(bytes); out.write(0) }
        fun signature(value: String) { val bytes = value.toByteArray(); byte(bytes.size); out.write(bytes); out.write(0) }
        fun patch(at: Int, value: Int) {
            val bytes = out.toByteArray()
            for (index in 0 until 4) bytes[at + index] = ((value ushr (8 * index)) and 0xff).toByte()
            out.reset(); out.write(bytes)
        }
    }

    // Writes [value] of the single complete type at the start of [type]; returns the rest of the signature.
    private fun Writer.value(type: String, value: Any): String {
        when (type[0]) {
            'y' -> byte((value as Number).toInt())
            'b' -> u32(if (value as Boolean) 1 else 0)
            'u', 'i' -> u32((value as Number).toInt())
            's', 'o' -> string(value as String)
            'g' -> signature(value as String)
            'v' -> {
                val inner = variantType(value)
                signature(inner); value(inner, value)
            }
            'a' -> {
                val element = completeType(type, 1)
                u32(0); val lengthAt = size - 4
                pad(if (element[0] == '{' || element[0] == '(') 8 else alignment(element[0]))
                val start = size
                when (value) {
                    is ByteArray -> value.forEach { byte(it.toInt()) }
                    is Map<*, *> -> {
                        val key = completeType(element, 1)
                        val item = completeType(element, 1 + key.length)
                        for ((k, v) in value) { pad(8); value(key, k!!); value(item, v!!) }
                    }
                    is List<*> -> value.forEach { value(element, it!!) }
                    else -> throw IllegalArgumentException("Not an array: $value")
                }
                patch(lengthAt, size - start)
                return type.substring(1 + element.length)
            }
            '(' -> {
                pad(8)
                var rest = type.substring(1)
                for (item in value as List<*>) rest = value(rest, item!!)
                return rest.substring(1)
            }
            else -> throw IllegalArgumentException("Unsupported type $type")
        }
        return type.substring(1)
    }

    private fun variantType(value: Any): String = when (value) {
        is Boolean -> "b"
        is String -> "s"
        is ByteArray -> "ay"
        is Int -> "u"
        else -> throw IllegalArgumentException("Unsupported variant $value")
    }

    private fun alignment(code: Char) = when (code) {
        'y', 'g', 'v' -> 1
        'n', 'q' -> 2
        'x', 't', 'd', '(', '{' -> 8
        else -> 4
    }

    /** The single complete type that starts at [from] in [signature]. */
    fun completeType(signature: String, from: Int): String {
        var end = from
        while (signature[end] == 'a') end++
        when (signature[end]) {
            '(', '{' -> {
                var depth = 0
                do {
                    when (signature[end]) { '(', '{' -> depth++; ')', '}' -> depth-- }
                    end++
                } while (depth > 0)
                return signature.substring(from, end)
            }
            else -> return signature.substring(from, end + 1)
        }
    }

    fun message(serial: Int, destination: String, path: String, iface: String, member: String, signature: String, args: List<Any>): ByteArray {
        val body = Writer()
        var rest = signature
        for (arg in args) rest = body.value(rest, arg)
        val fields = buildList<List<Any>> {
            add(listOf(1, Variant("o", path)))
            add(listOf(2, Variant("s", iface)))
            add(listOf(3, Variant("s", member)))
            add(listOf(6, Variant("s", destination)))
            if (signature.isNotEmpty()) add(listOf(8, Variant("g", signature)))
        }
        val header = Writer()
        header.byte('l'.code); header.byte(1); header.byte(0); header.byte(1)
        header.u32(body.size); header.u32(serial)
        header.u32(0); val lengthAt = header.size - 4
        header.pad(8)
        val start = header.size
        for ((code, variant) in fields) {
            header.pad(8); header.byte(code as Int)
            variant as Variant
            header.signature(variant.type); header.value(variant.type, variant.value)
        }
        header.patch(lengthAt, header.size - start)
        header.pad(8)
        return header.out.toByteArray() + body.out.toByteArray()
    }

    private class Variant(val type: String, val value: Any)

    private class Reader(val data: ByteArray, val order: ByteOrder) {
        var position = 0
        val buffer: ByteBuffer = ByteBuffer.wrap(data).order(order)
        fun pad(boundary: Int) { position = align(position, boundary); check() }
        fun check() { if (position > data.size) throw IOException("Truncated D-Bus message") }
        fun byte(): Int { check(); return data[position++].toInt() and 0xff }
        fun u32(): Int { pad(4); val value = buffer.getInt(position); position += 4; check(); return value }
        fun string(): String {
            val length = u32()
            if (length < 0 || position + length >= data.size) throw IOException("Bad D-Bus string")
            val text = String(data, position, length, Charsets.UTF_8); position += length + 1; return text
        }
        fun signature(): String {
            val length = byte()
            if (position + length >= data.size) throw IOException("Bad D-Bus signature")
            val text = String(data, position, length, Charsets.US_ASCII); position += length + 1; return text
        }

        fun value(type: String, depth: Int = 0): Any? {
            if (depth > 32) throw IOException("D-Bus value nested too deeply")
            return when (type[0]) {
                'y' -> byte()
                'b' -> u32() != 0
                'n', 'q' -> { pad(2); val value = buffer.getShort(position).toInt(); position += 2; check(); value }
                'i', 'u', 'h' -> u32()
                'x', 't', 'd' -> { pad(8); val value = buffer.getLong(position); position += 8; check(); value }
                's', 'o' -> string()
                'g' -> signature()
                'v' -> value(signature(), depth + 1)
                'a' -> {
                    val element = completeType(type, 1)
                    val length = u32()
                    pad(if (element[0] == '{' || element[0] == '(') 8 else alignment(element[0]))
                    val end = position + length
                    if (length < 0 || end > data.size) throw IOException("Bad D-Bus array")
                    if (element[0] == '{') {
                        val key = completeType(element, 1)
                        val item = completeType(element, 1 + key.length)
                        val map = LinkedHashMap<Any?, Any?>()
                        while (position < end) { pad(8); map[value(key, depth + 1)] = value(item, depth + 1) }
                        map
                    } else {
                        val list = ArrayList<Any?>()
                        while (position < end) list.add(value(element, depth + 1))
                        list
                    }
                }
                '(' -> {
                    pad(8)
                    val list = ArrayList<Any?>()
                    var rest = type.substring(1, type.length - 1)
                    while (rest.isNotEmpty()) {
                        val item = completeType(rest, 0)
                        list.add(value(item, depth + 1)); rest = rest.substring(item.length)
                    }
                    list
                }
                else -> throw IOException("Unsupported D-Bus type $type")
            }
        }
    }

    fun parse(data: ByteArray): DBusMessage {
        val order = if (data[0] == 'B'.code.toByte()) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val reader = Reader(data, order)
        reader.position = 1
        val type = reader.byte()
        reader.position = 8
        val serial = reader.u32()
        @Suppress("UNCHECKED_CAST")
        val fields = (reader.value("a(yv)") as List<List<Any?>>).associate { (it[0] as Int) to it[1] }
        reader.pad(8)
        val bodyStart = reader.position
        val signature = fields[8] as? String ?: ""
        val body = Reader(data.copyOfRange(bodyStart, data.size), order)
        val values = ArrayList<Any?>()
        var rest = signature
        while (rest.isNotEmpty()) {
            val item = completeType(rest, 0)
            values.add(body.value(item)); rest = rest.substring(item.length)
        }
        return DBusMessage(type, serial, fields[5] as? Int, fields[1] as? String, fields[2] as? String,
            fields[3] as? String, fields[4] as? String, values)
    }
}
