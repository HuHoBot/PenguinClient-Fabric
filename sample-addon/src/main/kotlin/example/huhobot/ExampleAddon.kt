package example.huhobot

import com.huhobot.penguin.addon.Addon
import com.huhobot.penguin.addon.AddonApi
import com.huhobot.penguin.addon.AddonLifecycle
import com.huhobot.penguin.addon.AddonProvider

/**
 * 示例附属插件。
 *
 * 验证点：
 * - 三条命令（普通 /仅管理员 / 占用内置命令名）
 * - onLoad / onEnable / onDisable 三个生命周期回调
 * - 通过 api 访问宿主能力
 */
class ExampleAddon : AddonProvider, AddonLifecycle {

    override val meta = Addon(
        name = "example",
        version = "1.0.0",
        description = "演示附属插件加载器的命令注册与生命周期",
        author = "HuHoBot"
    )

    override fun onLoad(api: AddonApi) {
        // 普通命令
        api.registerCommand("你好", "示例插件打招呼") { ctx ->
            ctx.reply("来自附属插件 example 的问候，发送者 ${ctx.displayName}")
        }

        // 带参数
        api.registerCommand("回声", "示例插件：原样返回参数") { ctx ->
            if (ctx.params.isBlank()) {
                ctx.reply("用法：回声 <内容>")
            } else {
                ctx.reply("回声：${ctx.params}")
            }
        }

        // 仅管理员，参数里可以带敏感词过滤后的结果
        api.registerCommand("踢人", "示例插件：踢出玩家（仅管理员）", onlyAdmin = true) { ctx ->
            if (!ctx.isAdmin) {
                ctx.reply("你没有管理员权限")
                return@registerCommand
            }
            val (success, output) = api.runCommand("kick ${ctx.params} 已被示例插件踢出")
            ctx.reply(if (success) "已踢出 ${ctx.params}" else "踢出失败：$output")
        }

        // 故意占用内置命令名，应该被拒绝而不是覆盖
        val taken = api.registerCommand("查在线", "试图覆盖内置命令") { ctx ->
            ctx.reply("这条不应该出现")
        }
        if (taken) {
            api.logWarn("内置命令保护失效：查在线 被覆盖了")
        } else {
            api.log("内置命令保护正常：查在线 拒绝了覆盖")
        }
    }

    override fun onEnable() {
        // 命令数从桩里拿不到真实值，这里只确认生命周期被调用
        println("[example] onEnable")
    }

    override fun onDisable() {
        println("[example] onDisable")
    }
}