package com.sakata.focusflow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun SleepHealthConnectSettings() {
    val context = LocalContext.current
    val store = remember { PrototypeStore(context) }
    val source = remember { HealthConnectSleepDataSource(context) }
    var enabled by remember { mutableStateOf(store.loadSleepDataEnabled()) }
    var permissionGranted by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf(store.loadSleepSummary()) }
    var status by remember { mutableStateOf("尚未连接") }
    var refreshToken by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(HealthConnectSleepDataSource.permissionContract) { granted ->
        permissionGranted = granted.containsAll(source.readPermissions)
        status = if (permissionGranted) "已授权，正在同步" else "未授予睡眠读取权限"
        if (permissionGranted) refreshToken++
    }

    LaunchedEffect(enabled, refreshToken) {
        if (!enabled) {
            status = "已关闭；已有摘要保留在本机"
            return@LaunchedEffect
        }
        when (source.availability()) {
            SleepSourceAvailability.UNAVAILABLE -> status = "此设备没有可用的 Health Connect"
            SleepSourceAvailability.UPDATE_REQUIRED -> status = "Health Connect 需要更新"
            SleepSourceAvailability.AVAILABLE -> runCatching {
                permissionGranted = source.hasReadPermission()
                if (!permissionGranted) {
                    status = "等待授权读取睡眠"
                } else {
                    status = "正在同步昨夜睡眠"
                    source.readLastMainSleep()?.also {
                        store.saveSleepSummary(it)
                        summary = it
                    }
                    status = if (summary == null) "最近 36 小时没有至少 3 小时的睡眠记录" else "已同步"
                }
            }.onFailure { status = "同步失败；不会影响精力记录" }
        }
    }

    Column {
        SettingSwitch(
            "从 Health Connect 读取睡眠",
            "默认关闭；只读昨夜主要睡眠摘要，不读取或写入其他健康数据",
            enabled
        ) {
            enabled = it
            store.saveSleepDataEnabled(it)
        }
        if (enabled) {
            CollapsibleSettingsDetails(summary = summary?.let { "最近 ${it.durationMinutes / 60} 小时 ${it.durationMinutes % 60} 分钟 · $status" } ?: status) {
                Spacer(Modifier.height(6.dp))
                Text(status)
                if (source.availability() == SleepSourceAvailability.AVAILABLE && !permissionGranted) {
                    OutlinedButton(onClick = { permissionLauncher.launch(source.readPermissions) }) { Text("授权读取睡眠") }
                } else if (permissionGranted) {
                    OutlinedButton(onClick = { refreshToken++ }) { Text("同步昨夜睡眠") }
                }
                summary?.let {
                    Text("最近摘要：${it.durationMinutes / 60} 小时 ${it.durationMinutes % 60} 分钟 · ${formatDateTime(it.endAt)}结束")
                }
            }
        }
    }
}
