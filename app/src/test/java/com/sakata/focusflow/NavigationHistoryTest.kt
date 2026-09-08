package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationHistoryTest {

    private fun snap(tab: Int, inbox: Boolean = false, plan: PlanPage? = null) = PageSnapshot(
        tab, inbox, plan, null, emptyList()
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
    fun goToRecordsAndSameDestinationIsNoOp() {
        val h = NavHistory()
        val plans = snap(PageSnapshot.TAB_PLANS)
        assertEquals(plans, h.goTo(plans))
        assertTrue(h.canGoBack())
        assertFalse(h.canGoForward())
        // 相同目的地不产生历史。
        assertNull(h.goTo(plans))
        assertNull(h.goTo(PageSnapshot(PageSnapshot.TAB_PLANS, false, null, null, emptyList())))
    }

    @Test
    fun backAndForwardRoundTrip() {
        val h = NavHistory()
        val a = snap(PageSnapshot.TAB_PLANS)
        val b = snap(PageSnapshot.TAB_SETTINGS)
        h.goTo(a)
        h.goTo(b)
        assertEquals(a, h.back())
        assertTrue(h.canGoForward())
        assertEquals(b, h.forward())
        assertFalse(h.canGoForward())
        assertEquals(a, h.back())
        assertEquals(PageSnapshot.ROOT, h.back())
        assertNull(h.back())
        assertTrue(h.canGoForward())
    }

    @Test
    fun backRestoresSettingsBackStack() {
        val h = NavHistory()
        val settingsA = PageSnapshot(3, false, null, SettingsSubPage.COMMUTE_PLACES, listOf(SettingsSubPage.ROADMAP))
        h.goTo(settingsA)
        val back = h.back()!!
        assertNull(back.settingsSubPage)
        assertTrue(back.settingsBackStack.isEmpty())
        assertEquals(settingsA, h.forward())
    }

    @Test
    fun newNavigationTruncatesForwardBranch() {
        val h = NavHistory()
        val a = snap(1)
        val b = snap(2)
        val c = snap(3)
        h.goTo(a)
        h.goTo(b)
        h.back() // 折返栈现有 b
        assertTrue(h.canGoForward())
        h.goTo(c) // 新导航清空折返
        assertFalse(h.canGoForward())
        assertNull(h.forward())
    }

    @Test
    fun abABounceCollapsesToA() {
        val h = NavHistory()
        val a = snap(1)
        val b = snap(2)
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
    fun entriesAndJumpTo() {
        val h = NavHistory()
        val a = snap(1)
        val b = snap(2)
        val c = snap(3)
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
    fun capacityDropsOldestBackEntry() {
        val h = NavHistory(capacity = 2)
        h.goTo(snap(1))
        h.goTo(snap(2))
        h.goTo(snap(3)) // 回退栈超限，丢最旧的 root
        // 回退两步到顶：2 → 1，之后不可再退。
        assertEquals(2, h.back()!!.tab)
        assertEquals(1, h.back()!!.tab)
        assertNull(h.back())
    }

    @Test
    fun isTabRootDetectsSubpages() {
        assertTrue(PageSnapshot.ROOT.isTabRoot())
        assertFalse(snap(0, inbox = true).isTabRoot())
        assertFalse(snap(2, plan = PlanPage.GOALS).isTabRoot())
        assertFalse(PageSnapshot(3, false, null, SettingsSubPage.APPEARANCE, emptyList()).isTabRoot())
        assertTrue(snap(1).isTabRoot())
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
