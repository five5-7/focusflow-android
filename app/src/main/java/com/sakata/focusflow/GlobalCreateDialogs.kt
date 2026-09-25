package com.sakata.focusflow

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Calendar

@Composable
internal fun SimpleTitleDialog(title: String, label: String, onDismiss: () -> Unit, onSave: (String) -> Boolean) {
    var value by remember { mutableStateOf("") }
    AppDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value, { value = it.take(200) }, label = { Text(label) }, singleLine = true) },
        confirmButton = { Button(enabled = value.isNotBlank(), onClick = { onSave(value.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun GlobalTimeCreateDialog(title: String, onDismiss: () -> Unit,
                                    existing: List<StandaloneReminder> = emptyList(),
                                    onCompleteReminder: (StandaloneReminder) -> Unit = {},
                                    onSave: (String, Long) -> Boolean) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var selectedAt by remember { mutableLongStateOf(System.currentTimeMillis() + 60 * 60_000L) }
    AppDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(200) }, label = { Text("名称") }, singleLine = true)
            Text("时间：${formatDateTime(selectedAt)}")
            OutlinedButton(onClick = {
                val initial = Calendar.getInstance().apply { timeInMillis = selectedAt }
                DatePickerDialog(context, { _, year, month, day ->
                    selectedAt = Calendar.getInstance().apply {
                        timeInMillis = selectedAt
                        set(year, month, day)
                    }.timeInMillis
                }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH)).show()
            }) { Text("选择日期") }
            OutlinedButton(onClick = {
                val initial = Calendar.getInstance().apply { timeInMillis = selectedAt }
                TimePickerDialog(context, { _, hour, minute ->
                    selectedAt = Calendar.getInstance().apply {
                        timeInMillis = selectedAt
                        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                }, initial.get(Calendar.HOUR_OF_DAY), initial.get(Calendar.MINUTE), true).show()
            }) { Text("选择时间") }
            Text(if (title == "新建提醒") "独立提醒不会变成待办。" else "将出现在日程和待办中。",
                style = MaterialTheme.typography.bodySmall)
            if (existing.isNotEmpty()) {
                HorizontalDivider()
                Text("已有提醒", style = MaterialTheme.typography.titleSmall)
                existing.filter { it.completedAt == null }.take(5).forEach { reminder ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${reminder.title} · ${formatDateTime(reminder.triggerAt)}",
                            modifier = Modifier.weight(1f), maxLines = 1)
                        TextButton(onClick = { onCompleteReminder(reminder) }) { Text("取消") }
                    }
                }
            }
        } },
        confirmButton = { Button(enabled = name.isNotBlank() && selectedAt > System.currentTimeMillis(),
            onClick = { onSave(name.trim(), selectedAt) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
