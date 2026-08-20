package com.huhobot.penguin.qq

import com.huhobot.penguin.config.PenguinConfig
import org.slf4j.LoggerFactory
import java.io.OutputStreamWriter
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection

private val logger = LoggerFactory.getLogger("PenguinServer-Fabric/QQClient")

/** 群消息数据类，对齐 BDS 版 qqclient.js 的 message 对象。 */
data class GroupMessage(
    val id: String,
    val groupId: String,
    val content: String,
    val userId: String,
    val username: String?,
    val memberRole: String?,
    val timestamp: String?,
    val attachments: List<Map<*, *>>? = null  // 附件列表（图片等）
)

private const val OP_DISPATCH = 0
private const val OP_HEARTBEAT = 1
private const val OP_IDENTIFY = 2
private const val OP_RESUME = 6
private const val OP_RECONNECT = 7
private const val OP_HELLO = 10
private const val OP_HEARTBEAT_ACK = 11
private const val OP_INVALID_SESSION = 9

private const val INTENTS_GROUP = 1 shl 25
private const val TOKEN_REFRESH_LEAD = 60_000L
private const val MAX_RECONNECT_DELAY = 30_000L
private const val SEND_GAP_MS = 500L

/**
 * QQ 开放平台 WebSocket 网关客户端，对齐 BDS 版 qqclient.js。
 * 使用 Java 标准库的 SSLSocket + 自实现 RFC6455 WebSocket 层（WsConnection）。
 */
class QQClient(private val cfg: PenguinConfig) {

    private val stopped = AtomicBoolean(true)
    private var groupMessageListener: ((GroupMessage) -> Unit)? = null

    // token 状态
    private var accessToken: String? = null
    private var tokenExpireAt: Long = 0
    @Volatile private var tokenRefreshing = false

    // 会话状态
    private var sessionId: String? = null
    private var lastSeq: Int? = null
    private var firstConnect = true
    private var reconnectAttempt = 0

    // WebSocket 连接
    @Volatile private var wsConn: WsConnection? = null

    // 心跳
    private var heartbeatInterval = 41_250L
    @Volatile private var heartbeatThread: Thread? = null
    @Volatile private var lastAckTime = AtomicLong(0)

    // 发消息串行队列
    private val sendQueue = LinkedBlockingQueue<() -> Unit>(256)
    private var sendThread: Thread? = null

    // 独立 watchdog 线程：每 60 秒用系统时钟检测连接状态，断线自动重连
    @Volatile private var watchdogThread: Thread? = null

    // 去重
    private val recentIds = ArrayDeque<String>(200)

    fun onGroupMessage(listener: (GroupMessage) -> Unit) {
        groupMessageListener = listener
    }

    fun start() {
        stopped.set(false)
        firstConnect = true
        startSendThread()
        startWatchdog()
        connectAsync(0)
    }

    fun stop() {
        stopped.set(true)
        watchdogThread?.interrupt()
        watchdogThread = null
        heartbeatThread?.interrupt()
        heartbeatThread = null
        sendThread?.interrupt()
        sendThread = null
        wsConn?.close(1000, "shutdown")
        wsConn = null
    }

    /** 独立 watchdog，完全不依赖 MC 服务器线程或时钟。每 30s 检测一次，断线立即重连并刷新 token。 */
    private fun startWatchdog() {
        watchdogThread?.interrupt()
        watchdogThread = Thread {
            try {
                while (!Thread.interrupted() && !stopped.get()) {
                    Thread.sleep(30_000L)
                    if (stopped.get()) break
                    if (wsConn?.isConnected() != true) {
                        if (cfg.debugLogEvents) logger.info("Watchdog：连接已断开，刷新 token 并重连…")
                        connectAsync(0)
                    }
                }
            } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.name = "penguin-watchdog" }
        watchdogThread!!.start()
    }

    // ---- 连接流程 ----

