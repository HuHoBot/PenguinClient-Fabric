package com.huhobot.penguin.command

import com.huhobot.penguin.PenguinServerMod
import com.huhobot.penguin.config.PenguinConfig
import com.huhobot.penguin.qq.GroupMessage
import com.huhobot.penguin.qq.QQClient
import com.huhobot.penguin.state.BotState
import com.huhobot.penguin.state.MODE_BOTH
import com.huhobot.penguin.state.MODE_MANUAL
import com.huhobot.penguin.state.MODE_QQ
import org.slf4j.LoggerFactory
import java.lang.reflect.Method

private val logger = LoggerFactory.getLogger("PenguinServer-Fabric/Commands")

/**
 * 群命令分发 + 全部内置命令
 *
 * 使用 @BotCommand 注解自动发现和注册命令，无需手动维护命令列表
 */
class CommandHandler(
    private val cfg: PenguinConfig,
    private val state: BotState,
    private val qqClient: QQClient,
    private val custom: CustomCommands
) {
    /** 消息上下文，一次命令执行期间有效。 */
    data class Ctx(
        val msgId: String,
        val groupId: String,
        val userId: String,
        val username: String?,
        val memberRole: String?,
        var params: String = "",
        /** 回复当前消息。附属插件通过它把结果发回QQ 群。 */
        val reply: (String) -> Unit = {},
        /** 回复 Markdown。 */
        val replyMarkdown: (String) -> Unit = {},
        /** 当前用户是否为管理员。 */
        val isAdmin: Boolean = false,
        /** 发送者的展示名（username 缺失时回退为 openid）。 */
        val displayName: String = username ?: userId
    )

    // 命令映射表：命令名 -> 方法
    private val commandMap = mutableMapOf<String, Method>()

    // 命令元数据列表（用于 QQ 面板同步）
    private val commandMetadata = mutableListOf<CommandMetadata>()

    //附属插件命令：命令名 -> (来源插件, 处理函数)
    private val addonCommands = mutableMapOf<String, AddonBinding>()

    data class AddonBinding(
        val source: String,
        val metadata: CommandMetadata,
        val handler: (Ctx) -> Unit
    )

    init {
        // 反射扫描所有带 @BotCommand 注解的方法
        scanCommands()
        logger.info("已注册 ${commandMap.size} 个命令")
    }

    /**
     * 注册一条附属插件命令。
     *
     * 与内置命令同名时**不覆盖**：内置命令优先，避免插件把「查在线」这类
     * 既有命令顶掉。返回 false 表示命令名已被占用。
     */
    fun registerAddonCommand(
        source: String,
        name: String,
        describe: String,
        adminOnly: Boolean,
        handler: (Ctx) -> Unit
    ): Boolean {
        val key = name.trim()
        if (key.isEmpty()) {
            logger.warn("附属插件 $source 注册了空命令名，已忽略")
            return false
        }
        if (commandMap.containsKey(key)) {
            logger.warn("附属插件 $source 试图注册已存在的命令「$key」，已忽略")
            return false
        }
        if (addonCommands.containsKey(key)) {
            val owner = addonCommands[key]!!.source
            logger.warn("附属插件 $source 试图注册已被 $owner 占用的命令「$key」，已忽略")
            return false
        }
        val metadata = CommandMetadata(key, describe, adminOnly, source)
        addonCommands[key] = AddonBinding(source, metadata, handler)
        commandMetadata.add(metadata)
        com.huhobot.penguin.addon.AddonManager.addCommand(
            source, com.huhobot.penguin.addon.AddonCommand(key, describe, adminOnly, source)
        )
        logger.info("附属插件 $source 注册命令：$key (admin=$adminOnly)")
        return true
    }

    private fun scanCommands() {
        for (method in this::class.java.declaredMethods) {
            val annotation = method.getAnnotation(BotCommand::class.java) ?: continue
            val name = annotation.name.trim()

            if (name.isEmpty()) {
                logger.warn("跳过命令方法 ${method.name}：命令名为空")
                continue
            }

            // 设置可访问（允许调用 private 方法）
            method.isAccessible = true

            // 注册命令
            commandMap[name] = method

            // 收集元数据
            commandMetadata.add(CommandMetadata(
                name = name,
                description = annotation.description,
                adminOnly = annotation.adminOnly
            ))

            if (cfg.debugLogEvents) {
                logger.info("注册命令: $name (admin=${annotation.adminOnly})")
            }
        }
    }

    /**
     * 返回所有命令元数据（供 QQ 面板同步使用）
     */
    fun getCommandMetadata(): List<CommandMetadata> = commandMetadata.toList()

    private fun reply(ctx: Ctx, content: String) {
        qqClient.sendGroupMessage(ctx.groupId, content, ctx.msgId)
    }

    private fun isAdmin(ctx: Ctx): Boolean =
        state.isAdmin(ctx.groupId, ctx.userId, ctx.memberRole)

    private fun gateAdmin(ctx: Ctx): Boolean {
        if (isAdmin(ctx)) return true
        reply(ctx, "此命令需要管理员权限")
        return false
    }

    private fun displayName(ctx: Ctx): String = ctx.username ?: ctx.userId

    private fun runGameCommand(ctx: Ctx, command: String) {
        val (_, output) = PenguinServerMod.runCommand(command)
        reply(ctx, output.ifEmpty { "已发送执行请求" })
    }

    // ---- 命令实现（使用 @BotCommand 注解） ----

    @BotCommand("查信息", "查询 OpenId 和认证状态")
    private fun cmdQueryInfo(ctx: Ctx) {
        val target = ctx.params.trim()
        if (target.isEmpty()) {
            reply(ctx,
                "群：${ctx.groupId}\n本人 OpenID：${ctx.userId}\n" +
                "角色：${ctx.memberRole ?: "member"}\n" +
                "认证状态：${if (state.isAuthenticated(ctx.groupId, ctx.userId)) "已认证" else "未认证"}"
            )
        } else {
            if (!gateAdmin(ctx)) return
            val openid = target.split(Regex("\\s+"))[0]
            reply(ctx,
                "目标 OpenID：$openid\n" +
                "认证状态：${if (state.isAuthenticated(ctx.groupId, openid)) "已认证" else "未认证"}"
            )
        }
    }

    @BotCommand("发消息", "发送消息到游戏")
    private fun cmdSendMessage(ctx: Ctx) {
        if (ctx.params.isBlank()) { reply(ctx, "用法：发消息 <内容>"); return }
        com.huhobot.penguin.filter.TextFilter.audit(ctx.params, cfg) { filtered ->
            val tempMsg = com.huhobot.penguin.qq.GroupMessage(
                id = "", groupId = ctx.groupId, content = filtered,
                userId = ctx.userId, username = ctx.username, memberRole = ctx.memberRole,
                timestamp = null, attachments = null
            )
            PenguinServerMod.broadcastToGame(PenguinServerMod.formatGroupMessage(displayName(ctx), tempMsg))
        }
    }

    @BotCommand("发信息", "发送消息到游戏")
    private fun cmdSendMessage2(ctx: Ctx) {
        if (ctx.params.isBlank()) { reply(ctx, "用法：发信息 <内容>"); return }
        com.huhobot.penguin.filter.TextFilter.audit(ctx.params, cfg) { filtered ->
            val tempMsg = com.huhobot.penguin.qq.GroupMessage(
                id = "", groupId = ctx.groupId, content = filtered,
                userId = ctx.userId, username = ctx.username, memberRole = ctx.memberRole,
                timestamp = null, attachments = null
            )
            PenguinServerMod.broadcastToGame(PenguinServerMod.formatGroupMessage(displayName(ctx), tempMsg))
        }
    }

    @BotCommand("查在线", "查询在线玩家")
    private fun cmdQueryOnline(ctx: Ctx) {
        val (_, output) = PenguinServerMod.runCommand("list")
        if (output.isEmpty()) { reply(ctx, "无输出"); return }
        val players = parsePlayerList(output)

        // 构建 MOTD 图片 URL，自动把中文域名转成 ASCII（Punycode）
        val timestampSeconds = System.currentTimeMillis() / 1000
        val asciiIp = try {
            java.net.IDN.toASCII(cfg.motdServerIp, java.net.IDN.ALLOW_UNASSIGNED)
        } catch (_: Exception) {
            cfg.motdServerIp
        }
        val imgUrl = cfg.motdApi
            .replace("{ip}", asciiIp)
            .replace("{port}", cfg.motdServerPort.toString()) + "&$timestampSeconds"

        // 根据配置选择输出方式
        if (cfg.motdUseMarkdown && players != null) {
            // Markdown 模式（带或不带图片）
            val markdownImgUrl = if (cfg.motdPostImg) imgUrl else null
            qqClient.sendMarkdown(ctx.groupId, buildOnlineMarkdown(players, markdownImgUrl), ctx.msgId)
        } else if (cfg.motdPostImg && players != null) {
            // 纯图片模式（文本 + 图片，非 Markdown）
            val formattedPlayerList = players.joinToString("\n")
            val text = cfg.motdText
                .replace("{server}", cfg.serverName.ifEmpty { cfg.botName })
                .replace("{online}", players.size.toString())
                .replace("{players}", formattedPlayerList)
            qqClient.sendGroupMessageWithImage(ctx.groupId, text, imgUrl, ctx.msgId)
        } else if (cfg.featureMarkdownOnline && players != null) {
            // 旧版 Markdown（向后兼容）
            qqClient.sendMarkdown(ctx.groupId, buildOnlineMarkdown(players), ctx.msgId)
        } else {
            // 纯文本
            reply(ctx, output)
        }
    }

    @BotCommand("在线服务器", "查看服务器是否在线")
    private fun cmdQueryServers(ctx: Ctx) {
        reply(ctx, "${cfg.botName} 在线")
    }

    @BotCommand("motd", "查询服务器状态")
    private fun cmdMotd(ctx: Ctx) {
        if (ctx.params.isBlank()) {
            reply(ctx, "用法：motd <IP:端口>\n示例：motd mc.hypixel.net:25565")
            return
        }

        // 解析 IP:端口
        val parts = ctx.params.split(":")
        val ip = parts.getOrNull(0)?.trim() ?: run {
            reply(ctx, "无效的地址格式")
            return
        }
        val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 25565

        // 转换中文域名为 ASCII
        val asciiIp = try {
            java.net.IDN.toASCII(ip, java.net.IDN.ALLOW_UNASSIGNED)
        } catch (_: Exception) {
            ip
        }

        // 构建 MOTD 图片 URL
        val timestampSeconds = System.currentTimeMillis() / 1000
        val imgUrl = cfg.motdApi
            .replace("{ip}", asciiIp)
            .replace("{port}", port.toString()) + "&$timestampSeconds"

        // 发送图片（不使用 Markdown，因为手机端显示不正常）
        val text = "查询服务器：$ip:$port"
        qqClient.sendGroupMessageWithImage(ctx.groupId, text, imgUrl, ctx.msgId)
    }

    @BotCommand("执行", "执行自定义命令")
    private fun cmdExecute(ctx: Ctx) {
        val item = custom.resolveRun(ctx.params)
        if (item == null) {
            reply(ctx, "未找到可执行的自定义命令：${ctx.params}")
            return
        }
        runGameCommand(ctx, renderCommand(item.command, ctx.params, ctx.groupId, ctx.userId, "run"))
    }

    @BotCommand("执行命令", "执行服务器命令", adminOnly = true)
    private fun cmdRunCommand(ctx: Ctx) {
        if (!gateAdmin(ctx)) return
        if (ctx.params.isBlank()) { reply(ctx, "用法：执行命令 <MC命令>"); return }
        runGameCommand(ctx, ctx.params)
    }

    @BotCommand("管理员执行", "管理员执行自定义命令", adminOnly = true)
    private fun cmdAdminExecute(ctx: Ctx) {
        if (!gateAdmin(ctx)) return
        val item = custom.resolveAdminRun(ctx.params)
        if (item == null) { reply(ctx, "未找到自定义命令：${ctx.params}"); return }
        runGameCommand(ctx, renderCommand(item.command, ctx.params, ctx.groupId, ctx.userId, "adminrun"))
    }

    @BotCommand("查管理", "查看管理员列表", adminOnly = true)
    private fun cmdQueryAdmin(ctx: Ctx) {
        val admins = state.listAdmins(ctx.groupId)
        val configured = cfg.adminOpenids
        val lines = mutableListOf("手动管理员：${if (admins.isEmpty()) "无" else admins.joinToString("、")}")
        if (configured.isNotEmpty()) lines.add("配置的管理员：${configured.joinToString("、")}")
        reply(ctx, lines.joinToString("\n"))
    }

    @BotCommand("加管理", "添加管理员", adminOnly = true)
    private fun cmdAddAdmin(ctx: Ctx) {
        if (!gateAdmin(ctx)) return
        val target = ctx.params.trim()
        if (target.isEmpty()) { reply(ctx, "用法：加管理 <OpenID>"); return }
        val openid = target.split(Regex("\\s+"))[0]
        state.addAdmin(ctx.groupId, openid)
        reply(ctx, "已添加管理员：$openid")
    }

    @BotCommand("删管理", "删除管理员", adminOnly = true)
    private fun cmdRemoveAdmin(ctx: Ctx) {
        if (!gateAdmin(ctx)) return
        val target = ctx.params.trim()
        if (target.isEmpty()) { reply(ctx, "用法：删管理 <OpenID>"); return }
        val openid = target.split(Regex("\\s+"))[0]
        state.removeAdmin(ctx.groupId, openid)
        reply(ctx, "已删除管理员：$openid")
    }

    @BotCommand("管理方式", "设置管理员判定方式", adminOnly = true)
    private fun cmdAdminMode(ctx: Ctx) {
        if (!gateAdmin(ctx)) return
        val mode = parseMode(ctx.params) ?: run { reply(ctx, "用法：管理方式 <QQ/手动/双重>"); return }
        state.setMode(ctx.groupId, mode)
        reply(ctx, "已设置本群管理方式：${modeLabel(mode)}")
    }

    @BotCommand("添加白名单", "添加玩家白名单", adminOnly = true)
    private fun cmdAddWhitelist(ctx: Ctx) {
        if (!gateAdmin(ctx)) return
        val name = ctx.params.trim()
        if (name.isEmpty()) { reply(ctx, "用法：添加白名单 <玩家名>"); return }
        val command = cfg.whitelistAddCmd.replace("{name}", name)
        runGameCommand(ctx, command)
    }

    @BotCommand("删除白名单", "删除玩家白名单", adminOnly = true)
    private fun cmdRemoveWhitelist(ctx: Ctx) {
        if (!gateAdmin(ctx)) return
        val name = ctx.params.trim()
        if (name.isEmpty()) { reply(ctx, "用法：删除白名单 <玩家名>"); return }
        val command = cfg.whitelistDelCmd.replace("{name}", name)
        runGameCommand(ctx, command)
    }

    @BotCommand("查白名单", "查看白名单列表", adminOnly = true)
    private fun cmdQueryWhitelist(ctx: Ctx) {
        val (_, output) = PenguinServerMod.runCommand("whitelist list")
        if (output.isEmpty()) { reply(ctx, "无输出"); return }
        val names = parseWhitelist(output)
        if (cfg.featureMarkdownWhitelist && names != null) {
            qqClient.sendMarkdown(ctx.groupId, buildWhitelistMarkdown(names), ctx.msgId)
        } else {
            reply(ctx, output)
        }
    }

    @BotCommand("绑定白名单", "自助绑定并加入白名单")
    private fun cmdBindWhitelist(ctx: Ctx) {
        val name = ctx.params.trim()
        if (name.isEmpty()) { reply(ctx, "用法：绑定白名单 <玩家名>"); return }
        state.bindName(ctx.groupId, ctx.userId, name)
        val command = cfg.whitelistAddCmd.replace("{name}", name)
        PenguinServerMod.runCommand(command)
        reply(ctx, "绑定成功：QQ ${ctx.userId} ⇄ 游戏 $name\n已执行白名单加入：$command")
    }

    @BotCommand("解绑白名单", "解绑指定玩家并移出白名单", adminOnly = true)
    private fun cmdUnbindWhitelist(ctx: Ctx) {
        if (!gateAdmin(ctx)) return
        val name = ctx.params.trim()
        if (name.isEmpty()) { reply(ctx, "用法：解绑白名单 <玩家名>"); return }
        val openid = state.findOpenidByGameName(ctx.groupId, name)
        val removed = if (openid != null) state.unbindOpenid(ctx.groupId, openid) else null
        val command = cfg.whitelistDelCmd.replace("{name}", name)
        PenguinServerMod.runCommand(command)
        if (openid != null) {
            reply(ctx, "已解绑：QQ $openid ⇄ 游戏 $removed\n已执行白名单移除：$command")
        } else {
            reply(ctx, "未找到该游戏名的绑定记录（已执行白名单移除：$command）")
        }
    }

    @BotCommand("解除绑定", "解除自己的绑定并移出白名单")
    private fun cmdUnbindSelf(ctx: Ctx) {
        val removed = state.unbindOpenid(ctx.groupId, ctx.userId)
        if (removed == null) { reply(ctx, "你当前没有绑定记录"); return }
        val command = cfg.whitelistDelCmd.replace("{name}", removed)
        PenguinServerMod.runCommand(command)
        reply(ctx, "已解除绑定：QQ ${ctx.userId} ⇄ 游戏 $removed\n已执行白名单移除：$command")
    }

    @BotCommand("认证", "查看或管理认证状态")
    private fun cmdAuthenticate(ctx: Ctx) {
        val target = ctx.params.trim()
        if (target.isEmpty()) {
            val status = if (state.isAuthenticated(ctx.groupId, ctx.userId)) "已认证" else "未认证"
            reply(ctx, "本人认证状态：$status\nOpenID：${ctx.userId}")
            return
        }
        if (!gateAdmin(ctx)) return
        val openid = target.split(Regex("\\s+")).last()
        state.authenticate(ctx.groupId, openid)
        reply(ctx, "已认证：$openid")
    }

    @BotCommand("解除认证", "解除认证状态")
    private fun cmdRevokeAuth(ctx: Ctx) {
        val target = ctx.params.trim()
        if (target.isEmpty()) {
            state.revoke(ctx.groupId, ctx.userId)
            reply(ctx, "已解除本人认证")
            return
        }
        if (!gateAdmin(ctx)) return
        val openid = target.split(Regex("\\s+")).last()
        state.revoke(ctx.groupId, openid)
        reply(ctx, "已解除认证：$openid")
    }

    @BotCommand("全量", "切换全量聊天转发", adminOnly = true)
    private fun cmdFullForwarding(ctx: Ctx) {
        if (!gateAdmin(ctx)) return
        val enabled = parseOnOff(ctx.params) ?: run { reply(ctx, "用法：全量 <开/关>"); return }
        state.setFullForwarding(ctx.groupId, enabled)
        reply(ctx, "已设置本群全量转发：${if (enabled) "开" else "关"}")
    }

    @BotCommand("同步面板", "手动同步QQ指令面板", adminOnly = true)
    private fun cmdSyncPanel(ctx: Ctx) {
        if (!gateAdmin(ctx)) return
        reply(ctx, "正在同步指令面板...")
        Thread {
            try {
                PenguinServerMod.syncCommandPanel()
                Thread.sleep(4000) // 等待同步完成
                qqClient.sendGroupMessage(ctx.groupId, "指令面板同步成功！", ctx.msgId)
            } catch (e: Exception) {
                qqClient.sendGroupMessage(ctx.groupId, "指令面板同步失败：${e.message}", ctx.msgId)
            }
        }.start()
    }

    @BotCommand("刷新", "手动同步QQ指令面板", adminOnly = true)
    private fun cmdRefreshPanel(ctx: Ctx) {
        if (!gateAdmin(ctx)) return
        reply(ctx, "正在同步指令面板...")
        Thread {
            try {
                PenguinServerMod.syncCommandPanel()
                Thread.sleep(4000) // 等待同步完成
                qqClient.sendGroupMessage(ctx.groupId, "指令面板同步成功！", ctx.msgId)
            } catch (e: Exception) {
                qqClient.sendGroupMessage(ctx.groupId, "指令面板同步失败：${e.message}", ctx.msgId)
            }
        }.start()
    }

    @BotCommand("重载", "重载配置并重启网关", adminOnly = true)
    private fun cmdReload(ctx: Ctx) {
        if (!gateAdmin(ctx)) return
        reply(ctx, "正在重载配置...")
        Thread {
            try {
                PenguinServerMod.reload()
                Thread.sleep(4000) // 等待重载完成
                qqClient.sendGroupMessage(ctx.groupId, "配置重载成功！网关已重启", ctx.msgId)
            } catch (e: Exception) {
                qqClient.sendGroupMessage(ctx.groupId, "配置重载失败：${e.message}", ctx.msgId)
            }
        }.start()
    }

    @BotCommand("附属插件", "查看已安装的附属插件")
    private fun cmdListAddons(ctx: Ctx) {
        val addons = com.huhobot.penguin.addon.AddonManager.allAddons()
        if (addons.isEmpty()) {
            reply(ctx, "当前没有已安装的附属插件")
            return
        }
        val sb = StringBuilder("已安装的附属插件 (${addons.size}):\n")
        addons.forEach { addon ->
            sb.appendLine("  - ${addon.name} v${addon.version} by ${addon.author}")
            if (addon.description.isNotBlank()) {
                sb.appendLine("    ${addon.description}")
            }
            val commands = com.huhobot.penguin.addon.AddonManager.commandsOf(addon.name)
            if (commands.isNotEmpty()) {
                sb.appendLine("    命令: ${commands.joinToString(", ") { it.command }}")
            }
        }
        reply(ctx, sb.toString().trimEnd())
    }

    @BotCommand("重载插件", "重新加载附属插件", adminOnly = true)
    private fun cmdReloadAddons(ctx: Ctx) {
        if (!gateAdmin(ctx)) return
        reply(ctx, "正在重新加载附属插件...")
        Thread {
            try {
                Thread.sleep(200)
                PenguinServerMod.reloadAddons()
                Thread.sleep(500)
                val count = com.huhobot.penguin.addon.AddonManager.size
                qqClient.sendGroupMessage(ctx.groupId, "附属插件重载完成，当前已加载 $count 个", ctx.msgId)
            } catch (e: Exception) {
                qqClient.sendGroupMessage(ctx.groupId, "附属插件重载失败：${e.message}", ctx.msgId)
            }
        }.also { it.isDaemon = true; it.name = "penguin-addon-reload" }.start()
    }

    // ---- 群管理（对齐官方 OpenAPI） ----

    @BotCommand("群信息", "查看本群信息与机器人状态", adminOnly = true)
    private fun cmdGroupInfo(ctx: Ctx) {
        if (!gateGroupApi(ctx)) return
        runGroupApi(ctx, "查询群信息") { api ->
            val info = api.getGroupInfo(ctx.groupId)
            val state = api.getBotState(ctx.groupId)
            buildString {
                appendLine("群名：${info.groupName ?: "未知"}")
                appendLine("成员：${info.memberCount}/${info.maxMember}")
                appendLine("机器人：${if (state.joined) "已入群" else "未入群"}")
                if (state.mutedInGroup) appendLine("机器人当前被禁言")
            }.trim()
        }
    }

    @BotCommand("群成员", "查看群成员列表", adminOnly = true)
    private fun cmdGroupMembers(ctx: Ctx) {
        if (!gateGroupApi(ctx)) return
        val limit = ctx.params.trim().toIntOrNull() ?: 20
        runGroupApi(ctx, "查询群成员") { api ->
            val members = api.listMembers(ctx.groupId, maxCount = limit.coerceIn(1, 500))
            if (members.isEmpty()) "本群没有查到成员"
            else members.joinToString("\n") { m ->
                val role = when (m.memberRole) {
                    "owner" -> "[群主]"
                    "admin" -> "[管理]"
                    else -> ""
                }
                "$role${m.username ?: m.memberOpenid}"
            }
        }
    }

    @BotCommand("查成员", "查询指定群成员信息", adminOnly = true)
    private fun cmdGroupMemberInfo(ctx: Ctx) {
        if (!gateGroupApi(ctx)) return
        val openid = ctx.params.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        if (openid.isEmpty()) { reply(ctx, "用法：查成员 <OpenID>"); return }
        runGroupApi(ctx, "查询成员") { api ->
            val m = api.getMember(ctx.groupId, openid)
            buildString {
                appendLine("昵称：${m.username ?: "未知"}")
                appendLine("OpenID：$openid")
                appendLine("角色：${m.memberRole ?: "member"}")
                m.joinTime?.let { appendLine("入群时间：$it") }
                m.msgCount?.let { appendLine("群消息数：$it") }
            }.trim()
        }
    }

    @BotCommand("禁言", "全群禁言或解除", adminOnly = true)
    private fun cmdMuteAll(ctx: Ctx) {
        if (!gateGroupApi(ctx)) return
        val minutes = ctx.params.trim().toIntOrNull() ?: -1
        if (minutes < 0) { reply(ctx, "用法：禁言 <分钟数>（0 = 解除全群禁言）"); return }
        val seconds = (minutes * 60L).coerceIn(0L, 30L * 24 * 3600)
        runGroupApi(ctx, "设置全群禁言") { api ->
            api.setMuteAll(ctx.groupId, seconds.toInt())
            if (seconds == 0L) "已解除全群禁言" else "已开启全群禁言，时长 $minutes 分钟"
        }
    }

    @BotCommand("踢人", "把指定成员移出本群", adminOnly = true)
    private fun cmdKick(ctx: Ctx) {
        if (!gateGroupApi(ctx)) return
        val openid = ctx.params.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        if (openid.isEmpty()) { reply(ctx, "用法：踢人 <OpenID>"); return }
        runGroupApi(ctx, "踢人") { api ->
            api.batchRemoveMembers(ctx.groupId, listOf(openid))
            "已将 $openid 移出本群"
        }
    }

    @BotCommand("入群申请", "查看本群入群申请", adminOnly = true)
    private fun cmdJoinRequests(ctx: Ctx) {
        if (!gateGroupApi(ctx)) return
        runGroupApi(ctx, "查询入群申请") { api ->
            val list = api.listJoinRequests(ctx.groupId, pending = true)
            if (list.isEmpty()) "当前没有待处理的入群申请"
            else list.joinToString("\n") { r ->
                buildString {
                    append("${r.username ?: r.memberOpenid}（${r.memberOpenid}）")
                    r.riskTips?.takeIf { it.isNotBlank() }?.let { append("  风险：$it") }
                }
            }
        }
    }

    @BotCommand("同意入群", "同意指定成员入群", adminOnly = true)
    private fun cmdApproveJoin(ctx: Ctx) {
        if (!gateGroupApi(ctx)) return
        val openid = ctx.params.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        if (openid.isEmpty()) { reply(ctx, "用法：同意入群 <OpenID>"); return }
        runGroupApi(ctx, "同意入群") { api ->
            api.approveJoinRequest(ctx.groupId, openid)
            "已同意 $openid 入群"
        }
    }

    @BotCommand("拒绝入群", "拒绝指定成员入群", adminOnly = true)
    private fun cmdRejectJoin(ctx: Ctx) {
        if (!gateGroupApi(ctx)) return
        val openid = ctx.params.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        if (openid.isEmpty()) { reply(ctx, "用法：拒绝入群 <OpenID>"); return }
        runGroupApi(ctx, "拒绝入群") { api ->
            api.rejectJoinRequest(ctx.groupId, openid)
            "已拒绝 $openid 入群"
        }
    }

    @BotCommand("群黑名单", "查看本群黑名单", adminOnly = true)
    private fun cmdGroupBlacklist(ctx: Ctx) {
        if (!gateGroupApi(ctx)) return
        runGroupApi(ctx, "查询群黑名单") { api ->
            val list = api.getBlacklist(ctx.groupId)
            if (list.isEmpty()) "本群黑名单为空" else list.joinToString("\n")
        }
    }

    @BotCommand("拉黑", "把成员加入本群黑名单", adminOnly = true)
    private fun cmdAddBlacklist(ctx: Ctx) {
        if (!gateGroupApi(ctx)) return
        val openid = ctx.params.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        if (openid.isEmpty()) { reply(ctx, "用法：拉黑 <OpenID>"); return }
        runGroupApi(ctx, "加入黑名单") { api ->
            api.addBlacklist(ctx.groupId, listOf(openid))
            "已把 $openid 加入本群黑名单"
        }
    }

    @BotCommand("移出黑名单", "把成员移出本群黑名单", adminOnly = true)
    private fun cmdRemoveBlacklist(ctx: Ctx) {
        if (!gateGroupApi(ctx)) return
        val openid = ctx.params.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        if (openid.isEmpty()) { reply(ctx, "用法：移出黑名单 <OpenID>"); return }
        runGroupApi(ctx, "移出黑名单") { api ->
            api.removeBlacklist(ctx.groupId, listOf(openid))
            "已把 $openid 移出本群黑名单"
        }
    }

    @BotCommand("撤回", "撤回机器人最近一条消息", adminOnly = true)
    private fun cmdRecall(ctx: Ctx) {
        if (!gateAdmin(ctx)) return
        if (ctx.msgId.isBlank()) { reply(ctx, "无法定位要撤回的消息"); return }
        qqClient.recallMessage(ctx.groupId, ctx.msgId)
    }

    @BotCommand("扫码绑定", "扫码绑定 QQ 机器人凭据", adminOnly = true)
    private fun cmdQrBind(ctx: Ctx) {
        if (!gateAdmin(ctx)) return
        PenguinServerMod.startQrBind { msg -> qqClient.sendGroupMessage(ctx.groupId, msg) }
    }

    /** 群管理命令的权限闸门。 */
    private fun gateGroupApi(ctx: Ctx): Boolean {
        if (!cfg.groupApiAdminOnly) return true
        return gateAdmin(ctx)
    }

    // ---- 分发逻辑 ----

    /**
     * 群管理类命令的统一执行壳。
     *
     * REST 调用是阻塞的（成员列表翻页可能上千毫秒），直接放在网关回调线程里
     * 会卡住心跳与心跳超时判定，所以丢到后台线程，结果再异步回群。
     */
    private fun runGroupApi(ctx: Ctx, label: String, block: (com.huhobot.penguin.qq.GroupApi) -> String) {
        val api = qqClient.groupApi()
        Thread {
            val text = try {
                block(api)
            } catch (e: Exception) {
                "$label 失败：${e.message ?: e.javaClass.simpleName}"
            }
            qqClient.sendGroupMessage(ctx.groupId, text)
        }.also { it.isDaemon = true; it.name = "penguin-groupapi" }.start()
    }

    /**
     * 互动事件（面板按钮）处理。
     *
     * 回执已由 [PenguinServerMod] 统一发送，这里只负责执行业务。
     */
    fun handleInteraction(event: com.huhobot.penguin.qq.InteractionEvent) {
        val resolved = event.data["resolved"] as? Map<*, *> ?: return
        val buttonId = resolved["button_id"] as? String ?: return
        val groupId = event.groupId ?: return
        val buttonData = resolved["button_data"] as? String ?: ""

        logger.info("互动事件：group=$groupId button=$buttonId data=$buttonData")

        if (buttonId.startsWith("cmd:")) {
            val command = buttonId.removePrefix("cmd:")
            val ctx = Ctx(
                msgId = resolved["message_id"] as? String ?: "",
                groupId = groupId,
                userId = event.userId ?: "",
                username = null,
                memberRole = null,
                params = buttonData,
                reply = { content -> qqClient.sendGroupMessage(groupId, content) },
                replyMarkdown = { content -> qqClient.sendMarkdown(groupId, content) },
                isAdmin = state.isAdmin(groupId, event.userId ?: "", null)
            )
            val match = findCommand(command) ?: return
            try {
                commandMap[match.first]?.invoke(this, ctx)
            } catch (e: Exception) {
                logger.error("按钮命令 ${match.first} 执行出错", e)
            }
        }
    }

    private fun findCommand(cleaned: String): Pair<String, String>? {
        // 按命令名长度降序匹配，避免短命令抢先
        val sorted = (commandMap.keys + addonCommands.keys).sortedByDescending { it.length }
        for (name in sorted) {
            if (cleaned == name || cleaned.startsWith("$name ")) {
                val params = if (cleaned == name) "" else cleaned.removePrefix(name).trim()
                return Pair(name, params)
            }
        }
        return null
    }

    private fun normalizeContent(content: String): String {
        var cleaned = content.trim()
        cleaned = cleaned.replace(Regex("<@!?[^>]+>"), "").trim()
        if (cleaned.startsWith("/")) cleaned = cleaned.removePrefix("/").trim()
        return cleaned
    }

    /** 群消息总入口。 */
    fun handle(message: GroupMessage) {
        val ctx = Ctx(
            msgId = message.id,
            groupId = message.groupId,
            userId = message.userId,
            username = message.username,
            memberRole = message.memberRole,
            reply = { content -> qqClient.sendGroupMessage(message.groupId, content, message.id) },
            replyMarkdown = { content -> qqClient.sendMarkdown(message.groupId, content, message.id) },
            isAdmin = state.isAdmin(message.groupId, message.userId, message.memberRole)
        )

        val cleaned = normalizeContent(message.content)

        // 查信息命令不受群限制，对齐官方版本逻辑
        if (!cleaned.contains("查信息")) {
            val groups = cfg.botGroups
            if (groups.isNotEmpty() && !groups.contains(message.groupId)) {
                if (cfg.debugLogEvents)
                    logger.info("群 ${message.groupId} 不在 bot.groups 白名单，忽略")
                return
            }
        }

        if (cleaned.isNotEmpty()) {
            val match = findCommand(cleaned)
            if (match != null) {
                val (name, params) = match
                if (!cfg.isCommandEnabled(name)) {
                    if (cfg.debugLogEvents) logger.info("命令 $name 已被关闭")
                    reply(ctx, "此命令已被管理员关闭")
                    return
                }
                ctx.params = params
                val addonBinding = addonCommands[name]
                if (addonBinding != null) {
                    // 附属插件命令：管理员校验交给插件自己，这里只做异常隔离
                    if (cfg.debugLogEvents) logger.info("命中附属命令：$name (${addonBinding.source})")
                    try {
                        addonBinding.handler(ctx)
                    } catch (e: Exception) {
                        logger.error("附属命令 $name 执行出错", e)
                        reply(ctx, "命令执行出错：${e.message}")
                    }
                    return
                }
                try {
                    if (cfg.debugLogEvents)
                        logger.info("命中命令：$name 参数=$params")
                    // 通过反射调用命令方法
                    val method = commandMap[name]!!
                    method.invoke(this, ctx)
                } catch (e: Exception) {
                    logger.error("命令 $name 执行出错", e)
                }
                return
            }
        }

        // 非命令消息：全量转发到游戏
        if (cfg.chatPostChat && state.isFullForwarding(message.groupId)) {
            // 创建临时消息对象用于格式化（包含 filtered 内容和原始 attachments）
            val filteredMsg = message.copy(content = message.content.trim())
            if (filteredMsg.content.isNotEmpty() || !filteredMsg.attachments.isNullOrEmpty()) {
                com.huhobot.penguin.filter.TextFilter.audit(filteredMsg.content, cfg) { filtered ->
                    val msgWithFiltered = filteredMsg.copy(content = filtered)
                    PenguinServerMod.broadcastToGame(
                        PenguinServerMod.formatGroupMessage(displayName(ctx), msgWithFiltered)
                    )
                }
            }
        }
    }

    // ---- 辅助解析 ----

    private fun parsePlayerList(output: String): List<String>? {
        val m = Regex("[:：][^:：]*$").find(output.trim()) ?: return null
        val namesPart = m.value.drop(1).trim()
        if (namesPart.isEmpty()) return emptyList()
        if (Regex("[。！？!?]").containsMatchIn(namesPart) ||
            Regex("在线|online|players|玩家", RegexOption.IGNORE_CASE).containsMatchIn(namesPart))
            return null
        return namesPart.split(Regex("[,，、]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "无" &&
                !Regex("no players|无玩家|0\\s*位|0\\s*个|0\\s*人", RegexOption.IGNORE_CASE).containsMatchIn(it) &&
                !Regex("^[-=]+$").matches(it) }
    }

    private fun parseWhitelist(output: String): List<String>? {
        val m = Regex("\\{[\\s\\S]*\\}").find(output.trim())
        if (m != null) {
            try {
                // 简单解析 JSON result 数组
                val names = Regex(""""name"\s*:\s*"([^"]+)"""").findAll(m.value)
                    .map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
                return names
            } catch (_: Exception) {}
        }
        val m2 = Regex("[:：][^:：]*$").find(output.trim()) ?: return null
        val namesPart = m2.value.drop(1).trim()
        if (namesPart.isEmpty()) return emptyList()
        return namesPart.split(Regex("[,，、]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "无" &&
                !Regex("no players|无玩家|0\\s*位|0\\s*个|0\\s*人", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    }

    private fun buildOnlineMarkdown(players: List<String>, imgUrl: String? = null): String {
        val sb = StringBuilder("# ${cfg.serverName.ifEmpty { cfg.botName }}查在线结果\n\n***\n\n")
        // 如果有图片 URL，按照 QQ 官方 Markdown 语法添加图片
        if (imgUrl != null) {
            sb.append("![Motd #700px #389px]($imgUrl)\n\n")
        }
        sb.append("***\n\n")
        sb.append("- **当前服内有**`${players.size}`**位玩家**\n")
        if (players.isNotEmpty()) {
            sb.append("- **名单如下:**\n\n")
            players.forEachIndexed { index, name -> sb.append("${index + 1}. **$name**\n") }
        }
        return sb.toString()
    }

    private fun buildWhitelistMarkdown(names: List<String>): String {
        val sb = StringBuilder("# 白名单\n\n当前白名单：**${names.size}** 人")
        if (names.isNotEmpty()) { sb.append("\n"); names.forEach { sb.append("\n- $it") } }
        return sb.toString()
    }

    private fun parseOnOff(text: String): Boolean? {
        return when (text.trim().lowercase()) {
            "开", "on", "true", "1", "yes" -> true
            "关", "off", "false", "0", "no" -> false
            else -> null
        }
    }

    private fun parseMode(text: String): String? = when (text.trim().lowercase()) {
        "qq" -> MODE_QQ
        "手动", "manual", "config" -> MODE_MANUAL
        "双重", "both" -> MODE_BOTH
        else -> null
    }

    private fun modeLabel(mode: String): String = when (mode) {
        MODE_QQ -> "QQ 群主/管理员"
        MODE_MANUAL -> "手动管理员"
        else -> "双重（两者任一即可）"
    }
}
