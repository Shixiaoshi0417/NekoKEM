package com.shixiaoshi0417.nekokem.progress

import com.shixiaoshi0417.nekokem.nativecore.NativeProgressCallback
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

// A primitive return type avoids boxing a timestamp on every Core callback.
internal fun interface ProgressClock {
    fun nanoTime(): Long
}

/**
 * Receives synchronous Core callbacks on a worker thread. Every callback checks
 * cancellation and records byte counts. Sampling and publishing are throttled
 * to 150 ms; speed is averaged over the most recent two seconds.
 */
class OperationProgressTracker internal constructor(
    private val clock: ProgressClock,
    private val publish: (OperationProgressSnapshot) -> Unit,
) : CancellableProgressCallback {
    constructor(publish: (OperationProgressSnapshot) -> Unit) :
        this(System::nanoTime, publish)

    private data class Sample(val timeNanos: Long, val bytes: Long)

    private val cancelled = AtomicBoolean(false)
    private val lock = Any()
    private val samples = ArrayDeque<Sample>()
    private val startNanos = clock.nanoTime()
    private var hasProgress = false
    private var lastPublishNanos = 0L
    private var latestProgressNanos = startNanos
    private var latestProcessedBytes = 0L
    private var latestTotalBytes = 0L

    override fun isCancelled(): Boolean = cancelled.get()

    fun cancel() {
        cancelled.set(true)
    }

    override fun onProgress(processedBytes: Long, totalBytes: Long): Boolean {
        if (cancelled.get()) {
            return false
        }

        val now = clock.nanoTime()
        var update: OperationProgressSnapshot? = null
        synchronized(lock) {
            val processed = max(0L, processedBytes)
            val total = max(0L, totalBytes)
            val first = !hasProgress
            val phaseChanged = hasProgress &&
                (processed < latestProcessedBytes || total != latestTotalBytes)
            if (phaseChanged) {
                samples.clear()
            }
            hasProgress = true
            latestProgressNanos = now
            latestProcessedBytes = processed
            latestTotalBytes = total

            val terminal = total > 0L && processed >= total
            if (first || phaseChanged || terminal ||
                now - lastPublishNanos >= UPDATE_INTERVAL_NANOS
            ) {
                samples.addLast(Sample(now, processed))
                while (samples.size > 2 &&
                    now - samples.first().timeNanos > SPEED_WINDOW_NANOS
                ) {
                    samples.removeFirst()
                }
                lastPublishNanos = now
                update = createSnapshot(now)
            }
        }
        update?.let(publish)
        return !cancelled.get()
    }

    fun snapshot(): OperationProgressSnapshot = synchronized(lock) {
        createSnapshot(clock.nanoTime())
    }

    private fun createSnapshot(now: Long): OperationProgressSnapshot {
        val speed = movingSpeed()
        val etaMillis = if (speed > 0.0 && latestTotalBytes > latestProcessedBytes) {
            (((latestTotalBytes - latestProcessedBytes).toDouble() / speed) * 1000.0)
                .toLong()
                .coerceAtLeast(0L)
        } else {
            null
        }
        return OperationProgressSnapshot(
            processedBytes = latestProcessedBytes,
            totalBytes = latestTotalBytes,
            bytesPerSecond = speed,
            elapsedMillis = nanosToMillis(now - startNanos),
            etaMillis = etaMillis,
        )
    }

    private fun movingSpeed(): Double {
        val first = samples.firstOrNull() ?: return 0.0
        val duration = latestProgressNanos - first.timeNanos
        val bytes = latestProcessedBytes - first.bytes
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