    private fun connectAsync(delayMs: Long) {
        if (stopped.get()) return
        Thread {
            if (delayMs > 0) Thread.sleep(delayMs)
            if (stopped.get()) return@Thread
            try {
                doConnect()
            } catch (e: Exception) {
                if (!stopped.get()) {
                    logger.error("连接失败：${e.message}")
                    scheduleReconnect()
                }
            }
        }.also { it.isDaemon = true; it.name = "penguin-connect" }.start()
    }

    private fun doConnect() {
        // 使用缓存的token，避免频繁刷新触发API限制
        val token = getAccessTokenSync()
        if (cfg.debugLogEvents) logger.info("QQ 网关：正式环境 api.bot.qq.com")

        val gatewayUrl = fetchGatewayUrl(token)
        if (gatewayUrl.isNullOrBlank()) throw IllegalStateException("网关地址为空")
        if (cfg.debugLogEvents) logger.info("网关地址：$gatewayUrl")

        reconnectAttempt = 0
        openSocket(gatewayUrl, token)
    }

    private fun openSocket(url: String, token: String) {
        wsConn?.close(1000, "reconnect")

        val conn = WsConnection(url, mapOf(
            "Authorization" to "QQBot $token",
            "X-Union-Appid" to cfg.botAppId
        ))
        wsConn = conn

        conn.onOpen {
            onSocketOpen(conn)
        }
        conn.onMessage { data ->
            onSocketMessage(conn, data)
        }
        conn.onClose { code, reason ->
            onSocketClose(conn, code, reason)
        }
        conn.onError { err ->
            logger.error("网关 socket 错误：${err.message}")
        }

        conn.connect()
    }

    private fun onSocketOpen(conn: WsConnection) {
        if (conn !== wsConn) return
        lastAckTime.set(System.currentTimeMillis())

        val token = try { getAccessTokenSync() } catch (e: Exception) {
            logger.error("握手时获取 token 失败：${e.message}")
            conn.close(1000, "no-token")
            return
        }

        if (sessionId != null && !firstConnect) {
            if (cfg.debugLogEvents) logger.info("尝试 Resume 已断开会话…")
            conn.send("""{"op":$OP_RESUME,"d":{"token":"QQBot $token","session_id":"${sessionId}","seq":${lastSeq ?: "null"}}}""")
        } else {
            val platform = System.getProperty("os.name", "unknown")
            conn.send("""{"op":$OP_IDENTIFY,"d":{"token":"QQBot $token","intents":$INTENTS_GROUP,"shard":[0,1],"properties":{"${'$'}os":"$platform","${'$'}browser":"${cfg.botName} (Fabric)","${'$'}device":"${cfg.botName} (Fabric)"}}}""")
        }
    }

    private fun onSocketMessage(conn: WsConnection, data: String) {
        if (conn !== wsConn) return
        val payload = try { parseJson(data) } catch (e: Exception) {
            logger.warn("收到非法 JSON 帧：${data.take(200)}")
            return
        }

        when (payload["op"]?.let { (it as? Number)?.toInt() }) {
            OP_HELLO -> {
                val d = payload["d"] as? Map<*, *>
                heartbeatInterval = (d?.get("heartbeat_interval") as? Number)?.toLong() ?: 41_250L
                lastAckTime.set(System.currentTimeMillis())
                startHeartbeat(conn)
            }
            OP_HEARTBEAT_ACK -> lastAckTime.set(System.currentTimeMillis())
            OP_INVALID_SESSION -> {
                val d = payload["d"]
                logger.warn("收到 Invalid Session（d=$d），重建会话")
                if (d == true) sessionId = null
                conn.close(1000, "invalid-session")
                scheduleReconnect(0)
            }
            OP_RECONNECT -> {
                logger.warn("服务端要求重连（op7）")
                conn.close(4000, "server-reconnect")
            }
            OP_DISPATCH -> onDispatch(payload)
        }
    }

