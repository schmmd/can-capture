package com.cancapture.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Talks the socketcand text protocol:
 *   server> < hi >
 *   client> < open <bus> >
 *   server> < ok >
 *   client> < rawmode >
 *   server> < ok >
 *   server> < frame <can_id_hex> <secs.usecs> <data_hex> >   (repeated)
 *
 * The can_id hex carries the Linux CAN flag bits (EFF=0x80000000, RTR=0x40000000,
 * ERR=0x20000000) in the high bits; the identifier is in the low bits.
 */
class SocketcandClient(
    private val host: String,
    private val port: Int,
    private val bus: String,
    private val connectTimeoutMs: Int = 5000
) {
    fun frames(): Flow<CanFrame> = channelFlow {
        val resolved = withContext(Dispatchers.IO) {
            InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }
                ?: InetAddress.getByName(host)
        }
        val targetAddress = InetSocketAddress(resolved, port)
        val socket = withContext(Dispatchers.IO) {
            val s = Socket()
            s.connect(targetAddress, connectTimeoutMs)
            s
        }
        val readerJob = launch(Dispatchers.IO) {
            try {
                socket.use { sock ->
                    sock.tcpNoDelay = true
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII))
                    val writer = OutputStreamWriter(sock.getOutputStream(), Charsets.US_ASCII)

                    readMessage(reader) ?: throw IOException("No greeting from server")

                    writer.write("< open $bus >")
                    writer.flush()
                    val openResp = readMessage(reader) ?: throw IOException("No response to open")
                    if (!openResp.contains("ok")) {
                        throw IOException("Bus open rejected: $openResp")
                    }

                    writer.write("< rawmode >")
                    writer.flush()
                    val rawResp = readMessage(reader) ?: throw IOException("No response to rawmode")
                    if (!rawResp.contains("ok")) {
                        throw IOException("Rawmode rejected: $rawResp")
                    }

                    while (isActive) {
                        val msg = readMessage(reader) ?: break
                        val frame = parseFrame(msg) ?: continue
                        trySend(frame)
                    }
                }
            } catch (e: CancellationException) {
                // expected on flow cancellation
            } catch (e: Exception) {
                close(e)
                return@launch
            }
            close()
        }

        awaitClose {
            readerJob.cancel()
            try { socket.close() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    /** Reads characters until the next '>' (message terminator). Returns null on EOF. */
    private fun readMessage(reader: BufferedReader): String? {
        val sb = StringBuilder(64)
        while (true) {
            val c = reader.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            val ch = c.toChar()
            sb.append(ch)
            if (ch == '>') return sb.toString()
        }
    }

    internal fun parseFrame(message: String): CanFrame? {
        val body = message.trim().removePrefix("<").removeSuffix(">").trim()
        if (body.isEmpty()) return null
        val firstSpace = body.indexOf(' ')
        if (firstSpace < 0) return null
        val tag = body.substring(0, firstSpace)
        if (tag != "frame") return null

        val rest = body.substring(firstSpace + 1).trim()
        // tokens: <can_id_hex> <ts> <data_hex...>
        val tok1End = rest.indexOf(' ')
        if (tok1End < 0) return null
        val idStr = rest.substring(0, tok1End)
        val afterId = rest.substring(tok1End + 1).trim()
        val tok2End = afterId.indexOf(' ')
        val tsStr: String
        val dataPart: String
        if (tok2End < 0) {
            tsStr = afterId
            dataPart = ""
        } else {
            tsStr = afterId.substring(0, tok2End)
            dataPart = afterId.substring(tok2End + 1).trim()
        }

        val rawId = idStr.toLongOrNull(16) ?: return null
        val ts = tsStr.toDoubleOrNull() ?: return null

        // socketcand passes the Linux can_id verbatim: EFF=0x80000000, RTR=0x40000000,
        // ERR=0x20000000, identifier in the low bits.
        val extended = (rawId and 0x80000000L) != 0L
        val rtr = (rawId and 0x40000000L) != 0L
        val isError = (rawId and 0x20000000L) != 0L
        if (isError) return null
        val canId = rawId and if (extended) 0x1FFFFFFFL else 0x7FFL

        val cleaned = dataPart.replace(Regex("\\s+"), "")
        if (cleaned.length % 2 != 0) return null
        val data = ByteArray(cleaned.length / 2)
        for (i in data.indices) {
            val high = Character.digit(cleaned[i * 2], 16)
            val low = Character.digit(cleaned[i * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            data[i] = ((high shl 4) or low).toByte()
        }

        return CanFrame(
            id = canId,
            extended = extended,
            rtr = rtr,
            timestamp = ts,
            data = data
        )
    }
}
