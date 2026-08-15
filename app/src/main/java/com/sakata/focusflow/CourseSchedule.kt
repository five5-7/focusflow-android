package com.sakata.focusflow

data class Course(
    val title: String,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val building: String,
    val zone: CampusZone,
    val needsConfirmation: Boolean = true
)

data class CourseGap(val from: Course, val to: Course, val minutesFree: Int, val travelMinutes: Int, val suggestedStartMinute: Int)

/** Entries read only where the supplied screenshot clearly showed a teaching building. */
object ScreenshotCoursePreview {
    val courses = listOf(
        Course("物理化学", 1, 6, 7, "西1教学楼", CampusZone.WEST_TEACHING),
        Course("大学物理实验", 2, 3, 5, "东4教学楼", CampusZone.EAST_TEACHING),
        Course("有机化学", 2, 6, 7, "西2教学楼", CampusZone.WEST_TEACHING),
        Course("物理化学", 3, 1, 2, "西1教学楼", CampusZone.WEST_TEACHING),
        Course("大学物理", 3, 3, 4, "西2教学楼", CampusZone.WEST_TEACHING),
        Course("军事理论", 3, 6, 7, "北2教学楼", CampusZone.NORTH_TEACHING),
        Course("英语词汇学", 3, 9, 10, "东6教学楼", CampusZone.EAST_TEACHING),
        Course("大学化学实验", 4, 3, 5, "化学实验中心", CampusZone.CHEMISTRY_LABS),
        Course("有机化学", 4, 6, 7, "西2教学楼", CampusZone.WEST_TEACHING),
        Course("大学物理", 5, 1, 2, "西2教学楼", CampusZone.WEST_TEACHING)
    )
}

object CourseGapPlanner {
    // Each teaching period is modeled as 45 minutes; longer breaks are retained in the timetable start times.
    private val periodStarts = listOf(480, 530, 600, 650, 700, 805, 855, 905, 975, 1025, 1130, 1180, 1230)

    fun periodStart(period: Int): Int = periodStarts[period.coerceIn(1, periodStarts.size) - 1]
    fun periodEnd(period: Int): Int = periodStart(period) + 45

    fun gaps(courses: List<Course>, profile: CommuteProfile): List<CourseGap> = courses
        .groupBy { it.weekday }
        .values
        .flatMap { daily ->
            daily.sortedBy { it.startPeriod }.zipWithNext().map { (from, to) ->
                val classEnds = periodStarts[from.endPeriod - 1] + 45
                val nextStarts = periodStarts[to.startPeriod - 1]
                val travel = ZijingangTravel.estimateMinutes(from.zone, to.zone, profile)
                CourseGap(from, to, (nextStarts - classEnds - travel).coerceAtLeast(0), travel, classEnds + travel)
            }
        }
}
