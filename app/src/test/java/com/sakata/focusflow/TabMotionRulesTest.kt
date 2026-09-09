package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 8.1.0 第三轮：页签转场规则的组合矩阵。
 * 这些规则原先散在 Composable 里，只能靠真机逐例试，同一个判断反复出错；
 * 现在抽成 [TabMotionRules] 的纯函数，由本测试覆盖「直达 / 回退 / 折返 / 跨页签」的组合。
 */
class TabMotionRulesTest {
    private fun today(inbox: Boolean = false, settings: SettingsSubPage? = null) =
        PageSnapshot(PageSnapshot.TAB_TODAY, inbox, null, settings, emptyList())

    private fun settings(sub: SettingsSubPage? = null) =
        PageSnapshot(PageSnapshot.TAB_SETTINGS, false, null, sub, emptyList())

    private fun plans(page: PlanPage?) =
        PageSnapshot(PageSnapshot.TAB_PLANS, false, page, null, emptyList())

    @Test fun departingSubpageIsRecordedOnlyWhenTheCurrentTabShowsASubpage() {
        assertEquals(3, TabMotionRules.departingSubpageTab(settings(SettingsSubPage.APPEARANCE), today()))
        assertEquals(TabMotionRules.NO_TAB, TabMotionRules.departingSubpageTab(settings(), today()))
        // 同一页签内的子页开合不算"离开页签"
        assertEquals(TabMotionRules.NO_TAB, TabMotionRules.departingSubpageTab(settings(SettingsSubPage.APPEARANCE), settings()))
        assertEquals(0, TabMotionRules.departingSubpageTab(today(inbox = true), plans(null)))
    }

    @Test fun leavingASubpageKeepsItForTheCollapseButDoesNotTouchTheDestination() {
        // 设置·外观 → 今日：快照保留后台子页，目标页签自身没被子页变化影响
        val from = settings(SettingsSubPage.APPEARANCE)
        val to = today(settings = SettingsSubPage.APPEARANCE)
        assertEquals(3, TabMotionRules.departingSubpageTab(from, to))
        assertFalse(TabMotionRules.destinationSubpageReset(from, to))
    }

    @Test fun directTabEntryResetsTheDestinationSubpageWithoutReplayingAnAnimation() {
        // 今日 → 设置（直达入口回主页）：目标子页被改掉 → 不补播收起
        val from = today(settings = SettingsSubPage.APPEARANCE)
        val to = settings()
        assertTrue(TabMotionRules.destinationSubpageReset(from, to))
        assertEquals(TabMotionRules.NO_TAB, TabMotionRules.departingSubpageTab(from, to))
    }

    @Test fun forwardAfterBackDoesNotReplayTheCollapse() {
        // 用户实测序列：设置主页 → 外观 → 今日 → 设置主页 → 上一步 → 下一步
        val hub = settings()
        val appearance = settings(SettingsSubPage.APPEARANCE)
        val todayWithBackgroundSubpage = today(settings = SettingsSubPage.APPEARANCE)

        // 外观 → 今日：唯一应当播收起的一次
        assertEquals(3, TabMotionRules.departingSubpageTab(appearance, todayWithBackgroundSubpage))
        // 今日 → 设置主页：目标子页被重置 → 不补播
        assertTrue(TabMotionRules.destinationSubpageReset(todayWithBackgroundSubpage, hub))
        // 上一步：回到今日，没有页签在离开子页
        assertEquals(TabMotionRules.NO_TAB, TabMotionRules.departingSubpageTab(hub, todayWithBackgroundSubpage))
        // 下一步：再次回到设置主页，仍只是"目标子页被改掉"，不该再播一次收起
        assertEquals(TabMotionRules.NO_TAB, TabMotionRules.departingSubpageTab(todayWithBackgroundSubpage, hub))
        assertTrue(TabMotionRules.destinationSubpageReset(todayWithBackgroundSubpage, hub))
    }

    @Test fun returningToATabThatStillShowsASubpageGrowsFromItsIcon() {
        val hasSubpage = TabMotionRules.subpageOpenOn(PageSnapshot.TAB_SETTINGS, settings(SettingsSubPage.APPEARANCE))
        assertTrue(hasSubpage)
        assertTrue(TabMotionRules.growsOnArrival(hasSubpage))
        assertEquals(MotionSpec.COLLAPSE_SCALE, TabMotionRules.restingScale(hasSubpage, jumped = false), 0.0001f)
        // 已回到主页的页签：不放大、停在 1.0，回来只平移
        assertFalse(TabMotionRules.growsOnArrival(false))
        assertEquals(1f, TabMotionRules.restingScale(false, jumped = false), 0.0001f)
        assertEquals(0.85f, TabMotionRules.restingScale(false, jumped = true), 0.0001f)
    }

    @Test fun plansSubpageIsTrackedPerTab() {
        assertTrue(TabMotionRules.subpageOpenOn(PageSnapshot.TAB_PLANS, plans(PlanPage.GOALS)))
        assertFalse(TabMotionRules.subpageOpenOn(PageSnapshot.TAB_PLANS, plans(null)))
        // 计划子页不影响设置页签的判断
        assertFalse(TabMotionRules.subpageOpenOn(PageSnapshot.TAB_SETTINGS, plans(PlanPage.GOALS)))
    }
}
