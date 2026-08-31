package com.sakata.focusflow

import java.util.Calendar

/**
 * 快速输入自然语言解析结果。
 *
 * 字段语义：
 * - [title]：剥离元语后的标题（若剥离后为空则回退原始输入）；
 * - [durationMinutes]：提取的预计时长（已 coerce 到 5..360），未命中时长元语为 null；
 * - [periodLabel]/[windowStartAt]/[windowEndAt]：时段词与其落点窗口（毫秒，已含顺延）；
 * - [exactMinute]：精确时刻的当日分钟数（0..1439），[exactDayOffset] 相对今天的日偏移，[exactAt] 构造后的毫秒时刻（已含顺延）；
 * - [recognized]：是否命中元语。false 时调用方应将整串当作普通标题（与旧 QuickCaptureDialog 行为一致）。
 */
data class QuickInputParse(
    val title: String,
    val durationMinutes: Int? = null,
    val periodLabel: String? = null,
    val windowStartAt: Long? = null,
    val windowEndAt: Long? = null,
    val exactMinute: Int? = null,
    val exactDayOffset: Int? = null,
    val exactAt: Long? = null,
    val recognized: Boolean
)

/**
 * 快速输入自然语言解析器（纯 Kotlin，无 Android 依赖，可单测）。
 *
 * 支持：时段词（9 档）、时长（半小时/X个半小时/X小时/X分钟/X分，循环内累加）、
 * 精确时间（X点/X点半/X点YZ分，下午/傍晚/晚上/深夜且 hour<12 时 +12）、
 * 日偏移词（今天/明天/后天）、标题头部动词剥离（带防误剥 guard 集）。
 *
 * 过去时间规则：精确时间已过 → 顺延一天（下一次发生）；时段窗口整体已过 → 顺延一天，部分已过保持。
 * 未命中元语（duration∧exact∧period+动词 均为假）→ recognized=false，整串即标题。
 */
object QuickInputParser {
    /** 头部动词剥离集。 */
    private const val VERBS = "看读学写做背听打练玩抄"

    /** 动词后紧跟的防误剥字（学习/练习/读书/做饭…）。 */
    private const val GUARDS = "习练生院校分业完工力饭书"

    /** 时段限定词：精确时间 hour<12 时 +12。 */
    private const val STICKY_PERIODS = "下午傍晚晚上深夜"

    private const val DAY_MS = 24L * 60 * 60 * 1000

    private val CN_DIGIT = mapOf(
        '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10
    )

    /** 日偏移词 → 相对今天的天数。 */
    private val DAY_WORDS = listOf("今天" to 0, "明天" to 1, "后天" to 2)

    /** 时段表：词 → [起始分钟, 结束分钟)（0 点为原点，结束分钟可为 1440 即次日 00:00）。 */
    private val PERIODS = listOf(
        "凌晨" to (0 to 300),
        "清晨" to (300 to 480),
        "早上" to (360 to 540),
        "上午" to (480 to 720),
        "中午" to (660 to 840),
        "下午" to (720 to 1080),
        "傍晚" to (1020 to 1200),
        "晚上" to (1080 to 1440),
        "深夜" to (1320 to 1440)
    )

    private val EXACT_REGEX = Regex("(\\d{1,2}|[一二两三四五六七八九十])点(半|(\\d{1,2})分?)?")

    /** 时长模式，按优先级：个半小时 > 小时 > 半小时 > 分钟 > 分。 */
    private val DURATION_PATTERNS = listOf(
        Regex("(\\d{1,2}|[一二两三四五六七八九十])个?半小时"),
        Regex("(\\d{1,2}|[一二两三四五六七八九十])个?小时"),
        Regex("半小时"),
        Regex("(\\d{1,2}|[一二两三四五六七八九十])分钟"),
        Regex("(\\d{1,2}|[一二两三四五六七八九十])分")
    )

    private data class ExactMatch(val start: Int, val end: Int, val hour: Int, val minute: Int)

