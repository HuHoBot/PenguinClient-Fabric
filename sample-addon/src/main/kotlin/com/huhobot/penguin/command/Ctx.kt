package com.huhobot.penguin.command

/**
 * 附属插件能看到的命令上下文。
 *
 * 注意这里是**嵌套类** `CommandHandler.Ctx`，不是顶层类——宿主里它定义在
 * `CommandHandler` 内部，编译产物是 `CommandHandler$Ctx`。stub 必须和宿主
 * 保持同样的嵌套结构，否则二进制名对不上，插件运行时会
 * `NoClassDefFoundError: com/huhobot/penguin/command/Ctx`。
 *
 * 只声明示例插件用到的字段，真实上下文与此完全一致。
 */
class CommandHandler {
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
}
