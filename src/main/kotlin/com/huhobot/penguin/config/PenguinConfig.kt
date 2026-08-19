package com.huhobot.penguin.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("PenguinServer-Fabric/Config")
private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

private const val CONFIG_VERSION = 2

/**
 * 配置门面，对齐 BDS 版 config.js 的键名与默认值。
 * 使用点号展平的 Map 内部存储，对外暴露强类型属性。
 */
class PenguinConfig private constructor(private val raw: MutableMap<String, Any?>) {

    // ---- bot ----
    val botAppId: String get() = getString("bot.app-id", "")
    val botSecret: String get() = getString("bot.secret", "")
    val botName: String get() = getString("bot.name", "HuHoBot")
    val botGroups: List<String> get() = getList("bot.groups")
    val serverName: String get() = getString("serverName", "")

    // ---- chat-format ----
    val chatFromGame: String get() = getString("chat-format.from-game", "[游戏] {name}: {message}")
    val chatFromGroup: String get() = getString("chat-format.from-group", "[QQ] {name}: {message}")
    val chatPostChat: Boolean get() = getBool("chat-format.post-chat", true)
    val chatStartWith: String get() = getString("chat-format.start-with", "")

    // ---- whitelist ----
    val whitelistAddCmd: String get() = getString("whitelist.add-command", "whitelist add {name}")
    val whitelistDelCmd: String get() = getString("whitelist.del-command", "whitelist remove {name}")

    // ---- filter ----
    val filterRegex: List<String> get() = getList("filter-regex")

    // ---- admin ----
    val adminMode: String get() = getString("admin.mode", "both")
    val adminOpenids: List<String> get() = getList("admin.openids")

    // ---- features ----
    val featureFullAmount: Boolean get() = getBool("features.full-amount", false)
    val featureMarkdownOnline: Boolean get() = getBool("features.markdown-query-online", true)
    val featureMarkdownWhitelist: Boolean get() = getBool("features.markdown-whitelist", true)

    // ---- join-leave ----
    val joinLeaveEnabled: Boolean get() = getBool("join-leave.enabled", true)
    val joinFormat: String get() = getString("join-leave.join-format", "[{server}] 🟢{name}进入服务器")
    val leaveFormat: String get() = getString("join-leave.leave-format", "[{server}] 🔴{name}退出服务器")

    // ---- motd ----
    val motdServerIp: String get() = getString("motd.server-ip", "127.0.0.1")
    val motdServerPort: Int get() = (raw["motd.server-port"] as? Number)?.toInt() ?: 25565
    val motdApi: String get() = getString("motd.api", "https://motd.txssb.cn/api/status_img?theme=simple&ip={ip}&port={port}&dark=true&lang=zh-CN")
    val motdText: String get() = getString("motd.text", "[{server}] 在线玩家：{online}\n{players}")
    val motdPostImg: Boolean get() = getBool("motd.post-img", false)
    val motdUseMarkdown: Boolean get() = getBool("motd.use-markdown", false)

    // ---- audit (OpenAI 兼容二审) ----
    val auditBaseUrl: String get() = getString("audit.base-url", "")
    val auditApiKey: String get() = getString("audit.api-key", "")
    val auditModel: String get() = getString("audit.model", "gpt-4o-mini")

    // ---- debug ----
    val debugLogEvents: Boolean get() = getBool("debug.log-events", false)

    // ---- bstats ----
    val bstatsEnabled: Boolean get() = getBool("bstats.enabled", true)
    val bstatsUuid: String get() = getString("bstats.uuid", "")

    /** 某个内置命令是否启用（默认 true）。 */
    fun isCommandEnabled(name: String): Boolean = getBool("commands.$name", true)

    @Suppress("UNCHECKED_CAST")
    fun getCustomCommands(): List<Map<String, Any?>> {
        val v = raw["custom-commands"] ?: return emptyList()
        return v as? List<Map<String, Any?>> ?: emptyList()
    }

    // ---- 读取原语 ----

    fun getString(key: String, default: String): String {
        val v = raw[key] ?: return default
        return v.toString()
    }

