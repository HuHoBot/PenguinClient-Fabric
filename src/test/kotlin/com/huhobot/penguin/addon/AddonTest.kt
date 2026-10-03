package com.huhobot.penguin.addon

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * 附属插件注册表回归测试。
 *
 * 关注内置命令保护与卸载回调——插件重载时不叠加注册、关服时确实回调 onDisable。
 */
class AddonManagerTest {

    @BeforeEach
    fun setUp() {
        AddonManager.unloadAll()
    }

    @AfterEach
    fun tearDown() {
        AddonManager.unloadAll()
    }

    private class Dummy(
        override val meta: Addon,
        private val onDisableCalls: MutableList<String>? = null
    ) : AddonProvider, AddonLifecycle {
        override fun onDisable() {
            onDisableCalls?.add(meta.name)
        }
    }

    @Test
    fun `注册后可按名查询`() {
        val ok = AddonManager.register(Addon("demo", "1.0.0", "测试", "me"))
        assertTrue(ok)
        assertEquals(1, AddonManager.size)
        assertTrue("demo" in AddonManager)
        assertEquals("1.0.0", AddonManager.allAddons().first().version)
    }

    @Test
    fun `同名插件二次注册被拒`() {
        assertTrue(AddonManager.register(Addon("dup")))
        assertFalse(AddonManager.register(Addon("dup")))
        assertEquals(1, AddonManager.size)
    }

    @Test
    fun `注册时带上的命令进入命令索引`() {
        AddonManager.register(
            Addon("withcmd"),
            listOf(AddonCommand("你好", "打招呼"), AddonCommand("踢人", "踢", onlyAdmin = true))
        )
        val cmds = AddonManager.commandsOf("withcmd")
        assertEquals(2, cmds.size)
        assertEquals("踢人", cmds.first { it.onlyAdmin }.command)
        assertEquals(2, AddonManager.allCommands().size)
    }

    @Test
    fun `卸载会回调每个插件的 onDisable 并清空注册表`() {
        val calls = mutableListOf<String>()
        for (n in listOf("a", "b")) {
            AddonManager.register(Addon(n))
            AddonManager.trackInstance(Dummy(Addon(n), calls), null, "test")
        }
        assertEquals(2, AddonManager.size)

        AddonManager.unloadAll()

        assertEquals(listOf("a", "b"), calls.sorted())
        assertEquals(0, AddonManager.size)
        assertTrue(AddonManager.allCommands().isEmpty(), "卸载后命令索引应清空")
    }

    @Test
    fun `重复调用 unloadAll 不会二次回调`() {
        val calls = mutableListOf<String>()
        AddonManager.trackInstance(Dummy(Addon("once"), calls), null, "test")
        AddonManager.unloadAll()
        AddonManager.unloadAll()
        assertEquals(1, calls.size)
    }

    @Test
    fun `hasAny 反映是否存在已加载实例`() {
        assertFalse(AddonManager.hasAny)
        AddonManager.trackInstance(Dummy(Addon("x")), null, "test")
        assertTrue(AddonManager.hasAny)
    }
}

/**
 * 附属插件 jar 入口发现测试。
 *
 * 用真实的临时 jar 走完整加载路径，覆盖：
 * - penguin-addon.json 的 mainClass 正常路径
 * - 缺 json 时回退全量扫描
 * - 坏 jar 不影响其他插件
 */
class AddonLoaderDiscoveryTest {

    private var tmpDir: File? = null
    private val loaders = mutableListOf<ClassLoader>()

    @AfterEach
    fun tearDown() {
        AddonManager.unloadAll()
        loaders.forEach { runCatching { (it as? java.io.Closeable)?.close() } }
        loaders.clear()
        tmpDir?.deleteRecursively()
    }

