package com.sakata.focusflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 8.1.0 动画速度设置（外观页）：全局时长倍率，影响页面转场与底部导航动画。
 * 0f=关闭（瞬时），0.5f=较快，1f=标准，1.5f=较慢。
 * 与 StorageProtection 同一模式：全局可观察状态；设置页写入并持久化，动画处读取换算。
 */
internal object MotionSettings {
    var durationScale by mutableStateOf(1f)
        private set

    fun update(scale: Float) {
        durationScale = scale.coerceIn(0f, 1.5f)
    }
}

/** 按动画速度设置换算动画时长（毫秒）。 */
internal fun motionMillis(base: Int): Int = (base * MotionSettings.durationScale).toInt().coerceIn(0, 4000)
