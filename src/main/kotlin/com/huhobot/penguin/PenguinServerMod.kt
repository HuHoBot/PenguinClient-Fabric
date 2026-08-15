package com.huhobot.penguin

import com.huhobot.penguin.config.PenguinConfig
import com.huhobot.penguin.qq.QQClient
import com.huhobot.penguin.command.CommandHandler
import com.huhobot.penguin.command.PenguinCommand
import com.huhobot.penguin.filter.TextFilter
import com.huhobot.penguin.state.BotState
import com.huhobot.penguin.command.CustomCommands
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

object PenguinServerMod : ModInitializer {
    const val MOD_ID = "penguin-server-fabric"
    const val MOD_NAME = "PenguinServer-Fabric"

    val logger = LoggerFactory.getLogger(MOD_NAME)

    lateinit var config: PenguinConfig
    lateinit var state: BotState
    lateinit var custom: CustomCommands
    lateinit var qqClient: QQClient
    lateinit var commandHandler: CommandHandler

    var server: MinecraftServer? = null

    // 去重缓冲：防止 onChat 和 ServerMessageEvents 重复转发
    private val recentForwards = ArrayDeque<RecentEntry>(32)
    private data class RecentEntry(val key: String, val ts: Long)

    override fun onInitialize() {
        logger.info("$MOD_NAME 正在初始化...")

        config = PenguinConfig.load()
        state = BotState(config)
        custom = CustomCommands(config)
        qqClient = QQClient(config)
        commandHandler = CommandHandler(config, state, qqClient, custom)

        // 注册 /penguin 命令
        PenguinCommand.register()

        // 监听群消息 → 分发给命令处理器
        qqClient.onGroupMessage { msg -> commandHandler.handle(msg) }

        // 服务器启动完毕后启动 QQ 网关
        ServerLifecycleEvents.SERVER_STARTED.register { srv ->
            server = srv
            if (config.botAppId.isNotBlank() && config.botSecret.isNotBlank()) {
                qqClient.start()
                logger.info("$MOD_NAME QQ 网关已启动")
            } else {
                logger.warn("$MOD_NAME 未配置 bot.app-id / bot.secret，QQ 机器人未启动。请编辑 config/penguin-server.json")
            }
        }

        // 服务器停止时关闭网关
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            qqClient.stop()
            logger.info("$MOD_NAME QQ 网关已停止")
        }

        // 拦截玩家聊天 → 转发到 QQ
        ServerMessageEvents.CHAT_MESSAGE.register { message, sender, _ ->
            val startWith = config.chatStartWith
            val raw = message.decoratedContent().string
            if (startWith.isEmpty() || raw.startsWith(startWith)) {
                val content = if (startWith.isEmpty()) raw else raw.removePrefix(startWith)
                forwardGameMessage(sender.name.string, content)
            }
        }

        // 玩家进服通知
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val name = handler.player.name.string
            if (config.joinLeaveEnabled) {
                val text = config.joinFormat
                    .replace("{server}", config.serverName)
                    .replace("{name}", name)
                sendToAllGroups(text)
            }
        }

        // 玩家退服通知
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val name = handler.player.name.string
            if (config.joinLeaveEnabled) {
                val text = config.leaveFormat
                    .replace("{server}", config.serverName)
                    .replace("{name}", name)
                sendToAllGroups(text)
            }
        }

        logger.info("$MOD_NAME 初始化完成！")
    }

    /** 游戏消息去重转发：1500ms 窗口内相同 key 只发一次。 */
    fun forwardGameMessage(playerName: String, content: String) {
        val key = "$playerName\n$content"
        val now = System.currentTimeMillis()
        synchronized(recentForwards) {
            recentForwards.removeAll { now - it.ts > 1500 }
            if (recentForwards.any { it.key == key }) return
            recentForwards.addLast(RecentEntry(key, now))
        }
        TextFilter.audit(content, config) { filtered ->
            sendToAllGroups(formatGameMessage(playerName, filtered))
        }
    }

    fun formatGameMessage(name: String, message: String): String =
        config.chatFromGame.replace("{name}", name).replace("{message}", message)

    fun formatGroupMessage(name: String, msg: com.huhobot.penguin.qq.GroupMessage): String {
        var content = msg.content

        // 处理附件（图片、语音等）：content 为空但有 attachments
        if (content.isBlank() && !msg.attachments.isNullOrEmpty()) {
            val attachment = msg.attachments.firstOrNull()
            val contentType = attachment?.get("content_type") as? String

            when {
                contentType?.startsWith("image/") == true -> {
                    content = "[图片]"
                }
                contentType == "voice" -> {
                    val asrText = attachment?.get("asr_refer_text") as? String
                    content = if (asrText.isNullOrBlank()) "[语音]" else "[语音：$asrText]"
                }
                contentType?.startsWith("video/") == true -> {
                    content = "[视频]"
                }
                contentType == "file" -> {
                    content = "[文件]"
                }
                else -> {
                    content = "[附件]"
                }
            }
        }

        // 清理 QQ 消息中的格式化标签
        val cleaned = content
            .replace(Regex("<faceType=6,faceId=\"0\",ext=\"[^\"]+\">"), "[图片]")  // faceType=6 且 faceId=0 是真图片
            .replace(Regex("<faceType=[^>]+>"), "[表情]")  // 其他 faceType 是表情
            .replace(Regex("<[^>]+>"), "")  // 移除其他 XML 标签
            .trim()

        return config.chatFromGroup.replace("{name}", name).replace("{message}", cleaned)
    }

    /** 广播文字到游戏内所有玩家（QQ → 游戏）。 */
    fun broadcastToGame(message: String) {
        val srv = server ?: return
        srv.execute {
            try {
                srv.playerList.broadcastSystemMessage(
                    net.minecraft.network.chat.Component.literal(message), false
                )
            } catch (e: Exception) {
                logger.error("广播到游戏失败", e)
            }
        }
    }

    /** 执行服务器控制台命令，返回 (success, output)。 */
    fun runCommand(command: String): Pair<Boolean, String> {
        val srv = server ?: return Pair(false, "服务器未就绪")
        return try {
            val output = StringBuilder()
            val result = srv.commands.dispatcher.execute(
                command,
                srv.createCommandSourceStack().withSource(object : net.minecraft.commands.CommandSource {
                    override fun sendSystemMessage(message: net.minecraft.network.chat.Component) {
                        output.append(message.string).append("\n")
                    }
                    override fun acceptsSuccess() = true
                    override fun acceptsFailure() = true
                    override fun shouldInformAdmins() = false
                })
            )
            Pair(result >= 1, output.toString().trim())
        } catch (e: Exception) {
            Pair(false, e.message ?: "执行失败")
        }
    }

    private fun sendToAllGroups(content: String) {
        for (groupId in config.botGroups) {
            qqClient.sendGroupMessage(groupId, content)
        }
    }

    /** 重载配置并重启网关（对应 BDS 版的 huhobot reload）。 */
    fun reload() {
        qqClient.stop()
        config = PenguinConfig.load()
        state = BotState(config)
        custom = CustomCommands(config)
        qqClient = QQClient(config)
        commandHandler = CommandHandler(config, state, qqClient, custom)
        qqClient.onGroupMessage { msg -> commandHandler.handle(msg) }
        if (config.botAppId.isNotBlank() && config.botSecret.isNotBlank()) {
            qqClient.start()
        }
        logger.info("$MOD_NAME 配置已重载")
    }
}