    @Test
    fun `按 penguin-addon json 的 mainClass 加载`() {
        val jar = writeJar("declared.jar", mapOf("penguin-addon.json" to """{"mainClass":"$ENTRY_CLASS"}"""))
        val found = AddonLoader.findAddonClass(loaderFor(jar), jar)
        assertEquals(ENTRY_CLASS, found?.name)
    }

    @Test
    fun `json 缺失时全量扫描找出实现 AddonProvider 的类`() {
        val jar = writeJar("scan.jar", emptyMap())
        val found = AddonLoader.findAddonClass(loaderFor(jar), jar)
        assertEquals(ENTRY_CLASS, found?.name, "应通过扫描找到入口类")
    }

    @Test
    fun `json 里 mainClass 不存在时回退扫描`() {
        val jar = writeJar("badpath.jar", mapOf("penguin-addon.json" to """{"mainClass":"com.not.Exist"}"""))
        val found = AddonLoader.findAddonClass(loaderFor(jar), jar)
        assertEquals(ENTRY_CLASS, found?.name, "mainClass 失效应回退到全量扫描")
    }

    @Test
    fun `jar 内无任何 AddonProvider 实现时返回 null`() {
        val jar = writeJar("empty.jar", mapOf("readme.txt" to "nothing here"), withEntryClass = false)
        assertNull(AddonLoader.findAddonClass(loaderFor(jar), jar))
    }

    @Test
    fun `addonDir 指向 config 下的 penguin-addons`() {
        // 纯单测环境里 FabricLoader.getInstance() 未初始化，取不到真实路径时只校验末两级目录名
        val dir = runCatching { AddonLoader.addonDir() }.getOrNull()
        assumeTrue(dir != null, "FabricLoader 未初始化，跳过路径断言")
        assertEquals("penguin-addons", dir!!.name)
        assertEquals("config", dir.parentFile.name)
    }

    /** 每个 jar 一个独立类加载器，与真实加载路径一致。 */
    private fun loaderFor(jar: File): ClassLoader {
        val cl = URLClassLoader(arrayOf(jar.toURI().toURL()), this::class.java.classLoader)
        loaders += cl
        return cl
    }

    // ---- 造 jar ----

    private fun writeJar(
        name: String,
        extra: Map<String, String>,
        withEntryClass: Boolean = true
    ): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "penguin-addon-test-${System.nanoTime()}")
            .apply { mkdirs() }
        tmpDir = dir
        val jar = File(dir, name)
        JarOutputStream(jar.outputStream().buffered()).use { jos ->
            for ((path, content) in extra) {
                jos.putNextEntry(JarEntry(path))
                jos.write(content.toByteArray(Charsets.UTF_8))
                jos.closeEntry()
            }
            if (withEntryClass) {
                jos.putNextEntry(JarEntry(ENTRY_CLASS.replace('.', '/') + ".class"))
                jos.write(locateEntryClassFile().readBytes())
                jos.closeEntry()
            }
        }
        return jar
    }

    /** 取测试用的入口类 class 文件（由本测试的顶层类提供）。 */
    private fun locateEntryClassFile(): File {
        val resource = "/$ENTRY_CLASS_PATH"
        val stream = AddonLoaderDiscoveryTest::class.java.getResourceAsStream(resource)
            ?: error("找不到测试类字节码 $resource，请先 compileTestKotlin")
        val dir = File(System.getProperty("java.io.tmpdir"), "penguin-addon-classes")
        dir.mkdirs()
        val out = File(dir, "TestEntryAddon.class")
        out.outputStream().use { os -> stream.use { it.copyTo(os) } }
        return out
    }

    companion object {
        const val ENTRY_CLASS = "com.huhobot.penguin.addon.TestEntryAddon"
        private const val ENTRY_CLASS_PATH = "com/huhobot/penguin/addon/TestEntryAddon.class"
    }
}

/** 供 AddonLoaderDiscoveryTest 打包用的最小插件实现。 */
class TestEntryAddon : AddonProvider {
    override val meta = Addon("test-entry", "1.0.0", "测试入口", "test")
}