    fun parse(input: String, now: Long): QuickInputParse {
        val s = input.trim()
        if (s.isEmpty()) return QuickInputParse(title = s, recognized = false)

        var dayOffset = 0
        var body = s
        for ((word, offset) in DAY_WORDS) {
            if (body.startsWith(word)) {
                dayOffset = offset
                body = body.substring(word.length)
                break
            }
        }

        // 时段词（取首个命中）
        var periodLabel: String? = null
        var periodRange: Pair<Int, Int>? = null
        var periodSpan: IntRange? = null
        for ((word, range) in PERIODS) {
            val index = body.indexOf(word)
            if (index >= 0) {
                periodLabel = word
                periodRange = range
                periodSpan = index until index + word.length
                break
            }
        }

        // 精确时间（首个命中，校验小时/分钟合法）
        var exact: ExactMatch? = null
        EXACT_REGEX.find(body)?.let { match ->
            val hour = toNumber(match.groupValues[1])
            var minute = 0
            when {
                match.groupValues[2] == "半" -> minute = 30
                match.groupValues[3].isNotEmpty() -> minute = toNumber(match.groupValues[3]) ?: 0
            }
            if (hour != null && hour in 0..23 && minute in 0..59) {
                exact = ExactMatch(match.range.first, match.range.last, hour, minute)
            }
        }

        // 时长（与精确时间区间不重叠；循环内累加）
        val durations = findDurations(body, exact)
        val durationMinutes = durations.takeIf { it.isNotEmpty() }
            ?.sumOf { it.second }
            ?.coerceIn(5, 360)

        // 标题：剔除元语区间后剥离头部动词（guard 防误剥；剥空回退原输入）
        val removed = buildList {
            periodSpan?.let { add(it) }
            exact?.let { add(it.start..it.end) }
        } + durations.map { it.first }
        val rawTitle = stripRanges(body, removed).trim()
        val title = stripVerb(rawTitle).ifEmpty { s }

        val sticky = periodLabel != null && periodLabel in STICKY_PERIODS

        // 精确时刻 + 顺延（下一次发生）
        var exactMinuteOfDay: Int? = null
        var exactDayOffsetOut: Int? = null
        var exactAt: Long? = null
        if (exact != null) {
            val hour = if (sticky && exact.hour < 12) exact.hour + 12 else exact.hour
            exactMinuteOfDay = hour * 60 + exact.minute
            var at = dayStartOf(now) + dayOffset * DAY_MS + exactMinuteOfDay * 60_000L
            if (at <= now) at += DAY_MS
            exactAt = at
            exactDayOffsetOut = ((at - dayStartOf(now)) / DAY_MS).toInt()
        }

        // 时段窗口 + 顺延（整体已过 → 次日，部分已过保持）
        var windowStartAt: Long? = null
        var windowEndAt: Long? = null
        if (periodRange != null) {
            var startAt = dayStartOf(now) + dayOffset * DAY_MS + periodRange.first * 60_000L
            var endAt = dayStartOf(now) + dayOffset * DAY_MS + periodRange.second * 60_000L
            if (endAt <= now) {
                startAt += DAY_MS
                endAt += DAY_MS
            }
            windowStartAt = startAt
            windowEndAt = endAt
        }

        val recognized = durationMinutes != null || exact != null ||
            (periodLabel != null && rawTitle.isNotEmpty() && rawTitle[0] in VERBS)
        if (!recognized) return QuickInputParse(title = s, recognized = false)

        return QuickInputParse(
            title = title,
            durationMinutes = durationMinutes,
            periodLabel = periodLabel,
            windowStartAt = windowStartAt,
            windowEndAt = windowEndAt,
            exactMinute = exactMinuteOfDay,
            exactDayOffset = exactDayOffsetOut,
            exactAt = exactAt,
            recognized = true
        )
    }

    /** 自然日 00:00（与 TaskHistory.dayStartOf 相同的 Calendar 日界定义；此处独立实现避免跨对象依赖）。 */
    private fun dayStartOf(millis: Long): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /** 数字 token → Int；阿拉伯数字直接解析，单字汉字走 CN_DIGIT，无法解析返回 null。 */
    private fun toNumber(raw: String): Int? {
        if (raw.isEmpty()) return null
        if (raw.length == 1 && raw[0] in CN_DIGIT) return CN_DIGIT[raw[0]]
        return raw.toIntOrNull()
    }

    private fun findDurations(body: String, exact: ExactMatch?): List<Pair<IntRange, Int>> {
        val excludeStart = exact?.start ?: 0
        val excludeEnd = exact?.end ?: 0
        val candidates = mutableListOf<Pair<IntRange, Int>>()
        for (pattern in DURATION_PATTERNS) {
            for (match in pattern.findAll(body)) {
                val range = match.range
                if (range.last < excludeStart || range.first >= excludeEnd) {
                    val value = when (pattern) {
                        DURATION_PATTERNS[2] -> 30
                        DURATION_PATTERNS[0] -> toNumber(match.groupValues[1])?.let { it * 60 + 30 }
                        DURATION_PATTERNS[1] -> toNumber(match.groupValues[1])?.let { it * 60 }
                        else -> toNumber(match.groupValues[1])
                    }
                    if (value != null) candidates += range to value
                }
            }
        }
        // 区间重叠剔除：起点升序、长度降序，依次取与已选不交者（如"两个半小时"不再取内嵌的"半小时"）
        val sorted = candidates.sortedWith(compareBy({ it.first.first }, { -(it.first.last - it.first.first) }))
        return buildList {
            for (candidate in sorted) {
                if (none { rangesOverlap(it.first, candidate.first) }) add(candidate)
            }
        }
    }

    private fun rangesOverlap(first: IntRange, second: IntRange): Boolean =
        first.first <= second.last && second.first <= first.last

    /** 剔除 [ranges]（已排序或未排序均可）覆盖的字符，返回剩余串。 */
    private fun stripRanges(body: String, ranges: List<IntRange>): String {
        val builder = StringBuilder()
        var cursor = 0
        for (range in ranges.sortedBy { it.first }) {
            if (range.first < cursor) continue
            builder.append(body, cursor, range.first)
            cursor = range.last + 1
        }
        if (cursor <= body.length) builder.append(body, cursor, body.length)
        return builder.toString()
    }

    /** 剥离头部一个动词字符（次字命中 guard 时不剥）。 */
    private fun stripVerb(title: String): String {
        if (title.isEmpty()) return title
        val first = title[0]
        if (first !in VERBS) return title
        val second = title.getOrNull(1)
        if (second != null && second in GUARDS) return title
        return title.drop(1).trim()
    }
}
