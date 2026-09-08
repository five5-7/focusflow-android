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
}

/**
 * 会话内全局页面历史（浏览器式，仅内存、跨重启不保留）。
 *
 * - [goTo]：任何一次页面目的地变化（点页签、开/关子页、系统返回引发的页面变化）都先
 *   把当前快照压入回退栈，并清空折返栈（新导航截断前进分支）。
 * - [back]/[forward]：只移动栈指针与 current，不额外记录，避免回退/折返本身制造历史。
 * - 栈深有限（默认 64），超限丢弃最旧记录。
 */
internal class NavHistory(private val capacity: Int = 64) {
    private val backStack = ArrayDeque<PageSnapshot>()
    private val forwardStack = ArrayDeque<PageSnapshot>()

    var current: PageSnapshot = PageSnapshot.ROOT
        private set

    /** 记录一次用户导航并应用新目的地；目的地未变化则忽略。 */
    fun goTo(next: PageSnapshot): PageSnapshot? {
        if (next == current) return null
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
}
