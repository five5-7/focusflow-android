package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 教务课表响应的**脱敏形状摘要**，用于在真机上采集字段证据。
 *
 * 采集目标（见 `docs/9.0-stage6-course-identity-design.md` §5）：字段名、类型、非空比例、
 * 字段在两次导入间是否相同（用稳定指纹比较）、请求学年/学期代码所在层级。
 *
 * **绝不输出**：任何原始取值、课程名/地点原文、个人信息字段名（学号/姓名/学院等一律以
 * `[redacted]` 占位）。只有"身份相关"的少数结构化字段会输出**截断指纹**，用于比较两次
 * 导入是否同一值——截断 SHA-256 不是加密，低基数结构化字段（星期/节次/学期）本可枚举反查，
 * 它只用来做跨次比对，不承担保密职责。
 *
 * 只在 debug 构建里由 [ZjuTimetableParser] 的调用方打印；release 不打印。
 */
internal object ZjuResponseShape {

    /**
     * 允许输出指纹的字段：结构化、非个人、且是身份/选课键相关。
     * **不放任何可读文本字段**（课程名、地点、教学班名都可能含课程名/教师名），
     * 也**不放没有证据支撑的猜测键**——真实键名必须先取证再加。
     */
    private val FINGERPRINT_FIELDS = setOf(
        "xkkh", "xnm", "xqm", "xqj", "djj", "skcd", "jcs", "dsz"
    )

    /**
     * 个人信息相关字段：连字段名都不输出，只保留数量占位。
     * 末尾三个（`jszgh` 教师职工号、`xzb` 行政班、`zy` 专业）是**真机日志实证存在**的行字段——
     * 只列猜测名而漏掉实证名，等于对真实载荷失效。
     */
    private val REDACTED_FIELDS = setOf(
        "xh", "xm", "xydm", "xy", "zym", "bjm", "nj", "sfzh", "zjh", "sjh", "lxdh",
        "yhm", "mm", "jsxm", "jsgh", "email", "yx", "xb", "mz", "zzmm", "csrq",
        "jszgh", "xzb", "zy"
    )

    private const val MAX_ROW_FIELDS = 40
    private const val MAX_VALUES = 64
    private const val FINGERPRINT_HEX = 12

    /**
     * 生成单行摘要。格式：
     * `rows=16 keys=[kcb,kcmc,xkkh,...] kcb=str/16/16 cdmc=str/14/13 xkkh=str/16/9#a1b2c3d4e5f6`
     * 每段为 `字段名=类型/行数/非空数[#指纹]`。
     */
    internal fun summarize(payload: String): String {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return "shape=empty"
        val text = trimmed.take(400_000)

        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return "shape=not-json len=${text.length}"

        val keys = root.keys().asSequence().toList().sorted()
        val topKeys = keys.joinToString(",") { if (it.lowercase() in REDACTED_FIELDS) "[redacted]" else it }

        val rows = root.optJSONArray("kbList")
            ?: return "shape=json-no-kbList keys=[$topKeys]"

        val stats = linkedMapOf<String, FieldStat>()
        var objectRows = 0
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            objectRows += 1
            for (name in row.keys().asSequence().toList()) {
                val stat = stats.getOrPut(name.lowercase()) { FieldStat() }
                stat.record(row.opt(name))
            }
        }

        // 字段上限的取舍：**身份相关字段必须保住**，否则 xkkh 可能被静默丢掉、取证白做。
        val ordered = stats.entries.sortedWith(
            compareByDescending<Map.Entry<String, FieldStat>> { it.key in FINGERPRINT_FIELDS }.thenBy { it.key }
        )
        val shown = ordered.take(MAX_ROW_FIELDS).sortedBy { it.key }
        val omitted = ordered.size - shown.size
        val fields = shown.joinToString(" ") { (name, stat) -> render(name, stat) }
        val suffix = if (omitted > 0) " omitted=$omitted" else ""

        return "rows=${rows.length()} objects=$objectRows keys=[$topKeys] $fields$suffix"
    }

    private fun render(name: String, stat: FieldStat): String {
        if (name in REDACTED_FIELDS) {
            return "[redacted]=${stat.type}/${stat.total}/${stat.nonBlank}"
        }
        // 值集触顶时标 `+`：否则"两次导入取到不同子集"会被误读成字段不稳定。
        val truncated = if (stat.valuesTruncated) "+" else ""
        val base = "$name=${stat.type}/${stat.total}/${stat.nonBlank}/${stat.distinct}$truncated"
        if (name !in FINGERPRINT_FIELDS || stat.values.isEmpty()) return base
        return "$base#${fingerprint(stat.values.toList())}"
    }

    /**
     * 值的稳定指纹：同一字段两次导入若取值集合相同，指纹相同——这正是"字段是否跨次稳定"的判据。
     * 只对结构化字段调用（见 [FINGERPRINT_FIELDS]），截断到 12 个 hex。
     */
    private fun fingerprint(values: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(values.sorted().joinToString("\u0000").toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }.take(FINGERPRINT_HEX)
    }

    private class FieldStat {
        var total = 0
        var nonBlank = 0
        var type = "none"
        var valuesTruncated = false
        val values = linkedSetOf<String>()

        val distinct: Int get() = values.size

        fun record(value: Any?) {
            total += 1
            when (value) {
                null, JSONObject.NULL -> if (type == "none") type = "null"
                is JSONObject -> type = "obj"
                is JSONArray -> type = "arr"
                is Boolean -> type = "bool"
                // 数值也要进值集：若教务把 djj/skcd 之类返回成 JSON number，
                // 不进值集就会"没有指纹"，跨次稳定性根本无从比较（复核指出的取证保真缺陷）。
                is Number -> {
                    type = if (type == "none" || type == "null") "num" else type
                    nonBlank += 1
                    add(value.toString())
                }
                else -> {
                    val text = value.toString().trim()
                    if (text.isNotEmpty() && !text.equals("null", ignoreCase = true)) {
                        type = if (type == "none" || type == "null") "str" else type
                        nonBlank += 1
                        add(text)
                    }
                }
            }
        }

        private fun add(text: String) {
            if (text in values) return
            if (values.size < MAX_VALUES) values += text else valuesTruncated = true
        }
    }
}
