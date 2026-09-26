package com.sakata.focusflow

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.sakata.focusflow.data.CoreDataReadResult
import com.sakata.focusflow.data.CoreDataRuntimeAccess
import com.sakata.focusflow.data.CoreDataRuntimeResolution
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A course setting applies to the current persisted meeting ID until course identity is migrated. */
internal data class CourseReminderSettings(
    val enabled: Boolean = false,
    val overrides: Map<Long, Boolean> = emptyMap()
) {
    fun enabledFor(course: Course): Boolean = overrides[course.id] ?: enabled
}

internal object CourseReminderPolicy {
    const val ADVANCE_MINUTES = 10

    fun startAt(course: Course, day: LocalDate, table: CoursePeriodTable, zone: ZoneId): Long? {
        if (!table.isValid() || course.weekday !in 1..7 || course.startPeriod !in 1..table.periods.size ||
            course.endPeriod !in course.startPeriod..table.periods.size || day.dayOfWeek.value != course.weekday ||
            course.needsConfirmation || !CourseActivationPolicy.isActiveOn(course, day.toEpochDay())) return null
        val minute = table.periods[course.startPeriod - 1].startMinute
        return day.atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
    }

    fun nextTrigger(course: Course, table: CoursePeriodTable, now: Long, zone: ZoneId): Long? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val firstDay = maxOf(today.toEpochDay(), course.effectiveFromEpochDay ?: today.toEpochDay())
        for (offset in 0L..7L) {
            val start = startAt(course, LocalDate.ofEpochDay(firstDay + offset), table, zone) ?: continue
            val trigger = start - ADVANCE_MINUTES * 60_000L
            if (trigger > now) return trigger
        }
        return null
    }
}

internal object CourseReminders {
    private const val FILE = "course_reminder_settings"
    private const val GLOBAL = "enabled"
    private const val PREFIX = "meeting_"
    private const val DELIVERED_PREFIX = "delivered_"

    fun load(context: Context): CourseReminderSettings {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val globallyEnabled = runCatching { prefs.getBoolean(GLOBAL, false) }.getOrDefault(false)
        return CourseReminderSettings(globallyEnabled, prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(PREFIX) || value !is Boolean) null
            else key.removePrefix(PREFIX).toLongOrNull()?.let { it to value }
        }.toMap())
    }

    fun setGlobal(context: Context, enabled: Boolean): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(GLOBAL, enabled).commit()

    fun setOverride(context: Context, id: Long, enabled: Boolean?): Boolean {
        if (id <= 0) return false
        val edit = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (enabled == null) edit.remove(PREFIX + id) else edit.putBoolean(PREFIX + id, enabled)
        return edit.commit()
    }

    @Synchronized fun markNotified(context: Context, id: Long, expectedAt: Long): Boolean {
        if (id <= 0 || expectedAt <= 0) return false
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val key = DELIVERED_PREFIX + id
        if (runCatching { prefs.getLong(key, -1L) }.getOrDefault(-1L) >= expectedAt) return false
        return prefs.edit().putLong(key, expectedAt).commit()
    }

    fun restore(context: Context) {
        val runtime = CoreDataRuntimeAccess.resolve(context) as? CoreDataRuntimeResolution.Ready ?: return
        val snapshot = (runtime.repository.read() as? CoreDataReadResult.Ready)?.snapshot ?: return
        val store = PrototypeStore(context)
        sync(context, snapshot.courses, snapshot.courses, store.loadCoursePeriodTable(), load(context))
    }

    /** Cancel by meeting identity before replacing any old alarm with its current rule. */
    fun sync(context: Context, old: List<Course>, current: List<Course>, table: CoursePeriodTable,
             settings: CourseReminderSettings = load(context)) {
        (old.map { it.id } + current.map { it.id }).distinct().forEach { cancel(context, it) }
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val manager = context.getSystemService(AlarmManager::class.java)
        current.filter { settings.enabledFor(it) }.forEach { course ->
            val at = CourseReminderPolicy.nextTrigger(course, table, now, zone) ?: return@forEach
            val pending = pendingIntent(context, course.id, at)
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms())
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } catch (_: SecurityException) {
                // Permission may be revoked between the check and the scheduling call.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }
    }

    fun cancel(context: Context, id: Long) {
        val pending = PendingIntent.getBroadcast(context, 0, baseIntent(context, id),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        pending?.let { context.getSystemService(AlarmManager::class.java).cancel(it) }
    }

    fun currentOccurrence(context: Context, id: Long, expectedAt: Long): Pair<Course, Long>? {
        val runtime = CoreDataRuntimeAccess.resolve(context) as? CoreDataRuntimeResolution.Ready ?: return null
        val snapshot = (runtime.repository.read() as? CoreDataReadResult.Ready)?.snapshot ?: return null
        val course = snapshot.courses.singleOrNull { it.id == id } ?: return null
        if (!load(context).enabledFor(course)) return null
        val table = PrototypeStore(context).loadCoursePeriodTable()
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(expectedAt).atZone(zone).toLocalDate()
        val start = listOf(date, date.plusDays(1)).firstNotNullOfOrNull {
            CourseReminderPolicy.startAt(course, it, table, zone)
                ?.takeIf { value -> value - CourseReminderPolicy.ADVANCE_MINUTES * 60_000L == expectedAt }
        } ?: return null
        return course to start
    }

    private fun baseIntent(context: Context, id: Long) = Intent(context, ReminderReceiver::class.java).apply {
        action = ReminderReceiver.ACTION_COURSE_DUE
        data = Uri.parse("focusflow://course/reminder/$id")
    }

    private fun pendingIntent(context: Context, id: Long, at: Long): PendingIntent =
        PendingIntent.getBroadcast(context, 0, baseIntent(context, id).apply {
            putExtra(ReminderReceiver.EXTRA_COURSE_ID, id)
            putExtra(ReminderReceiver.EXTRA_COURSE_TRIGGER_AT, at)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** A one-off room change is keyed by the meeting and calendar date; future weeks keep the base location. */
internal object CourseLocationOverrides {
    private const val FILE = "course_location_overrides"

    fun get(context: Context, meetingId: Long, epochDay: Long): String? =
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getString("${meetingId}_$epochDay", null)?.takeIf { it.isNotBlank() }
        }.getOrNull()

    fun set(context: Context, meetingId: Long, epochDay: Long, place: String?): Boolean {
        if (meetingId <= 0 || epochDay < 0 || (place?.trim()?.length ?: 0) > 100) return false
        val edit = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        val key = "${meetingId}_$epochDay"
        if (place.isNullOrBlank()) edit.remove(key) else edit.putString(key, place.trim())
        return edit.commit()
    }
}
