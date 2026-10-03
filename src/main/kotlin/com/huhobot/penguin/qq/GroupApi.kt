package com.huhobot.penguin.qq

import com.huhobot.penguin.config.PenguinConfig
import org.slf4j.LoggerFactory
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val logger = LoggerFactory.getLogger("PenguinServer-Fabric/GroupApi")

/** 群基本信息。 */
data class GroupInfo(
    val groupName: String?,
    val memberCount: Int,
    val maxMember: Int
)

/** 机器人在本群的状态。 */
data class GroupBotState(
    val joined: Boolean,
    val mutedInGroup: Boolean
)

/** 群成员。memberRole 为 owner/admin/member。 */
data class GroupMember(
    val memberOpenid: String,
    val username: String?,
    val memberRole: String?,
    val joinTime: String?,
    val msgCount: Int?
)

/** 入群申请。 */
data class JoinRequest(
    val requestId: String,
    val memberOpenid: String,
    val username: String?,
    val applyAt: String?,
    val riskTips: String?,
    val invitedBy: String?,
    val applySource: String?
)

/**
 * QQ 群管理 REST 客户端，路径与语义对齐 qqpd-bot-java 的 `GroupBaseV2`。
 *
 * 所有方法都是阻塞的，必须在后台线程调用；失败统一抛 [GroupApiException]，
 * 消息里带 HTTP 状态码与 QQ 返回体，方便直接在群里回显给管理员。
 */
