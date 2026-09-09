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

    /**
     * 是否处于"当前页签的主页"（决定规则 v3 的主页↔主页不记录、以及 [NavHistory.markWorkedHere]）。
     * 只看当前页签：别的页签遗留在后台的子页状态（切走后保留）不代表用户正在看子页。
     */
    fun isTabRoot(): Boolean = when (tab) {
        TAB_TODAY -> !todayInboxOpen
        TAB_PLANS -> planPage == null
        TAB_SETTINGS -> settingsSubPage == null
        else -> true
    }

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

    /** 记录一次用户导航并应用新目的地；无变化、去抖抵消或主页↔主页时返回 null。 */
    fun goTo(next: PageSnapshot): PageSnapshot? {
        if (next == current) return null
        if (backStack.isNotEmpty() && next == backStack.last()) {
            // 去抖：切到 B 又切回上一个目的地，B 不入历史。
            backStack.removeLast()
            forwardStack.clear()
            current = next
            return next
        }
        // 规则 v3：主页↔主页的跳转不记录（底栏一键可达，不值得回退）。
        if (current.isTabRoot() && next.isTabRoot()) {
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

    /**
     * 规则 v3 数据操作兜底：在主页做了实质数据操作时调用，把当前主页记为可回退的一步（幂等）。
     * 子页无需处理（进入子页时已记录）。
     */
    fun markWorkedHere() {
        if (!current.isTabRoot()) return
        if (backStack.isNotEmpty() && backStack.last() == current) return
        backStack.addLast(current)
        while (backStack.size > capacity) backStack.removeFirst()
        forwardStack.clear()
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
