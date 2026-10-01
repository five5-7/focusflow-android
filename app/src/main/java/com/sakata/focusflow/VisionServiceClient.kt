package com.sakata.focusflow

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VisionSession(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    val id: String = UUID.randomUUID().toString()
    private val started = clock()
    private var requests = 0
    private var active = 0
    private val stopped = AtomicBoolean(false)
    private val connections = mutableSetOf<HttpURLConnection>()
    @Synchronized internal fun begin() { check(!cancelled() && requests < VisionLimits.MAX_REQUESTS && active < 2); requests++; active++ }
    @Synchronized internal fun end() { active-- }
    fun cancelled() = stopped.get() || clock() - started >= VisionLimits.SESSION_MS
    fun cancel() { stopped.set(true); synchronized(this) { connections.toList().forEach { it.disconnect() }; connections.clear() } }
    @Synchronized internal fun attach(c: HttpURLConnection) { check(!cancelled()); connections.add(c) }
    @Synchronized internal fun detach(c: HttpURLConnection) { connections.remove(c) }
    internal fun remainingMs() = (VisionLimits.SESSION_MS - (clock() - started)).coerceAtLeast(1)
}
internal data class VisionHttpReply(val status: Int, val body: ByteArray)
internal interface VisionTransport { fun exchange(profile: VisionServiceProfile, key: String, path: String, body: ByteArray?, session: VisionSession): VisionHttpReply }
internal class VisionUrlTransport : VisionTransport {
    companion object { private val timer = ScheduledThreadPoolExecutor(1) { r -> Thread(r,"vision-deadline").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true } }
    override fun exchange(profile: VisionServiceProfile, key: String, path: String, body: ByteArray?, session: VisionSession): VisionHttpReply {
        val connection = URL(profile.endpoint(path)).openConnection() as HttpURLConnection
        session.attach(connection)
        val deadline = timer.schedule({ connection.disconnect() }, session.remainingMs(), TimeUnit.MILLISECONDS)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = minOf(15000, session.remainingMs().toInt())
            connection.readTimeout = minOf(profile.timeoutSeconds * 1000, session.remainingMs().toInt())
            connection.requestMethod = if(body == null) "GET" else "POST"
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) { connection.doOutput = true; connection.setRequestProperty("Content-Type","application/json"); connection.setFixedLengthStreamingMode(body.size); connection.outputStream.use { it.write(body) } }
            val status = connection.responseCode
            // Never follow any redirect; never include raw provider errors in diagnostics.
            if (status !in 200..299) return VisionHttpReply(status, byteArrayOf())
            require(connection.contentLengthLong <= VisionLimits.MAX_RESPONSE_BYTES || connection.contentLengthLong == -1L)
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input -> val buffer = ByteArray(8192); while(true) { check(!session.cancelled()); val count = input.read(buffer); if(count < 0) break; require(bytes.size() + count <= VisionLimits.MAX_RESPONSE_BYTES); bytes.write(buffer,0,count) } }
            return VisionHttpReply(status,bytes.toByteArray())
        } finally { deadline.cancel(false); session.detach(connection); connection.disconnect() }
    }
}
sealed class VisionClientResult {
    data class Text(val value: String) : VisionClientResult()
    data class Failure(val message: String) : VisionClientResult()
}
data class VisionCapabilityResult(val connected: Boolean, val image: Boolean, val structured: Boolean, val message: String) {
    val passed get() = connected && image && structured
}
class VisionServiceClient internal constructor(private val transport: VisionTransport) {
    constructor() : this(VisionUrlTransport())
    internal fun request(profile: VisionServiceProfile, key: String, path: String, body: ByteArray?, session: VisionSession): VisionClientResult {
        if(profile.rejection() != null || key.isBlank() || key.any { it == '\n' || it == '\r' } || (body?.size ?: 0) > VisionLimits.MAX_REQUEST_BYTES) return VisionClientResult.Failure("配置或请求大小不合法")
        var begun = false
        return try {
            session.begin(); begun = true
            val reply = transport.exchange(profile,key,path,body,session)
            if(session.cancelled()) return VisionClientResult.Failure("已取消或超过会话时限")
            if(reply.status !in 200..299) return VisionClientResult.Failure(when(reply.status) { 401,403 -> "认证失败，请检查 key 与服务权限"; 404 -> "接口或模型不可用"; 429 -> "服务限流，请稍后重试"; in 300..399 -> "服务重定向已拒绝，请核对接口地址"; else -> "服务请求失败（${reply.status}）" })
            require(reply.body.size <= VisionLimits.MAX_RESPONSE_BYTES)
            val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(reply.body)).toString()
            require(VisionJsonSyntax.valid(raw) && raw.trimStart().startsWith("{"))
            VisionClientResult.Text(raw)
        } catch (_: Exception) { VisionClientResult.Failure(if(session.cancelled()) "已取消或超过会话时限" else "请求失败：检查网络、响应格式、大小限制或服务配置") }
        finally { if(begun) session.end() }
    }
    fun chat(profile: VisionServiceProfile, key: String, prompt: String, image: ByteArray?, session: VisionSession): VisionClientResult {
        if(image != null && image.size > VisionLimits.MAX_IMAGE_BYTES) return VisionClientResult.Failure("图片超过 4 MiB，请缩小后重试")
        val content = JSONArray().put(JSONObject().put("type","text").put("text",prompt))
        if(image != null) content.put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url","data:image/jpeg;base64," + Base64.encodeToString(image,Base64.NO_WRAP))))
        val body = JSONObject().put("model",profile.model).put("temperature",0).put("max_tokens",4096).put("messages",JSONArray().put(JSONObject().put("role","user").put("content",content))).toString().toByteArray()
        return when(val reply = request(profile,key,"chat/completions",body,session)) {
            is VisionClientResult.Failure -> reply
            is VisionClientResult.Text -> runCatching {
                val root = JSONObject(reply.value); val choice = root.getJSONArray("choices").getJSONObject(0)
                require(choice.optString("finish_reason") != "length")
                val message = choice.getJSONObject("message"); require(!message.has("tool_calls") || message.isNull("tool_calls"))
                val text = when(val value = message.get("content")) {
                    is String -> value
                    is JSONArray -> (0 until value.length()).joinToString("") { val part = value.getJSONObject(it); require(part.get("type") == "text" && part.get("text") is String); part.getString("text") }
                    else -> error("unsupported content")
                }; require(text.isNotBlank()); VisionClientResult.Text(text)
            }.getOrElse { VisionClientResult.Failure("响应缺少完整文本，或模型输出被截断") }
        }
    }
    fun models(profile: VisionServiceProfile, key: String, session: VisionSession): List<String>? {
        val reply = request(profile,key,"models",null,session) as? VisionClientResult.Text ?: return null
        return runCatching { val a = JSONObject(reply.value).getJSONArray("data"); require(a.length() <= 10000); (0 until a.length()).map { val id = a.getJSONObject(it).get("id"); require(id is String && id.isNotBlank() && id.length <= 200 && id.none { c -> c.isISOControl() }); id }.distinct() }.getOrNull()
    }
    internal fun probe(profile: VisionServiceProfile, key: String, session: VisionSession, image: ByteArray, expected: String): VisionCapabilityResult {
        val connected = chat(profile,key,"Reply with OK.",null,session)
        if(connected !is VisionClientResult.Text) return VisionCapabilityResult(false,false,false,(connected as VisionClientResult.Failure).message)
        val answer = chat(profile,key,"Read the six digits in this image. Return exactly one JSON object with a string field named code. No markdown or other text.",image,session)
        if(answer !is VisionClientResult.Text) return VisionCapabilityResult(true,false,false,(answer as VisionClientResult.Failure).message)
        val code = runCatching { require(VisionJsonSyntax.valid(answer.value)); val o = JSONObject(answer.value); require(o.keySet() == setOf("code") && o.get("code") is String); o.getString("code") }.getOrNull()
        val imageMatched = code == expected
        return VisionCapabilityResult(true,imageMatched,code != null,if(imageMatched) "连接、图像读取与结构化输出通过" else "连接成功，但图像校验或严格 JSON 输出未通过")
    }
    fun probe(profile: VisionServiceProfile, key: String, session: VisionSession): VisionCapabilityResult {
        val nonce = java.security.SecureRandom().nextInt(900000).plus(100000).toString()
        val bitmap = Bitmap.createBitmap(640,200,Bitmap.Config.ARGB_8888)
        val bytes = try { val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE); val paint = Paint().apply { color = Color.BLACK; textSize = 110f; isAntiAlias = true; typeface = android.graphics.Typeface.MONOSPACE }; canvas.drawText(nonce,100f,145f,paint); ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) }.toByteArray() } finally { bitmap.recycle() }
        return probe(profile,key,session,bytes,nonce)
    }
}
