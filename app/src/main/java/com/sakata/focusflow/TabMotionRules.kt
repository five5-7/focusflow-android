package com.sakata.focusflow

/**
 * 8.1.0 第三轮：页签转场的判定规则（纯函数，便于单测）。
 *
 * 这套规则原先散在 Composable 里，只能靠真机逐例试，结果同一个判断反复出错：
 * - 收起动画只应在"本次导航离开的、正在看子页的页签"上播放，且必须在**应用新快照之前**判定；
 * - 页签直达、回退/折返恢复若改掉了目标页签的子页状态，这次子页切换不该补播动画；
 * - 隐藏页签仍开着子页时停在图标大小，回到它时从图标放大；已回到主页的页签只平移。
 */
internal object TabMotionRules {
    /** 没有页签在离开子页。 */
    const val NO_TAB = -1

    /** 指定页签此刻是否停在子页。 */
    fun subpageOpenOn(tab: Int, snapshot: PageSnapshot): Boolean = when (tab) {
        PageSnapshot.TAB_TODAY -> snapshot.todayInboxOpen
        PageSnapshot.TAB_PLANS -> snapshot.planPage != null
        PageSnapshot.TAB_SETTINGS -> snapshot.settingsSubPage != null
        else -> false
    }

    /** 本次导航离开的、正在看子页的页签；没有则返回 [NO_TAB]。 */
    fun departingSubpageTab(current: PageSnapshot, next: PageSnapshot): Int =
        if (next.tab != current.tab && subpageOpenOn(current.tab, current)) current.tab else NO_TAB

    /**
     * 本次导航是否改掉了**目标页签**的子页状态。
     * 是则这次子页切换属于"顺带发生"，不该补播收起/放大动画。
     */
    fun destinationSubpageChanged(current: PageSnapshot, next: PageSnapshot): Boolean {
        if (next.tab == current.tab) return false
        return when (next.tab) {
            PageSnapshot.TAB_TODAY -> next.todayInboxOpen != current.todayInboxOpen
            PageSnapshot.TAB_PLANS -> next.planPage != current.planPage
            PageSnapshot.TAB_SETTINGS -> next.settingsSubPage != current.settingsSubPage
            else -> false
        }
    }

    /** 隐藏页签的静止缩放：仍开着子页 → 图标大小；跳转 → 0.85；否则 1.0。 */
    fun restingScale(hasSubpage: Boolean, jumped: Boolean): Float = when {
        hasSubpage -> MotionSpec.COLLAPSE_SCALE
        jumped -> 0.85f
        else -> 1f
    }

    /** 到达该页签时是否从图标放大：只有仍开着子页才放大，否则瞬时归位、只平移。 */
    fun growsOnArrival(hasSubpage: Boolean): Boolean = hasSubpage
}
