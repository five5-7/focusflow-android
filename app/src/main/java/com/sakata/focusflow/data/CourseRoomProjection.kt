package com.sakata.focusflow.data

import com.sakata.focusflow.Course

/** A parent is stored once; meeting IDs and their source order remain independent. */
internal fun List<Course>.toCourseParentEntities(): List<CourseEntity> {
    require(map(Course::id).distinct().size == size && all { it.id > 0 && it.courseId > 0 })
    val meetingsById = associateBy(Course::id)
    require(all { row -> meetingsById[row.courseId]?.courseId == null ||
        meetingsById[row.courseId]?.courseId == row.courseId }) { "parent id belongs to another course" }
    return withIndex().groupBy { it.value.courseId }.map { (parentId, rows) ->
        val first = rows.first()
        require(rows.all { it.value.title == first.value.title &&
            it.value.needsConfirmation == first.value.needsConfirmation }) {
            "course parent fields disagree"
        }
        CourseEntity.fromLegacy(first.value, first.index)
    }
}

internal fun mapCourseRules(
    parents: List<CourseEntity>, rules: List<CourseMeetingRuleEntity>
): List<Course>? {
    val byId = parents.associateBy(CourseEntity::id)
    val rulesById = rules.associateBy(CourseMeetingRuleEntity::id)
    if (byId.size != parents.size || rulesById.size != rules.size ||
        rules.any { it.id <= 0 || it.courseId <= 0 || it.courseId !in byId ||
            it.weekday !in 1..7 || it.startPeriod !in 1..20 || it.endPeriod !in it.startPeriod..20 } ||
        rules.any { rulesById[it.courseId]?.courseId != null &&
            rulesById[it.courseId]?.courseId != it.courseId } ||
        parents.any { parent -> rules.none { it.courseId == parent.id } }
    ) return null
    return rules.map { rule -> rule.toLegacy(byId.getValue(rule.courseId)) }
}
