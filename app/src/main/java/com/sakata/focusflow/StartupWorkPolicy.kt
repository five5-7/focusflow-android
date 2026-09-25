package com.sakata.focusflow

/** 首屏稳定后才允许执行的非关键工作，集中定义以免重新挤占首次动画。 */
internal object StartupWorkPolicy {
    /** 完全未触摸时也要给图片上传与首屏亚克力建层留下稳定窗口。 */
    const val INITIAL_WARMUP_IDLE_MS = 2_400L

    /** 用户已经开始操作后，以最后一次按下为起点重新等待；任何新按下都会取消本轮。 */
    const val AFTER_INTERACTION_IDLE_MS = 1_400L

    fun canWarmTabs(globalLoading: Boolean, dialogVisible: Boolean): Boolean =
        !globalLoading && !dialogVisible

    fun warmupIdleMs(hasInteracted: Boolean): Long =
        if (hasInteracted) AFTER_INTERACTION_IDLE_MS else INITIAL_WARMUP_IDLE_MS

    fun pendingTabs(visited: Collection<Int>, current: Int): List<Int> =
        (0..3).filter { it != current && it !in visited }

    /** 每个空闲窗口只预热一个完整页签，避免图片背景与玻璃卡片连续建层。 */
    fun nextPendingTab(visited: Collection<Int>, current: Int): Int? =
        pendingTabs(visited, current).firstOrNull()
}
