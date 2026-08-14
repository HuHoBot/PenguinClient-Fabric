package com.huhobot.penguin.network

import com.huhobot.penguin.PenguinClientMod
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.minecraft.client.MinecraftClient
import net.minecraft.network.PacketByteBuf
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/**
 * 消息处理器 - 处理与服务器端 HuHoBot 的通信
 */
class MessageHandler {
    companion object {
        // 自定义网络通道 ID（需要与服务端一致）
        val CHAT_MESSAGE_CHANNEL = Identifier("huhobot", "chat_message")
        val QQ_MESSAGE_CHANNEL = Identifier("huhobot", "qq_message")
        val PLAYER_JOIN_CHANNEL = Identifier("huhobot", "player_join")
        val PLAYER_LEAVE_CHANNEL = Identifier("huhobot", "player_leave")
    }

    private var isConnected = false

    /**
     * 玩家加入服务器时调用
     */
    fun onJoinServer(client: MinecraftClient) {
        isConnected = true
        PenguinClientMod.logger.info("已连接到服务器，开始监听 HuHoBot 消息")

        // 注册接收 QQ 消息的监听器
        ClientPlayNetworking.registerGlobalReceiver(QQ_MESSAGE_CHANNEL) { client, handler, buf, responseSender ->
            handleQQMessage(client, buf)
        }

        // 注册接收玩家加入消息
        ClientPlayNetworking.registerGlobalReceiver(PLAYER_JOIN_CHANNEL) { client, handler, buf, responseSender ->
            handlePlayerJoin(client, buf)
        }

        // 注册接收玩家离开消息
        ClientPlayNetworking.registerGlobalReceiver(PLAYER_LEAVE_CHANNEL) { client, handler, buf, responseSender ->
            handlePlayerLeave(client, buf)
        }
    }

    /**
     * 断开连接时调用
     */
    fun onDisconnect() {
        isConnected = false
        PenguinClientMod.logger.info("已断开与服务器的连接")
    }

    /**
     * 发送游戏聊天消息到 QQ（通过服务端转发）
     */
    fun sendChatToQQ(message: String) {
        if (!isConnected) {
            PenguinClientMod.logger.warn("未连接到服务器，无法发送消息")
            return
        }

        if (!message.startsWith(PenguinClientMod.config.chatPrefix)) {
            return
        }

        try {
            val buf = PacketByteBufs.create()
            buf.writeString(message)
            ClientPlayNetworking.send(CHAT_MESSAGE_CHANNEL, buf)
            PenguinClientMod.logger.debug("已发送消息到服务端: $message")
        } catch (e: Exception) {
            PenguinClientMod.logger.error("发送消息失败", e)
        }
    }

    /**
     * 处理从 QQ 接收的消息
     */
    private fun handleQQMessage(client: MinecraftClient, buf: PacketByteBuf) {
        if (!PenguinClientMod.config.showQQMessages) {
            return
        }

        try {
            val playerName = buf.readString()
            val message = buf.readString()

            val formatted = PenguinClientMod.config.messageFormat.fromQQ
                .replace("{name}", playerName)
                .replace("{message}", message)

            client.execute {
                client.player?.sendMessage(Text.literal(formatted), false)
            }
        } catch (e: Exception) {
            PenguinClientMod.logger.error("处理 QQ 消息失败", e)
        }
    }

    /**
     * 处理玩家加入消息
     */
    private fun handlePlayerJoin(client: MinecraftClient, buf: PacketByteBuf) {
        if (!PenguinClientMod.config.showJoinLeave) {
            return
        }

        try {
            val playerName = buf.readString()

            val formatted = PenguinClientMod.config.messageFormat.joinServer
                .replace("{name}", playerName)

            client.execute {
                client.player?.sendMessage(Text.literal(formatted), false)
            }
        } catch (e: Exception) {
            PenguinClientMod.logger.error("处理玩家加入消息失败", e)
        }
    }

    /**
     * 处理玩家离开消息
     */
    private fun handlePlayerLeave(client: MinecraftClient, buf: PacketByteBuf) {
        if (!PenguinClientMod.config.showJoinLeave) {
            return
        }

        try {
            val playerName = buf.readString()

            val formatted = PenguinClientMod.config.messageFormat.leaveServer
                .replace("{name}", playerName)

            client.execute {
                client.player?.sendMessage(Text.literal(formatted), false)
            }
        } catch (e: Exception) {
            PenguinClientMod.logger.error("处理玩家离开消息失败", e)
        }
    }

    /**
     * 打开快速发送界面（暂时使用聊天框）
     */
    fun openQuickSendScreen(client: MinecraftClient) {
        client.execute {
            client.setScreen(null)
            client.player?.sendMessage(
                Text.literal("§e提示: 在聊天框输入 '${PenguinClientMod.config.chatPrefix}你的消息' 发送到 QQ 群"),
                false
            )
        }
    }
}
