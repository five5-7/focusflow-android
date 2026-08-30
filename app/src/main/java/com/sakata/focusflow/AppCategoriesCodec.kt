package com.sakata.focusflow

import org.json.JSONObject

/** app_categories 的 JSON 边界：包名 → 分类名键值对（写入时跳过空分类名）。 */
object AppCategoriesCodec {
    fun decode(raw: String): Map<String, String> = runCatching {
        val obj = JSONObject(raw.ifBlank { "{}" })
        obj.keys().asSequence().associateWith { obj.optString(it, "") }.filterValues { it.isNotBlank() }
    }.getOrDefault(emptyMap())

    fun encode(categories: Map<String, String>): String = JSONObject().apply {
        categories.forEach { (pkg, category) -> if (category.isNotBlank()) put(pkg, category) }
    }.toString()
}
