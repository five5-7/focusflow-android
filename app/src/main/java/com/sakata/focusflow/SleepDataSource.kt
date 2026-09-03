package com.sakata.focusflow

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant

data class SleepSummary(
    val startAt: Long,
    val endAt: Long,
    val durationMinutes: Int,
    val sourcePackage: String,
    val syncedAt: Long
)

enum class SleepSourceAvailability { AVAILABLE, UPDATE_REQUIRED, UNAVAILABLE }

interface SleepDataSource {
    val readPermissions: Set<String>
    fun availability(): SleepSourceAvailability
    suspend fun hasReadPermission(): Boolean
    suspend fun readLastMainSleep(now: Long = System.currentTimeMillis()): SleepSummary?
}

object SleepSummaryPolicy {
    const val MAX_AGE_MILLIS = 36 * 60 * 60_000L

    fun display(summary: SleepSummary?, now: Long = System.currentTimeMillis()): String? {
        if (summary == null || summary.endAt > now + 5 * 60_000L || now - summary.endAt > MAX_AGE_MILLIS) return null
        return "Health Connect：昨夜约 ${summary.durationMinutes / 60} 小时 ${summary.durationMinutes % 60} 分钟；仅作精力背景，以你的实际感受为准。"
    }
}

class HealthConnectSleepDataSource(private val context: Context) : SleepDataSource {
    override val readPermissions: Set<String> = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))

    override fun availability(): SleepSourceAvailability = when (
        HealthConnectClient.getSdkStatus(context, HealthConnectClient.DEFAULT_PROVIDER_PACKAGE_NAME)
    ) {
        HealthConnectClient.SDK_AVAILABLE -> SleepSourceAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> SleepSourceAvailability.UPDATE_REQUIRED
        else -> SleepSourceAvailability.UNAVAILABLE
    }

    override suspend fun hasReadPermission(): Boolean {
        if (availability() != SleepSourceAvailability.AVAILABLE) return false
        return client().permissionController.getGrantedPermissions().containsAll(readPermissions)
    }

    override suspend fun readLastMainSleep(now: Long): SleepSummary? {
        if (!hasReadPermission()) return null
        val end = Instant.ofEpochMilli(now)
        val response = client().readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(end.minus(Duration.ofHours(36)), end)
            )
        )
        return response.records
            .filter { Duration.between(it.startTime, it.endTime).toMinutes() >= MIN_MAIN_SLEEP_MINUTES }
            .maxByOrNull { it.endTime }
            ?.let { record ->
                SleepSummary(
                    startAt = record.startTime.toEpochMilli(),
                    endAt = record.endTime.toEpochMilli(),
                    durationMinutes = Duration.between(record.startTime, record.endTime).toMinutes().toInt(),
                    sourcePackage = record.metadata.dataOrigin.packageName,
                    syncedAt = now
                )
            }
    }

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    companion object {
        const val MIN_MAIN_SLEEP_MINUTES = 3 * 60L
        val permissionContract get() = PermissionController.createRequestPermissionResultContract()
    }
}
