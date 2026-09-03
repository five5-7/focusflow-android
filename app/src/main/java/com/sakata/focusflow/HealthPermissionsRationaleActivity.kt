package com.sakata.focusflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class HealthPermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("睡眠数据权限说明", style = MaterialTheme.typography.headlineMedium)
                        Text("FocusFlow 仅申请读取 Health Connect 中的睡眠记录，用于在本机分析睡眠时长与不同时间段精力记录的关系。")
                        Text("应用不会写入、修改或删除健康数据，也不会读取心率、步数等其他数据。没有真实睡眠记录时不会生成默认数据。")
                        Text("睡眠摘要与分析保存在本机；关闭睡眠数据开关后将停止读取。睡眠信息不会自动修改日程或替代你的实际感受。")
                        Button(onClick = { finish() }) { Text("返回") }
                    }
                }
            }
        }
    }
}
