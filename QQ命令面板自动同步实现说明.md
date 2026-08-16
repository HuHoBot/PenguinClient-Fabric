# QQ 命令面板自动同步功能实现说明

## 实现概述

已成功为 PenguinClient-Fabric (mc26.2 分支) 实现了 QQ 命令面板自动解析和同步功能。

## 实现的功能

### 1. 注解驱动的命令系统
- 使用 `@BotCommand` 注解标记命令方法
- 自动通过反射扫描和注册命令
- 无需手动维护命令列表

### 2. 命令元数据收集
- 自动提取命令名、描述、权限信息
- 支持标记仅管理员可用的命令
- 提供统一的元数据接口供面板同步使用

### 3. QQ 面板自动同步
- 启动时自动同步命令到 QQ 群指令面板
- 重载配置时重新同步
- 使用指纹检测避免重复同步
- 持久化 panel_id 支持增量更新

## 新增文件

### 1. BotCommand.kt
```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class BotCommand(
    val name: String,           // 命令名
    val description: String,    // 命令描述
    val adminOnly: Boolean = false  // 是否仅管理员
)

data class CommandMetadata(
    val name: String,
    val description: String,
    val adminOnly: Boolean
)
```

### 2. CommandPanelSync.kt
QQ 群指令面板同步器，负责：
- 调用 QQ API 创建/更新指令面板
- 指纹计算和缓存检测
- panel_id 持久化到 `config/penguin-panel-state.properties`

## 修改的文件

### 1. CommandHandler.kt
**重大重构：**
- 从硬编码命令列表改为注解驱动
- 所有命令方法使用 `@BotCommand` 注解
- 通过反射自动扫描和注册命令
- 提供 `getCommandMetadata()` 接口返回命令元数据

**命令定义示例：**
```kotlin
@BotCommand("查在线", "查询在线玩家")
private fun cmdQueryOnline(ctx: Ctx) {
    // 实现逻辑
}

@BotCommand("执行命令", "执行服务器命令", adminOnly = true)
private fun cmdRunCommand(ctx: Ctx) {
    if (!gateAdmin(ctx)) return
    // 实现逻辑
}
```

### 2. PenguinServerMod.kt
**新增功能：**
- 初始化 `CommandPanelSync` 面板同步器
- 服务器启动后调用 `syncCommandPanel()` 同步面板
- 重载配置后重新同步面板

## 使用方法

### 添加新命令
只需添加一个带注解的方法：

```kotlin
@BotCommand("踢人", "踢出指定玩家", adminOnly = true)
private fun cmdKickPlayer(ctx: Ctx) {
    if (!gateAdmin(ctx)) return
    val playerName = ctx.params.trim()
    if (playerName.isEmpty()) {
        reply(ctx, "用法：踢人 <玩家名>")
        return
    }
    runGameCommand(ctx, "kick $playerName")
}
```

重启服务器后：
1. 命令自动注册到命令处理器
2. 自动同步到 QQ 群指令面板
3. 立即可在群内使用

### 配置文件
无需修改配置文件，只需确保 `config/penguin-server.json` 中配置了：
- `bot.app-id` - QQ 机器人 AppID
- `bot.secret` - QQ 机器人 Secret
- `bot.groups` - 要同步面板的群 OpenID 列表

### 面板状态文件
同步状态保存在 `config/penguin-panel-state.properties`，包含：
- `panel_id` - QQ 面板 ID（用于增量更新）
- `fingerprint` - 命令指纹（用于检测变化）

## 编译说明

**环境要求：**
- Java 25+（mc26.2 分支要求）
- Gradle 自动处理

**编译命令：**
```bash
export JAVA_HOME="/c/Program Files/Zulu/zulu-25"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew clean build
```

**生成文件：**
- `build/libs/penguin-server-fabric-1.1.2.jar` - 主 jar 文件
- `build/libs/penguin-server-fabric-1.1.2-sources.jar` - 源码 jar

## 测试步骤

### 1. 部署到测试服务器
```bash
# 将 jar 复制到服务器 mods 目录
cp build/libs/penguin-server-fabric-1.1.2.jar /path/to/server/mods/

# 确保已安装依赖：
# - Fabric Loader 0.16.0+
# - Fabric API (对应 MC 版本)
# - Fabric Language Kotlin 1.13.0+
```

