package com.huhobot.penguin.addon

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private val logger = LoggerFactory.getLogger("PenguinServer-Fabric/Addons")

/**
 * 附属插件注册中心，对齐主仓库 `AddonManager` 的契约：
 * register / allAddons / commandsOf / addCommand / contains / size。
 *
 * 额外保存实例与 ClassLoader，供 reload 与关服时卸载。
 */
object AddonManager {

    private val addons = ConcurrentHashMap<String, Addon>()
    private val addonCommands = ConcurrentHashMap<String, MutableList<AddonCommand>>()

    /** addon 实例 → 加载它的 ClassLoader，用于卸载时关闭。 */
    private val instances = CopyOnWriteArrayList<LoadedAddon>()

    data class LoadedAddon(
        val addon: AddonProvider,
        val classLoader: ClassLoader?,
        val source: String
    )

    /** 已注册的附属插件数量。 */
    val size: Int get() = addons.size

    /** 是否已加载过任何附属插件。 */
    val hasAny: Boolean get() = instances.isNotEmpty()

    /**
     * 注册一个附属插件。
     *
     * @param addon    插件元数据
     * @param commands 该插件提供的命令列表
     * @return false 表示同名插件已注册，调用方应跳过该插件
     */
    fun register(addon: Addon, commands: List<AddonCommand> = emptyList()): Boolean {
        if (addon.name.isBlank()) {
            logger.warn("忽略缺少 name 的附属插件注册")
            return false
        }
        if (addons.containsKey(addon.name)) {
            logger.warn("附属插件 ${addon.name} 已注册，跳过重复注册")
            return false
        }
        addons[addon.name] = addon
        if (commands.isNotEmpty()) {
            addonCommands.getOrPut(addon.name) { CopyOnWriteArrayList() }.addAll(commands)
        }
        logger.info("已注册附属插件：${addon.name} v${addon.version}")
        return true
    }

    /** 登记已实例化的插件，供卸载阶段使用。 */
    fun trackInstance(addon: AddonProvider, classLoader: ClassLoader?, source: String) {
        instances.add(LoadedAddon(addon, classLoader, source))
    }

    /** 获取所有已注册附属插件（按名称排序）。 */
    fun allAddons(): List<Addon> = addons.values.sortedBy { it.name }

    /** 获取指定附属插件的命令列表。 */
    fun commandsOf(addonName: String): List<AddonCommand> =
        addonCommands[addonName]?.toList().orEmpty()

    /** 汇总所有附属插件的命令，供指令面板同步使用。 */
    fun allCommands(): List<AddonCommand> =
        addonCommands.values.flatten().sortedBy { it.command }

    /** 向已注册附属插件追加命令（插件未注册则忽略；按 command 去重）。 */
    fun addCommand(addonName: String, command: AddonCommand) {
        if (!addons.containsKey(addonName)) return
        val list = addonCommands.getOrPut(addonName) { CopyOnWriteArrayList() }
        if (list.none { it.command == command.command }) {
            list.add(command)
        }
    }

    /** 检查附属插件是否已注册（支持 `name in AddonManager` 语法）。 */
    operator fun contains(name: String): Boolean = addons.containsKey(name)

    /**
     * 卸载全部附属插件：先回调 onDisable，再关闭各自的 ClassLoader。
     * 单个插件卸载失败不影响其它插件。
     */
    fun unloadAll() {
        val loaded = instances.toList()
        instances.clear()
        for (item in loaded.reversed()) {
            try {
                (item.addon as? AddonLifecycle)?.onDisable()
            } catch (e: Throwable) {
                logger.error("附属插件 ${item.addon.meta.name} onDisable 异常：${e.message}")
            }
            try {
                // URLClassLoader 实现了 Closeable，但 ClassLoader 类型上没有 close()
                (item.classLoader as? java.io.Closeable)?.close()
            } catch (e: Throwable) {
                logger.warn("关闭 ${item.source} 的 ClassLoader 失败：${e.message}")
            }
        }
        addons.clear()
        addonCommands.clear()
        if (loaded.isNotEmpty()) {
            logger.info("已卸载 ${loaded.size} 个附属插件")
        }
    }
}