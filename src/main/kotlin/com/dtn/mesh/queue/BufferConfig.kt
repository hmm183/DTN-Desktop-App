package com.dtn.mesh.queue

/**
 * Configuration for the DTN message buffer / queue manager on Desktop.
 */
data class BufferConfig(
    val maxBufferBytes: Long = 10_485_760L, // 10 MB on desktop
    val pressureThreshold: Double = 0.85,
    val forwardingTimeoutMs: Long = 30_000L,
    val retentionPeriodMs: Long = 86_400_000L, // 24 hours
    val defaultTtlMs: Long = 4 * 3_600_000L, // 4 hours
)
