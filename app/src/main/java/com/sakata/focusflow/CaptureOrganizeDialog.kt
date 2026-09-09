package com.sakata.focusflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 收集箱整理弹窗草稿：关闭后重开恢复正在填写的“可执行的下一步”（8.1.0 草稿保险箱）。 */
private data class CaptureOrganizeDraft(val nextAction: String)

@Composable
internal fun CaptureOrganizeDialog(
    item: Item,
    onDismiss: () -> Unit,
    onProgress: (String) -> Unit,
    onReference: () -> Unit,
    onConvertToGoal: () -> Unit,
    onAttachToPlan: () -> Unit
) {
    val vault = LocalDraftVault.current
    val draftKey = "organize:${item.id}"
    val saved = vault.load<CaptureOrganizeDraft>(draftKey)
    var nextAction by remember(item.id, item.nextAction) { mutableStateOf(saved?.nextAction ?: item.nextAction) }
    fun persist() = vault.save(draftKey, CaptureOrganizeDraft(nextAction))
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("整理《${item.title}》") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "原说明会保留。只有确实需要时间的下一步才进入日程。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = nextAction,
                    onValueChange = { nextAction = it; persist() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("可执行的下一步") },
                    placeholder = { Text("例如：找回课程，确认上次停在哪里") },
                    minLines = 2
                )
                if (item.parentCaptureId != null) Text("这是已有方向的一步，不能再嵌套方向；留作参考后会解除关联。", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { vault.clear(draftKey); onProgress(nextAction.trim()) },
                    enabled = nextAction.isNotBlank() && item.parentCaptureId == null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存为逐步推进") }
                OutlinedButton(onClick = onReference, modifier = Modifier.fillMaxWidth()) { Text("留作参考") }
                if (CaptureRoute.fromKey(item.captureRoute) == CaptureRoute.INBOX) {
                    TextButton(onClick = onAttachToPlan, modifier = Modifier.fillMaxWidth()) { Text("归入已有目标") }
                    TextButton(onClick = onConvertToGoal, modifier = Modifier.fillMaxWidth()) { Text("转成新目标") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
