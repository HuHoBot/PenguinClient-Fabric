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

/** 成员进群事件。 */
data class GroupMemberJoin(
    val groupId: String,
    val userId: String,
    val username: String?
)

/** 成员退群事件。 */
data class GroupMemberLeave(
    val groupId: String,
    val userId: String,
    val username: String?
)

/** 入群申请事件。 */
data class GroupJoinRequest(
    val groupId: String,
    val userId: String,
    val username: String?,
    val applyAt: String?,
    val riskTips: String?
)

/**
 * 互动事件（面板按钮、快捷菜单）。
 *
 * [data] 是 `data.resolved` 原始内容，按钮回调参数都在里面：
 * `button_id` / `button_data` / `feature_id` / `message_id`。
 */
data class InteractionEvent(
    val id: String,
    /** 11 = 消息按钮，12 = 单聊快捷菜单。 */
    val type: Int,
    /** c2c / group / guild。 */
    val scene: String?,
    /** 0 = 频道，1 = 群聊，2 = 单聊。 */
    val chatType: Int?,
    val groupId: String?,
    val userId: String?,
    val data: Map<*, *>
)

/** 互动事件响应码，取值对齐官方文档。 */
object InteractionCode {
    /** 操作成功。 */
    const val SUCCESS = 0

    /** 操作失败。 */
    const val FAILED = 1

    /** 操作过于频繁，稍后再试。 */
    const val TOO_FREQUENT = 2

    /** 重复操作。 */
    const val DUPLICATE = 3

    /** 没有权限。 */
    const val NO_PERMISSION = 4

    /** 仅管理员可操作。 */
    const val ADMIN_ONLY = 5
}

private const val OP_DISPATCH = 0
private const val OP_HEARTBEAT = 1
private const val OP_IDENTIFY = 2
private const val OP_RESUME = 6
private const val OP_RECONNECT = 7
private const val OP_HELLO = 10
private const val OP_HEARTBEAT_ACK = 11
private const val OP_INVALID_SESSION = 9

/**
 * Intents 位定义，取值对齐 qqpd-bot-java 的 `Intents`。
 *
 * 官方要求按需订阅：多勾一位不会多收事件，但没勾就永远收不到。
 */
object Intents {
    /** 群成员进退群 + 入群申请。申请还要求机器人是群管理员。 */
    const val GROUP_MEMBER_EVENT = 1 shl 24

    /** 群消息 / 群事件基础位。 */
    const val GROUP = 1 shl 25

    /** 互动事件（面板按钮、快捷菜单）。 */
    const val INTERACTION = 1 shl 26

    /** 默认开启：群消息 + 群成员事件 + 互动事件。 */
    const val DEFAULT_GROUP = GROUP or GROUP_MEMBER_EVENT or INTERACTION
}

private const val TOKEN_REFRESH_LEAD = 60_000L
private const val MAX_RECONNECT_DELAY = 30_000L
private const val SEND_GAP_MS = 500L

/** QQ Markdown 键盘限制：单条消息最多 5 个按钮，按钮 id/label 也有长度上限。 */
private const val MAX_BUTTONS = 5
private const val MAX_BUTTON_ID = 10
private const val MAX_BUTTON_LABEL = 12

/**
 * QQ 开放平台 WebSocket 网关客户端，对齐 BDS 版 qqclient.js。
 * 使用 Java 标准库的 SSLSocket + 自实现 RFC6455 WebSocket 层（WsConnection）。
 */
class QQClient(private val cfg: PenguinConfig) {

    private val stopped = AtomicBoolean(true)
    private var groupMessageListener: ((GroupMessage) -> Unit)? = null
    private var memberJoinListener: ((GroupMemberJoin) -> Unit)? = null
    private var memberLeaveListener: ((GroupMemberLeave) -> Unit)? = null
    private var joinRequestListener: ((GroupJoinRequest) -> Unit)? = null
    private var interactionListener: ((InteractionEvent) -> Unit)? = null

    /**
     * 每条消息的 seq 序号。QQ 要求带msg_id 回复时 msg_seq 严格递增，
     * 否则第二次回复会被判为重复消息丢弃。
     */
    private val msgSeq = java.util.concurrent.atomic.AtomicInteger(1)

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

    fun onMemberJoin(listener: (GroupMemberJoin) -> Unit) {
        memberJoinListener = listener
    }

    fun onMemberLeave(listener: (GroupMemberLeave) -> Unit) {
        memberLeaveListener = listener
    }

    fun onJoinRequest(listener: (GroupJoinRequest) -> Unit) {
        joinRequestListener = listener
    }

