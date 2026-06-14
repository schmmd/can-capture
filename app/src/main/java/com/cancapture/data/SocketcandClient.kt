package com.cancapture.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.Closeable
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
 *   server> < frame <can_id_hex> <secs.usecs> <data_hex> >   (RX)
 *   client> < send <can_id_hex> <len> <data_hex> >           (TX in rawmode)
 *
 * The can_id hex carries the Linux CAN flag bits (EFF=0x80000000, RTR=0x40000000,
 * ERR=0x20000000) in the high bits; the identifier is in the low bits.
 */
class SocketcandClient(
    private val host: String,
    private val port: Int,
    private val bus: String,
    private val channel: Int = 1,
    private val connectTimeoutMs: Int = 5000,
) {
    /**
     * Open the TCP connection, run the socketcand handshake, and return a
     * live session. Caller owns the returned [SocketcandSession] and must
     * close it. The session's reader job runs in [scope].
     */
    suspend fun connect(scope: CoroutineScope): SocketcandSession {
        val resolved = withContext(Dispatchers.IO) {
            InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }
                ?: InetAddress.getByName(host)
        }
        val target = InetSocketAddress(resolved, port)
        val socket = withContext(Dispatchers.IO) {
            Socket().also {
                it.connect(target, connectTimeoutMs)
                it.tcpNoDelay = true
            }
        }
        try {
            val reader = withContext(Dispatchers.IO) {
                BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            }
            val writer = withContext(Dispatchers.IO) {
                OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII)
            }
            withContext(Dispatchers.IO) {
                readSocketcandMessage(reader) ?: throw IOException("No greeting from server")
                writer.write("< open $bus >"); writer.flush()
                val openResp = readSocketcandMessage(reader)
                    ?: throw IOException("No response to open")
                if (!openResp.contains("ok")) throw IOException("Bus open rejected: $openResp")
                writer.write("< rawmode >"); writer.flush()
                val rawResp = readSocketcandMessage(reader)
                    ?: throw IOException("No response to rawmode")
                if (!rawResp.contains("ok")) throw IOException("Rawmode rejected: $rawResp")
            }
            return SocketcandSession(socket, reader, writer, channel, scope)
        } catch (t: Throwable) {
            try { socket.close() } catch (_: Exception) {}
            throw t
        }
    }

    internal fun parseFrame(message: String): CanFrame? = parseSocketcandFrame(message)
}

/**
 * Long-lived socketcand connection. [frames] emits received CAN frames
 * (already tagged with [channel]). [send] transmits a frame on this bus.
 * Closing cancels the reader job and tears down the socket.
 */
class SocketcandSession internal constructor(
    private val socket: Socket,
    private val reader: BufferedReader,
    private val writer: OutputStreamWriter,
    val channel: Int,
    scope: CoroutineScope,
) : Closeable {
    private val rxChannel = Channel<CanFrame>(capacity = 256)
    val frames: Flow<CanFrame> = rxChannel.consumeAsFlow()

    private val sendMutex = Mutex()

    private val readerJob: Job = scope.launch(Dispatchers.IO) {
        try {
            while (isActive) {
                val msg = readSocketcandMessage(reader) ?: break
                val frame = parseSocketcandFrame(msg) ?: continue
                rxChannel.send(frame.copy(channel = channel))
            }
            rxChannel.close()
        } catch (e: CancellationException) {
            rxChannel.close()
            throw e
        } catch (e: Throwable) {
            rxChannel.close(e)
        }
    }

    suspend fun send(canId: Long, data: ByteArray, extended: Boolean = false) {
        require(data.size in 0..8) { "Classical CAN frame max 8 bytes" }
        val idMasked = if (extended) (canId and 0x1FFFFFFFL) or 0x80000000L else canId and 0x7FFL
        val idStr = java.lang.Long.toHexString(idMasked).uppercase()
        val msg = buildString(32 + data.size * 3) {
            append("< send ")
            append(idStr)
            append(' ')
            append(data.size)
            for (b in data) {
                append(' ')
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
            append(" >")
        }
        sendMutex.withLock {
            withContext(Dispatchers.IO) {
                writer.write(msg)
                writer.flush()
            }
        }
    }

    override fun close() {
        readerJob.cancel()
        try { socket.close() } catch (_: Exception) {}
    }

    private companion object {
        val HEX = "0123456789ABCDEF".toCharArray()
    }
}

internal fun readSocketcandMessage(reader: BufferedReader): String? {
    val sb = StringBuilder(64)
    while (true) {
        val c = reader.read()
        if (c == -1) return if (sb.isEmpty()) null else sb.toString()
        val ch = c.toChar()
        sb.append(ch)
        if (ch == '>') return sb.toString()
    }
}

internal fun parseSocketcandFrame(message: String): CanFrame? {
    val body = message.trim().removePrefix("<").removeSuffix(">").trim()
    if (body.isEmpty()) return null
    val firstSpace = body.indexOf(' ')
    if (firstSpace < 0) return null
    val tag = body.substring(0, firstSpace)
    if (tag != "frame") return null

    val rest = body.substring(firstSpace + 1).trim()
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
        data = data,
    )
}
