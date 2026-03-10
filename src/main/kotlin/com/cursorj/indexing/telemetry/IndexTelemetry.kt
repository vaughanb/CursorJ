package com.cursorj.indexing.telemetry

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class IndexTelemetry {
    data class Snapshot(
        val queryCount: Long,
        val totalQueryLatencyMs: Long,
        val queryByEngine: Map<String, Long>,
        val fallbackByReason: Map<String, Long>,
        val reindexByReason: Map<String, Long>,
        val cacheHitCount: Long,
        val cacheMissCount: Long,
        val startupDurationsMs: List<Long>,
        val lastQueueDepth: Long,
        val readyWithoutManualRebuildCount: Long,
        val sessionCount: Long,
    ) {
        val averageLatencyMs: Double
            get() = if (queryCount == 0L) 0.0 else totalQueryLatencyMs.toDouble() / queryCount.toDouble()
        val cacheHitRatio: Double
            get() {
                val total = cacheHitCount + cacheMissCount
                if (total == 0L) return 0.0
                return cacheHitCount.toDouble() / total.toDouble()
            }
    }

    private val queryCount = AtomicLong(0)
    private val totalQueryLatencyMs = AtomicLong(0)
    private val queryByEngine = ConcurrentHashMap<String, AtomicLong>()
    private val fallbackByReason = ConcurrentHashMap<String, AtomicLong>()
    private val reindexByReason = ConcurrentHashMap<String, AtomicLong>()
    private val cacheHitCount = AtomicLong(0)
    private val cacheMissCount = AtomicLong(0)
    private val startupDurationsMs = mutableListOf<Long>()
    private val lastQueueDepth = AtomicLong(0)
    private val readyWithoutManualRebuildCount = AtomicLong(0)
    private val sessionCount = AtomicLong(0)

    fun recordQuery(engine: String, latencyMs: Long) {
        queryCount.incrementAndGet()
        totalQueryLatencyMs.addAndGet(latencyMs.coerceAtLeast(0))
        queryByEngine.computeIfAbsent(engine) { AtomicLong(0) }.incrementAndGet()
    }

    fun recordFallback(reason: String) {
        fallbackByReason.computeIfAbsent(reason) { AtomicLong(0) }.incrementAndGet()
    }

    fun recordReindex(reason: String) {
        reindexByReason.computeIfAbsent(reason) { AtomicLong(0) }.incrementAndGet()
    }

    fun recordCacheHit() {
        cacheHitCount.incrementAndGet()
    }

    fun recordCacheMiss() {
        cacheMissCount.incrementAndGet()
    }

    fun recordQueueDepth(depth: Long) {
        lastQueueDepth.set(depth.coerceAtLeast(0))
    }

    @Synchronized
    fun recordStartupIndexingDuration(durationMs: Long) {
        startupDurationsMs.add(durationMs.coerceAtLeast(0))
    }

    fun beginSession() {
        sessionCount.incrementAndGet()
    }

    fun recordReadyWithoutManualRebuild() {
        readyWithoutManualRebuildCount.incrementAndGet()
    }

    fun snapshot(): Snapshot {
        return Snapshot(
            queryCount = queryCount.get(),
            totalQueryLatencyMs = totalQueryLatencyMs.get(),
            queryByEngine = queryByEngine.mapValues { it.value.get() },
            fallbackByReason = fallbackByReason.mapValues { it.value.get() },
            reindexByReason = reindexByReason.mapValues { it.value.get() },
            cacheHitCount = cacheHitCount.get(),
            cacheMissCount = cacheMissCount.get(),
            startupDurationsMs = synchronized(this) { startupDurationsMs.toList() },
            lastQueueDepth = lastQueueDepth.get(),
            readyWithoutManualRebuildCount = readyWithoutManualRebuildCount.get(),
            sessionCount = sessionCount.get(),
        )
    }
}
