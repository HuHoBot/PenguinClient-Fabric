package com.huhobot.penguin.addon

import com.huhobot.penguin.command.Ctx

/**
 * 附属插件 API —— 编译期桩。
 *
 * 这个文件**不属于模组本体**，只是为了让示例工程能独立编译。
 * 运行时加载器会提供宿主里的真实实现，插件 jar 里不含这个类。
 *
 * 签名与 `src/main/kotlin/com/huhobot/penguin/addon/Addon.kt` 保持一致；
 * 宿主新增能力时，同步更新这里。
 */

/** 插件元数据。 */
data class Addon(
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = ""
)

/** 附属插件可用的宿主能力。 */
interface AddonApi {

    /** 注册一条 QQ 群命令。命令名冲突时返回 false，不会覆盖已有命令。 */
    fun registerCommand(
        name: String,
        describe: String,
        onlyAdmin: Boolean = false,
        handler: (Ctx) -> Unit
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
 * 实现类必须 public 且有无参构造。加载器实例化后依次调用
 * onLoad（在这里完成命令注册）与 onEnable。
 */
interface AddonProvider {
    val meta: Addon
    fun onLoad(api: AddonApi) {}
    fun onEnable() {}
}

/** 卸载回调，由加载器在 reload / 关服时调用。 */
interface AddonLifecycle {
    fun onDisable() {}
}

/** 附属插件命令的元数据。 */
data class AddonCommand(
    val command: String,
    val describe: String,
    val onlyAdmin: Boolean = false,
    val source: String? = null
)

/**
 * 插件注册中心，插件可用它查询自己注册了哪些命令。
 *
 * 桩版本无法得知真实注册表，这里退化为返回传入的计数；真实实现按addon 名查索引。
 */
object AddonManager {
    var commandCount: Int = 0
    fun commandsOf(addonName: String): List<AddonCommand> = List(commandCount) {
        AddonCommand("command-$it", describe = "", onlyAdmin = false, source = addonName)
    }
}
