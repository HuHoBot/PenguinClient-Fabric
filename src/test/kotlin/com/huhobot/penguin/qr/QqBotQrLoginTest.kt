package com.huhobot.penguin.qr

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * QQ 扫码绑定回归测试。
 *
 * 全部只连本地 HTTP 桩 + 纯本地加解密，不访问腾讯任何接口、不需要真实扫码。
 *
 * 密文用一套**独立实现**（见 [seal]）生成，而不是拿被测的 decryptSecret 去造数据，
 * 否则「布局理解错」和「解密实现错」会互相抵消，测不出问题。
 */
class QqBotQrLoginTest {

    private lateinit var api: FakeQrApi

    @BeforeEach
    fun setUp() {
        api = FakeQrApi()
        QqBotQrLogin.createUrl = "${api.base}/lite/create_bind_task"
        QqBotQrLogin.pollUrl = "${api.base}/lite/poll_bind_result"
    }

    @AfterEach
    fun tearDown() {
        QqBotQrLogin.createUrl = "https://q.qq.com/lite/create_bind_task"
        QqBotQrLogin.pollUrl = "https://q.qq.com/lite/poll_bind_result"
        api.close()
    }

    // ---- AES-256-GCM 解密（风险最高的一段） ----

    @Test
    fun `按IV ciphertext tag 布局解密出原始 Secret`() {
        val secret = "s3cr3t-AppSecret-值-含中文-abc123"
        val key = randomKey()
        val blob = seal(key, secret)

        assertEquals(secret, QqBotQrLogin.decryptSecret(b64(blob), b64(key)))
    }

    @Test
    fun `解出空字符串也应当成功而不是抛异常`() {
        val key = randomKey()
        val blob = seal(key, "")
        assertEquals("", QqBotQrLogin.decryptSecret(b64(blob), b64(key)))
    }

    @Test
    fun `不同 key 解同一密文应失败而不是解出垃圾`() {
        val blob = seal(randomKey(), "correct-secret")
        val wrongKey = randomKey()
        val error = assertThrows(QrLoginException::class.java) {
            QqBotQrLogin.decryptSecret(b64(blob), b64(wrongKey))
        }
        assertTrue(error.message!!.contains("解密"), "错误信息应说明是解密失败，实际=${error.message}")
    }

    @Test
    fun `篡改密文一个字节应被 GCM 完整性校验拒绝`() {
        val key = randomKey()
        val blob = seal(key, "secret-value")
        blob[15] = (blob[15].toInt() xor 0x01).toByte() // 动ciphertext 区，不动 IV 和 tag
        val error = assertThrows(QrLoginException::class.java) {
            QqBotQrLogin.decryptSecret(b64(blob), b64(key))
        }
        assertTrue(error.message!!.contains("解密"))
    }

    @Test
    fun `key 长度不是 32 字节时本地拒绝`() {
        val shortKey = ByteArray(16).also(SecureRandom()::nextBytes)
        val error = assertThrows(QrLoginException::class.java) {
            QqBotQrLogin.decryptSecret(b64(ByteArray(40)), b64(shortKey))
        }
        assertTrue(error.message!!.contains("32"), "应说明 key 长度要求，实际=${error.message}")
    }

    @Test
    fun `密文短于 IV+tag 时本地拒绝`() {
        val key = randomKey()
        val tooShort = ByteArray(20) // < 12 + 16
        val error = assertThrows(QrLoginException::class.java) {
            QqBotQrLogin.decryptSecret(b64(tooShort), b64(key))
        }
        assertTrue(error.message!!.contains("长度"), "实际=${error.message}")
    }

    @Test
    fun `非法 base64 报出是哪个字段的问题`() {
        val key = randomKey()
        val keyErr = assertThrows(QrLoginException::class.java) {
            QqBotQrLogin.decryptSecret("!!!not-base64!!!", b64(key))
        }
        assertTrue(keyErr.message!!.contains("bot_encrypt_secret"), "实际=${keyErr.message}")

        val blobErr = assertThrows(QrLoginException::class.java) {
            QqBotQrLogin.decryptSecret(b64(ByteArray(40)), "###")
        }
        assertTrue(blobErr.message!!.contains("key"), "实际=${blobErr.message}")
    }

    @Test
    fun `实际长度 32 字节的 key 被接受 33 字节被拒绝`() {
        val key32 = randomKey()
        assertEquals(32, key32.size)
        // 32 字节：能走完到解密这一步（会因密文不匹配失败，但错误类型是"解密"不是"key 长度"）
        val ok32 = runCatching { QqBotQrLogin.decryptSecret(b64(ByteArray(40)), b64(key32)) }
        assertTrue(
            ok32.exceptionOrNull()?.message?.contains("解密") == true,
            "32 字节 key 应通过长度校验，实际=${ok32.exceptionOrNull()?.message}"
        )

        val key33 = randomKey() + ByteArray(1)
        val err33 = assertThrows(QrLoginException::class.java) {
            QqBotQrLogin.decryptSecret(b64(ByteArray(40)), b64(key33))
        }
        assertTrue(err33.message!!.contains("32"), "实际=${err33.message}")
    }