    private fun onSocketClose(conn: WsConnection, code: Int, reason: String) {
        if (conn !== wsConn) return
        heartbeatThread?.interrupt()
        heartbeatThread = null
        logger.warn("网关连接断开：code=$code reason=$reason")
        if (!stopped.get()) scheduleReconnect()
    }

    // ---- 心跳 ----

    private fun startHeartbeat(conn: WsConnection) {
        heartbeatThread?.interrupt()
        val interval = heartbeatInterval
        heartbeatThread = Thread {
            try {
                while (!Thread.interrupted() && !stopped.get() && conn === wsConn) {
                    Thread.sleep(interval)
                    if (conn !== wsConn || stopped.get()) break
                    // 心跳 ACK 超时检测
                    if (System.currentTimeMillis() - lastAckTime.get() > interval * 2 + 5000) {
                        logger.warn("心跳超时，主动断开重连")
                        conn.close(1000, "heartbeat-timeout")
                        break
                    }
                    conn.send("""{"op":$OP_HEARTBEAT,"d":${lastSeq ?: "null"}}""")
                }
            } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.name = "penguin-heartbeat" }
        heartbeatThread!!.start()
    }

    // ---- Dispatch ----

    private fun onDispatch(payload: Map<String, Any?>) {
        lastSeq = (payload["s"] as? Number)?.toInt()
        val t = payload["t"] as? String ?: return

        if (cfg.debugLogEvents) {
            logger.info("收到 Dispatch：t=$t 前200=${jsonStringify(payload).take(200)}")
        }

        when (t) {
            "READY" -> {
                val d = payload["d"] as? Map<*, *>
                sessionId = d?.get("session_id") as? String
                firstConnect = false
                reconnectAttempt = 0
                logger.info("QQ 机器人已连接（session_id=$sessionId）")
            }
            "RESUMED" -> {
                firstConnect = false
                if (cfg.debugLogEvents) logger.info("Resume 成功，会话已恢复")
            }
            "GROUP_AT_MESSAGE_CREATE", "GROUP_MESSAGE_CREATE" -> {
                val d = payload["d"] as? Map<*, *> ?: return
                onGroupMessage(d)
            }
        }
    }

    private fun onGroupMessage(d: Map<*, *>) {
        val id = d["id"] as? String ?: run {
            logger.warn("群消息事件缺少 id 字段")
            return
        }
        val groupId = d["group_openid"] as? String ?: run {
            logger.warn("群消息事件缺少 group_openid 字段")
            return
        }

        // 去重
        synchronized(recentIds) {
            if (recentIds.contains(id)) return
            recentIds.addLast(id)
            if (recentIds.size > 200) recentIds.removeFirst()
        }

        val author = d["author"] as? Map<*, *>
        val message = GroupMessage(
            id = id,
            groupId = groupId,
            content = (d["content"] as? String) ?: "",
            userId = (author?.get("id") as? String) ?: "",
            username = author?.get("username") as? String,
            memberRole = author?.get("member_role") as? String,
            timestamp = d["timestamp"] as? String,
            attachments = d["attachments"] as? List<Map<*, *>>
        )

        if (cfg.debugLogEvents) {
            logger.info("收到群消息：group=$groupId user=${message.userId} content=${message.content.take(100)}")
        }

        groupMessageListener?.invoke(message)
    }

    // ---- access_token ----

    @Synchronized
    fun getAccessTokenSync(): String {
        val now = System.currentTimeMillis()
        val cached = accessToken
        if (cached != null && now < tokenExpireAt - TOKEN_REFRESH_LEAD) return cached
        return refreshToken()
    }

    private fun refreshToken(): String {
        if (cfg.debugLogEvents) logger.info("正在获取 access_token…")
        val body = """{"appId":"${cfg.botAppId}","clientSecret":"${cfg.botSecret}"}"""
        val resp = postJson("https://bots.qq.com/app/getAppAccessToken", body, emptyMap())
        val token = resp["access_token"] as? String
            ?: throw IllegalStateException("token 接口未返回 access_token：${jsonStringify(resp)}")
        val expiresIn = ((resp["expires_in"] as? Number)?.toLong() ?: 7200L).let {
            if (it > 0) it else 7200L
        }
        accessToken = token
        tokenExpireAt = System.currentTimeMillis() + expiresIn * 1000L
        return token
    }

