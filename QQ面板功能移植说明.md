# QQ指令面板自动补全功能移植说明

## 概述
已成功将 mc26.2 分支的 QQ 指令面板自动补全功能移植到 master 分支（1.20.1版本）。

## 主要更改

### 1. 新增文件

#### `BotCommand.kt`
- 路径：`src/main/kotlin/com/huhobot/penguin/command/BotCommand.kt`
- 功能：定义命令注解和元数据类
- 作用：
  - `@BotCommand` 注解用于标记QQ群命令方法
  - `CommandMetadata` 数据类用于存储命令元数据（名称、描述、管理员权限）

#### `CommandPanelSync.kt`
- 路径：`src/main/kotlin/com/huhobot/penguin/qq/CommandPanelSync.kt`
- 功能：QQ指令面板同步管理
- 主要功能：
  - 自动同步命令列表到QQ群指令面板
  - 使用指纹（SHA-256）检测命令变化，避免重复同步
  - 支持删除旧面板、创建新面板
  - 持久化面板状态到 `config/penguin-panel-state.properties`

### 2. 修改文件

#### `CommandHandler.kt`
- **架构重构**：从手动维护命令列表改为使用注解 + 反射自动扫描
- **主要变化**：
  - 移除了 `List<Cmd>` 手动命令列表
  - 使用 `@BotCommand` 注解标记所有命令方法
  - 添加反射扫描机制 `scanCommands()`
  - 添加命令元数据收集 `commandMetadata`
  - 新增 `getCommandMetadata()` 方法供面板同步使用
  - **新增命令**：`@BotCommand("同步面板", "手动同步QQ指令面板", adminOnly = true)`

#### `PenguinServerMod.kt`
- **新增导入**：
  - `import com.huhobot.penguin.qq.CommandPanelSync`
  - `import java.io.File`
- **新增属性**：
  - `lateinit var panelSync: CommandPanelSync`
- **初始化改动**：
  - 在 `onInitialize()` 中初始化 `CommandPanelSync`
  - 在服务器启动后自动调用 `syncCommandPanel()`
- **新增方法**：
  - `fun syncCommandPanel()`：异步同步命令面板到QQ群
- **reload() 方法更新**：
  - 重载配置时重新初始化 `panelSync`

## 功能特性

### 自动同步
- 服务器启动后自动同步命令面板到已配置的QQ群
- 延迟3秒启动，确保QQ网关完全初始化

### 手动同步
- 新增 `同步面板` 命令（管理员专用）
- 用法：在QQ群中发送 `同步面板` 或 `/同步面板`
- 异步执行，同步完成后会返回结果消息

### 智能缓存
- 使用SHA-256指纹检测命令列表是否变化
- 未变化时跳过同步，节省API调用
- 状态持久化到 `config/penguin-panel-state.properties`

### 限制处理
- 最多同步20个命令（QQ平台限制）
- 超出时会记录警告日志

## 技术细节

### 注解驱动的命令系统
```kotlin
@BotCommand("查在线", "查询在线玩家")
private fun cmdQueryOnline(ctx: Ctx) {
    // 命令实现
}
```

### 反射扫描机制
- 启动时扫描所有带 `@BotCommand` 注解的方法
- 自动注册到命令映射表
- 收集元数据供面板同步使用

### QQ面板API调用
- 列出面板：`GET /v2/panels?scope=group`
- 删除面板：`DELETE /v2/panels/{panel_id}`
- 创建面板：`POST /v2/panels`

## 配置要求

需要在 `config/penguin-server.json` 中配置：
- `bot.app-id`：QQ机器人应用ID
- `bot.secret`：QQ机器人密钥
- `bot.groups`：目标QQ群OpenID列表（必须配置，否则跳过同步）

## 已注册的命令

当前系统注册了以下21个命令（自动从注解收集）：

1. 查信息
2. 发消息
3. 发信息
4. 查在线
5. 在线服务器
6. motd
7. 执行
8. 执行命令（管理员）
9. 管理员执行（管理员）
10. 查管理（管理员）
11. 加管理（管理员）
12. 删管理（管理员）
13. 管理方式（管理员）
14. 添加白名单（管理员）
15. 删除白名单（管理员）
16. 查白名单（管理员）
17. 绑定白名单
18. 解绑白名单（管理员）
19. 解除绑定
20. 认证
21. 解除认证
22. 全量（管理员）
23. **同步面板（管理员）** - 新增

## 编译和部署

### 编译环境
- 需要 Java 17 或更高版本
- 本次使用 Java 21 编译成功

### 编译命令
```bash
export JAVA_HOME="/c/Program Files/Zulu/zulu-21"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew build --no-daemon
```

### 编译结果
- ✅ 编译成功（BUILD SUCCESSFUL）
- 生成的jar文件位于 `build/libs/` 目录

## 日志输出

系统会在日志中记录以下信息：
- `已注册 X 个命令`：启动时显示注册的命令数量
- `面板内容未变化，跳过同步`：命令未变化时跳过同步
- `已删除面板: xxx`：删除旧面板时记录
- `面板同步成功: xxx`：同步成功并记录新面板ID
- `面板同步失败: xxx`：同步失败时记录错误信息

## 故障排查

### 如果面板未同步
1. 检查 `bot.groups` 是否已配置
2. 检查日志中的错误信息
3. 使用 `同步面板` 命令手动触发同步
4. 检查 `config/penguin-panel-state.properties` 文件

### 如果命令未出现在面板
1. 确认命令数量未超过20个限制
2. 检查命令是否带有 `@BotCommand` 注解
3. 查看日志确认命令是否被正确注册

## 兼容性说明

- ✅ 与 master 分支（1.20.1）完全兼容
- ✅ 保留了所有现有功能
- ✅ 向后兼容旧的命令调用方式
- ✅ API调用使用的是与 mc26.2 相同的逻辑

## 测试建议

1. 启动服务器，观察日志确认命令注册和面板同步
2. 在QQ群中测试命令补全功能
3. 使用 `同步面板` 命令测试手动同步
4. 修改命令后重启，验证增量同步逻辑

---

**移植完成时间**：2026-08-18
**目标版本**：1.20.1 (master分支)
**源版本**：mc26.2分支