### 2. 配置 QQ 机器人
编辑 `config/penguin-server.json`：
```json
{
  "bot": {
    "app-id": "你的机器人 AppID",
    "secret": "你的机器人 Secret",
    "groups": ["群的 group_openid"]
  }
}
```

### 3. 启动服务器
观察日志：
```
[PenguinServer-Fabric] 正在初始化...
[PenguinServer-Fabric] 已注册 21 个命令
[PenguinServer-Fabric] QQ 网关已启动
[PenguinServer-Fabric/PanelSync] 开始同步 21 个命令到 QQ 指令面板
[PenguinServer-Fabric/PanelSync] 指令面板同步成功: panel_id=xxx, commands=21
```

### 4. 验证功能

**在 QQ 群中：**
1. 查看群聊天界面，应该能看到 QQ 指令面板（输入框上方）
2. 点击面板应显示所有命令及描述
3. 测试命令：`@机器人 查在线`
4. 测试管理员命令：`@机器人 执行命令 list`（需要管理员权限）

**在服务器控制台：**
```bash
# 重载配置（会重新同步面板）
/penguin reload

# 查看服务器信息
/penguin info
```

### 5. 测试面板更新
修改 CommandHandler.kt 添加新命令：
```kotlin
@BotCommand("测试", "测试命令")
private fun cmdTest(ctx: Ctx) {
    reply(ctx, "这是一个测试命令")
}
```

重新编译并重启服务器，QQ 面板应自动更新显示新命令。

## 技术亮点

### 1. 完全自动化
- 添加命令只需一个注解，无需手动注册
- 命令元数据自动提取和同步
- 面板状态自动持久化

### 2. 增量更新
- 使用 SHA-256 指纹检测命令变化
- 只在命令变化时才调用 QQ API
- 持久化 panel_id 避免重复创建

### 3. 容错性强
- 面板同步失败不影响命令功能
- 自动重试机制（创建失败时）
- 详细的日志输出便于调试

### 4. 向后兼容
- 保留所有原有命令功能
- 不改变命令执行逻辑
- 配置文件完全兼容

## 对比原 PenguinClient 实现

| 特性 | PenguinClient (原版) | PenguinClient-Fabric (本次实现) |
|------|---------------------|--------------------------------|
| 注解系统 | @Commands | @BotCommand |
| 反射扫描 | BaseCommand 基类 | CommandHandler 直接扫描 |
| 面板同步 | MenuManager | CommandPanelSync |
| 指纹算法 | SHA-256 | SHA-256（相同） |
| 持久化 | qq-panel-state.properties | penguin-panel-state.properties |
| 自定义命令 | 支持融合 | 当前未实现融合（可扩展） |

## 已知限制

1. **自定义命令未融合到面板**：当前只同步内置命令，自定义命令需要单独处理
2. **面板 API 频率限制**：QQ API 有调用频率限制，短时间内多次重载可能失败
3. **错误恢复**：面板创建失败时会自动重试，但多次失败后需要手动干预

## 扩展建议

### 1. 自定义命令融合
在 `syncCommandPanel()` 中合并自定义命令元数据：
```kotlin
val customMetadata = custom.snapshot().map {
    CommandMetadata(
        name = it.key,
        description = "自定义命令",
        adminOnly = it.permission > 0
    )
}
val allMetadata = commandHandler.getCommandMetadata() + customMetadata
panelSync.syncCommands(allMetadata)
```

### 2. 多语言支持
扩展 `@BotCommand` 支持多语言描述：
```kotlin
annotation class BotCommand(
    val name: String,
    val description: String,
    val descriptionEn: String = "",
    val adminOnly: Boolean = false
)
```

### 3. 命令分组
添加 category 字段实现命令分类：
```kotlin
annotation class BotCommand(
    val name: String,
    val description: String,
    val category: String = "常用",
    val adminOnly: Boolean = false
)
```

## 总结

本次实现成功将 PenguinClient 的 QQ 命令面板自动解析功能移植到 PenguinClient-Fabric，采用注解驱动的方式大幅简化了命令管理，实现了命令到 QQ 面板的自动同步，提升了开发效率和用户体验。

编译生成的 jar 文件位于：
- **主程序**: `build/libs/penguin-server-fabric-1.1.2.jar`
- **源码**: `build/libs/penguin-server-fabric-1.1.2-sources.jar`

现在可以将 jar 文件部署到测试服务器进行测试。
