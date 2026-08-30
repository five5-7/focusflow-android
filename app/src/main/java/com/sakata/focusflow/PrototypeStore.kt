package com.sakata.focusflow

import android.content.Context
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/** 自定义主题预设：命名配色存档，用于保存／切换多套自定义配色。 */
data class ThemePreset(val name: String, val colors: FocusFlowThemeColors)

/** Deliberately small offline persistence for the first test build. */
class PrototypeStore(context: Context) {
    private val preferences = context.getSharedPreferences("focusflow", Context.MODE_PRIVATE)

    fun loadTheme(): FocusFlowThemeOption =
        FocusFlowThemeOption.fromStorageKey(preferences.getString("app_theme", null))

    fun saveTheme(theme: FocusFlowThemeOption) {
        preferences.edit().putString("app_theme", theme.storageKey).apply()
    }

    fun loadDarkMode(): Boolean = preferences.getBoolean("dark_mode", false)

    fun saveDarkMode(enabled: Boolean) {
        preferences.edit().putBoolean("dark_mode", enabled).apply()
    }

    fun saveReminderTestScheduled(expectedAt: Long) {
        preferences.edit()
            .putLong("reminder_test_expected_at", expectedAt)
            .remove("reminder_test_delivered_at")
            .apply()
    }

    fun markReminderTestDelivered(deliveredAt: Long = System.currentTimeMillis()) {
        preferences.edit().putLong("reminder_test_delivered_at", deliveredAt).apply()
    }

    fun loadReminderTestProbe(): ReminderTestProbe? {
        val expectedAt = preferences.getLong("reminder_test_expected_at", 0L)
        if (expectedAt <= 0L) return null
        return ReminderTestProbe(
            expectedAt = expectedAt,
            deliveredAt = preferences.getLong("reminder_test_delivered_at", 0L).takeIf { it > 0L }
        )
    }

    /** 自定义主题 5 色（ARGB，全局配色分工）。无自定义记录时返回 null。 */
    fun loadCustomThemeColors(): FocusFlowThemeColors? = runCatching {
        preferences.getString("custom_theme_colors", null)?.let { json ->
            val value = JSONObject(json)
            FocusFlowThemeColors(
                primaryAction = Color(value.getLong("primaryAction")),
                // 旧存档（3.9.10 前）没有这些字段：回退 OCEAN 种子值。
                secondary = Color(value.optLong("secondary", 0xFF5C4B9A)),
                accent = Color(value.getLong("accent")),
                schedule = Color(value.getLong("schedule")),
                neutral = Color(value.getLong("neutral")),
                warning = Color(value.getLong("warning")),
                text = Color(value.optLong("text", 0xFF182124))
            )
        }
    }.getOrNull()

    fun saveCustomThemeColors(colors: FocusFlowThemeColors) {
        preferences.edit().putString("custom_theme_colors", JSONObject().apply {
            put("primaryAction", colors.primaryAction.value.toLong())
            put("secondary", colors.secondary.value.toLong())
            put("accent", colors.accent.value.toLong())
            put("schedule", colors.schedule.value.toLong())
            put("neutral", colors.neutral.value.toLong())
            put("warning", colors.warning.value.toLong())
            put("text", colors.text.value.toLong())
        }.toString()).apply()
    }

    /** 自定义主题预设：多套命名配色存档。无预设时返回空列表。 */
    fun loadThemePresets(): List<ThemePreset> = runCatching {
        preferences.getString("theme_presets", null)?.let { json ->
            JSONArray(json).let { arr ->
                (0 until arr.length()).map { i ->
                    val item = arr.getJSONObject(i)
                    val c = item.getJSONObject("colors")
                    ThemePreset(
                        name = item.getString("name"),
                        colors = FocusFlowThemeColors(
                            primaryAction = Color(c.getLong("primaryAction")),
                            secondary = Color(c.optLong("secondary", 0xFF5C4B9A)),
                            accent = Color(c.getLong("accent")),
                            schedule = Color(c.getLong("schedule")),
                            neutral = Color(c.getLong("neutral")),
                            warning = Color(c.getLong("warning")),
                            text = Color(c.optLong("text", 0xFF182124))
                        )
                    )
                }
            }
        } ?: emptyList()
    }.getOrElse { emptyList() }

    fun saveThemePresets(presets: List<ThemePreset>) {
        preferences.edit().putString("theme_presets", JSONArray().apply {
            presets.forEach { preset ->
                put(JSONObject().apply {
                    put("name", preset.name)
                    put("colors", JSONObject().apply {
                        put("primaryAction", preset.colors.primaryAction.value.toLong())
                        put("secondary", preset.colors.secondary.value.toLong())
                        put("accent", preset.colors.accent.value.toLong())
                        put("schedule", preset.colors.schedule.value.toLong())
                        put("neutral", preset.colors.neutral.value.toLong())
                        put("warning", preset.colors.warning.value.toLong())
                        put("text", preset.colors.text.value.toLong())
                    })
                })
            }
        }.toString()).apply()
    }

