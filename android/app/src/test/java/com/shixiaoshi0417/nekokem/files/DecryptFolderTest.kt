package com.shixiaoshi0417.nekokem.files

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DecryptFolderTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun unique(name: String, vararg taken: String): String =
        uniqueChildName(name) { it.lowercase() in taken.map(String::lowercase) }

    @Test fun freeNameIsKept() {
        assertEquals("photo.jpg", unique("photo.jpg", "other.jpg"))
    }

    @Test fun takenNameGetsTheNextFreeNumber() {
        assertEquals("photo (1).jpg", unique("photo.jpg", "photo.jpg"))
        assertEquals("photo (3).jpg", unique("photo.jpg", "photo.jpg", "photo (1).jpg", "PHOTO (2).JPG"))
    }

    @Test fun numberGoesBeforeTheLastExtensionOnly() {
        assertEquals("backup.tar (1).gz", unique("backup.tar.gz", "backup.tar.gz"))
        assertEquals("README (1)", unique("README", "readme"))
        assertEquals(".profile (1)", unique(".profile", ".profile"))
    }

    @Test fun longNamesStayWithinTheFileSystemLimit() {
        val stem = "猫".repeat(100) // 300 UTF-8 bytes
        val name = unique("$stem.txt", "$stem.txt")
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(name.endsWith(" (1).txt"))
        val free = unique("$stem.txt")
        assertTrue(free.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(free.endsWith(".txt"))
    }

    @Test fun folderChoiceSurvivesAndClears() {
        assertNull(DecryptFolderStore.read(context))
        val folder = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        assertTrue(DecryptFolderStore.write(context, folder))
        assertEquals(folder, DecryptFolderStore.read(context))
        assertTrue(DecryptFolderStore.write(context, null))
        assertNull(DecryptFolderStore.read(context))
    }

    @Test fun decryptedNamesFollowTheExistingRules() {
        assertEquals(
            "report.pdf",
            decryptedDocumentRequest("x.nkem", "file", "decrypted", "report.pdf").displayName,
        )
        assertEquals("notes.txt", decryptedDocumentRequest("notes.txt.nkem", "file", "decrypted").displayName)
        assertEquals("decrypted_blob.bin", decryptedDocumentRequest("blob.bin", "file", "decrypted").displayName)
    }
}
