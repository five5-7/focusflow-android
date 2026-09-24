package com.sakata.focusflow

/** Presentation only: records retain separate IDs until the multi-meeting course model exists. */
internal data class CourseDisplayGroup(val title: String, val meetings: List<Course>)

/** A single visible block; original records remain addressable for edit, deletion and migration. */
internal data class ConnectedCourseSpan(val records: List<Course>) {
    val display: Course get() = records.first().copy(endPeriod = records.last().endPeriod)
}

internal val courseMeetingOrder: Comparator<Course> = compareBy<Course> { it.weekday }
    .thenBy { it.startPeriod }
    .thenBy { it.endPeriod }
    .thenBy { it.id }

internal fun groupCourseMeetings(courses: List<Course>): List<CourseDisplayGroup> =
    courses.sortedWith(courseMeetingOrder)
        .groupBy { it.title.trim() }
        .map { (title, meetings) -> CourseDisplayGroup(title, meetings) }

/** Only consecutive periods with identical meeting attributes form one block. */
internal fun connectedCourseSpans(courses: List<Course>): List<ConnectedCourseSpan> {
    val spans = mutableListOf<ConnectedCourseSpan>()
    courses.sortedWith(courseMeetingOrder).forEach { course ->
        val previous = spans.lastOrNull()
        val last = previous?.records?.lastOrNull()
        if (previous != null && last != null && last.weekday == course.weekday &&
            last.endPeriod + 1 == course.startPeriod &&
            last.title.trim() == course.title.trim() &&
            last.building.trim() == course.building.trim() && last.zone == course.zone &&
            last.needsConfirmation == course.needsConfirmation && last.enabled == course.enabled &&
            last.effectiveFromEpochDay == course.effectiveFromEpochDay &&
            last.effectiveUntilEpochDay == course.effectiveUntilEpochDay
        ) {
            spans[spans.lastIndex] = ConnectedCourseSpan(previous.records + course)
        } else spans += ConnectedCourseSpan(listOf(course))
    }
    return spans
}

/** A compact summary may show course cards only when their periods do not collide. */
internal fun trailingDaysCanCollapse(courses: List<Course>): Boolean =
    courses.size <= 2 && courses.indices.none { index ->
        courses.drop(index + 1).any { other ->
            courses[index].startPeriod <= other.endPeriod && other.startPeriod <= courses[index].endPeriod
        }
    }
