package com.sakata.focusflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Deliberately small offline persistence for the first test build. */
class PrototypeStore(context: Context) {
    private val preferences = context.getSharedPreferences("focusflow", Context.MODE_PRIVATE)

    fun loadItems(): List<Item> = runCatching {
        val values = JSONArray(preferences.getString("items", "[]") ?: "[]")
        List(values.length()) { index ->
            val item = values.getJSONObject(index)
            Item(item.getLong("id"), item.getString("title"), item.getString("detail"), item.getString("kind"), item.optBoolean("done"), item.optLong("scheduledAt").takeIf { it > 0 }, item.optBoolean("dayOnly"), item.optLong("goalId").takeIf { it > 0 }, item.optString("completionLevel"))
        }
    }.getOrDefault(emptyList())

    fun saveItems(items: List<Item>) {
        val values = JSONArray()
        items.forEach { item -> values.put(JSONObject().apply {
            put("id", item.id); put("title", item.title); put("detail", item.detail); put("kind", item.kind); put("done", item.done); put("scheduledAt", item.scheduledAt ?: 0); put("dayOnly", item.dayOnly); put("goalId", item.goalId ?: 0); put("completionLevel", item.completionLevel)
        }) }
        preferences.edit().putString("items", values.toString()).apply()
    }

    fun saveSession(session: ActivitySession) {
        val sessions = loadSessions().filterNot { it.id == session.id } + session
        val values = JSONArray()
        sessions.takeLast(50).forEach { value -> values.put(JSONObject().apply {
            put("id", value.id); put("name", value.name); put("endsAt", value.endsAt); put("status", value.status)
        }) }
        preferences.edit().putString("sessions", values.toString()).apply()
    }

    fun updateSession(id: Long, status: String, endsAt: Long? = null) {
        val current = loadSessions().firstOrNull { it.id == id } ?: return
        saveSession(current.copy(status = status, endsAt = endsAt ?: current.endsAt))
    }

    fun loadLatestActiveSession(): ActivitySession? = loadSessions().lastOrNull { it.status == "active" }

    fun addReplanItem(activityName: String) {
        val updated = listOf(Item(title = "重新安排：$activityName", detail = "刚才跳过了本次活动；可以改期、缩短或暂停", kind = "收集箱")) + loadItems()
        saveItems(updated)
    }

    fun updateItem(id: Long, transform: (Item) -> Item) {
        saveItems(loadItems().map { if (it.id == id) transform(it) else it })
    }

    fun findItem(id: Long): Item? = loadItems().firstOrNull { it.id == id }

    fun recoverMissedGoalTasks(): List<Item> {
        val cutoff = System.currentTimeMillis() - 2 * 60 * 60_000L
        val recovered = loadItems().map { item ->
            if (item.goalId != null && item.kind == "任务" && !item.done && (item.scheduledAt ?: Long.MAX_VALUE) < cutoff) {
                item.copy(title = if (item.title.startsWith("重新安排：")) item.title else "重新安排：${item.title}", kind = "收集箱", detail = "上次目标安排未确认；可改期、缩短、暂停或放弃", scheduledAt = null)
            } else item
        }
        if (recovered != loadItems()) saveItems(recovered)
        return recovered
    }

    fun loadCommuteProfile(): CommuteProfile = CommuteProfile(
        enabled = preferences.getBoolean("commute_enabled", false),
        oneWayMinutes = preferences.getInt("commute_one_way_minutes", 30),
        campusMode = preferences.getString("campus_mode", "步行") ?: "步行",
        buildingBufferMinutes = preferences.getInt("building_buffer_minutes", 3),
        eBikeBattery = preferences.getString("ebike_battery", "未知") ?: "未知"
    )

    fun saveCommuteProfile(profile: CommuteProfile) {
        preferences.edit()
            .putBoolean("commute_enabled", profile.enabled)
            .putInt("commute_one_way_minutes", profile.oneWayMinutes)
            .putString("campus_mode", profile.campusMode)
            .putInt("building_buffer_minutes", profile.buildingBufferMinutes)
            .putString("ebike_battery", profile.eBikeBattery)
            .apply()
    }

    fun hasCourseSetup(): Boolean = preferences.getBoolean("course_setup_done", false)

    fun loadCourses(): List<Course> = runCatching {
        val values = JSONArray(preferences.getString("courses", "[]") ?: "[]")
        List(values.length()) { index ->
            val course = values.getJSONObject(index)
            Course(
                title = course.getString("title"), weekday = course.getInt("weekday"), startPeriod = course.getInt("startPeriod"), endPeriod = course.getInt("endPeriod"),
                building = course.getString("building"), zone = CampusZone.valueOf(course.getString("zone")), needsConfirmation = course.optBoolean("needsConfirmation", false)
            )
        }
    }.getOrDefault(emptyList())

    fun saveCourses(courses: List<Course>) {
        val values = JSONArray()
        courses.forEach { course -> values.put(JSONObject().apply {
            put("title", course.title); put("weekday", course.weekday); put("startPeriod", course.startPeriod); put("endPeriod", course.endPeriod)
            put("building", course.building); put("zone", course.zone.name); put("needsConfirmation", course.needsConfirmation)
        }) }
        preferences.edit().putBoolean("course_setup_done", true).putString("courses", values.toString()).apply()
    }

