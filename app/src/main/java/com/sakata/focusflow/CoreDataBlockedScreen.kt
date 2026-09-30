package com.sakata.focusflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sakata.focusflow.data.CoreDataActivationDecision

@Composable
internal fun CoreDataBlockedScreen(decision: CoreDataActivationDecision) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("任务数据启动已暂停", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "FocusFlow 无法安全确定唯一数据源。为避免旧数据与数据库同时写入，任务、计划和提醒动作暂时不会继续。",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "关闭并重新打开应用后会重新检查；若仍出现，请保留下面的诊断信息。",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "诊断：${decision.status.name}" + decision.message
                        .takeIf { it.isNotBlank() }
                        ?.let { "\n$it" }
                        .orEmpty(),
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
