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

/** QQ 群指令面板硬上限。 */
private const val PANEL_MAX_ITEMS = 20

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
     * 作废内存中的面板缓存，强制下次同步真的打接口。
     *
     * 附属插件重载后命令集合一定变了，但指纹可能与上次相同（同一批插件、
     * 同一批命令），不清缓存会被「内容未变化」直接跳过，QQ 群里留着旧面板。
     */
    fun invalidateCache() {
        cachedFingerprint = null
    }

    /**
     * 面板候选命令：对齐主仓库 MenuManager 的排序策略。
     *
     * 内置命令优先、附属插件命令补足——纯按名称排序会让插件命令被整段挤掉
     * （内置已 38+ 条，上限 20）。同名的插件命令直接丢弃。
     */
    private fun eligiblePanelCommands(commands: List<CommandMetadata>): List<CommandMetadata> {
        val builtin = commands.filter { it.addonSource.isNullOrBlank() }
        val addon = commands.filter { !it.addonSource.isNullOrBlank() }
        val builtinNames = builtin.mapTo(mutableSetOf()) { it.name }
        return (builtin + addon.filter { it.name !in builtinNames }).sortedBy { it.name }
    }

    fun syncCommands(commands: List<CommandMetadata>) {
        if (cfg.botGroups.isEmpty()) {
            logger.warn("未配置 bot.groups，跳过指令面板同步")
            return
        }

        val candidates = eligiblePanelCommands(commands)
        if (candidates.size > PANEL_MAX_ITEMS) {
            logger.warn("命令数量 ${candidates.size} 超过面板上限 $PANEL_MAX_ITEMS，仅同步前 $PANEL_MAX_ITEMS 个")
        }
        val limitedCommands = candidates.take(PANEL_MAX_ITEMS)

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
