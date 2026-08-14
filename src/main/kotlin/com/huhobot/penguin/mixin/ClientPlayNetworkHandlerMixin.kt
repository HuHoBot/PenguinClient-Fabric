package com.huhobot.penguin.mixin

import com.huhobot.penguin.PenguinClientMod
import net.minecraft.client.network.ClientPlayNetworkHandler
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Mixin：拦截游戏消息包，处理聊天消息转发
 */
@Mixin(ClientPlayNetworkHandler::class)
class ClientPlayNetworkHandlerMixin {

    @Inject(
        method = ["onGameMessage"],
        at = [At("HEAD")]
    )
    private fun onGameMessage(packet: GameMessageS2CPacket, info: CallbackInfo) {
        // 这里可以拦截服务器发来的游戏消息
        // 用于识别 QQ 消息格式并特殊处理
        try {
            val content = packet.content()
            val message = content.string

            // 检查是否是 QQ 消息格式
            if (message.contains("[QQ]") || message.contains("🟢") || message.contains("🔴")) {
                PenguinClientMod.logger.debug("收到 HuHoBot 消息: $message")
            }
        } catch (e: Exception) {
            // 静默处理异常，避免影响正常游戏
        }
    }
}
