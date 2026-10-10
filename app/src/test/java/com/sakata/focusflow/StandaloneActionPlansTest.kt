package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组④纯函数的单测（不接线、不写存储、不碰通知）。
 *
 * 重点两件事：
 * 1. **逐条镜像**现有写入函数的守卫条件（完成／稍后的前置条件），让这些条件在没有 Context 时也能被测；
 * 2. 把**不得弱化**的语义钉住：稍后只改下一次通知时间（`triggerAt` 不变）、旧通知的动作一律拒绝、
 *    "开始"因语义未定稿而**明确拒绝**。
 */
class StandaloneActionPlansTest {

    private val now = 1_800_000_000_000L

    private fun reminder(
        id: Long = 7L,
        title: String = "交电费",
        triggerAt: Long = now - 60_000L,
        deliveredAt: Long? = now - 30_000L,
        completedAt: Long? = null,
        snoozedUntil: Long? = null
    ) = StandaloneReminder(
        id = id,
        title = title,
        triggerAt = triggerAt,
        deliveredAt = deliveredAt,
        completedAt = completedAt,
        snoozedUntil = snoozedUntil
    )

    private fun planOf(
        reminder: StandaloneReminder,
        action: StandaloneActionPlans.StandaloneAction,
        expectedAt: Long = reminder.scheduledAt,
        deliveredAt: Long? = reminder.deliveredAt,
        snoozeMinutes: Int = 10,
        completeAfterMove: Boolean = true
    ) = StandaloneActionPlans.plan(
        reminder = reminder,
        action = action,
        now = now,
        expectedAt = expectedAt,
        deliveredAt = deliveredAt,
        snoozeMinutes = snoozeMinutes,
        completeAfterMove = completeAfterMove
    )

    // ------------------------------------------------------------ 完成

    @Test
    fun `completing a live reminder records the time`() {
        val plan = planOf(reminder(), StandaloneActionPlans.StandaloneAction.COMPLETE)

        val completed = plan as StandaloneActionPlans.StandaloneActionPlan.Completed
        assertEquals(now, completed.completedAt)
        assertEquals(now, StandaloneActionPlans.applyTo(reminder(), plan).completedAt)
    }

    @Test
    fun `completing works even before delivery, mirroring the existing writer`() {
        // 现有 complete() 只要求"未完成 + expectedAt 匹配"，不要求已投递；这里如实镜像。
        val fresh = reminder(deliveredAt = null)

        val plan = planOf(fresh, StandaloneActionPlans.StandaloneAction.COMPLETE)

        assertTrue(plan is StandaloneActionPlans.StandaloneActionPlan.Completed)
    }

    @Test
    fun `an already completed reminder cannot be completed again`() {
        val done = reminder(completedAt = now - 1_000L)

        val plan = planOf(done, StandaloneActionPlans.StandaloneAction.COMPLETE)

        assertEquals(
            StandaloneActionPlans.StandaloneActionRejectReason.ALREADY_COMPLETED,
            (plan as StandaloneActionPlans.StandaloneActionPlan.Rejected).rejectReason
        )
    }

    @Test
    fun `an action from an old notification is rejected for every action`() {
        for (action in StandaloneActionPlans.StandaloneAction.entries) {
            val plan = planOf(reminder(), action, expectedAt = now - 999_999L)

            assertEquals(
                "action=$action",
                StandaloneActionPlans.StandaloneActionRejectReason.STALE_ACTION,
                (plan as StandaloneActionPlans.StandaloneActionPlan.Rejected).rejectReason
            )
        }
    }

    @Test
    fun `a snoozed reminder uses the snoozed time as its current schedule`() {
        val snoozed = reminder(snoozedUntil = now + 600_000L)

        // 旧通知带的是原 triggerAt ⇒ 必须判为旧动作。
        val stale = planOf(snoozed, StandaloneActionPlans.StandaloneAction.COMPLETE, expectedAt = snoozed.triggerAt)
        assertEquals(
            StandaloneActionPlans.StandaloneActionRejectReason.STALE_ACTION,
            (stale as StandaloneActionPlans.StandaloneActionPlan.Rejected).rejectReason
        )

        // 带当前 scheduledAt（= snoozedUntil）⇒ 允许。
        val live = planOf(snoozed, StandaloneActionPlans.StandaloneAction.COMPLETE, expectedAt = snoozed.scheduledAt)
        assertTrue(live is StandaloneActionPlans.StandaloneActionPlan.Completed)
    }

    // ------------------------------------------------------------ 稍后

