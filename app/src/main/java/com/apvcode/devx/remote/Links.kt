package com.apvcode.devx.remote

import java.nio.ByteBuffer

/** Carries whole messages (bytes) to the computer and knows nothing about them. */
interface Link {
    /** `direct` (a WebSocket to the computer) or `relay` (through a public broker). */
    val kind: String
    var onMessage: (ByteArray) -> Unit
    var onClose: () -> Unit
    fun send(data: ByteArray)
    fun close()
}

class WsLink(private val ws: WsConn) : Link {
    override val kind = "direct"
    override var onMessage: (ByteArray) -> Unit = {}
    override var onClose: () -> Unit = {}

    init {
        ws.onMessage = { onMessage(it) }
        ws.onClose = { onClose() }
    }

    override fun send(data: ByteArray) = ws.send(data)
    override fun close() = ws.close()

    companion object {
        fun open(address: String, timeoutMs: Int = 4000): WsLink = WsLink(WsConn.open("ws://$address", null, timeoutMs))
    }
}

/** Cuts a message into relay packets and puts them back together. */
class Chunker(private val conn: ByteArray) {
    private var nextId = 0
    private class Partial(val parts: Array<ByteArray?>, var got: Int, val at: Long)
    private val partial = HashMap<Int, Partial>()

    @Synchronized
    fun split(data: ByteArray): List<ByteArray> {
        val id = nextId++
        val total = maxOf(1, (data.size + CHUNK - 1) / CHUNK)
        return List(total) { i ->
            val body = data.copyOfRange(i * CHUNK, minOf(data.size, (i + 1) * CHUNK))
            val h = ByteBuffer.allocate(HEADER).put(PKT_CHUNK.toByte()).put(conn).putInt(id).putShort(i.toShort()).putShort(total.toShort())
            h.array() + body
        }
    }

    @Synchronized
    fun join(packet: ByteArray): ByteArray? {
        if (packet.size < HEADER || packet[0].toInt() != PKT_CHUNK) return null
        val b = ByteBuffer.wrap(packet)
        val id = b.getInt(9)
        val idx = b.getShort(13).toInt() and 0xffff
        val total = b.getShort(15).toInt() and 0xffff
        if (total == 0 || idx >= total || total > 400) return null
        val body = packet.copyOfRange(HEADER, packet.size)
        if (total == 1) return body
        val now = System.currentTimeMillis()
        partial.entries.removeAll { now - it.value.at > 30_000 }
        val p = partial.getOrPut(id) { Partial(arrayOfNulls(total), 0, now) }
        if (p.parts.size != total) return null
        if (p.parts[idx] == null) { p.parts[idx] = body; p.got++ }
        if (p.got < total) return null
        partial.remove(id)
        val out = java.io.ByteArrayOutputStream()
        p.parts.forEach { out.write(it!!) }
        return out.toByteArray()
    }

    companion object {
        const val PKT_CHUNK = 0x01
        const val PKT_BYE = 0x02
        const val CHUNK = 12 * 1024
        const val HEADER = 1 + 8 + 4 + 2 + 2
    }
}

/** The relay: a public MQTT broker passes the encrypted packets to the computer, wherever it is. */
class RelayLink private constructor(private val mqtt: MqttConn, private val hostId: String, private val conn: ByteArray) : Link {
    override val kind = "relay"
    override var onMessage: (ByteArray) -> Unit = {}
    override var onClose: () -> Unit = {}
    private val chunker = Chunker(conn)
    private val up = "devx/v1/$hostId/up"

    override fun send(data: ByteArray) { for (part in chunker.split(data)) mqtt.publishTo(up, part) }

    override fun close() {
        runCatching { mqtt.publishTo(up, byteArrayOf(Chunker.PKT_BYE.toByte()) + conn) }
        Thread { Thread.sleep(250); mqtt.close() }.apply { isDaemon = true }.start()
    }

    companion object {
        /** The brokers by id, as in relay.ts on the computer. */
        val BROKERS = mapOf(
            0 to "wss://broker.hivemq.com:8884/mqtt",
            1 to "wss://test.mosquitto.org:8081",
            2 to "wss://broker.emqx.io:8084/mqtt",
        )

        /** Connects to the broker and listens for the answers of the computer. Throws when the broker cannot be reached. */
        fun open(brokerUrl: String, hostId: String, timeoutMs: Long = 8000): RelayLink {
            val conn = RemoteCrypto.randomBytes(8)
            val connHex = conn.toHex()
            lateinit var link: RelayLink
            val ws = WsConn.open(brokerUrl, "mqtt", timeoutMs.toInt())
            var pending: RelayLink? = null
            val mqtt = MqttConn(ws, { _, payload -> pending?.let { l -> l.chunker.join(payload)?.let { whole -> l.onMessage(whole) } } }, { pending?.onClose?.invoke() })
            try {
                if (!mqtt.handshake("devx-" + RemoteCrypto.randomBytes(6).toHex(), timeoutMs)) throw java.io.IOException("the broker refused")
                if (!mqtt.subscribeTo("devx/v1/$hostId/dn/$connHex", timeoutMs)) throw java.io.IOException("the broker did not confirm")
            } catch (e: Exception) {
                mqtt.close()
                throw e
            }
            link = RelayLink(mqtt, hostId, conn)
            pending = link
            return link
        }
    }
}
