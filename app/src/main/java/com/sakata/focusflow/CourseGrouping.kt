package com.sakata.focusflow

/** Local, explicitly confirmed parentage; an external candidate key never groups rows by itself. */
internal object CourseGrouping {
    fun confirmImported(courses: List<Course>, meetingIds: Set<Long>): List<Course>? {
        val selected = courses.filter { it.id in meetingIds }
        if (selected.size < 2 || selected.size != meetingIds.size ||
            selected.any { !it.needsConfirmation || !it.enabled || it.courseId != it.id } ||
            selected.any { candidate -> courses.any { other ->
                other.id !in meetingIds && other.enabled && !other.needsConfirmation &&
                    coursesOverlap(candidate, other)
            } }) return null
        val first = selected.first()
        val provenance = Triple(first.externalSchoolYearCode, first.externalTermCode,
            first.externalSelectionKeyCandidate)
        if (provenance.toList().any(String::isBlank) || selected.any {
                Triple(it.externalSchoolYearCode, it.externalTermCode,
                    it.externalSelectionKeyCandidate) != provenance || it.title != first.title
            } || overlaps(selected)) return null
        val parentId = selected.minOf(Course::id)
        return courses.map { if (it.id in meetingIds) it.copy(courseId = parentId,
            needsConfirmation = false) else it }
    }

    fun linkConfirmed(courses: List<Course>, meetingIds: Set<Long>): List<Course>? {
        val selected = courses.filter { it.id in meetingIds }
        if (selected.size < 2 || selected.size != meetingIds.size ||
            selected.any { it.needsConfirmation }) return null
        val groupIds = selected.mapTo(mutableSetOf(), Course::courseId)
        val members = courses.filter { it.courseId in groupIds }
        val first = members.firstOrNull() ?: return null
        if (members.any { it.needsConfirmation || it.title != first.title } ||
            overlaps(members) || provenanceConflict(members)) return null
        val parentId = groupIds.min()
        return courses.map { if (it.courseId in groupIds) it.copy(courseId = parentId) else it }
    }

    fun separate(courses: List<Course>, meetingId: Long): List<Course>? {
        val selected = courses.singleOrNull { it.id == meetingId } ?: return null
        val group = courses.filter { it.courseId == selected.courseId }
        if (group.size < 2) return null
        val remainingParent = if (selected.courseId == meetingId)
            group.filterNot { it.id == meetingId }.minOf(Course::id) else selected.courseId
        return courses.map { when {
            it.id == meetingId -> it.copy(courseId = it.id)
            it.courseId == selected.courseId -> it.copy(courseId = remainingParent)
            else -> it
        } }
    }

    private fun overlaps(members: List<Course>): Boolean = members.indices.any { i ->
        ((i + 1) until members.size).any { j -> coursesOverlap(members[i], members[j]) }
    }

    private fun provenanceConflict(members: List<Course>): Boolean {
        val scopes = members.mapNotNull { course ->
            if (course.externalSchoolYearCode.isBlank() || course.externalTermCode.isBlank()) null
            else course.externalSchoolYearCode to course.externalTermCode
        }.distinct()
        return scopes.size > 1
    }
}
