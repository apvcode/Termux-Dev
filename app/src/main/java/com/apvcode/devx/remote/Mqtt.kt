package com.apvcode.devx.remote

import com.apvcode.devx.data.attempt
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The little bit of MQTT 3.1.1 the relay needs (connect, subscribe, publish at QoS 0, ping) over a WebSocket.
 * The packets may arrive split over several WebSocket messages or several in one: it is read as a byte stream.
 */
class MqttConn(private val ws: WsConn, private val onPublish: (topic: String, payload: ByteArray) -> Unit, private val onClosed: () -> Unit) {
    private val buffer = ByteArrayOutputStream()
    private val connack = CountDownLatch(1)
    private val suback = CountDownLatch(1)
    @Volatile private var accepted = false
    @Volatile private var closed = false
    private var packetId = 0
    private var pinger: Thread? = null

    companion object {
        fun string(s: String): ByteArray { val b = s.toByteArray(); return byteArrayOf((b.size shr 8).toByte(), (b.size and 0xff).toByte()) + b }

        fun remainingLength(n: Int): ByteArray {
            val out = ByteArrayOutputStream()
            var x = n
            do {
                var d = x % 128
                x /= 128
                if (x > 0) d = d or 0x80
                out.write(d)
            } while (x > 0)
            return out.toByteArray()
        }

        fun packet(firstByte: Int, body: ByteArray): ByteArray = byteArrayOf(firstByte.toByte()) + remainingLength(body.size) + body

        fun connect(clientId: String, keepAlive: Int): ByteArray =
            packet(0x10, string("MQTT") + byteArrayOf(4, 0x02, (keepAlive shr 8).toByte(), (keepAlive and 0xff).toByte()) + string(clientId))

        fun subscribe(id: Int, topic: String): ByteArray = packet(0x82, byteArrayOf((id shr 8).toByte(), (id and 0xff).toByte()) + string(topic) + byteArrayOf(0))

        fun publish(topic: String, payload: ByteArray): ByteArray = packet(0x30, string(topic) + payload)

        val PING = byteArrayOf(0xC0.toByte(), 0)
        val DISCONNECT = byteArrayOf(0xE0.toByte(), 0)

        /** Reads one packet from the front of [data]: (first byte, body, total length), or null if it is not complete yet. */
        fun parse(data: ByteArray): Triple<Int, ByteArray, Int>? {
            if (data.size < 2) return null
            var mult = 1
            var len = 0
            var i = 1
            while (true) {
                if (i >= data.size) return null
                val d = data[i].toInt() and 0xff
                len += (d and 0x7f) * mult
                i++
                if (d and 0x80 == 0) break
                mult *= 128
                if (mult > 128 * 128 * 128) throw IllegalStateException("bad length")
            }
            if (data.size < i + len) return null
            return Triple(data[0].toInt() and 0xff, data.copyOfRange(i, i + len), i + len)
        }
    }

    init {
        ws.onMessage = { feed(it) }
        ws.onClose = { closed = true; pinger?.interrupt(); onClosed() }
    }

    @Synchronized
    private fun feed(bytes: ByteArray) {
        buffer.write(bytes)
        var data = buffer.toByteArray()
        while (true) {
            val p = try { parse(data) } catch (_: Exception) { ws.close(); return } ?: break
            handle(p.first, p.second)
            data = data.copyOfRange(p.third, data.size)
        }
        buffer.reset()
        buffer.write(data)
    }

    private fun handle(first: Int, body: ByteArray) {
        when (first shr 4) {
            2 -> { accepted = body.size >= 2 && body[1].toInt() == 0; connack.countDown() }
            9 -> suback.countDown()
            3 -> {
                if ((first shr 1) and 3 != 0) return // only QoS 0 is used
                val tl = ((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff)
                onPublish(String(body, 2, tl), body.copyOfRange(2 + tl, body.size))
            }
        }
    }

    /** Says hello to the broker; false when it does not answer or refuses. */
    fun handshake(clientId: String, timeoutMs: Long): Boolean {
        ws.send(connect(clientId, 30))
        if (!connack.await(timeoutMs, TimeUnit.MILLISECONDS) || !accepted) return false
        pinger = Thread({
            try { while (!closed) { Thread.sleep(20_000); if (!closed) ws.send(PING) } } catch (_: InterruptedException) {}
        }, "devx-mqtt-ping").apply { isDaemon = true; start() }
        return true
    }

    fun subscribeTo(topic: String, timeoutMs: Long): Boolean {
        ws.send(subscribe(++packetId, topic))
        return suback.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun publishTo(topic: String, payload: ByteArray) = ws.send(publish(topic, payload))

    fun close() {
        if (closed) return
        attempt("mqtt") { ws.send(DISCONNECT) }
        ws.close()
    }
}
