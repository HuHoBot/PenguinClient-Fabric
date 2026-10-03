package com.huhobot.penguin

import com.huhobot.penguin.addon.AddonLoader
import com.huhobot.penguin.addon.AddonManager
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
import net.fabricmc.loader.api.FabricLoader
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

        // 加载附属插件（此时 commandHandler 已就绪，插件注册的命令才能接上分发链）
        AddonLoader.loadAll()

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
                // 启动后同步命令面板（内含附属插件命令）
                syncCommandPanel()
            } else {
                logger.warn("$MOD_NAME 未配置 bot.app-id / bot.secret，QQ 机器人未启动。请编辑 config/penguin-server.json")
            }
        }

        // 服务器停止时关闭网关
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            com.huhobot.penguin.qr.QrLoginManager.cancel()
            AddonManager.unloadAll()
            qqClient.stop()
            logger.info("$MOD_NAME QQ 网关已停止")
        }

        // 拦截玩家聊天 → 转发到 QQ
        ServerMessageEvents.CHAT_MESSAGE.register { message, sender, _ ->
            val startWith = config.chatStartWith
            val raw = message.content.string
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
        // 检查 post-chat 配置
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
                srv.playerManager.broadcast(net.minecraft.text.Text.literal(message), false)
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
            val result = srv.commandManager.dispatcher.execute(
                command,
                srv.commandSource.withOutput(object : net.minecraft.server.command.CommandOutput {
                    override fun sendMessage(message: net.minecraft.text.Text) {
                        output.append(message.string).append("\n")
                    }
                    override fun shouldReceiveFeedback() = true
                    override fun shouldTrackOutput() = true
                    override fun shouldBroadcastConsoleToOps() = false
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

    /** 附属插件用：向所有已配置 QQ 群发文本。 */
    fun sendTextToAllGroups(content: String) = sendToAllGroups(content)

    /**
     * 挂群成员 / 入群申请 / 互动事件监听。
     *
     * 交互事件必须在 5 秒内 [QQClient.respondInteraction] 回执，否则 QQ 记为无响应；
     * 面板按钮由 [commandHandler] 消化，其余仅回 SUCCESS。
     */
    private fun registerQqEventListeners(client: QQClient) {
        client.onMemberJoin { evt ->
            if (config.groupMemberEventToGame) {
                val name = evt.username ?: evt.userId
                broadcastToGame("[QQ]🟢$name 加入 QQ 群")
            }
        }
        client.onMemberLeave { evt ->
            if (config.groupMemberEventToGame) {
                val name = evt.username ?: evt.userId
                broadcastToGame("[QQ]🔴$name 退出 QQ 群")
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
                if (ok) "扫码绑定成功，已写入配置，正在重连QQ 网关…"
                else "扫码绑定成功，但写入配置失败：${com.huhobot.penguin.config.PenguinConfig.configFile()}"
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
        val file = com.huhobot.penguin.config.PenguinConfig.configFile()
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        val root: MutableMap<String, Any?> = if (file.exists()) {
            val type = object : com.google.gson.reflect.TypeToken<MutableMap<String, Any?>>() {}.type
            gson.fromJson(file.readText(), type) ?: mutableMapOf()
        } else {
            mutableMapOf()
        }
        @Suppress("UNCHECKED_CAST")
        val bot = (root["bot"] as? MutableMap<String, Any?>) ?: mutableMapOf<String, Any?>().also { root["bot"] = it }
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
        // 先卸载附属插件：它们注册的命令挂在旧的 commandHandler 上，
        // 顺序反了会让插件往已废弃的 handler 里塞命令。
        AddonManager.unloadAll()
        qqClient.stop()
        config = PenguinConfig.load()
        state = BotState(config)
        custom = CustomCommands(config)
        qqClient = QQClient(config)
        commandHandler = CommandHandler(config, state, qqClient, custom)
        qqClient.onGroupMessage { msg -> commandHandler.handle(msg) }
        registerQqEventListeners(qqClient)

        // 重新初始化面板同步器
        val configDir = File("config")
        val stateFile = File(configDir, "penguin-panel-state.properties")
        panelSync = CommandPanelSync(qqClient, config, stateFile)

        // 重新加载附属插件，命令会注册到新的 commandHandler 上
        AddonLoader.loadAll()

        if (config.botAppId.isNotBlank() && config.botSecret.isNotBlank()) {
            qqClient.start()
        }
        logger.info("$MOD_NAME 配置已重载")
    }

    /**
     * 只重载附属插件，不动网关与配置。
     * 插件命令注册在 commandHandler 上，因此必须换一个新的 handler。
     */
    fun reloadAddons() {
        AddonManager.unloadAll()
        commandHandler = CommandHandler(config, state, qqClient, custom)
        qqClient.onGroupMessage { msg -> commandHandler.handle(msg) }
        AddonLoader.loadAll()
        // 面板里可能有旧插件的命令残留，重建一次
        panelSync.invalidateCache()
        syncCommandPanel()
    }

    /** 由 AddonLoader 调用，把附属插件命令接到当前分发链上。 */
    fun registerAddonCommand(
        source: String,
        name: String,
        describe: String,
        adminOnly: Boolean,
        handler: (CommandHandler.Ctx) -> Unit
    ): Boolean = commandHandler.registerAddonCommand(source, name, describe, adminOnly, handler)

    /** mod 的配置目录。 */
    fun configDir(): File = FabricLoader.getInstance().configDir.toFile()

    /** 同步命令面板到QQ群 */
    fun syncCommandPanel() {
        Thread {
            try {
                Thread.sleep(3000) // 等待3秒确保网关完全启动
                val commands = commandHandler.getCommandMetadata()
                panelSync.syncCommands(commands)
                logger.info("命令面板同步完成")
            } catch (e: Exception) {
                logger.error("命令面板同步失败", e)
            }
        }.apply {
            isDaemon = true
            name = "penguin-panel-sync"
        }.start()
    }
}
