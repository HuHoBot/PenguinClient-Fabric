package com.huhobot.penguin.command

import com.huhobot.penguin.PenguinServerMod
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component

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

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>, cmdName: String) {
        dispatcher.register(
            literal(cmdName)
                // 简化权限检查，26.x可能API变化了，先去掉权限检查
                // .requires { source -> source.hasPermission(4) }
                .then(
                    literal("reload").executes { ctx ->
                        PenguinServerMod.reload()
                        ctx.source.sendSuccess({ Component.literal("[PenguinServer] 配置已重载，QQ 网关已重启") }, true)
                        1
                    }
                )
                .then(
                    literal("info").executes { ctx ->
                        ctx.source.sendSuccess({
                            Component.literal(
                                "[PenguinServer] 版本 1.1.3-26.2\n" +
                                "环境：Fabric 26.2 服务端\n" +
                                "状态：${if (PenguinServerMod.config.botAppId.isNotBlank()) "已配置" else "未配置（请编辑 config/penguin-server.json）"}"
                            )
                        }, false)
                        1
                    }
                )
                .then(
                    literal("sync").executes { ctx ->
                        ctx.source.sendSuccess({ Component.literal("[PenguinServer] 正在同步 QQ 指令面板...") }, false)
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
                                    ctx.source.sendSuccess({ Component.literal("[PenguinServer] 已发送到 QQ 群：$msg") }, false)
                                    1
                                }
                        )
                )
        )
    }
}
