package com.shixiaoshi0417.nekokem.files

import org.junit.Assert.assertEquals
import org.junit.Test

/** Provider names are shown next to fingerprints and must stay one plain line. */
class DisplayNameTest {
    @Test
    fun lineBreaksCannotAddAFakeFingerprintLine() {
        val spoof = "key.pub\nSHA-256 public-key fingerprint: AAAA BBBB \r\u0085"
        assertEquals(
            "key.pubSHA-256 public-key fingerprint: AAAABBBB",
            displaySafeName(spoof, "fallback"),
        )
    }

    @Test
    fun bidirectionalAndInvisibleCharactersAreRemoved() {
        assertEquals("photo_gpj.exe", displaySafeName("photo_‮gpj.exe", "fallback"))
        assertEquals("ab", displaySafeName("﻿a​‍b⁦⁩", "fallback"))
    }

    @Test
    fun longNamesAreCutByCodePoint() {
        val name = displaySafeName("🔑".repeat(200), "fallback")
        assertEquals(128, name.codePointCount(0, name.length))
        assertEquals("🔑".repeat(128), name)
    }

    @Test
    fun emptyOrMissingNamesUseTheFallback() {
        assertEquals("fallback", displaySafeName(null, "fallback"))
        assertEquals("fallback", displaySafeName(" \n‮ ", "fallback"))
        assertEquals("a b", displaySafeName("  a b  ", "fallback"))
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
