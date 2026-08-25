package com.sakata.focusflow

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 教程联网搜索的默认模型：硅基流动长期免费可用（旧默认 Qwen/Qwen2.5-7B 已下线，勿改回）。 */
const val DEFAULT_TUTORIAL_MODEL = "Qwen/Qwen2.5-7B-Instruct"

/** 视频分析的默认模型：与教程搜索同档（免费文本模型足够处理字幕/简介文本）。 */
const val DEFAULT_VIDEO_ANALYSIS_MODEL = "Qwen/Qwen2.5-7B-Instruct"

/** 教程搜索可选模型预设（免费/推荐/自定义），设置页一键切换。 */
val TUTORIAL_MODEL_PRESETS: List<Pair<String, String>> = listOf(
    "Qwen/Qwen2.5-7B-Instruct" to "Qwen2.5-7B（免费，默认）",
    "deepseek-ai/DeepSeek-V4-Flash" to "DeepSeek-V4-Flash"
)

/** 视频分析可选模型预设（免费/推荐/自定义），设置页一键切换，模式同前。 */
val VIDEO_ANALYSIS_MODEL_PRESETS: List<Pair<String, String>> = listOf(
    "Qwen/Qwen2.5-7B-Instruct" to "Qwen2.5-7B（免费，默认）",
    "deepseek-ai/DeepSeek-V4-Flash" to "DeepSeek-V4-Flash"
)

/** 教程联网搜索设置：开关 + 硅基流动 key + 模型名（无 key 时功能禁用）。 */
data class TutorialSearchSettings(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val model: String = DEFAULT_TUTORIAL_MODEL
)

/** 学习路径的一步：学什么、用什么资源类型、去哪个平台搜什么关键词。不编造链接。 */
data class LearningStep(
    val topic: String,
    val resourceType: String,
    val keyword: String,
    val reason: String
)

/** AI 教程查找建议：去哪个平台搜什么关键词、为什么。 */
data class TutorialSuggestion(
    val platform: String,
    val keyword: String,
    val reason: String
)

/** 硅基流动 OpenAI 兼容客户端；key 仅存本机、只发往 api.siliconflow.cn。 */
object SiliconFlowClient {
    const val ENDPOINT = "https://api.siliconflow.cn/v1/chat/completions"
    private const val MAX_BYTES = 256 * 1024

    sealed class SearchResult {
        class Steps(val items: List<LearningStep>) : SearchResult()
        class Suggestions(val items: List<TutorialSuggestion>) : SearchResult()
        class RawText(val text: String) : SearchResult()
        class Error(val message: String) : SearchResult()
    }

    /** 平台搜索链接：B站 / 知乎 / 中国大学MOOC。 */
    fun platformSearchUrl(platform: String, keyword: String): String {
        val q = android.net.Uri.encode(keyword)
        return when (platform) {
            "知乎" -> "https://www.zhihu.com/search?type=content&q=$q"
            "慕课" -> "https://www.icourse163.org/search.htm?searchValue=$q"
            else -> "https://search.bilibili.com/all?keyword=$q"
        }
    }

    private val systemPrompt = """
        你是学习规划助手。根据用户的学习目标，必须给出 3-5 个步骤的可执行学习路径，数组至少 3 个元素。
        只返回一个 JSON 数组，不要输出其他任何内容（不要代码围栏、不要解释、不要编造链接或网址）。
        数组每个元素格式：{"topic":"这一步学什么","resourceType":"视频|文章|练习","keyword":"去 B站/知乎/慕课等平台搜索的关键词","reason":"为什么先学这一步（一句话）"}
    """.trimIndent()