    // ---- create_bind_task ----

    @Test
    fun `创建绑定任务返回 task_id 与本地生成的 32 字节 key`() {
        api.createResponse = """{"retcode":0,"data":{"task_id":"task-abc-123"}}"""

        val task = QqBotQrLogin.createBindTask()

        assertEquals("task-abc-123", task.taskId)
        assertEquals(32, Base64.getDecoder().decode(task.key).size, "key 必须是 AES-256 的 32 字节")
        assertEquals("POST", api.createMethods.first())
        assertTrue(api.createBodies.first().contains("\"key\""), "应下发本地生成的 key，实际=${api.createBodies.first()}")
    }

    @Test
    fun `两次创建任务得到不同的 key`() {
        api.createResponse = """{"retcode":0,"data":{"task_id":"t"}}"""
        assertNotEquals(QqBotQrLogin.createBindTask().key, QqBotQrLogin.createBindTask().key)
    }

    @Test
    fun `create_bind_task 返回非零 retcode 时抛出并带 msg`() {
        api.createResponse = """{"retcode":1001,"msg":"来源不允许"}"""
        val error = assertThrows(QrLoginException::class.java) { QqBotQrLogin.createBindTask() }
        assertEquals("来源不允许", error.message)
    }

    @Test
    fun `create_bind_task 缺少 task_id 时明确报错`() {
        api.createResponse = """{"retcode":0,"data":{}}"""
        val error = assertThrows(QrLoginException::class.java) { QqBotQrLogin.createBindTask() }
        assertTrue(error.message!!.contains("task_id"), "实际=${error.message}")
    }

    @Test
    fun `create_bind_task 返回非法 JSON 时报错`() {
        api.createResponse = "not json at all"
        val error = assertThrows(QrLoginException::class.java) { QqBotQrLogin.createBindTask() }
        assertTrue(error.message!!.contains("JSON"), "实际=${error.message}")
    }

    // ---- poll_bind_result ----

    @Test
    fun `轮询到完成状态时解析出 appid 与密文`() {
        val key = randomKey()
        val secret = "the-real-app-secret"
        api.pollResponse = """{"retcode":0,"data":{"status":2,"bot_appid":"1905453859",""" +
            """"bot_encrypt_secret":"${b64(seal(key, secret))}","user_openid":"u-openid"}}"""

        val result = QqBotQrLogin.pollBindResult("task-1")

        assertEquals(QrBindStatus.COMPLETED, result.status)
        assertEquals("1905453859", result.appId)
        assertEquals("u-openid", result.userOpenid)
        assertEquals(secret, QqBotQrLogin.decryptSecret(result.encryptedSecret, b64(key)))
    }

    @Test
    fun `bot_appid 是数字形态时不能变成科学计数法`() {
        // Gson 默认把 JSON 数字反序列化成 Double，1905453859 会变成 1.905453859E9，
        // 写进配置后鉴权必失败。改用 LONG_OR_DOUBLE 策略后修复。
        api.pollResponse = """{"retcode":0,"data":{"status":2,"bot_appid":1905453859}}"""
        assertEquals("1905453859", QqBotQrLogin.pollBindResult("task-1").appId)
    }

    @Test
    fun `待扫码与已过期的状态映射正确`() {
        // status=1 / 3 取自对 q.qq.com 的实跑响应，不是猜的
        api.pollResponse = """{"retcode":0,"data":{"status":1,"bot_appid":"0","bot_encrypt_secret":"","user_openid":""}}"""
        assertEquals(QrBindStatus.PENDING, QqBotQrLogin.pollBindResult("t").status)

        api.pollResponse = """{"retcode":0,"data":{"status":3,"bot_appid":"0","bot_encrypt_secret":"","user_openid":""}}"""
        assertEquals(QrBindStatus.EXPIRED, QqBotQrLogin.pollBindResult("t").status)
    }

    @Test
    fun `未扫码时线上返回的占位字段不应被当成有效凭据`() {
        // 实跑：未扫码时 bot_appid 是字符串 "0"、密文与 openid 均为空串
        api.pollResponse = """{"retcode":0,"data":{"status":1,"bot_appid":"0","bot_encrypt_secret":"","user_openid":""}}"""
        val result = QqBotQrLogin.pollBindResult("t")
        assertEquals("", result.encryptedSecret)
        assertNull(result.userOpenid)
    }

    @Test
    fun `task_id 为空时线上返回 retcode 30001 并带中文 msg`() {
        // 实跑响应：{"msg":"task_id 不能为空","retcode":30001}
        api.pollResponse = """{"msg":"task_id 不能为空","retcode":30001}"""
        val error = assertThrows(QrLoginException::class.java) { QqBotQrLogin.pollBindResult("") }
        assertEquals("task_id 不能为空", error.message)
    }

    @Test
    fun `status 为 0 时映射为 NONE`() {
        // 0 在枚举里就是 NONE，与「未知状态继续轮询」语义一致
        api.pollResponse = """{"retcode":0,"data":{"status":0}}"""
        assertEquals(QrBindStatus.NONE, QqBotQrLogin.pollBindResult("t").status)
    }

