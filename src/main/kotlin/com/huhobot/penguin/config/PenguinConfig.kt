package com.huhobot.penguin.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

data class PenguinConfig(
    var enabled: Boolean = true,
    var chatPrefix: String = "#",
    var showQQMessages: Boolean = true,
    var showJoinLeave: Boolean = true,
    var messageFormat: MessageFormat = MessageFormat(),
    var filters: Filters = Filters()
) {
    data class MessageFormat(
        var fromQQ: String = "§b[QQ]§r {name}: {message}",
        var toQQ: String = "[游戏] {name}: {message}",
        var joinServer: String = "§a🟢 {name} 进入服务器§r",
        var leaveServer: String = "§c🔴 {name} 退出服务器§r"
    )

    data class Filters(
        var enableFilter: Boolean = true,
        var sensitiveWords: MutableList<String> = mutableListOf()
    )

    private val logger = LoggerFactory.getLogger("PenguinClient")

    private val configFile: File
        get() = FabricLoader.getInstance().configDir.resolve("penguin-client.json").toFile()

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun load() {
        if (!configFile.exists()) {
            save()
            logger.info("已创建默认配置文件: ${configFile.absolutePath}")
            return
        }

        try {
            val loadedConfig = gson.fromJson(configFile.readText(), PenguinConfig::class.java)
            this.enabled = loadedConfig.enabled
            this.chatPrefix = loadedConfig.chatPrefix
            this.showQQMessages = loadedConfig.showQQMessages
            this.showJoinLeave = loadedConfig.showJoinLeave
            this.messageFormat = loadedConfig.messageFormat
            this.filters = loadedConfig.filters

            logger.info("配置文件加载成功")
        } catch (e: Exception) {
            logger.error("配置文件加载失败，使用默认配置", e)
            save()
        }
    }

    fun save() {
        try {
            configFile.parentFile?.mkdirs()
            configFile.writeText(gson.toJson(this))
            logger.info("配置文件已保存")
        } catch (e: Exception) {
            logger.error("配置文件保存失败", e)
        }
    }

    fun reload() {
        load()
    }
}
