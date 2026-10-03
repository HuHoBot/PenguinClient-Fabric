package com.huhobot.penguin.qr

/** 扫码绑定成功后拿到的机器人凭据。 */
data class QrCredentials(
    val appId: String,
    val appSecret: String,
    val userOpenid: String? = null
)

/**
 * 扫码绑定任务。
 *
 * [key] 是本地生成的 AES-256 密钥（base64），必须与 [taskId] 配对使用；
 * 服务端不回传明文 Secret，只回传用它加密后的密文。
 */
data class QrBindTask(val taskId: String, val key: String)

/** 扫码状态机，取值对齐 QQ 开放平台 `poll_bind_result` 的 `status`。 */
enum class QrBindStatus(val code: Int) {
    /** 未知状态，继续轮询。 */
    NONE(0),

    /** 等待扫码 / 已扫码待确认，继续轮询。 */
    PENDING(1),

    /** 扫码并确认成功，可以解密 AppSecret。 */
    COMPLETED(2),

    /** 二维码已过期，需要重新创建任务并刷新二维码。 */
    EXPIRED(3);

    companion object {
        fun from(code: Int): QrBindStatus = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/** 单次轮询结果。 */
data class QrPollResult(
    val status: QrBindStatus,
    val appId: String,
    val encryptedSecret: String,
    val userOpenid: String?
)

/** 扫码流程中出现的协议/网络错误。 */
class QrLoginException(message: String, cause: Throwable? = null) : Exception(message, cause)
