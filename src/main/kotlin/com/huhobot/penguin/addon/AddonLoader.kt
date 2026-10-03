package com.huhobot.penguin.addon

import com.huhobot.penguin.PenguinServerMod
import com.huhobot.penguin.command.CommandHandler
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarFile

private val logger = LoggerFactory.getLogger("PenguinServer-Fabric/AddonLoader")

/**
 * 附属插件加载器：扫描 `config/penguin-addons` 下的 jar，逐个隔离加载。
 *
 * 每个 jar 用独立的 URLClassLoader，父加载器取本class（Knot），
 * 这样附属插件能拿到 AddonProvider 接口，同时插件之间的依赖互相隔离。
 */
object AddonLoader {

    private const val PLUGIN_SUFFIX = ".jar"

    /** 附属插件目录，默认 `config/penguin-addons`。 */
    fun addonDir(): File = File(PenguinServerMod.configDir(), "penguin-addons")

    /** 实现 AddonApi 的宿主能力实现。 */
    private class HostApi(
        private val addonName: String,
        private val register: (
            source: String, name: String, describe: String,
            onlyAdmin: Boolean, handler: (CommandHandler.Ctx) -> Unit
        ) -> Boolean
    ) : AddonApi {

        override val config get() = PenguinServerMod.config

        override fun registerCommand(
            name: String,
            describe: String,
            onlyAdmin: Boolean,
            handler: (CommandHandler.Ctx) -> Unit
        ): Boolean = register(addonName, name, describe, onlyAdmin, handler)

        override fun sendGroupMessage(groupId: String, content: String) {
            PenguinServerMod.qqClient.sendGroupMessage(groupId, content)
        }

        override fun sendTextToAllGroups(content: String) {
            PenguinServerMod.sendTextToAllGroups(content)
        }

        override fun broadcastToGame(message: String) {
            PenguinServerMod.broadcastToGame(message)
        }

        override fun runCommand(command: String): Pair<Boolean, String> =
            PenguinServerMod.runCommand(command)

        override fun isAdmin(groupId: String, userId: String, memberRole: String?): Boolean =
            PenguinServerMod.state.isAdmin(groupId, userId, memberRole)

        override fun log(message: String) = logger.info("[$addonName] $message")
        override fun logWarn(message: String) = logger.warn("[$addonName] $message")
        override fun logError(message: String) = logger.error("[$addonName] $message")
    }

    /**
     * 扫描并加载全部附属插件。
     *
     * 加载前会先卸载旧实例，保证 reload 不会叠加注册。
     *
     * @return 成功加载的插件数
     */
    fun loadAll(): Int {
        AddonManager.unloadAll()

        val dir = addonDir()
        if (!dir.isDirectory) {
            logger.info("附属插件目录不存在，跳过加载：${dir.absolutePath}")
            return 0
        }

        val jars = dir.listFiles { f: File -> f.isFile && f.name.endsWith(PLUGIN_SUFFIX) }
            ?.sortedBy { it.name }
            .orEmpty()

        if (jars.isEmpty()) {
            logger.info("附属插件目录为空：${dir.absolutePath}")
            return 0
        }

        var loaded = 0
        for (jar in jars) {
            if (loadOne(jar)) loaded++
        }
        logger.info("附属插件加载完成：${loaded}/${jars.size} 成功")
        return loaded
    }

    /** 加载单个 jar，任何异常都不影响其它插件。 */
    private fun loadOne(jar: File): Boolean {
        var cl: URLClassLoader? = null
        return try {
            cl = URLClassLoader(arrayOf(jar.toURI().toURL()), AddonLoader::class.java.classLoader)

            val addonClass = findAddonClass(cl, jar) ?: run {
                logger.warn("${jar.name} 中未找到实现 AddonProvider 的类，跳过")
                return false
            }

            val instance = addonClass.getDeclaredConstructor().apply {
                isAccessible = true
            }.newInstance() as? AddonProvider ?: run {
                logger.warn("${jar.name} 的 ${addonClass.name} 实例化结果不是 AddonProvider，跳过")
                return false
            }

            val meta = try {
                instance.meta
            } catch (e: Throwable) {
                logger.error("${jar.name} 读取 meta 失败：${e.message}")
                return false
            }

            if (meta.name.isBlank()) {
                logger.warn("${jar.name} 的插件名为空，跳过")
                return false
            }

            // 先占位注册，挡住同名插件；命令注册则通过 api 回调进 CommandHandler
            if (!AddonManager.register(meta)) return false

            val api = HostApi(meta.name) { source, name, describe, onlyAdmin, handler ->
                PenguinServerMod.registerAddonCommand(source, name, describe, onlyAdmin, handler)
            }

            instance.onLoad(api)
            instance.onEnable()

            AddonManager.trackInstance(instance, cl, jar.name)
            logger.info("附属插件 ${meta.name} v${meta.version} 加载成功（${jar.name}）")
            true
        } catch (e: Throwable) {
            logger.error("加载附属插件 ${jar.name} 失败：${e.message}", e)
            try {
                (cl as? java.io.Closeable)?.close()
            } catch (_: Throwable) {
            }
            false
        }
    }

    /**
     * 在 jar 中查找实现 [AddonProvider] 的类。
     *
     * 优先读 `penguin-addon.json` 里声明的 mainClass，找不到再退回全量扫描。
     */
    internal fun findAddonClass(cl: ClassLoader, jar: File): Class<*>? {
        // 注意：声明的 mainClass 加载失败时不能直接返回 null，必须继续往下走全量扫描
        findDeclaredMainClass(cl, jar)?.let { declared ->
            runCatching { cl.loadClass(declared) }.getOrNull()?.let { return it }
            logger.warn("${jar.name} 声明的 mainClass $declared 不存在，回退全量扫描")
        }

        JarFile(jar).use { jf ->
            val candidates = jf.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.contains("module-info") }
                .map { it.name.removeSuffix(".class").replace('/', '.') }
                .toList()

            for (name in candidates) {
                val loaded = runCatching { cl.loadClass(name) }.getOrNull() ?: continue
                if (AddonProvider::class.java.isAssignableFrom(loaded) &&
                    !loaded.isInterface &&
                    runCatching { loaded.getDeclaredConstructor() }.isSuccess
                ) {
                    return loaded
                }
            }
        }
        return null
    }

    /** 读取 `penguin-addon.json` 的 `mainClass` 字段。 */
    private fun findDeclaredMainClass(cl: ClassLoader, jar: File): String? = try {
        JarFile(jar).use { jf ->
            val entry = jf.getEntry("penguin-addon.json") ?: return@use null
            val text = jf.getInputStream(entry).bufferedReader().use { it.readText() }
            Regex("\"mainClass\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        }
    } catch (e: Throwable) {
        logger.debug("读取 ${jar.name} 的 penguin-addon.json 失败：${e.message}")
        null
    }

    /** 供 reload 使用的别名，保持与 PenguinServerMod.reload() 语义一致。 */
    fun reload(): Int = loadAll()
}