    fun onInteraction(listener: (InteractionEvent) -> Unit) {
        interactionListener = listener
    }

    /** 群管理 REST 客户端，token 由本类提供。 */
    fun groupApi(): GroupApi = GroupApi(cfg, { getAccessTokenSync() })

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
            val intents = cfg.qqIntents
            if (cfg.debugLogEvents) {
                logger.info("Identify intents=$intents（群消息/群成员/互动按配置订阅）")
            }
            conn.send("""{"op":$OP_IDENTIFY,"d":{"token":"QQBot $token","intents":$intents,"shard":[0,1],"properties":{"${'$'}os":"$platform","${'$'}browser":"${cfg.botName} (Fabric)","${'$'}device":"${cfg.botName} (Fabric)"}}}""")
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
            "GROUP_MEMBER_ADD" -> {
                val d = payload["d"] as? Map<*, *> ?: return
                val groupId = d["group_openid"] as? String ?: return
                val user = d["user"] as? Map<*, *> ?: return
                val event = GroupMemberJoin(
                    groupId = groupId,
                    userId = user["id"] as? String ?: return,
                    username = user["username"] as? String
                )
                if (cfg.debugLogEvents) logger.info("收到进群事件：${event.groupId} ${event.userId}")
                memberJoinListener?.invoke(event)
            }
            "GROUP_MEMBER_REMOVE" -> {
                val d = payload["d"] as? Map<*, *> ?: return
                val groupId = d["group_openid"] as? String ?: return
                val user = d["user"] as? Map<*, *> ?: return
                val event = GroupMemberLeave(
                    groupId = groupId,
                    userId = user["id"] as? String ?: return,
                    username = user["username"] as? String
                )
                if (cfg.debugLogEvents) logger.info("收到退群事件：${event.groupId} ${event.userId}")
                memberLeaveListener?.invoke(event)
            }
            "GROUP_JOIN_REQUEST" -> {
                val d = payload["d"] as? Map<*, *> ?: return
                val groupId = d["group_openid"] as? String ?: return
                val user = d["user"] as? Map<*, *> ?: return
                val event = GroupJoinRequest(
                    groupId = groupId,
                    userId = user["id"] as? String ?: return,
                    username = user["username"] as? String,
                    applyAt = d["apply_time"] as? String,
                    riskTips = d["risk_tips"] as? String
                )
                if (cfg.debugLogEvents) logger.info("收到入群申请：${event.groupId} ${event.userId}")
                joinRequestListener?.invoke(event)
            }
            "INTERACTION_CREATE" -> {
                val d = payload["d"] as? Map<*, *> ?: return
                val event = InteractionEvent(
                    id = d["id"] as? String ?: return,
                    type = (d["type"] as? Number)?.toInt() ?: 0,
                    scene = d["scene"] as? String,
                    chatType = (d["chat_type"] as? Number)?.toInt(),
                    groupId = d["group_openid"] as? String,
                    userId = (d["group_member_openid"] as? String) ?: (d["user_openid"] as? String),
                    data = d["data"] as? Map<*, *> ?: emptyMap<Any, Any>()
                )
                if (cfg.debugLogEvents) {
                    logger.info("收到互动事件：id=${event.id} type=${event.type} group=${event.groupId}")
                }
                interactionListener?.invoke(event)
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
                doSendGroupMessage(groupId, content, msgId, 0, keyboard = null)
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
                doSendGroupMessage(groupId, markdownContent, msgId, 2, keyboard = null)
                if (cfg.debugLogEvents)
                    logger.info("群 Markdown 已发送 group=$groupId")
            } catch (e: Exception) {
                logger.error("群 Markdown 发送失败 group=$groupId：${e.message}")
            }
        }
    }

    /**
     * 发送带按钮的 Markdown 消息。
     *
     * [buttons] 最多 5 个，每个 `id` 在同一按钮行内唯一；[rowIndex] 从 1 开始。
     * 用户点击后 QQ 会下发 `INTERACTION_CREATE`，[resolved.button_id] 即这里设的 id。
     */
    fun sendMarkdownWithButtons(
        groupId: String,
        markdownContent: String,
        buttons: List<Pair<String, String>>,
        rowIndex: Int = 1,
        msgId: String? = null
    ) {
        if (buttons.isEmpty()) { sendMarkdown(groupId, markdownContent, msgId); return }
        if (buttons.size > MAX_BUTTONS) {
            logger.warn("按钮数 ${buttons.size} 超过上限 $MAX_BUTTONS，已截断")
        }
        val keyboard = mapOf(
            "rows" to listOf(
                mapOf(
                    "buttons" to buttons.take(MAX_BUTTONS).map { (id, label) ->
                        mapOf("id" to id.take(MAX_BUTTON_ID), "label" to label.take(MAX_BUTTON_LABEL))
                    }
                )
            )
        )
        enqueue {
            try {
                doSendGroupMessage(groupId, markdownContent, msgId, 2, keyboard, rowIndex.coerceAtLeast(1))
                if (cfg.debugLogEvents) logger.info("群按钮 Markdown 已发送 group=$groupId")
            } catch (e: Exception) {
                logger.error("群按钮 Markdown 发送失败 group=$groupId：${e.message}")
            }
        }
    }

    /**
     * 撤回自己发过的消息。
     *
     * QQ 只允许撤回机器人自己在 2 分钟内发出的消息，超时返回错误码。
     */
    fun recallMessage(groupId: String, messageId: String) {
        enqueue {
            try {
                deleteMessage(groupId, messageId)
                if (cfg.debugLogEvents) logger.info("已撤回消息 group=$groupId msg=$messageId")
            } catch (e: Exception) {
                logger.error("撤回消息失败 group=$groupId：${e.message}")
            }
        }
    }

    /**
     * 响应互动事件。必须在收到事件后 5 秒内调用，否则 QQ 判定为无响应。
     *
     * 立即返回是否投递成功（不含 HTTP 结果）；需要确认结果用 [respondInteractionNow]。
     */
    fun respondInteraction(interactionId: String, code: Int = InteractionCode.SUCCESS): Boolean =
        enqueue {
            try {
                putInteraction(interactionId, code)
            } catch (e: Exception) {
                logger.warn("互动事件响应失败 id=$interactionId code=$code：${e.message}")
            }
        }

    /** 同步版本的互动响应，供命令处理等需要确认结果的场景使用。 */
    fun respondInteractionNow(interactionId: String, code: Int = InteractionCode.SUCCESS): Boolean =
        try {
            putInteraction(interactionId, code)
            true
        } catch (e: Exception) {
            logger.warn("互动事件响应失败 id=$interactionId code=$code：${e.message}")
            false
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

    private fun doSendGroupMessage(
        groupId: String,
        content: String,
        msgId: String?,
        msgType: Int,
        keyboard: Map<String, Any>?,
        rowIndex: Int = 1
    ) {
        val id = java.net.URLEncoder.encode(groupId, "UTF-8")
        val bodyMap = mutableMapOf<String, Any>("msg_type" to msgType)
        if (msgType == 2) {
            // markdown 也可能是只读 map（无按钮时用 mapOf），所以统一建成可变再填
            val markdown = mutableMapOf<String, Any>("content" to content)
            if (keyboard != null) {
                markdown["keyboard"] = mapOf("id" to rowIndex)
                bodyMap["keyboard"] = keyboard
            }
            bodyMap["markdown"] = markdown
        } else {
            bodyMap["content"] = content
            keyboard?.let { bodyMap["keyboard"] = it }
        }
        if (msgId != null) {
            bodyMap["msg_id"] = msgId
            // 带 msg_id 回复时必须带 seq，否则连续两次回复会被 QQ 判为重复
            bodyMap["msg_seq"] = msgSeq.getAndIncrement()
        }

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

    private fun enqueue(task: () -> Unit): Boolean {
        if (!sendQueue.offer(task)) {
            logger.warn("发消息队列已满，丢弃一条")
            return false
        }
        return true
    }

    // ---- 撤回 / 互动响应 ----

    private fun deleteMessage(groupId: String, messageId: String) {
        val url = "https://api.bot.qq.com/v2/groups/${java.net.URLEncoder.encode(groupId, "UTF-8")}" +
            "/messages/${java.net.URLEncoder.encode(messageId, "UTF-8")}"
        requestWithToken("DELETE", url, null)
    }

    private fun putInteraction(interactionId: String, code: Int) {
        val url = "https://api.bot.qq.com/interactions/${java.net.URLEncoder.encode(interactionId, "UTF-8")}"
        requestWithToken("PUT", url, mapOf("code" to code))
    }

    private fun requestWithToken(method: String, url: String, body: Map<String, Any?>?): Map<String, Any?> {
        val token = getAccessTokenSync()
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Authorization", "QQBot $token")
        conn.setRequestProperty("X-Union-Appid", cfg.botAppId)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
                it.write(jsonStringify(body))
            }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(StandardCharsets.UTF_8)?.readText().orEmpty()
        if (code == 401) invalidateToken()
        if (code !in 200..299) throw RuntimeException("HTTP $code：${text.take(200)}")
        if (text.isBlank()) return emptyMap()
        return parseJson(text)
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