    @Test
    fun `snooze moves only the next notification time and keeps the original trigger`() {
        val original = reminder()

        val plan = planOf(original, StandaloneActionPlans.StandaloneAction.SNOOZE, snoozeMinutes = 10)
        val applied = StandaloneActionPlans.applyTo(original, plan)

        assertEquals(now + 600_000L, (plan as StandaloneActionPlans.StandaloneActionPlan.Snoozed).snoozedUntil)
        assertEquals("原设定时间不得被改", original.triggerAt, applied.triggerAt)
        assertEquals(now + 600_000L, applied.snoozedUntil)
        assertNull("稍后后应回到未投递状态", applied.deliveredAt)
        assertNull(applied.completedAt)
    }

    @Test
    fun `snooze rejects a minute count outside the allowed range`() {
        for (minutes in listOf(4, 0, -10, 181)) {
            val plan = planOf(reminder(), StandaloneActionPlans.StandaloneAction.SNOOZE, snoozeMinutes = minutes)

            assertEquals(
                "minutes=$minutes",
                StandaloneActionPlans.StandaloneActionRejectReason.SNOOZE_MINUTES_OUT_OF_RANGE,
                (plan as StandaloneActionPlans.StandaloneActionPlan.Rejected).rejectReason
            )
        }
    }

    @Test
    fun `snooze accepts the boundary minute counts`() {
        for (minutes in listOf(5, 180)) {
            val plan = planOf(reminder(), StandaloneActionPlans.StandaloneAction.SNOOZE, snoozeMinutes = minutes)

            assertTrue("minutes=$minutes", plan is StandaloneActionPlans.StandaloneActionPlan.Snoozed)
        }
    }

    @Test
    fun `snooze requires the reminder to have been delivered`() {
        val plan = planOf(reminder(deliveredAt = null), StandaloneActionPlans.StandaloneAction.SNOOZE, deliveredAt = null)

        assertEquals(
            StandaloneActionPlans.StandaloneActionRejectReason.NOT_DELIVERED_YET,
            (plan as StandaloneActionPlans.StandaloneActionPlan.Rejected).rejectReason
        )
    }

    @Test
    fun `snooze rejects a zero delivery time`() {
        // 镜像真实 snooze() 的 `deliveredAt > 0`：0 与负数都必须拒绝（这条分支此前没有用例钉住）。
        for (delivered in listOf(0L, -1L)) {
            val plan = planOf(reminder(deliveredAt = delivered), StandaloneActionPlans.StandaloneAction.SNOOZE, deliveredAt = delivered)

            assertEquals(
                "deliveredAt=$delivered",
                StandaloneActionPlans.StandaloneActionRejectReason.NOT_DELIVERED_YET,
                (plan as StandaloneActionPlans.StandaloneActionPlan.Rejected).rejectReason
            )
        }
    }

    @Test
    fun `snooze rejects a delivery time that does not match the record`() {
        val plan = planOf(
            reminder(deliveredAt = now - 30_000L),
            StandaloneActionPlans.StandaloneAction.SNOOZE,
            deliveredAt = now - 31_000L
        )

        assertEquals(
            StandaloneActionPlans.StandaloneActionRejectReason.DELIVERY_MISMATCH,
            (plan as StandaloneActionPlans.StandaloneActionPlan.Rejected).rejectReason
        )
    }

    // ------------------------------------------------------------ 移入收集箱

    @Test
    fun `moving to inbox produces a draft and completes the reminder by default`() {
        val original = reminder(title = "交电费")

        val plan = planOf(original, StandaloneActionPlans.StandaloneAction.MOVE_TO_INBOX)
        val moved = plan as StandaloneActionPlans.StandaloneActionPlan.MovedToInbox

        assertEquals("交电费", moved.draft.title)
        assertTrue("草案必须标明来源，用户才认得出它是从提醒来的", moved.draft.sourceLabel.isNotBlank())
        assertTrue(moved.completesReminder)
        assertEquals(now, StandaloneActionPlans.applyTo(original, plan).completedAt)
    }

    @Test
    fun `moving to inbox can keep the reminder open when asked to`() {
        val original = reminder()

        val plan = planOf(original, StandaloneActionPlans.StandaloneAction.MOVE_TO_INBOX, completeAfterMove = false)
        val moved = plan as StandaloneActionPlans.StandaloneActionPlan.MovedToInbox

        assertFalse(moved.completesReminder)
        assertNull(moved.completedAt)
        assertNull(StandaloneActionPlans.applyTo(original, plan).completedAt)
    }

