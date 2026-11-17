package com.shixiaoshi0417.nekokem.files

import com.shixiaoshi0417.nekokem.nativecore.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The import limit for decryption must cover every container the app can write. */
class ContainerOverheadTest {
    private val recipients = NativeBridge.MAX_RECIPIENTS.toLong()

    @Test
    fun overheadIsTheLargestSignedContainer() {
        // docs/NKEM-v5.md: 10228 + 1672 * N with the file-information record.
        assertEquals(10_228L + 1_672L * 64L, MAX_CONTAINER_OVERHEAD_BYTES)
    }

    @Test
    fun overheadCoversEveryFormat() {
        val v3 = 1_716L
        val v4 = 156L + 1_672L * recipients
        val v5 = 9_204L + 1_672L * recipients
        val v5WithName = 10_228L + 1_672L * recipients
        for (overhead in listOf(v3, v4, v5, v5WithName)) {
            assertTrue(overhead <= MAX_CONTAINER_OVERHEAD_BYTES)
        }
    }
}
