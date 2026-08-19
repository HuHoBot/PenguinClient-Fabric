package com.huhobot.penguin.metrics

import com.google.gson.Gson
import org.slf4j.LoggerFactory
import java.io.File
import java.io.OutputStreamWriter
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.*
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

private val logger = LoggerFactory.getLogger("BStats")

class BStats(
    private val pluginId: Int,
    private val pluginName: String,
    private val pluginVersion: String,
    private val enabled: Boolean
) {
    private val serverUUID: String
    private val gson = Gson()
    private val baseUrl = "https://bstats.org/api/v2/data/bukkit"

    init {
        serverUUID = loadOrCreateUUID()
    }

    private fun loadOrCreateUUID(): String {
        val uuidFile = File("config/bstats/uuid.txt")

        if (uuidFile.exists()) {
            try {
                return uuidFile.readText().trim()
            } catch (e: Exception) {
                logger.warn("读取UUID失败: ${e.message}")
            }
        }

        val newUUID = UUID.randomUUID().toString()
        try {
            uuidFile.parentFile?.mkdirs()
            uuidFile.writeText(newUUID)
        } catch (e: Exception) {
            logger.warn("写入UUID失败: ${e.message}")
        }

        return newUUID
    }

    private fun collectData(): Map<String, Any> {
        val playerCount = try {
            val srv = com.huhobot.penguin.PenguinServerMod.server
            srv?.playerManager?.currentPlayerCount ?: 0
        } catch (e: Exception) { 0 }

        val mcVersion = try {
            net.minecraft.SharedConstants.getGameVersion().name
        } catch (e: Exception) { "1.20.1" }

        val osName = System.getProperty("os.name", "Unknown")
        val osArch = System.getProperty("os.arch", "Unknown")
        val osVersion = System.getProperty("os.version", "Unknown")
        val javaVersion = System.getProperty("java.version", "Unknown")
        val coreCount = Runtime.getRuntime().availableProcessors()

        return mapOf(
            "serverUUID" to serverUUID,
            "metricsVersion" to "2",
            "playerAmount" to playerCount,
            "onlineMode" to 1,
            "bukkitVersion" to mcVersion,
            "javaVersion" to javaVersion,
            "osName" to osName,
            "osArch" to osArch,
            "osVersion" to osVersion,
            "coreCount" to coreCount,
            "service" to mapOf(
                "id" to pluginId,
                "pluginVersion" to pluginVersion,
                "customCharts" to listOf<Map<String, Any>>()
            )
        )
    }

    private fun submit() {
        try {
            val payload = collectData()
            val json = gson.toJson(payload)

            val conn = URL(baseUrl).openConnection() as HttpsURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
                it.write(json)
            }

            val code = conn.responseCode
            if (code == 200) {
                logger.info("bStats数据上报成功")
            } else {
                logger.warn("bStats上报失败: HTTP $code")
            }
        } catch (e: Exception) {
            logger.warn("bStats上报异常: ${e.message}")
        }
    }

    fun start() {
        if (!enabled) {
            logger.info("bStats已禁用（可在config中启用）")
            return
        }

        // 首次上报：随机30-60秒延迟
        val firstDelay = (30000 + Math.random() * 30000).toLong()

        thread(isDaemon = true, name = "bStats-Init") {
            Thread.sleep(firstDelay)
            submit()

            // 之后每30分钟上报一次
            while (true) {
                Thread.sleep(30 * 60 * 1000)
                submit()
            }
        }

        logger.info("bStats已启动 (插件ID: $pluginId)")
    }
}
