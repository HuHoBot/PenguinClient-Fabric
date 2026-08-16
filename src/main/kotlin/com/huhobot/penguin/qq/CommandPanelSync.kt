package com.huhobot.penguin.qq

import com.google.gson.Gson
import com.huhobot.penguin.command.CommandMetadata
import com.huhobot.penguin.config.PenguinConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.io.OutputStreamWriter
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import javax.net.ssl.HttpsURLConnection

private val logger = LoggerFactory.getLogger("PenguinServer-Fabric/PanelSync")

class CommandPanelSync(
    private val qqClient: QQClient,
    private val cfg: PenguinConfig,
    private val stateFile: File
) {
    private var cachedPanelId: String? = null
    private var cachedFingerprint: String? = null
    private val gson = Gson()

    init {
        loadState()
    }

    fun syncCommands(commands: List<CommandMetadata>) {
        if (cfg.botGroups.isEmpty()) {
            logger.warn("未配置 bot.groups，跳过指令面板同步")
            return
        }

        val limitedCommands = commands.sortedBy { it.name }.take(20)
        if (commands.size > 20) {
            logger.warn("命令数量超过限制，仅同步前 20 个")
        }

        val fingerprint = calculateFingerprint(limitedCommands)
        if (fingerprint == cachedFingerprint && cachedPanelId != null) {
            logger.info("面板内容未变化，跳过同步")
            return
        }

        try {
            val token = qqClient.getAccessTokenSync()

            // 删除旧面板
            listPanels(token, "group").forEach { panelId ->
                try {
                    deletePanel(token, panelId)
                    logger.info("已删除面板: $panelId")
                } catch (e: Exception) {
                    logger.warn("删除失败: ${e.message}")
                }
            }

            // 创建新面板（必须包含 type 字段）
            val items = limitedCommands.map {
                mapOf(
                    "type" to "command",
                    "name" to it.name,
                    "desc" to it.description,
                    "only_admin" to it.adminOnly
                )
            }

            val panelId = createPanel(token, items)
            cachedPanelId = panelId
            cachedFingerprint = fingerprint
            saveState()

            logger.info("面板同步成功: $panelId")
        } catch (e: Exception) {
            logger.error("面板同步失败: ${e.message}")
        }
    }

    private fun listPanels(token: String, scope: String): List<String> {
        val conn = URL("https://api.bot.qq.com/v2/panels?scope=$scope&limit=50").openConnection() as HttpsURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "QQBot $token")
        conn.setRequestProperty("X-Union-Appid", cfg.botAppId)
        val resp = conn.inputStream.bufferedReader().readText()
        @Suppress("UNCHECKED_CAST")
        val body = gson.fromJson(resp, Map::class.java) as Map<String, Any>
        return (body["records"] as? List<*>)?.mapNotNull {
            @Suppress("UNCHECKED_CAST")
            (it as? Map<String, Any>)?.get("panel_id") as? String
        } ?: emptyList()
    }

    private fun deletePanel(token: String, panelId: String) {
        val conn = URL("https://api.bot.qq.com/v2/panels/$panelId").openConnection() as HttpsURLConnection
        conn.requestMethod = "DELETE"
        conn.setRequestProperty("Authorization", "QQBot $token")
        conn.setRequestProperty("X-Union-Appid", cfg.botAppId)
        conn.responseCode
    }

    private fun createPanel(token: String, items: List<Map<String, Any>>): String {
        val body = mapOf(
            "scope" to "group",
            "target_type" to "specific",
            "group_openids" to cfg.botGroups,
            "panel" to mapOf("remark" to "HuHoBot Penguin", "items" to items)
        )

        val conn = URL("https://api.bot.qq.com/v2/panels").openConnection() as HttpsURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "QQBot $token")
        conn.setRequestProperty("X-Union-Appid", cfg.botAppId)

        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
            it.write(gson.toJson(body))
        }

        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()

        if (code !in 200..299) throw RuntimeException("HTTP $code: $resp")

        @Suppress("UNCHECKED_CAST")
        return (gson.fromJson(resp, Map::class.java) as Map<String, Any>)["panel_id"] as String
    }

    private fun calculateFingerprint(commands: List<CommandMetadata>): String {
        val content = commands.joinToString("|") { "${it.name}:${it.description}:${it.adminOnly}" }
        return MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun loadState() {
        if (!stateFile.exists()) return
        try {
            Properties().apply {
                stateFile.inputStream().use { load(it) }
                cachedPanelId = getProperty("panel_id")?.trim()?.takeIf { it.isNotEmpty() }
                cachedFingerprint = getProperty("fingerprint")?.trim()?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            logger.warn("加载状态失败: ${e.message}")
        }
    }

    private fun saveState() {
        try {
            stateFile.parentFile?.mkdirs()
            Properties().apply {
                cachedPanelId?.let { setProperty("panel_id", it) }
                cachedFingerprint?.let { setProperty("fingerprint", it) }
                stateFile.outputStream().use { store(it, "Panel State") }
            }
        } catch (e: Exception) {
            logger.warn("保存状态失败: ${e.message}")
        }
    }
}
