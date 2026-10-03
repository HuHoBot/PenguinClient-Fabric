package com.huhobot.penguin

import com.huhobot.penguin.config.PenguinConfig
import com.huhobot.penguin.qq.QQClient
import com.huhobot.penguin.qq.CommandPanelSync
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
import java.io.File

object PenguinServerMod : ModInitializer {
    const val MOD_ID = "penguin-server-fabric"
    const val MOD_NAME = "PenguinServer-Fabric"

    val logger = LoggerFactory.getLogger(MOD_NAME)

    lateinit var config: PenguinConfig
    lateinit var state: BotState
    lateinit var custom: CustomCommands
    lateinit var qqClient: QQClient
    lateinit var commandHandler: CommandHandler
    lateinit var panelSync: CommandPanelSync

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

        // 初始化面板同步器
        val configDir = File("config")
        val stateFile = File(configDir, "penguin-panel-state.properties")
        panelSync = CommandPanelSync(qqClient, config, stateFile)

        // 注册 /penguin 命令
        PenguinCommand.register()

        // 监听群消息 → 分发给命令处理器
        qqClient.onGroupMessage { msg -> commandHandler.handle(msg) }
        registerQqEventListeners(qqClient)

        // 服务器启动完毕后启动 QQ 网关
        ServerLifecycleEvents.SERVER_STARTED.register { srv ->
            server = srv

            // 初始化 bStats
            try {
                val bstats = com.huhobot.penguin.metrics.BStats(33526, MOD_NAME, "1.1.5", config)
                bstats.start()
            } catch (e: Exception) {
                logger.warn("$MOD_NAME bStats 初始化失败：${e.message}")
            }

            if (config.botAppId.isNotBlank() && config.botSecret.isNotBlank()) {
                qqClient.start()
                logger.info("$MOD_NAME QQ 网关已启动")
                // 启动后同步命令面板
                syncCommandPanel()
            } else {
                logger.warn("$MOD_NAME 未配置 bot.app-id / bot.secret，QQ 机器人未启动。请编辑 config/penguin-server.json")
            }
        }

        // 服务器停止时关闭网关
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            com.huhobot.penguin.qr.QrLoginManager.cancel()
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
        // post-chat 关闭时，不将游戏内聊天转发至 QQ 群。
        if (!config.chatPostChat) {
           return
        }
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

    /** 附属插件/命令用：向所有已配置 QQ 群发文本。 */
    fun sendTextToAllGroups(content: String) = sendToAllGroups(content)

    /**
     * 挂群成员 / 入群申请 / 互动事件监听。
     *
     * 交互事件必须在 5 秒内[QQClient.respondInteraction] 回执，否则 QQ 记为无响应。
     */
    private fun registerQqEventListeners(client: QQClient) {
        client.onMemberJoin { evt ->
            if (config.groupMemberEventToGame) {
                broadcastToGame("[QQ]🟢${evt.username ?: evt.userId} 加入 QQ 群")
            }
        }
        client.onMemberLeave { evt ->
            if (config.groupMemberEventToGame) {
                broadcastToGame("[QQ]🔴${evt.username ?: evt.userId} 退出 QQ 群")
            }
        }
        client.onJoinRequest { evt ->
            val name = evt.username ?: evt.userId
            if (config.groupJoinRequestToGame) {
                broadcastToGame("[QQ]📝$name 申请加入 QQ 群")
            }
            logger.info("收到入群申请：群 ${evt.groupId} 用户 $name${evt.riskTips?.let { "（风险提示：$it）" } ?: ""}")
        }
        client.onInteraction { evt ->
            if (!client.respondInteractionNow(evt.id, com.huhobot.penguin.qq.InteractionCode.SUCCESS)) {
                logger.warn("互动事件回执失败：${evt.id}")
            }
            commandHandler.handleInteraction(evt)
        }
    }

    /**
     * 启动 QQ 机器人扫码绑定。绑定成功后写回配置并重启网关。
     *
     * @return false 表示已有扫码会话在进行
     */
    fun startQrBind(feedback: (String) -> Unit = {}): Boolean {
        val started = com.huhobot.penguin.qr.QrLoginManager.start { credentials ->
            logger.info("扫码绑定成功，正在写入配置…")
            val ok = saveCredentials(credentials)
            feedback(
                if (ok) "扫码绑定成功，已写入配置，正在重连 QQ 网关…"
                else "扫码绑定成功，但写入配置失败：${PenguinConfig.configFile()}"
            )
            if (ok) reload()
        }
        feedback(
            if (started) "已开始扫码绑定流程，请查看服务端控制台输出的二维码"
            else "已有扫码绑定会话正在进行中"
        )
        return started
    }

    /** 把扫码拿到的凭据写回 penguin-server.json，保留其余配置项。 */
    private fun saveCredentials(credentials: com.huhobot.penguin.qr.QrCredentials): Boolean = try {
        val file = PenguinConfig.configFile()
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        val root: MutableMap<String, Any?> = if (file.exists()) {
            val type = object : com.google.gson.reflect.TypeToken<MutableMap<String, Any?>>() {}.type
            gson.fromJson(file.readText(), type) ?: mutableMapOf()
        } else {
            mutableMapOf()
        }
        @Suppress("UNCHECKED_CAST")
        val bot = (root["bot"] as? MutableMap<String, Any?>)
            ?: mutableMapOf<String, Any?>().also { root["bot"] = it }
        bot["app-id"] = credentials.appId
        bot["secret"] = credentials.appSecret
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(root) + "\n")
        true
    } catch (e: Exception) {
        logger.error("写入扫码凭据失败：${e.message}")
        false
    }

    /** 重载配置并重启网关（对应 BDS 版的 huhobot reload）。 */
    fun reload() {
        qqClient.stop()
        config = PenguinConfig.load()
        state = BotState(config)
        custom = CustomCommands(config)
        qqClient = QQClient(config)
        commandHandler = CommandHandler(config, state, qqClient, custom)

        // 重新初始化面板同步器
        val configDir = File("config")
        val stateFile = File(configDir, "penguin-panel-state.properties")
        panelSync = CommandPanelSync(qqClient, config, stateFile)

        qqClient.onGroupMessage { msg -> commandHandler.handle(msg) }
        registerQqEventListeners(qqClient)
        if (config.botAppId.isNotBlank() && config.botSecret.isNotBlank()) {
            qqClient.start()
            // 重载后重新同步命令面板
            syncCommandPanel()
        }
        logger.info("$MOD_NAME 配置已重载")
    }

    /**
     * 同步命令面板到 QQ
     */
    fun syncCommandPanel() {
        Thread {
            try {
                Thread.sleep(3000) // 等待3秒确保网关完全启动
                val metadata = commandHandler.getCommandMetadata()
                logger.info("开始同步 ${metadata.size} 个命令到 QQ 指令面板")
                panelSync.syncCommands(metadata)
                logger.info("命令面板同步完成")
            } catch (e: Exception) {
                logger.error("同步指令面板失败", e)
            }
        }.apply {
            isDaemon = true
            name = "penguin-panel-sync"
        }.start()
    }
}
