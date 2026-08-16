# PenguinServer-Fabric

HuHoBot Penguin 的 Fabric 服务端模组，直连 QQ 官方机器人网关，实现 QQ 群与 Minecraft Java 版服务器的双向消息桥接。

## 功能特性
✅ **0封号风险** - 使用QQ官方Bot接口 <br>
✅ **双向消息转发** - 游戏聊天转发到 QQ 群，QQ 群消息广播到游戏内  
✅ **进退服通知** - 玩家进服/退服自动推送到 QQ 群  
✅ **20+ 内置群命令** - 查在线、白名单管理、管理员管理、MOTD查询等  
✅ **MOTD 查询** - `/motd <IP:端口>` 查询任意服务器状态，支持图片展示  
✅ **全量模式优化** - 自动识别图片、语音、表情、视频等多媒体内容  
✅ **白名单自助绑定** - QQ 用户自助绑定游戏名并自动加入白名单  
✅ **管理员系统** - 支持 QQ 群管理员 / 手动管理员 / 双重模式  
✅ **敏感词过滤** - 正则过滤 + 词库过滤，可选 OpenAI 兼容二审  
✅ **自定义命令** - 支持参数占位符的自定义群命令  
✅ **全量转发** - 可按群开启非命令消息广播到游戏  
✅ **中文域名支持** - server-ip 支持中文域名，自动转换为 ASCII  
✅ **直接执行 MC 命令** - `/执行命令` 管理员可直接执行任意服务器命令

## 环境要求

支持以下 Minecraft 版本：

| Minecraft 版本 | JAR 文件 |
|---|---|
| 1.20.1 ~ 1.21.x | `penguin-server-fabric-1.1.2-mc1.20.1.jar`（需 Java 21+） |
| 26.1+ | `penguin-server-fabric-1.1.2-mc26.2.jar`（需 Java 25+） |

- **Fabric Loader**: 0.16.0+
- **Fabric API**: 对应 MC 版本的最新版
- **Fabric Language Kotlin**: 1.13.0+
- **QQ 开放平台机器人**（需提审上线后才能收到群事件）

## 安装方法