    suspend fun search(apiKey: String, model: String, title: String, description: String): SearchResult = withContext(Dispatchers.IO) {
        val userMessage = "学习目标：$title" + (if (description.isNotBlank()) "\n补充说明：$description" else "")

        /** 发一次请求并返回 (statusCode 或 null=网络错误, 响应体或错误文案)。每次调用都用全新连接。 */
        fun call(messages: JSONArray, temperature: Double): Pair<Int?, String> {
            val request = JSONObject().apply {
                put("model", model)
                put("temperature", temperature)
                put("max_tokens", 1200)
                put("messages", messages)
            }
            val connection = try {
                (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 60_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    doOutput = true
                }
            } catch (e: Exception) {
                return null to "网络不可用"
            }
            try {
                connection.outputStream.use { stream: OutputStream -> stream.write(request.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) return status to readBody(connection).trim().take(120)
                return null to readBody(connection)
            } catch (e: Exception) {
                return null to (e.message ?: "网络不可用")
            } finally {
                connection.disconnect()
            }
        }

        val (status1, body1) = call(JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
        }, 0.3)
        if (status1 != null) {
            return@withContext SearchResult.Error(
                when (status1) {
                    401 -> "API key 无效，请检查设置页的 key"
                    429 -> "请求过于频繁，稍后再试"
                    else -> {
                        // 错误体通常是 JSON（如模型不存在）；提取 message，模型类错误附更换提示。
                        val message = runCatching { JSONObject(body1).optString("message", "") }.getOrNull() ?: body1
                        val text = message.ifBlank { body1 }.take(120)
                        when {
                            text.isBlank() -> "请求失败（$status1）"
                            text.contains("odel", ignoreCase = true) -> "$text（模型可能已下线，去设置页换一个模型名）"
                            else -> text
                        }
                    }
                }
            )
        }
        val content = runCatching {
            JSONObject(body1).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""
        }.getOrElse { "" }
        if (content.isBlank()) return@withContext SearchResult.Error("没有返回内容，请检查模型名")
        var parsed = parseSteps(content)
        // 首次输出不符合要求时，用全新连接重试一次（追加“只输出 JSON 数组”指令）。
        if (parsed == null) {
            val (status2, body2) = call(JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
                put(JSONObject().apply { put("role", "assistant"); put("content", content) })
                put(JSONObject().apply { put("role", "user"); put("content", "以上输出不符合要求。请只输出一个 JSON 数组，不要任何其他文字、解释或代码围栏。数组每个元素格式：{\"topic\":\"这一步学什么\",\"resourceType\":\"视频|文章|练习\",\"keyword\":\"去 B站/知乎/慕课等平台搜索的关键词\",\"reason\":\"为什么先学这一步\"}，必须 3-5 个元素。") })
            }, 0.1)
            if (status2 == null) {
                val retryContent = runCatching {
                    JSONObject(body2).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""
                }.getOrElse { "" }
                if (retryContent.isNotBlank()) parsed = parseSteps(retryContent)
            }
        }
        // 仍失败时再严格重试一次（带格式示例、更低温度）。
        if (parsed == null) {
            val (status3, body3) = call(JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
                put(JSONObject().apply { put("role", "assistant"); put("content", content) })
                put(JSONObject().apply { put("role", "user"); put("content", "再次要求：只输出 JSON 数组，格式示例：[{\"topic\":\"学习内容\",\"resourceType\":\"视频\",\"keyword\":\"搜索关键词\",\"reason\":\"原因\"}]，3-5 个元素，不要任何其他文字或代码围栏。") })
            }, 0.05)
            if (status3 == null) {
                val thirdContent = runCatching {
                    JSONObject(body3).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""
                }.getOrElse { "" }
                if (thirdContent.isNotBlank()) parsed = parseSteps(thirdContent)
            }
        }
        if (parsed != null) SearchResult.Steps(parsed) else SearchResult.RawText(content)
    }

    private val findPrompt = """
        你是学习规划助手。根据用户的学习目标，给出 3-5 条"去哪个平台搜什么关键词能找到有用教程"的建议，数组至少 3 个元素。
        只返回一个 JSON 数组，不要输出其他任何内容（不要代码围栏、不要解释、不要编造链接或网址）。
        数组每个元素格式：{"platform":"B站|知乎|慕课","keyword":"具体搜索关键词","reason":"为什么这样搜（一句话）"}，platform 只能是 B站、知乎、慕课 之一。
    """.trimIndent()

    /** 按目标生成平台搜索建议（B站/知乎/慕课 + 关键词 + 原因）；解析失败或首次输出异常时用全新连接重试一次。 */
    suspend fun findTutorials(apiKey: String, model: String, contextText: String): SearchResult = withContext(Dispatchers.IO) {
        /** 发一次请求并返回 (statusCode 或 null=网络错误, 响应体或错误文案)。每次调用都用全新连接。 */
        fun call(messages: JSONArray, temperature: Double): Pair<Int?, String> {
            val request = JSONObject().apply {
                put("model", model)
                put("temperature", temperature)
                put("max_tokens", 1200)
                put("messages", messages)
            }
            val connection = try {
                (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 60_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    doOutput = true
                }
            } catch (e: Exception) {
                return null to "网络不可用"
            }
            try {
                connection.outputStream.use { stream: OutputStream -> stream.write(request.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) return status to readBody(connection).trim().take(120)
                return null to readBody(connection)
            } catch (e: Exception) {
                return null to (e.message ?: "网络不可用")
            } finally {
                connection.disconnect()
            }
        }

        val userMessage = "学习目标：$contextText"
        val (status1, body1) = call(JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", findPrompt) })
            put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
        }, 0.3)
        if (status1 != null) {
            return@withContext SearchResult.Error(
                when (status1) {
                    401 -> "API key 无效，请检查设置页的 key"
                    429 -> "请求过于频繁，稍后再试"
                    else -> {
                        val message = runCatching { JSONObject(body1).optString("message", "") }.getOrNull() ?: body1
                        val text = message.ifBlank { body1 }.take(120)
                        when {
                            text.isBlank() -> "请求失败（$status1）"
                            text.contains("odel", ignoreCase = true) -> "$text（模型可能已下线，去设置页换一个模型名）"
                            else -> text
                        }
                    }
                }
            )
        }
        val content = runCatching {
            JSONObject(body1).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""
        }.getOrElse { "" }
        if (content.isBlank()) return@withContext SearchResult.Error("没有返回内容，请检查模型名")
        var parsed = parseSuggestions(content)
        if (parsed == null) {
            val (status2, body2) = call(JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
                put(JSONObject().apply { put("role", "assistant"); put("content", content) })
                put(JSONObject().apply { put("role", "user"); put("content", "以上输出不符合要求。请只输出一个 JSON 数组，不要任何其他文字、解释或代码围栏。数组每个元素格式：{\"platform\":\"B站|知乎|慕课\",\"keyword\":\"具体搜索关键词\",\"reason\":\"为什么（一句话）\"}，必须 3-5 个元素。") })
            }, 0.1)
            if (status2 == null) {
                val retryContent = runCatching {
                    JSONObject(body2).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""
                }.getOrElse { "" }
                if (retryContent.isNotBlank()) parsed = parseSuggestions(retryContent)
            }
        }
        if (parsed != null) SearchResult.Suggestions(parsed) else SearchResult.RawText(content)
    }

    /** 解析平台搜索建议；兼容数组、单个对象、说明文字与 JSON 混杂、键名变体。失败返回 null。 */
    private fun parseSuggestions(content: String): List<TutorialSuggestion>? = runCatching {

        val cleaned = content.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        when {
            start >= 0 && end > start -> {
                val values = JSONArray(cleaned.substring(start, end + 1))
                List(values.length()) { index -> parseSuggestion(values.optJSONObject(index) ?: return@List null) }
                    .filterNotNull().takeIf { it.isNotEmpty() }
            }
            cleaned.startsWith("{") -> listOfNotNull(parseSuggestion(JSONObject(cleaned)))
            else -> {
                val list = mutableListOf<TutorialSuggestion>()
                var from = 0
                while (true) {
                    val open = cleaned.indexOf('{', from)
                    if (open < 0) break
                    val close = findMatchingBrace(cleaned, open)
                    if (close < 0) break
                    parseSuggestion(JSONObject(cleaned.substring(open, close + 1)))?.let { list += it }
                    from = close + 1
                }
                list.takeIf { it.isNotEmpty() }
            }
        }
    }.getOrNull()

    private fun parseSuggestion(value: JSONObject): TutorialSuggestion? {
        val keyword = value.optString("keyword", "").takeIf { it.isNotBlank() } ?: value.optString("关键词", "")
        if (keyword.isBlank()) return null
        val platformRaw = value.optString("platform", "").takeIf { it.isNotBlank() } ?: value.optString("平台", "")
        val platform = when {
            platformRaw.contains("B站") || platformRaw.contains("bili") -> "B站"
            platformRaw.contains("知乎") || platformRaw.contains("zhihu") -> "知乎"
            platformRaw.contains("慕课") || platformRaw.contains("icourse") || platformRaw.contains("MOOC") -> "慕课"
            else -> "B站"
        }
        return TutorialSuggestion(
            platform = platform,
            keyword = keyword.trim(),
            reason = value.optString("reason", "").trim()
        )
    }

    private val weeklySummaryPrompt =
        "你是学习与生活复盘助手。根据用户本周的真实记录数据，生成一段简短的中文周总结（150 字以内）：概括本周目标完成情况与主要阻碍，客观指出 1-2 个可改进点，语气平和不评判。只输出总结正文，不要标题、不要编造数据。数据：\n\n"

    /** 周总结：把本周目标/反馈/自律数据发给模型生成简短复盘；失败返回 null。 */
    suspend fun weeklySummary(apiKey: String, model: String, dataText: String): String? = withContext(Dispatchers.IO) {
        val request = JSONObject().apply {
            put("model", model)
            put("temperature", 0.3)
            put("max_tokens", 400)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", weeklySummaryPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", dataText.take(3000)) })
            })
        }
        val connection = try {
            (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
            }
        } catch (e: Exception) {
            return@withContext null
        }
        try {
            connection.outputStream.use { stream: OutputStream -> stream.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) return@withContext null
            return@withContext runCatching {
                JSONObject(readBody(connection)).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")?.trim()?.takeIf { it.isNotBlank() }
            }.getOrNull()
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /** 剥围栏后解析为学习路径步骤；兼容：数组、单个对象、说明文字与 JSON 混杂、键名变体。失败返回 null。 */
    private fun parseSteps(content: String): List<LearningStep>? = runCatching {
        val cleaned = content.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        when {
            start >= 0 && end > start -> parseArray(cleaned.substring(start, end + 1))
            cleaned.startsWith("{") -> parseArray("[$cleaned]")
            else -> {
                // 逐对象扫描：说明文字夹在中间也能提取
                val steps = mutableListOf<LearningStep>()
                var from = 0
                while (true) {
                    val open = cleaned.indexOf('{', from)
                    if (open < 0) break
                    val close = findMatchingBrace(cleaned, open)
                    if (close < 0) break
                    parseObject(JSONObject(cleaned.substring(open, close + 1)))?.let { steps += it }
                    from = close + 1
                }
                steps.takeIf { it.isNotEmpty() }
            }
        }
    }.getOrNull()

    private fun parseArray(text: String): List<LearningStep>? = runCatching {
        val values = JSONArray(text)
        List(values.length()) { index -> parseObject(values.optJSONObject(index) ?: return@List null) }
            .filterNotNull().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun parseObject(value: JSONObject): LearningStep? {
        val topic = listOf("topic", "title", "标题", "主题", "学习内容").firstNotNullOfOrNull { value.optString(it, "").takeIf { k -> k.isNotBlank() } } ?: ""
        val keyword = listOf("keyword", "关键词", "搜索关键词", "搜索词").firstNotNullOfOrNull { value.optString(it, "").takeIf { k -> k.isNotBlank() } } ?: topic
        if (topic.isBlank()) return null
        return LearningStep(
            topic = topic.trim(),
            resourceType = listOf("resourceType", "类型", "资源类型").firstNotNullOfOrNull { value.optString(it, "").takeIf { k -> k.isNotBlank() } } ?: "文章",
            keyword = keyword.trim(),
            reason = listOf("reason", "原因", "为什么").firstNotNullOfOrNull { value.optString(it, "").takeIf { k -> k.isNotBlank() } } ?: ""
        )
    }

    /** 从 open 处的 { 找到配对的 }（考虑嵌套）。 */
    private fun findMatchingBrace(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val reader = BufferedReader(InputStreamReader(stream, "UTF-8"))
        val buffer = StringBuilder()
        val chunk = CharArray(8192)
        var total = 0
        while (total < MAX_BYTES) {
            val count = reader.read(chunk, 0, minOf(8192, MAX_BYTES - total))
            if (count < 0) break
            buffer.append(chunk, 0, count)
            total += count
        }
        reader.close()
        return buffer.toString()
    }
}

/** AI 总结：把粘贴的教程/文章文本压缩为中文要点（3-5 条 + 适用目标说明）。成功返回总结文本，失败返回以【错误】开头。 */
object AiSummarizer {
    private val summaryPrompt =
        "请把下面的教程/文章内容总结为 3-5 条中文要点，并说明它适合用来完成什么样的学习目标。要求：每条要点一句话、保留具体动作/步骤/练习名称等关键细节；不要输出重复内容或无意义的字符；格式为“要点1：…”逐行列出，最后一行“适用目标：…”。内容：\n\n"
    private val retryPrompt =
        "上一条输出无效（重复或无意义的字符，如大量“1”）。请重新总结下面的内容：只输出正文要点，不要重复序号或任何无意义字符。内容：\n\n"

    suspend fun summarize(apiKey: String, model: String, text: String): String = withContext(Dispatchers.IO) {
        val trimmed = text.trim().take(6000)
        if (trimmed.length < 10) return@withContext "【错误】文本太短（少于 10 字），先粘贴更多内容再试。"

        /** 发一次请求返回内容文本；出错返回 null。每次用全新连接。 */
        fun call(prompt: String): String? {
            val request = JSONObject().apply {
                put("model", model)
                put("temperature", 0.2)
                put("max_tokens", 600)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", prompt + trimmed) })
                })
            }
            val connection = try {
                (URL(SiliconFlowClient.ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 60_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    doOutput = true
                }
            } catch (e: Exception) {
                return null
            }
            try {
                connection.outputStream.use { stream: OutputStream -> stream.write(request.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) return null
                return runCatching {
                    JSONObject(readSummaryBody(connection)).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""
                }.getOrElse { "" }
            } catch (e: Exception) {
                return null
            } finally {
                connection.disconnect()
            }
        }

        var content = call(summaryPrompt)
        // 防退化：内容过短或大量重复同一字符（如“1111…”）时，用更严格的提示重试一次。
        if (content == null) return@withContext "【错误】请求失败，请检查网络或模型名"
        if (content.isBlank()) return@withContext "【错误】没有返回内容，请检查模型名"
        if (isDegenerate(content)) {
            content = call(retryPrompt)
            if (content != null && !isDegenerate(content) && content.isNotBlank()) content.trim()
            else "【错误】模型输出异常（重复字符），请重试或换一个模型"
        } else content.trim()
    }

    /** 退化检测：去除空白后过短，或单个字符占比过高（如大量“1”）。 */
    private fun isDegenerate(text: String): Boolean {
        val compact = text.replace(Regex("\\s"), "")
        if (compact.length < 10) return true
        val max = compact.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        return max >= compact.length * 0.6
    }

    private fun readSummaryBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val reader = BufferedReader(InputStreamReader(stream, "UTF-8"))
        val buffer = StringBuilder()
        val chunk = CharArray(8192)
        var total = 0
        while (total < 256 * 1024) {
            val count = reader.read(chunk, 0, minOf(8192, 256 * 1024 - total))
            if (count < 0) break
            buffer.append(chunk, 0, count)
            total += count
        }
        reader.close()
        return buffer.toString()
    }
}

