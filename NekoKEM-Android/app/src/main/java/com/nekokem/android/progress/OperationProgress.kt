package com.nekokem.android.progress

import com.nekokem.android.nativecore.NativeProgressCallback
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

data class OperationProgressSnapshot(
    val processedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Double = 0.0,
    val elapsedMillis: Long = 0L,
    val etaMillis: Long? = null,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) {
            0f
        } else {
            (processedBytes.toDouble() / totalBytes.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        }
}

interface CancellableProgressCallback : NativeProgressCallback {
    fun isCancelled(): Boolean
}

/**
 * Receives synchronous Core callbacks on a worker thread. Publishing is
 * throttled to 150 ms and speed is averaged over the most recent two seconds.
 */
class OperationProgressTracker(
    private val publish: (OperationProgressSnapshot) -> Unit,
) : CancellableProgressCallback {
    private data class Sample(val timeNanos: Long, val bytes: Long)

    private val cancelled = AtomicBoolean(false)
    private val lock = Any()
    private val samples = ArrayDeque<Sample>()
    private val startNanos = System.nanoTime()
    private var lastPublishNanos = 0L
    private var latest = OperationProgressSnapshot()

    override fun isCancelled(): Boolean = cancelled.get()

    fun cancel() {
        cancelled.set(true)
    }

    override fun onProgress(processedBytes: Long, totalBytes: Long): Boolean {
        if (cancelled.get()) {
            return false
        }

        val now = System.nanoTime()
        var update: OperationProgressSnapshot? = null
        synchronized(lock) {
            val processed = max(0L, processedBytes)
            val total = max(0L, totalBytes)
            if (samples.isNotEmpty() && processed < samples.last().bytes) {
                samples.clear()
            }
            samples.addLast(Sample(now, processed))
            while (samples.size > 2 &&
                now - samples.first().timeNanos > SPEED_WINDOW_NANOS
            ) {
                samples.removeFirst()
            }

            val speed = movingSpeed()
            val elapsedMillis = nanosToMillis(now - startNanos)
            val etaMillis = if (speed > 0.0 && total > processed) {
                (((total - processed).toDouble() / speed) * 1000.0)
                    .toLong()
                    .coerceAtLeast(0L)
            } else {
                null
            }
            latest = OperationProgressSnapshot(
                processedBytes = processed,
                totalBytes = total,
                bytesPerSecond = speed,
                elapsedMillis = elapsedMillis,
                etaMillis = etaMillis,
            )

            val terminal = total > 0L && processed >= total
            if (lastPublishNanos == 0L || terminal ||
                now - lastPublishNanos >= UPDATE_INTERVAL_NANOS
            ) {
                lastPublishNanos = now
                update = latest
            }
        }
        update?.let(publish)
        return !cancelled.get()
    }

    fun snapshot(): OperationProgressSnapshot = synchronized(lock) {
        val now = System.nanoTime()
        latest.copy(elapsedMillis = nanosToMillis(now - startNanos))
    }

    private fun movingSpeed(): Double {
        if (samples.size < 2) {
            return 0.0
        }
        val first = samples.first()
        val last = samples.last()
        val duration = last.timeNanos - first.timeNanos
        val bytes = last.bytes - first.bytes
        return if (duration <= 0L || bytes <= 0L) {
            0.0
        } else {
            bytes.toDouble() * NANOS_PER_SECOND.toDouble() / duration.toDouble()
        }
    }

    private fun nanosToMillis(nanos: Long): Long =
        (nanos / NANOS_PER_MILLISECOND).coerceAtLeast(0L)

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val UPDATE_INTERVAL_NANOS = 150L * NANOS_PER_MILLISECOND
        const val SPEED_WINDOW_NANOS = 2L * NANOS_PER_SECOND
    }
}
