package com.huhobot.penguin.qq

import com.huhobot.penguin.config.PenguinConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * QQ 网关重连行为回归测试。
 *
 * 复现的线上事故（2026-08）：QQ 提前作废 access_token 后 `GET /gateway` 持续返回
 * 401，而 `getJson()` 不像 `postJson()` 那样作废本地 token 缓存，于是每次重连都
 * 拿同一个死 token 去换网关地址；重连链又没有单飞闸门，watchdog 每 30s 还会再插
 * 一条，请求速率随在线时长线性增长，最终被腾讯限流成
 * `{"message":"接口调用超过频率限制","code":100017}`，单个日志文件一天 250MB。
 *
 * 所有用例只连本地 HTTP 桩，不访问腾讯任何接口。
 */
class QQClientReconnectTest {

    private lateinit var api: FakeQQApi
    private var client: QQClient? = null

    @BeforeEach
    fun setUp() {
        killPenguinThreads()
        api = FakeQQApi()
    }

    @AfterEach
    fun tearDown() {
        client?.stop()
        client = null
        // 用例之间必须收干净：退避中的 penguin-connect 线程会污染下一个用例的线程计数
        killPenguinThreads()
        api.close()
    }

    /** 停掉所有遗留的 penguin-* 线程（stop() 之后它们只是还在 sleep 里等退避）。 */
    private fun killPenguinThreads() {
        Thread.getAllStackTraces().keys
            .filter { it.name.startsWith("penguin-") }
            .forEach { runCatching { it.interrupt() } }
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && livePenguinConnectThreads() > 0) {
            Thread.sleep(50)
        }
    }

    private fun livePenguinConnectThreads(): Int =
        Thread.getAllStackTraces().keys.count { it.name == "penguin-connect" && it.isAlive }

    private fun startClient(): QQClient =
        QQClient(testConfig(), api.tokenUrl, api.apiBase).also {
            client = it
            it.start()
        }

    /**
     * 回归点 1：`/gateway` 返回 401 时必须作废 token 缓存。
     *
     * 修复前 `getJson()` 完全不处理 401，本地缓存要等 7200s TTL 自然到期，
     * 期间每次重连都复用同一个死 token —— token 申请次数会永远停在 1。
     */
    @Test
    fun `gateway 401 invalidates cached token`() {
        api.gatewayStatus = 401
        startClient()

        assertTrue(
            api.awaitTokenRequests(2, 25),
            "401 之后应重新申请 access_token，实际只申请了 ${api.tokenRequests.get()} 次"
        )
    }

    /**
     * 回归点 2：命中「接口调用超过频率限制」后必须进入冷却，
     * 不能继续按秒级退避接着敲同一个被限流的接口。
     */
    @Test
    fun `rate limited gateway enters cooldown`() {
        api.gatewayStatus = 400
        startClient()

        assertTrue(api.awaitGatewayRequests(1, 20), "客户端应至少尝试过一次 /gateway")
        val beforeCooldown = api.gatewayRequests.get()
        val peakThreads = sampleConnectThreadPeak(12_000)

        assertEquals(
            beforeCooldown, api.gatewayRequests.get(),
            "限流冷却期内不应再打 /gateway（冷却前 $beforeCooldown 次）"
        )
        assertTrue(peakThreads <= 2, "penguin-connect 线程峰值应 <=2，实测 $peakThreads")
    }

    /**
     * 回归点 3：单飞闸门。socket 关闭、watchdog、op9 可能在同一瞬间都要求重连，
     * 修复前每一次都会新起一条永不回收的重试链——这就是速率线性增长的来源。
     */
    @Test
    fun `concurrent reconnect requests collapse into one attempt`() {
        api.gatewayDelayMs = 1500
        api.gatewayStatus = 400
        val c = startClient()

        // 第一条连接尝试此刻正卡在 /gateway 上，再从外部猛敲 20 次重连入口
        val connectAsync = QQClient::class.java
            .getDeclaredMethod("connectAsync", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        repeat(20) { connectAsync.invoke(c, 0L) }
        Thread.sleep(1000)

        assertEquals(
            1, api.gatewayRequests.get(),
            "并发重连请求应被闸门收敛成 1 次，实测 ${api.gatewayRequests.get()} 次"
        )
    }

    /** 回归点 4：401 之后必须能自愈——换到新 token 并重新连上网关 socket。 */
    @Test
    fun `recovers after transient gateway 401`() {
        val probe = WsProbe()
        try {
            api.wsPort = probe.port
            api.gatewayStatus = 401
            startClient()

            assertTrue(api.awaitTokenRequests(2, 25), "401 之后应重新申请 token")
            api.gatewayStatus = 200

            assertTrue(
                probe.connected.await(40, TimeUnit.SECONDS),
                "恢复后应重新建立网关 socket，/gateway 已请求 ${api.gatewayRequests.get()} 次"
            )
        } finally {
            probe.close()
        }
    }

    /** token 未到期时不应被重复申请（避免把 getAppAccessToken 打到限流）。 */
    @Test
    fun `valid token is reused across reconnects`() {
        val probe = WsProbe()
        try {
            api.wsPort = probe.port
            api.gatewayStatus = 200
            startClient()

            assertTrue(probe.connected.await(25, TimeUnit.SECONDS), "应连接网关 socket")
            // 探针不是真 WS 服务端，握手必然失败，从而触发若干次重连
            assertTrue(api.awaitGatewayRequests(2, 25), "握手失败后应触发重连")

            assertEquals(
                1, api.tokenRequests.get(),
                "token 仍有效时不应重复申请，实测 ${api.tokenRequests.get()} 次"
            )
        } finally {
            probe.close()
        }
    }

    // ---- 辅助设施 ----

    /** 采样 [durationMs] 内同时存活的 penguin-connect 线程峰值。 */
    private fun sampleConnectThreadPeak(durationMs: Long): Int {
        val deadline = System.currentTimeMillis() + durationMs
        var peak = 0
        while (System.currentTimeMillis() < deadline) {
            val alive = livePenguinConnectThreads()
            if (alive > peak) peak = alive
            Thread.sleep(100)
        }
        return peak
    }

    /** PenguinConfig 的构造器是 private 且 load() 依赖 FabricLoader，这里直接反射造一个。 */
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

/** 本地 QQ 开放平台桩：token 接口 + /gateway，可注入 401 / 限流 / 延迟。 */
private class FakeQQApi {

    val tokenRequests = AtomicInteger(0)
    val gatewayRequests = AtomicInteger(0)

    /** 200 = 正常返回网关地址；401 = AccessToken 失效；400 = 接口调用超过频率限制。 */
    @Volatile var gatewayStatus = 200
    @Volatile var gatewayDelayMs = 0L
    @Volatile var wsPort = 0

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    private val port: Int get() = server.address.port
    val tokenUrl: String get() = "http://127.0.0.1:$port/app/getAppAccessToken"
    val apiBase: String get() = "http://127.0.0.1:$port"

    init {
        server.createContext("/app/getAppAccessToken") { ex ->
            val n = tokenRequests.incrementAndGet()
            send(ex, 200, """{"access_token":"token-$n","expires_in":7200}""")
        }
        server.createContext("/gateway") { ex ->
            gatewayRequests.incrementAndGet()
            val delay = gatewayDelayMs
            if (delay > 0) try { Thread.sleep(delay) } catch (_: InterruptedException) {}
            when (gatewayStatus) {
                401 -> send(ex, 401, qqError("AccessToken无效或过期", 11244, 40011027))
                400 -> send(ex, 400, qqError("接口调用超过频率限制", 100017, 40023001))
                else -> send(ex, 200, """{"url":"wss://127.0.0.1:$wsPort/websocket"}""")
            }
        }
        server.executor = Executors.newCachedThreadPool { r ->
            Thread(r).apply { isDaemon = true; name = "fake-qq-api" }
        }
        server.start()
    }

    fun awaitTokenRequests(n: Int, seconds: Long) = await(seconds) { tokenRequests.get() >= n }
    fun awaitGatewayRequests(n: Int, seconds: Long) = await(seconds) { gatewayRequests.get() >= n }
    fun close() = server.stop(0)

    private fun await(seconds: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private fun send(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** 复刻 QQ 开放平台的错误响应体，trace_id 每次都不同（正好覆盖日志折叠的归一化逻辑）。 */
    private fun qqError(message: String, code: Int, errCode: Int) =
        """{"message":"$message","code":$code,"err_code":$errCode,"trace_id":"${traceId()}"}"""

    private fun traceId() = java.util.UUID.randomUUID().toString().replace("-", "")
}

/**
 * 只负责「接受连接」的探针，用来断言客户端确实去连了网关地址。
 * 它不说 TLS/WebSocket，所以握手一定失败——正好用来驱动重连路径。
 */
private class WsProbe {

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val connected = CountDownLatch(1)

    init {
        Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        connected.countDown()
                        socket.soTimeout = 2000
                        runCatching { socket.getInputStream().read(ByteArray(256)) }
                    }
                } catch (_: Exception) {
                    return@Thread
                }
            }
        }.apply { isDaemon = true; name = "ws-probe" }.start()
    }

    fun close() {
        runCatching { server.close() }
    }
}
