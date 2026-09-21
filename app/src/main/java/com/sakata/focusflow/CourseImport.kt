package com.sakata.focusflow

/**
 * 课程数据来自哪里。来源只描述获取方式；去重、冲突检查和逐项确认由统一导入流程完成。
 */
enum class CourseImportSource(val label: String) {
    VISION_SCREENSHOT("课表截图"),
    ZJU_TIMETABLE("浙江大学教务"),
    SCHOOL_EXPORT("学校课表"),
    MANUAL("手动新增")
}

/**
 * 任意导入器的统一输出。后续接入学校导出文件或登录后的页面解析时，只需产生此结构。
 */
data class CourseImportBatch(
    val source: CourseImportSource,
    val courses: List<Course>,
    val newPlaces: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

/** 导入边界统一收紧，避免不同来源把无效或已确认状态的数据直接写进课表。 */
object CourseImportPolicy {
    fun prepare(batch: CourseImportBatch): CourseImportBatch {
        val courses = batch.courses.mapNotNull { course ->
            val title = course.title.trim()
            val building = course.building.trim().ifBlank { "地点待确认" }
            if (title.isBlank() || course.weekday !in 1..7 || course.startPeriod !in 1..20) {
                null
            } else {
                course.copy(
                    title = title,
                    startPeriod = course.startPeriod,
                    endPeriod = course.endPeriod.coerceIn(course.startPeriod, 20),
                    building = building,
                    needsConfirmation = true
                )
            }
        }.distinctBy { listOf(it.weekday, it.startPeriod, it.endPeriod, it.title) }

        return batch.copy(
            courses = courses,
            newPlaces = batch.newPlaces.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(50),
            warnings = batch.warnings.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(10)
        )
    }
}

/** 同一星期与完整节次完全相同却出现不同课程时，必须逐门打开编辑器核对后才能确认。 */
internal object CourseConfirmationSafety {
    fun blockedDirectConfirmationIds(courses: List<Course>): Set<Long> = courses
        .filter { it.enabled }
        .groupBy { Triple(it.weekday, it.startPeriod, it.endPeriod) }
        .values
        .filter { group -> group.map { it.title.trim() }.distinct().size >= 2 }
        .flatten()
        .filter { it.needsConfirmation }
        .mapTo(mutableSetOf()) { it.id }

    fun isDirectConfirmationBlocked(course: Course, courses: List<Course>): Boolean =
        course.id in blockedDirectConfirmationIds(courses)
}


internal data class SchoolCourseSync(
    val courses: List<Course>,
    val addedCount: Int,
    val updatedCount: Int,
    val unchangedCount: Int
)

/**
 * 官方课表同步：唯一匹配时更新已有记录并保留其 ID、确认状态、启停与生效期；
 * 无法唯一匹配的课程只新增为待确认，绝不猜测覆盖同名多节课程。
 */
internal fun syncSchoolCourses(existing: List<Course>, imported: List<Course>): SchoolCourseSync {
    val output = existing.toMutableList()
    val usedIndexes = mutableSetOf<Int>()
    val incomingGroups = imported.groupingBy { it.title.trim() to it.weekday }.eachCount()
    var added = 0
    var updated = 0
    var unchanged = 0

    imported.forEach { incoming ->
        val title = incoming.title.trim()
        val exact = output.indices.filter { index ->
            index !in usedIndexes && output[index].title.trim() == title &&
                output[index].weekday == incoming.weekday &&
                output[index].startPeriod == incoming.startPeriod &&
                output[index].endPeriod == incoming.endPeriod
        }
        val sameDay = output.indices.filter { index ->
            index !in usedIndexes && output[index].title.trim() == title &&
                output[index].weekday == incoming.weekday
        }
        val match = when {
            exact.size == 1 -> exact.single()
            exact.isEmpty() && sameDay.size == 1 && incomingGroups[title to incoming.weekday] == 1 -> sameDay.single()
            else -> null
        }
        if (match == null) {
            output += incoming.copy(needsConfirmation = true)
            added += 1
        } else {
            usedIndexes += match
            val previous = output[match]
            val replacement = previous.copy(
                title = title,
                weekday = incoming.weekday,
                startPeriod = incoming.startPeriod,
                endPeriod = incoming.endPeriod,
                building = incoming.building,
                zone = incoming.zone
            )
            if (replacement == previous) unchanged += 1 else {
                output[match] = replacement
                updated += 1
            }
        }
    }
    return SchoolCourseSync(output, added, updated, unchanged)
}
