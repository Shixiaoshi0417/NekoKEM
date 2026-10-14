package com.shixiaoshi0417.nekokem.files

import org.junit.Assert.assertEquals
import org.junit.Test

/** Provider names are shown next to fingerprints and must stay one plain line. */
class DisplayNameTest {
    @Test
    fun lineBreaksCannotAddAFakeFingerprintLine() {
        val spoof = "key.pub\nSHA-256 public-key fingerprint: AAAA\u2028BBBB\u2029\r\u0085"
        assertEquals(
            "key.pubSHA-256 public-key fingerprint: AAAABBBB",
            displaySafeName(spoof, "fallback"),
        )
    }

    @Test
    fun invisibleFormatCharactersAreRemoved() {
        assertEquals("photo_gpj.exe", displaySafeName("photo_\u202Egpj.exe", "fallback"))
        assertEquals("ab", displaySafeName("\uFEFFa\u200B\u2060b\u2066\u2069", "fallback"))
        // Not on any hand-made list: soft hyphen, invisible operators,
        // Mongolian vowel separator, deprecated format controls, annotations.
        assertEquals("ab", displaySafeName("a\u00AD\u2061\u2064\u180E\u206A\uFFF9b", "fallback"))
    }

    @Test
    fun joinersInEmojiAndScriptsAreKept() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67.txt"
        assertEquals(family, displaySafeName(family, "fallback"))
        assertEquals("\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645",
            displaySafeName("\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645", "fallback"))
        // Between ASCII characters, or at an edge, a joiner only hides something.
        assertEquals("ab.txt", displaySafeName("\u200Da\u200Db\u200C.txt\u200D", "fallback"))
        // Judged after filtering: a hidden character or a second joiner is no neighbour.
        assertEquals("\u00E9a", displaySafeName("\u00E9\u200D\u200Ba", "fallback"))
        assertEquals("\u00E9\u00E9", displaySafeName("\u00E9\u200D\u200D\u00E9", "fallback"))
        assertEquals("\u00E9", displaySafeName("\u3000\u200D\u00E9", "fallback"))
    }

    @Test
    fun longFileNamesKeepTheirExtension() {
        val name = "a".repeat(150) + ".pdf.nkem"
        assertEquals(name, displaySafeName(name, "fallback"))
        val capped = displaySafeName("\uD83D\uDD11".repeat(300), "fallback")
        assertEquals(255, capped.codePointCount(0, capped.length))
        // The cap never leaves a joiner without the character it joined.
        val joined = displaySafeName("\u00E9".repeat(254) + "\u200D\u00E9", "fallback")
        assertEquals("\u00E9".repeat(254), joined)
    }

    @Test
    fun emptyOrMissingNamesUseTheFallback() {
        assertEquals("fallback", displaySafeName(null, "fallback"))
        assertEquals("fallback", displaySafeName(" \n\u202E ", "fallback"))
        assertEquals("a b", displaySafeName("  a b  ", "fallback"))
        assertEquals("fallback", displaySafeName("\uD800", "fallback"))
    }
    @Test
    fun saveNamesUseTheSameVisibleCharacterRulesWithoutChangingExtensions() {
        val spoof = "dir/\uFEFFreport\u0085\u2028\u2029\u202E\u2066\u200B\u200D.txt"
        assertEquals("report.txt.nkem", encryptedOutputName(spoof, "default"))
        assertEquals("report.txt", decryptedOutputName(spoof + ".NKEM", "default", "plain"))
        assertEquals("decrypted_report.txt", decryptedOutputName(spoof, "default", "plain"))
        assertEquals("default.nkem", encryptedOutputName("\u0085\u202E\u200B", "default"))
    }

}
