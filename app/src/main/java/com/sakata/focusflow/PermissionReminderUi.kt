package com.sakata.focusflow

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** 8.3 权限中心的运行时快照；厂商私有开关只标“手动确认”。 */
internal fun permissionCenterEntries(context: Context): List<PermissionCenterEntry> {
    val health = NotificationChannelSettings.health(context)
    val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    val batteryUnrestricted = context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName)
    val detectionEnabled = PrototypeStore(context).loadGameDetectionEnabled()
    val usageAllowed = !detectionEnabled || AppLibrary.hasUsageAccess(context)
    return PermissionCenterPolicy.entries(
        notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        taskChannelReady = health.taskBannerAllowed,
        exactAlarmAllowed = exactAllowed,
        batteryUnrestricted = batteryUnrestricted,
        appDetectionEnabled = detectionEnabled,
        usageAccessAllowed = usageAllowed
    )
}

internal fun openPermissionCenterItem(context: Context, item: PermissionCenterItem) {
    val packageUri = Uri.parse("package:${context.packageName}")
    val intent = when (item) {
        PermissionCenterItem.NOTIFICATIONS, PermissionCenterItem.BANNER ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS, packageUri)
        PermissionCenterItem.TASK_CHANNEL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, ReminderReceiver.CHANNEL_TASK)
        PermissionCenterItem.EXACT_ALARM -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
        PermissionCenterItem.BATTERY_UNRESTRICTED, PermissionCenterItem.AUTOSTART ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        PermissionCenterItem.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)) } }
}

@Composable
internal fun PermissionRequirementsDialog(
    todayReminderDismissed: Boolean,
    onDismiss: () -> Unit,
    onRestoreTodayReminder: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var entries by remember { mutableStateOf(permissionCenterEntries(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) entries = permissionCenterEntries(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("权限与提醒") },
        text = {
            ScrollableDialogBox(maxHeight = 520.dp, spacing = 12.dp) {
                Text("系统可读取的状态会自动更新；ColorOS 私有开关需要手动确认。", style = MaterialTheme.typography.bodySmall)
                PermissionCenterSection(
                    "提醒所需",
                    entries.filter { it.item.group == PermissionCenterGroup.REQUIRED_FOR_REMINDERS }
                )
                PermissionCenterSection(
                    "体验增强",
                    entries.filter { it.item.group == PermissionCenterGroup.OPTIONAL }
                )
                Text(
                    "OPPO／一加／realme：设置 → 电池 → 应用耗电管理 → FocusFlow，检查允许后台运行、自启动与关联启动；再用一分钟测试验证实际送达。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
private fun PermissionCenterSection(title: String, entries: List<PermissionCenterEntry>) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        entries.forEach { entry ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(entry.item.label, style = MaterialTheme.typography.bodyMedium)
                    Text(entry.item.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (entry.status) {
                    PermissionCenterStatus.READY -> PermissionStatusText("已开启")
                    PermissionCenterStatus.NOT_IN_USE -> PermissionStatusText("未使用")
                    PermissionCenterStatus.ACTION_NEEDED -> TextButton(onClick = { openPermissionCenterItem(context, entry.item) }) { Text("去设置") }
                    PermissionCenterStatus.MANUAL_CHECK -> TextButton(onClick = { openPermissionCenterItem(context, entry.item) }) { Text("手动检查") }
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusText(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}
