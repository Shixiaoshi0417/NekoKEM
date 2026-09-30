package com.shixiaoshi0417.nekokem.files

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import com.shixiaoshi0417.nekokem.keys.LocalKeyManager
import com.shixiaoshi0417.nekokem.nativecore.NativeBridge
import com.shixiaoshi0417.nekokem.progress.CancellableProgressCallback
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Executes the production SAF transaction without loading or mocking Core/JNI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [HostPosixShadow::class])
class SafOutputTransactionTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var workflow: SafFileWorkflow
    private val uri = Uri.parse("content://transaction-test/document")
    private val original = "existing document".toByteArray()
    private var document = original.copyOf()
    private var inputCloses = 0
    private var outputOpens = 0
    private var outputCloses = 0
    private var failWrite = false
    private var failOpen = false
    private var failClose = false
    private var failRestore = false
    private var failInputRead = false
    private var failInputClose = false

    @Before fun setUp() {
        val cache = temporary.newFolder("cache")
        val files = temporary.newFolder("files")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getCacheDir(): File = cache
            override fun getFilesDir(): File = files
        }
        workflow = SafFileWorkflow(context, LocalKeyManager(context))
        val resolver = shadowOf(context.contentResolver)
        resolver.registerInputStreamSupplier(uri) {
            object : ByteArrayInputStream(document.copyOf()) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (failInputRead) throw IOException("backup read")
                    return super.read(bytes, offset, length)
                }
                override fun close() {
                    inputCloses++
                    super.close()
                    if (failInputClose) throw IOException("backup close")
                }
            }
        }
        resolver.registerOutputStreamSupplier(uri) {
            outputOpens++
            document = ByteArray(0) // Model "wt", including truncation before open fails.
            if (failOpen && outputOpens == 1) throw IOException("open after truncate")
            object : OutputStream() {
                override fun write(value: Int) {
                    if ((failWrite && outputOpens == 1 && document.isNotEmpty()) ||
                        (failRestore && outputOpens > 1)
                    ) throw IOException("write")
                    document += value.toByte()
                }
                override fun close() {
                    outputCloses++
                    if (failClose && outputOpens == 1) throw IOException("close")
                }
            }
        }
    }

    private fun progress(accept: (Long) -> Boolean = { true }) =
        object : CancellableProgressCallback {
            override fun isCancelled() = false
            override fun onProgress(processedBytes: Long, totalBytes: Long) =
                accept(processedBytes)
        }

    private fun commit(
        bytes: ByteArray = "authenticated staged plaintext".toByteArray(),
        callback: CancellableProgressCallback = progress(),
    ): Int {
        val stage = File(context.cacheDir, "stage")
        stage.writeBytes(bytes)
        return workflow.commitPreparedDecryption(PreparedDecryption(stage), uri, callback)
    }

    private fun backups(): List<File> =
        File(context.cacheDir, "nekokem-output-backups").listFiles()?.toList().orEmpty()

    @Test fun cancellationBeforeWriteNeverOpensDestination() {
        // A rollback write would fail, demonstrating that cancellation must not mutate it.
        failWrite = true
        assertNotEquals(NativeBridge.RESULT_SUCCESS, commit(callback = progress { false }))
        assertEquals("cancel before write must not open wt", 0, outputOpens)
        assertArrayEquals(original, document)
        assertEquals(1, inputCloses)
        assertTrue(backups().isEmpty())
    }

    @Test fun backupInitializationFailureClosesInput() {
        File(context.cacheDir, "nekokem-output-backups").writeText("not a directory")
        assertNotEquals(NativeBridge.RESULT_SUCCESS, commit())
        assertEquals("input must close even before temp file creation", 1, inputCloses)
        assertEquals(0, outputOpens)
        assertArrayEquals(original, document)
    }

    @Test fun partialWriteFailureRestoresOriginal() {
        failWrite = true
        assertNotEquals(NativeBridge.RESULT_SUCCESS, commit())
        assertArrayEquals(original, document)
        assertEquals(2, outputOpens)
        assertEquals(2, outputCloses)
        assertTrue(backups().isEmpty())
    }

    @Test fun openFailureAfterTruncationRestoresOriginal() {
        failOpen = true
        assertNotEquals(NativeBridge.RESULT_SUCCESS, commit())
        assertArrayEquals(original, document)
        assertEquals(2, outputOpens)
        assertTrue(backups().isEmpty())
    }

    @Test fun closeFailureRestoresOriginal() {
        failClose = true
        assertNotEquals(NativeBridge.RESULT_SUCCESS, commit())
        assertArrayEquals(original, document)
        assertEquals(2, outputCloses)
        assertTrue(backups().isEmpty())
    }

    @Test fun cancellationAfterWriteRestoresOriginal() {
        assertNotEquals(NativeBridge.RESULT_SUCCESS, commit(callback = progress { it == 0L }))
        assertArrayEquals(original, document)
        assertEquals(2, outputOpens)
        assertTrue(backups().isEmpty())
    }

    @Test fun failedRestorePreservesBackup() {
        failWrite = true
        failRestore = true
        assertNotEquals(NativeBridge.RESULT_SUCCESS, commit())
        assertEquals(1, backups().size)
        assertArrayEquals(original, backups().single().readBytes())
    }

    @Test fun successfulWriteCommitsAndCleansBackup() {
        val bytes = "new authenticated document".toByteArray()
        assertEquals(NativeBridge.RESULT_SUCCESS, commit(bytes))
        assertArrayEquals(bytes, document)
        assertEquals(1, outputOpens)
        assertEquals(1, outputCloses)
        assertTrue(backups().isEmpty())
    }

    @Test fun emptyPlaintextCommits() {
        assertEquals(NativeBridge.RESULT_SUCCESS, commit(ByteArray(0)))
        assertEquals(0, document.size)
        assertEquals(1, outputOpens)
        assertTrue(backups().isEmpty())
    }
    @Test fun backupReadFailureClosesInputAndNeverWrites() {
        failInputRead = true
        assertNotEquals(NativeBridge.RESULT_SUCCESS, commit())
        assertEquals(1, inputCloses)
        assertEquals(0, outputOpens)
        assertArrayEquals(original, document)
        assertTrue(backups().isEmpty())
    }

    @Test fun backupCloseFailureNeverWritesAndCleansBackup() {
        failInputClose = true
        assertNotEquals(NativeBridge.RESULT_SUCCESS, commit())
        assertEquals(1, inputCloses)
        assertEquals(0, outputOpens)
        assertArrayEquals(original, document)
        assertTrue(backups().isEmpty())
    }

    @Test fun progressExceptionBeforeWriteNeverOpensDestination() {
        assertNotEquals(NativeBridge.RESULT_SUCCESS, commit(callback = progress {
            throw IOException("progress callback")
        }))
        assertEquals(0, outputOpens)
        assertArrayEquals(original, document)
        assertTrue(backups().isEmpty())
    }

    @Test fun missingStageDoesNotTruncateDestinationOrLeakOutput() {
        assertNotEquals(NativeBridge.RESULT_SUCCESS, commit(callback = progress {
            check(File(context.cacheDir, "stage").delete())
            true
        }))
        assertEquals(0, outputOpens)
        assertEquals(0, outputCloses)
        assertArrayEquals(original, document)
        assertTrue(backups().isEmpty())
    }

    @Test fun alreadyCancelledDoesNotReadOrWriteDestination() {
        val cancelled = object : CancellableProgressCallback {
            override fun isCancelled() = true
            override fun onProgress(processedBytes: Long, totalBytes: Long) = false
        }
        assertEquals(NativeBridge.RESULT_CANCELLED, commit(callback = cancelled))
        assertEquals(0, inputCloses)
        assertEquals(0, outputOpens)
        assertArrayEquals(original, document)
        assertTrue(backups().isEmpty())
    }

}
