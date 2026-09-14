package com.sakata.focusflow

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 当前版本只在真正缺少时提示；应用检测未启用时不把使用情况访问列为待办。 */
internal fun reminderPermissionsMissing(context: Context): List<MissingPermission> =
    PermissionReminderPolicy.missing(
        notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        exactAlarmsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
        usageAccessRequired = false,
        usageAccessGranted = true
    )

internal fun openPermissionSettings(context: Context, permission: MissingPermission) {
    when (permission) {
        MissingPermission.NOTIFICATIONS -> context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
        MissingPermission.EXACT_ALARMS -> context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        )
        MissingPermission.USAGE_ACCESS -> AppLibrary.openUsageAccessSettings(context)
    }
}

@Composable
internal fun PermissionRequirementsDialog(
    missing: List<MissingPermission>,
    todayReminderDismissed: Boolean,
    onDismiss: () -> Unit,
    onRestoreTodayReminder: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("权限与提醒") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("只在你使用对应能力时需要；不开启可选权限不会影响手动记录和安排。", style = MaterialTheme.typography.bodySmall)
                PermissionRequirementGroup("必要权限", listOf(MissingPermission.NOTIFICATIONS), missing)
                PermissionRequirementGroup("可选权限", listOf(MissingPermission.EXACT_ALARMS), missing)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (todayReminderDismissed) TextButton(onClick = onRestoreTodayReminder) { Text("恢复今日提示") }
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        }
    )
}

@Composable
private fun PermissionRequirementGroup(
    title: String,
    permissions: List<MissingPermission>,
    missing: List<MissingPermission>
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        permissions.forEach { permission ->
            val isMissing = permission in missing
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(permission.label, style = MaterialTheme.typography.bodyMedium)
                    Text(permission.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isMissing) {
                    TextButton(onClick = { openPermissionSettings(context, permission) }) { Text("设置") }
                } else Text("已设置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
