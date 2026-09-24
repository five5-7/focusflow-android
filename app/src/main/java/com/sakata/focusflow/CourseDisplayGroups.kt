package com.sakata.focusflow

/** Presentation only: records retain separate IDs until the multi-meeting course model exists. */
internal data class CourseDisplayGroup(val title: String, val meetings: List<Course>)

internal val courseMeetingOrder: Comparator<Course> = compareBy<Course> { it.weekday }
    .thenBy { it.startPeriod }
    .thenBy { it.endPeriod }
    .thenBy { it.id }

internal fun groupCourseMeetings(courses: List<Course>): List<CourseDisplayGroup> =
    courses.sortedWith(courseMeetingOrder)
        .groupBy { it.title.trim() }
        .map { (title, meetings) -> CourseDisplayGroup(title, meetings) }
