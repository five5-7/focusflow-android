package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 8.1.0 审计补充：导航状态矩阵里此前没有覆盖的组合。
 * 只断言"应当如此"的行为——某条失败即说明实现与规则不符（审计报告第三节的缺口即由此暴露）。
 */
class TabMotionMatrixTest {
    private fun today(inbox: Boolean = false, settings: SettingsSubPage? = null) =
        PageSnapshot(PageSnapshot.TAB_TODAY, inbox, null, settings, emptyList())

    private fun schedule(settings: SettingsSubPage? = null) =
        PageSnapshot(PageSnapshot.TAB_SCHEDULE, false, null, settings, emptyList())

    private fun plans(page: PlanPage?, settings: SettingsSubPage? = null) =
        PageSnapshot(PageSnapshot.TAB_PLANS, false, page, settings, emptyList())

    private fun settings(sub: SettingsSubPage?, back: List<SettingsSubPage> = emptyList()) =
        PageSnapshot(PageSnapshot.TAB_SETTINGS, false, null, sub, back)

    /** A3：计划子页 → 今日主页：离开页是计划，收起；目标页签自身不受影响。 */
    @Test fun departingPlansSubpageIsCollapsedWhenTappingTodayTab() {
        val from = plans(PlanPage.GOALS)
        val to = PageSnapshot(PageSnapshot.TAB_TODAY, false, PlanPage.GOALS, null, emptyList())
        assertEquals(PageSnapshot.TAB_PLANS, TabMotionRules.departingSubpageTab(from, to))
        assertFalse(TabMotionRules.destinationSubpageChanged(from, to))
        assertTrue(TabMotionRules.subpageOpenOn(PageSnapshot.TAB_PLANS, to))
    }

    /** A3：今日收集箱 → 计划主页：离开页是今日；计划没有子页，回来时只平移。 */
    @Test fun departingTodayInboxIsCollapsedWhenTappingPlansTab() {
        val from = today(inbox = true)
        val to = plans(null)
        assertEquals(PageSnapshot.TAB_TODAY, TabMotionRules.departingSubpageTab(from, to))
        assertFalse(TabMotionRules.destinationSubpageChanged(from, to))
        assertEquals(1f, TabMotionRules.restingScale(TabMotionRules.subpageOpenOn(PageSnapshot.TAB_TODAY, to), jumped = false), 0.0001f)
    }

    /** A5：点当前页签入口关闭自己的子页：不是"离开页签"，也不该抑制。 */
    @Test fun tappingTheCurrentTabClosesItsOwnSubpageWithTheNormalCollapse() {
        val from = today(inbox = true)
        val to = today(inbox = false)
        assertEquals(TabMotionRules.NO_TAB, TabMotionRules.departingSubpageTab(from, to))
        assertFalse(TabMotionRules.destinationSubpageChanged(from, to))
        assertEquals(-1, NavigationMotion.direction(1, 0))

        // 规则 v3 去抖：主页 → 收集箱 → 主页 属于 A→B→A，收集箱不进历史（点错又点回不记）。
        val history = NavHistory()
        history.goTo(from)
        history.goTo(to)
        assertFalse(history.canGoBack())
        assertEquals(to, history.current)
    }

    /** A6：跨页签回退到"仍开着子页"的目标：目标从图标放大，离开页不播收起。 */
    @Test fun crossTabBackRestoresASubpageSnapshotAndGrowsTheDestination() {
        val appearance = settings(SettingsSubPage.APPEARANCE)
        val todayLeftover = PageSnapshot(PageSnapshot.TAB_TODAY, false, null, SettingsSubPage.APPEARANCE, emptyList())

        val history = NavHistory()
        history.goTo(appearance)
        history.goTo(todayLeftover)
        assertEquals(appearance, history.back())

        assertEquals(TabMotionRules.NO_TAB, TabMotionRules.departingSubpageTab(todayLeftover, appearance))
        assertFalse(TabMotionRules.destinationSubpageChanged(todayLeftover, appearance))
        assertTrue(TabMotionRules.subpageOpenOn(PageSnapshot.TAB_SETTINGS, appearance))
        assertTrue(TabMotionRules.growsOnArrival(true))
    }

    /** A8：折返回到今日主页：设置子页跨页收起，今日自身无子页。 */
    @Test fun forwardBackIntoTodayCollapsesTheSettingsSubpageAcross() {
        val appearance = settings(SettingsSubPage.APPEARANCE)
        val todayLeftover = PageSnapshot(PageSnapshot.TAB_TODAY, false, null, SettingsSubPage.APPEARANCE, emptyList())

        val history = NavHistory()
        history.goTo(appearance)
        history.goTo(todayLeftover)
        history.back()
        assertEquals(todayLeftover, history.forward())
        assertFalse(history.canGoForward())

        assertEquals(PageSnapshot.TAB_SETTINGS, TabMotionRules.departingSubpageTab(appearance, todayLeftover))
        assertFalse(TabMotionRules.destinationSubpageChanged(appearance, todayLeftover))
        assertFalse(TabMotionRules.subpageOpenOn(PageSnapshot.TAB_TODAY, todayLeftover))
    }

