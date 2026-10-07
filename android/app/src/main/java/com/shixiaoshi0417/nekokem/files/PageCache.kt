package com.shixiaoshi0417.nekokem.files

import android.system.Os
import android.system.OsConstants
import com.shixiaoshi0417.nekokem.progress.OperationProgressTracker
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Work offered to a closed page; coroutines treat it as cancellation. */
class PageClosedException : CancellationException("Page closed")

/**
 * Private cache directory of one page (Activity instance).
 *
 * Recreating the Activity (rotation, language change) keeps the process, and a
 * synchronous Core call started by the old page keeps running after its
 * coroutine is cancelled. Pages therefore never share cache files, and no page
 * cleans another page's directory. Closing a page cancels its tracked
 * operations; the directory is removed only after the page's last running
 * block has returned. Opening a page removes what an earlier process or an
 * older App version left behind.
 */
class PageCache private constructor(val directory: File) {
    private val trackers = LinkedHashSet<OperationProgressTracker>()
    private val removalStarted = AtomicBoolean(false)
    private var running = 0
    private var closed = false

    /**
     * Runs blocking page work; the directory outlives every running block. A
     * closed page starts no new work, since its directory may already be gone.
     */
    fun <T> run(block: () -> T): T {
        synchronized(lock) {
            if (closed) throw PageClosedException()
            running += 1
        }
        try {
            return block()
        } finally {
            val last = synchronized(lock) {
                running -= 1
                closed && running == 0
            }
            if (last) remove()
        }
    }

    /** A page closed before [tracker] started cancels it at once. */
    fun track(tracker: OperationProgressTracker) {
        val cancelNow = synchronized(lock) {
            if (!closed) trackers.add(tracker)
            closed
        }
        if (cancelNow) tracker.cancel()
    }

    fun untrack(tracker: OperationProgressTracker) {
        synchronized(lock) { trackers.remove(tracker) }
    }

    /** Cancels this page's operations; the directory goes once they return. */
    fun close() {
        val cancelled: List<OperationProgressTracker>
        val idle: Boolean
        synchronized(lock) {
            if (closed) return
            closed = true
            cancelled = trackers.toList()
            trackers.clear()
            idle = running == 0
        }
        cancelled.forEach { it.cancel() }
        if (idle) cleaner.execute { remove() }
    }

    private fun remove() {
        if (!removalStarted.compareAndSet(false, true)) return
        try {
            clearTree(directory)
        } finally {
            synchronized(lock) { pages.remove(directory) }
        }
    }

    companion object {
        private const val ROOT_NAME = "nekokem-pages"
        private const val PAGE_PREFIX = "page-"
        private const val UNAVAILABLE_NAME = "unavailable"
        private const val PERMISSION_MASK = 0x1FF
        private const val PRIVATE_DIRECTORY_MODE = 0x1C0
        private const val CLEAR_BUFFER_SIZE = 64 * 1024

        // Caches shared by every page before pages had their own directories.
        private val LEGACY_ROOTS = listOf(
            "nekokem-work",
            "nekokem-key-selection",
            "nekokem-contact-work",
        )

        private val lock = Any()

        // Directories of pages that are open or still waiting for removal.
        private val pages = HashSet<File>()

        private val cleaner = Executors.newSingleThreadExecutor { task ->
            Thread(task, "nekokem-page-cache").apply { isDaemon = true }
        }

        /**
         * Never fails: if no private directory can be prepared, the page gets
         * a path that does not exist, and its operations report a storage
         * error instead of the App failing to start.
         */
        fun open(cacheDir: File): PageCache {
            val root = File(cacheDir, ROOT_NAME)
            var stale: List<File> = emptyList()
            val page = synchronized(lock) {
                val directory = createPageDirectory(root)
                if (directory == null) {
                    PageCache(File(root, UNAVAILABLE_NAME))
                } else {
                    pages.add(directory)
                    // Pages are registered under the lock before they create
                    // files, so a directory missing from the set is no live page's.
                    stale = root.listFiles().orEmpty().filter { it !in pages }
                    PageCache(directory)
                }
            }
            val legacy = LEGACY_ROOTS.map { File(cacheDir, it) }
            cleaner.execute { (stale + legacy).forEach(::clearTree) }
            return page
        }

        private fun createPageDirectory(root: File): File? = try {
            if (!preparePrivateDirectory(root)) {
                null
            } else {
                val directory = java.nio.file.Files.createTempDirectory(
                    root.toPath(),
                    PAGE_PREFIX,
                ).toFile()
                if (preparePrivateDirectory(directory)) {
                    directory
                } else {
                    clearTree(directory)
                    null
                }
            }
        } catch (_: Exception) {
            null
        }

        /** Waits until removals queued so far have finished. */
        internal fun awaitCleanup() {
            cleaner.submit {}.get()
        }

        private fun preparePrivateDirectory(directory: File): Boolean {
            if (!directory.exists() && !directory.mkdir()) return false
            val initial = Os.lstat(directory.absolutePath)
            if (!OsConstants.S_ISDIR(initial.st_mode) || initial.st_uid != Os.getuid()) {
                return false
            }
            Os.chmod(directory.absolutePath, PRIVATE_DIRECTORY_MODE)
            val status = Os.lstat(directory.absolutePath)
            return OsConstants.S_ISDIR(status.st_mode) &&
                status.st_uid == Os.getuid() &&
                (status.st_mode and PERMISSION_MASK) == PRIVATE_DIRECTORY_MODE
        }

        /** Zeroes regular files, unlinks links without following them. */
        internal fun clearTree(file: File) {
            val status = try {
                Os.lstat(file.absolutePath)
            } catch (_: Exception) {
                return
            }
            if (OsConstants.S_ISDIR(status.st_mode) && status.st_uid == Os.getuid()) {
                file.listFiles().orEmpty().forEach(::clearTree)
            } else if (OsConstants.S_ISREG(status.st_mode)) {
                overwrite(file)
            }
            file.delete()
        }

        private fun overwrite(file: File) {
            try {
                RandomAccessFile(file, "rw").use { stream ->
                    val zeros = ByteArray(CLEAR_BUFFER_SIZE)
                    var remaining = stream.length()
                    stream.seek(0L)
                    while (remaining > 0L) {
                        val count = minOf(remaining, zeros.size.toLong()).toInt()
                        stream.write(zeros, 0, count)
                        remaining -= count.toLong()
                    }
                    stream.fd.sync()
                }
            } catch (_: Exception) {
                // Deletion is still attempted; no path or contents are logged.
            }
        }
    }
}
