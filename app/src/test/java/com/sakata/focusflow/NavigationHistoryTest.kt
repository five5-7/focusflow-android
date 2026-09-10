package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationHistoryTest {

    private fun snap(tab: Int, inbox: Boolean = false, plan: PlanPage? = null, subPage: SettingsSubPage? = null) = PageSnapshot(
        tab, inbox, plan, subPage, emptyList()
    )

    @Test
    fun startsAtRootWithEmptyStacks() {
        val h = NavHistory()
        assertEquals(PageSnapshot.ROOT, h.current)
        assertFalse(h.canGoBack())
        assertFalse(h.canGoForward())
        assertNull(h.back())
        assertNull(h.forward())
    }

    @Test
    fun rootToRootNotRecorded() {
        val h = NavHistory()
        val schedule = snap(PageSnapshot.TAB_SCHEDULE)
        val settings = snap(PageSnapshot.TAB_SETTINGS)
        h.goTo(schedule)
        h.goTo(settings)
        assertEquals(settings, h.current)
        assertFalse(h.canGoBack())
        assertNull(h.back())
    }

    @Test
    fun rootToSubpageRecords() {
        val h = NavHistory()
        val plansSub = snap(PageSnapshot.TAB_PLANS, plan = PlanPage.GOALS)
        assertEquals(plansSub, h.goTo(plansSub))
        assertTrue(h.canGoBack())
        assertEquals(PageSnapshot.ROOT, h.back())
        assertNull(h.back())
    }

    @Test
    fun subpageToRootRecordsReturnTrip() {
        val h = NavHistory()
        val sub = snap(PageSnapshot.TAB_PLANS, plan = PlanPage.COURSES)
        h.goTo(sub)
        val plansRoot = snap(PageSnapshot.TAB_PLANS)
        h.goTo(plansRoot) // 子页→主页：记录（可回退回子页）
        assertEquals(sub, h.back())
    }

    @Test
    fun abABounceCollapsesToA() {
        val h = NavHistory()
        val a = snap(PageSnapshot.TAB_PLANS, plan = PlanPage.GOALS)
        val b = snap(PageSnapshot.TAB_SETTINGS, subPage = SettingsSubPage.APPEARANCE)
        h.goTo(a)
        h.goTo(b)
        h.goTo(a) // A→B→A 抵消：等同只发生了 ROOT→A
        assertEquals(a, h.current)
        assertEquals(PageSnapshot.ROOT, h.back())
        assertNull(h.back())
        assertEquals(a, h.forward()) // 折返回到 a
        assertFalse(h.canGoForward())
    }

    @Test
    fun newNavigationTruncatesForwardBranch() {
        val h = NavHistory()
        val a = snap(1, plan = PlanPage.GOALS)
        val b = snap(2, plan = PlanPage.COURSES)
        val c = snap(3, subPage = SettingsSubPage.ROADMAP)
        h.goTo(a)
        h.goTo(b)
        h.back() // 折返栈现有 b
        assertTrue(h.canGoForward())
        h.goTo(c) // 新导航清空折返
        assertFalse(h.canGoForward())
        assertNull(h.forward())
    }

    @Test
    fun markWorkedHereRecordsRootOnce() {
        val h = NavHistory()
        val today = snap(PageSnapshot.TAB_TODAY)
        h.goTo(today) // root→root 不记
        h.markWorkedHere()
        assertTrue(h.canGoBack())
        h.markWorkedHere() // 幂等：不重复记录
        val plans = snap(PageSnapshot.TAB_PLANS)
        h.goTo(plans) // root→root 不记
        assertEquals(today, h.back()) // 回到工作过的主页
        assertNull(h.back())
    }

    @Test
    fun markWorkedHereOnSubpageIsNoOp() {
        val h = NavHistory()
        val sub = snap(2, plan = PlanPage.GOALS)
        h.goTo(sub)
        val backStackBefore = h.entries().size
        h.markWorkedHere()
        assertEquals(backStackBefore, h.entries().size)
    }

    @Test
    fun capacityDropsOldestBackEntry() {
        val h = NavHistory(capacity = 2)
        val a = snap(1, plan = PlanPage.GOALS)
        val b = snap(2, plan = PlanPage.COURSES)
        val c = snap(3, subPage = SettingsSubPage.ROADMAP)
        h.goTo(a)
        h.goTo(b)
        h.goTo(c) // 回退栈超限，丢最旧的 root
        assertEquals(2, h.back()!!.tab)
        assertEquals(1, h.back()!!.tab)
        assertNull(h.back())
    }

    @Test
    fun entriesAndJumpTo() {
        val h = NavHistory()
        // 用真实组合：计划页签下的子页（tab=1 携带 planPage 是现实中不存在的状态，
        // isTabRoot() 改为按当前页签判定后，这种构造会被正确视为"主页"）。
        val a = snap(2, plan = PlanPage.GOALS)
        val b = snap(2, plan = PlanPage.COURSES)
        val c = snap(3, subPage = SettingsSubPage.ROADMAP)
        h.goTo(a); h.goTo(b); h.goTo(c)
        assertEquals(listOf(PageSnapshot.ROOT, a, b, c), h.entries())
        assertTrue(h.jumpTo(a))
        assertEquals(a, h.current)
        assertFalse(h.canGoForward())
        assertEquals(PageSnapshot.ROOT, h.back())
        assertNull(h.back())
        // c 与 a 都已不在回退轨迹中（a 在折返栈，不参与 jumpTo）
        assertFalse(h.jumpTo(c))
        assertFalse(h.jumpTo(a))
    }

    @Test
    fun openingDialogAddsNoHistoryStep() {
        // 用户口径：打开弹窗本身不算一步。「副页 + 弹窗 + 已填数据」是同一个整体。
        val h = NavHistory()
        val courses = snap(2, plan = PlanPage.COURSES)
        h.goTo(courses)
        assertTrue(h.setDialogLayer(true)) // 手动新增 → 打开课程编辑弹窗
        assertEquals(courses.copy(dialogOpen = true), h.current)
        // 上一步：回到进入课程子页之前的那一步（计划主页），不是"只关弹窗"
        val previous = h.back()!!
        assertEquals(PageSnapshot.ROOT, previous)
        assertFalse(previous.dialogOpen)
        // 下一步：整包回来——副页 + 弹窗（数据由调用点与草稿箱保留）
        val restored = h.forward()!!
        assertEquals(courses, restored.withoutDialog())
        assertTrue(restored.dialogOpen)
        assertFalse(h.setDialogLayer(true)) // 已是打开状态
    }

    @Test
    fun dialogOnTabRootAddsNoBackStep() {
        // 主页上开弹窗不产生历史：上一步没有目标（只能靠底栏回主页）。
        val h = NavHistory()
        h.goTo(snap(0))
        assertFalse(h.canGoBack())
        h.setDialogLayer(true)
        assertFalse(h.canGoBack())
        assertNull(h.back())
    }

    @Test
    fun leavingThePageAndComingBackRestoresTheWholePackage() {
        // 从「课程 + 弹窗」跳到别处（管理地点/其他页），上一步要回到这个整体状态。
        val h = NavHistory()
        val courses = snap(2, plan = PlanPage.COURSES)
        val commute = snap(3, subPage = SettingsSubPage.COMMUTE_PLACES)
        h.goTo(courses)
        h.setDialogLayer(true)
        h.goTo(commute) // 跳转：整体压栈
        assertEquals(commute, h.current)
        val back = h.back()!!
        assertEquals(courses, back.withoutDialog())
        assertTrue(back.dialogOpen) // 整包还原：副页 + 弹窗
    }

    @Test
    fun switchingTabKeepsThePackageForBack() {
        // 切页签（例如回本页签主页）后再上一步，同样回到整体状态。
        val h = NavHistory()
        val courses = snap(2, plan = PlanPage.COURSES)
        val plansRoot = snap(2)
        h.goTo(courses)
        h.setDialogLayer(true)
        h.goTo(plansRoot) // 回到计划主页
        assertEquals(plansRoot, h.current)
        val back = h.back()!!
        assertEquals(courses, back.withoutDialog())
        assertTrue(back.dialogOpen)
    }

    @Test
    fun samePageNavigationKeepsTheDialog() {
        // 同一页面上的导航（例如再点一次当前入口）不该把弹窗关掉。
        val h = NavHistory()
        val courses = snap(2, plan = PlanPage.COURSES)
        h.goTo(courses)
        h.setDialogLayer(true)
        assertNull(h.goTo(courses)) // 目的地没变：什么都不发生
        assertTrue(h.current.dialogOpen)
    }

    @Test
    fun closingDialogByUserMakesItUnrecoverable() {
        val h = NavHistory()
        val courses = snap(2, plan = PlanPage.COURSES)
        val commute = snap(3, subPage = SettingsSubPage.COMMUTE_PLACES)
        h.goTo(courses)
        h.setDialogLayer(true)
        h.goTo(commute) // 栈里留下"课程 + 弹窗"的整体
        h.setDialogLayer(false) // 用户在收起/离开期间把它真正关掉
        assertFalse(h.current.dialogOpen)
        val back = h.back()!!
        assertEquals(courses, back) // 回来时不再带弹窗（它已经没了）
        assertFalse(back.dialogOpen)
    }

    @Test
    fun clearingDialogLayerDemotesThePackageToPlainPage() {
        val h = NavHistory()
        val courses = snap(2, plan = PlanPage.COURSES)
        val commute = snap(3, subPage = SettingsSubPage.COMMUTE_PLACES)
        h.goTo(courses)
        h.setDialogLayer(true)
        h.goTo(commute)
        h.clearDialogLayer() // 调用点注销（弹窗真的关掉了）
        assertFalse(h.current.dialogOpen)
        // 页面这一步留着（用户确实到过课程页），只是回来时不再带弹窗
        assertEquals(courses, h.back())
        assertEquals(PageSnapshot.ROOT, h.back())
    }

    @Test
    fun entriesSkipDialogLayer() {
        val h = NavHistory()
        val courses = snap(2, plan = PlanPage.COURSES)
        val commute = snap(3, subPage = SettingsSubPage.COMMUTE_PLACES)
        h.goTo(courses)
        h.setDialogLayer(true)
        h.goTo(commute)
        // 历史列表按页面粒度展示：同一个页面不会因为弹窗开关出现两次
        assertEquals(listOf(PageSnapshot.ROOT, courses, commute), h.entries())
    }

    @Test
    fun jumpToWhileDialogOpenClearsTheLayer() {
        val h = NavHistory()
        val courses = snap(2, plan = PlanPage.COURSES)
        val goals = snap(2, plan = PlanPage.GOALS)
        h.goTo(goals)
        h.goTo(courses)
        h.setDialogLayer(true)
        assertTrue(h.jumpTo(goals))
        assertEquals(goals, h.current)
        assertFalse(h.current.dialogOpen)
        assertEquals(PageSnapshot.ROOT, h.back())
    }

    @Test
    fun snapshotLabels() {
        assertEquals("今日主页", PageSnapshot.ROOT.label)
        assertEquals("今日 · 收集箱", snap(0, inbox = true).label)
        assertEquals("日程", snap(1).label)
        assertEquals("计划 · 目标与执行", snap(2, plan = PlanPage.GOALS).label)
        assertEquals("设置 · 外观", PageSnapshot(3, false, null, SettingsSubPage.APPEARANCE, emptyList()).label)
        assertEquals("设置主页", snap(3).label)
    }

    @Test
    fun draftVaultRoundTripAndClear() {
        val vault = DraftVault()
        assertNull(vault.load<String>("x"))
        vault.save("x", "草稿内容")
        assertEquals("草稿内容", vault.load<String>("x"))
        assertTrue(vault.has("x"))
        vault.clear("x")
        assertFalse(vault.has("x"))
        assertNull(vault.load<String>("x"))
    }

    @Test
    fun draftVaultTypeMismatchReturnsNull() {
        val vault = DraftVault()
        vault.save("k", 123)
        assertNull(vault.load<String>("k"))
        assertEquals(123, vault.load<Int>("k"))
        assertSame(123, vault.load<Int>("k"))
    }
}
