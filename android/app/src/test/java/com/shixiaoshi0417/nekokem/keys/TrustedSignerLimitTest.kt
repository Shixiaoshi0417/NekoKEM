package com.shixiaoshi0417.nekokem.keys

import com.shixiaoshi0417.nekokem.nativecore.NativeBridge
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedSignerLimitTest {
    /** Every saved contact plus this device's key fits one verified decryption. */
    @Test fun everyContactAndThisDeviceFitTheTrustedSignerLimit() {
        assertTrue(PublicKeyContacts.MAX_CONTACTS + 1 <= NativeBridge.MAX_TRUSTED_SIGNERS)
        assertTrue(NativeBridge.MAX_TRUSTED_SIGNERS > NativeBridge.MAX_RECIPIENTS)
    }
}