    fun loadEnergyLevel(): String = (preferences.getString("energy_level", "正常") ?: "正常").takeIf { it in setOf("偏低", "正常", "充足") } ?: "正常"

    fun saveEnergyLevel(level: String) {
        preferences.edit().putString("energy_level", level).apply()
    }

    fun loadStatusCheckInSettings(): StatusCheckInSettings = StatusCheckInSettings(
        enabled = preferences.getBoolean("status_checkin_enabled", false),
        promptHour = preferences.getInt("status_checkin_hour", 14).coerceIn(8, 22),
        snoozeMinutes = preferences.getInt("status_checkin_snooze_minutes", 60).coerceIn(30, 180),
        promptHourAutoAdjusted = preferences.getBoolean("status_checkin_hour_auto", false)
    )

    fun saveStatusCheckInSettings(settings: StatusCheckInSettings) {
        preferences.edit()
            .putBoolean("status_checkin_enabled", settings.enabled)
            .putInt("status_checkin_hour", settings.promptHour)
            .putInt("status_checkin_snooze_minutes", settings.snoozeMinutes)
            .putBoolean("status_checkin_hour_auto", settings.promptHourAutoAdjusted)
            .apply()
    }

    fun loadStatusCheckIns(limit: Int = 90): List<StatusCheckIn> = runCatching {
        val values = JSONArray(preferences.getString("status_checkins", "[]") ?: "[]")
        List(values.length()) { index ->
            val value = values.getJSONObject(index)
            StatusCheckIn(
                energy = value.optString("energy", "正常").takeIf { it in StatusCheckInCatalog.energies } ?: "正常",
                activity = value.optString("activity", "其他").takeIf { it in StatusCheckInCatalog.activities } ?: "其他",
                recordedAt = value.optLong("recordedAt", System.currentTimeMillis())
            )
        }.takeLast(limit.coerceIn(1, 365))
    }.getOrDefault(emptyList())

    fun saveStatusCheckIn(checkIn: StatusCheckIn) {
        val all = (loadStatusCheckIns(365) + checkIn).takeLast(365)
        val values = JSONArray()
        all.forEach { value -> values.put(JSONObject().apply {
            put("energy", value.energy)
            put("activity", value.activity)
            put("recordedAt", value.recordedAt)
        }) }
        preferences.edit()
            .putString("status_checkins", values.toString())
            .putString("energy_level", checkIn.energy)
            .apply()
    }

    fun loadLatestStatusCheckIn(): StatusCheckIn? = loadStatusCheckIns(1).lastOrNull()

