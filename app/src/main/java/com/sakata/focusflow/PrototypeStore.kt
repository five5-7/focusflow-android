package com.sakata.focusflow

import android.content.Context
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 自定义主题预设：命名配色存档，用于保存／切换多套自定义配色。 */
data class ThemePreset(val name: String, val colors: FocusFlowThemeColors)

private val taskHistoryLock = Any()

/** Deliberately small offline persistence for the first test build. */
class PrototypeStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = ProtectedPreferences(context.getSharedPreferences("focusflow", Context.MODE_PRIVATE))

    /** 损坏数据备份目录：解析失败的 prefs 原始串先落盘，再返回空默认——后续保存覆盖也不丢原始数据。 */
    private val corruptDir: File by lazy { File(appContext.filesDir, CorruptionBackup.DIR_NAME) }

    /**
     * 数据版本锚点（只读）：本仓库 prefs 数据当前按 data_version = 1 组织；旧版本无此键，视为 1。
     * 归位规则：将来引入数据迁移时——先读 data_version，≥ 目标版本则跳过；按一次性标记键逐项迁移并置位，
     * 完成后把 data_version 抬到目标值（写此键允许，但迁移逻辑必须先在下方注册表登记）。
     *
     * 迁移注册表（一次性布尔标记 → 用途）：
     *   task_history_migrated_v65_0 → 6.5 存量 items 补齐任务事件（migrateTaskHistory，已完成）。
     */
    val dataVersion: Int = preferences.getInt("data_version", 1)

    /**
     * 列表/集合型 JSON 存档的损坏保护：解码结果为空（或解码本身失败）而原始串非常规空容器，
     * 判定为损坏——备份原始串后返回默认空值。正常路径与原先行为完全一致。
     */
    private inline fun <T> decodeGuarded(
        key: String,
        fallback: T,
        decode: (String) -> T,
        isDamaged: (T) -> Boolean
    ): T {
        val raw = preferences.getString(key, null) ?: return fallback
        val decoded = runCatching { decode(raw) }.getOrNull()
        if (decoded == null || isDamaged(decoded)) {
            StorageProtection.backup(corruptDir, key, raw)
            return fallback
        }
        return decoded
    }

    /** 对象型 JSON 存档的损坏保护：解码失败 → 备份原始串后返回默认值。 */
    private inline fun <T> decodeObjectGuarded(
        key: String,
        fallback: T?,
        decode: (String) -> T
    ): T? {
        val raw = preferences.getString(key, null) ?: return fallback
        val decoded = runCatching { decode(raw) }.getOrNull()
        if (decoded == null) {
            StorageProtection.backup(corruptDir, key, raw)
            return fallback
        }
        return decoded
    }

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
    fun loadCustomThemeColors(): FocusFlowThemeColors? =
        decodeObjectGuarded("custom_theme_colors", null, { json ->
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
        })

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
    fun loadThemePresets(): List<ThemePreset> =
        decodeGuarded("theme_presets", emptyList(), { json ->
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
        }, { it.isEmpty() })

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

    fun loadStatusCheckIns(limit: Int = 90): List<StatusCheckIn> =
        decodeGuarded("status_checkins", emptyList(), { StatusCheckInCodec.decode(it) }, { it.isEmpty() })
            .takeLast(limit.coerceIn(1, 365))

    fun saveStatusCheckIn(checkIn: StatusCheckIn) {
        val all = (loadStatusCheckIns(365) + checkIn).takeLast(365)
        preferences.edit()
            .putString("status_checkins", StatusCheckInCodec.encode(all))
            .putString("energy_level", checkIn.energy)
            .apply()
    }

    fun loadLatestStatusCheckIn(): StatusCheckIn? = loadStatusCheckIns(1).lastOrNull()

    fun loadItems(): List<Item> {
        val raw = preferences.getString("items", null) ?: return emptyList()
        val result = ItemsCodec.decode(raw)
        if (result.items.isEmpty() && CorruptionBackup.shouldBackup(raw)) {
            StorageProtection.backup(corruptDir, "items", raw)
            return emptyList()
        }
        if (result.idsNormalized) saveItems(result.items)
        return result.items
    }

    fun saveItems(items: List<Item>) {
        preferences.edit().putString("items", ItemsCodec.encode(items)).apply()
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
        val item = Item(title = "重新安排：$activityName", detail = "刚才跳过了本次活动；可以改期、缩短或暂停", kind = "收集箱")
        saveItems(listOf(item) + loadItems())
        appendTaskEvent(TaskRecorder.event(TaskEventType.TASK_CREATED, item.id, item.title))
    }

    fun updateItem(id: Long, transform: (Item) -> Item) {
        saveItems(loadItems().map { if (it.id == id) transform(it) else it })
    }

    fun findItem(id: Long): Item? = loadItems().firstOrNull { it.id == id }

    fun recoverMissedGoalTasks(): List<Item> {
        val cutoff = System.currentTimeMillis() - 2 * 60 * 60_000L
        val all = loadItems()
        val recovered = all.map { item ->
            if (item.goalId != null && item.kind == "任务" && !item.done && (item.scheduledAt ?: Long.MAX_VALUE) < cutoff) {
                appendTaskEvent(TaskRecorder.event(TaskEventType.TASK_TO_INBOX, item.id, item.title, extra = "错过自动放回"))
                item.copy(title = if (item.title.startsWith("重新安排：")) item.title else "重新安排：${item.title}", kind = "收集箱", detail = "上次目标安排未确认；可改期、缩短、暂停或放弃", scheduledAt = null)
            } else item
        }
        if (recovered != all) saveItems(recovered)
        return recovered
    }

    fun loadCommuteProfile(): CommuteProfile = CommuteProfile(
        enabled = preferences.getBoolean("commute_enabled", false),
        oneWayMinutes = preferences.getInt("commute_one_way_minutes", 30),
        campusMode = preferences.getString("campus_mode", "步行") ?: "步行",
        buildingBufferMinutes = preferences.getInt("building_buffer_minutes", 3),
        eBikeBattery = preferences.getString("ebike_battery", "未知") ?: "未知",
        // 路由校准/观测键不套损坏保护：空为合法状态，可回退 legacy 观测。
        routeCalibrations = CommuteRouteCodec.decodeCalibrations(preferences.getString("route_calibrations", "{}") ?: "{}"),
        routeObservations = CommuteRouteCodec.decodeObservations(preferences.getString("route_observations", "{}") ?: "{}").ifEmpty {
            CommuteRouteCodec.legacyObservations(preferences.getString("route_calibrations", "{}") ?: "{}")
        }
    )

    fun saveCommuteProfile(profile: CommuteProfile) {
        preferences.edit()
            .putBoolean("commute_enabled", profile.enabled)
            .putInt("commute_one_way_minutes", profile.oneWayMinutes)
            .putString("campus_mode", profile.campusMode)
            .putInt("building_buffer_minutes", profile.buildingBufferMinutes)
            .putString("ebike_battery", profile.eBikeBattery)
            .putString("route_calibrations", CommuteRouteCodec.encodeCalibrations(profile.routeCalibrations))
            .putString("route_observations", CommuteRouteCodec.encodeObservations(profile.routeObservations))
            .apply()
    }

    fun loadCampusLifeEnabled(): Boolean = preferences.getBoolean("campus_life_enabled", true)

    fun saveCampusLifeEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("campus_life_enabled", enabled).apply()
    }

    /** 被用户删除（隐藏）的内置默认地点名；可从“已隐藏地点”恢复。 */
    fun loadHiddenPlaces(): Set<String> =
        decodeGuarded("hidden_places", emptySet(), { StringArrayCodec.decodeNonBlank(it).toSet() }, { it.isEmpty() })

    fun saveHiddenPlaces(hidden: Set<String>) {
        preferences.edit().putString("hidden_places", StringArrayCodec.encode(hidden.take(100))).apply()
    }

    fun loadCampusMapPackage(): CampusMapPackage? =
        decodeObjectGuarded("campus_map_package", null, { CampusMapPackageCodec.parse(it) })

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
    fun loadCustomPlaces(): List<CampusPlace> =
        decodeGuarded("custom_places", emptyList(), { json ->
            val values = JSONArray(json)
            val seen = mutableSetOf<String>()
            List(values.length()) { index ->
                runCatching { CampusMapPackageCodec.parsePlace(values.getJSONObject(index)) }.getOrNull()
            }.filterNotNull().filter { seen.add(it.name.lowercase()) }.takeLast(100)
        }, { it.isEmpty() })

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

    fun loadCampusCenter(): CampusCenter =
        decodeObjectGuarded("campus_center", AmapWebApi.defaultCampusCenter(), { json ->
            val value = JSONObject(json)
            CampusCenter(
                lat = value.optDouble("lat", AmapWebApi.ZIJINGANG_CENTER.first),
                lng = value.optDouble("lng", AmapWebApi.ZIJINGANG_CENTER.second),
                city = value.optString("city", "杭州").ifBlank { "杭州" }
            )
        }) ?: AmapWebApi.defaultCampusCenter()

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

    /** AI 周总结设置：独立键未设置过时开关回退到教程搜索开关（升级用户零感知）；key 为独立的 `ai_weekly_summary_key`，留空时调用方回退教程搜索 key。 */
    fun loadAiWeeklySummarySettings(): AiWeeklySummarySettings = AiWeeklySummarySettings(
        enabled = if (preferences.contains("ai_weekly_summary_enabled"))
            preferences.getBoolean("ai_weekly_summary_enabled", false)
        else preferences.getBoolean("tutorial_search_enabled", false),
        apiKey = preferences.getString("ai_weekly_summary_key", "") ?: ""
    )

    fun saveAiWeeklySummarySettings(settings: AiWeeklySummarySettings) {
        preferences.edit().apply {
            putBoolean("ai_weekly_summary_enabled", settings.enabled)
            if (settings.apiKey.isBlank()) remove("ai_weekly_summary_key")
            else putString("ai_weekly_summary_key", settings.apiKey.trim())
        }.apply()
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
    fun loadPendingPlaces(): List<String> =
        decodeGuarded("pending_places", emptyList(), { json ->
            val values = JSONArray(json)
            List(values.length()) { index -> values.optString(index, "") }
                .filter { it.isNotBlank() }
                .mapNotNull { value ->
                    val stripped = CourseScreenshotParser.stripCampusPrefix(value).trim()
                    CourseScreenshotParser.buildingFromRoom(stripped) ?: stripped.takeIf { it.isNotBlank() }
                }
                .distinct()
        }, { it.isEmpty() })

    fun savePendingPlaces(places: List<String>) {
        val values = JSONArray()
        places.take(50).forEach { values.put(it) }
        preferences.edit().putString("pending_places", values.toString()).apply()
    }

    fun hasCourseSetup(): Boolean = preferences.getBoolean("course_setup_done", false)

    fun loadCourses(): List<Course> =
        decodeGuarded("courses", emptyList(), { json ->
            val values = JSONArray(json)
            List(values.length()) { index ->
                val course = values.getJSONObject(index)
                Course(
                    title = course.getString("title"), weekday = course.getInt("weekday"), startPeriod = course.getInt("startPeriod"), endPeriod = course.getInt("endPeriod"),
                    building = course.getString("building"), zone = CampusZone.valueOf(course.getString("zone")), needsConfirmation = course.optBoolean("needsConfirmation", false)
                )
            }
        }, { it.isEmpty() })

    fun saveCourses(courses: List<Course>) {
        val values = JSONArray()
        courses.forEach { course -> values.put(JSONObject().apply {
            put("title", course.title); put("weekday", course.weekday); put("startPeriod", course.startPeriod); put("endPeriod", course.endPeriod)
            put("building", course.building); put("zone", course.zone.name); put("needsConfirmation", course.needsConfirmation)
        }) }
        preferences.edit().putBoolean("course_setup_done", true).putString("courses", values.toString()).apply()
    }

    fun loadGoals(): List<Goal> =
        decodeGuarded("goals", emptyList(), { StoredGoalsCodec.decodeGoals(it) }, { it.isEmpty() })

    fun saveGoals(goals: List<Goal>) {
        preferences.edit().putString("goals", StoredGoalsCodec.encodeGoals(goals)).apply()
    }

    fun markGoalCompleted(goalId: Long, minimum: Boolean = false) {
        val key = GoalPlanner.currentWeekKey()
        saveGoals(loadGoals().map { goal -> if (goal.id != goalId) goal else if (goal.completionWeekKey == key) {
            if (minimum) goal.copy(minimumCompletionsThisWeek = goal.minimumCompletionsThisWeek + 1) else goal.copy(completedThisWeek = goal.completedThisWeek + 1)
        } else if (minimum) goal.copy(minimumCompletionsThisWeek = 1, completionWeekKey = key) else goal.copy(completedThisWeek = 1, minimumCompletionsThisWeek = 0, completionWeekKey = key) })
    }

    fun loadResources(): List<LearningResource> =
        decodeGuarded("resources", emptyList(), { StoredGoalsCodec.decodeResources(it) }, { it.isEmpty() })

    fun saveResources(resources: List<LearningResource>) {
        preferences.edit().putString("resources", StoredGoalsCodec.encodeResources(resources)).apply()
    }

    fun loadFeedback(): List<TaskFeedback> =
        decodeGuarded("feedback", emptyList(), { TaskFeedbackCodec.decode(it) }, { it.isEmpty() })

    fun addFeedback(feedback: TaskFeedback) {
        val all = (loadFeedback() + feedback).takeLast(200)
        preferences.edit().putString("feedback", TaskFeedbackCodec.encode(all)).apply()
    }

    fun loadImprovementNotes(): List<ImprovementNote> =
        decodeGuarded("improvement_notes", emptyList(), { ImprovementNoteCodec.decode(it) }, { it.isEmpty() })

    fun saveImprovementNotes(notes: List<ImprovementNote>) {
        preferences.edit().putString("improvement_notes", ImprovementNoteCodec.encode(notes.takeLast(100))).apply()
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

    fun loadBaselineProfile(): BaselineProfile =
        decodeObjectGuarded("baseline_profile", BaselineProfile(), { json -> BaselineProfileCodec.parse(JSONObject(json)) })
            ?: BaselineProfile()

    fun saveBaselineProfile(profile: BaselineProfile) {
        preferences.edit().putString("baseline_profile", BaselineProfileCodec.encode(profile)).apply()
    }

    /** 已另存的生活模式方案（同阶段多种作息共存，可切换/删除）。 */
    fun loadBaselineVariants(): List<BaselineProfile> =
        decodeGuarded("baseline_variants", emptyList(), { json ->
            val values = JSONArray(json)
            List(values.length()) { index -> values.optJSONObject(index)?.let(BaselineProfileCodec::parse) }.filterNotNull()
        }, { it.isEmpty() })

    fun saveBaselineVariants(variants: List<BaselineProfile>) {
        val values = JSONArray()
        variants.take(8).forEach { values.put(BaselineProfileCodec.toJson(it)) }
        preferences.edit().putString("baseline_variants", values.toString()).apply()
    }

    fun appendBaselineEvent(event: BaselineEvent) {
        val all = (loadBaselineEvents(500) + event).takeLast(500)
        preferences.edit().putString("baseline_events", BaselineEventsCodec.encode(all)).apply()
    }

    fun loadBaselineEvents(limit: Int = 200): List<BaselineEvent> =
        decodeGuarded("baseline_events", emptyList(), { BaselineEventsCodec.decode(it) }, { it.isEmpty() })
            .takeLast(limit.coerceIn(1, 500))

    fun clearBaselineEvents() {
        preferences.edit().remove("baseline_events").apply()
    }

    /** 按 id 删除单条原始事件（dialog 展示窗口即全量 500 上限内）；未命中时无副作用。 */
    fun removeBaselineEvent(eventId: Long): Boolean {
        val all = loadBaselineEvents(500)
        val remaining = BaselineEventsCodec.without(all, eventId)
        if (remaining.size == all.size) return !StorageProtection.readOnly
        return preferences.edit().apply {
            if (remaining.isEmpty()) remove("baseline_events") else putString("baseline_events", BaselineEventsCodec.encode(remaining))
        }.commit()
    }

    fun appendTaskEvent(event: TaskEvent) = synchronized(taskHistoryLock) {
        val all = TaskHistory.append(loadTaskEvents(), event)
        preferences.edit().putString("task_events", TaskEventCodec.encode(all)).apply()
    }

    fun loadTaskEvents(limit: Int = Int.MAX_VALUE): List<TaskEvent> =
        decodeGuarded("task_events", emptyList(), { TaskEventCodec.decode(it) }, { it.isEmpty() })
            .takeLast(limit.coerceAtLeast(1))

    /** 6.5 一次性迁移：已执行过则直接返回 false；否则按存量 items 补齐可推断事件并置位标记。 */
    fun migrateTaskHistory(): Boolean {
        if (preferences.getBoolean("task_history_migrated_v65_0", false)) return false
        TaskHistoryMigration.buildEvents(loadItems()).forEach(::appendTaskEvent)
        preferences.edit().putBoolean("task_history_migrated_v65_0", true).apply()
        return true
    }

    fun resetBaseline() {
        preferences.edit()
            .remove("baseline_profile")
            .remove("baseline_events")
            .putBoolean("baseline_onboarding_done", false)
            .apply()
    }

    fun loadMealRecords(limit: Int = 200): List<MealRecord> =
        decodeGuarded("meal_records", emptyList(), { MealRecordsCodec.decode(it) }, { it.isEmpty() })
            .takeLast(limit.coerceIn(1, 500))

    fun appendMealRecord(record: MealRecord) {
        val all = (loadMealRecords(500) + record).takeLast(500)
        preferences.edit().putString("meal_records", MealRecordsCodec.encode(all)).apply()
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
        preferences.edit().putString("meal_records", MealRecordsCodec.encode(updated)).apply()
    }

    fun deleteMealRecord(id: Long) {
        val remaining = loadMealRecords(500).filterNot { it.id == id }
        preferences.edit().putString("meal_records", MealRecordsCodec.encode(remaining)).apply()
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
    fun loadAppCategories(): Map<String, String> =
        decodeGuarded("app_categories", emptyMap(), { AppCategoriesCodec.decode(it) }, { it.isEmpty() })

    fun saveAppCategories(categories: Map<String, String>) {
        preferences.edit().putString("app_categories", AppCategoriesCodec.encode(categories)).apply()
    }

    /** 被用户忽略（从应用分类列表隐藏）的应用包名；可从“已忽略应用”恢复。 */
    fun loadHiddenApps(): Set<String> =
        decodeGuarded("hidden_apps", emptySet(), { StringArrayCodec.decodeNonBlank(it).toSet() }, { it.isEmpty() })

    fun saveHiddenApps(hidden: Set<String>) {
        preferences.edit().putString("hidden_apps", StringArrayCodec.encode(hidden.take(300))).apply()
    }

    fun loadGameSessions(): List<GameSessionRecord> =
        decodeGuarded("game_sessions", emptyList(), { GameSessionsCodec.decode(it) }, { it.isEmpty() })

    fun saveGameSessions(sessions: List<GameSessionRecord>) {
        preferences.edit().putString("game_sessions", GameSessionsCodec.encode(sessions.takeLast(200))).apply()
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
    fun loadMealSkipDays(): Set<String> =
        decodeGuarded("meal_skip_days", emptySet(), { StringArrayCodec.decodeStrict(it).toSet() }, { it.isEmpty() })

    fun saveMealSkipDays(skipDays: Set<String>) {
        preferences.edit().putString("meal_skip_days", StringArrayCodec.encode(skipDays)).apply()
    }

    private fun loadSessions(): List<ActivitySession> =
        decodeGuarded("sessions", emptyList(), { json ->
            val values = JSONArray(json)
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
        }, { it.isEmpty() })
}
