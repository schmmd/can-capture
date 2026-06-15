package com.cancapture.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.io.IOException

class UdsException(
    message: String,
    val nrc: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Minimal UDS (ISO 14229) client layered on [IsoTp]. Handles positive/negative
 * response framing and the ResponseStillPending (NRC 0x78) retry loop.
 */
class UdsClient(private val isoTp: IsoTp) {
    suspend fun request(service: Int, payload: ByteArray): ByteArray {
        val req = ByteArray(1 + payload.size).also {
            it[0] = (service and 0xFF).toByte()
            payload.copyInto(it, 1)
        }
        val positive = ((service or 0x40) and 0xFF).toByte()
        while (true) {
            val resp = isoTp.request(req)
            if (resp.isEmpty()) throw UdsException("Empty UDS response")
            if (resp[0] == 0x7F.toByte()) {
                if (resp.size < 3) throw UdsException("Malformed negative response")
                val nrc = resp[2].toInt() and 0xFF
                if (nrc == 0x78) continue  // response pending, keep waiting
                throw UdsException(
                    "NRC 0x${"%02X".format(nrc)} for service 0x${"%02X".format(service)}",
                    nrc = nrc,
                )
            }
            if (resp[0] != positive) {
                throw UdsException(
                    "Unexpected response service 0x${"%02X".format(resp[0].toInt() and 0xFF)}"
                )
            }
            return resp.copyOfRange(1, resp.size)
        }
    }
}

/** Aggregate stats from the poll loop, surfaced to the UI. */
data class UdsPollStats(
    val polls: Int = 0,
    val errors: Int = 0,
    val lastError: String? = null,
)

/**
 * Walks a fixed list of UDS requests at a configured period. UDS-level NRCs
 * count as completed polls (the ECU answered, that's what we wanted to log);
 * ISO-TP / transport errors increment the error counter and trigger a short
 * backoff before the next sweep, mirroring `capture_dual_can.py`.
 */
class UdsPoller(
    private val uds: UdsClient,
    private val entries: List<UdsRequest>,
    private val periodMs: Int,
) {
    suspend fun run(onStats: (UdsPollStats) -> Unit) {
        var polls = 0
        var errors = 0
        var lastError: String? = null
        val period = periodMs.toLong().coerceAtLeast(1L)
        var nextT = System.currentTimeMillis()
        while (true) {
            currentCoroutineContext().ensureActive()
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                try {
                    uds.request(entry.service, entry.payload)
                    polls++
                } catch (e: UdsException) {
                    // ECU answered (with an NRC) — that still counts.
                    polls++
                } catch (e: IsoTpException) {
                    // Transport timeout — typically a flow-control frame that
                    // reached the ECU after its N_Bs deadline, so it abandoned
                    // the segmented response and the consecutive frames never
                    // came. Skip just this DID and keep polling the rest of the
                    // sweep: one late response must not abandon the other ~30
                    // requests. The ECU recovers by the next request, and this
                    // DID is retried on the next sweep.
                    errors++
                    lastError = e.message
                }
                onStats(UdsPollStats(polls, errors, lastError))
            }
            nextT += period
            val slack = nextT - System.currentTimeMillis()
            if (slack > 0) {
                delay(slack)
            } else {
                nextT = System.currentTimeMillis()
            }
        }
    }
}