    fun loadGoals(): List<Goal> = runCatching {
        val values = JSONArray(preferences.getString("goals", "[]") ?: "[]")
        List(values.length()) { index ->
            val goal = values.getJSONObject(index)
            Goal(goal.getLong("id"), goal.getString("title"), goal.getInt("weeklyTarget"), goal.getInt("durationMinutes"), goal.optString("metricType", "时长"), goal.optString("metricTarget"), goal.optString("minimumVersion"), goal.optString("resourceTitle"), goal.optString("resourceUnit"), goal.optInt("completedThisWeek"), goal.optInt("minimumCompletionsThisWeek"), goal.optLong("completionWeekKey", GoalPlanner.currentWeekKey()), goal.optString("desiredOutcome"))
        }
    }.getOrDefault(emptyList())

    fun saveGoals(goals: List<Goal>) {
        val values = JSONArray()
        goals.forEach { goal -> values.put(JSONObject().apply {
            put("id", goal.id); put("title", goal.title); put("weeklyTarget", goal.weeklyTarget); put("durationMinutes", goal.durationMinutes); put("metricType", goal.metricType); put("metricTarget", goal.metricTarget); put("minimumVersion", goal.minimumVersion); put("resourceTitle", goal.resourceTitle); put("resourceUnit", goal.resourceUnit); put("completedThisWeek", goal.completedThisWeek); put("minimumCompletionsThisWeek", goal.minimumCompletionsThisWeek); put("completionWeekKey", goal.completionWeekKey); put("desiredOutcome", goal.desiredOutcome)
        }) }
        preferences.edit().putString("goals", values.toString()).apply()
    }

    fun markGoalCompleted(goalId: Long, minimum: Boolean = false) {
        val key = GoalPlanner.currentWeekKey()
        saveGoals(loadGoals().map { goal -> if (goal.id != goalId) goal else if (goal.completionWeekKey == key) {
            if (minimum) goal.copy(minimumCompletionsThisWeek = goal.minimumCompletionsThisWeek + 1) else goal.copy(completedThisWeek = goal.completedThisWeek + 1)
        } else if (minimum) goal.copy(minimumCompletionsThisWeek = 1, completionWeekKey = key) else goal.copy(completedThisWeek = 1, minimumCompletionsThisWeek = 0, completionWeekKey = key) })
    }

    fun loadResources(): List<LearningResource> = runCatching {
        val values = JSONArray(preferences.getString("resources", "[]") ?: "[]")
        List(values.length()) { index ->
            val resource = values.getJSONObject(index)
            LearningResource(resource.getLong("id"), resource.getString("title"), resource.optString("url"), resource.optBoolean("selected"))
        }
    }.getOrDefault(emptyList())

    fun saveResources(resources: List<LearningResource>) {
        val values = JSONArray()
        resources.forEach { resource -> values.put(JSONObject().apply {
            put("id", resource.id); put("title", resource.title); put("url", resource.url); put("selected", resource.selected)
        }) }
        preferences.edit().putString("resources", values.toString()).apply()
    }

    fun loadFeedback(): List<TaskFeedback> = runCatching {
        val values = JSONArray(preferences.getString("feedback", "[]") ?: "[]")
        List(values.length()) { index ->
            val feedback = values.getJSONObject(index)
            TaskFeedback(feedback.getLong("id"), feedback.getLong("goalId"), feedback.getString("completionLevel"), feedback.getString("difficulty"), feedback.getString("barrier"), feedback.getLong("createdAt"))
        }
    }.getOrDefault(emptyList())

    fun addFeedback(feedback: TaskFeedback) {
        val all = (loadFeedback() + feedback).takeLast(200)
        val values = JSONArray()
        all.forEach { value -> values.put(JSONObject().apply {
            put("id", value.id); put("goalId", value.goalId); put("completionLevel", value.completionLevel); put("difficulty", value.difficulty); put("barrier", value.barrier); put("createdAt", value.createdAt)
        }) }
        preferences.edit().putString("feedback", values.toString()).apply()
    }

    fun loadImprovementNotes(): List<ImprovementNote> = runCatching {
        val values = JSONArray(preferences.getString("improvement_notes", "[]") ?: "[]")
        List(values.length()) { index ->
            val note = values.getJSONObject(index)
            ImprovementNote(note.getLong("id"), note.getString("text"), note.getLong("createdAt"))
        }
    }.getOrDefault(emptyList())

    fun saveImprovementNotes(notes: List<ImprovementNote>) {
        val values = JSONArray()
        notes.takeLast(100).forEach { note -> values.put(JSONObject().apply {
            put("id", note.id); put("text", note.text); put("createdAt", note.createdAt)
        }) }
        preferences.edit().putString("improvement_notes", values.toString()).apply()
    }

    fun loadRoadmapSelections(): Set<String> = preferences.getStringSet("roadmap_selections", emptySet()) ?: emptySet()
    fun saveRoadmapSelections(selections: Set<String>) { preferences.edit().putStringSet("roadmap_selections", selections).apply() }

    private fun loadSessions(): List<ActivitySession> = runCatching {
        val values = JSONArray(preferences.getString("sessions", "[]") ?: "[]")
        List(values.length()) { index ->
            val item = values.getJSONObject(index)
            ActivitySession(item.getLong("id"), item.getString("name"), item.getLong("endsAt"), item.getString("status"))
        }
    }.getOrDefault(emptyList())
}
