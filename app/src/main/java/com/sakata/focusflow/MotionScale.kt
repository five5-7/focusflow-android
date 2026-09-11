package com.sakata.focusflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 8.1.0 动画速度设置（外观页）：全局时长倍率，影响页面转场与底部导航动画。
 * 0f=关闭（瞬时），0.5f=较快，1f=标准，1.5f=较慢。
 * 与 StorageProtection 同一模式：全局可观察状态；设置页写入并持久化，动画处读取换算。
 *
 * 8.2.0 起还承载「丰富的动画与外观效果」里的**动画形态**开关 [richForms]：
 * 关掉时不只是变快，而是把转场的**形变幅度收成 0**（位移 0、缩放 1、底栏不形变），
 * 于是原有的"淡入淡出 + 形变"只剩淡入淡出——正合开关文案承诺的
 * "只留最基本的淡入淡出与纯色配色"。
 *
 * 为什么用"收成 0"而不是"换一套转场实现"：各页的转场本来就是
 * `fade + transform` 组合出来的，把 transform 的差值置为恒等即可退化成纯淡入淡出，
 * 不需要在十几个调用点各写一遍分支，也不会出现"某个页面忘了改"的漏网。
 */
internal object MotionSettings {
    var durationScale by mutableStateOf(1f)
        private set

    /** 是否使用丰富的动画形态（位移/缩放/形变）。默认 true = 现状。 */
    var richForms by mutableStateOf(true)
        private set

    fun update(scale: Float) {
        durationScale = scale.coerceIn(0f, 1.5f)
    }

    fun updateRichForms(rich: Boolean) {
        richForms = rich
    }
}

/** 按动画速度设置换算动画时长（毫秒）。 */
internal fun motionMillis(base: Int): Int = (base * MotionSettings.durationScale).toInt().coerceIn(0, 4000)
