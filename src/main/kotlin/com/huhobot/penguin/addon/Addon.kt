package com.huhobot.penguin.addon

import com.huhobot.penguin.command.CommandHandler

/**
 * 已注册的附属插件元数据，对齐主仓库 `cn.huohuas001.bot.addon.Addon`。
 *
 * @param name        插件名（唯一标识，同时也是 QQ 指令面板里的「来源」标注）
 * @param version     版本号
 * @param description 简要描述
 * @param author      作者
 */
data class Addon(
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = ""
)

/**
 * 附属插件命令的元数据，对齐主仓库的 `RegisteredCommand`。
 *
 * @param source 所属附属插件名，内置命令为 null
 */
data class AddonCommand(
    val command: String,
    val describe: String,
    val onlyAdmin: Boolean = false,
    val source: String? = null
)

/**
 * 附属插件可用的宿主能力。
 *
 * 附属插件只依赖这个接口，不直接碰 mod 内部类，便于跨版本兼容。
 */
interface AddonApi {

    /** 当前生效的配置对象（只读访问）。 */
    val config: com.huhobot.penguin.config.PenguinConfig

    /** 注册一条 QQ 群命令。命令名冲突时返回 false，不会覆盖已有命令。 */
    fun registerCommand(
        name: String,
        describe: String,
        onlyAdmin: Boolean = false,
        handler: (CommandHandler.Ctx) -> Unit
    ): Boolean

    /** 向指定 QQ 群发普通文本。 */
    fun sendGroupMessage(groupId: String, content: String)

    /** 向所有已配置 QQ 群发普通文本。 */
    fun sendTextToAllGroups(content: String)

    /** 广播文字到游戏内所有在线玩家。 */
    fun broadcastToGame(message: String)

    /** 执行服务端控制台命令，返回 (success, output)。 */
    fun runCommand(command: String): Pair<Boolean, String>

    /** 判断某 QQ 群成员是否为管理员。 */
    fun isAdmin(groupId: String, userId: String, memberRole: String?): Boolean

    /** 写一条带插件名前缀的日志。 */
    fun log(message: String)

    /** 写一条警告日志。 */
    fun logWarn(message: String)

    /** 写一条错误日志。 */
    fun logError(message: String)
}

/**
 * 附属插件入口。
 *
 * 实现类必须是 public 且有无参构造。加载器实例化后依次调用
 * [onLoad]（必须在这里完成命令注册）与 [onEnable]。
 */
interface AddonProvider {
    /** 插件元数据。 */
    val meta: Addon

    /** 注册命令、申请资源的阶段。 */
    fun onLoad(api: AddonApi) {}

    /** 全部加载完成、网关可用后的阶段。 */
    fun onEnable() {}
}

/**
 * 卸载回调，由加载器在 reload / 关服时调用。
 * 不实现则不做任何清理。
 */
interface AddonLifecycle {
    fun onDisable() {}
}