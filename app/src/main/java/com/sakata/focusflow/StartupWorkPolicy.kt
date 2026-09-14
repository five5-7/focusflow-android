package com.sakata.focusflow

/** 首屏稳定后才允许执行的非关键工作，集中定义以免重新挤占首次动画。 */
internal object StartupWorkPolicy {
    const val TAB_WARMUP_IDLE_MS = 1_200L
    const val TAB_WARMUP_GAP_MS = 350L

    fun canWarmTabs(globalLoading: Boolean, dialogVisible: Boolean): Boolean =
        !globalLoading && !dialogVisible

    fun pendingTabs(visited: Collection<Int>, current: Int): List<Int> =
        (0..3).filter { it != current && it !in visited }
}
