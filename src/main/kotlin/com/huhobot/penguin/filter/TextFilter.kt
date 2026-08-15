package com.huhobot.penguin.filter

import com.huhobot.penguin.config.PenguinConfig
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File
import java.io.OutputStreamWriter
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

private val logger = LoggerFactory.getLogger("PenguinServer-Fabric/Filter")

private val DEFAULT_WORDS = listOf("傻逼", "操你", "色情", "反动", "赌博")

/**
 * 文字过滤，对齐 BDS 版 filter.js：
 * 正则替换 → 敏感词替换 → 可选 OpenAI 兼容二审。
 */
object TextFilter {

    private fun escapeRegex(word: String): String {
        val metaChars = """\.^$*+?()[]{}|"""
        val sb = StringBuilder()
        for (c in word) {
            if (c in metaChars) sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    private fun sensitiveWordsDir(): File {
        val gameDir = FabricLoader.getInstance().gameDir.toFile()
        return File(gameDir, "config/penguin-sensitive-words")
    }

    private fun loadSensitiveWords(): List<String> {
        val dir = sensitiveWordsDir()
        if (!dir.exists()) return emptyList()
        val words = mutableListOf<String>()
        dir.listFiles { f -> f.extension == "txt" }?.forEach { file ->
            try {
                file.readLines(Charsets.UTF_8)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .forEach { words.add(it) }
            } catch (e: Exception) {
                logger.warn("敏感词文件读取失败：${file.name}：${e.message}")
            }
        }
        return words
    }

    private fun filterByRegex(text: String, patterns: List<String>): String {
        var out = text
        for (pattern in patterns) {
            try {
                out = out.replace(Regex(pattern, RegexOption.IGNORE_CASE), "*")
            } catch (e: Exception) {
                // 忽略非法正则
            }
        }
        return out
    }

    private fun replaceWords(text: String, words: List<String>): String {
        var out = text
        for (word in words) {
            if (word.isEmpty()) continue
            out = out.replace(Regex(escapeRegex(word), RegexOption.IGNORE_CASE), "*".repeat(word.length))
        }
        return out
    }

    private fun aiReview(value: String, baseUrl: String, apiKey: String, model: String): String? {
        return try {
            val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
            val body = """{"model":"$model","messages":[{"role":"system","content":"你是敏感词二审工具，只输出替换敏感内容后的完整原文。"},{"role":"user","content":${escapeJson(value)}}],"temperature":0.1}"""
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpsURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
            }
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            val respCode = conn.responseCode
            if (respCode != 200) return null
            val resp = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            // 简单解析 choices[0].message.content
            val contentMatch = Regex(""""content"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(resp)
            contentMatch?.groupValues?.get(1)?.let { unescapeJson(it) }
        } catch (e: Exception) {
            logger.warn("AI 二审失败，回退本地结果：${e.message}")
            null
        }
    }

    private fun escapeJson(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }

    private fun unescapeJson(s: String): String =
        s.replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")

    /**
     * 整体过滤入口（异步，在调用线程执行 HTTP；完成后回调 callback）。
     * 对齐 BDS 版 audit()：正则 → 敏感词首检 → 命中且配齐时 AI 二审全量重写。
     */
    fun audit(value: String, cfg: PenguinConfig, callback: (String) -> Unit) {
        val words = (DEFAULT_WORDS + loadSensitiveWords()).distinct()
        val local = replaceWords(filterByRegex(value, cfg.filterRegex), words)

        val baseUrl = cfg.auditBaseUrl
        val apiKey = cfg.auditApiKey
        if (local == value || baseUrl.isEmpty() || apiKey.isEmpty()) {
            callback(local)
            return
        }

        // AI 二审在独立线程运行，完成后回调
        Thread {
            val result = aiReview(value, baseUrl, apiKey, cfg.auditModel)
            callback(if (!result.isNullOrBlank()) result.trim() else local)
        }.also { it.isDaemon = true }.start()
    }
}
