# PenguinServer-Fabric 附属插件开发指南

附属插件是一个普通 jar，扔进 `config/penguin-addons/` 就能被加载，不需要放进 `mods/`。

`master`（MC 1.20.1）与 `mc26.2`（MC 26.2）两个分支都已内置加载器，API 完全一致。

> **不想读文档？** 仓库里的 [`sample-addon/`](sample-addon/) 是一个能独立构建的完整示例工程，
> 包含三种命令写法（普通 / 仅管理员 / 占用内置命令名）和三个生命周期回调。
> `cd sample-addon && ./gradlew jar` 就能产出可用的插件 jar。

## 目录

- [1. 最小可用示例](#1-最小可用示例)
- [2. 目录与加载规则](#2-目录与加载规则)
- [3. 生命周期](#3-生命周期)
- [4. AddonApi 能做什么](#4-addonapi-能做什么)
- [5. 命令注册规则](#5-命令注册规则)
- [6. 工程配置](#6-工程配置)
- [7. 调试与常见问题](#7-调试与常见问题)

## 1. 最小可用示例

```kotlin
package example.huhobot

import com.huhobot.penguin.addon.Addon
import com.huhobot.penguin.addon.AddonApi
import com.huhobot.penguin.addon.AddonProvider

class ExampleAddon : AddonProvider {

    override val meta = Addon(
        name = "example",
        version = "1.0.0",
        description = "演示附属插件",
        author = "你的名字"
    )

    override fun onLoad(api: AddonApi) {
        api.registerCommand("你好", "示例插件打招呼") { ctx ->
            ctx.reply("你好，${ctx.displayName}")
        }
    }
}
```

`penguin-addon.json` 放在插件 jar 的根目录：

```json
{ "mainClass": "example.huhobot.ExampleAddon" }
```

编译产物丢到 `config/penguin-addons/`，然后在 QQ 群里发 `重载插件`，或控制台执行 `/penguin addons reload`。

## 2. 目录与加载规则

**放置位置**：`<游戏目录>/config/penguin-addons/*.jar`，只扫描这一层，不递归子目录。

**入口类发现**（按顺序）：
1. 读 jar 里的 `penguin-addon.json`，取 `mainClass` 字段；
2. 读不到或类加载失败时，退化为全量扫描 jar 中所有实现了 `AddonProvider` 且有无参构造的类。

**要求**：
- 实现类必须 `public` 且有无参构造（加载器用 `getDeclaredConstructor().newInstance()`）；
- 同一目录的 jar 会被逐个独立加载，一个失败不影响其他。

## 3. 生命周期

| 时机 | 回调 | 说明 |
|---|---|---|
| 插件加载 | `onLoad(api)` | **必须在这里注册命令**，此时分发链已接好 |
| 全部加载完 | `onEnable()` | 网关可用，可发消息 |
| 重载 / 关服 | `onDisable()` | 需实现 `AddonLifecycle` 才有此回调 |

时序：`onLoad(所有插件)` → `onEnable(所有插件)`。重载时先对所有插件走 `onDisable`，再重新 `onLoad`。

`onDisable` 里请停掉自己起的后台线程——插件的 jar 会被 `URLClassLoader.close()` 卸载，线程里持有的类引用会失效。

## 4. AddonApi 能做什么

```kotlin
val config: PenguinConfig        // 只读配置
fun registerCommand(name, describe, onlyAdmin, handler): Boolean
fun sendGroupMessage(groupId, content)          // 发文本到指定群
fun sendTextToAllGroups(content)                // 发到所有已配置群
fun broadcastToGame(message)                    // 广播到游戏内
fun runCommand(command): Pair<Boolean, String>   // 执行 MC 控制台命令
fun isAdmin(groupId, userId, memberRole): Boolean
fun log / logWarn / logError                    // 带插件名前缀的日志
```

### 命令回调的 Ctx

```kotlin
ctx.msgId          // 原消息 id，可用于撤回
ctx.groupId        // 群 openid
ctx.userId         // 发送者 openid
ctx.username       // 昵称，可能为 null
ctx.memberRole     // "owner" / "admin" / "member"
ctx.params         // 命令名之后的参数
ctx.displayName    // 展示名，username 为 null 时回退为 openid
ctx.reply(text)            // 回复该消息
ctx.replyMarkdown(md)      // 回复 Markdown
ctx.isAdmin         // 是否管理员
```

`onlyAdmin = true` 只是把 `isAdmin` 告诉你，**不自动拦截**——要在回调里自己判：

```kotlin
api.registerCommand("踢人", "踢出玩家", onlyAdmin = true) { ctx ->
    if (!ctx.isAdmin) {
        ctx.reply("权限不足")
        return@registerCommand
    }
    val (ok, output) = api.runCommand("kick ${ctx.params}")
    ctx.reply(if (ok) "已踢出" else "失败：$output")
}
```

## 5. 命令注册规则

- **内置命令优先**：插件不能覆盖已有命令，`registerCommand` 返回 `false` 并打 WARN。
- 同名插件之间也不能互相覆盖，先注册的赢。
- 命令名匹配按长度降序，所以 `添加白名单` 不会被 `添加` 抢先。
- 每条插件命令会同步到 QQ 群指令面板，但面板上限 20 项，排序策略是**内置优先、插件补足**（插件命令排在所有内置命令之后按名称排）。插件命令多于剩余槽位时会被挤出面板，但仍能手动发命令调用。

## 6. 工程配置

**最快的路子：直接复制仓库里的 `sample-addon/`**，它是一个能独立构建的完整工程，改类名就能用。

```bash
cp -r sample-addon/ my-addon/
cd my-addon
# 改 src/main/kotlin/example/huhobot/ExampleAddon.kt 里的类名、meta.name
# 改 src/main/resources/penguin-addon.json 里的 mainClass
./gradlew jar
```

产物 `build/libs/example-addon-1.0.0.jar` 丢进 `config/penguin-addons/` 即可。

### 宿主 API 从哪来

插件编译时需要 `AddonApi` / `AddonProvider` 这些类型。`sample-addon` 的做法是**把 API 声明为源码放在 `src/main/kotlin/com/huhobot/penguin/` 下**（`AddonApi.kt` 和 `Ctx.kt` 两个文件），并在打包时排除：

```kotlin
tasks.jar {
    // 宿主 API 桩只用于编译，绝不能进 jar
    exclude("com/huhobot/**")
}
```

> **为什么必须 `exclude`**：不打这个排除，jar 里会带一份 `com.huhobot.penguin.addon.Addon` 的副本。
> 加载器扫描 jar 时会扫到这些副本，而它们由插件自己的类加载器加载，与宿主的同名类**不是同一个 `Class` 对象**，
> `isAssignableFrom` 判断会失效，行为不可预期。

这个方案的好处是不依赖任何外部 jar，示例工程克隆下来就能编译。

### 改用真实的模组 jar 编译

不想带桩，或者要用宿主新增的 API 时，传 `-PpenguinJar` 指向真实 jar：

```bash
./gradlew jar -PpenguinJar=/path/to/penguin-server-fabric-1.1.6-pre1.jar
```

按目标分支选对应产物：

| 分支 | Minecraft | Java | 产物 |
|---|---|---|---|
| `master` | 1.20.1 ~ 1.21.x | 21 | `*-mc1.20.1.jar` |
| `mc26.2` | 26.1+ | 25 | `*-mc26.2.jar` |

编译 mc26.2 的 jar 时加上 `-PjavaRelease=25`。

从 [Releases](https://github.com/HuHoBot/PenguinClient-Fabric/releases) 下载。

两个分支的 `AddonApi` / `AddonProvider` / `AddonLifecycle` 签名相同，同一份插件代码可以原样跑在两边（前提是没用到分支特有的 Minecraft API）。

## 7. 调试与常见问题

**加载失败**——看服务端日志，形如 `附属插件 xxx 加载失败：<原因>`。常见原因：
- 漏了 `penguin-addon.json`，且 jar 里有多个 `AddonProvider` 实现类（全量扫描时只取第一个，行为不保证）；
- 实现类不是 public 或没有无参构造；
- 依赖了 mod 里不存在的类版本（插件按 `compileOnly` 编译，不校验宿主版本）。

**命令能注册但群里不生效**——确认 `onLoad` 而不是 `onEnable` 里注册的。`onEnable` 在分发链接好之后才跑，实际上也能工作，但重载时序更绕，统一放 `onLoad` 更稳。

**想确认当前加载了哪些插件**——QQ 群发 `附属插件`，或控制台 `/penguin addons`。
