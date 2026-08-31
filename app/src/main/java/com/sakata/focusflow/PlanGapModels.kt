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

internal fun recommendForWindow(goals: List<Goal>, items: List<Item>, minutes: Int, store: PrototypeStore, weekday: Int, startMinute: Int): GapRecommendation? {
    val goal = goals.filter { g -> GoalPlanner.completedThisWeek(g) < g.weeklyTarget && g.durationMinutes <= minutes }
        .sortedWith(compareByDescending<Goal> { PlanLearning.completionRate(store, weekday, startMinute / 60) ?: -1f }
            .thenByDescending { it.weeklyTarget - GoalPlanner.completedThisWeek(it) }
            .thenByDescending { it.durationMinutes })
        .firstOrNull()
    if (goal != null) {
        val remaining = goal.weeklyTarget - GoalPlanner.completedThisWeek(goal)
        val rate = PlanLearning.completionRate(store, weekday, startMinute / 60)
        val rateNote = if (rate != null && rate >= 0.6f) " · 该时段完成率较高" else ""
        return GapRecommendation(goal.title, "目标还剩 $remaining 次 · 每次 ${goal.durationMinutes} 分钟$rateNote", goal, null)
    }
    val flexible = items.filter { it.kind == "任务" && it.scheduledAt == null && it.durationMinutes <= minutes }
        .sortedByDescending { it.durationMinutes }.firstOrNull()
    if (flexible != null) return GapRecommendation(flexible.title, "弹性任务 · 约 ${flexible.durationMinutes} 分钟", null, flexible)
    return null
}
