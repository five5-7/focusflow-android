package com.sakata.focusflow

/** 长期反馈分析：跨周模式与心态调整建议。数据不足时不给出具体建议（不假装精确）。 */
object FeedbackInsights {
    /** 至少多少条完成反馈才分析。 */
    const val MIN_FEEDBACK = 5
    private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000

    data class LongTermInsight(
        val totalCount: Int,
        val topBarriers: List<Pair<String, Int>>,
        val recentBarriers: List<Pair<String, Int>>,
        val minimumRatio: Float,
        val difficultyCounts: Map<String, Int>,
        val advice: String
    )

    fun analyze(feedback: List<TaskFeedback>, weeks: Int = 8): LongTermInsight? {
        if (feedback.size < MIN_FEEDBACK) return null
        val barriers = feedback.filter { it.barrier != "无" }.groupingBy { it.barrier }.eachCount()
        if (barriers.isEmpty()) return null
        val topBarriers = barriers.entries.sortedByDescending { it.value }.take(2).map { it.key to it.value }
        val recentStart = GoalPlanner.currentWeekKey() - 2 * WEEK_MILLIS
        val recentBarriers = feedback.filter { it.createdAt >= recentStart && it.barrier != "无" }
            .groupingBy { it.barrier }.eachCount().entries.sortedByDescending { it.value }.take(2).map { it.key to it.value }
        val minimumRatio = feedback.count { it.completionLevel == "最低版本" }.toFloat() / feedback.size
        val difficultyCounts = feedback.groupingBy { it.difficulty }.eachCount()
        return LongTermInsight(feedback.size, topBarriers, recentBarriers, minimumRatio, difficultyCounts, adviceText(topBarriers, recentBarriers, minimumRatio))
    }

    private fun adviceText(
        topBarriers: List<Pair<String, Int>>,
        recentBarriers: List<Pair<String, Int>>,
        minimumRatio: Float
    ): String {
        val topBarrier = topBarriers.firstOrNull()?.first ?: return "保持当前安排即可；把每周完成保持在“完成得舒服”的量上。"
        val base = when (topBarrier) {
            "时间不够" -> if (minimumRatio >= 0.3f) "你多次靠最低版本维持：可以把完整版本拆得更小，或接受最低版本作为常态。" else "时间不够较常见：优先安排更短的空档，并保留最低版本兜底。"
            "精力不足" -> "精力不足较常见：把目标放到更早或更轻松的时段，先保证睡眠。"
            "被娱乐打断" -> "被娱乐打断较多：在娱乐开始前先设结束时间，把最低版本作为结束后的过渡任务。"
            "方法不清楚" -> "方法不清楚较多：把教程的一个小章节或练习作为更明确的完成标准。"
            "地点不合适" -> "地点影响较多：优先选择图书馆、宿舍或离下一节课更近的稳定位置。"
            else -> "保持当前安排即可；把每周完成保持在“完成得舒服”的量上。"
        }
        val recentTop = recentBarriers.firstOrNull()
        return if (recentTop != null && recentTop.first != topBarrier && recentTop.second >= 2) {
            "最近更常遇到“${recentTop.first}”：下周可针对这一点做一次小调整。$base"
        } else base
    }
}
