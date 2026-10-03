package com.huhobot.penguin.qq

import com.huhobot.penguin.config.PenguinConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 群管理 REST 与互动响应回归测试。
 *
 * 全部只连本地 HTTP 桩，不访问腾讯任何接口。
 * 覆盖 GroupApi 的路径拼装、翻页、错误码提取，以及 PUT /interactions/{id} 的回执。
 */
class GroupApiTest {

    private lateinit var api: FakeGroupApi

    @BeforeEach
    fun setUp() {
        api = FakeGroupApi()
    }

    @AfterEach
    fun tearDown() {
        api.close()
    }

    private fun groupApi() = GroupApi(testConfig(), { "stub-token" }, api.apiBase)

    // ---- 群信息 / bot 状态 ----

    @Test
    fun `群信息解析字段`() {
        val info = groupApi().getGroupInfo("g1")
        assertEquals("测试群", info.groupName)
        assertEquals(128, info.memberCount)
        assertEquals(500, info.maxMember)
        assertEquals("GET /v2/groups/g1/info", api.lastPath)
    }

    /**
     * QQ 的 info 响应实测没有 member_count / max_member。缺失时必须返回 null，
     * 兜成 0 会让群里显示「成员：0/0」，那是编出来的数据。
     */
    @Test
    fun `info 接口缺成员数字段时返回 null 而不是 0`() {
        val info = groupApi().getGroupInfo("g_no_count")
        assertEquals("无成员数群", info.groupName)
        assertNull(info.memberCount, "缺字段时memberCount 应为 null")
        assertNull(info.maxMember, "缺字段时 maxMember 应为 null")
    }

    @Test
    fun `bot 状态按真实字段解析`() {
        val state = groupApi().getBotState("g1")
        // 接口调通即代表在群里，QQ 不提供 joined 字段
        assertTrue(state.inGroup)
        assertEquals("all", state.recvSetting)
        assertEquals("admin", state.memberRole)
        assertEquals("2026-08-15T11:47:56+08:00", state.joinedAt)
    }

    // ---- 禁言 ----

    @Test
    fun `全群禁言下发 duration 秒数`() {
        groupApi().setMuteAll("g1", 600)
        assertEquals("POST /v2/groups/g1/restrict_chat_setting", api.lastPath)
        assertTrue(api.lastBody.contains("\"duration\":600"), "实际 body=${api.lastBody}")
    }

    @Test
    fun `禁言超过 30 天应本地拒绝而不是打接口`() {
        var threw = false
        try {
            groupApi().setMuteAll("g1", 30 * 24 * 3600 + 1)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "超上限应抛 IllegalArgumentException")
        assertNull(api.lastPath, "本地拒绝时不应发出请求")
    }

    // ---- 成员 ----

    @Test
    fun `成员列表自动翻页到 after 为空`() {
        val members = groupApi().listMembers("g1", maxCount = 100)
        assertEquals(3, members.size)
        assertEquals("u1", members[0].memberOpenid)
        assertEquals("owner", members[0].memberRole)
        // 第1 页取2 条 + 第 2 页 1 条
        assertEquals(2, api.memberPageRequests.get())
    }

    @Test
    fun `成员数量受 maxCount 限制`() {
        val members = groupApi().listMembers("g1", maxCount = 2)
        assertEquals(2, members.size)
        assertEquals(1, api.memberPageRequests.get(), "达到 maxCount 应停止翻页")
    }

    @Test
    fun `成员详情返回单条`() {
        val m = groupApi().getMember("g1", "u1")
        assertEquals("u1", m.memberOpenid)
        assertEquals("admin", m.memberRole)
        assertEquals(42, m.msgCount)
    }

    @Test
    fun `批量踢人超过 100 个应本地拒绝`() {
        val ids = (1..101).map { "u$it" }
        val error = runCatching { groupApi().batchRemoveMembers("g1", ids) }.exceptionOrNull()
        assertTrue(error is GroupApi.GroupApiException, "应抛 GroupApiException，实际=$error")
        assertTrue(error!!.message!!.contains("100"), "错误信息应说明上限，实际=${error.message}")
    }

    @Test
    fun `批量踢人正常下发 member_openids`() {
        groupApi().batchRemoveMembers("g1", listOf("u1", "u2"), reason = "spam")
        assertEquals("POST /v2/groups/g1/batch_remove_members", api.lastPath)
        assertTrue(api.lastBody.contains("\"member_openids\":[\"u1\",\"u2\"]"), "实际 body=${api.lastBody}")
    }

