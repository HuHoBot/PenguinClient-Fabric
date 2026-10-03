package com.huhobot.penguin.qq

import com.huhobot.penguin.config.PenguinConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 发消息请求体回归测试。
 *
 * 关注三件容易回归的事：
 * 1. 带 msg_id 回复时必须带 msg_seq，且严格递增（缺了会被 QQ 判为重复消息丢弃）
 * 2. Markdown 按钮的 keyboard 结构（markdown.keyboard.id + 顶层 keyboard.rows）
 * 3. 撤回走 DELETE /v2/groups/{gid}/messages/{mid}
 *
 * 发消息是入队异步的，用例里等队列排空。
 */
class SendMessageBodyTest {

    private lateinit var api: FakeSendApi
    private lateinit var client: QQClient

    @BeforeEach
    fun setUp() {
        killPenguinThreads()
        api = FakeSendApi()
        client = QQClient(testConfig(), api.tokenUrl, api.apiBase)
        startSendWorker()
    }

    /**
     * 只启动消费队列的发送线程，不走 start()——start() 会顺带连网关，
     * 那样桩还得额外模拟 /gateway 与 WebSocket 握手，与本用例要测的东西无关。
     *
     * 注意必须先 stopped=false：发送线程的循环条件里带 !stopped.get()，
     * 而 stopped 的初值就是 true，不清掉的话线程会立刻退出，队列永远没人消费。
     */
    private fun startSendWorker() {
        val flag = QQClient::class.java.getDeclaredField("stopped").apply { isAccessible = true }
        (flag.get(client) as AtomicBoolean).set(false)
        val m = QQClient::class.java.getDeclaredMethod("startSendThread").apply { isAccessible = true }
        m.invoke(client)
    }

    @AfterEach
    fun tearDown() {
        client.stop()
        killPenguinThreads()
        api.close()
    }

