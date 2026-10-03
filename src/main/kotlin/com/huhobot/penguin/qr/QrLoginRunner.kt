package com.huhobot.penguin.qr

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

private val logger = LoggerFactory.getLogger("PenguinServer-Fabric/QrLogin")

/**
 * 扫码绑定流程的执行器（阻塞式，需要在后台线程运行）。
 *
 * 循环逻辑：
 * ```
 * while (未取消) {
 *     创建绑定任务 → 输出二维码
 *     轮询直到 COMPLETED / EXPIRED
 *     COMPLETED → 解密 AppSecret → 回调 → 结束
 *     EXPIRED   → 自动换一张二维码继续
 * }
 * ```
 * 单次轮询失败按静默重试处理，不会中断流程。
 */
class QrLoginRunner(
    private val source: String,
    private val onCredentials: (QrCredentials) -> Unit,
    private val pollIntervalMs: Long = QqBotQrLogin.POLL_INTERVAL_MS,
    private val httpTimeoutMs: Long = QqBotQrLogin.HTTP_TIMEOUT_MS
) {
    @Volatile
    private var cancelled = false

    /** 取消扫码流程；阻塞中的等待最长会在 [SLEEP_SLICE_MS] 内返回。 */
    fun cancel() {
        cancelled = true
    }

    fun run() {
        var createAttempts = 0

        while (!cancelled) {
            val task = try {
                QqBotQrLogin.createBindTask(httpTimeoutMs)
            } catch (error: Exception) {
                if (cancelled) return
                createAttempts++
                if (createAttempts >= MAX_CREATE_ATTEMPTS) {
                    logger.error(
                        "创建 QQ 扫码绑定任务连续失败 $createAttempts 次（${error.message}），" +
                            "已停止扫码流程，可重载插件后重试"
                    )
                    return
                }
                logger.warn(
                    "创建 QQ 扫码绑定任务失败（第 $createAttempts 次）: ${error.message}，" +
                        "${CREATE_RETRY_DELAY_MS / 1000} 秒后重试"
                )
                sleepInterruptibly(CREATE_RETRY_DELAY_MS)
                continue
            }

            createAttempts = 0
            displayQrCode(QqBotQrLogin.connectUrl(task.taskId, source))

            while (!cancelled) {
                val result = pollOrNull(task.taskId)
                if (result == null) {
                    sleepInterruptibly(pollIntervalMs)
                    continue
                }

                when (result.status) {
                    QrBindStatus.COMPLETED -> {
                        val credentials = decryptOrNull(result, task.key) ?: return
                        onCredentials(credentials)
                        return
                    }

                    QrBindStatus.EXPIRED -> {
                        if (cancelled) return
                        logger.info("二维码已过期，正在刷新…")
                        break
                    }

                    QrBindStatus.PENDING, QrBindStatus.NONE -> sleepInterruptibly(pollIntervalMs)
                }
            }
        }
    }

    private fun pollOrNull(taskId: String): QrPollResult? = try {
        QqBotQrLogin.pollBindResult(taskId, httpTimeoutMs)
    } catch (_: Exception) {
        // 单次轮询失败属于正常抖动
        null
    }

    private fun decryptOrNull(result: QrPollResult, taskKey: String): QrCredentials? {
        val secret = try {
            QqBotQrLogin.decryptSecret(result.encryptedSecret, taskKey)
        } catch (error: Exception) {
            logger.error("解密 AppSecret 失败，扫码绑定中止: ${error.message}")
            return null
        }

        if (result.appId.isBlank() || secret.isBlank()) {
            logger.error("QQ 开放平台返回的 AppID 或 AppSecret 为空，扫码绑定中止")
            return null
        }
        return QrCredentials(appId = result.appId, appSecret = secret, userOpenid = result.userOpenid)
    }

    private fun displayQrCode(url: String) {
        logger.info("请使用手机 QQ 扫描下方二维码完成机器人绑定：")
        val qrCode = TerminalQrRenderer.render(url)
        if (qrCode == null) {
            logger.warn("当前环境无法渲染二维码，请复制下方链接自行生成二维码")
        } else {
            logger.info("\n$qrCode")
        }
        logger.info("二维码链接: $url")
    }

    private fun sleepInterruptibly(millis: Long) {
        var remaining = millis
        while (remaining > 0 && !cancelled) {
            val slice = minOf(remaining, SLEEP_SLICE_MS)
            try {
                Thread.sleep(slice)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                cancelled = true
                return
            }
            remaining -= slice
        }
    }

    private companion object {
        const val MAX_CREATE_ATTEMPTS = 5
        const val CREATE_RETRY_DELAY_MS = 10_000L
        const val SLEEP_SLICE_MS = 250L
    }
}

/**
 * 扫码绑定的协调器：保证同一时间只有一个绑定会话。
 *
 * 绑定成功后通过 [onCredentials] 回调交给调用方落盘配置并重连 QQ 客户端。
 */
object QrLoginManager {
    private val active = AtomicReference<QrLoginRunner?>(null)

    val isRunning: Boolean get() = active.get() != null

    /** 启动扫码绑定流程；已有会话正在运行时返回 false。 */
    fun start(
        source: String = QqBotQrLogin.DEFAULT_SOURCE,
        onCredentials: (QrCredentials) -> Unit
    ): Boolean {
        val runner = QrLoginRunner(source, onCredentials)
        if (!active.compareAndSet(null, runner)) return false

        return try {
            Thread {
                try {
                    runner.run()
                } finally {
                    active.compareAndSet(runner, null)
                }
            }.also { it.isDaemon = true; it.name = "penguin-qr-login" }.start()
            true
        } catch (error: Exception) {
            active.compareAndSet(runner, null)
            logger.error("启动扫码绑定线程失败: ${error.message}")
            false
        }
    }

    /** 取消正在进行的扫码绑定流程（关服时调用）。 */
    fun cancel() {
        active.getAndSet(null)?.cancel()
    }
}
