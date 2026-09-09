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
