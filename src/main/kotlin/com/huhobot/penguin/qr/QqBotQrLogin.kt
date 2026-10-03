package com.huhobot.penguin.qr

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * QQ 机器人扫码绑定的纯协议实现，行为对齐主仓库 `cn.huohuas001.bot.qr.QqBotQrLogin`。
 *
 * 流程：创建绑定任务 → 生成二维码 URL → 轮询结果 → AES-256-GCM 解密 AppSecret。
 *
 * 本对象只做「一次请求 = 一次函数调用」，轮询循环与重试由 [QrLoginRunner] 负责。
 */
object QqBotQrLogin {
    /** 单次 HTTP 请求超时。 */
    const val HTTP_TIMEOUT_MS = 10_000L

    /** 轮询间隔。 */
    const val POLL_INTERVAL_MS = 2_000L

    /** 二维码页面上展示的接入方标识，不参与鉴权。 */
    const val DEFAULT_SOURCE = "penguin"

    /** 二维码域名：即使 API 切到测试环境，二维码也始终指向线上域名。 */
    private const val HOST = "q.qq.com"

    /**
     * 协议端点。仅测试用本地桩覆盖，生产恒为线上地址——
     * 二维码页面（[connectUrl]）无论如何都指向线上，不受这里影响。
     */
    internal var createUrl: String = "https://$HOST/lite/create_bind_task"
    internal var pollUrl: String = "https://$HOST/lite/poll_bind_result"

    private val random = SecureRandom()

    /**
     * 与 JS `encodeURIComponent` 等价的编码：`URLEncoder` 是表单编码，
     * 需要把 `+` 与少数几个未转义字符补回来。
     */
    fun encodeUriComponent(value: String): String = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20")
        .replace("%21", "!")
        .replace("%27", "'")
        .replace("%28", "(")
        .replace("%29", ")")
        .replace("%7E", "~")

    /** 生成扫码 URL（本地拼串，无网络请求）。 */
    fun connectUrl(taskId: String, source: String = DEFAULT_SOURCE): String =
        "https://$HOST/qqbot/openclaw/connect.html" +
            "?task_id=${encodeUriComponent(taskId)}" +
            "&source=${encodeUriComponent(source)}" +
            "&_wv=2"

    /** ① `POST /lite/create_bind_task`；本地生成 32 字节密钥，服务端不回传明文 Secret。 */
    @Throws(QrLoginException::class)
    fun createBindTask(httpTimeoutMs: Long = HTTP_TIMEOUT_MS): QrBindTask {
        val keyBase64 = Base64.getEncoder().encodeToString(ByteArray(32).also(random::nextBytes))
        val payload = """{"key":"$keyBase64"}"""
        val data = postForData(createUrl, payload, httpTimeoutMs, "create_bind_task")
        val taskId = data["task_id"] as? String ?: ""
        if (taskId.isEmpty()) {
            throw QrLoginException("create_bind_task: missing task_id")
        }
        return QrBindTask(taskId = taskId, key = keyBase64)
    }

    /** ② `POST /lite/poll_bind_result` */
    @Throws(QrLoginException::class)
    fun pollBindResult(taskId: String, httpTimeoutMs: Long = HTTP_TIMEOUT_MS): QrPollResult {
        val payload = """{"task_id":"$taskId"}"""
        val data = postForData(pollUrl, payload, httpTimeoutMs, "poll_bind_result")
        return QrPollResult(
            status = QrBindStatus.from((data["status"] as? Number)?.toInt() ?: 0),
            // bot_appid 历史上出现过数字与字符串两种形态
            appId = data["bot_appid"]?.toString().orEmpty(),
            encryptedSecret = data["bot_encrypt_secret"] as? String ?: "",
            userOpenid = (data["user_openid"] as? String)?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * ③ AES-256-GCM 解密 AppSecret。
     *
     * 密文布局与 JS SDK 一致：`IV(12) || ciphertext || AuthTag(16)`；
     * JCE 约定 `doFinal` 的入参为 `ciphertext || authTag`。
     */
    @Throws(QrLoginException::class)
    fun decryptSecret(encryptedBase64: String, keyBase64: String): String {
        val key = decodeBase64(keyBase64, "key")
        val blob = decodeBase64(encryptedBase64, "bot_encrypt_secret")

        if (key.size != 32) {
            throw QrLoginException("key 必须是 32 字节（AES-256），实际 ${key.size}")
        }
        if (blob.size < GCM_IV_LENGTH + GCM_TAG_LENGTH) {
            throw QrLoginException("密文长度不合法：${blob.size}")
        }

        val iv = blob.copyOfRange(0, GCM_IV_LENGTH)
        val tag = blob.copyOfRange(blob.size - GCM_TAG_LENGTH, blob.size)
        val cipherText = blob.copyOfRange(GCM_IV_LENGTH, blob.size - GCM_TAG_LENGTH)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, iv)
            )
            String(cipher.doFinal(cipherText + tag), Charsets.UTF_8)
        } catch (error: Exception) {
            throw QrLoginException("解密 AppSecret 失败: ${error.message}", error)
        }
    }

    /** 统一处理 `{ retcode, msg, data }` 信封，返回 `data`。 */
    @Suppress("UNCHECKED_CAST")
    @Throws(QrLoginException::class)
    private fun postForData(
        url: String,
        bodyJson: String,
        httpTimeoutMs: Long,
        action: String
    ): Map<String, Any?> {
        val body = postJson(url, bodyJson, httpTimeoutMs)
        val root: Map<String, Any?> = try {
            // LONG_OR_DOUBLE：默认策略把所有数字塞成 Double，bot_appid=1905453859
            // 会变成 1.905453859E9 写进配置，鉴权直接失败（实测踩到）
            val gson = com.google.gson.GsonBuilder()
                .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE)
                .create()
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
            gson.fromJson(body, type) ?: emptyMap()
        } catch (error: Exception) {
            throw QrLoginException("$action 返回内容不是合法 JSON: ${body.take(200)}", error)
        }

        val retcode = (root["retcode"] as? Number)?.toInt() ?: 0
        if (retcode != 0) {
            val message = (root["msg"] as? String)?.takeIf { it.isNotEmpty() } ?: "$action failed"
            throw QrLoginException(message)
        }
        return (root["data"] as? Map<String, Any?>) ?: emptyMap()
    }

    private fun postJson(url: String, bodyJson: String, httpTimeoutMs: Long): String {
        val timeout = httpTimeoutMs.coerceIn(1_000L, Int.MAX_VALUE.toLong()).toInt()
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            throw QrLoginException("无法连接 $url: ${error.message}", error)
        }

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (statusCode !in 200..299) {
                throw QrLoginException("HTTP $statusCode from $url")
            }
            return body
        } catch (error: QrLoginException) {
            throw error
        } catch (error: Exception) {
            throw QrLoginException("请求 $url 失败: ${error.message}", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeBase64(value: String, field: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (error: Exception) {
        throw QrLoginException("$field 不是合法的 base64 内容", error)
    }

    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 16
}
