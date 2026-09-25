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

private val HEX = "0123456789ABCDEF".toCharArray()

/** Upper-case hex, e.g. `[0x1A, 0x2B].toHex(" ") == "1A 2B"`. */
fun ByteArray.toHex(separator: String = ""): String {
    val sb = StringBuilder(size * 3)
    for (i in indices) {
        if (i > 0) sb.append(separator)
        val v = this[i].toInt() and 0xFF
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return sb.toString()
}
