package com.cancapture.data

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException

class IsoTpException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Classical CAN ISO 15765-2 (ISO-TP) request/response over a single
 * [SocketcandSession]. Supports only single-frame TX (sufficient for UDS
 * service requests of up to 7 bytes, which covers ReadDataByIdentifier,
 * DiagnosticSessionControl, TesterPresent, etc.). RX handles SF and
 * multi-frame (FF + flow control + CFs) with reassembly.
 */
class IsoTp(
    private val session: SocketcandSession,
    private val txId: Long,
    private val rxId: Long,
    private val extended: Boolean = false,
    private val blockSize: Int = 0,
    private val stMinMs: Int = 0,
    private val paddingByte: Int = 0x00,
    private val timeoutMs: Long = 1000,
) {
    private val rxChannel = Channel<CanFrame>(capacity = 32)
    private val requestMutex = Mutex()

    /** Called by the upstream RX collector for every frame. */
    fun onFrame(frame: CanFrame): Boolean {
        if (frame.id != rxId) return false
        rxChannel.trySend(frame)
        return true
    }

    /**
     * Send a UDS-style request (raw bytes — caller has already prefixed the
     * service ID) and wait for the reassembled response payload. Throws
     * [IsoTpException] on timeout, malformed frames, or flow control overflow.
     */
    suspend fun request(payload: ByteArray): ByteArray = requestMutex.withLock {
        require(payload.isNotEmpty()) { "Empty ISO-TP payload" }
        require(payload.size <= 7) {
            "Multi-frame TX not supported; payload was ${payload.size} bytes"
        }
        // Drain any stragglers from a previous request.
        while (rxChannel.tryReceive().isSuccess) {}

        sendSingleFrame(payload)
        return try {
            withTimeout(timeoutMs) { collectResponse() }
        } catch (e: TimeoutCancellationException) {
            throw IsoTpException("Timed out waiting for response to ${payload.toHex()}", e)
        }
    }

    private suspend fun sendSingleFrame(payload: ByteArray) {
        val pad = (paddingByte and 0xFF).toByte()
        val frame = ByteArray(8) { pad }
        frame[0] = payload.size.toByte()
        for (i in payload.indices) frame[i + 1] = payload[i]
        session.send(txId, frame, extended)
    }

    private suspend fun sendFlowControl() {
        val pad = (paddingByte and 0xFF).toByte()
        val frame = ByteArray(8) { pad }
        frame[0] = 0x30
        frame[1] = (blockSize and 0xFF).toByte()
        frame[2] = (stMinMs and 0xFF).toByte()
        session.send(txId, frame, extended)
    }

    private suspend fun collectResponse(): ByteArray {
        val buffer = ArrayList<Byte>(64)
        var expected = -1
        var nextSeq = 1
        while (true) {
            val frame = rxChannel.receive()
            val d = frame.data
            if (d.isEmpty()) continue
            val pci = (d[0].toInt() and 0xF0) shr 4
            when (pci) {
                0x0 -> {
                    val len = d[0].toInt() and 0x0F
                    if (len <= 0 || len > 7 || len > d.size - 1) {
                        throw IsoTpException("Malformed single frame, len=$len")
                    }
                    return d.copyOfRange(1, 1 + len)
                }
                0x1 -> {
                    if (d.size < 8) throw IsoTpException("First frame must be 8 bytes")
                    expected = ((d[0].toInt() and 0x0F) shl 8) or (d[1].toInt() and 0xFF)
                    if (expected < 8) throw IsoTpException("First frame length < 8: $expected")
                    for (i in 2 until 8) buffer.add(d[i])
                    nextSeq = 1
                    sendFlowControl()
                }
                0x2 -> {
                    if (expected < 0) {
                        // Stray consecutive frame; ignore.
                        continue
                    }
                    val seq = d[0].toInt() and 0x0F
                    if (seq != nextSeq) {
                        throw IsoTpException("CF sequence mismatch: got $seq, expected $nextSeq")
                    }
                    val remaining = expected - buffer.size
                    val take = minOf(7, remaining, d.size - 1)
                    for (i in 0 until take) buffer.add(d[1 + i])
                    nextSeq = (nextSeq + 1) and 0x0F
                    if (buffer.size >= expected) {
                        return ByteArray(expected) { buffer[it] }
                    }
                }
                0x3 -> {
                    // Flow control from peer. We only TX single frames, so we
                    // shouldn't receive FC; ignore if it appears.
                }
                else -> {
                    // Unknown PCI; ignore.
                }
            }
        }
    }
}

private fun ByteArray.toHex(): String =
    joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