    fun loadItems(): List<Item> = runCatching {
        val values = JSONArray(preferences.getString("items", "[]") ?: "[]")
        val parsed = List(values.length()) { index ->
            val item = values.getJSONObject(index)
            Item(
                id = item.getLong("id"), title = item.getString("title"), detail = item.getString("detail"), kind = item.getString("kind"),
                done = item.optBoolean("done"), scheduledAt = item.optLong("scheduledAt").takeIf { it > 0 }, dayOnly = item.optBoolean("dayOnly"),
                goalId = item.optLong("goalId").takeIf { it > 0 }, completionLevel = item.optString("completionLevel"), completedAt = item.optLong("completedAt").takeIf { it > 0 },
                durationMinutes = item.optInt("durationMinutes", 60).coerceIn(5, 360), windowStartAt = item.optLong("windowStartAt").takeIf { it > 0 }, windowEndAt = item.optLong("windowEndAt").takeIf { it > 0 },
                rescheduleCount = item.optInt("rescheduleCount", 0).coerceAtLeast(0), lastRescheduledAt = item.optLong("lastRescheduledAt").takeIf { it > 0 }
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
            put("id", item.id); put("title", item.title); put("detail", item.detail); put("kind", item.kind); put("done", item.done); put("scheduledAt", item.scheduledAt ?: 0); put("dayOnly", item.dayOnly); put("goalId", item.goalId ?: 0); put("completionLevel", item.completionLevel); put("completedAt", item.completedAt ?: 0); put("durationMinutes", item.durationMinutes); put("windowStartAt", item.windowStartAt ?: 0); put("windowEndAt", item.windowEndAt ?: 0); put("rescheduleCount", item.rescheduleCount); put("lastRescheduledAt", item.lastRescheduledAt ?: 0)
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
        strongerEndReminder = preferences.getBoolean("activity_stronger_end_reminder", true),
        scheduleRemindersEnabled = preferences.getBoolean("schedule_reminders_enabled", true),
        scheduleAdvanceMinutes = preferences.getInt("schedule_reminders_advance_minutes", 10).coerceIn(0, 60)
    )

    fun saveActivityReminderSettings(settings: ActivityReminderSettings) {
        preferences.edit()
            .putBoolean("activity_notifications", settings.notificationsEnabled)
            .putInt("activity_preview_minutes", settings.previewMinutes)
            .putInt("activity_max_extensions", settings.maxExtensions)
            .putBoolean("activity_stronger_end_reminder", settings.strongerEndReminder)
            .putBoolean("schedule_reminders_enabled", settings.scheduleRemindersEnabled)
            .putInt("schedule_reminders_advance_minutes", settings.scheduleAdvanceMinutes.coerceIn(0, 60))
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
        }.getOrDefault(emptyMap()),
        routeObservations = runCatching {
            val values = JSONObject(preferences.getString("route_observations", "{}") ?: "{}")
            val parsed = mutableMapOf<String, List<Int>>()
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entries = values.optJSONArray(key) ?: continue
                val minutes = List(entries.length()) { entries.optInt(it) }.filter { it in 1..180 }.takeLast(12)
                if (minutes.isNotEmpty()) parsed[key] = minutes
            }
            parsed
        }.getOrDefault(emptyMap()).ifEmpty {
            runCatching {
                val legacy = JSONObject(preferences.getString("route_calibrations", "{}") ?: "{}")
                val parsed = mutableMapOf<String, List<Int>>()
                val keys = legacy.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    legacy.optInt(key).takeIf { it in 1..180 }?.let { parsed[key] = listOf(it) }
                }
                parsed
            }.getOrDefault(emptyMap())
        }
    )

    fun saveCommuteProfile(profile: CommuteProfile) {
        val observations = JSONObject()
        profile.routeObservations.forEach { (key, values) ->
            observations.put(key, JSONArray().apply { values.takeLast(12).forEach { put(it) } })
        }
        preferences.edit()
            .putBoolean("commute_enabled", profile.enabled)
            .putInt("commute_one_way_minutes", profile.oneWayMinutes)
            .putString("campus_mode", profile.campusMode)
            .putInt("building_buffer_minutes", profile.buildingBufferMinutes)
            .putString("ebike_battery", profile.eBikeBattery)
            .putString("route_calibrations", JSONObject(profile.routeCalibrations).toString())
            .putString("route_observations", observations.toString())
            .apply()
    }

    fun loadCampusLifeEnabled(): Boolean = preferences.getBoolean("campus_life_enabled", true)

    fun saveCampusLifeEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("campus_life_enabled", enabled).apply()
    }

    /** 被用户删除（隐藏）的内置默认地点名；可从“已隐藏地点”恢复。 */
    fun loadHiddenPlaces(): Set<String> = runCatching {
        val values = JSONArray(preferences.getString("hidden_places", "[]") ?: "[]")
        List(values.length()) { index -> values.optString(index, "") }.filter { it.isNotBlank() }.toSet()
    }.getOrDefault(emptySet())

    fun saveHiddenPlaces(hidden: Set<String>) {
        val values = JSONArray()
        hidden.take(100).forEach { values.put(it) }
        preferences.edit().putString("hidden_places", values.toString()).apply()
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

    /** 用户自定义地点（本地退化版地点管理），与内置目录/地点包合并后作为全部地点列表。 */
    fun loadCustomPlaces(): List<CampusPlace> = runCatching {
        val values = JSONArray(preferences.getString("custom_places", "[]") ?: "[]")
        val seen = mutableSetOf<String>()
        List(values.length()) { index ->
            runCatching { CampusMapPackageCodec.parsePlace(values.getJSONObject(index)) }.getOrNull()
        }.filterNotNull().filter { seen.add(it.name.lowercase()) }.takeLast(100)
    }.getOrDefault(emptyList())

    fun saveCustomPlaces(places: List<CampusPlace>) {
        val values = JSONArray()
        places.takeLast(100).forEach { place -> values.put(CampusMapPackageCodec.encodePlace(place)) }
        preferences.edit().putString("custom_places", values.toString()).apply()
    }

    fun loadAmapKey(): String = preferences.getString("amap_web_key", "") ?: ""

    fun saveAmapKey(key: String) {
        preferences.edit().apply {
            if (key.isBlank()) remove("amap_web_key")
            else putString("amap_web_key", key.trim())
        }.apply()
    }

    fun loadCampusCenter(): CampusCenter = runCatching {
        preferences.getString("campus_center", null)?.let { json ->
            val value = JSONObject(json)
            CampusCenter(
                lat = value.optDouble("lat", AmapWebApi.ZIJINGANG_CENTER.first),
                lng = value.optDouble("lng", AmapWebApi.ZIJINGANG_CENTER.second),
                city = value.optString("city", "杭州").ifBlank { "杭州" }
            )
        }
    }.getOrNull() ?: AmapWebApi.defaultCampusCenter()

    fun saveCampusCenter(center: CampusCenter) {
        preferences.edit().putString("campus_center", JSONObject().apply {
            put("lat", center.lat)
            put("lng", center.lng)
            put("city", center.city.ifBlank { "杭州" })
        }.toString()).apply()
    }

    fun loadTutorialSearchSettings(): TutorialSearchSettings = TutorialSearchSettings(
        enabled = preferences.getBoolean("tutorial_search_enabled", false),
        apiKey = preferences.getString("siliconflow_api_key", "") ?: "",
        // 旧默认 Qwen/Qwen2.5-7B 已从硅基流动下线：已保存的旧默认值迁移为新默认，用户自定义模型名不动。
        model = (preferences.getString("tutorial_search_model", null) ?: DEFAULT_TUTORIAL_MODEL)
            .takeIf { it.isNotBlank() }
            ?.let { if (it == "Qwen/Qwen2.5-7B") DEFAULT_TUTORIAL_MODEL else it }
            ?: DEFAULT_TUTORIAL_MODEL
    )

    fun saveTutorialSearchSettings(settings: TutorialSearchSettings) {
        preferences.edit()
            .putBoolean("tutorial_search_enabled", settings.enabled)
            .putString("siliconflow_api_key", settings.apiKey.trim())
            .putString("tutorial_search_model", settings.model.ifBlank { DEFAULT_TUTORIAL_MODEL })
            .apply()
    }

    /** 完成率学习原始 JSON（PlanLearning 使用）。 */
    fun loadPlanLearningRaw(): String = preferences.getString("plan_learning", "{}") ?: "{}"

    fun savePlanLearning(data: JSONObject) {
        preferences.edit().putString("plan_learning", data.toString()).apply()
    }

    fun loadCourseVisionSettings(): CourseVisionSettings = CourseVisionSettings(
        enabled = preferences.getBoolean("course_vision_enabled", false),
        model = migrateVisionModel(preferences.getString("course_vision_model", null) ?: DEFAULT_COURSE_VISION_MODEL)
    )

    /** 硅基流动已下线 Qwen2.5-VL 系列：已保存的旧 ID 迁移到在线的 Qwen3-VL 新 ID，其余自定义模型名不动。 */
    private fun migrateVisionModel(model: String): String {
        if (model.isBlank()) return DEFAULT_COURSE_VISION_MODEL
        val prefix = "Qwen/Qwen2.5-VL-"
        if (!model.startsWith(prefix)) return model
        return when (model.removePrefix(prefix)) {
            "7B-Instruct" -> "Qwen/Qwen3-VL-8B-Instruct"
            "32B-Instruct" -> "Qwen/Qwen3-VL-32B-Instruct"
            else -> DEFAULT_COURSE_VISION_MODEL
        }
    }

    fun saveCourseVisionSettings(settings: CourseVisionSettings) {
        preferences.edit()
            .putBoolean("course_vision_enabled", settings.enabled)
            .putString("course_vision_model", settings.model.ifBlank { DEFAULT_COURSE_VISION_MODEL })
            .apply()
    }

    /** 首次开启课表视觉模型时的 key 申请引导是否已显示过（只自动弹一次，之后可从设置页/帮助再次打开）。 */
    fun loadCourseVisionGuideShown(): Boolean = preferences.getBoolean("course_vision_guide_shown", false)

    fun saveCourseVisionGuideShown(shown: Boolean) {
        preferences.edit().putBoolean("course_vision_guide_shown", shown).apply()
    }

    /** 课表识别发现的新地点（楼级文字，尚未加入地点目录），最多 50 个；加载时自动剥校区前缀并归并到楼级。 */
    fun loadPendingPlaces(): List<String> = runCatching {
        val values = JSONArray(preferences.getString("pending_places", "[]") ?: "[]")
        List(values.length()) { index -> values.optString(index, "") }
            .filter { it.isNotBlank() }
            .mapNotNull { value ->
                val stripped = CourseScreenshotParser.stripCampusPrefix(value).trim()
                CourseScreenshotParser.buildingFromRoom(stripped) ?: stripped.takeIf { it.isNotBlank() }
            }
            .distinct()
    }.getOrDefault(emptyList())

    fun savePendingPlaces(places: List<String>) {
        val values = JSONArray()
        places.take(50).forEach { values.put(it) }
        preferences.edit().putString("pending_places", values.toString()).apply()
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

    fun loadGoals(): List<Goal> = StoredGoalsCodec.decodeGoals(preferences.getString("goals", "[]") ?: "[]")

    fun saveGoals(goals: List<Goal>) {
        preferences.edit().putString("goals", StoredGoalsCodec.encodeGoals(goals)).apply()
    }

    fun markGoalCompleted(goalId: Long, minimum: Boolean = false) {
        val key = GoalPlanner.currentWeekKey()
        saveGoals(loadGoals().map { goal -> if (goal.id != goalId) goal else if (goal.completionWeekKey == key) {
            if (minimum) goal.copy(minimumCompletionsThisWeek = goal.minimumCompletionsThisWeek + 1) else goal.copy(completedThisWeek = goal.completedThisWeek + 1)
        } else if (minimum) goal.copy(minimumCompletionsThisWeek = 1, completionWeekKey = key) else goal.copy(completedThisWeek = 1, minimumCompletionsThisWeek = 0, completionWeekKey = key) })
    }

    fun loadResources(): List<LearningResource> = StoredGoalsCodec.decodeResources(preferences.getString("resources", "[]") ?: "[]")

    fun saveResources(resources: List<LearningResource>) {
        preferences.edit().putString("resources", StoredGoalsCodec.encodeResources(resources)).apply()
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

    fun loadOnboardingDone(): Boolean = preferences.getBoolean("baseline_onboarding_done", false)

    fun saveOnboardingDone(done: Boolean) {
        preferences.edit().putBoolean("baseline_onboarding_done", done).apply()
    }

    // 首次启动权限一站式标记：申请过即不再重复打扰（即使系统在弹窗期间杀进程也不重申请）。
    fun loadPermissionOnboardingDone(): Boolean = preferences.getBoolean("permission_onboarding_done", false)

    fun savePermissionOnboardingDone(done: Boolean) {
        preferences.edit().putBoolean("permission_onboarding_done", done).apply()
    }

    /** 首次启动的快速入门是否已显示过（之后可从 设置 → 快速入门 再次打开）。 */
    fun loadFeatureIntroShown(): Boolean = preferences.getBoolean("feature_intro_shown", false)

    fun saveFeatureIntroShown(shown: Boolean) {
        preferences.edit().putBoolean("feature_intro_shown", shown).apply()
    }

    /** 首次完成习惯基线后的“后续在哪找”提示是否已显示过。 */
    fun loadBaselineWhereToFindShown(): Boolean = preferences.getBoolean("baseline_where_to_find_shown", false)

    fun saveBaselineWhereToFindShown(shown: Boolean) {
        preferences.edit().putBoolean("baseline_where_to_find_shown", shown).apply()
    }

    fun loadBaselineProfile(): BaselineProfile = runCatching {
        val value = preferences.getString("baseline_profile", null) ?: return@runCatching BaselineProfile()
        baselineProfileFromJson(JSONObject(value))
    }.getOrDefault(BaselineProfile())

    fun saveBaselineProfile(profile: BaselineProfile) {
        preferences.edit().putString("baseline_profile", baselineProfileToJson(profile).toString()).apply()
    }

    /** 已另存的生活模式方案（同阶段多种作息共存，可切换/删除）。 */
    fun loadBaselineVariants(): List<BaselineProfile> = runCatching {
        val values = JSONArray(preferences.getString("baseline_variants", "[]") ?: "[]")
        List(values.length()) { index -> values.optJSONObject(index)?.let(::baselineProfileFromJson) }.filterNotNull()
    }.getOrDefault(emptyList())

    fun saveBaselineVariants(variants: List<BaselineProfile>) {
        val values = JSONArray()
        variants.take(8).forEach { profile -> values.put(baselineProfileToJson(profile)) }
        preferences.edit().putString("baseline_variants", values.toString()).apply()
    }

    private fun baselineProfileFromJson(json: JSONObject): BaselineProfile = BaselineProfile(
        lifeStage = LifeStage.fromKey(json.optString("lifeStage", "")),
        wakeMinute = json.optInt("wakeMinute", -1),
        sleepMinute = json.optInt("sleepMinute", -1),
        meals = runCatching {
            val values = json.optJSONArray("meals") ?: JSONArray()
            List(values.length()) { index ->
                val meal = values.getJSONObject(index)
                MealTimeline(
                    type = MealType.fromLabel(meal.getString("type")) ?: return@List MealTimeline(MealType.BREAKFAST, 480),
                    typicalStartMinute = meal.optInt("typicalStartMinute", 480).coerceIn(0, 24 * 60 - 1),
                    typicalMinutes = meal.optInt("typicalMinutes", 20).coerceIn(5, 120)
                )
            }
        }.getOrDefault(emptyList()),
        entertainmentWindow = json.optString("entertainmentWindow", ""),
        variantName = json.optString("variantName", ""),
        dayGroups = runCatching {
            val values = json.optJSONArray("dayGroups") ?: JSONArray()
            List(values.length()) { index ->
                val group = values.getJSONObject(index)
                val days = group.optJSONArray("days")?.let { daysArray -> List(daysArray.length()) { i -> daysArray.optInt(i, 0) }.filter { it in 1..7 }.toSet() } ?: emptySet()
                val meals = runCatching {
                    val mealValues = group.optJSONArray("meals") ?: JSONArray()
                    List(mealValues.length()) { i ->
                        val meal = mealValues.getJSONObject(i)
                        MealTimeline(
                            type = MealType.fromLabel(meal.getString("type")) ?: return@List MealTimeline(MealType.BREAKFAST, 480),
                            typicalStartMinute = meal.optInt("typicalStartMinute", 480).coerceIn(0, 24 * 60 - 1),
                            typicalMinutes = meal.optInt("typicalMinutes", 20).coerceIn(5, 120)
                        )
                    }
                }.getOrDefault(emptyList())
                DayGroup(group.optString("label", ""), days, group.optInt("wakeMinute", -1), group.optInt("sleepMinute", -1), meals)
            }
        }.getOrDefault(emptyList())
    )

    private fun baselineProfileToJson(profile: BaselineProfile): JSONObject {
        val meals = JSONArray()
        profile.meals.forEach { meal -> meals.put(JSONObject().apply {
            put("type", meal.type.label)
            put("typicalStartMinute", meal.typicalStartMinute)
            put("typicalMinutes", meal.typicalMinutes)
        }) }
        val groups = JSONArray()
        profile.dayGroups.forEach { group ->
            groups.put(JSONObject().apply {
                put("label", group.label)
                val days = JSONArray()
                group.days.sorted().forEach { days.put(it) }
                put("days", days)
                put("wakeMinute", group.wakeMinute)
                put("sleepMinute", group.sleepMinute)
                val groupMeals = JSONArray()
                group.meals.forEach { meal -> groupMeals.put(JSONObject().apply {
                    put("type", meal.type.label)
                    put("typicalStartMinute", meal.typicalStartMinute)
                    put("typicalMinutes", meal.typicalMinutes)
                }) }
                put("meals", groupMeals)
            })
        }
        return JSONObject().apply {
            put("lifeStage", profile.lifeStage?.storageKey ?: "")
            put("wakeMinute", profile.wakeMinute)
            put("sleepMinute", profile.sleepMinute)
            put("meals", meals)
            put("entertainmentWindow", profile.entertainmentWindow)
            put("variantName", profile.variantName)
            put("dayGroups", groups)
        }
    }

    fun appendBaselineEvent(event: BaselineEvent) {
        val all = (loadBaselineEvents(500) + event).takeLast(500)
        val values = JSONArray()
        all.forEach { value -> values.put(JSONObject().apply {
            put("id", value.id)
            put("type", value.type.storageKey)
            put("recordedAt", value.recordedAt)
            put("payload", value.payload)
        }) }
        preferences.edit().putString("baseline_events", values.toString()).apply()
    }

    fun loadBaselineEvents(limit: Int = 200): List<BaselineEvent> = runCatching {
        val values = JSONArray(preferences.getString("baseline_events", "[]") ?: "[]")
        List(values.length()) { index ->
            val value = values.getJSONObject(index)
            BaselineEvent(
                id = value.getLong("id"),
                type = BaselineEventType.entries.firstOrNull { it.storageKey == value.optString("type") } ?: BaselineEventType.LIFE_STAGE_SET,
                recordedAt = value.optLong("recordedAt", 0),
                payload = value.optString("payload", "")
            )
        }.takeLast(limit.coerceIn(1, 500))
    }.getOrDefault(emptyList())

    fun clearBaselineEvents() {
        preferences.edit().remove("baseline_events").apply()
    }

    fun resetBaseline() {
        preferences.edit()
            .remove("baseline_profile")
            .remove("baseline_events")
            .putBoolean("baseline_onboarding_done", false)
            .apply()
    }

    fun loadMealRecords(limit: Int = 200): List<MealRecord> = runCatching {
        val values = JSONArray(preferences.getString("meal_records", "[]") ?: "[]")
        List(values.length()) { index ->
            val value = values.getJSONObject(index)
            MealRecord(
                id = value.getLong("id"),
                mealType = MealType.fromLabel(value.optString("mealType", "")) ?: MealType.LUNCH,
                lifeStage = value.optString("lifeStage", ""),
                startedAt = value.optLong("startedAt", 0),
                endedAt = value.optLong("endedAt").takeIf { it > 0 },
                location = value.optString("location", ""),
                category = value.optString("category", ""),
                merchant = value.optString("merchant", ""),
                amount = value.optInt("amount", -1),
                payMethod = value.optString("payMethod", ""),
                rating = value.optInt("rating", 0).coerceIn(0, 5),
                note = value.optString("note", ""),
                recordedAt = value.optLong("recordedAt", value.optLong("startedAt", 0))
            )
        }.takeLast(limit.coerceIn(1, 500))
    }.getOrDefault(emptyList())

    fun appendMealRecord(record: MealRecord) {
        val all = (loadMealRecords(500) + record).takeLast(500)
        val values = JSONArray()
        all.forEach { value -> values.put(JSONObject().apply {
            put("id", value.id)
            put("mealType", value.mealType.label)
            put("lifeStage", value.lifeStage)
            put("startedAt", value.startedAt)
            put("endedAt", value.endedAt ?: 0)
            put("location", value.location)
            put("category", value.category)
            put("merchant", value.merchant)
            put("amount", value.amount)
            put("payMethod", value.payMethod)
            put("rating", value.rating)
            put("note", value.note)
            put("recordedAt", value.recordedAt)
        }) }
        preferences.edit().putString("meal_records", values.toString()).apply()
    }

    fun updateMealRecordEnd(id: Long, endedAt: Long, draft: MealDraft = MealDraft()) {
        val updated = loadMealRecords(500).map {
            if (it.id == id) it.copy(
                endedAt = endedAt,
                location = if (draft.location.isNotBlank()) draft.location else it.location,
                category = if (draft.category.isNotBlank()) draft.category else it.category,
                merchant = if (draft.merchant.isNotBlank()) draft.merchant else it.merchant,
                amount = if (draft.amount >= 0) draft.amount else it.amount,
                payMethod = if (draft.payMethod.isNotBlank()) draft.payMethod else it.payMethod,
                rating = if (draft.rating > 0) draft.rating else it.rating,
                note = if (draft.note.isNotBlank()) draft.note else it.note
            ) else it
        }
        val values = JSONArray()
        updated.forEach { value -> values.put(JSONObject().apply {
            put("id", value.id)
            put("mealType", value.mealType.label)
            put("lifeStage", value.lifeStage)
            put("startedAt", value.startedAt)
            put("endedAt", value.endedAt ?: 0)
            put("location", value.location)
            put("category", value.category)
            put("merchant", value.merchant)
            put("amount", value.amount)
            put("payMethod", value.payMethod)
            put("rating", value.rating)
            put("note", value.note)
            put("recordedAt", value.recordedAt)
        }) }
        preferences.edit().putString("meal_records", values.toString()).apply()
    }

    fun deleteMealRecord(id: Long) {
        val remaining = loadMealRecords(500).filterNot { it.id == id }
        val values = JSONArray()
        remaining.forEach { value -> values.put(JSONObject().apply {
            put("id", value.id)
            put("mealType", value.mealType.label)
            put("lifeStage", value.lifeStage)
            put("startedAt", value.startedAt)
            put("endedAt", value.endedAt ?: 0)
            put("location", value.location)
            put("category", value.category)
            put("merchant", value.merchant)
            put("amount", value.amount)
            put("payMethod", value.payMethod)
            put("rating", value.rating)
            put("note", value.note)
            put("recordedAt", value.recordedAt)
        }) }
        preferences.edit().putString("meal_records", values.toString()).apply()
    }

    fun loadMealReminderEnabled(): Boolean = preferences.getBoolean("meal_reminder_enabled", true)

    fun saveMealReminderEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("meal_reminder_enabled", enabled).apply()
    }

    fun loadQuickCaptureEnabled(): Boolean = preferences.getBoolean("quick_capture_enabled", false)

    fun saveQuickCaptureEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("quick_capture_enabled", enabled).apply()
    }

    fun loadGameDetectionEnabled(): Boolean = preferences.getBoolean("game_detection_enabled", false)

    fun saveGameDetectionEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("game_detection_enabled", enabled).apply()
    }

    fun loadVideoAnalysisModel(): String = preferences.getString("video_analysis_model", DEFAULT_VIDEO_ANALYSIS_MODEL) ?: DEFAULT_VIDEO_ANALYSIS_MODEL

    fun saveVideoAnalysisModel(model: String) {
        preferences.edit().putString("video_analysis_model", model).apply()
    }

    /** 用户手动设定的应用分类：包名 → 分类名（AppCategory.name）。 */
    fun loadAppCategories(): Map<String, String> = runCatching {
        val obj = JSONObject(preferences.getString("app_categories", "{}") ?: "{}")
        obj.keys().asSequence().associateWith { obj.optString(it, "") }.filterValues { it.isNotBlank() }
    }.getOrDefault(emptyMap())

    fun saveAppCategories(categories: Map<String, String>) {
        val obj = JSONObject()
        categories.forEach { (pkg, category) -> if (category.isNotBlank()) obj.put(pkg, category) }
        preferences.edit().putString("app_categories", obj.toString()).apply()
    }

    /** 被用户忽略（从应用分类列表隐藏）的应用包名；可从“已忽略应用”恢复。 */
    fun loadHiddenApps(): Set<String> = runCatching {
        val values = JSONArray(preferences.getString("hidden_apps", "[]") ?: "[]")
        List(values.length()) { values.optString(it, "") }.filter { it.isNotBlank() }.toSet()
    }.getOrDefault(emptySet())

    fun saveHiddenApps(hidden: Set<String>) {
        val values = JSONArray()
        hidden.take(300).forEach { values.put(it) }
        preferences.edit().putString("hidden_apps", values.toString()).apply()
    }

    fun loadGameSessions(): List<GameSessionRecord> = runCatching {
        val values = JSONArray(preferences.getString("game_sessions", "[]") ?: "[]")
        List(values.length()) { index ->
            val session = values.getJSONObject(index)
            GameSessionRecord(
                id = session.getLong("id"),
                title = session.getString("title"),
                category = session.optString("category", "游戏").takeIf { it.isNotBlank() } ?: "游戏",
                packageName = session.optString("packageName", "").takeIf { it.isNotBlank() },
                plannedStartAt = session.getLong("plannedStartAt"),
                plannedEndAt = session.getLong("plannedEndAt"),
                actualEndAt = if (session.has("actualEndAt") && !session.isNull("actualEndAt")) session.getLong("actualEndAt") else null,
                endedOnTime = session.optBoolean("endedOnTime", false),
                overrunMinutes = session.optInt("overrunMinutes", 0),
                remindStart = session.optBoolean("remindStart", false)
            )
        }
    }.getOrDefault(emptyList())

    fun saveGameSessions(sessions: List<GameSessionRecord>) {
        val values = JSONArray()
        sessions.takeLast(200).forEach { session -> values.put(JSONObject().apply {
            put("id", session.id); put("title", session.title); put("category", session.category); put("packageName", session.packageName ?: ""); put("plannedStartAt", session.plannedStartAt); put("plannedEndAt", session.plannedEndAt)
            session.actualEndAt?.let { put("actualEndAt", it) }
            put("endedOnTime", session.endedOnTime); put("overrunMinutes", session.overrunMinutes); put("remindStart", session.remindStart)
        }) }
        preferences.edit().putString("game_sessions", values.toString()).apply()
    }

    /** 更新一条游戏会话（前台检测/通知动作记录实际结束等）。 */
    fun updateGameSession(id: Long, transform: (GameSessionRecord) -> GameSessionRecord) {
        val sessions = loadGameSessions()
        saveGameSessions(sessions.map { if (it.id == id) transform(it) else it })
    }

    fun loadQuietHoursSettings(): QuietHoursSettings = QuietHoursSettings(
        enabled = preferences.getBoolean("quiet_hours_enabled", false),
        startMinute = preferences.getInt("quiet_hours_start", 23 * 60).coerceIn(0, 1439),
        endMinute = preferences.getInt("quiet_hours_end", 7 * 60).coerceIn(0, 1439),
        suppressStatusCheckIn = preferences.getBoolean("quiet_hours_suppress_status", true),
        suppressMeal = preferences.getBoolean("quiet_hours_suppress_meal", true),
        suppressWindDown = preferences.getBoolean("quiet_hours_suppress_wind_down", true),
        muteUntil = preferences.getLong("quiet_hours_mute_until", 0L)
    )

    fun saveQuietHoursSettings(settings: QuietHoursSettings) {
        preferences.edit()
            .putBoolean("quiet_hours_enabled", settings.enabled)
            .putInt("quiet_hours_start", settings.startMinute)
            .putInt("quiet_hours_end", settings.endMinute)
            .putBoolean("quiet_hours_suppress_status", settings.suppressStatusCheckIn)
            .putBoolean("quiet_hours_suppress_meal", settings.suppressMeal)
            .putBoolean("quiet_hours_suppress_wind_down", settings.suppressWindDown)
            .putLong("quiet_hours_mute_until", settings.muteUntil)
            .apply()
    }

    fun loadWindDownEnabled(): Boolean = preferences.getBoolean("wind_down_enabled", true)

    fun saveWindDownEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("wind_down_enabled", enabled).apply()
    }

    /** 当天标记“今天不需要”的餐次，格式为 “yyyy-MM-dd:类型标签”。 */
    fun loadMealSkipDays(): Set<String> = runCatching {
        val values = JSONArray(preferences.getString("meal_skip_days", "[]") ?: "[]")
        List(values.length()) { index -> values.getString(index) }.toSet()
    }.getOrDefault(emptySet())

    fun saveMealSkipDays(skipDays: Set<String>) {
        val values = JSONArray()
        skipDays.forEach { values.put(it) }
        preferences.edit().putString("meal_skip_days", values.toString()).apply()
    }

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
