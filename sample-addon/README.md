# example-addon

`PenguinServer-Fabric` 附属插件的完整示例工程，克隆即可构建。

完整文档见仓库根目录的 [`ADDON-DEV-GUIDE.md`](../ADDON-DEV-GUIDE.md)。

## 构建

```bash
./gradlew jar
```

产物：`build/libs/example-addon-1.0.0.jar`（无需先编译模组本体，宿主 API 以源码形式参与编译）。

要求 JDK 21+。编译 mc26.2 分支的宿主 jar 时加 `-PjavaRelease=25`。

## 安装

把 jar 丢进 `<游戏目录>/config/penguin-addons/`，然后：

- QQ 群发 `重载插件`，或
- 控制台 `/penguin addons reload`

## 改成你自己的插件

1. **改类名与元数据** —— `src/main/kotlin/example/huhobot/ExampleAddon.kt`

   ```kotlin
   override val meta = Addon(
       name = "myaddon",        // 插件唯一标识，不要和内置或其他插件重名
       version = "1.0.0",
       description = "我的插件",
       author = "你的名字"
   )
   ```

2. **改入口声明** —— `src/main/resources/penguin-addon.json`

   ```json
   { "mainClass": "你的包名.你的类名" }
   ```

   漏了这个文件也能加载（加载器会全量扫描 jar），但 jar 里有多个 `AddonProvider` 实现时行为不保证。

3. **写命令** —— 在 `onLoad(api)` 里注册

   ```kotlin
   override fun onLoad(api: AddonApi) {
       api.registerCommand("你好", "打招呼") { ctx ->
           ctx.reply("你好，${ctx.displayName}")
       }
   }
   ```

## 这个示例演示了什么

| 位置 | 演示内容 |
|---|---|
| `onLoad` 里的 `你好` | 最简单的命令，用 `ctx.reply` 回复 |
| `onLoad` 里的 `回声` | 读 `ctx.params` 拿参数 |
| `onLoad` 里的 `踢人` | `onlyAdmin = true` + `api.runCommand` 执行 MC 命令 |
| `onLoad` 里的 `查在线` | 故意占用内置命令名，演示**内置命令保护**（会被拒绝，返回 `false`） |
| `onEnable` / `onDisable` | 两个生命周期回调 |

## 注意

- **不要把 `src/main/kotlin/com/huhobot/` 打进 jar**。那里是宿主 API 的编译期桩，
  打包时已用 `exclude("com/huhobot/**")` 排除。去掉这个排除会导致 jar 里出现同名类副本，
  加载器扫描时行为不可预期。
- `onDisable` 里记得停掉自己起的后台线程——插件 jar 会被 `URLClassLoader.close()` 卸载。
- `onlyAdmin = true` **不会自动拦截**，只是把 `ctx.isAdmin` 告诉你，要自己在回调里判。
