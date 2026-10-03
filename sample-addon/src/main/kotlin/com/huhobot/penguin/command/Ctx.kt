package com.huhobot.penguin.command

/**
 * 附属插件能看到的命令上下文。
 *
 * 这是宿主内部的类，插件只依赖它的字段。此处仅声明示例插件用到的部分，
 * 签名与真实实现保持一致；真实上下文还带有 msgId / groupId / userId 等字段。
 */
data class Ctx(
    val msgId: String,
    val groupId: String,
    val userId: String,
    val username: String?,
    val memberRole: String?,
    var params: String = "",
    val reply: (String) -> Unit = {},
    val replyMarkdown: (String) -> Unit = {},
    val isAdmin: Boolean = false,
    val displayName: String = username ?: userId
)