    // ---- 黑名单 ----

    @Test
    fun `黑名单添加与移除分别用 add_list 和 delete_list`() {
        val g = groupApi()
        g.addBlacklist("g1", listOf("u9"))
        assertTrue(api.lastBody.contains("\"add_list\":[\"u9\"]"), "实际 body=${api.lastBody}")
        g.removeBlacklist("g1", listOf("u9"))
        assertTrue(api.lastBody.contains("\"delete_list\":[\"u9\"]"), "实际 body=${api.lastBody}")
    }

    // ---- 入群申请 ----

    @Test
    fun `入群申请列表解析`() {
        val list = groupApi().listJoinRequests("g1", pending = true)
        assertEquals(1, list.size)
        assertEquals("req-1", list[0].requestId)
        assertEquals("新人和", list[0].username)
        assertEquals("high", list[0].riskTips)
    }

    @Test
    fun `同意与拒绝入群路径不同`() {
        val g = groupApi()
        g.approveJoinRequest("g1", "u5")
        assertEquals("POST /v2/groups/g1/approval_join_request/u5", api.lastPath)
        g.rejectJoinRequest("g1", "u5")
        assertEquals("POST /v2/groups/g1/reject_join_request/u5", api.lastPath)
    }

    @Test
    fun `加群审批策略中文映射`() {
        assertEquals("自动同意（无需审核）", groupApi().getJoinApprovalStrategy("g1"))
    }

    // ---- 错误处理 ----

    @Test
    fun `HTTP 错误时抛出并带上 QQ 返回的 message`() {
        api.failNextWith = 403 to """{"code":40014022,"message":"机器人无管理权限"}"""
        val error = runCatching { groupApi().getGroupInfo("g1") }.exceptionOrNull()
        assertTrue(error is GroupApi.GroupApiException)
        assertTrue(error!!.message!!.contains("机器人无管理权限"), "实际=${error.message}")
    }

    @Test
    fun `token 获取失败时给出可读错误而非裸异常`() {
        val boom = GroupApi(testConfig(), { throw IllegalStateException("token 接口 500") }, api.apiBase)
        val error = runCatching { boom.getGroupInfo("g1") }.exceptionOrNull()
        assertTrue(error is GroupApi.GroupApiException)
        assertTrue(error!!.message!!.contains("token"), "实际=${error.message}")
    }

    // ---- 互动事件回执 ----

    @Test
    fun `互动响应使用 PUT 且 body 只带 code`() {
        val client = QQClient(testConfig(), api.tokenUrl, api.apiBase)
        val ok = client.respondInteractionNow("i-1", InteractionCode.SUCCESS)
        assertTrue(ok)
        assertEquals("PUT /interactions/i-1", api.lastPath)
        assertEquals("""{"code":0}""", api.lastBody)
    }

    @Test
    fun `互动响应失败时返回 false 而非抛异常`() {
        api.failNextWith = 404 to """{"code":40022001,"message":"interaction 不存在"}"""
        val client = QQClient(testConfig(), api.tokenUrl, api.apiBase)
        val ok = client.respondInteractionNow("i-missing", InteractionCode.FAILED)
        assertEquals(false, ok)
    }

    @Test
    fun `互动响应码常量与官方语义一致`() {
        assertEquals(0, InteractionCode.SUCCESS)
        assertEquals(1, InteractionCode.FAILED)
        assertEquals(2, InteractionCode.TOO_FREQUENT)
        assertEquals(3, InteractionCode.DUPLICATE)
        assertEquals(4, InteractionCode.NO_PERMISSION)
        assertEquals(5, InteractionCode.ADMIN_ONLY)
    }

    // ---- Intents ----

    @Test
    fun `默认 intents 开启群消息 群成员与互动三位`() {
        val expected = (1 shl 24) or (1 shl 25) or (1 shl 26)
        assertEquals(expected, Intents.DEFAULT_GROUP)
        assertEquals(117440512, Intents.DEFAULT_GROUP, "应与线上日志观测到的值一致")
    }

    @Test
    fun `intents-auto 关闭时只保留群消息位`() {
        val cfg = testConfig(mapOf("qq.intents" to 0, "qq.intents-auto" to false))
        assertEquals(Intents.GROUP, cfg.qqIntents)
    }