class GroupApi(
    private val cfg: PenguinConfig,
    private val tokenProvider: () -> String,
    private val apiBase: String = "https://api.bot.qq.com"
) {

    class GroupApiException(message: String) : Exception(message)

    // ---- 群基本信息 ----

    fun getGroupInfo(groupOpenid: String): GroupInfo {
        val d = get("/v2/groups/$groupOpenid/info")
        return GroupInfo(
            groupName = d["group_name"] as? String,
            memberCount = (d["member_count"] as? Number)?.toInt() ?: 0,
            maxMember = (d["max_member"] as? Number)?.toInt() ?: 0
        )
    }

    fun getBotState(groupOpenid: String): GroupBotState {
        val d = get("/v2/groups/$groupOpenid/bot_state")
        return GroupBotState(
            joined = d["joined"] as? Boolean ?: false,
            mutedInGroup = d["muted_in_group"] as? Boolean ?: false
        )
    }

    // ---- 全群禁言 / 解除 ----

    /**
     * @param durationSec 禁言时长秒数，0 表示解除禁言。
     *                   非 0 时上限 30 天（2592000秒），超出会被 QQ 拒绝。
     */
    fun setMuteAll(groupOpenid: String, durationSec: Int) {
        require(durationSec >= 0) { "禁言时长不能为负" }
        require(durationSec <= 30 * 24 * 3600) { "禁言时长不能超过 30 天" }
        post("/v2/groups/$groupOpenid/restrict_chat_setting", mapOf("duration" to durationSec))
    }

    // ---- 成员 ----

    /** 拉取成员列表，自动翻页直到 `after` 为空或达到 [maxCount] 条。 */
    fun listMembers(groupOpenid: String, limit: Int = 100, maxCount: Int = 500): List<GroupMember> {
        val out = ArrayList<GroupMember>()
        var after: String? = null
        while (out.size < maxCount) {
            val query = buildString {
                append("?limit=").append(limit.coerceIn(1, 1000))
                if (!after.isNullOrEmpty()) append("&after=").append(URLEncoder.encode(after, "UTF-8"))
            }
            val data = get("/v2/groups/$groupOpenid/members$query")
            val members = data["members"] as? List<*> ?: emptyList<Any>()
            for (raw in members) {
                val m = raw as? Map<*, *> ?: continue
                out.add(GroupMember(
                    memberOpenid = m["member_openid"] as? String ?: continue,
                    username = m["username"] as? String,
                    memberRole = m["member_role"] as? String,
                    joinTime = m["join_time"] as? String,
                    msgCount = (m["msg_count"] as? Number)?.toInt()
                ))
            }
            after = data["after"] as? String
            if (after.isNullOrEmpty()) break
        }
        return out
    }

    fun getMember(groupOpenid: String, memberOpenid: String): GroupMember {
        val m = get("/v2/groups/$groupOpenid/members/$memberOpenid")
        return GroupMember(
            memberOpenid = memberOpenid,
            username = m["username"] as? String,
            memberRole = m["member_role"] as? String,
            joinTime = m["join_time"] as? String,
            msgCount = (m["msg_count"] as? Number)?.toInt()
        )
    }

    /**
     * 批量踢人。QQ 侧要求**不传入** `msg_id` 时静默踢人，传入时带踢人理由。
     *
     * 一次最多 100 个 openid，超出直接截断——QQ 会拒绝整个请求而不是截断。
     */
    fun batchRemoveMembers(
        groupOpenid: String,
        memberOpenids: List<String>,
        reason: String? = null
    ): Boolean {
        if (memberOpenids.isEmpty()) return true
        if (memberOpenids.size > MAX_BATCH_REMOVE) {
            throw GroupApiException("单次最多踢 $MAX_BATCH_REMOVE 人，当前 ${memberOpenids.size} 人")
        }
        post(
            "/v2/groups/$groupOpenid/batch_remove_members",
            buildMap {
                put("member_openids", memberOpenids)
                if (!reason.isNullOrBlank()) put("msg_id", reason.take(200))
            }
        )
        return true
    }

    // ---- 群黑名单 ----

    fun getBlacklist(groupOpenid: String): List<String> {
        val d = get("/v2/groups/$groupOpenid/member_blacklist")
        return (d["blacklist"] as? List<*>).orEmpty().mapNotNull { it as? String }
    }

    fun addBlacklist(groupOpenid: String, memberOpenids: List<String>): Boolean {
        if (memberOpenids.isEmpty()) return true
        post("/v2/groups/$groupOpenid/member_blacklist", mapOf("add_list" to memberOpenids))
        return true
    }

    fun removeBlacklist(groupOpenid: String, memberOpenids: List<String>): Boolean {
        if (memberOpenids.isEmpty()) return true
        post("/v2/groups/$groupOpenid/member_blacklist", mapOf("delete_list" to memberOpenids))
        return true
    }

    // ---- 入群申请 ----

    /** @param pending 是否只看待处理（true）或已处理（false）；null 表示全部。 */
    fun listJoinRequests(groupOpenid: String, pending: Boolean? = null, limit: Int = 100, maxCount: Int = 500): List<JoinRequest> {
        val out = ArrayList<JoinRequest>()
        var after: String? = null
        while (out.size < maxCount) {
            val query = buildString {
                append("?limit=").append(limit.coerceIn(1, 1000))
                if (pending != null) append("&pending=").append(pending)
                if (!after.isNullOrEmpty()) append("&after=").append(URLEncoder.encode(after, "UTF-8"))
            }
            val d = get("/v2/groups/$groupOpenid/join_request_list$query")
            for (raw in d["data"] as? List<*> ?: emptyList<Any>()) {
                val m = raw as? Map<*, *> ?: continue
                out.add(JoinRequest(
                    requestId = m["id"] as? String ?: continue,
                    memberOpenid = m["member_openid"] as? String ?: "",
                    username = m["username"] as? String,
                    applyAt = m["apply_time"] as? String,
                    riskTips = m["risk_tips"] as? String,
                    invitedBy = m["invited_by"] as? String,
                    applySource = m["apply_source"] as? String
                ))
            }
            after = d["after"] as? String
            if (after.isNullOrEmpty()) break
        }
        return out
    }

    fun approveJoinRequest(groupOpenid: String, memberOpenid: String, reason: String? = null): Boolean {
        val body = buildMap<String, Any> { if (!reason.isNullOrBlank()) put("reason", reason.take(200)) }
        post("/v2/groups/$groupOpenid/approval_join_request/$memberOpenid", body)
        return true
    }

    fun rejectJoinRequest(groupOpenid: String, memberOpenid: String, reason: String? = null): Boolean {
        val body = buildMap<String, Any> { if (!reason.isNullOrBlank()) put("reason", reason.take(200)) }
        post("/v2/groups/$groupOpenid/reject_join_request/$memberOpenid", body)
        return true
    }

    /** 查本群加群审批策略。注意此接口**不含** group_openid 路径段。 */
    fun getJoinApprovalStrategy(groupOpenid: String): String {
        val d = get("/v2/groups/$groupOpenid/join_approval_strategy")
        return when ((d["strategy"] as? Number)?.toInt()) {
            1 -> "自动同意（无需审核）"
            2 -> "自动拒绝（需管理员审核）"
            else -> "未知策略"
        }
    }

    // ---- HTTP ----

    private fun get(path: String): Map<String, Any?> = request("GET", path, null)

    private fun post(path: String, body: Map<String, Any?>): Map<String, Any?> = request("POST", path, body)

    private fun request(method: String, path: String, body: Map<String, Any?>?): Map<String, Any?> {
        val token = try {
            tokenProvider()
        } catch (e: Exception) {
            throw GroupApiException("获取 access_token 失败：${e.message}")
        }
        val conn = URL(apiBase + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Authorization", "QQBot $token")
        conn.setRequestProperty("X-Union-Appid", cfg.botAppId)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
                it.write(com.google.gson.Gson().toJson(body))
            }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(StandardCharsets.UTF_8)?.readText().orEmpty()
        if (code !in 200..299) {
            logger.warn("群管理接口 $method $path 失败：HTTP $code ${text.take(200)}")
            throw GroupApiException("HTTP $code：${extractMessage(text) ?: text.take(150)}")
        }
        if (text.isBlank()) return emptyMap()
        return com.google.gson.Gson().fromJson(text, MAP_TYPE) ?: emptyMap()
    }

    /** QQ 错误体统一是 `{"code":N,"message":"..."}`。 */
    private fun extractMessage(text: String): String? = runCatching {
        @Suppress("UNCHECKED_CAST")
        (com.google.gson.Gson().fromJson(text, MAP_TYPE) as? Map<String, Any?>)?.get("message") as? String
    }.getOrNull()

    companion object {
        private const val MAX_BATCH_REMOVE = 100

        @Suppress("UNCHECKED_CAST")
        private val MAP_TYPE =
            object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
    }
}
