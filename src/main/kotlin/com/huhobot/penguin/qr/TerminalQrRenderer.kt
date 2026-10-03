package com.huhobot.penguin.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

/**
 * 把扫码 URL 渲染成终端可以直接显示的半块字符二维码。
 *
 * 采用「深色终端」配色：二维码中浅色（白色）模块用前景色字符绘制，
 * 深色模块留空显示终端背景色，与手机 QQ 扫码所需的黑白对比一致。
 *
 * 渲染失败（例如 URL 过长）时返回 null，调用方应退化为直接输出链接。
 */
object TerminalQrRenderer {

    fun render(url: String): String? {
        if (url.isBlank()) return null
        return try {
            val matrix = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0)
            buildString(matrix)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildString(matrix: BitMatrix): String {
        val builder = StringBuilder((matrix.width + 1) * (matrix.height / 2 + 1))
        var y = 0
        while (y < matrix.height) {
            for (x in 0 until matrix.width) {
                // ZXing 的 matrix 中 true 表示深色模块
                val topLight = !matrix.get(x, y)
                val bottomLight = y + 1 < matrix.height && !matrix.get(x, y + 1)
                builder.append(
                    when {
                        topLight && bottomLight -> '█'
                        topLight -> '▀'
                        bottomLight -> '▄'
                        else -> ' '
                    }
                )
            }
            builder.append('\n')
            y += 2
        }
        return builder.toString()
    }
}
