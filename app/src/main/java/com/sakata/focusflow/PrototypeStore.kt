package com.sakata.focusflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Deliberately small offline persistence for the first test build. */
class PrototypeStore(context: Context) {
    private val preferences = context.getSharedPreferences("focusflow", Context.MODE_PRIVATE)

    fun loadTheme(): FocusFlowThemeOption =
        FocusFlowThemeOption.fromStorageKey(preferences.getString("app_theme", null))

    fun saveTheme(theme: FocusFlowThemeOption) {
        preferences.edit().putString("app_theme", theme.storageKey).apply()
    }

    fun loadEnergyLevel(): String = (preferences.getString("energy_level", "正常") ?: "正常").takeIf { it in setOf("偏低", "正常", "充足") } ?: "正常"

    fun saveEnergyLevel(level: String) {
        preferences.edit().putString("energy_level", level).apply()
    }

    fun loadItems(): List<Item> = runCatching {
        val values = JSONArray(preferences.getString("items", "[]") ?: "[]")
        val parsed = List(values.length()) { index ->
            val item = values.getJSONObject(index)
            Item(
                id = item.getLong("id"), title = item.getString("title"), detail = item.getString("detail"), kind = item.getString("kind"),
                done = item.optBoolean("done"), scheduledAt = item.optLong("scheduledAt").takeIf { it > 0 }, dayOnly = item.optBoolean("dayOnly"),
                goalId = item.optLong("goalId").takeIf { it > 0 }, completionLevel = item.optString("completionLevel"), completedAt = item.optLong("completedAt").takeIf { it > 0 },
                durationMinutes = item.optInt("durationMinutes", 60).coerceIn(5, 360), windowStartAt = item.optLong("windowStartAt").takeIf { it > 0 }, windowEndAt = item.optLong("windowEndAt").takeIf { it > 0 }
            )
        }
        val firstByOriginalId = mutableMapOf<Long, Item>()
        val assignedIds = mutableSetOf<Long>()
        val normalized = parsed.map { item ->
            val first = firstByOriginalId.putIfAbsent(item.id, item)
            if (first == null && item.id > 0 && assignedIds.add(item.id)) {
                item
            } else {
                var replacementId = newItemId()
                while (!assignedIds.add(replacementId)) replacementId = newItemId()
                val accidentallyCompletedTogether = first?.let {
                    it.done && item.done && it.completedAt != null && it.completedAt == item.completedAt
                } == true
                item.copy(
                    id = replacementId,
                    done = if (accidentallyCompletedTogether) false else item.done,
                    completionLevel = if (accidentallyCompletedTogether) "" else item.completionLevel,
                    completedAt = if (accidentallyCompletedTogether) null else item.completedAt
                )
            }
        }
        if (normalized != parsed) saveItems(normalized)
        normalized
    }.getOrDefault(emptyList())

    fun saveItems(items: List<Item>) {
        val values = JSONArray()
        items.forEach { item -> values.put(JSONObject().apply {
            put("id", item.id); put("title", item.title); put("detail", item.detail); put("kind", item.kind); put("done", item.done); put("scheduledAt", item.scheduledAt ?: 0); put("dayOnly", item.dayOnly); put("goalId", item.goalId ?: 0); put("completionLevel", item.completionLevel); put("completedAt", item.completedAt ?: 0); put("durationMinutes", item.durationMinutes); put("windowStartAt", item.windowStartAt ?: 0); put("windowEndAt", item.windowEndAt ?: 0)
        }) }
        preferences.edit().putString("items", values.toString()).apply()
    }

    fun saveSession(session: ActivitySession) {
        val sessions = loadSessions().filterNot { it.id == session.id } + session
        val values = JSONArray()
        sessions.takeLast(50).forEach { value -> values.put(JSONObject().apply {
            put("id", value.id)
            put("name", value.name)
            put("category", value.category)
            put("plannedStartAt", value.plannedStartAt)
            put("actualStartAt", value.actualStartAt)
            put("endsAt", value.endsAt)
            put("nextStep", value.nextStep)
            put("status", value.status)
            put("extensionCount", value.extensionCount)
            put("extensionReason", value.extensionReason)
            put("actualEndAt", value.actualEndAt ?: 0)
            put("endChoice", value.endChoice)
        }) }
        preferences.edit().putString("sessions", values.toString()).apply()
    }

    fun updateSession(id: Long, status: String, endsAt: Long? = null) {
        val current = loadSessions().firstOrNull { it.id == id } ?: return
        saveSession(current.copy(status = status, endsAt = endsAt ?: current.endsAt))
    }

    fun finishSession(id: Long, status: String, choice: String, endedAt: Long = System.currentTimeMillis()) {
        val current = loadSessions().firstOrNull { it.id == id } ?: return
        saveSession(current.copy(status = status, actualEndAt = endedAt, endChoice = choice))
    }

