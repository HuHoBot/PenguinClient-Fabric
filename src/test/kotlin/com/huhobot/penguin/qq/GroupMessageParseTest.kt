package com.huhobot.penguin.qq

import com.huhobot.penguin.config.PenguinConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 群消息 Dispatch 的解析行为。
 *
 * 用反射直接调私有的 [QQClient.onGroupMessage]，避免为了测一个解析函数
 * 去起真实 websocket。桩数据一律照QQ 实测响应写。
 */
class GroupMessageParseTest {

    private fun client(): Pair<QQClient, MutableList<GroupMessage>> {
        val cli = QQClient(testConfig())
        val received = mutableListOf<GroupMessage>()
        cli.onGroupMessage { received += it }
        return cli to received
    }

    private fun testConfig(extra: Map<String, Any?> = emptyMap()): PenguinConfig {
        // Kotlin 2.4 会为默认值合成额外的构造器，不能直接取 first()
        val ctor = PenguinConfig::class.java.declaredConstructors
            .first { it.parameterCount == 1 && it.parameterTypes[0] == Map::class.java }
        ctor.isAccessible = true
        val flat = mutableMapOf<String, Any?>(
            "bot.app-id" to "test-app-id",
            "bot.secret" to "test-secret",
            "bot.name" to "TestBot",
            "bot.groups" to emptyList<String>(),
            "debug.log-events" to false
        )
        flat.putAll(extra)
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(flat) as PenguinConfig
    }

    private fun invoke(cli: QQClient, payload: Map<String, Any?>) {
        val m = cli.javaClass.getDeclaredMethod("onGroupMessage", Map::class.java)
        m.isAccessible = true
        m.invoke(cli, payload)
    }

    private fun msg(
        id: String,
        content: String,
        bot: Boolean = false,
        userId: String = "U1"
    ): Map<String, Any?> = mapOf(
        "id" to id,
        "group_openid" to "G1",
        "content" to content,
        "timestamp" to "2026-10-03T23:46:28+08:00",
        "author" to mapOf(
            "id" to userId,
            "username" to "某人",
            "member_role" to "member",
            "bot" to bot
        )
    )

    @Test
    fun `普通群消息应解析并回调`() {
        val (cli, got) = client()
        invoke(cli, msg("m1", "查信息"))
        assertEquals(1, got.size)
        assertEquals("查信息", got[0].content)
        assertEquals("U1", got[0].userId)
        assertEquals("某人", got[0].username)
        assertEquals("member", got[0].memberRole)
    }

    /**
     * 别的机器人发的消息（实测腾讯 Q群管家的入群欢迎语就是这样）也会推过来。
     * 不排除会把它当用户消息转发进游戏，还可能撞上命令关键词。
     */
    @Test
    fun `其他机器人消息应被丢弃不进回调`() {
        val (cli, got) = client()
        invoke(cli, msg("b1", "欢迎来到本群", bot = true))
        assertEquals(0, got.size, "author.bot=true 的消息不应进入业务回调")
    }

    /** 去重窗口内同 id 只处理一次（GROUP_AT 与 GROUP_MESSAGE 会重复推）。 */
    @Test
    fun `相同 id 只处理一次`() {
        val (cli, got) = client()
        invoke(cli, msg("dup", "查在线"))
        invoke(cli, msg("dup", "查在线"))
        assertEquals(1, got.size)
    }

    @Test
    fun `缺 id 或 group_openid 时丢弃且不崩`() {
        val (cli, got) = client()
        invoke(cli, mapOf("group_openid" to "G1", "content" to "x"))
        invoke(cli, mapOf("id" to "x1", "content" to "x"))
        assertEquals(0, got.size)
    }
}