    // ---- 发消息 ----

    fun sendGroupMessage(groupId: String, content: String, msgId: String? = null) {
        enqueue {
            try {
                doSendGroupMessage(groupId, content, msgId, 0)
                if (cfg.debugLogEvents)
                    logger.info("群消息已发送 group=$groupId content=${content.take(100)}")
            } catch (e: Exception) {
                logger.error("群消息发送失败 group=$groupId：${e.message}")
            }
        }
    }

    fun sendMarkdown(groupId: String, markdownContent: String, msgId: String? = null) {
        enqueue {
            try {
                doSendGroupMessage(groupId, markdownContent, msgId, 2)
                if (cfg.debugLogEvents)
                    logger.info("群 Markdown 已发送 group=$groupId")
            } catch (e: Exception) {
                logger.error("群 Markdown 发送失败 group=$groupId：${e.message}")
            }
        }
    }

    fun sendGroupMessageWithImage(groupId: String, text: String, imgUrl: String, msgId: String? = null) {
        enqueue {
            try {
                doSendGroupMessageWithImage(groupId, text, imgUrl, msgId)
                if (cfg.debugLogEvents)
                    logger.info("群消息（带图片）已发送 group=$groupId imgUrl=$imgUrl")
            } catch (e: Exception) {
                logger.error("群消息（带图片）发送失败 group=$groupId：${e.message}")
            }
        }
    }

    private fun doSendGroupMessage(groupId: String, content: String, msgId: String?, msgType: Int) {
        val id = java.net.URLEncoder.encode(groupId, "UTF-8")
        val bodyMap = mutableMapOf<String, Any>("msg_type" to msgType)
        if (msgType == 2) {
            bodyMap["markdown"] = mapOf("content" to content)
        } else {
            bodyMap["content"] = content
        }
        if (msgId != null) bodyMap["msg_id"] = msgId

        // 最多重试一次（token 过期时自动刷新后重发）
        repeat(2) { attempt ->
            val token = getAccessTokenSync()
            try {
                postJson(
                    "https://api.bot.qq.com/v2/groups/$id/messages",
                    jsonStringify(bodyMap),
                    mapOf(
                        "Authorization" to "QQBot $token",
                        "X-Union-Appid" to cfg.botAppId,
                        "Content-Type" to "application/json; charset=utf-8"
                    )
                )
                return
            } catch (e: RuntimeException) {
                if (attempt == 0 && e.message?.startsWith("HTTP 401") == true) {
                    // token 已被清除，下次循环会重新获取
                } else {
                    throw e
                }
            }
        }
    }

    private fun doSendGroupMessageWithImage(groupId: String, text: String, imgUrl: String, msgId: String?) {
        try {
            // 1. 先上传图片到 QQ 服务器
            val fileInfo = uploadImage(groupId, imgUrl)
            if (fileInfo == null) {
                logger.error("图片上传失败，未获取到 file_info")
                return
            }

            // 2. 用 file_info 发送富媒体消息
            val token = getAccessTokenSync()
            val id = java.net.URLEncoder.encode(groupId, "UTF-8")
            val bodyMap = mutableMapOf<String, Any>(
                "msg_type" to 7,  // 富媒体消息
                "content" to text,
                "media" to mapOf("file_info" to fileInfo)
            )
            if (msgId != null) bodyMap["msg_id"] = msgId

            postJson(
                "https://api.bot.qq.com/v2/groups/$id/messages",
                jsonStringify(bodyMap),
                mapOf(
                    "Authorization" to "QQBot $token",
                    "X-Union-Appid" to cfg.botAppId,
                    "Content-Type" to "application/json; charset=utf-8"
                )
            )
            if (cfg.debugLogEvents) logger.info("群富媒体消息已发送 group=$groupId")
        } catch (e: Exception) {
            logger.error("发送图片消息失败", e)
        }
    }

