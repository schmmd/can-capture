package com.cancapture.data

import java.io.File
import java.time.Instant

data class CanFrame(
    val id: Long,
    val extended: Boolean,
    val rtr: Boolean,
    val timestamp: Double,
    val data: ByteArray,
    val channel: Int = 1
)

data class Capture(
    val file: File,
    val displayName: String,
    val createdAt: Instant,
    val durationMs: Long,
    val frameCount: Int,
    val sizeBytes: Long
)
