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

/**
 * QQ 群指令面板同步器
 *
 * 将命令元数据同步到 QQ 群指令面板，支持增量更新和状态持久化
 */
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

    /**
     * 同步命令到 QQ 群指令面板
     */
    fun syncCommands(commands: List<CommandMetadata>) {
        if (cfg.botGroups.isEmpty()) {
            logger.warn("未配置 bot.groups，跳过指令面板同步")
            return
        }

        if (commands.isEmpty()) {
            logger.warn("没有可同步的命令")
            return
        }

        // 计算指纹
        val fingerprint = calculateFingerprint(commands)
        if (fingerprint == cachedFingerprint && cachedPanelId != null) {
            logger.info("指令面板内容未变化，跳过同步 (panel_id=$cachedPanelId)")
            return
        }

        try {
            // 构建面板数据
            val panelItems = commands.sortedBy { it.name }.map {
                mapOf(
                    "name" to it.name,
                    "desc" to it.description,
                    "only_admin" to it.adminOnly
                )
            }

            // 调用 QQ API
            val panelId = if (cachedPanelId == null) {
                createPanel(panelItems)
            } else {
                try {
                    updatePanel(cachedPanelId!!, panelItems)
                    cachedPanelId
                } catch (e: Exception) {
                    logger.warn("更新面板失败，尝试重新创建: ${e.message}")
                    createPanel(panelItems)
                }
            }

            // 保存状态
            cachedPanelId = panelId
            cachedFingerprint = fingerprint
            saveState()

            logger.info("指令面板同步成功: panel_id=$panelId, commands=${commands.size}")
        } catch (e: Exception) {
            logger.error("指令面板同步失败: ${e.message}", e)
        }
    }

    private fun createPanel(items: List<Map<String, Any>>): String {
        val token = qqClient.getAccessTokenSync()

        val body = mapOf(
            "scope" to "group",
            "target" to "specific",
            "group_openids" to cfg.botGroups,
            "panel_definition" to mapOf(
                "remark" to "HuHoBot Penguin 指令面板",
                "items" to items
            )
        )

        logger.info("创建指令面板: groups=${cfg.botGroups.size}, items=${items.size}")

        val response = postJson(
            "https://api.bot.qq.com/openapi/panel/create",
            body,
            mapOf(
                "Authorization" to "QQBot $token",
                "X-Union-Appid" to cfg.botAppId
            )
        )

        val panelId = response["panel_id"] as? String
        if (panelId == null) {
            logger.error("创建面板失败: 未返回 panel_id, response=$response")
            throw RuntimeException("创建面板失败: 未返回 panel_id")
        }

        return panelId
    }

    private fun updatePanel(panelId: String, items: List<Map<String, Any>>) {
        val token = qqClient.getAccessTokenSync()

        val body = mapOf(
            "scope" to "group",
            "target" to "specific",
            "group_openids" to cfg.botGroups,
            "panel_definition" to mapOf(
                "remark" to "HuHoBot Penguin 指令面板",
                "items" to items
            )
        )

        logger.info("更新指令面板: panel_id=$panelId, items=${items.size}")

        postJson(
            "https://api.bot.qq.com/openapi/panel/$panelId/update",
            body,
            mapOf(
                "Authorization" to "QQBot $token",
                "X-Union-Appid" to cfg.botAppId
            )
        )
    }

    private fun calculateFingerprint(commands: List<CommandMetadata>): String {
        val content = commands.sortedBy { it.name }
            .joinToString("|") { "${it.name}:${it.description}:${it.adminOnly}" }

        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun loadState() {
        if (!stateFile.exists()) return

        try {
            val props = Properties()
            stateFile.inputStream().use { props.load(it) }
            cachedPanelId = props.getProperty("panel_id")?.takeIf { it.isNotBlank() }
            cachedFingerprint = props.getProperty("fingerprint")?.takeIf { it.isNotBlank() }
            logger.info("加载面板状态: panel_id=$cachedPanelId")
        } catch (e: Exception) {
            logger.warn("加载面板状态失败: ${e.message}")
        }
    }

    private fun saveState() {
        try {
            stateFile.parentFile?.mkdirs()
            val props = Properties()
            props.setProperty("panel_id", cachedPanelId ?: "")
            props.setProperty("fingerprint", cachedFingerprint ?: "")
            stateFile.outputStream().use { props.store(it, "HuHoBot QQ Panel State") }
        } catch (e: Exception) {
            logger.warn("保存面板状态失败: ${e.message}")
        }
    }

    private fun postJson(
        url: String,
        body: Map<String, Any>,
        headers: Map<String, String>
    ): Map<String, Any> {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

        val bodyJson = gson.toJson(body)
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(bodyJson) }

        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader(StandardCharsets.UTF_8).readText()

        if (code !in 200..299) {
            throw RuntimeException("HTTP $code: ${resp.take(500)}")
        }

        @Suppress("UNCHECKED_CAST")
        return gson.fromJson(resp, Map::class.java) as Map<String, Any>
    }
}
