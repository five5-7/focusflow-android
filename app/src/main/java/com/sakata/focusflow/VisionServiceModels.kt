package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Batch A limits; one session includes capability probes and recognition requests. */
object VisionLimits {
    const val SESSION_MS = 120_000L
    const val MAX_REQUESTS = 8
    const val MAX_RESPONSE_BYTES = 1_048_576
    const val MAX_IMAGE_BYTES = 4_194_304
    const val MAX_REQUEST_BYTES = 6_291_456
    const val MAX_CANDIDATES = 200
    const val PROBE_VERSION = 1
}

/** Geometry evidence is normalized to [0,1]; it never writes a Course directly. */
data class VisionBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    fun valid() = listOf(left, top, right, bottom).all { it.isFinite() && it in 0.0..1.0 } && left < right && top < bottom
}
enum class VisionCandidateState { CONFIRMED_BY_USER, NEEDS_REVIEW, REJECTED }
data class VisionGridAxis(val index: Int, val start: Double, val end: Double, val source: String) {
    fun valid(maximum: Int) = index in 1..maximum && start.isFinite() && end.isFinite() && start >= 0 && end <= 1 && start < end && source in setOf("detected", "manual")
}
data class VisionImageGeometry(
    val width: Int, val height: Int, val originalToWorking: List<Double>,
    val weekdays: List<VisionGridAxis>, val periods: List<VisionGridAxis>, val evidence: List<String>
) {
    fun valid() = width in 1..8192 && height in 1..8192 && originalToWorking.size == 9 && originalToWorking.all { it.isFinite() } &&
        weekdays.all { it.valid(7) } && periods.all { it.valid(20) } && weekdays.map { it.index }.distinct().size == weekdays.size && periods.map { it.index }.distinct().size == periods.size
}
data class VisionCandidate(
    val id: String, val title: String, val day: Int?, val startPeriod: Int?, val endPeriod: Int?,
    val box: VisionBox?, val evidence: List<String>, val state: VisionCandidateState = VisionCandidateState.NEEDS_REVIEW,
    val rawLocation: String? = null, val weeks: List<Int>? = null, val note: String? = null,
    val textConfidence: Double? = null, val geometryConfidence: Double? = null
) {
    fun valid() = id.isNotBlank() && title.isNotBlank() && title.length <= 200 && (day == null || day in 1..7) &&
        (startPeriod == null || startPeriod in 1..20) && (endPeriod == null || endPeriod in 1..20) &&
        (startPeriod == null || endPeriod == null || endPeriod >= startPeriod) && (box == null || box.valid()) &&
        listOf(textConfidence,geometryConfidence).all { it == null || (it.isFinite() && it in 0.0..1.0) } &&
        (weeks == null || (weeks.all { it in 1..60 } && weeks.distinct().size == weeks.size)) &&
        (state != VisionCandidateState.CONFIRMED_BY_USER || (day != null && startPeriod != null && endPeriod != null))
}


/** No credential values enter this DTO or its JSON. Verification binds the exact configuration and key revision. */
data class VisionServiceProfile(
    val id: String, val name: String, val baseUrl: String, val model: String,
    val credentialRef: String, val revision: String,
    val timeoutSeconds: Int = 60, val protocol: String = "openai_chat",
    val verifiedCredentialRevision: String? = null, val probeVersion: Int? = null
) {
    fun verified(keyRevision: String) = verifiedCredentialRevision == keyRevision && probeVersion == VisionLimits.PROBE_VERSION
    fun endpoint(path: String): String = baseUrl.trimEnd('/') + "/" + path
    fun rejection(): String? {
        if (id.isBlank() || revision.isBlank() || name.isBlank() || name.length > 80 || model.isBlank() || model.length > 200) return "名称、模型或配置身份不合法"
        if (credentialRef.isBlank() || protocol != "openai_chat" || timeoutSeconds !in 5..120) return "协议或超时不合法"
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return "服务地址不合法"
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.port == 0 || uri.port < -1 || uri.port > 65535) return "服务地址须为 HTTPS，不能带账号、查询参数或片段"
        if (credentialRef == VisionCredentialStore.SHARED_REF && (id != "siliconflow" || uri.host != "api.siliconflow.cn")) return "共享 key 只用于原硅基流动服务；其它地址请新增服务"
        if (id == "siliconflow" && credentialRef != VisionCredentialStore.SHARED_REF) return "内置服务凭据身份不合法"
        if (uri.normalize() != uri || uri.rawPath.orEmpty().contains("%", true) || baseUrl.any { it.isWhitespace() }) return "服务路径不合法"
        if (baseUrl.endsWith("/chat/completions") || baseUrl.endsWith("/models")) return "填写接口根地址，例如 https://服务域名/v1"
        if ((verifiedCredentialRevision == null) != (probeVersion == null) || (probeVersion != null && probeVersion != VisionLimits.PROBE_VERSION)) return "能力测试记录不合法"
        return null
    }
}
data class VisionServiceConfiguration(val profiles: List<VisionServiceProfile>, val defaultId: String? = null)

