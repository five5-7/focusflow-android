package com.sakata.focusflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun VisionReviewDialog(
    payload: VisionRecognitionPreview,
    onApply: (VisionReviewResult) -> Unit,
    onDismiss: () -> Unit
) {
    val decisions = remember { mutableStateMapOf<String, VisionReviewDecision>() }
    val editingId = remember { mutableStateOf<String?>(null) }
    val editTitle = remember { mutableStateOf("") }
    val editDay = remember { mutableStateOf("") }
    val editStart = remember { mutableStateOf("") }
    val editEnd = remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("检查课表识别") },
        text = {
            Column {
                if (payload.warnings.isNotEmpty()) {
                    Text(payload.warnings.joinToString("；"), color = MaterialTheme.colorScheme.error)
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(payload.candidates, key = { it.id }) { candidate ->
                        val cell = payload.preview.cells[candidate.id]
                        val editing = editingId.value == candidate.id
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(candidate.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "候选 ${candidate.id} · " +
                                        (cell?.let { "定位：周${it.day} 第${it.startPeriod}-${it.endPeriod}节" } ?: "无法唯一定位"),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                candidate.rawLocation?.takeIf { it.isNotBlank() }?.let {
                                    Text("地点：$it", style = MaterialTheme.typography.bodySmall)
                                }
                                if (editing) {
                                    OutlinedTextField(editTitle.value, { editTitle.value = it }, label = { Text("课程名") }, singleLine = true)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedTextField(editDay.value, { editDay.value = it }, label = { Text("星期") }, modifier = Modifier.weight(1f), singleLine = true)
                                        OutlinedTextField(editStart.value, { editStart.value = it }, label = { Text("起始节") }, modifier = Modifier.weight(1f), singleLine = true)
                                        OutlinedTextField(editEnd.value, { editEnd.value = it }, label = { Text("结束节") }, modifier = Modifier.weight(1f), singleLine = true)
                                    }
                                    Row {
                                        TextButton(onClick = {
                                            decisions[candidate.id] = VisionReviewDecision(
                                                candidateId = candidate.id,
                                                action = VisionReviewAction.EDIT,
                                                title = editTitle.value.trim().ifBlank { candidate.title },
                                                day = editDay.value.toIntOrNull(),
                                                startPeriod = editStart.value.toIntOrNull(),
                                                endPeriod = editEnd.value.toIntOrNull()
                                            )
                                            editingId.value = null
                                        }) { Text("保存编辑") }
                                        TextButton(onClick = { editingId.value = null }) { Text("取消") }
                                    }
                                } else {
                                    Row {
                                        TextButton(onClick = {
                                            decisions[candidate.id] = VisionReviewDecision(candidate.id, VisionReviewAction.CONFIRM)
                                        }) { Text("确认") }
                                        TextButton(onClick = {
                                            editingId.value = candidate.id
                                            editTitle.value = candidate.title
                                            editDay.value = (candidate.day ?: cell?.day)?.toString().orEmpty()
                                            editStart.value = (candidate.startPeriod ?: cell?.startPeriod)?.toString().orEmpty()
                                            editEnd.value = (candidate.endPeriod ?: cell?.endPeriod)?.toString().orEmpty()
                                        }) { Text("编辑") }
                                        TextButton(onClick = {
                                            decisions[candidate.id] = VisionReviewDecision(candidate.id, VisionReviewAction.REJECT)
                                        }) { Text("拒绝") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onApply(VisionReviewApplier.apply(payload.preview, payload.candidates, decisions.values.toList()))
            }) { Text("应用审核结果") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun VisionAnchorEditor(
    anchors: VisionAnchorSet,
    onChanged: (VisionAnchorSet) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("人工校正四角", style = MaterialTheme.typography.titleSmall)
        anchors.anchors.sortedBy { it.corner.ordinal }.forEach { anchor ->
            val x = remember(anchor) { mutableStateOf(anchor.x.toString()) }
            val y = remember(anchor) { mutableStateOf(anchor.y.toString()) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(anchor.corner.name, modifier = Modifier.weight(1f))
                OutlinedTextField(x.value, { value ->
                    x.value = value
                    value.toDoubleOrNull()?.let { onChanged(anchors.replace(anchor.copy(x = it))) }
                }, label = { Text("x") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(y.value, { value ->
                    y.value = value
                    value.toDoubleOrNull()?.let { onChanged(anchors.replace(anchor.copy(y = it))) }
                }, label = { Text("y") }, modifier = Modifier.weight(1f), singleLine = true)
            }
        }
    }
}

private fun VisionAnchorSet.replace(updated: VisionAnchor): VisionAnchorSet =
    copy(anchors = anchors.map { if (it.corner == updated.corner) updated else it })