    /** 发消息走 sendQueue + 500ms 间隔，等两条都落地。 */
    private fun awaitBodies(n: Int, seconds: Long = 10): List<String> {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (api.messageBodies.size >= n) break
            Thread.sleep(50)
        }
        return api.messageBodies.toList()
    }

    // ---- msg_seq ----

    @Test
    fun `带 msg_id 回复时下发递增的 msg_seq`() {
        client.sendGroupMessage("g1", "第一条", "m0")
        client.sendGroupMessage("g1", "第二条", "m0")
        val bodies = awaitBodies(2)

        assertEquals(2, bodies.size, "实际=${bodies}")
        val first = Regex("\"msg_seq\":(\\d+)").find(bodies[0])?.groupValues?.get(1)?.toInt()
        val second = Regex("\"msg_seq\":(\\d+)").find(bodies[1])?.groupValues?.get(1)?.toInt()
        assertTrue(first != null, "第一条应带 msg_seq，实际=${bodies[0]}")
        assertTrue(second != null, "第二条应带 msg_seq，实际=${bodies[1]}")
        assertTrue(second!! > first!!, "msg_seq 必须递增，实际 $first -> $second")
    }

    @Test
    fun `不带 msg_id 的推送不写 msg_seq`() {
        client.sendGroupMessage("g1", "广播")
        val bodies = awaitBodies(1)
        assertFalse(bodies[0].contains("msg_seq"), "无 msg_id 时不该有 msg_seq，实际=${bodies[0]}")
        assertFalse(bodies[0].contains("msg_id"), "无 msg_id 时不该有 msg_id，实际=${bodies[0]}")
    }

    @Test
    fun `回复原消息时带上报的 msg_id`() {
        client.sendGroupMessage("g1", "回复", "origin-123")
        val bodies = awaitBodies(1)
        assertTrue(bodies[0].contains("\"msg_id\":\"origin-123\""), "实际=${bodies[0]}")
    }

    // ---- Markdown 与按钮 ----

    @Test
    fun `Markdown 消息体为 msg_type 2 加 markdown content`() {
        client.sendMarkdown("g1", "# 标题")
        val bodies = awaitBodies(1)
        assertTrue(bodies[0].contains("\"msg_type\":2"), "实际=${bodies[0]}")
        assertTrue(bodies[0].contains("\"markdown\""), "实际=${bodies[0]}")
        assertTrue(bodies[0].contains("# 标题"), "实际=${bodies[0]}")
        assertFalse(bodies[0].contains("keyboard"), "无按钮时不该有 keyboard，实际=${bodies[0]}")
    }

    @Test
    fun `按钮 Markdown 同时下发 markdown keyboard id 与顶层 keyboard rows`() {
        client.sendMarkdownWithButtons("g1", "选一个", listOf("cmd:ok" to "确定", "cmd:no" to "取消"))
        val bodies = awaitBodies(1)
        val body = bodies[0]

        assertTrue(body.contains("\"markdown\":{\"content\":\"选一个\",\"keyboard\":{\"id\":1}}"),
            "markdown 内应嵌 keyboard.id，实际=$body")
        assertTrue(body.contains("\"rows\":[{\"buttons\":["), "应下发 keyboard.rows，实际=$body")
        assertTrue(body.contains("\"id\":\"cmd:ok\""), "实际=$body")
        assertTrue(body.contains("\"label\":\"确定\""), "实际=$body")
    }

    @Test
    fun `按钮超过 5 个时截断到 QQ 上限`() {
        client.sendMarkdownWithButtons("g1", "太多", (1..8).map { "b$it" to "B$it" })
        val bodies = awaitBodies(1)
        val count = Regex("\"id\":\"b\\d\"").findAll(bodies[0]).count()
        assertEquals(5, count, "QQ 最多 5 个按钮，实际=$count")
    }

    @Test
    fun `按钮列表为空时退化为普通 Markdown`() {
        client.sendMarkdownWithButtons("g1", "空按钮", emptyList())
        val bodies = awaitBodies(1)
        assertTrue(bodies[0].contains("\"msg_type\":2"), "实际=${bodies[0]}")
        assertFalse(bodies[0].contains("keyboard"), "空按钮不该有 keyboard，实际=${bodies[0]}")
    }

    // ---- 撤回 ----

    @Test
    fun `撤回走 DELETE 且路径含消息 id`() {
        client.recallMessage("g1", "m-999")
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && !api.deletePaths.contains("/v2/groups/g1/messages/m-999")) {
            Thread.sleep(50)
        }
        assertTrue(api.deletePaths.contains("/v2/groups/g1/messages/m-999"),
            "实际 DELETE 路径=${api.deletePaths}")
    }

    @Test
    fun `撤回失败只记日志不抛出`() {
        api.failDeleteWith = 400 to """{"code":40022009,"message":"消息超过2分钟无法撤回"}"""
        client.recallMessage("g1", "too-old")
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && api.deleteAttempts.get() == 0) {
            Thread.sleep(50)
        }
        assertTrue(api.deleteAttempts.get() > 0, "应尝试过撤回")
    }

    // ---- 辅助 ----

    private fun stopAllPenguinThreads() {
        Thread.getAllStackTraces().keys
            .filter { it.name.startsWith("penguin-") }
            .forEach { runCatching { it.interrupt() } }
    }

    private fun killPenguinThreads() {
        stopAllPenguinThreads()
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            val alive = Thread.getAllStackTraces().keys.count {
                (it.name == "penguin-send" || it.name == "penguin-connect") && it.isAlive
            }
            if (alive == 0) return
            Thread.sleep(50)
        }
    }

    private fun testConfig(): PenguinConfig {
        val ctor = PenguinConfig::class.java.declaredConstructors.first()
        ctor.isAccessible = true
        val flat = mutableMapOf<String, Any?>(
            "bot.app-id" to "test-app-id",
            "bot.secret" to "test-secret",
            "bot.name" to "TestBot",
            "bot.groups" to emptyList<String>(),
            "debug.log-events" to false
        )
        return ctor.newInstance(flat) as PenguinConfig
    }
}

/** 本地发消息桩：记录消息体与 DELETE 路径。 */
private class FakeSendApi {

    val messageBodies = CopyOnWriteArrayList<String>()
    val deletePaths = CopyOnWriteArrayList<String>()
    val deleteAttempts = AtomicInteger(0)

    @Volatile var failDeleteWith: Pair<Int, String>? = null

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val tokenUrl: String get() = "http://127.0.0.1:${server.address.port}/app/getAppAccessToken"
    val apiBase: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/app/getAppAccessToken") { ex ->
            send(ex, 200, """{"access_token":"stub-token","expires_in":7200}""")
        }
        server.createContext("/") { ex ->
            when (ex.requestMethod) {
                "POST" -> {
                    val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                    if (ex.requestURI.path.endsWith("/messages")) {
                        messageBodies.add(body)
                    }
                    send(ex, 200, """{"id":"sent-1"}""")
                }
                "DELETE" -> {
                    deleteAttempts.incrementAndGet()
                    deletePaths.add(ex.requestURI.path)
                    failDeleteWith?.let { (code, payload) ->
                        failDeleteWith = null
                        send(ex, code, payload)
                        return@createContext
                    }
                    send(ex, 200, "{}")
                }
                else -> send(ex, 200, "{}")
            }
        }
        server.executor = Executors.newCachedThreadPool { r ->
            Thread(r).apply { isDaemon = true; name = "fake-send-api" }
        }
        server.start()
    }

    fun close() = server.stop(0)

    private fun send(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