    fun getBool(key: String, default: Boolean): Boolean {
        val v = raw[key] ?: return default
        return when (v) {
            is Boolean -> v
            is String -> v.lowercase() in listOf("true", "1", "yes", "on")
            is Number -> v.toInt() != 0
            else -> default
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun getList(key: String): List<String> {
        val v = raw[key] ?: return emptyList()
        return (v as? List<*>)?.map { it.toString() } ?: emptyList()
    }

    companion object {
        private val COMMAND_NAMES = listOf(
            "查信息", "查管理", "加管理", "删管理", "管理方式",
            "添加白名单", "删除白名单", "查白名单", "查在线", "在线服务器",
            "发信息", "发消息", "执行命令", "执行", "管理员执行",
            "全量", "认证", "解除认证", "绑定白名单", "解绑白名单", "解除绑定"
        )

        private val DEFAULTS: Map<String, Any?> = buildMap {
            put("config-version", CONFIG_VERSION)
            put("bot.app-id", "")
            put("bot.secret", "")
            put("bot.name", "HuHoBot")
            put("bot.groups", emptyList<String>())
            put("serverName", "")
            put("chat-format.from-game", "[游戏] {name}: {message}")
            put("chat-format.from-group", "[QQ] {name}: {message}")
            put("chat-format.post-chat", true)
            put("chat-format.start-with", "")
            put("whitelist.add-command", "whitelist add {name}")
            put("whitelist.del-command", "whitelist remove {name}")
            put("filter-regex", emptyList<String>())
            put("admin.mode", "both")
            put("admin.openids", emptyList<String>())
            put("features.full-amount", false)
            put("features.markdown-query-online", true)
            put("features.markdown-whitelist", true)
            put("join-leave.enabled", true)
            put("join-leave.join-format", "[{server}] 🟢{name}进入服务器")
            put("join-leave.leave-format", "[{server}] 🔴{name}退出服务器")
            put("motd.server-ip", "127.0.0.1")
            put("motd.server-port", 25565)
            put("motd.api", "https://motd.txssb.cn/api/status_img?theme=simple&ip={ip}&port={port}&dark=true&lang=zh-CN")
            put("motd.text", "[{server}] 在线玩家：{online}\n{players}")
            put("motd.post-img", false)
            put("motd.use-markdown", false)
            put("audit.base-url", "")
            put("audit.api-key", "")
            put("audit.model", "gpt-4o-mini")
            put("custom-commands", emptyList<Any>())
            put("debug.log-events", false)
            put("bstats.enabled", true)
            put("bstats.uuid", "") // 会在load时自动生成
            for (name in COMMAND_NAMES) put("commands.$name", true)
        }

        fun configFile(): File {
            val dir = FabricLoader.getInstance().configDir.toFile()
            return File(dir, "penguin-server.json")
        }

        fun load(): PenguinConfig {
            val file = configFile()
            var nested: Map<String, Any?> = emptyMap()
            if (file.exists()) {
                try {
                    val type = object : TypeToken<Map<String, Any?>>() {}.type
                    nested = gson.fromJson(file.readText(), type) ?: emptyMap()
                } catch (e: Exception) {
                    logger.warn("config 读取失败，使用默认配置：${e.message}")
                }
            }

            val flat = flatten(nested, "").toMutableMap()

            // 迁移旧键
            if ("chat-format.post-prefix" in flat && "chat-format.start-with" !in flat) {
                flat["chat-format.start-with"] = flat["chat-format.post-prefix"]
            }
            flat.remove("chat-format.post-prefix")

            var changed = false
            for ((k, v) in DEFAULTS) {
                if (k !in flat) { flat[k] = v; changed = true }
            }

            // 自动生成 bstats UUID
            if (flat["bstats.uuid"] == null || flat["bstats.uuid"].toString().isBlank()) {
                flat["bstats.uuid"] = java.util.UUID.randomUUID().toString()
                changed = true
            }

            val prev = (flat["config-version"] as? Number)?.toInt() ?: 0
            if (prev != CONFIG_VERSION) { flat["config-version"] = CONFIG_VERSION; changed = true }

            if (changed || !file.exists()) {
                try {
                    file.parentFile?.mkdirs()
                    file.writeText(gson.toJson(nest(flat)) + "\n")
                    logger.info("配置文件已写入/升级（版本 $prev → $CONFIG_VERSION）")
                } catch (e: Exception) {
                    logger.warn("配置写入失败：${e.message}")
                }
            }

            return PenguinConfig(flat)
        }

        @Suppress("UNCHECKED_CAST")
        private fun flatten(map: Map<String, Any?>, prefix: String): Map<String, Any?> {
            val out = mutableMapOf<String, Any?>()
            for ((k, v) in map) {
                val full = if (prefix.isEmpty()) k else "$prefix.$k"
                if (v is Map<*, *> && v.isNotEmpty() && v.keys.first() is String) {
                    out.putAll(flatten(v as Map<String, Any?>, full))
                } else {
                    out[full] = v
                }
            }
            return out
        }

        private fun nest(flat: Map<String, Any?>): Map<String, Any?> {
            val root = mutableMapOf<String, Any?>()
            for ((key, value) in flat) {
                val parts = key.split(".")
                var node = root
                for (i in 0 until parts.size - 1) {
                    val part = parts[i]
                    @Suppress("UNCHECKED_CAST")
                    node = node.getOrPut(part) { mutableMapOf<String, Any?>() } as MutableMap<String, Any?>
                }
                node[parts.last()] = value
            }
            return root
        }
    }
}
