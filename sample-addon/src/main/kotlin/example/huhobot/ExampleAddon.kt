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

        registerMarkdownSamples(api)
    }

    /**
     * Markdown 渲染测试。
     *
     * QQ 群消息的 markdown 是**受限子集**，不支持按钮（按钮只在群指令面板里，
     * 而面板是纯命令列表）。这里覆盖能验的部分：标题、粗体、斜体、行内代码、
     * 代码块、引用、列表、链接、图片、分隔线。
     *
     * 每项单独一条消息——QQ 渲染失败时是整条消息都不显示，
     * 混在一起看不出是哪一项挂了。
     */
    private fun registerMarkdownSamples(api: AddonApi) {
        api.registerCommand("md标题", "渲染测试：标题与行内样式") { ctx ->
            ctx.replyMarkdown(
                """
                # 一级标题
                ## 二级标题
                ### 三级标题
                正常文字
                **粗体**　*斜体*　***粗斜体***
                `行内代码`
                ~~删除线~~（如果 QQ 支持）
                """.trimIndent()
            )
        }

        api.registerCommand("md列表", "渲染测试：有序/无序列表与引用") { ctx ->
            ctx.replyMarkdown(
                """
                无序列表：
                - 第一项
                - 第二项
                  - 嵌套项
                - 第三项

                有序列表：
                1. 步骤一
                2. 步骤二
                3. 步骤三

                引用：
                > 这是引用行
                > 第二行引用
                """.trimIndent()
            )
        }

        api.registerCommand("md代码", "渲染测试：代码块与语言标记") { ctx ->
            ctx.replyMarkdown(
                """
                无语言标记：
                ```
                val x: Int = 1
                println("hello")
                ```
                带语言标记：
                ```kotlin
                fun main() {
                    println("kotlin 语法高亮")
                }
                ```
                """.trimIndent()
            )
        }

        api.registerCommand("md链接", "渲染测试：链接与图片") { ctx ->
            // 图片域名必须用国内可达的。mc-heads.net 被墙（DNS 解析到
            // 198.18.0.111 这个 benchmark 保留段），QQ 服务器拉不到会显示裂图。
            // motd.txssb.cn 是「查在线」命令一直在用的，能正常显示。
            ctx.replyMarkdown(
                """
                行内链接：[QQ开放平台](https://bot.q.qq.com/)
                带文字链接：[点我](https://example.com/test?a=1&b=2)

                图片（国内可达域名）：
                ![Motd #400px #200px](https://motd.txssb.cn/api/status_img?theme=simple&ip=tuf.xn--55qx5d.cn&port=25565&dark=true&lang=zh-CN)

                图片（无尺寸参数）：
                ![Motd](https://motd.txssb.cn/api/status_img?theme=simple&ip=tuf.xn--55qx5d.cn&port=25565)
                """.trimIndent()
            )
        }

        api.registerCommand("md综合", "渲染测试：全部元素混合") { ctx ->
            ctx.replyMarkdown(
                """
                # 综合渲染测试
                **时间**：2026-10-03　**群**：${ctx.groupId.take(8)}…

                ---

                ## 列表
                1. 第一
                2. 第二

                ## 代码
                `inline` 与
                ```
                block
                ```

                ## 引用
                > 引用内容

                ## 链接
                [QQ官方文档](https://bot.q.qq.com/wiki/)
                """.trimIndent()
            )
        }

        // 边界情况：空内容 / 超长文本 / 特殊字符，用来确认不会让发送端报错
        api.registerCommand("md边界", "渲染测试：空内容与超长文本") { ctx ->
            ctx.replyMarkdown("**加粗**`代码`*斜体*[链接](https://a.b)")
        }

        api.registerCommand("md长文", "渲染测试：超长文本截断表现") { ctx ->
            ctx.replyMarkdown(buildString {
                appendLine("# 超长文本测试")
                repeat(40) { appendLine("第 $it 行：中文字符与 English mixed 0123456789") }
            })
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