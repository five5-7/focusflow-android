package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject

/**
 * Stable JSON boundary for goal and learning-resource data written by FocusFlow 6.1 and earlier.
 * Keep field defaults here so UI refactors cannot accidentally change the on-device data contract.
 */
object StoredGoalsCodec {
    fun decodeGoals(raw: String, defaultWeekKey: Long = GoalPlanner.currentWeekKey()): List<Goal> = runCatching {
        val values = JSONArray(raw.ifBlank { "[]" })
        List(values.length()) { index ->
            val goal = values.getJSONObject(index)
            Goal(
                id = goal.getLong("id"),
                title = goal.getString("title"),
                weeklyTarget = goal.getInt("weeklyTarget"),
                durationMinutes = goal.getInt("durationMinutes"),
                metricType = goal.optString("metricType", "时长"),
                metricTarget = goal.optString("metricTarget", ""),
                minimumVersion = goal.optString("minimumVersion", ""),
                resourceTitle = goal.optString("resourceTitle", ""),
                resourceUnit = goal.optString("resourceUnit", ""),
                completedThisWeek = goal.optInt("completedThisWeek", 0),
                minimumCompletionsThisWeek = goal.optInt("minimumCompletionsThisWeek", 0),
                completionWeekKey = goal.optLong("completionWeekKey", defaultWeekKey),
                desiredOutcome = goal.optString("desiredOutcome", ""),
                firstAction = goal.optString("firstAction", "")
            )
        }
    }.getOrDefault(emptyList())

    fun encodeGoals(goals: List<Goal>): String = JSONArray().apply {
        goals.forEach { goal -> put(JSONObject().apply {
            put("id", goal.id)
            put("title", goal.title)
            put("weeklyTarget", goal.weeklyTarget)
            put("durationMinutes", goal.durationMinutes)
            put("metricType", goal.metricType)
            put("metricTarget", goal.metricTarget)
            put("minimumVersion", goal.minimumVersion)
            put("resourceTitle", goal.resourceTitle)
            put("resourceUnit", goal.resourceUnit)
            put("completedThisWeek", goal.completedThisWeek)
            put("minimumCompletionsThisWeek", goal.minimumCompletionsThisWeek)
            put("completionWeekKey", goal.completionWeekKey)
            put("desiredOutcome", goal.desiredOutcome)
            put("firstAction", goal.firstAction)
        }) }
    }.toString()

    fun decodeResources(raw: String): List<LearningResource> = runCatching {
        val values = JSONArray(raw.ifBlank { "[]" })
        List(values.length()) { index ->
            val resource = values.getJSONObject(index)
            LearningResource(
                id = resource.getLong("id"),
                title = resource.getString("title"),
                url = resource.optString("url", ""),
                selected = resource.optBoolean("selected", false),
                summary = resource.optString("summary", "")
            )
        }
    }.getOrDefault(emptyList())

    fun encodeResources(resources: List<LearningResource>): String = JSONArray().apply {
        resources.forEach { resource -> put(JSONObject().apply {
            put("id", resource.id)
            put("title", resource.title)
            put("url", resource.url)
            put("selected", resource.selected)
            put("summary", resource.summary)
        }) }
    }.toString()
}
