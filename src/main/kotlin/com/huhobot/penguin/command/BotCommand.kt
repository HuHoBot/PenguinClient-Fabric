package com.huhobot.penguin.command

/**
 * 标记一个方法为 QQ 群命令
 *
 * 用于自动发现、注册命令，并同步到 QQ 群指令面板
 *
 * @param name 命令名称，如 "查在线"
 * @param description 命令描述，显示在 QQ 群指令面板
 * @param adminOnly 是否仅管理员可用
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class BotCommand(
    val name: String,
    val description: String,
    val adminOnly: Boolean = false
)

/**
 * 命令元数据，用于 QQ 面板同步
 */
data class CommandMetadata(
    val name: String,
    val description: String,
    val adminOnly: Boolean
)
