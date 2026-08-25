package com.sakata.focusflow

internal data class GapRecommendation(
    val title: String,
    val reason: String,
    val goal: Goal?,
    val flexibleItem: Item?
)

internal data class GapPlan(
    val recommendation: GapRecommendation,
    val weekday: Int,
    val startMinute: Int,
    val minutes: Int
)

/** 只按明确关键词提供粗粒度地点提示；不声称已定位或完成地图检索。 */
internal fun locationHintFor(text: String): String? {
    val normalized = text.lowercase()
    return when {
        listOf("游泳").any { normalized.contains(it) } -> "游泳馆"
        listOf("实验").any { normalized.contains(it) } -> "实验楼"
        listOf("讨论", "小组", "开会", "会议").any { normalized.contains(it) } -> "研讨室/教室"
        listOf("跑步", "健身", "锻炼", "球", "操场", "跳绳", "骑行", "运动", "瑜伽")
            .any { normalized.contains(it) } -> "操场/体育馆"
        listOf("图书馆", "自习").any { normalized.contains(it) } -> "图书馆"
        listOf("学习", "复习", "背", "刷题", "看书", "阅读", "作业", "单词", "论文", "课程", "上课")
            .any { normalized.contains(it) } -> "图书馆/教学楼"
        listOf("游戏", "娱乐", "追剧", "视频", "看剧").any { normalized.contains(it) } -> "宿舍"
        else -> null
    }
}