    @Test
    fun `intents 显式配置时原样使用`() {
        val cfg = testConfig(mapOf("qq.intents" to 33554432))
        assertEquals(33554432, cfg.qqIntents)
    }

    // ---- 辅助 ----

    private fun testConfig(extra: Map<String, Any?> = emptyMap()): PenguinConfig {
        // Kotlin 2.4 会为默认值合成额外的构造器，不能直接取 first()
        val ctor = PenguinConfig::class.java.declaredConstructors
            .first { it.parameterCount == 1 && it.parameterTypes[0] == Map::class.java }
        ctor.isAccessible = true
        val flat = mutableMapOf<String, Any?>(
            "bot.app-id" to "test-app-id",
            "bot.secret" to "test-secret",
            "bot.name" to "TestBot",
            "bot.groups" to emptyList<String>(),
            "debug.log-events" to false
        )
        flat.putAll(extra)
        return ctor.newInstance(flat) as PenguinConfig
    }
}

/** 本地 QQ 群管理接口桩，记录最后一次请求的路径与 body。 */
private class FakeGroupApi {

    val memberPageRequests = java.util.concurrent.atomic.AtomicInteger(0)
    val recordedPaths = CopyOnWriteArrayList<String>()

    @Volatile var lastPath: String? = null
    @Volatile var lastBody: String = ""

    /** 非 null 时，下一个请求直接返回该 (状态码, body)。 */
    @Volatile var failNextWith: Pair<Int, String>? = null

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val apiBase: String get() = "http://127.0.0.1:${server.address.port}"
    val tokenUrl: String get() = "$apiBase/app/getAppAccessToken"

    init {
        server.createContext("/app/getAppAccessToken") { ex ->
            send(ex, 200, """{"access_token":"stub-token","expires_in":7200}""")
        }
        server.createContext("/") { ex ->
            // 注意：getPath() 不含 query，分页判断必须用 requestURI 全串
            val path = ex.requestURI.path
            val full = ex.requestURI.toString()
            val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            recordedPaths.add("${ex.requestMethod} $full")
            lastPath = "${ex.requestMethod} $full"
            lastBody = body

            failNextWith?.let { (code, payload) ->
                failNextWith = null
                send(ex, code, payload)
                return@createContext
            }

            when {
                path.endsWith("/info") -> send(ex, 200,
                    // g_no_count 模拟真实 QQ 的 info 响应：只有群名，没有成员数字段
                    if (full.contains("/v2/groups/g_no_count/"))
                        """{"group_name":"无成员数群","group_finger_memo":"x"}"""
                    else """{"group_name":"测试群","member_count":128,"max_member":500}""")
                path.endsWith("/bot_state") -> send(ex, 200, """{"member_openid":"m1","joined_at":"2026-08-15T11:47:56+08:00","allow_proactive_msg":true,"recv_msg_setting":"all","member_role":"admin"}""")
                full.contains("/members?") -> sendMembersPage(ex)
                path.matches(Regex(".*/members/[^/]+")) ->
                    send(ex, 200, """{"member_openid":"u1","username":"甲","member_role":"admin","msg_count":42}""")
                path.endsWith("/member_blacklist") -> send(ex, 200, """{"blacklist":["b1","b2"]}""")
                path.contains("/join_request_list") ->
                    send(ex, 200, """{"data":[{"id":"req-1","member_openid":"m1","username":"新人和","apply_time":"2026-10-03","risk_tips":"high"}],"after":""}""")
                path.endsWith("/join_approval_strategy") -> send(ex, 200, """{"strategy":1}""")
                path.startsWith("/interactions/") -> send(ex, 200, "{}")
                else -> send(ex, 200, "{}")
            }
        }
        server.executor = Executors.newCachedThreadPool { r ->
            Thread(r).apply { isDaemon = true; name = "fake-group-api" }
        }
        server.start()
    }

    /** 第 1 页返回 2 条带 after，第 2 页返回 1 条收尾。 */
    private fun sendMembersPage(ex: HttpExchange) {
        val page = memberPageRequests.incrementAndGet()
        val body = if (page == 1) {
            """{"members":[{"member_openid":"u1","username":"甲","member_role":"owner"},""" +
                """{"member_openid":"u2","username":"乙","member_role":"admin"}],"after":"cur-2"}"""
        } else {
            """{"members":[{"member_openid":"u3","username":"丙","member_role":"member"}],"after":""}"""
        }
        send(ex, 200, body)
    }

    fun close() = server.stop(0)

    private fun send(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