/** 教程资料 AI 总结对话框：粘贴正文 → 生成要点 → 保存到教程。 */
@Composable
fun ResourceSummaryDialog(settings: TutorialSearchSettings, resource: LearningResource, onDismiss: () -> Unit, onSave: (String) -> Unit, onLoadingChange: (Boolean) -> Unit = {}) {
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var generating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isError = result?.startsWith("【错误】") == true
    AlertDialog(
        onDismissRequest = { if (!generating) onDismiss() },
        title = { Text("AI 总结《${resource.title}》") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("把教程/文章正文粘贴进来，生成 3–5 条中文要点与适用目标。内容只发给硅基流动，不保存原文。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("粘贴正文（建议 ≥50 字）") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                if (settings.apiKey.isBlank()) Text("请先在 设置 → 学习路径建议 填写硅基流动 key。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = {
            if (result == null) {
                Button(enabled = text.length >= 50 && !generating && settings.apiKey.isNotBlank(), onClick = {
                    generating = true
                    result = null
                    onLoadingChange(true)
                    scope.launch {
                        result = AiSummarizer.summarize(settings.apiKey, settings.model, text)
                        generating = false
                        onLoadingChange(false)
                    }
                }) { Text(if (generating) "总结中…" else "生成总结") }
            } else {
                Button(enabled = !isError, onClick = { onSave(result!!) }) { Text("保存到教程") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !generating) { Text("关闭") } }
    )
}

/** 学习路径建议对话框：输入目标 → 生成 3–5 步（学什么/资源类型/搜索关键词/为什么），每步可一键去 B站搜，不编造链接。 */
@Composable
fun TutorialSearchDialog(
    settings: TutorialSearchSettings,
    onDismiss: () -> Unit,
    initialTitle: String = "",
    onLoadingChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initialTitle) }
    var description by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<SiliconFlowClient.SearchResult?>(null) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!searching) onDismiss() },
        title = { Text("学习路径建议") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("学习目标，如“概率论”") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("补充说明（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                when (val result = state) {
                    null -> Text("按目标生成 3–5 步学习路径：每步给出学什么、用什么资源（视频/文章/练习）和去 B站/知乎/慕课 搜什么关键词。不编造链接，搜到的有用内容可手动收藏到教程资料。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is SiliconFlowClient.SearchResult.Error -> Text(result.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    is SiliconFlowClient.SearchResult.Suggestions -> Text("返回了平台搜索建议而非学习路径，请在 新建目标 → 搜学习教程 中查看。", style = MaterialTheme.typography.bodySmall)
                    is SiliconFlowClient.SearchResult.RawText -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("返回内容不是路径，已原文展示：", style = MaterialTheme.typography.labelMedium)
                        Text(result.text, style = MaterialTheme.typography.bodySmall)
                    }
                    is SiliconFlowClient.SearchResult.Steps -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        result.items.forEachIndexed { index, step ->
                            Card {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("${index + 1}. ${step.topic}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        Text(step.resourceType, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Text("搜：${step.keyword}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (step.reason.isNotBlank()) Text(step.reason, style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = {
                                        val url = "https://search.bilibili.com/all?keyword=" + android.net.Uri.encode(step.keyword)
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                                    }, modifier = Modifier.fillMaxWidth()) { Text("去 B站搜「${step.keyword.take(10)}${if (step.keyword.length > 10) "…" else ""}」") }
                                }
                            }
                        }
                        Text("点“去 B站搜”跳转搜索该关键词；搜到有用的内容可在 目标与执行 → 教程资料 手动收藏。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = title.isNotBlank() && !searching, onClick = {
                searching = true
                state = null
                onLoadingChange(true)
                scope.launch {
                    state = SiliconFlowClient.search(settings.apiKey, settings.model, title.trim(), description.trim())
                    searching = false
                    onLoadingChange(false)
                }
            }) { Text(if (searching) "生成中…" else "生成学习路径") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !searching) { Text("关闭") } }
    )
}

