package com.sakata.focusflow

import org.json.JSONArray

/** 字符串数组键（hidden_places / hidden_apps / meal_skip_days）的 JSON 边界；键名与过滤规则由调用点决定。 */
object StringArrayCodec {
    /** 宽松解码：忽略空白与空串元素（hidden_places / hidden_apps 用）。 */
    fun decodeNonBlank(raw: String): List<String> = runCatching {
        val values = JSONArray(raw.ifBlank { "[]" })
        List(values.length()) { index -> values.optString(index, "") }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    /** 严格解码：原样元素（meal_skip_days 用，非字符串元素被视为坏数据整体降级）。 */
    fun decodeStrict(raw: String): List<String> = runCatching {
        val values = JSONArray(raw.ifBlank { "[]" })
        List(values.length()) { index -> values.getString(index) }
    }.getOrDefault(emptyList())

    fun encode(values: Collection<String>): String = JSONArray().apply {
        values.forEach { put(it) }
    }.toString()
}
