package com.shixiaoshi0417.nekokem.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationProgressTrackerTest {
    private class Clock {
        var millis = 0L
        fun nanoTime() = millis * 1_000_000L
    }

    @Test fun frequentCallbacksKeepLiveSnapshotsWithoutPublishingEveryChunk() {
        val clock = Clock()
        val updates = mutableListOf<OperationProgressSnapshot>()
        val tracker = OperationProgressTracker(clock::nanoTime, updates::add)

        assertTrue(tracker.onProgress(0L, 10_000L))
        for (millis in 1L..149L) {
            clock.millis = millis
            assertTrue(tracker.onProgress(millis, 10_000L))
        }

        assertEquals(1, updates.size)
        val snapshot = tracker.snapshot()
        assertEquals(149L, snapshot.processedBytes)
        assertEquals(10_000L, snapshot.totalBytes)
        assertEquals(149L, snapshot.elapsedMillis)
        assertEquals(1_000.0, snapshot.bytesPerSecond, 0.001)
        assertEquals(9_851L, snapshot.etaMillis)

        clock.millis = 150L
        assertTrue(tracker.onProgress(150L, 10_000L))
        assertEquals(2, updates.size)
        assertEquals(tracker.snapshot(), updates.last())
    }

    @Test fun cancellationIsCheckedEvenWhenNoUpdateIsDue() {
        val clock = Clock()
        val updates = mutableListOf<OperationProgressSnapshot>()
        val tracker = OperationProgressTracker(clock::nanoTime, updates::add)
        assertTrue(tracker.onProgress(0L, 10_000L))
        clock.millis = 1L
        assertTrue(tracker.onProgress(1L, 10_000L))

        tracker.cancel()
        assertTrue(tracker.isCancelled())
        assertFalse(tracker.onProgress(2L, 10_000L))
        assertEquals(1, updates.size)
        assertEquals(1L, tracker.snapshot().processedBytes)
    }

    @Test fun cancellationDuringAThrottledCallbackIsObservedBeforeReturning() {
        val clock = Clock()
        var cancelDuringClockRead = false
        lateinit var tracker: OperationProgressTracker
        tracker = OperationProgressTracker(
            clock = {
                if (cancelDuringClockRead) tracker.cancel()
                clock.nanoTime()
            },
            publish = {},
        )
        assertTrue(tracker.onProgress(0L, 10_000L))
        clock.millis = 1L
        cancelDuringClockRead = true
        assertFalse(tracker.onProgress(1L, 10_000L))
    }

    @Test fun cancellationByThePublisherIsObservedBeforeReturning() {
        val clock = Clock()
        lateinit var tracker: OperationProgressTracker
        tracker = OperationProgressTracker(clock::nanoTime) { tracker.cancel() }
        assertFalse(tracker.onProgress(0L, 10_000L))
    }

    @Test fun backwardProgressResetsSpeedEvenBetweenPublicationIntervals() {
        val clock = Clock()
        val updates = mutableListOf<OperationProgressSnapshot>()
        val tracker = OperationProgressTracker(clock::nanoTime, updates::add)
        tracker.onProgress(0L, 10_000L)
        clock.millis = 100L
        tracker.onProgress(1_000L, 10_000L)
        assertEquals(1, updates.size)

        // This is ahead of the last published count, but behind the latest callback.
        clock.millis = 110L
        tracker.onProgress(100L, 10_000L)
        assertEquals(2, updates.size)
        assertEquals(100L, updates.last().processedBytes)
        assertEquals(0.0, updates.last().bytesPerSecond, 0.0)
        assertNull(updates.last().etaMillis)

        clock.millis = 120L
        tracker.onProgress(200L, 10_000L)
        assertEquals(10_000.0, tracker.snapshot().bytesPerSecond, 0.001)
    }

    @Test fun aNewTotalStartsANewPhaseImmediately() {
        val clock = Clock()
        val updates = mutableListOf<OperationProgressSnapshot>()
        val tracker = OperationProgressTracker(clock::nanoTime, updates::add)
        tracker.onProgress(0L, 10_000L)
        clock.millis = 1L
        tracker.onProgress(100L, 20_000L)

        assertEquals(2, updates.size)
        assertEquals(20_000L, updates.last().totalBytes)
        assertEquals(0.0, updates.last().bytesPerSecond, 0.0)
    }

    @Test fun terminalProgressPublishesWithoutWaitingForTheInterval() {
        val clock = Clock()
        val updates = mutableListOf<OperationProgressSnapshot>()
        val tracker = OperationProgressTracker(clock::nanoTime, updates::add)
        tracker.onProgress(0L, 100L)
        clock.millis = 1L
        assertTrue(tracker.onProgress(100L, 100L))

        assertEquals(2, updates.size)
        assertEquals(1f, updates.last().fraction, 0f)
        assertNull(updates.last().etaMillis)
    }

    @Test fun speedDiscardsThePreviousPhaseAndOldSamples() {
        val clock = Clock()
        val tracker = OperationProgressTracker(clock::nanoTime) {}
        tracker.onProgress(0L, 100_000L)
        for (step in 1L..40L) {
            clock.millis = step * 150L
            val bytes = if (step <= 20L) step * 150L else
                3_000L + (step - 20L) * 300L
            tracker.onProgress(bytes, 100_000L)
        }
        assertEquals(2_000.0, tracker.snapshot().bytesPerSecond, 0.001)
    }

    @Test fun emptyAndNegativeCountsRemainBounded() {
        val clock = Clock()
        val updates = mutableListOf<OperationProgressSnapshot>()
        val tracker = OperationProgressTracker(clock::nanoTime, updates::add)
        assertEquals(OperationProgressSnapshot(), tracker.snapshot())
        assertTrue(tracker.onProgress(-1L, -1L))
        assertEquals(1, updates.size)
        assertEquals(OperationProgressSnapshot(), updates.last())
        clock.millis = 10L
        assertTrue(tracker.onProgress(0L, 0L))
        assertEquals(1, updates.size)
        assertEquals(10L, tracker.snapshot().elapsedMillis)
    }
}
