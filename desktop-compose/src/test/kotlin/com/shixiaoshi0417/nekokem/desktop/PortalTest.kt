package com.shixiaoshi0417.nekokem.desktop

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PortalTest {
    @Test
    fun methodCallRoundTripsThroughTheParser() {
        val options = mapOf("handle_token" to "t1", "modal" to true, "current_folder" to "/home/a\u0000".toByteArray())
        val bytes = Wire.message(7, "org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop",
            "org.freedesktop.portal.FileChooser", "SaveFile", "ssa{sv}", listOf("", "Title", options))
        // The body starts at the 8-byte boundary after the header fields.
        assertEquals(0, headerLength(bytes) % 8)
        assertEquals(bytes.size - headerLength(bytes), ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
        val message = Wire.parse(bytes)
        assertEquals(1, message.type)
        assertEquals(7, message.serial)
        assertEquals("/org/freedesktop/portal/desktop", message.path)
        assertEquals("org.freedesktop.portal.FileChooser", message.iface)
        assertEquals("SaveFile", message.member)
        assertEquals("", message.body[0])
        assertEquals("Title", message.body[1])
        @Suppress("UNCHECKED_CAST")
        val parsed = message.body[2] as Map<String, Any?>
        assertEquals("t1", parsed["handle_token"])
        assertEquals(true, parsed["modal"])
        assertContentEquals("/home/a\u0000".toByteArray().map { it.toInt() and 0xff }, parsed["current_folder"] as List<*>)
    }

    @Test
    fun responseSignalYieldsTheChosenUri() {
        val message = Wire.parse(responseSignal("/org/freedesktop/portal/desktop/request/1_5/t1", 0,
            listOf("file:///run/user/1000/doc/ab12/My%20File.txt")))
        assertEquals(Wire.SIGNAL, message.type)
        assertEquals("Response", message.member)
        assertEquals(0, message.body[0])
        @Suppress("UNCHECKED_CAST")
        val uris = (message.body[1] as Map<String, Any?>)["uris"] as List<String>
        assertEquals("/run/user/1000/doc/ab12/My File.txt", PortalFileChooser.fromUri(uris.single()))
    }

    @Test
    fun onlyLocalFileUrisAreAccepted() {
        assertEquals("/a/b", PortalFileChooser.fromUri("file:///a/b"))
        assertEquals("/a/b", PortalFileChooser.fromUri("file://localhost/a/b"))
        assertNull(PortalFileChooser.fromUri("file://elsewhere/a/b"))
        assertNull(PortalFileChooser.fromUri("https://example.invalid/a"))
        assertNull(PortalFileChooser.fromUri("not a uri"))
        assertEquals(true, PortalFileChooser.isDocument("/run/user/1000/doc/ab12/x"))
        assertEquals(false, PortalFileChooser.isDocument("/home/user/x"))
    }

    @Test
    fun truncatedMessagesAreRefused() {
        val bytes = responseSignal("/r", 0, listOf("file:///x"))
        assertFailsWith<java.io.IOException> { Wire.parse(bytes.copyOf(bytes.size - 6)) }
    }

    @Test
    fun completeTypesSplitSignatures() {
        assertEquals("a{sv}", Wire.completeType("a{sv}u", 0))
        assertEquals("(sa(us))", Wire.completeType("(sa(us))s", 0))
        assertEquals("s", Wire.completeType("ssa{sv}", 1))
    }

    private fun headerLength(bytes: ByteArray) =
        Wire.align(16 + ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(12), 8)

    /** A Request.Response signal with results {"uris": <as>, "choices": <a(ss)>}, built by hand. */
    private fun responseSignal(path: String, code: Int, uris: List<String>): ByteArray {
        val body = Bytes()
        body.u32(code)
        body.array(8) {
            body.pad(8); body.string("uris"); body.signature("as")
            body.array(4) { uris.forEach { body.string(it) } }
            body.pad(8); body.string("choices"); body.signature("a(ss)")
            body.array(8) { body.pad(8); body.string("k"); body.string("v") }
        }
        val header = Bytes()
        header.byte('l'.code); header.byte(4); header.byte(0); header.byte(1)
        header.u32(body.size); header.u32(3)
        header.array(8) {
            header.pad(8); header.byte(1); header.signature("o"); header.string(path)
            header.pad(8); header.byte(2); header.signature("s"); header.string("org.freedesktop.portal.Request")
            header.pad(8); header.byte(3); header.signature("s"); header.string("Response")
            header.pad(8); header.byte(8); header.signature("g"); header.signature("ua{sv}")
        }
        header.pad(8)
        return header.bytes() + body.bytes()
    }

    private class Bytes {
        private val out = ByteArrayOutputStream()
        val size get() = out.size()
        fun bytes(): ByteArray = out.toByteArray()
        fun byte(value: Int) = out.write(value)
        fun pad(boundary: Int) { while (out.size() % boundary != 0) out.write(0) }
        fun u32(value: Int) { pad(4); for (shift in 0 until 32 step 8) out.write((value ushr shift) and 0xff) }
        fun string(value: String) { val b = value.toByteArray(); u32(b.size); out.write(b); out.write(0) }
        fun signature(value: String) { byte(value.length); out.write(value.toByteArray()); out.write(0) }
        fun array(alignment: Int, items: () -> Unit) {
            u32(0); val at = size - 4
            pad(alignment); val start = size
            items()
            val length = size - start
            val all = out.toByteArray()
            for (index in 0 until 4) all[at + index] = ((length ushr (8 * index)) and 0xff).toByte()
            out.reset(); out.write(all)
        }
    }
}
