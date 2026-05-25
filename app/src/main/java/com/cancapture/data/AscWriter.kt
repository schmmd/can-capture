package com.cancapture.data

import java.io.BufferedWriter
import java.io.Closeable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes Vector ASCII (.asc) format. Single channel (1). Timestamps are relative
 * to the first frame received (Start of measurement = 0.000000).
 */
class AscWriter(
    private val writer: BufferedWriter,
    startInstant: Instant
) : Closeable {

    private val dateString: String = DateTimeFormatter
        .ofPattern("EEE MMM dd hh:mm:ss.SSS a yyyy", Locale.US)
        .withZone(ZoneId.systemDefault())
        .format(startInstant)

    private var firstFrameTs: Double? = null
    private var closed = false

    fun writeHeader() {
        writer.write("date $dateString\n")
        writer.write("base hex  timestamps absolute\n")
        writer.write("internal events logged\n")
        writer.write("// version 8.0.0\n")
        writer.write("Begin Triggerblock $dateString\n")
        writer.write("   0.000000 Start of measurement\n")
        writer.flush()
    }

    fun writeFrame(frame: CanFrame) {
        val t0 = firstFrameTs ?: frame.timestamp.also { firstFrameTs = it }
        val relTime = (frame.timestamp - t0).coerceAtLeast(0.0)

        val idStr = buildString {
            append(java.lang.Long.toHexString(frame.id).uppercase(Locale.US))
            if (frame.extended) append('x')
        }
        val dlc = frame.data.size
        val dataStr = if (dlc == 0) {
            ""
        } else {
            buildString(dlc * 3) {
                for ((i, b) in frame.data.withIndex()) {
                    if (i > 0) append(' ')
                    val v = b.toInt() and 0xFF
                    append(HEX[v ushr 4])
                    append(HEX[v and 0x0F])
                }
            }
        }

        val kind = if (frame.rtr) 'r' else 'd'
        val line = "%11.6f 1  %-15s Rx   %c %d %s\n".format(
            Locale.US,
            relTime,
            idStr,
            kind,
            dlc,
            dataStr
        )
        writer.write(line)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            writer.write("End TriggerBlock\n")
            writer.flush()
        } finally {
            writer.close()
        }
    }

    private companion object {
        val HEX = "0123456789ABCDEF".toCharArray()
    }
}
