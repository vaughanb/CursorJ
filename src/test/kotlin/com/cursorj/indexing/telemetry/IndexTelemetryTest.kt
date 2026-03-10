package com.cursorj.indexing.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndexTelemetryTest {
    @Test
    fun `snapshot defaults are safe when no events recorded`() {
        val telemetry = IndexTelemetry()
        val snapshot = telemetry.snapshot()
        assertEquals(0, snapshot.queryCount)
        assertEquals(0.0, snapshot.averageLatencyMs)
        assertEquals(0.0, snapshot.cacheHitRatio)
        assertEquals(0, snapshot.lastQueueDepth)
        assertTrue(snapshot.startupDurationsMs.isEmpty())
    }

    @Test
    fun `snapshot reports cache and startup metrics`() {
        val telemetry = IndexTelemetry()
        telemetry.beginSession()
        telemetry.recordQuery("lexical", 10)
        telemetry.recordQuery("hybrid", 20)
        telemetry.recordCacheHit()
        telemetry.recordCacheMiss()
        telemetry.recordCacheHit()
        telemetry.recordStartupIndexingDuration(4200)
        telemetry.recordQueueDepth(7)
        telemetry.recordReadyWithoutManualRebuild()

        val snapshot = telemetry.snapshot()
        assertEquals(2, snapshot.queryCount)
        assertEquals(30, snapshot.totalQueryLatencyMs)
        assertEquals(2, snapshot.cacheHitCount)
        assertEquals(1, snapshot.cacheMissCount)
        assertTrue(snapshot.cacheHitRatio > 0.6)
        assertEquals(7, snapshot.lastQueueDepth)
        assertEquals(1, snapshot.readyWithoutManualRebuildCount)
        assertEquals(1, snapshot.sessionCount)
        assertTrue(snapshot.startupDurationsMs.first() >= 4200)
    }

    @Test
    fun `startup durations preserve insertion order`() {
        val telemetry = IndexTelemetry()
        telemetry.recordStartupIndexingDuration(100)
        telemetry.recordStartupIndexingDuration(250)
        telemetry.recordStartupIndexingDuration(50)
        val snapshot = telemetry.snapshot()
        assertEquals(listOf(100L, 250L, 50L), snapshot.startupDurationsMs)
    }

    @Test
    fun `queue depth clamps to non negative`() {
        val telemetry = IndexTelemetry()
        telemetry.recordQueueDepth(-5)
        val snapshot = telemetry.snapshot()
        assertEquals(0, snapshot.lastQueueDepth)
    }
}