    /** A9：日程页按系统返回回今日：纯平移，日程本来就没有子页。 */
    @Test fun systemBackFromScheduleToTodayIsPureSlide() {
        val from = schedule(SettingsSubPage.APPEARANCE)
        val to = today(settings = SettingsSubPage.APPEARANCE)
        assertEquals(TabMotionRules.NO_TAB, TabMotionRules.departingSubpageTab(from, to))
        assertFalse(TabMotionRules.destinationSubpageChanged(from, to))
        assertEquals(
            TabMotionRules.subpageOpenOn(PageSnapshot.TAB_SETTINGS, from),
            TabMotionRules.subpageOpenOn(PageSnapshot.TAB_SETTINGS, to)
        )
    }

    /** B9：设置多级回退：同一页签内，不触发页签级转场。 */
    @Test fun nestedSettingsBackPopsOneLevelWithoutTabMotion() {
        val from = settings(SettingsSubPage.CAMPUS_PLACES, listOf(SettingsSubPage.ADVANCED))
        val to = settings(SettingsSubPage.ADVANCED)
        assertEquals(-1, NavigationMotion.direction(3, 1))
        assertEquals(TabMotionRules.NO_TAB, TabMotionRules.departingSubpageTab(from, to))
        assertFalse(TabMotionRules.destinationSubpageChanged(from, to))
        assertTrue(TabMotionRules.subpageOpenOn(PageSnapshot.TAB_SETTINGS, from))
        assertTrue(TabMotionRules.subpageOpenOn(PageSnapshot.TAB_SETTINGS, to))
    }

    /** B5：计划子页之间是同级（深度都为 1）→ 交叉淡入。 */
    @Test fun planSiblingSubpagesCrossFade() {
        assertEquals(0, NavigationMotion.direction(1, 1))
        assertTrue(TabMotionRules.subpageOpenOn(PageSnapshot.TAB_PLANS, plans(PlanPage.GOALS)))
        assertTrue(TabMotionRules.subpageOpenOn(PageSnapshot.TAB_PLANS, plans(PlanPage.COURSES)))
        assertFalse(TabMotionRules.destinationSubpageChanged(plans(PlanPage.GOALS), plans(PlanPage.COURSES)))
    }

    /** 设置页的深度映射（与 SettingsScreen 的分组一致）驱动缩放方向。 */
    @Test fun settingsDepthMappingDrivesZoomDirection() {
        assertEquals(1, NavigationMotion.direction(0, 1))
        assertEquals(1, NavigationMotion.direction(1, 2))
        assertEquals(1, NavigationMotion.direction(2, 3))
        assertEquals(-1, NavigationMotion.direction(3, 1))
        assertEquals(-1, NavigationMotion.direction(2, 1))
        assertEquals(0, NavigationMotion.direction(2, 2))
        assertEquals(0, NavigationMotion.direction(1, 1))
    }

    /** A14：快速记录跳到今日·收集箱：目标从图标放大，且不补播动画。 */
    @Test fun quickCaptureJumpOpensTheInboxWithoutReplay() {
        val from = settings(SettingsSubPage.APPEARANCE)
        val to = PageSnapshot(PageSnapshot.TAB_TODAY, true, null, SettingsSubPage.APPEARANCE, emptyList())
        assertEquals(PageSnapshot.TAB_SETTINGS, TabMotionRules.departingSubpageTab(from, to))
        assertTrue(TabMotionRules.destinationSubpageChanged(from, to))
        assertTrue(TabMotionRules.subpageOpenOn(PageSnapshot.TAB_TODAY, to))
        assertTrue(TabMotionRules.growsOnArrival(true))
        assertEquals(MotionSpec.COLLAPSE_SCALE, TabMotionRules.restingScale(hasSubpage = true, jumped = true), 0.0001f)
        assertEquals(0.85f, TabMotionRules.restingScale(hasSubpage = false, jumped = true), 0.0001f)
    }

    /** A13：通知跳转离开计划子页：子页保留在后台，不误播收起。 */
    @Test fun notificationJumpFromPlanSubpageKeepsItInTheBackground() {
        val from = plans(PlanPage.GOALS)
        val to = PageSnapshot(PageSnapshot.TAB_TODAY, false, PlanPage.GOALS, null, emptyList())
        assertEquals(PageSnapshot.TAB_PLANS, TabMotionRules.departingSubpageTab(from, to))
        assertFalse(TabMotionRules.destinationSubpageChanged(from, to))
        assertTrue(TabMotionRules.subpageOpenOn(PageSnapshot.TAB_PLANS, to))
        assertFalse(TabMotionRules.growsOnArrival(TabMotionRules.subpageOpenOn(PageSnapshot.TAB_TODAY, to)))
    }

    /** 历史列表跳转：截断其后历史并清空折返。 */
    @Test fun historyListJumpTruncatesForwardAndKeepsCurrent() {
        val history = NavHistory()
        history.goTo(plans(PlanPage.GOALS))
        history.goTo(settings(SettingsSubPage.APPEARANCE))
        assertTrue(history.jumpTo(plans(PlanPage.GOALS)))
        assertEquals(plans(PlanPage.GOALS), history.current)
        assertFalse(history.canGoForward())
        assertEquals(PageSnapshot.ROOT, history.back())
    }
}
