package com.apvcode.devx.remote

import com.apvcode.devx.data.attempt
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.io.IOException
import java.net.URI
import java.security.SecureRandom

/**
 * A small WebSocket client over a plain socket (binary messages, ping/pong, close). It is used instead of OkHttp
 * because the link to the computer must also work over `ws://` to an address on the local network, which the
 * HTTP stack refuses by the app's network policy: this link is encrypted and signed on its own.
 */
class WsConn private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
) {
    @Volatile var onMessage: (ByteArray) -> Unit = {}
    @Volatile var onClose: () -> Unit = {}
    @Volatile private var closed = false
    private val writeLock = Any()

    companion object {
        private val random = SecureRandom()

        /** Opens a connection: `url` is `ws://host:port/path` or `wss://...`. Throws on any failure. */
        fun open(url: String, subprotocol: String? = null, connectTimeoutMs: Int = 5000): WsConn {
            val uri = URI(url)
            val tls = uri.scheme == "wss"
            val port = if (uri.port > 0) uri.port else if (tls) 443 else 80
            val host = uri.host ?: throw IllegalArgumentException("no host in $url")
            val path = (uri.rawPath.takeIf { !it.isNullOrEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")

            val plain = Socket()
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(host, port), connectTimeoutMs)
            var socket: Socket = plain
            try {
                if (tls) {
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
                    ssl.soTimeout = connectTimeoutMs
                    ssl.startHandshake()
                    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) throw IOException("certificate does not match $host")
                    socket = ssl
                }
                socket.soTimeout = connectTimeoutMs
                val out = socket.getOutputStream()
                val inp = DataInputStream(BufferedInputStream(socket.getInputStream(), 16 * 1024))

                val key = Base64.getEncoder().encodeToString(ByteArray(16).also { random.nextBytes(it) })
                val request = buildString {
                    append("GET $path HTTP/1.1\r\n")
                    append("Host: $host:$port\r\n")
                    append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n")
                    if (subprotocol != null) append("Sec-WebSocket-Protocol: $subprotocol\r\n")
                    append("\r\n")
                }
                out.write(request.toByteArray())
                out.flush()

                // The answer: status line and headers, up to the empty line.
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    val b = inp.read()
                    if (b < 0) throw EOFException("closed during the handshake")
                    head.append(b.toChar())
                    if (head.length > 16 * 1024) throw IOException("answer too long")
                }
                val lines = head.toString().split("\r\n")
                if (!lines[0].contains(" 101 ")) throw IOException("not a WebSocket server: ${lines[0]}")
                val expect = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                val accept = lines.firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }?.substringAfter(':')?.trim()
                if (accept != expect) throw IOException("bad accept key")
                socket.soTimeout = 0
                return WsConn(socket, inp, out).also { it.startReader() }
            } catch (e: Exception) {
                attempt("ws") { socket.close() }
                throw e
            }
        }
    }

    private fun startReader() {
        Thread({
            try {
                val message = ByteArrayOutputStream()
                var binary = true
                while (!closed) {
                    val b0 = input.read()
                    if (b0 < 0) break
                    val fin = b0 and 0x80 != 0
                    val opcode = b0 and 0x0f
                    val b1 = input.readUnsignedByte()
                    var len = (b1 and 0x7f).toLong()
                    if (len == 126L) len = input.readUnsignedShort().toLong() else if (len == 127L) len = input.readLong()
                    val masked = b1 and 0x80 != 0
                    val mask = if (masked) ByteArray(4).also { input.readFully(it) } else null
                    if (len > 8 * 1024 * 1024) break
                    val payload = ByteArray(len.toInt())
                    input.readFully(payload)
                    if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    when (opcode) {
                        0x8 -> break
                        0x9 -> writeFrame(0xA, payload)
                        0xA -> Unit
                        0x1, 0x2, 0x0 -> {
                            if (opcode != 0x0) { message.reset(); binary = opcode == 0x2 }
                            message.write(payload)
                            if (fin) {
                                val whole = message.toByteArray()
                                message.reset()
                                if (binary) onMessage(whole)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                RLog.d("ws reader: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                shutdown()
            }
        }, "devx-ws-reader").apply { isDaemon = true }.start()
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        synchronized(writeLock) {
            if (closed && opcode != 0x8) return
            val header = ByteArrayOutputStream()
            header.write(0x80 or opcode)
            val n = payload.size
            when {
                n < 126 -> header.write(0x80 or n)
                n < 65536 -> { header.write(0x80 or 126); header.write(n shr 8); header.write(n and 0xff) }
                else -> { header.write(0x80 or 127); for (i in 7 downTo 0) header.write(((n.toLong() shr (8 * i)) and 0xff).toInt()) }
            }
            val mask = ByteArray(4).also { random.nextBytes(it) }
            header.write(mask)
            val body = ByteArray(n) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
            output.write(header.toByteArray())
            output.write(body)
            output.flush()
        }
    }

    fun send(data: ByteArray) {
        try { writeFrame(0x2, data) } catch (_: Exception) { shutdown() }
    }

    fun close() {
        if (closed) return
        try { writeFrame(0x8, ByteArray(0)) } catch (_: Exception) {}
        shutdown()
    }

    private fun shutdown() {
        if (closed) return
        closed = true
        attempt("ws") { socket.close() }
        onClose()
    }
}
