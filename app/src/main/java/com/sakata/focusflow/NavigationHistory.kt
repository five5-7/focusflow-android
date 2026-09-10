package com.sakata.focusflow

/**
 * 页面目的地快照：覆盖全部页面级导航状态。
 *
 * [dialogOpen] 记录"当前这个页面上有一个弹窗浮层"（8.1.0 第四轮）。
 * 它**不是**独立的一步历史，而是当前状态的属性：「副页 + 弹窗 + 已填数据」算同一个整体，
 * 从别处上一步回来时整包还原（弹窗内容由调用点状态与 [DraftVault] 保存）。
 */
internal data class PageSnapshot(
    val tab: Int,
    val todayInboxOpen: Boolean,
    val planPage: PlanPage?,
    val settingsSubPage: SettingsSubPage?,
    val settingsBackStack: List<SettingsSubPage>,
    val dialogOpen: Boolean = false
) {

    /** 去掉弹窗层后的同页快照：历史列表展示与跳转都按页面粒度处理。 */
    fun withoutDialog(): PageSnapshot = if (dialogOpen) copy(dialogOpen = false) else this
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
 * - [setDialogLayer]/[clearDialogLayer]：弹窗浮层作为历史的一步（见 [PageSnapshot.dialogOpen]）。
 * - 栈深有限（默认 30），超限丢弃最旧记录。
 */
internal class NavHistory(private val capacity: Int = 30) {
    private val backStack = ArrayDeque<PageSnapshot>()
    private val forwardStack = ArrayDeque<PageSnapshot>()

    var current: PageSnapshot = PageSnapshot.ROOT
        private set

    /** 记录一次用户导航并应用新目的地；无变化、去抖抵消或主页↔主页时返回 null。 */
    fun goTo(next: PageSnapshot): PageSnapshot? {
        // 只是"同一页面上的弹窗开关"变化时不当作一次导航：保留弹窗层原样（不新增历史）。
        val target = if (next.withoutDialog() == current.withoutDialog()) current else next.withoutDialog()
        if (target == current) return null
        // 去抖：切到 B 又切回上一个目的地，B 不入历史。
        // 例外：B 上开着弹窗——「副页 + 弹窗 + 已填数据」是一个整体，那是用户真的到过、动过的地方，
        // 必须留在历史里；否则"点本页签回主页"这一步会把整体吃掉，上一步再也回不去（真机复现过）。
        if (backStack.isNotEmpty() && target == backStack.last() && !current.dialogOpen) {
            backStack.removeLast()
            forwardStack.clear()
            current = target
            return target
        }
        // 规则 v3：主页↔主页的跳转不记录（底栏一键可达，不值得回退）。
        if (current.isTabRoot() && target.isTabRoot()) {
            forwardStack.clear()
            current = target
            return target
        }
        backStack.addLast(current)
        while (backStack.size > capacity) backStack.removeFirst()
        forwardStack.clear()
        current = target
        return target
    }

    /**
     * 弹窗浮层开／关**本身不算一步历史**（用户口径）：
     * 它是当前状态的属性——「副页 + 弹窗 + 已填数据」是同一个整体，
     * 因此只就地更新 current，不压栈、不产生折返。
     *
     * 于是：
     * - 在副页开着弹窗时按上一步 → 回到上一个页面（这一步本来就存在），弹窗随整体一起收起；
     * - 从别处（管理地点／其他页／本页签主页）按上一步回来 → 回到这个整体：副页 + 弹窗 + 数据。
     *
     * 传 false（用户自己关掉、或调用点注销）时：清掉当前标记，并把栈里**已经无法兑现**的
     * 弹窗快照降级为"只有页面"——页面这一步要留着（用户确实到过那里），只是回来时不再带弹窗。
     *
     * 返回是否发生了变化（调用方据此重算可见性）。
     */
    fun setDialogLayer(open: Boolean): Boolean {
        if (open) {
            if (current.dialogOpen) return false
            current = current.copy(dialogOpen = true)
            return true
        }
        return clearDialogLayer()
    }

    /**
     * 弹窗被真正关掉（点遮罩/按钮/取消/调用点离开组合）后调用：
     * 当前快照去标记，栈里所有"弹窗开着"的快照降级为同页无弹窗版本。
     * 不能直接删掉它们——那样会把用户"到过这个页面"这件事一起抹掉，上一步会多退一步。
     */
    fun clearDialogLayer(): Boolean {
        val hadLayer = current.dialogOpen ||
            backStack.any { it.dialogOpen } ||
            forwardStack.any { it.dialogOpen }
        if (!hadLayer) return false
        current = current.withoutDialog()
        demoteDialogLayers()
        return true
    }

    /** 把栈里的弹窗快照降级成"同页、无弹窗"，保留页面这一步。 */
    private fun demoteDialogLayers() {
        for (i in backStack.indices) {
            val snapshot = backStack[i]
            if (snapshot.dialogOpen) backStack[i] = snapshot.withoutDialog()
        }
        for (i in forwardStack.indices) {
            val snapshot = forwardStack[i]
            if (snapshot.dialogOpen) forwardStack[i] = snapshot.withoutDialog()
        }
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

    /**
     * 回退轨迹（最旧 → 当前），供历史列表展示。
     * 弹窗层不进列表：列表是"跳到某个页面"，同一个页面的弹窗开/关不重复出现。
     */
    fun entries(): List<PageSnapshot> =
        (backStack.toList() + current).map { it.withoutDialog() }.distinct()

    /** 跳到历史列表中的某个目的地：其后的历史全部截断；目标不在轨迹中返回 false。 */
    fun jumpTo(target: PageSnapshot): Boolean {
        // 跳转前先清掉弹窗层：调用方一定会关掉当前弹窗，跳到一个"弹窗开着"的快照无法兑现。
        clearDialogLayer()
        val wanted = target.withoutDialog()
        if (wanted == current) return true
        val idx = backStack.indexOf(wanted)
        if (idx < 0) return false
        val prefix = ArrayList<PageSnapshot>(idx)
        for (i in 0 until idx) prefix.add(backStack.elementAt(i))
        backStack.clear()
        prefix.forEach { backStack.addLast(it) }
        forwardStack.clear()
        current = wanted
        return true
    }
}
