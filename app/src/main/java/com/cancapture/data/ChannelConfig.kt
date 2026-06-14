package com.cancapture.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Per-bus capture mode. [Passive] just receives and logs; [UdsPoll] also
 * actively requests data via UDS over ISO-TP so silent diagnostic ECUs
 * produce traffic that lands in the same merged ASC file.
 */
sealed interface ChannelConfig {
    val bus: String

    data class Passive(override val bus: String) : ChannelConfig

    data class UdsPoll(
        override val bus: String,
        val txId: Long,
        val rxId: Long,
        val extended: Boolean = false,
        val periodMs: Int = 1000,
        val timeoutMs: Int = 1000,
        val blockSize: Int = 0,
        val stMinMs: Int = 0,
        val paddingByte: Int = 0x00,
        val entries: List<UdsRequest>,
    ) : ChannelConfig
}

data class UdsRequest(
    val service: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UdsRequest) return false
        return service == other.service && payload.contentEquals(other.payload)
    }
    override fun hashCode(): Int = 31 * service + payload.contentHashCode()
}

object ChannelConfigJson {
    /**
     * Parse a per-bus UDS poll config like:
     * ```
     * {
     *   "txId": "0x7E0",
     *   "rxId": "0x7E8",
     *   "extended": false,
     *   "periodMs": 1000,
     *   "timeoutMs": 1000,
     *   "blockSize": 0,
     *   "stMinMs": 0,
     *   "padding": "0x00",
     *   "polls": [
     *     { "service": "0x22", "data": "0xF180" },
     *     { "service": "0x22", "data": "0xF181" }
     *   ]
     * }
     * ```
     */
    fun parseUdsPoll(bus: String, json: String): ChannelConfig.UdsPoll {
        val obj = JSONObject(json)
        val txId = parseHexOrInt(obj.get("txId"))
        val rxId = parseHexOrInt(obj.get("rxId"))
        val extended = obj.optBoolean("extended", false)
        val periodMs = obj.optInt("periodMs", 1000)
        val timeoutMs = obj.optInt("timeoutMs", 1000)
        val blockSize = obj.optInt("blockSize", 0)
        val stMinMs = obj.optInt("stMinMs", 0)
        val padding = if (obj.has("padding")) parseHexOrInt(obj.get("padding")).toInt() and 0xFF else 0x00
        val pollsArr = obj.optJSONArray("polls") ?: JSONArray()
        val entries = (0 until pollsArr.length()).map { i ->
            val e = pollsArr.getJSONObject(i)
            val service = parseHexOrInt(e.get("service")).toInt() and 0xFF
            val payload = parseHexBytes(e.opt("data"))
            UdsRequest(service, payload)
        }
        require(entries.isNotEmpty()) { "polls must be non-empty" }
        require(periodMs > 0) { "periodMs must be positive" }
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        return ChannelConfig.UdsPoll(
            bus = bus,
            txId = txId,
            rxId = rxId,
            extended = extended,
            periodMs = periodMs,
            timeoutMs = timeoutMs,
            blockSize = blockSize,
            stMinMs = stMinMs,
            paddingByte = padding,
            entries = entries,
        )
    }

    fun encodeUdsPoll(cfg: ChannelConfig.UdsPoll): String {
        val obj = JSONObject()
        obj.put("txId", "0x${"%X".format(cfg.txId)}")
        obj.put("rxId", "0x${"%X".format(cfg.rxId)}")
        obj.put("extended", cfg.extended)
        obj.put("periodMs", cfg.periodMs)
        obj.put("timeoutMs", cfg.timeoutMs)
        obj.put("blockSize", cfg.blockSize)
        obj.put("stMinMs", cfg.stMinMs)
        obj.put("padding", "0x${"%02X".format(cfg.paddingByte and 0xFF)}")
        val polls = JSONArray()
        for (e in cfg.entries) {
            val p = JSONObject()
            p.put("service", "0x${"%02X".format(e.service)}")
            p.put("data", "0x" + e.payload.joinToString("") { "%02X".format(it.toInt() and 0xFF) })
            polls.put(p)
        }
        obj.put("polls", polls)
        return obj.toString(2)
    }

    /**
     * Serialize the channels list to a JSON string for DataStore. Passive
     * channels are stored as `{"bus":"can0","mode":"passive"}`; UDS channels
     * embed the full config.
     */
    fun encodeChannels(channels: List<ChannelConfig>): String {
        val arr = JSONArray()
        for (c in channels) {
            val o = JSONObject()
            o.put("bus", c.bus)
            when (c) {
                is ChannelConfig.Passive -> o.put("mode", "passive")
                is ChannelConfig.UdsPoll -> {
                    o.put("mode", "uds_poll")
                    o.put("config", JSONObject(encodeUdsPoll(c)))
                }
            }
            arr.put(o)
        }
        return arr.toString()
    }

    fun decodeChannels(json: String): List<ChannelConfig>? {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val bus = o.getString("bus")
                when (o.optString("mode", "passive")) {
                    "uds_poll" -> {
                        val cfg = o.getJSONObject("config")
                        parseUdsPoll(bus, cfg.toString())
                    }
                    else -> ChannelConfig.Passive(bus)
                }
            }
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun parseHexOrInt(value: Any): Long = when (value) {
        is Number -> value.toLong()
        is String -> {
            val v = value.trim()
            when {
                v.startsWith("0x", ignoreCase = true) -> v.substring(2).toLong(16)
                v.startsWith("#") -> v.substring(1).toLong(16)
                v.contains(Regex("[a-fA-F]")) -> v.toLong(16)
                else -> v.toLong()
            }
        }
        else -> throw JSONException("Expected number or hex string, got $value")
    }

    private fun parseHexBytes(value: Any?): ByteArray {
        if (value == null || value == JSONObject.NULL) return ByteArray(0)
        return when (value) {
            is JSONArray -> ByteArray(value.length()) { i ->
                (parseHexOrInt(value.get(i)).toInt() and 0xFF).toByte()
            }
            is String -> {
                val s = value.trim().removePrefix("0x").removePrefix("0X").replace(" ", "")
                require(s.length % 2 == 0) { "Hex string must have even length: $value" }
                ByteArray(s.length / 2) { i ->
                    Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16).toByte()
                }
            }
            is Number -> {
                val v = value.toLong()
                val hex = java.lang.Long.toHexString(v).padStart(2, '0').let {
                    if (it.length % 2 == 0) it else "0$it"
                }
                ByteArray(hex.length / 2) { i ->
                    Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte()
                }
            }
            else -> throw JSONException("Expected hex bytes, got $value")
        }
    }
}
