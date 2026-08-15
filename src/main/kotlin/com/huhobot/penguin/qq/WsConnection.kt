package com.huhobot.penguin.qq

import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory

private val logger = LoggerFactory.getLogger("PenguinServer-Fabric/WsConnection")

private const val OP_CONTINUATION = 0x0
private const val OP_TEXT = 0x1
private const val OP_BINARY = 0x2
private const val OP_CLOSE = 0x8
private const val OP_PING = 0x9
private const val OP_PONG = 0xA

private val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

/**
 * 零额外依赖的 RFC6455 WebSocket 客户端，基于 Java SSLSocket 实现。
 * 对齐 BDS 版 wsc.js，专为 QQ 开放平台 wss 网关设计。
 * 事件：onOpen / onMessage / onClose / onError
 * 方法：connect() / send(text) / close(code, reason)
 */
class WsConnection(
    private val url: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val connectTimeoutMs: Int = 15_000,
    private val maxPayload: Int = 16 * 1024 * 1024
) {
    private var openCb: (() -> Unit)? = null
    private var messageCb: ((String) -> Unit)? = null
    private var closeCb: ((Int, String) -> Unit)? = null
    private var errorCb: ((Exception) -> Unit)? = null

    private val closed = AtomicBoolean(false)
    private var socket: javax.net.ssl.SSLSocket? = null
    private var outputStream: OutputStream? = null

    private var pendingCloseCode = 1005
    private var pendingCloseReason = ""

    fun onOpen(cb: () -> Unit) { openCb = cb }
    fun onMessage(cb: (String) -> Unit) { messageCb = cb }
    fun onClose(cb: (Int, String) -> Unit) { closeCb = cb }
    fun onError(cb: (Exception) -> Unit) { errorCb = cb }

    fun connect() {
        Thread {
            try {
                doConnect()
            } catch (e: Exception) {
                if (!closed.get()) {
                    errorCb?.invoke(e)
                    closeCb?.invoke(1006, e.message ?: "连接失败")
                }
            }
        }.also { it.isDaemon = true; it.name = "penguin-ws-reader" }.start()
    }

    private fun doConnect() {
        val parsed = URL(url.replace("wss://", "https://").replace("ws://", "http://"))
        val host = parsed.host
        val port = if (parsed.port == -1) 443 else parsed.port
        val path = if (parsed.file.isNullOrEmpty()) "/" else parsed.file

        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val sock = factory.createSocket(host, port) as javax.net.ssl.SSLSocket
        sock.soTimeout = 0 // 读取不超时（靠心跳检测）
        sock.tcpNoDelay = true
        sock.startHandshake()
        socket = sock

        val out = sock.outputStream
        outputStream = out

        // 生成 WebSocket key
        val keyBytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val key = Base64.getEncoder().encodeToString(keyBytes)
        val expectedAccept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.UTF_8))
        )

        // 发送 HTTP Upgrade 请求
        val reqLines = mutableListOf(
            "GET $path HTTP/1.1",
            "Host: $host${if (port == 443) "" else ":$port"}",
            "Upgrade: websocket",
            "Connection: Upgrade",
            "Sec-WebSocket-Key: $key",
            "Sec-WebSocket-Version: 13"
        )
        extraHeaders.forEach { (k, v) -> reqLines.add("$k: $v") }
        out.write((reqLines.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.UTF_8))
        out.flush()

        // 读 HTTP 升级响应头
        val inp = sock.inputStream
        val headerBuf = readHttpHeader(inp)
        val headerText = headerBuf.toString(Charsets.UTF_8)
        val firstLine = headerText.lines().firstOrNull() ?: ""
        if (!firstLine.contains("101")) {
            throw IllegalStateException("网关未返回 101 升级：$firstLine")
        }
        val acceptLine = headerText.lines().firstOrNull {
            it.lowercase().startsWith("sec-websocket-accept")
        }
        val accept = acceptLine?.substringAfter(":")?.trim()
        if (accept != expectedAccept) {
            throw IllegalStateException("Sec-WebSocket-Accept 校验失败（got=$accept expected=$expectedAccept）")
        }

        closed.set(false)
        openCb?.invoke()

        // 进入帧读取循环
        readFrameLoop(inp)
    }

    private fun readHttpHeader(inp: InputStream): ByteArray {
        val buf = mutableListOf<Byte>()
        var prev3 = IntArray(3)
        while (true) {
            val b = inp.read()
            if (b == -1) break
            buf.add(b.toByte())
            // 检测 \r\n\r\n
            if (buf.size >= 4) {
                val last4 = buf.takeLast(4)
                if (last4[0] == '\r'.code.toByte() && last4[1] == '\n'.code.toByte() &&
                    last4[2] == '\r'.code.toByte() && last4[3] == '\n'.code.toByte()
                ) break
            }
        }
        return buf.toByteArray()
    }

    private fun readFrameLoop(inp: InputStream) {
        val fragments = mutableListOf<ByteArray>()
        var fragmentOpcode = 0

        try {
            while (!closed.get()) {
                val b0 = inp.read()
                if (b0 == -1) { emitClose(1006, "连接中断"); return }
                val b1 = inp.read()
                if (b1 == -1) { emitClose(1006, "连接中断"); return }

                val fin = (b0 and 0x80) != 0
                val opcode = b0 and 0x0F
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()

                len = when {
                    len == 126L -> readUShort(inp).toLong()
                    len == 127L -> readULong(inp)
                    else -> len
                }

                if (len > maxPayload) {
                    emitClose(1009, "帧负载超出上限")
                    return
                }

                val payload = if (masked) {
                    val mask = inp.readNBytes(4)
                    val data = inp.readNBytes(len.toInt())
                    for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
                    data
                } else {
                    inp.readNBytes(len.toInt())
                }

                when (opcode) {
                    OP_PING -> sendFrame(OP_PONG, payload)
                    OP_PONG -> { /* 忽略 */ }
                    OP_CLOSE -> {
                        var code = 1005
                        var reason = ""
                        if (payload.size >= 2) {
                            code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                            reason = payload.drop(2).toByteArray().toString(Charsets.UTF_8)
                        }
                        sendFrame(OP_CLOSE, payload)
                        emitClose(code, reason)
                        return
                    }
                    OP_TEXT, OP_BINARY -> {
                        if (!fin) {
                            fragments.clear()
                            fragments.add(payload)
                            fragmentOpcode = opcode
                        } else {
                            emitMessage(payload, opcode)
                        }
                    }
                    OP_CONTINUATION -> {
                        fragments.add(payload)
                        if (fin) {
                            val full = fragments.fold(ByteArray(0)) { acc, b -> acc + b }
                            val op = fragmentOpcode
                            fragments.clear()
                            fragmentOpcode = 0
                            emitMessage(full, op)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                errorCb?.invoke(e)
                emitClose(1006, e.message ?: "读取错误")
            }
        }
    }

    private fun emitMessage(payload: ByteArray, opcode: Int) {
        if (opcode == OP_TEXT) {
            messageCb?.invoke(payload.toString(Charsets.UTF_8))
        }
    }

    private fun emitClose(code: Int, reason: String) {
        if (closed.compareAndSet(false, true)) {
            try { socket?.close() } catch (_: Exception) {}
            closeCb?.invoke(code, reason)
        }
    }

    fun send(text: String) {
        sendFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))
    }

    fun close(code: Int = 1000, reason: String = "") {
        if (closed.get()) return
        val reasonBytes = reason.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(2 + reasonBytes.size)
        payload[0] = ((code shr 8) and 0xFF).toByte()
        payload[1] = (code and 0xFF).toByte()
        reasonBytes.copyInto(payload, 2)
        sendFrame(OP_CLOSE, payload)
        emitClose(code, reason)
    }

    // ---- 帧发送（客户端帧一律掩码） ----

    @Synchronized
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val out = outputStream ?: return
        try {
            val len = payload.size
            val header = when {
                len < 126 -> ByteArray(2).also { it[1] = (0x80 or len).toByte() }
                len < 65536 -> ByteArray(4).also {
                    it[1] = (0x80 or 126).toByte()
                    it[2] = ((len shr 8) and 0xFF).toByte()
                    it[3] = (len and 0xFF).toByte()
                }
                else -> ByteArray(10).also {
                    it[1] = (0x80 or 127).toByte()
                    it[2] = 0; it[3] = 0; it[4] = 0; it[5] = 0
                    it[6] = ((len shr 24) and 0xFF).toByte()
                    it[7] = ((len shr 16) and 0xFF).toByte()
                    it[8] = ((len shr 8) and 0xFF).toByte()
                    it[9] = (len and 0xFF).toByte()
                }
            }
            header[0] = (0x80 or opcode).toByte() // FIN=1

            val maskKey = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
            val masked = ByteArray(len) { i -> (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte() }

            out.write(header)
            out.write(maskKey)
            out.write(masked)
            out.flush()
        } catch (e: Exception) {
            if (!closed.get()) logger.warn("发送帧失败：${e.message}")
        }
    }

    private fun readUShort(inp: InputStream): Int {
        val b = inp.readNBytes(2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    private fun readULong(inp: InputStream): Long {
        val b = inp.readNBytes(8)
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }
}
