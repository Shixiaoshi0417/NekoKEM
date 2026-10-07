package com.shixiaoshi0417.nekokem.files

import com.shixiaoshi0417.nekokem.progress.OperationProgressTracker
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Models an Activity recreation while the old page's Core call still runs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [HostPosixShadow::class])
class PageCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun recreatedPageNeverCleansRunningWorkOfTheOldPage() {
        val cache = temporary.newFolder("cache")
        val old = PageCache.open(cache)
        val staged = File(old.directory, "staged-input").apply { writeBytes(INPUT) }
        val tracker = OperationProgressTracker {}
        old.track(tracker)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var seenByOldPage = ByteArray(0)
        val worker = Thread {
            old.run {
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
                // The synchronous call keeps reading after its page closed.
                seenByOldPage = staged.readBytes()
            }
        }
        worker.start()
        assertTrue(started.await(10, TimeUnit.SECONDS))

        old.close()
        val recreated = PageCache.open(cache)
        PageCache.awaitCleanup()
        assertTrue("closing must cancel the old page's operation", tracker.isCancelled())
        assertTrue("old page files must outlive its running call", staged.isFile)

        release.countDown()
        worker.join(10_000)
        assertArrayEquals(INPUT, seenByOldPage)
        assertFalse("the last returning call removes the closed page", old.directory.exists())
        assertTrue(recreated.directory.isDirectory)
        recreated.close()
        PageCache.awaitCleanup()
        assertFalse(recreated.directory.exists())
    }

    @Test fun openingRemovesOnlyFilesNoLivePageOwns() {
        val cache = temporary.newFolder("cache")
        val live = PageCache.open(cache)
        val kept = File(live.directory, "prepared-output").apply { writeBytes(INPUT) }
        val earlierProcess = File(live.directory.parentFile, "page-from-earlier-process").apply {
            check(mkdir())
            File(this, "plaintext").writeBytes(INPUT)
        }
        val legacy = File(cache, "nekokem-work").apply {
            check(mkdir())
            File(this, "nkem-ei-1.tmp").writeBytes(INPUT)
        }

        val second = PageCache.open(cache)
        PageCache.awaitCleanup()
        assertFalse(earlierProcess.exists())
        assertFalse(legacy.exists())
        assertTrue("another open page keeps its files", kept.isFile)
        assertArrayEquals(INPUT, kept.readBytes())

        live.close()
        second.close()
        PageCache.awaitCleanup()
        assertEquals(0, live.directory.parentFile?.listFiles()?.size ?: 0)
    }

    @Test fun closedPageStartsNoNewWork() {
        val page = PageCache.open(temporary.newFolder("cache"))
        page.close()
        PageCache.awaitCleanup()
        var ran = false
        val error = runCatching { page.run { ran = true } }.exceptionOrNull()
        assertTrue(error is PageClosedException)
        assertFalse(ran)
        val late = OperationProgressTracker {}
        page.track(late)
        assertTrue("a tracker registered after close is cancelled", late.isCancelled())
    }

    private companion object {
        val INPUT = "plaintext that must reach Core intact".toByteArray()
    }
}
