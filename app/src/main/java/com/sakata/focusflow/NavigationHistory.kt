package com.sakata.focusflow

/**
 * 页面目的地快照：覆盖全部页面级导航状态。
 * 弹窗不属于页面层级（弹窗内容草稿由 [DraftVault] 保存），不计入历史。
 * 8.1.0 导航历史：回退/折返键按此快照恢复页面状态，系统返回保持原层级行为不变。
 */
internal data class PageSnapshot(
    val tab: Int,
    val todayInboxOpen: Boolean,
    val planPage: PlanPage?,
    val settingsSubPage: SettingsSubPage?,
    val settingsBackStack: List<SettingsSubPage>
) {
    companion object {
        const val TAB_TODAY = 0
        const val TAB_SCHEDULE = 1
        const val TAB_PLANS = 2
        const val TAB_SETTINGS = 3

        /** 应用冷启动的初始目的地：今日主页。 */
        val ROOT = PageSnapshot(TAB_TODAY, false, null, null, emptyList())
    }

    /** 是否处于无子页的页签主页（决定根页面返回是否触发退出提示）。 */
    fun isTabRoot(): Boolean = !todayInboxOpen && planPage == null && settingsSubPage == null

    /** 历史列表展示用标签。 */
    val label: String
        get() = when (tab) {
            TAB_TODAY -> if (todayInboxOpen) "今日 · 收集箱" else "今日主页"
            TAB_SCHEDULE -> "日程"
            TAB_PLANS -> planPage?.title?.let { "计划 · $it" } ?: "计划主页"
            else -> settingsSubPage?.title?.let { "设置 · $it" } ?: "设置主页"
        }
}

/**
 * 会话内全局页面历史（浏览器式，仅内存、跨重启不保留）。
 *
 * - [goTo]：任何一次页面目的地变化（点页签、开/关子页、系统返回引发的页面变化）都记录；
 *   目的地未变化忽略；「A→B→A」自动抵消为未发生（点错又点回不污染历史）。
 * - [back]/[forward]：只移动栈指针与 current，不额外记录。
 * - [jumpTo]：跳到历史列表中的某个目的地，其后的历史全部截断。
 * - 栈深有限（默认 30），超限丢弃最旧记录。
 */
internal class NavHistory(private val capacity: Int = 30) {
    private val backStack = ArrayDeque<PageSnapshot>()
    private val forwardStack = ArrayDeque<PageSnapshot>()

    var current: PageSnapshot = PageSnapshot.ROOT
        private set

    /** 记录一次用户导航并应用新目的地；无变化或去抖抵消时返回 null。 */
    fun goTo(next: PageSnapshot): PageSnapshot? {
        if (next == current) return null
        if (backStack.isNotEmpty() && next == backStack.last()) {
            // 去抖：切到 B 又切回上一个目的地，B 不入历史。
            backStack.removeLast()
            forwardStack.clear()
            current = next
            return next
        }
        backStack.addLast(current)
        while (backStack.size > capacity) backStack.removeFirst()
        forwardStack.clear()
        current = next
        return next
    }

    fun canGoBack(): Boolean = backStack.isNotEmpty()

    /** 回退一步：当前快照移入折返栈，恢复上一快照；到顶返回 null。 */
    fun back(): PageSnapshot? {
        if (backStack.isEmpty()) return null
        forwardStack.addLast(current)
        current = backStack.removeLast()
        return current
    }

    fun canGoForward(): Boolean = forwardStack.isNotEmpty()

    /** 折返一步：当前快照移入回退栈，恢复下一快照；到底返回 null。 */
    fun forward(): PageSnapshot? {
        if (forwardStack.isEmpty()) return null
        backStack.addLast(current)
        current = forwardStack.removeLast()
        return current
    }

    /** 回退轨迹（最旧 → 当前），供历史列表展示。 */
    fun entries(): List<PageSnapshot> = backStack.toList() + current

    /** 跳到历史列表中的某个目的地：其后的历史全部截断；目标不在轨迹中返回 false。 */
    fun jumpTo(target: PageSnapshot): Boolean {
        if (target == current) return true
        val idx = backStack.indexOf(target)
        if (idx < 0) return false
        val prefix = ArrayList<PageSnapshot>(idx)
        for (i in 0 until idx) prefix.add(backStack.elementAt(i))
        backStack.clear()
        prefix.forEach { backStack.addLast(it) }
        forwardStack.clear()
        current = target
        return true
    }
}
