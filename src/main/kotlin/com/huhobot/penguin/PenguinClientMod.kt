package com.huhobot.penguin

import com.huhobot.penguin.config.PenguinConfig
import com.huhobot.penguin.network.MessageHandler
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

object PenguinClientMod : ClientModInitializer {
    const val MOD_ID = "penguin-client-fabric"
    const val MOD_NAME = "PenguinClient-Fabric"

    val logger = LoggerFactory.getLogger(MOD_NAME)
    val config = PenguinConfig()
    val messageHandler = MessageHandler()

    // 快捷键：快速发送 QQ 消息
    private lateinit var quickSendKey: KeyBinding

    override fun onInitializeClient() {
        logger.info("$MOD_NAME 正在初始化...")

        // 加载配置
        config.load()

        // 注册快捷键
        quickSendKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.penguin.quick_send",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                "category.penguin.general"
            )
        )

        // 注册网络事件
        ClientPlayConnectionEvents.JOIN.register { handler, sender, client ->
            messageHandler.onJoinServer(client)
        }

        ClientPlayConnectionEvents.DISCONNECT.register { handler, client ->
            messageHandler.onDisconnect()
        }

        // 注册客户端 Tick 事件
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // 检查快捷键
            while (quickSendKey.wasPressed()) {
                messageHandler.openQuickSendScreen(client)
            }
        }

        logger.info("$MOD_NAME 初始化完成！")
    }
}
