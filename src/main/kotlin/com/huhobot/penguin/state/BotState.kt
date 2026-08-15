package com.huhobot.penguin.state

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.huhobot.penguin.config.PenguinConfig
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("PenguinServer-Fabric/State")
private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

const val MODE_QQ = "QQ"
const val MODE_MANUAL = "MANUAL"
const val MODE_BOTH = "BOTH"

fun mapConfigMode(value: String): String = when (value.lowercase()) {
    "qq" -> MODE_QQ
    "config", "manual" -> MODE_MANUAL
    else -> MODE_BOTH
}

/**
 * 分群状态持久化，对齐 BDS 版 state.js：
 * 手动管理员 / 认证用户 / 管理方式 / 全量转发 / 白名单绑定。
 * 变更即写盘（tmp + rename 原子写）。
 */
class BotState(private val cfg: PenguinConfig) {

    private val file: File = run {
        val dir = FabricLoader.getInstance().configDir.toFile()
        File(dir, "penguin-server-state.json")
    }

    private var data: StateData = StateData()

    init { load() }

    private fun load() {
        if (!file.exists()) return
        try {
            val type = object : TypeToken<StateData>() {}.type
            data = gson.fromJson(file.readText(), type) ?: StateData()
        } catch (e: Exception) {
            logger.warn("状态文件读取失败，使用空状态：${e.message}")
        }
    }

    private fun save() {
        val tmp = File(file.parent, file.name + ".tmp")
        try {
            tmp.writeText(gson.toJson(data) + "\n")
            tmp.renameTo(file)
        } catch (e: Exception) {
            logger.error("状态写入失败：${e.message}")
        }
    }

    // ---- 手动管理员 ----

    fun listAdmins(group: String): List<String> =
        data.administrators[group] ?: emptyList()

    fun containsManualAdmin(group: String, openid: String): Boolean =
        data.administrators[group]?.contains(openid) == true

    fun addAdmin(group: String, openid: String) {
        val list = data.administrators.getOrPut(group) { mutableListOf() }
        if (!list.contains(openid)) { list.add(openid); save() }
    }

    fun removeAdmin(group: String, openid: String) {
        val list = data.administrators[group] ?: return
        if (list.remove(openid)) save()
    }

    fun isManualAdmin(group: String, openid: String): Boolean {
        if (cfg.adminOpenids.contains(openid)) return true
        return containsManualAdmin(group, openid)
    }

    // ---- 认证用户 ----

    fun isAuthenticated(group: String, openid: String): Boolean =
        data.authenticatedUsers[group]?.contains(openid) == true

    fun authenticate(group: String, openid: String) {
        val list = data.authenticatedUsers.getOrPut(group) { mutableListOf() }
        if (!list.contains(openid)) { list.add(openid); save() }
    }

    fun revoke(group: String, openid: String) {
        val list = data.authenticatedUsers[group] ?: return
        if (list.remove(openid)) save()
    }

    // ---- 管理方式 ----

    fun mode(group: String): String {
        val stored = data.administratorModes[group]
        if (stored == MODE_QQ || stored == MODE_MANUAL || stored == MODE_BOTH) return stored!!
        return mapConfigMode(cfg.adminMode)
    }

    fun setMode(group: String, mode: String) {
        data.administratorModes[group] = mode; save()
    }

    fun isAdmin(group: String, openid: String, role: String?): Boolean {
        val m = mode(group)
        val roleOk = role == "owner" || role == "admin"
        val manualOk = isManualAdmin(group, openid)
        return when (m) {
            MODE_QQ -> roleOk
            MODE_MANUAL -> manualOk
            else -> roleOk || manualOk
        }
    }

    // ---- 全量转发 ----

    fun isFullForwarding(group: String): Boolean {
        val v = data.fullForwarding[group]
        return v ?: cfg.featureFullAmount
    }

    fun setFullForwarding(group: String, enabled: Boolean) {
        data.fullForwarding[group] = enabled; save()
    }

    // ---- 白名单绑定 ----

    fun getBindingName(group: String, openid: String): String? =
        data.bindings[group]?.get(openid)

    fun bindName(group: String, openid: String, gameName: String): String {
        data.bindings.getOrPut(group) { mutableMapOf() }[openid] = gameName
        save()
        return gameName
    }

    fun unbindOpenid(group: String, openid: String): String? {
        val groupMap = data.bindings[group] ?: return null
        val name = groupMap.remove(openid) ?: return null
        save()
        return name
    }

    fun findOpenidByGameName(group: String, gameName: String): String? {
        val groupMap = data.bindings[group] ?: return null
        val key = gameName.lowercase()
        return groupMap.entries.firstOrNull { it.value.lowercase() == key }?.key
    }

    // ---- 数据结构 ----

    private data class StateData(
        val administrators: MutableMap<String, MutableList<String>> = mutableMapOf(),
        val authenticatedUsers: MutableMap<String, MutableList<String>> = mutableMapOf(),
        val administratorModes: MutableMap<String, String> = mutableMapOf(),
        val fullForwarding: MutableMap<String, Boolean> = mutableMapOf(),
        val bindings: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
    )
}
