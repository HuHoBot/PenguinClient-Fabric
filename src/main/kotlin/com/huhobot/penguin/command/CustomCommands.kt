package com.huhobot.penguin.command

import com.huhobot.penguin.config.PenguinConfig
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("PenguinServer-Fabric/CustomCommands")

/**
 * 自定义命令，对齐 BDS 版 customcommands.js。
 * 占位符：{params} {group} {user} {0}/{1}/... 或 &0/&1/...
 */
fun renderCommand(command: String, params: String, groupId: String, userId: String, prefix: String = ""): String {
    val tokens = params.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    var out = command
    out = out.replace("{params}", params)
    out = out.replace("{group}", groupId)
    out = out.replace("{user}", userId)
    if (prefix.isNotEmpty()) out = out.replace("{prefix}", prefix)
    tokens.forEachIndexed { index, token ->
        out = out.replace("{$index}", token).replace("&$index", token)
    }
    return out
}

data class CustomCommand(val key: String, val command: String, val permission: Int)

class CustomCommands(private val cfg: PenguinConfig) {

    private var list: List<CustomCommand> = emptyList()

    init { reload() }

    fun reload() {
        val newList = mutableListOf<CustomCommand>()
        for (item in cfg.getCustomCommands()) {
            val key = (item["key"] as? String)?.trim() ?: continue
            val command = (item["command"] as? String)?.trim() ?: continue
            if (key.isEmpty() || command.isEmpty()) {
                logger.warn("忽略缺少 key 或 command 的自定义命令配置")
                continue
            }
            val permission = when (val p = item["permission"]) {
                is Number -> p.toInt()
                is String -> p.toIntOrNull() ?: 0
                else -> 0
            }
            newList.add(CustomCommand(key, command, permission))
        }
        list = newList
    }

    fun find(key: String): CustomCommand? = list.firstOrNull { it.key == key.trim() }

    /** 普通用户可执行（permission == 0）。 */
    fun resolveRun(key: String): CustomCommand? {
        val item = find(key) ?: return null
        return if (item.permission <= 0) item else null
    }

    /** 管理员执行：任意权限。 */
    fun resolveAdminRun(key: String): CustomCommand? = find(key)
}