    fun extendSession(id: Long, minutes: Int, reason: String = ""): ActivitySession? {
        val current = loadSessions().firstOrNull { it.id == id } ?: return null
        if (current.extensionCount >= loadActivityReminderSettings().maxExtensions) return null
        val extended = current.copy(
            endsAt = System.currentTimeMillis() + minutes.coerceIn(1, 180) * 60_000L,
            status = ActivitySession.STATUS_EXTENDED,
            extensionCount = current.extensionCount + 1,
            extensionReason = reason,
            actualEndAt = null,
            endChoice = ""
        )
        saveSession(extended)
        return extended
    }

    fun markSessionAwaitingConfirmation(id: Long): ActivitySession? {
        val current = loadSessions().firstOrNull { it.id == id } ?: return null
        if (!current.isOpen()) return current
        val pending = current.copy(status = ActivitySession.STATUS_AWAITING_CONFIRMATION)
        saveSession(pending)
        return pending
    }

    fun loadLatestActiveSession(): ActivitySession? = loadSessions().lastOrNull(ActivitySession::isOpen)

    fun findActivitySession(id: Long): ActivitySession? = loadSessions().firstOrNull { it.id == id }

    fun loadRecentActivitySessions(limit: Int = 20): List<ActivitySession> = loadSessions().takeLast(limit.coerceIn(1, 50)).reversed()

    fun loadActivityReminderSettings(): ActivityReminderSettings = ActivityReminderSettings(
        notificationsEnabled = preferences.getBoolean("activity_notifications", true),
        previewMinutes = preferences.getInt("activity_preview_minutes", 10).coerceIn(0, 60),
        maxExtensions = preferences.getInt("activity_max_extensions", 3).coerceIn(0, 10),
        strongerEndReminder = preferences.getBoolean("activity_stronger_end_reminder", true)
    )

    fun saveActivityReminderSettings(settings: ActivityReminderSettings) {
        preferences.edit()
            .putBoolean("activity_notifications", settings.notificationsEnabled)
            .putInt("activity_preview_minutes", settings.previewMinutes)
            .putInt("activity_max_extensions", settings.maxExtensions)
            .putBoolean("activity_stronger_end_reminder", settings.strongerEndReminder)
            .apply()
    }

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
        eBikeBattery = preferences.getString("ebike_battery", "未知") ?: "未知",
        routeCalibrations = runCatching {
            val values = JSONObject(preferences.getString("route_calibrations", "{}") ?: "{}")
            val parsed = mutableMapOf<String, Int>()
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                values.optInt(key).takeIf { it in 1..180 }?.let { parsed[key] = it }
            }
            parsed
        }.getOrDefault(emptyMap())
    )

    fun saveCommuteProfile(profile: CommuteProfile) {
        preferences.edit()
            .putBoolean("commute_enabled", profile.enabled)
            .putInt("commute_one_way_minutes", profile.oneWayMinutes)
            .putString("campus_mode", profile.campusMode)
            .putInt("building_buffer_minutes", profile.buildingBufferMinutes)
            .putString("ebike_battery", profile.eBikeBattery)
            .putString("route_calibrations", JSONObject(profile.routeCalibrations).toString())
            .apply()
    }

    fun loadCampusLifeEnabled(): Boolean = preferences.getBoolean("campus_life_enabled", true)

    fun saveCampusLifeEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("campus_life_enabled", enabled).apply()
    }

    fun loadCampusMapPackage(): CampusMapPackage? = runCatching {
        preferences.getString("campus_map_package", null)?.let(CampusMapPackageCodec::parse)
    }.getOrNull()

    fun saveCampusMapPackage(mapPackage: CampusMapPackage?) {
        preferences.edit().apply {
            if (mapPackage == null) remove("campus_map_package")
            else putString("campus_map_package", CampusMapPackageCodec.encode(mapPackage))
        }.apply()
    }

    fun loadCurrentCampusPlace(): String? = preferences.getString("current_campus_place", null)

    fun saveCurrentCampusPlace(placeName: String?) {
        preferences.edit().apply {
            if (placeName.isNullOrBlank()) remove("current_campus_place")
            else putString("current_campus_place", placeName)
        }.apply()
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
            val name = item.getString("name")
            val id = item.getLong("id")
            ActivitySession(
                id = id,
                name = name,
                category = item.optString("category", name),
                plannedStartAt = item.optLong("plannedStartAt", id),
                actualStartAt = item.optLong("actualStartAt", id),
                endsAt = item.getLong("endsAt"),
                nextStep = item.optString("nextStep"),
                status = item.optString("status", ActivitySession.STATUS_ACTIVE),
                extensionCount = item.optInt("extensionCount"),
                extensionReason = item.optString("extensionReason"),
                actualEndAt = item.optLong("actualEndAt").takeIf { it > 0 },
                endChoice = item.optString("endChoice")
            )
        }
    }.getOrDefault(emptyList())
}
