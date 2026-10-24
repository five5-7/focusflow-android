package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug 7A：弹窗「打开」状态的 token 化建模（纯状态逻辑，不需要 Android/Compose 组合）。
 *
 * 背景：弹窗被「上一步」收起后，宿主只是把浮层挪出屏幕（[AppDialogHostState.visible] = false），
 * 调用点的打开状态还是原来的值。此时再点同一个入口，等于把同一个值再写一遍，Compose 不会重组，
 * 界面就成了死按钮。
 *
 * 修法：打开状态改由 [DialogTargetState] / [DialogFlagState] 持有，每次「打开动作」推进 [DialogTargetState.requestId]，
 * 调用点用 `key(requestId)` 包住弹窗，于是弹窗子树重建、重新注册并重新显示。
 * 这里只测纯状态规则；UI 绑定（组合里的 key 重建、宿主重新进场）由真机验收覆盖。
 */
class DialogOpenStateTest {

    /**
     * 模拟弹窗宿主的最小状态：registered 对应 [AppDialogHostState.isOpen]（调用点还注册着弹窗），
     * visible 对应 [AppDialogHostState.visible]（浮层是否真的显示）。两者同时成立才算「被收起」。
     */
    private class FakeHost {
        var registered = false
        var visible = true
        val suspended: () -> Boolean = { registered && !visible }
    }

    private class Holder(host: FakeHost = FakeHost()) {
        val host = host
        val flagState = DialogFlagState(host.suspended)
        var flag by flagState
        val targetState = DialogTargetState<String>(host.suspended)
        var target by targetState
    }

    private fun snap(tab: Int, plan: PlanPage? = null) = PageSnapshot(tab, false, plan, null, emptyList())

    // ---------- 开关型弹窗 ----------

    @Test
    fun flagStartsClosedWithoutRequest() {
        val h = Holder()
        assertFalse(h.flag)
        assertEquals(0, h.flagState.requestId)
    }

    @Test
    fun flagFirstOpenIsNotARepeatRequest() {
        val h = Holder()
        h.flag = true
        assertTrue(h.flag)
        assertEquals(0, h.flagState.requestId)
    }

    @Test
    fun flagOpenWhileVisibleDoesNotStartRequest() {
        // 弹窗正显示时再写一次 true（例如程序性重写状态）不能重建弹窗、打断用户输入。
        val h = Holder()
        h.host.registered = true
        h.host.visible = true
        h.flag = true
        h.flag = true
        assertEquals(0, h.flagState.requestId)
    }

    @Test
    fun flagReopenAfterTuckStartsNewRequest() {
        // 主场景：弹窗曾被收起（注册着但不可见），再点同一个入口 -> 必须算一次新的打开请求。
        val h = Holder()
        h.flag = true
        h.host.registered = true
        h.host.visible = false
        h.flag = true
        assertTrue(h.flag)
        assertEquals(1, h.flagState.requestId)
    }

    @Test
    fun flagEveryTuckStartsExactlyOneMoreRequest() {
        val h = Holder()
        h.flag = true
        h.host.registered = true
        h.host.visible = false
        h.flag = true
        h.flag = true
        assertEquals(2, h.flagState.requestId)
    }

    @Test
    fun flagCloseDoesNotStartRequest() {
        val h = Holder()
        h.flag = true
        h.host.registered = true
        h.host.visible = false
        h.flag = false
        assertEquals(0, h.flagState.requestId)
    }

    @Test
    fun flagInitialValueIsKept() {
        // 引导弹窗这类"启动即打开"的状态要能带初值，且初值不算一次打开请求。
        val state = DialogFlagState({ false }, true)
        var open by state
        assertTrue(open)
        assertEquals(0, state.requestId)
    }

    // ---------- 目标型弹窗 ----------

    @Test
    fun targetOpenIsNotARepeatRequest() {
        val h = Holder()
        h.target = "弹性安排"
        assertEquals("弹性安排", h.target)
        assertEquals(0, h.targetState.requestId)
    }

    @Test
    fun targetSameValueAfterTuckStartsNewRequest() {
        // 死按钮原样复现：同一个 item 被再点一次（写相同的值）。
        val h = Holder()
        h.target = "弹性安排"
        h.host.registered = true
        h.host.visible = false
        h.target = "弹性安排"
        assertEquals(1, h.targetState.requestId)
    }

    @Test
    fun targetAnotherValueAfterTuckStartsNewRequest() {
        // 收起期间换成另一个目标（改期 A -> 改期 B）同样要重开，否则新目标看不见。
        val h = Holder()
        h.target = "A"
        h.host.registered = true
        h.host.visible = false
        h.target = "B"
        assertEquals(1, h.targetState.requestId)
        assertEquals("B", h.target)
    }

    @Test
    fun targetClearingDoesNotStartRequest() {
        val h = Holder()
        h.target = "A"
        h.host.registered = true
        h.host.visible = false
        h.target = null
        assertNull(h.target)
        assertEquals(0, h.targetState.requestId)
    }

    @Test
    fun targetVisibleUpdateDoesNotStartRequest() {
        val h = Holder()
        h.host.registered = true
        h.host.visible = true
        h.target = "A"
        h.target = "B"
        assertEquals(0, h.targetState.requestId)
        assertEquals("B", h.target)
    }

    // ---------- 与导航历史语义的组合 ----------

    @Test
    fun tuckAndReopenKeepsHistoryUnchanged() {
        // 历史列表不变（弹窗层不进列表）；上一步会把弹窗层退掉（2026-10 口径），
        // 再点同一个入口：token 化保证能拿到新的打开请求，宿主可以重新显示。
        val nav = NavHistory()
        nav.goTo(snap(PageSnapshot.TAB_PLANS, PlanPage.COURSES))
        val h = Holder()

        // 打开弹窗：注册进宿主 -> 记为弹窗层，但不压栈。
        val beforeOpen = nav.entries().size
        h.target = "item"
        h.host.registered = true
        h.host.visible = true
        nav.setDialogLayer(true)
        assertEquals(beforeOpen, nav.entries().size)
        assertEquals(0, h.targetState.requestId)

        // 上一步：宿主收起弹窗（调用点状态仍是同一个 item）。
        val snapshot = nav.back()
        assertTrue(snapshot != null)
        assertFalse(snapshot!!.dialogOpen)
        val afterBack = nav.entries().size
        h.host.registered = true
        h.host.visible = false

        // 再点同一个入口：拿到新的打开请求，宿主可以重新显示，且历史栈不动。
        h.target = "item"
        assertEquals(1, h.targetState.requestId)
        nav.setDialogLayer(true)
        assertTrue(nav.current.dialogOpen)
        assertEquals(afterBack, nav.entries().size)
    }
}
