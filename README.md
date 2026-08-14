# PenguinClient-Fabric

HuHoBot Penguin 的 Fabric 客户端模组，用于在 Minecraft 1.20.1 客户端与服务器端 HuHoBot 通信。

## 功能特性

✅ **QQ 消息接收** - 实时接收 QQ 群消息并显示在游戏聊天中  
✅ **游戏消息发送** - 通过 `#` 前缀将游戏消息发送到 QQ 群  
✅ **玩家进出提示** - 显示其他玩家的进服/退服通知  
✅ **自定义格式** - 可配置消息显示格式和颜色  
✅ **敏感词过滤** - 支持自定义敏感词过滤列表  

## 环境要求

- **Minecraft**: 1.20.1
- **Fabric Loader**: 0.16.0+
- **Fabric API**: 0.92.9+1.20.1
- **Fabric Language Kotlin**: 1.13.11+kotlin.2.3.21

## 安装方法

1. 确保已安装 [Fabric Loader](https://fabricmc.net/use/)
2. 下载并安装以下依赖模组：
   - [Fabric API 0.92.9+1.20.1](https://modrinth.com/mod/fabric-api)
   - [Fabric Language Kotlin 1.13.11+kotlin.2.3.21](https://modrinth.com/mod/fabric-language-kotlin)
3. 将 `penguin-client-fabric-1.0.0.jar` 放入 `.minecraft/mods/` 目录
4. 启动游戏

## 使用说明

### 基础功能

- **发送消息到 QQ**：在聊天框输入 `#你的消息`（默认前缀为 `#`）
- **接收 QQ 消息**：QQ 群消息会自动显示在游戏聊天中，格式为 `§b[QQ]§r 昵称: 消息内容`
- **快捷键**：按 `P` 键打开快速发送提示（可在控制设置中修改）

### 配置文件

首次运行后会在 `.minecraft/config/penguin-client.json` 生成配置文件：

```json
{
  "enabled": true,
  "chatPrefix": "#",
  "showQQMessages": true,
  "showJoinLeave": true,
  "messageFormat": {
    "fromQQ": "§b[QQ]§r {name}: {message}",
    "toQQ": "[游戏] {name}: {message}",
    "joinServer": "§a🟢 {name} 进入服务器§r",
    "leaveServer": "§c🔴 {name} 退出服务器§r"
  },
  "filters": {
    "enableFilter": true,
    "sensitiveWords": []
  }
}
```

#### 配置项说明

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | Boolean | `true` | 是否启用模组 |
| `chatPrefix` | String | `#` | 发送到 QQ 的消息前缀 |
| `showQQMessages` | Boolean | `true` | 是否显示 QQ 消息 |
| `showJoinLeave` | Boolean | `true` | 是否显示玩家进出提示 |
| `messageFormat.fromQQ` | String | `§b[QQ]§r {name}: {message}` | QQ 消息显示格式 |
| `messageFormat.toQQ` | String | `[游戏] {name}: {message}` | 发送到 QQ 的格式 |
| `messageFormat.joinServer` | String | `§a🟢 {name} 进入服务器§r` | 玩家加入提示 |
| `messageFormat.leaveServer` | String | `§c🔴 {name} 退出服务器§r` | 玩家离开提示 |
| `filters.enableFilter` | Boolean | `true` | 是否启用敏感词过滤 |
| `filters.sensitiveWords` | Array | `[]` | 敏感词列表 |

#### 颜色代码

消息格式支持 Minecraft 颜色代码：

- `§0` - 黑色, `§1` - 深蓝, `§2` - 深绿, `§3` - 深青
- `§4` - 深红, `§5` - 紫色, `§6` - 金色, `§7` - 灰色
- `§8` - 深灰, `§9` - 蓝色, `§a` - 绿色, `§b` - 青色
- `§c` - 红色, `§d` - 粉红, `§e` - 黄色, `§f` - 白色
- `§l` - 粗体, `§o` - 斜体, `§n` - 下划线, `§r` - 重置

## 网络通道

模组使用以下自定义网络通道与服务端通信（需要服务端 HuHoBot Fabric 支持）：

- `huhobot:chat_message` - 发送游戏消息到 QQ
- `huhobot:qq_message` - 接收 QQ 消息
- `huhobot:player_join` - 接收玩家加入通知
- `huhobot:player_leave` - 接收玩家离开通知

## 构建项目

```bash
# Windows
gradlew.bat build

# Linux/macOS
./gradlew build
```

构建完成后，模组文件位于 `build/libs/penguin-client-fabric-1.0.0.jar`

## 服务器端要求

此模组需要服务器端安装配套的 **HuHoBot Fabric 插件**，否则无法正常工作。

服务端需要实现以下功能：
1. 监听客户端发送的 `huhobot:chat_message` 数据包
2. 将 QQ 消息通过 `huhobot:qq_message` 发送给客户端
3. 转发玩家进出消息到客户端

## 故障排查

**问题：收不到 QQ 消息**
- 检查服务器是否安装了 HuHoBot Fabric 插件
- 确认配置文件中 `showQQMessages` 为 `true`
- 查看游戏日志是否有连接成功的提示

**问题：发送的消息没有转发到 QQ**
- 确认消息以正确的前缀开头（默认 `#`）
- 检查服务器端 HuHoBot 配置是否正确
- 查看客户端日志是否有发送成功的提示

**问题：模组加载失败**
- 确认已安装 Fabric API 和 Fabric Language Kotlin
- 检查 Minecraft 版本是否为 1.20.1
- 查看崩溃日志并提交 issue

## 开源许可

MIT License

## 相关项目

- [HuHoBotPenguin-LLSE](https://github.com/HuHoBot) - 基岩版服务端插件
- [HuHoBot](https://github.com/HuHoBot) - QQ 机器人核心

## 贡献

欢迎提交 Issue 和 Pull Request！
