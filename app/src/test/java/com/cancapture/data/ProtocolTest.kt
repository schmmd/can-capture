package com.cancapture.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.BufferedWriter
import java.io.StringWriter
import java.time.Instant

class ProtocolTest {

    @Test
    fun parsesStandardFrame() {
        val f = parseSocketcandFrame("< frame 123 1700000000.123456 11 22 33 >")!!
        assertEquals(0x123L, f.id)
        assertEquals(false, f.extended)
        assertEquals(1700000000.123456, f.timestamp, 1e-9)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33), f.data)
    }

    @Test
    fun parsesExtendedFrameAndStripsFlagBits() {
        val f = parseSocketcandFrame("< frame 98DAF140 1.0 00 >")!!
        assertEquals(true, f.extended)
        assertEquals(0x18DAF140L, f.id)
    }

    @Test
    fun ignoresErrorFramesAndNonFrames() {
        assertNull(parseSocketcandFrame("< frame 20000001 1.0 00 >"))
        assertNull(parseSocketcandFrame("< ok >"))
    }

    @Test
    fun hex() {
        assertEquals("1A2B", byteArrayOf(0x1A, 0x2B).toHex())
        assertEquals("1A 2B", byteArrayOf(0x1A, 0x2B).toHex(" "))
        assertEquals("", ByteArray(0).toHex(" "))
    }

    @Test
    fun ascLineFormatAndRelativeTime() {
        val sw = StringWriter()
        val w = AscWriter(BufferedWriter(sw), Instant.EPOCH)
        val t0 = w.writeFrame(CanFrame(0x123, false, false, 10.0, byteArrayOf(0x11, 0x22), channel = 1))
        val t1 = w.writeFrame(CanFrame(0x18DAF140, true, false, 10.5, ByteArray(0), channel = 2))
        w.close()
        assertEquals(0.0, t0, 0.0)
        assertEquals(0.5, t1, 1e-9)
        val lines = sw.toString().lines()
        assertEquals("   0.000000 1  123             Rx   d 2 11 22", lines[0])
        assertEquals("   0.500000 2  18DAF140x       Rx   d 0 ", lines[1])
        assertEquals("End TriggerBlock", lines[2])
    }

    @Test
    fun udsPollJsonRoundTrip() {
        val cfg = ChannelConfigJson.parseUdsPoll(
            "can1",
            """{"txId":"0x740","rxId":"0x748","periodMs":500,"polls":[{"service":"0x22","data":"0x2800"}]}"""
        )
        assertEquals(0x740L, cfg.txId)
        assertEquals(listOf(UdsRequest(0x22, byteArrayOf(0x28, 0x00))), cfg.entries)
        val back = ChannelConfigJson.decodeChannels(ChannelConfigJson.encodeChannels(listOf(cfg)))
        assertEquals(listOf(cfg), back)
    }

    /** Fake ECU: records TX frames and replies through onFrame. */
    private class Ecu(val reply: (ByteArray, IsoTp) -> Unit) {
        val sent = mutableListOf<ByteArray>()
        lateinit var isoTp: IsoTp
        val send: suspend (Long, ByteArray, Boolean) -> Unit = { _, data, _ ->
            sent += data
            reply(data, isoTp)
        }
    }

    private fun frame(vararg bytes: Int) =
        CanFrame(0x748, false, false, 0.0, ByteArray(bytes.size) { bytes[it].toByte() })

    private fun isoTp(ecu: Ecu) = IsoTp(ecu.send, txId = 0x740, rxId = 0x748, timeoutMs = 500)
        .also { ecu.isoTp = it }

    @Test
    fun reassemblesMultiFrameResponse() = runBlocking {
        val ecu = Ecu { data, tp ->
            when (data[0].toInt()) {
                // Our SF request: answer with a FF carrying 10 bytes total.
                0x03 -> tp.onFrame(frame(0x10, 0x0A, 0x62, 0x28, 0x00, 0x01, 0x02, 0x03))
                // Our flow control: send the remaining CF.
                0x30 -> tp.onFrame(frame(0x21, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x00))
            }
        }
        val resp = isoTp(ecu).request(byteArrayOf(0x22, 0x28, 0x00))
        assertArrayEquals(
            byteArrayOf(0x62, 0x28, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07),
            resp,
        )
        assertEquals(2, ecu.sent.size)
        assertEquals(0x30, ecu.sent[1][0].toInt())
    }

    @Test
    fun responsePendingWaitsWithoutResending() = runBlocking {
        val ecu = Ecu { _, tp ->
            tp.onFrame(frame(0x03, 0x7F, 0x22, 0x78))
            tp.onFrame(frame(0x04, 0x62, 0x28, 0x00, 0x42))
        }
        val uds = UdsClient(isoTp(ecu))
        val data = uds.request(0x22, byteArrayOf(0x28, 0x00))
        assertArrayEquals(byteArrayOf(0x28, 0x00, 0x42), data)
        assertEquals("request must be sent exactly once", 1, ecu.sent.size)
    }

    @Test
    fun negativeResponseThrowsWithNrc() = runBlocking {
        val ecu = Ecu { _, tp ->
            tp.onFrame(frame(0x03, 0x7F, 0x22, 0x31))
        }
        val uds = UdsClient(isoTp(ecu))
        val e = runCatching { uds.request(0x22, byteArrayOf(0x28, 0x00)) }.exceptionOrNull()
        assertEquals(0x31, (e as UdsException).nrc)
    }
}
