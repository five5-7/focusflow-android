package com.sakata.focusflow

internal enum class PlanPage(val title: String) {
    COURSES("课程"),
    GAPS("空挡建议"),
    GOALS("目标与执行"),
    REVIEW("本周回顾"),
    PAUSED("暂停项目")
}

internal data class PlanHubSnapshot(
    val confirmedCourseCount: Int = 0,
    val pendingCourseCount: Int = 0,
    val conflictingCourseCount: Int = 0,
    val gapCount: Int = 0,
    val goalCount: Int = 0,
    val resourceCount: Int = 0,
    val completedThisWeek: Int = 0,
    val weeklyTarget: Int = 0,
    val pausedCount: Int = 0
)

internal object PlanHubSummary {
    fun entries(snapshot: PlanHubSnapshot): List<Pair<PlanPage, String>> = listOf(
        PlanPage.COURSES to courseSummary(snapshot),
        PlanPage.GAPS to if (snapshot.gapCount == 0) {
            "暂无可用空挡"
        } else {
            "${snapshot.gapCount} 段可用空挡"
        },
        PlanPage.GOALS to if (snapshot.goalCount == 0) {
            "尚未创建目标 · ${snapshot.resourceCount} 项教程资料"
        } else {
            "${snapshot.goalCount} 个目标 · ${snapshot.resourceCount} 项教程资料"
        },
        PlanPage.REVIEW to if (snapshot.goalCount == 0) {
            "有目标后生成建议"
        } else {
            "本周 ${snapshot.completedThisWeek} / ${snapshot.weeklyTarget} 次 · 低压力建议"
        },
        PlanPage.PAUSED to if (snapshot.pausedCount == 0) "暂无" else "${snapshot.pausedCount} 项"
    )

    private fun courseSummary(snapshot: PlanHubSnapshot): String {
        val counts = "${snapshot.confirmedCourseCount} 门已确认 · " +
            "${snapshot.pendingCourseCount} 门待确认"
        return if (snapshot.conflictingCourseCount > 0) {
            "⚠ ${snapshot.conflictingCourseCount} 门冲突 · $counts"
        } else {
            counts
        }
    }
}