    @Test
    fun `moving an already completed reminder to inbox is rejected`() {
        val plan = planOf(reminder(completedAt = now - 1_000L), StandaloneActionPlans.StandaloneAction.MOVE_TO_INBOX)

        assertEquals(
            StandaloneActionPlans.StandaloneActionRejectReason.ALREADY_COMPLETED,
            (plan as StandaloneActionPlans.StandaloneActionPlan.Rejected).rejectReason
        )
    }

    @Test
    fun `planning the same input twice gives the same answer`() {
        // 纯函数：同一输入重复调用结果相同。
        // 注意强度上限：这里只说明"没有随机源/墙钟"，它**不能**发现存储或通知写入。
        val original = reminder()

        val first = planOf(original, StandaloneActionPlans.StandaloneAction.MOVE_TO_INBOX)
        val second = planOf(original, StandaloneActionPlans.StandaloneAction.MOVE_TO_INBOX)

        assertEquals(first, second)
    }

    @Test
    fun `the implementation file imports nothing and cannot touch storage or notifications`() {
        // 上一条测试无法证明"不写存储"，这里用源文本守卫补上（同仓先例：ReminderRestoreCoverageTest）。
        // 先剥掉行首注释再匹配：实现文件的 KDoc 里就写着"不读不写 SharedPreferences"这句承诺，
        // 不剥注释会把它自己误判成违规（这条守卫第一次跑就是这么红的）。
        // 强度上限：只证明"代码行里没有这些 import / 关键字"，不是语义守卫。
        val file = java.io.File("src/main/java/com/sakata/focusflow/StandaloneActionPlans.kt")
        assertTrue("找不到 ${file.path}", file.isFile)

        val codeLines = file.readText().lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .toList()

        for (forbidden in listOf("import android", "SharedPreferences", "AlarmManager", "PendingIntent", "notify(")) {
            assertTrue(
                "纯计划文件不得出现 $forbidden（否则它就不再是零写入、零通知的纯函数）",
                codeLines.none { it.contains(forbidden) }
            )
        }
    }

    // ------------------------------------------------------------ 开始（未放行）

    @Test
    fun `start is explicitly rejected because its meaning is not settled`() {
        val plan = planOf(reminder(), StandaloneActionPlans.StandaloneAction.START)

        val rejected = plan as StandaloneActionPlans.StandaloneActionPlan.Rejected
        assertEquals(StandaloneActionPlans.StandaloneActionRejectReason.UNSUPPORTED_ACTION, rejected.rejectReason)
        assertTrue("拒绝理由要说清是未定稿，而不是含糊的失败", rejected.reason.contains("未定稿"))
    }

    @Test
    fun `start is rejected even for a reminder that has never been delivered`() {
        // 与上一条**状态不同**的输入（未投递），结论相同：拒绝的理由是动作本身未放行，不是提醒状态。
        val fresh = reminder(deliveredAt = null)

        val plan = planOf(fresh, StandaloneActionPlans.StandaloneAction.START, deliveredAt = null)

        assertEquals(
            StandaloneActionPlans.StandaloneActionRejectReason.UNSUPPORTED_ACTION,
            (plan as StandaloneActionPlans.StandaloneActionPlan.Rejected).rejectReason
        )
    }

    // ------------------------------------------------------------ 施加计划的契约

    @Test
    fun `applying a rejected plan fails loudly instead of silently doing nothing`() {
        val rejected = planOf(reminder(completedAt = now), StandaloneActionPlans.StandaloneAction.COMPLETE)

        assertThrows(IllegalStateException::class.java) {
            StandaloneActionPlans.applyTo(reminder(), rejected)
        }
    }

    @Test
    fun `an inconsistent inbox plan fails loudly`() {
        // completesReminder=true 却没有完成时间 ⇒ 构造错误，不能编一个 0 出来。
        val inconsistent = StandaloneActionPlans.StandaloneActionPlan.MovedToInbox(
            draft = StandaloneActionPlans.InboxDraft(title = "交电费", sourceLabel = "来自提醒"),
            completesReminder = true,
            completedAt = null,
            reason = "测试用"
        )

        assertThrows(IllegalArgumentException::class.java) {
            StandaloneActionPlans.applyTo(reminder(), inconsistent)
        }
    }

    @Test
    fun `every action has a non-empty explanation`() {
        for (action in StandaloneActionPlans.StandaloneAction.entries) {
            val plan = planOf(reminder(), action)

            assertTrue("action=$action 必须给出人话理由", plan.reason.isNotBlank())
        }
    }
}
