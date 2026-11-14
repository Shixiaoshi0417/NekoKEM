package com.shixiaoshi0417.nekokem.keys

import org.junit.Assert.assertEquals
import org.junit.Test

class PasswordManagerCredentialsTest {
    @Test
    fun credentialIdShowsTheFirstSixteenFingerprintDigits() {
        assertEquals(
            "NekoKEM 0123 4567 89AB CDEF",
            PasswordManagerCredentials.credentialId("0123456789abcdef" + "0".repeat(48)),
        )
    }

    @Test
    fun credentialIdIgnoresSeparatorsInTheFingerprint() {
        assertEquals(
            "NekoKEM 0123 4567 89AB CDEF",
            PasswordManagerCredentials.credentialId("0123 4567:89AB-CDEF 0000"),
        )
    }

    @Test
    fun credentialIdWithoutPublicKeyIsTheAppName() {
        assertEquals("NekoKEM", PasswordManagerCredentials.credentialId(null))
        assertEquals("NekoKEM", PasswordManagerCredentials.credentialId(""))
    }
}
