package com.huhobot.penguin.command

import com.huhobot.penguin.PenguinServerMod
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.SharedConstants

/**
 * 注册 /penguin 和 /huhobot 控制台/OP 命令，对齐 BDS 版 huhobot reload / huhobot info。
 * 用法：/penguin reload | /penguin info | /penguin send <消息> | /penguin sync
 *      /huhobot reload | /huhobot info | /huhobot send <消息> | /huhobot sync
 */
object PenguinCommand {

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            registerCommands(dispatcher, "penguin")
            registerCommands(dispatcher, "huhobot")
        }
    }

    private fun registerCommands(dispatcher: CommandDispatcher<ServerCommandSource>, cmdName: String) {
        dispatcher.register(
            literal(cmdName)
                .requires { it.hasPermissionLevel(4) }
                .then(
                    literal("reload").executes { ctx ->
                        PenguinServerMod.reload()
                        ctx.source.sendFeedback({ Text.literal("[PenguinServer] 配置已重载，QQ 网关已重启") }, true)
                        1
                    }
                )
                .then(
                    literal("info").executes { ctx ->
                        val mcVersion = try {
                            SharedConstants.getGameVersion().name
                        } catch (e: Exception) {
                            "1.20.1"
                        }
                        val status = if (PenguinServerMod.config.botAppId.isNotBlank()) "已配置" else "未配置（请编辑 config/penguin-server.json）"
                        ctx.source.sendFeedback({ Text.literal("[PenguinServer] 版本 1.1.4") }, false)
                        ctx.source.sendFeedback({ Text.literal("[PenguinServer] 环境：Fabric $mcVersion 服务端") }, false)
                        ctx.source.sendFeedback({ Text.literal("[PenguinServer] 状态：$status") }, false)
                        1
                    }
                )
                .then(
                    literal("sync").executes { ctx ->
                        ctx.source.sendFeedback({ Text.literal("[PenguinServer] 正在同步 QQ 指令面板...") }, false)
                        PenguinServerMod.syncCommandPanel()
                        1
                    }
                )
                .then(
                    literal("send")
                        .then(
                            argument("message", StringArgumentType.greedyString())
                                .executes { ctx ->
                                    val msg = StringArgumentType.getString(ctx, "message")
                                    for (groupId in PenguinServerMod.config.botGroups) {
                                        PenguinServerMod.qqClient.sendGroupMessage(groupId, msg)
                                    }
                                    ctx.source.sendFeedback({ Text.literal("[PenguinServer] 已发送到 QQ 群：$msg") }, false)
                                    1
                                }
                        )
                )
        )
    }
}