/** AI only proposes a search action. It never creates a resource until the user confirms real material. */
@Composable
fun TutorialFinderDialog(
    settings: TutorialSearchSettings,
    initialContext: String,
    onDismiss: () -> Unit,
    onUseSuggestion: (action: String) -> Unit,
    onLoadingChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var searchText by remember { mutableStateOf(initialContext) }
    var state by remember { mutableStateOf<SiliconFlowClient.SearchResult?>(null) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!searching) onDismiss() },
        title = { Text("搜学习教程") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = searchText, onValueChange = { searchText = it }, label = { Text("目标/关键词（预填目标名＋预期结果）") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("B站", "知乎", "慕课").forEach { platform ->
                        OutlinedButton(onClick = {
                            val url = SiliconFlowClient.platformSearchUrl(platform, searchText.trim().ifBlank { "教程" })
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                        }, modifier = Modifier.weight(1f)) { Text("去${platform}搜") }
                    }
                }
                Text("手动搜索：直接跳转平台；找到真实内容后，回到资料工具箱保存链接或笔记。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                if (!settings.enabled || settings.apiKey.isBlank()) {
                    Text("AI 建议需在 设置 → 教程联网搜索 开启并填写硅基流动 key。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Button(enabled = !searching && searchText.isNotBlank(), onClick = {
                        searching = true
                        state = null
                        onLoadingChange(true)
                        scope.launch {
                            state = SiliconFlowClient.findTutorials(settings.apiKey, settings.model, searchText.trim())
                            searching = false
                            onLoadingChange(false)
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (searching) "生成中…" else "AI 生成搜索建议") }
                }
                when (val result = state) {
                    null -> Text("AI 只建议去哪里搜索什么；找到并确认真实资料后，再从资料工具箱保存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is SiliconFlowClient.SearchResult.Error -> Text(result.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    is SiliconFlowClient.SearchResult.RawText -> Text("返回内容不是建议，已原文展示：${result.text}", style = MaterialTheme.typography.bodySmall)
                    is SiliconFlowClient.SearchResult.Steps -> Text("返回了学习路径而非搜索建议，请重新生成。", style = MaterialTheme.typography.bodySmall)
                    is SiliconFlowClient.SearchResult.Suggestions -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        result.items.forEach { suggestion ->
                            Card {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("去${suggestion.platform}搜「${suggestion.keyword}」", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        Text(suggestion.platform, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    }
                                    if (suggestion.reason.isNotBlank()) Text(suggestion.reason, style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = {
                                            val url = SiliconFlowClient.platformSearchUrl(suggestion.platform, suggestion.keyword)
                                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                                        }, modifier = Modifier.weight(1f)) { Text("去${suggestion.platform}搜") }
                                        Button(onClick = { onUseSuggestion(LearningResourcePolicy.candidateFirstAction(suggestion.platform, suggestion.keyword)) }, modifier = Modifier.weight(1f)) { Text("用作第一步") }
                                    }
                                }
                            }
                        }
                        Text("采用后只会填入目标的候选第一步；请回到目标编辑器确认或修改，不会自动创建资料。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !searching) { Text("关闭") } }
    )
}

/** 视频分析（一站式整理）：粘贴视频标题/链接/字幕 → AI 要点＋适用目标 → 保存为新教程。模型模式同前（免费/推荐/自定义）。 */
@Composable
fun VideoAnalysisDialog(
    settings: TutorialSearchSettings,
    model: String,
    onDismiss: () -> Unit,
    onSave: (title: String, url: String, summary: String) -> Unit,
    onLoadingChange: (Boolean) -> Unit = {}
) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var analyzing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!analyzing) onDismiss() },
        title = { Text("视频分析（一站式整理）") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("粘贴已经确认的视频链接、简介或字幕，生成候选要点；保存前仍由你确认标题和真实材料。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("教程标题（如：概率论入门）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("视频链接（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("粘贴字幕／简介／笔记正文（要 AI 总结才需要）") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                result?.let {
                    Text("分析完成，可直接保存：", style = MaterialTheme.typography.bodySmall)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (settings.apiKey.isBlank()) {
                    Text("需要先在 设置 → 学习路径建议 填写硅基流动 key（视频分析与教程搜索共用）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Button(enabled = !analyzing && text.trim().length >= 10, onClick = {
                        analyzing = true
                        result = null
                        error = null
                        onLoadingChange(true)
                        scope.launch {
                            val summary = AiSummarizer.summarize(settings.apiKey, model, text.trim())
                            analyzing = false
                            if (summary.startsWith("【错误】")) error = summary else result = summary
                            onLoadingChange(false)
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (analyzing) "分析中…" else "AI 分析（要点＋适用目标）") }
                }
            }
        },
        confirmButton = { Button(enabled = LearningResourcePolicy.canSave(title, url, text), onClick = { onSave(title.trim(), url.trim(), result ?: text.trim()) }) { Text("确认资料并保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !analyzing) { Text("取消") } }
    )
}
