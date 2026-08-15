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

private val logger = LoggerFactory.getLogger("PenguinServer-Fabric/Commands")

/**
 * 群命令分发 + 全部内置命令，对齐 BDS 版 commands.js。
 * 20 个内置命令：查信息/发消息/查在线/在线服务器/执行/管理员执行/
 * 查管理/加管理/删管理/管理方式/添加白名单/删除白名单/查白名单/
 * 绑定白名单/解绑白名单/解除绑定/认证/解除认证/全量/发信息
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
        var params: String = ""
    )

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

    // ---- 内置命令表 ----

    private inner class Cmd(val name: String, val exec: (Ctx) -> Unit)

    private val commands: List<Cmd> = listOf(
        Cmd("查信息") { ctx ->
            val target = ctx.params.trim()
            if (target.isEmpty()) {
                reply(ctx,
                    "群：${ctx.groupId}\n本人 OpenID：${ctx.userId}\n" +
                    "角色：${ctx.memberRole ?: "member"}\n" +
                    "认证状态：${if (state.isAuthenticated(ctx.groupId, ctx.userId)) "已认证" else "未认证"}"
                )
            } else {
                if (!gateAdmin(ctx)) return@Cmd
                val openid = target.split(Regex("\\s+"))[0]
                reply(ctx,
                    "目标 OpenID：$openid\n" +
                    "认证状态：${if (state.isAuthenticated(ctx.groupId, openid)) "已认证" else "未认证"}"
                )
            }
        },
        Cmd("发消息") { ctx ->
            if (ctx.params.isBlank()) { reply(ctx, "用法：发消息 <内容>"); return@Cmd }
            com.huhobot.penguin.filter.TextFilter.audit(ctx.params, cfg) { filtered ->
                PenguinServerMod.broadcastToGame(PenguinServerMod.formatGroupMessage(displayName(ctx), filtered))
            }
        },
        Cmd("发信息") { ctx ->
            if (ctx.params.isBlank()) { reply(ctx, "用法：发信息 <内容>"); return@Cmd }
            com.huhobot.penguin.filter.TextFilter.audit(ctx.params, cfg) { filtered ->
                PenguinServerMod.broadcastToGame(PenguinServerMod.formatGroupMessage(displayName(ctx), filtered))
            }
        },
        Cmd("查在线") { ctx ->
            val (_, output) = PenguinServerMod.runCommand("list")
            if (output.isEmpty()) { reply(ctx, "无输出"); return@Cmd }
            val players = parsePlayerList(output)

            // 构建 MOTD 图片 URL
            val timestampSeconds = System.currentTimeMillis() / 1000
            val imgUrl = cfg.motdApi
                .replace("{ip}", cfg.motdServerIp)
                .replace("{port}", cfg.motdServerPort.toString()) + "&$timestampSeconds"

            // 根据配置选择输出方式
            if (cfg.motdUseMarkdown && players != null) {
                // Markdown 模式
                qqClient.sendMarkdown(ctx.groupId, buildOnlineMarkdown(players), ctx.msgId)
            } else if (cfg.motdPostImg && players != null) {
                // 图片模式
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
        },
        Cmd("在线服务器") { ctx ->
            reply(ctx, "${cfg.botName} 在线")
        },
        Cmd("执行") { ctx ->
            val item = custom.resolveRun(ctx.params)
            if (item == null) {
                reply(ctx, "未找到可执行的自定义命令：${ctx.params}")
                return@Cmd
            }
            runGameCommand(ctx, renderCommand(item.command, ctx.params, ctx.groupId, ctx.userId, "run"))
        },
        Cmd("执行命令") { ctx ->
            val item = custom.resolveRun(ctx.params)
            if (item == null) {
                reply(ctx, "未找到可执行的自定义命令：${ctx.params}")
                return@Cmd
            }
            runGameCommand(ctx, renderCommand(item.command, ctx.params, ctx.groupId, ctx.userId, "run"))
        },
        Cmd("管理员执行") { ctx ->
            if (!gateAdmin(ctx)) return@Cmd
            val item = custom.resolveAdminRun(ctx.params)
            if (item == null) { reply(ctx, "未找到自定义命令：${ctx.params}"); return@Cmd }
            runGameCommand(ctx, renderCommand(item.command, ctx.params, ctx.groupId, ctx.userId, "adminrun"))
        },
        Cmd("查管理") { ctx ->
            val admins = state.listAdmins(ctx.groupId)
            val configured = cfg.adminOpenids
            val lines = mutableListOf("手动管理员：${if (admins.isEmpty()) "无" else admins.joinToString("、")}")
            if (configured.isNotEmpty()) lines.add("配置的管理员：${configured.joinToString("、")}")
            reply(ctx, lines.joinToString("\n"))
        },
        Cmd("加管理") { ctx ->
            if (!gateAdmin(ctx)) return@Cmd
            val target = ctx.params.trim()
            if (target.isEmpty()) { reply(ctx, "用法：加管理 <OpenID>"); return@Cmd }
            val openid = target.split(Regex("\\s+"))[0]
            state.addAdmin(ctx.groupId, openid)
            reply(ctx, "已添加管理员：$openid")
        },
        Cmd("删管理") { ctx ->
            if (!gateAdmin(ctx)) return@Cmd
            val target = ctx.params.trim()
            if (target.isEmpty()) { reply(ctx, "用法：删管理 <OpenID>"); return@Cmd }
            val openid = target.split(Regex("\\s+"))[0]
            state.removeAdmin(ctx.groupId, openid)
            reply(ctx, "已删除管理员：$openid")
        },
        Cmd("管理方式") { ctx ->
            if (!gateAdmin(ctx)) return@Cmd
            val mode = parseMode(ctx.params) ?: run { reply(ctx, "用法：管理方式 <QQ/手动/双重>"); return@Cmd }
            state.setMode(ctx.groupId, mode)
            reply(ctx, "已设置本群管理方式：${modeLabel(mode)}")
        },
        Cmd("添加白名单") { ctx ->
            if (!gateAdmin(ctx)) return@Cmd
            val name = ctx.params.trim()
            if (name.isEmpty()) { reply(ctx, "用法：添加白名单 <玩家名>"); return@Cmd }
            val command = cfg.whitelistAddCmd.replace("{name}", name)
            runGameCommand(ctx, command)
        },
        Cmd("删除白名单") { ctx ->
            if (!gateAdmin(ctx)) return@Cmd
            val name = ctx.params.trim()
            if (name.isEmpty()) { reply(ctx, "用法：删除白名单 <玩家名>"); return@Cmd }
            val command = cfg.whitelistDelCmd.replace("{name}", name)
            runGameCommand(ctx, command)
        },
        Cmd("查白名单") { ctx ->
            val (_, output) = PenguinServerMod.runCommand("whitelist list")
            if (output.isEmpty()) { reply(ctx, "无输出"); return@Cmd }
            val names = parseWhitelist(output)
            if (cfg.featureMarkdownWhitelist && names != null) {
                qqClient.sendMarkdown(ctx.groupId, buildWhitelistMarkdown(names), ctx.msgId)
            } else {
                reply(ctx, output)
            }
        },
        Cmd("绑定白名单") { ctx ->
            val name = ctx.params.trim()
            if (name.isEmpty()) { reply(ctx, "用法：绑定白名单 <玩家名>"); return@Cmd }
            state.bindName(ctx.groupId, ctx.userId, name)
            val command = cfg.whitelistAddCmd.replace("{name}", name)
            PenguinServerMod.runCommand(command)
            reply(ctx, "绑定成功：QQ ${ctx.userId} ⇄ 游戏 $name\n已执行白名单加入：$command")
        },
        Cmd("解绑白名单") { ctx ->
            if (!gateAdmin(ctx)) return@Cmd
            val name = ctx.params.trim()
            if (name.isEmpty()) { reply(ctx, "用法：解绑白名单 <玩家名>"); return@Cmd }
            val openid = state.findOpenidByGameName(ctx.groupId, name)
            val removed = if (openid != null) state.unbindOpenid(ctx.groupId, openid) else null
            val command = cfg.whitelistDelCmd.replace("{name}", name)
            PenguinServerMod.runCommand(command)
            if (openid != null) {
                reply(ctx, "已解绑：QQ $openid ⇄ 游戏 $removed\n已执行白名单移除：$command")
            } else {
                reply(ctx, "未找到该游戏名的绑定记录（已执行白名单移除：$command）")
            }
        },
        Cmd("解除绑定") { ctx ->
            val removed = state.unbindOpenid(ctx.groupId, ctx.userId)
            if (removed == null) { reply(ctx, "你当前没有绑定记录"); return@Cmd }
            val command = cfg.whitelistDelCmd.replace("{name}", removed)
            PenguinServerMod.runCommand(command)
            reply(ctx, "已解除绑定：QQ ${ctx.userId} ⇄ 游戏 $removed\n已执行白名单移除：$command")
        },
        Cmd("认证") { ctx ->
            val target = ctx.params.trim()
            if (target.isEmpty()) {
                val status = if (state.isAuthenticated(ctx.groupId, ctx.userId)) "已认证" else "未认证"
                reply(ctx, "本人认证状态：$status\nOpenID：${ctx.userId}")
                return@Cmd
            }
            if (!gateAdmin(ctx)) return@Cmd
            val openid = target.split(Regex("\\s+")).last()
            state.authenticate(ctx.groupId, openid)
            reply(ctx, "已认证：$openid")
        },
        Cmd("解除认证") { ctx ->
            val target = ctx.params.trim()
            if (target.isEmpty()) {
                state.revoke(ctx.groupId, ctx.userId)
                reply(ctx, "已解除本人认证")
                return@Cmd
            }
            if (!gateAdmin(ctx)) return@Cmd
            val openid = target.split(Regex("\\s+")).last()
            state.revoke(ctx.groupId, openid)
            reply(ctx, "已解除认证：$openid")
        },
        Cmd("全量") { ctx ->
            if (!gateAdmin(ctx)) return@Cmd
            val enabled = parseOnOff(ctx.params) ?: run { reply(ctx, "用法：全量 <开/关>"); return@Cmd }
            state.setFullForwarding(ctx.groupId, enabled)
            reply(ctx, "已设置本群全量转发：${if (enabled) "开" else "关"}")
        }
    )

    // ---- 分发 ----

    private fun normalizeContent(content: String): String {
        var cleaned = content.trim()
        cleaned = cleaned.replace(Regex("<@!?[^>]+>"), "").trim()
        if (cleaned.startsWith("/")) cleaned = cleaned.removePrefix("/").trim()
        return cleaned
    }

    private fun dispatch(cleaned: String): Pair<Cmd, String>? {
        val sorted = commands.sortedByDescending { it.name.length }
        for (cmd in sorted) {
            if (cleaned == cmd.name || cleaned.startsWith("${cmd.name} ")) {
                val params = if (cleaned == cmd.name) "" else cleaned.removePrefix(cmd.name).trim()
                return Pair(cmd, params)
            }
        }
        return null
    }

    /** 群消息总入口。 */
    fun handle(message: GroupMessage) {
        val ctx = Ctx(
            msgId = message.id,
            groupId = message.groupId,
            userId = message.userId,
            username = message.username,
            memberRole = message.memberRole
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
            val match = dispatch(cleaned)
            if (match != null) {
                val (cmd, params) = match
                if (!cfg.isCommandEnabled(cmd.name)) {
                    if (cfg.debugLogEvents) logger.info("命令 ${cmd.name} 已被关闭")
                    reply(ctx, "此命令已被管理员关闭")
                    return
                }
                if (cfg.debugLogEvents)
                    logger.info("命中命令：${cmd.name} 参数=$params")
                ctx.params = params
                try {
                    cmd.exec(ctx)
                } catch (e: Exception) {
                    logger.error("命令 ${cmd.name} 执行出错", e)
                }
                return
            }
        }

        // 非命令消息：全量转发到游戏
        if (cfg.chatPostChat && state.isFullForwarding(message.groupId)) {
            val raw = message.content.trim()
            if (raw.isNotEmpty()) {
                com.huhobot.penguin.filter.TextFilter.audit(raw, cfg) { filtered ->
                    PenguinServerMod.broadcastToGame(
                        PenguinServerMod.formatGroupMessage(displayName(ctx), filtered)
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

    private fun buildOnlineMarkdown(players: List<String>): String {
        val sb = StringBuilder("# 在线玩家\n\n当前在线：**${players.size}** 人")
        if (players.isNotEmpty()) { sb.append("\n"); players.forEach { sb.append("\n- $it") } }
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