    /**
     * 上传图片到 QQ 服务器，返回 file_info。
     * 参考 SDK：https://github.com/HuHoBot/qqpd-bot-java FileMsg.java
     */
    private fun uploadImage(groupId: String, imgUrl: String): String? {
        if (cfg.debugLogEvents) logger.info("开始上传图片：url=$imgUrl")
        val token = getAccessTokenSync()
        val id = java.net.URLEncoder.encode(groupId, "UTF-8")

        val uploadBody = jsonStringify(mapOf(
            "file_type" to 1,
            "url" to imgUrl,
            "srv_send_msg" to false
        ))

        if (cfg.debugLogEvents) logger.info("上传请求：$uploadBody")
        val resp = postJson(
            "https://api.bot.qq.com/v2/groups/$id/files",
            uploadBody,
            mapOf(
                "Authorization" to "QQBot $token",
                "X-Union-Appid" to cfg.botAppId,
                "Content-Type" to "application/json; charset=utf-8"
            )
        )

        if (cfg.debugLogEvents) logger.info("上传响应：$resp")
        val fileInfo = resp["file_info"] as? String
        if (cfg.debugLogEvents) logger.info("提取到 file_info=$fileInfo")
        return fileInfo
    }

    private fun enqueue(task: () -> Unit) {
        if (!sendQueue.offer(task)) {
            logger.warn("发消息队列已满，丢弃一条")
        }
    }

    private fun startSendThread() {
        sendThread?.interrupt()
        sendThread = Thread {
            try {
                while (!Thread.interrupted() && !stopped.get()) {
                    val task = sendQueue.take()
                    task()
                    Thread.sleep(SEND_GAP_MS)
                }
            } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.name = "penguin-send" }
        sendThread!!.start()
    }

    // ---- 重连 ----

    private fun scheduleReconnect(delay: Long? = null) {
        if (stopped.get()) return
        val backoff = minOf(MAX_RECONNECT_DELAY, 1000L * (1L shl minOf(reconnectAttempt++, 5)))
        val wait = delay ?: (backoff + (Math.random() * 500).toLong())
        if (cfg.debugLogEvents) logger.info("${wait}ms 后重连网关…")
        connectAsync(wait)
    }

    // ---- HTTP / JSON 工具 ----

    private fun fetchGatewayUrl(token: String): String? {
        val resp = getJson(
            "https://api.bot.qq.com/gateway",
            mapOf("Authorization" to "QQBot $token", "X-Union-Appid" to cfg.botAppId)
        )
        return resp["url"] as? String
    }

    private fun getJson(url: String, headers: Map<String, String>): Map<String, Any?> {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader(StandardCharsets.UTF_8).readText()
        if (code !in 200..299) throw RuntimeException("HTTP $code：${body.take(300)}")
        return parseJson(body)
    }

    private fun postJson(url: String, body: String, headers: Map<String, String>): Map<String, Any?> {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader(StandardCharsets.UTF_8).readText()
        if (code == 401) {
            // token 过期，清除缓存强制下次重新获取
            accessToken = null
            tokenExpireAt = 0
        }
        if (code !in 200..299) throw RuntimeException("HTTP $code：${resp.take(300)}")
        return parseJson(resp)
    }

    // ---- 极简 JSON 解析（仅依赖 Gson，不引入额外依赖） ----

    @Suppress("UNCHECKED_CAST")
    private fun parseJson(json: String): Map<String, Any?> {
        val type = object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
        return com.google.gson.Gson().fromJson(json, type) ?: emptyMap()
    }

    private fun jsonStringify(value: Any?): String = com.google.gson.Gson().toJson(value)
}