1. 确保已安装 [Fabric Loader](https://fabricmc.net/use/)
2. 下载并安装以下依赖模组（对应你的 MC 版本）：
   - [Fabric API](https://modrinth.com/mod/fabric-api)
   - [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
3. 将对应版本的 jar 放入服务器 `mods/` 目录
4. 启动服务器，生成配置文件后关闭
5. 编辑 `config/penguin-server.json`，填入机器人凭据
6. 重新启动服务器

## 配置文件

首次启动后自动生成 `config/penguin-server.json`：

```json
{
  "bot": {
    "app-id": "你的 AppID",
    "secret": "你的 Secret",
    "name": "HuHoBot",
    "groups": ["群的 group_openid"]
  },
  "serverName": "我的服务器",
  "chat-format": {
    "from-game": "[游戏] {name}: {message}",
    "from-group": "[QQ] {name}: {message}",
    "post-chat": true,
    "start-with": ""
  },
  "whitelist": {
    "add-command": "whitelist add {name}",
    "del-command": "whitelist remove {name}"
  },
  "join-leave": {
    "enabled": true,
    "join-format": "[{server}] 🟢{name}进入服务器",
    "leave-format": "[{server}] 🔴{name}退出服务器"
  },
  "motd": {
    "server-ip": "你的服务器 IP 或域名",
    "server-port": 25565,
    "api": "https://motd.txssb.cn/api/status_img?theme=simple&ip={ip}&port={port}&dark=true&lang=zh-CN",
    "text": "[{server}] 在线玩家：{online}\n{players}",
    "post-img": false,
    "use-markdown": false
  },
  "admin": {
    "mode": "both",
    "openids": []
  },
  "audit": {
    "base-url": "",
    "api-key": "",
    "model": "gpt-4o-mini"
  }
}
```

### 主要配置项

| 配置项 | 说明 |
|--------|------|
| `bot.app-id` | QQ 开放平台机器人 AppID |
| `bot.secret` | QQ 开放平台机器人 Secret |
| `bot.groups` | 监听的 QQ 群 group_openid 列表（空 = 所有群） |
| `serverName` | 服务器名称（用于进退服消息） |
| `chat-format.start-with` | 游戏消息转发到 QQ 所需的前缀（空 = 全部转发） |
| `motd.server-ip` | 服务器 IP 或域名，支持中文域名 |
| `motd.post-img` | `/查在线` 是否发送 MOTD 图片（true/false） |
| `motd.api` | MOTD 图片 API 地址，支持 `{ip}` `{port}` 占位符 |
| `motd.use-markdown` | `/查在线` 是否使用 Markdown 格式（true/false） |
| `admin.mode` | 管理员模式：`qq`/`manual`/`both` |
| `audit.base-url` | OpenAI 兼容接口地址（留空则不启用 AI 二审） |

### MOTD 图片配置

`/查在线` 命令支持四种显示模式（优先级从高到低）：

1. **Markdown + 图片模式**（`use-markdown = true` 且 `post-img = true`）：推荐，Markdown 卡片内嵌 MOTD 图片
2. **纯图片模式**（`use-markdown = false` 且 `post-img = true`）：文本 + MOTD 图片
3. **纯 Markdown 模式**（`use-markdown = true` 且 `post-img = false`）：仅 Markdown 格式
4. **纯文本模式**（两者都为 false）：显示原始 `list` 命令输出

**推荐 API（默认）：**
```
https://motd.txssb.cn/api/status_img?theme=simple&ip={ip}&port={port}&dark=true&lang=zh-CN
```
- `theme=simple` 简洁主题
- `dark=true` 深色模式，`dark=false` 浅色模式
- 详细文档：https://motd.txssb.cn/docs

**注意**：简幻欢（Simpfun）服务器可能无法使用 MOTD 图片查询。

## 内置群命令

@机器人 发送以下命令：

| 命令 | 权限 | 说明 |
|------|------|------|
| `查在线` | 所有人 | 查看在线玩家列表 |
| `MOTD` | 所有人 | 查看指定服务器的MOTD |
| `在线服务器` | 所有人 | 查看服务器是否在线 |
| `查信息` | 所有人 | 查看自己的 OpenID 和认证状态 |
| `发消息 <内容>` | 所有人 | 广播消息到游戏 |
| `绑定白名单 <游戏名>` | 所有人 | 自助绑定并加入白名单 |
| `解除绑定` | 所有人 | 解除绑定并移出白名单 |
| `认证` | 所有人 | 查看/申请认证状态 |
| `添加白名单 <玩家名>` | 管理员 | 直接添加白名单 |
| `删除白名单 <玩家名>` | 管理员 | 移除白名单 |
| `查白名单` | 管理员 | 查看白名单列表 |
| `解绑白名单 <玩家名>` | 管理员 | 解绑指定玩家并移出白名单 |
| `加管理 <OpenID>` | 管理员 | 添加手动管理员 |
| `删管理 <OpenID>` | 管理员 | 删除手动管理员 |
| `查管理` | 管理员 | 查看管理员列表 |
| `管理方式 <QQ/手动/双重>` | 管理员 | 设置管理员认定方式 |
| `全量 <开/关>` | 管理员 | 开关全量消息转发到游戏 |
| `执行命令 <MC命令>` | 管理员 | 直接执行任意服务器命令（如 `执行命令 list`） |
| `认证 <OpenID>` | 管理员 | 认证指定用户 |
| `解除认证` | 所有人 | 解除自己的认证 |

## 服务端命令

需要 OP 权限（权限等级 4）：

```
/penguin reload        重载配置并重启 QQ 网关
/penguin info          查看模组状态
/penguin send <消息>   手动向所有配置的群发送消息
```

## 自定义命令

在配置文件 `custom-commands` 中配置：

```json
"custom-commands": [
  {
    "key": "天气",
    "command": "weather clear",
    "permission": 0
  },
  {
    "key": "踢人",
    "command": "kick {0}",
    "permission": 1
  }
]
```

占位符：`{params}` 全部参数、`{0}` `{1}` 第 N 个参数、`{group}` 群 OpenID、`{user}` 用户 OpenID

## 构建

```bash
./gradlew build
```

## 相关项目

- [PenguinBDSClient](https://github.com/Beeeee-really/PenguinBDSClient) - 基岩版（BDS/LeviLamina）版本

## 开源许可

AGPL3.0 License