    @Test
    fun `状态为 1 时映射为 PENDING`() {
        api.pollResponse = """{"retcode":0,"data":{"status":1}}"""
        assertEquals(QrBindStatus.PENDING, QqBotQrLogin.pollBindResult("t").status)
    }

    @Test
    fun `未知状态码归为 NONE 而不是抛异常`() {
        api.pollResponse = """{"retcode":0,"data":{"status":99}}"""
        assertEquals(QrBindStatus.NONE, QqBotQrLogin.pollBindResult("t").status)
    }

    @Test
    fun `轮询缺少 data 字段时给出空状态而不是崩`() {
        api.pollResponse = """{"retcode":0}"""
        val result = QqBotQrLogin.pollBindResult("t")
        assertEquals(QrBindStatus.NONE, result.status)
        assertEquals("", result.encryptedSecret)
        assertNull(result.userOpenid)
    }

    @Test
    fun `poll_bind_result 非零 retcode 时抛出并带 msg`() {
        api.pollResponse = """{"retcode":2001,"msg":"task 已失效"}"""
        val error = assertThrows(QrLoginException::class.java) { QqBotQrLogin.pollBindResult("t") }
        assertEquals("task 已失效", error.message)
    }

    // ---- URL 拼装 ----

    @Test
    fun `二维码 URL 固定指向线上 openclaw 页面并带 task_id 与 source`() {
        val url = QqBotQrLogin.connectUrl("task-xyz", source = "penguin")
        assertTrue(url.startsWith("https://q.qq.com/qqbot/openclaw/connect.html"), "实际=$url")
        assertTrue(url.contains("task_id=task-xyz"), "实际=$url")
        assertTrue(url.contains("source=penguin"), "实际=$url")
        assertTrue(url.contains("_wv=2"), "实际=$url")
    }

    @Test
    fun `端点被替换为本地桩时二维码 URL 仍指向线上`() {
        // 桩只影响协议请求，二维码页面必须还是线上——用户扫的是真页面
        assertTrue(QqBotQrLogin.createUrl.startsWith("http://127.0.0.1"), "前置条件：本测试已把端点指向桩")
        assertTrue(QqBotQrLogin.connectUrl("t").startsWith("https://q.qq.com/"))
    }

    @Test
    fun `encodeUriComponent 与 JS encodeURIComponent 行为一致`() {
        // 期望值取自 node 实跑 encodeURIComponent，不是手写推测
        assertEquals("a%20b", QqBotQrLogin.encodeUriComponent("a b"))
        assertEquals("!'()~", QqBotQrLogin.encodeUriComponent("!'()~"))
        assertEquals("*-._", QqBotQrLogin.encodeUriComponent("*-._"))
        assertEquals("a%20b%26c", QqBotQrLogin.encodeUriComponent("a b&c"))
        assertEquals("%E4%B8%AD%E6%96%87", QqBotQrLogin.encodeUriComponent("中文"))
        assertEquals("1905453859", QqBotQrLogin.encodeUriComponent("1905453859"))
    }

    @Test
    fun `二维码 URL 对 task_id 做百分号编码`() {
        assertTrue(QqBotQrLogin.connectUrl("a b&c").contains("task_id=a%20b%26c"))
    }

    // ---- 辅助 ----

    private fun randomKey(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /**
     * 独立的 AES-256-GCM 密封实现，产出 `IV(12) || ciphertext || AuthTag(16)`。
     *
     * 刻意不复用被测代码：JCE 的 `doFinal` 会把 AuthTag 拼到入参尾部，
     * 所以这里显式 `ciphertext || tag` 再包上 IV，与 JS SDK 的字节布局一致。
     */
    private fun seal(key: ByteArray, plaintext: String): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val enc = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        // JCE 输出尾部 16 字节就是 AuthTag
        val body = enc.copyOfRange(0, enc.size - 16)
        val tag = enc.copyOfRange(enc.size - 16, enc.size)
        return iv + body + tag
    }
}

/** 本地扫码协议桩。 */
private class FakeQrApi {

    val createMethods = CopyOnWriteArrayList<String>()
    val createBodies = CopyOnWriteArrayList<String>()
    val pollBodies = CopyOnWriteArrayList<String>()

    @Volatile var createResponse: String = """{"retcode":0,"data":{"task_id":"t"}}"""
    @Volatile var pollResponse: String = """{"retcode":0,"data":{"status":0}}"""

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/lite/create_bind_task") { ex ->
            createMethods.add(ex.requestMethod)
            createBodies.add(ex.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            send(ex, createResponse)
        }
        server.createContext("/lite/poll_bind_result") { ex ->
            pollBodies.add(ex.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            send(ex, pollResponse)
        }
        server.executor = Executors.newCachedThreadPool { r ->
            Thread(r).apply { isDaemon = true; name = "fake-qr-api" }
        }
        server.start()
    }

    fun close() = server.stop(0)

    private fun send(ex: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
