package com.huhobot.penguin.qq

import com.huhobot.penguin.config.PenguinConfig
import org.slf4j.LoggerFactory
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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

/** 被 QQ 判定为「接口调用超过频率限制」后额外静默的时间，避免继续给限流器加压。 */
private const val RATE_LIMIT_COOLDOWN_MS = 60_000L

/** 同一种连接失败最多每隔多久打一条日志，其余折叠成计数。 */
private const val ERROR_LOG_INTERVAL_MS = 60_000L

/** 一次连接尝试的握手宽限期，watchdog 在此期间不再插入新的连接。 */
private const val CONNECT_GRACE_MS = 20_000L

/** QQ 网关会话失效类关闭码（4900~4913），需要丢弃 session 重新 Identify。 */
private val SESSION_INVALID_CODES = 4900..4913

private const val DEFAULT_TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken"
private const val DEFAULT_API_BASE = "https://api.bot.qq.com"

/** 用于日志折叠：同一类错误里 trace_id 每次都不同，归一化后才能识别为「同一条」。 */
private val TRACE_ID_REGEX = Regex(""",?"trace_id":"[0-9a-fA-F]*"""")

/**
 * QQ 开放平台 WebSocket 网关客户端，对齐 BDS 版 qqclient.js。
 * 使用 Java 标准库的 SSLSocket + 自实现 RFC6455 WebSocket 层（WsConnection）。
 *
 * [tokenUrl] / [apiBase] 仅为可测试性而暴露，生产环境使用默认的官方地址。
 */
class QQClient(
    private val cfg: PenguinConfig,
    private val tokenUrl: String = DEFAULT_TOKEN_URL,
    private val apiBase: String = DEFAULT_API_BASE
) {

    private val stopped = AtomicBoolean(true)
    private var groupMessageListener: ((GroupMessage) -> Unit)? = null

    // token 状态
    @Volatile private var accessToken: String? = null
    @Volatile private var tokenExpireAt: Long = 0
    @Volatile private var tokenRefreshing = false

    // 会话状态
    private var sessionId: String? = null
    private var lastSeq: Int? = null
    private var firstConnect = true
    private val reconnectAttempt = AtomicInteger(0)

    // 连接闸门：同一时刻只允许一条连接尝试在飞行中，否则失败重试会指数级堆积
    private val connecting = AtomicBoolean(false)
    @Volatile private var lastConnectStartedAt = 0L
    @Volatile private var rateLimitedUntil = 0L

    // 连接失败日志折叠
    private val errorLogLock = Any()
    private var lastErrorKey: String? = null
    private var lastErrorLoggedAt = 0L
    private var suppressedErrorCount = 0

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
        reconnectAttempt.set(0)
        rateLimitedUntil = 0
        connecting.set(false)
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
        // 这里保留「先关后清」：stopped 已为 true，onSocketClose 不会再触发重连，
        // 但仍会打出一条正常的关闭日志。
        wsConn?.close(1000, "shutdown")
        wsConn = null
        connecting.set(false)
    }

    /**
     * 独立 watchdog，完全不依赖 MC 服务器线程或时钟。每 30s 检测一次。
     *
     * 注意：这里只能「唤醒」连接闸门，绝不能无条件新建连接链——旧实现每 30s
     * 无脑 connectAsync(0)，断线期间每个周期都会多留下一条永不退出的重试链，
     * 请求速率随在线时长线性增长，最终把 QQ 接口打到频率限制。
     */
    private fun startWatchdog() {
        watchdogThread?.interrupt()
        watchdogThread = Thread {
            try {
                while (!Thread.interrupted() && !stopped.get()) {
                    Thread.sleep(30_000L)
                    if (stopped.get()) break
                    val idle = System.currentTimeMillis() - lastConnectStartedAt > CONNECT_GRACE_MS
                    if (wsConn?.isConnected() != true && !connecting.get() && idle) {
                        if (cfg.debugLogEvents) logger.info("Watchdog：连接已断开，触发重连…")
                        connectAsync(0)
                    }
                }
            } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.name = "penguin-watchdog" }
        watchdogThread!!.start()
    }

    // ---- 连接流程 ----

    /**
     * 发起一次连接尝试。
     *
     * 通过 [connecting] 闸门保证同一时刻只有一条连接链在飞行中：任何在此期间
     * 到来的重连请求（socket 关闭、watchdog、op9）都会被直接丢弃，而不是各自
     * 再起一条线程。旧实现没有这道闸门，每次失败都会派生新链且从不回收。
     */
    private fun connectAsync(delayMs: Long) {
        if (stopped.get()) return
        if (!connecting.compareAndSet(false, true)) {
            if (cfg.debugLogEvents) logger.info("已有连接尝试在进行中，忽略本次重连请求")
            return
        }
        lastConnectStartedAt = System.currentTimeMillis()
        Thread {
            var failure: Exception? = null
            try {
                // 命中频率限制时，无论调用方给的延迟多短，都必须等冷却期结束
                val cooldown = rateLimitedUntil - System.currentTimeMillis()
                val wait = maxOf(delayMs, cooldown)
                if (wait > 0) Thread.sleep(wait)
                if (!stopped.get()) {
                    lastConnectStartedAt = System.currentTimeMillis()
                    doConnect()
                }
            } catch (e: Exception) {
                failure = e
            } finally {
                // 先放开闸门再安排重试，否则下面的 scheduleReconnect 会被自己挡掉
                connecting.set(false)
            }
            if (failure != null && !stopped.get()) {
                noteRateLimited(failure)
                logConnectFailure(failure)
                scheduleReconnect()
            }
        }.also { it.isDaemon = true; it.name = "penguin-connect" }.start()
    }

    private fun doConnect() {
        // 使用缓存的token，避免频繁刷新触发API限制
        val token = getAccessTokenSync()
        if (cfg.debugLogEvents) logger.info("QQ 网关：正式环境 $apiBase")

        val gatewayUrl = fetchGatewayUrl(token)
        if (gatewayUrl.isNullOrBlank()) throw IllegalStateException("网关地址为空")
        if (cfg.debugLogEvents) logger.info("网关地址：$gatewayUrl")

        openSocket(gatewayUrl, token)
    }

    private fun openSocket(url: String, token: String) {
        // 必须先摘掉 wsConn 再关闭旧连接：否则 onSocketClose 里的
        // `conn !== wsConn` 判定失效，会把我们自己的主动关闭当成意外掉线，
        // 打一条 WARN 并再安排一次重连（旧版本每天因此空转上万次）。
        val previous = wsConn
        wsConn = null
        previous?.close(1000, "reconnect")

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
                logger.warn("收到 Invalid Session（d=$d），丢弃会话并重新 Identify")
                // Resume 不可靠时重新 Identify 总是安全的，直接丢弃会话
                sessionId = null
                firstConnect = true
                // 关闭连接本身就会走 onSocketClose -> scheduleReconnect，
                // 这里不能再额外排一次，否则同一个事件会排出两条重连链。
                conn.close(1000, "invalid-session")
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
        if (code in SESSION_INVALID_CODES) {
            // 4900~4913：QQ 侧认为会话已失效，Resume 只会被再次拒绝，下次必须重新 Identify
            sessionId = null
            firstConnect = true
        }
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
                onConnectionEstablished()
                logger.info("QQ 机器人已连接（session_id=$sessionId）")
            }
            "RESUMED" -> {
                firstConnect = false
                onConnectionEstablished()
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

    /**
     * 只有真正握手成功（READY / RESUMED）才算连上，此时清空退避计数与限流冷却。
     *
     * 旧实现在 doConnect() 里「拿到网关地址」就把计数清零，于是握手一直失败也
     * 会不停把退避重置回 1 秒，指数退避形同虚设。
     */
    private fun onConnectionEstablished() {
        reconnectAttempt.set(0)
        rateLimitedUntil = 0
        synchronized(errorLogLock) {
            if (suppressedErrorCount > 0) {
                logger.info("连接已恢复（此前折叠了 $suppressedErrorCount 条重复错误日志）")
            }
            suppressedErrorCount = 0
            lastErrorKey = null
        }
    }

    /** 作废本地 token 缓存，下一次取用会强制向 QQ 重新申请。 */
    @Synchronized
    private fun invalidateToken() {
        accessToken = null
        tokenExpireAt = 0
    }

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
        val resp = postJson(tokenUrl, body, emptyMap())
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
                    "$apiBase/v2/groups/$id/messages",
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
                "$apiBase/v2/groups/$id/messages",
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
            "$apiBase/v2/groups/$id/files",
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
        val attempt = reconnectAttempt.getAndIncrement()
        val backoff = minOf(MAX_RECONNECT_DELAY, 1000L * (1L shl minOf(attempt, 5)))
        val wait = delay ?: (backoff + (Math.random() * 500).toLong())
        if (cfg.debugLogEvents) logger.info("${wait}ms 后重连网关（第 ${attempt + 1} 次）…")
        connectAsync(wait)
    }

    /**
     * 命中 QQ 的「接口调用超过频率限制」后，进入固定冷却期。
     *
     * 指数退避的上限是 30s，但限流一旦触发，继续按 30s 敲门只会让限流器一直
     * 处于触发状态；这里额外静默 [RATE_LIMIT_COOLDOWN_MS]，给限流窗口留出恢复时间。
     */
    private fun noteRateLimited(e: Exception) {
        val msg = e.message ?: return
        val limited = msg.contains("频率限制") || msg.contains("100017") ||
            msg.startsWith("HTTP 429")
        if (limited) {
            rateLimitedUntil = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
        }
    }

    /**
     * 连接失败日志折叠：同一种错误每 [ERROR_LOG_INTERVAL_MS] 只打一条，
     * 其余累计成计数。旧实现每次失败都打一条 ERROR，断线期间单个日志文件
     * 一天能涨到 250MB 以上。
     */
    private fun logConnectFailure(e: Exception) {
        val raw = e.message ?: e.javaClass.simpleName
        // trace_id 每次都不同，归一化后才能判定是不是「同一种错误」
        val key = TRACE_ID_REGEX.replace(raw, "")
        val now = System.currentTimeMillis()
        var suppressed = -1
        synchronized(errorLogLock) {
            if (key == lastErrorKey && now - lastErrorLoggedAt < ERROR_LOG_INTERVAL_MS) {
                suppressedErrorCount++
            } else {
                suppressed = suppressedErrorCount
                suppressedErrorCount = 0
                lastErrorKey = key
                lastErrorLoggedAt = now
            }
        }
        if (suppressed < 0) return
        if (suppressed > 0) logger.error("连接失败：$raw（另有 $suppressed 条同类错误已折叠）")
        else logger.error("连接失败：$raw")
    }

    // ---- HTTP / JSON 工具 ----

    private fun fetchGatewayUrl(token: String): String? {
        val resp = getJson(
            "$apiBase/gateway",
            mapOf("Authorization" to "QQBot $token", "X-Union-Appid" to cfg.botAppId)
        )
        return resp["url"] as? String
    }

    private fun getJson(url: String, headers: Map<String, String>): Map<String, Any?> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader(StandardCharsets.UTF_8).readText()
        // 关键修复：GET 也必须在 401 时作废 token。
        // /gateway 走的是这里，旧实现只在 postJson 里处理 401，于是 QQ 提前
        // 作废 token 后，每次重连都拿同一个死 token 去换网关地址，只能等本地
        // 7200s TTL 自然到期，期间重试链不断堆积直到被限流。
        if (code == 401) invalidateToken()
        if (code !in 200..299) throw RuntimeException("HTTP $code：${body.take(300)}")
        return parseJson(body)
    }

    private fun postJson(url: String, body: String, headers: Map<String, String>): Map<String, Any?> {
        val conn = URL(url).openConnection() as HttpURLConnection
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
        // token 过期，清除缓存强制下次重新获取
        if (code == 401) invalidateToken()
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