internal object VisionProfileCodec {
    fun encode(config: VisionServiceConfiguration): String {
        require(config.profiles.size <= 20 && config.profiles.map { it.id }.distinct().size == config.profiles.size)
        require(config.profiles.all { it.rejection() == null })
        require(config.defaultId == null || config.profiles.any { it.id == config.defaultId && it.probeVersion != null })
        return JSONObject().put("version", 1).put("defaultId", config.defaultId ?: JSONObject.NULL).put("profiles", JSONArray().apply {
            config.profiles.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("baseUrl", p.baseUrl).put("model", p.model)
                .put("credentialRef", p.credentialRef).put("revision", p.revision).put("timeoutSeconds", p.timeoutSeconds).put("protocol", p.protocol)
                .put("verifiedCredentialRevision", p.verifiedCredentialRevision ?: JSONObject.NULL).put("probeVersion", p.probeVersion ?: JSONObject.NULL)) }
        }).toString()
    }
    fun decode(raw: String): VisionServiceConfiguration? = runCatching {
        require(raw.toByteArray().size <= 131072 && VisionJsonSyntax.valid(raw))
        val root = JSONObject(raw)
        require(root.keys().asSequence().toSet() == setOf("version", "defaultId", "profiles") && root.get("version") is Int && root.getInt("version") == 1)
        val a = root.getJSONArray("profiles"); require(a.length() <= 20)
        fun text(o: JSONObject, k: String): String { require(o.get(k) is String); return o.getString(k) }
        val profiles = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            require(o.keys().asSequence().toSet() == setOf("id","name","baseUrl","model","credentialRef","revision","timeoutSeconds","protocol","verifiedCredentialRevision","probeVersion"))
            require(o.get("timeoutSeconds") is Int)
            val verified = o.get("verifiedCredentialRevision").let { if (it == JSONObject.NULL) null else { require(it is String); it } }
            val probe = o.get("probeVersion").let { if (it == JSONObject.NULL) null else { require(it is Int); it } }
            VisionServiceProfile(text(o,"id"),text(o,"name"),text(o,"baseUrl"),text(o,"model"),text(o,"credentialRef"),text(o,"revision"),o.getInt("timeoutSeconds"),text(o,"protocol"),verified,probe).also { require(it.rejection() == null) }
        }
        val default = root.get("defaultId").let { if (it == JSONObject.NULL) null else { require(it is String); it } }
        VisionServiceConfiguration(profiles, default).also { encode(it) }
    }.getOrNull()
}

/** Strict RFC 8259 syntax, duplicate-key rejection and depth bound. Floats in provider metadata are allowed. */
internal object VisionJsonSyntax {
    fun valid(raw: String): Boolean = runCatching { Reader(raw).run { value(0); whitespace(); require(pos == raw.length) } }.isSuccess
    private class Reader(val s: String) {
        var pos = 0
        fun whitespace() { while (pos < s.length && s[pos] in " \t\r\n") pos++ }
        private fun expect(c: Char) { whitespace(); require(pos < s.length && s[pos++] == c) }
        private fun string(): String {
            whitespace(); val start = pos; require(pos < s.length && s[pos++] == '"')
            while (pos < s.length) {
                val c = s[pos++]; if (c == '"') return JSONObject("{\"k\":" + s.substring(start, pos) + "}").getString("k")
                require(c.code >= 32)
                if (c == '\\') { require(pos < s.length); val e = s[pos++]; require(e in "\"\\/bfnrtu"); if (e == 'u') { require(pos + 4 <= s.length); repeat(4) { require(s[pos++].digitToIntOrNull(16) != null) } } }
            }
            error("unterminated string")
        }
        fun value(depth: Int) {
            require(depth <= 32); whitespace(); require(pos < s.length)
            when (s[pos]) {
                '{' -> { pos++; whitespace(); val keys = mutableSetOf<String>(); if (pos < s.length && s[pos] == '}') { pos++; return }; while (true) { require(keys.add(string())); expect(':'); value(depth + 1); whitespace(); require(pos < s.length); if (s[pos] == '}') { pos++; break }; expect(',') } }
                '[' -> { pos++; whitespace(); if (pos < s.length && s[pos] == ']') { pos++; return }; while (true) { value(depth + 1); whitespace(); require(pos < s.length); if (s[pos] == ']') { pos++; break }; expect(',') } }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> { val start = pos; if (s[pos] == '-') pos++; require(pos < s.length); if (s[pos] == '0') pos++ else { require(s[pos] in '1'..'9'); while (pos < s.length && s[pos] in '0'..'9') pos++ }; if (pos < s.length && s[pos] == '.') { pos++; digits() }; if (pos < s.length && s[pos] in "eE") { pos++; if (pos < s.length && s[pos] in "+-") pos++; digits() }; require(pos > start) }
            }
        }
        private fun digits() { val start = pos; while (pos < s.length && s[pos] in '0'..'9') pos++; require(pos > start) }
        private fun literal(v: String) { require(s.startsWith(v, pos)); pos += v.length }
    }
}
