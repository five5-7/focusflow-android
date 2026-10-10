package com.sakata.focusflow

/**
 * 阶段 6 组④：自定义提醒通知动作的**纯计划**（不接线、不写存储、不碰通知）。
 *
 * 授权边界（见 `docs/9.0-stage6-standalone-actions-design.md`）：
 * - 只回答"这个动作现在允不允许、允许的话状态会变成什么样"；
 * - 不读不写 SharedPreferences、不排闹钟、不建通知、不新增渠道；
 * - **"开始"动作语义未定稿 ⇒ 一律拒绝**（见 [StandaloneActionRejectReason.UNSUPPORTED_ACTION]）。
 *
 * 本件的守卫条件**逐条镜像**现有写入函数的既有语义：
 * - 完成镜像 `StandaloneReminders.complete`：`completedAt == null` 且 `expectedAt == scheduledAt`；
 * - 稍后镜像 `StandaloneReminders.snooze`：在其条件之上，还要求 `deliveredAt == 传入的 deliveredAt`、
 *   `deliveredAt > 0`、`snoozeMinutes in 5..180`；
 * - 稍后**只改下一次通知时间**（`snoozedUntil`），原设定时间 `triggerAt` 不变。
 *
 * **没有建模的（接线时须自行承担，别以为是已覆盖）**：
 * - `markDelivered` 的两条前置（`deliveredAt == null`、`expectedAt <= now`）——那是"通知投递"这一步，属上游；
 * - 记录的存在性与定位（`id` 匹配）、加载时的合法性过滤（`id > 0 && triggerAt > 0`）；
 * - 存储写入可能失败（既有 `save()` 在守卫全过时仍可能返回 false）⇒ 本件给出 `Completed` 只代表**允许**这个动作，
 *   **不代表已经写入**；
 * - 写路径"其余条目原样保留、不重建不清空"的契约（纯函数无法表达）。
 */
internal object StandaloneActionPlans {

    internal enum class StandaloneAction {
        COMPLETE,
        SNOOZE,

        /** 把提醒变成收集箱条目。本件只产出**草案**，写入留给下一个检查点。 */
        MOVE_TO_INBOX,

        /** 语义未定稿（活动会话？计划推进？），**未放行**。 */
        START
    }

    internal enum class StandaloneActionRejectReason {
        /** 动作携带的 `expectedAt` 与提醒当前的 `scheduledAt` 不一致：这是旧通知上的动作。 */
        STALE_ACTION,

        ALREADY_COMPLETED,

        /** 稍后要求这条提醒**已经投递**（通知已经出现过）。 */
        NOT_DELIVERED_YET,

        /** 稍后要求的投递时刻与实际不符（通知被重建过）。 */
        DELIVERY_MISMATCH,

        SNOOZE_MINUTES_OUT_OF_RANGE,

        /** "开始"语义未定稿：不猜、不实现。 */
        UNSUPPORTED_ACTION
    }

    /** 收集箱条目的**草案**（纯数据，不落库）。 */
    internal data class InboxDraft(
        val title: String,
        /** 来源标记，便于用户在收集箱里认出它是从提醒来的。 */
        val sourceLabel: String
    )

    internal sealed interface StandaloneActionPlan {

        val reason: String

        data class Rejected(
            val rejectReason: StandaloneActionRejectReason,
            override val reason: String
        ) : StandaloneActionPlan

        data class Completed(
            val completedAt: Long,
            override val reason: String
        ) : StandaloneActionPlan

        /** [snoozedUntil] 是**下一次通知时间**；`triggerAt` 不变。 */
        data class Snoozed(
            val snoozedUntil: Long,
            override val reason: String
        ) : StandaloneActionPlan

        data class MovedToInbox(
            val draft: InboxDraft,
            /** 移入收集箱后是否同时把提醒标记完成（默认 true；见设计件 §4 待定稿问题 3）。 */
            val completesReminder: Boolean,
            /** 完成时间；[completesReminder] 为 true 时必须非空（否则视为构造错误）。 */
            val completedAt: Long?,
            override val reason: String
        ) : StandaloneActionPlan
    }

    private val SNOOZE_MINUTES_RANGE = 5..180
    private const val DEFAULT_SNOOZE_MINUTES = 10
    private const val INBOX_SOURCE_LABEL = "来自提醒"

    /**
     * 拟一份动作计划。
     *
     * @param expectedAt 动作里携带的那次时间；必须等于提醒当前的 `scheduledAt`，否则判为旧动作。
     * @param deliveredAt 通知投递时刻（稍后需要；完成不需要）。
     */
    internal fun plan(
        reminder: StandaloneReminder,
        action: StandaloneAction,
        now: Long,
        expectedAt: Long,
        deliveredAt: Long? = reminder.deliveredAt,
        snoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES,
        completeAfterMove: Boolean = true
    ): StandaloneActionPlan {
        if (reminder.scheduledAt != expectedAt) {
            return rejected(
                StandaloneActionRejectReason.STALE_ACTION,
                "这是旧通知上的动作：动作时间（$expectedAt）与提醒当前时间（${reminder.scheduledAt}）不一致。"
            )
        }
        if (reminder.completedAt != null) {
            return rejected(StandaloneActionRejectReason.ALREADY_COMPLETED, "这条提醒已经完成过了。")
        }

        return when (action) {
            StandaloneAction.COMPLETE -> StandaloneActionPlan.Completed(
                completedAt = now,
                reason = "标记完成，时间记为 $now。"
            )

            StandaloneAction.SNOOZE -> planSnooze(reminder, deliveredAt, snoozeMinutes, now)

            StandaloneAction.MOVE_TO_INBOX -> StandaloneActionPlan.MovedToInbox(
                draft = InboxDraft(title = reminder.title, sourceLabel = INBOX_SOURCE_LABEL),
                completesReminder = completeAfterMove,
                completedAt = if (completeAfterMove) now else null,
                reason = "把这条提醒移入收集箱" +
                    if (completeAfterMove) "，并同时标记完成（避免重复提醒）。" else "，提醒保持未完成。"
            )

            StandaloneAction.START -> rejected(
                StandaloneActionRejectReason.UNSUPPORTED_ACTION,
                "\"开始\"的语义尚未定稿（活动会话还是计划推进？），本组不放行，故不实现。"
            )
        }
    }

    private fun planSnooze(
        reminder: StandaloneReminder,
        deliveredAt: Long?,
        snoozeMinutes: Int,
        now: Long
    ): StandaloneActionPlan {
        if (snoozeMinutes !in SNOOZE_MINUTES_RANGE) {
            return rejected(
                StandaloneActionRejectReason.SNOOZE_MINUTES_OUT_OF_RANGE,
                "稍后分钟数 $snoozeMinutes 不在允许范围（$SNOOZE_MINUTES_RANGE）内。"
            )
        }
        if (deliveredAt == null || deliveredAt <= 0) {
            return rejected(StandaloneActionRejectReason.NOT_DELIVERED_YET, "这条提醒还没有投递过，无法稍后。")
        }
        if (reminder.deliveredAt != deliveredAt) {
            return rejected(
                StandaloneActionRejectReason.DELIVERY_MISMATCH,
                "投递时刻不符（动作携带 $deliveredAt，提醒记录 ${reminder.deliveredAt}）：通知被重建过。"
            )
        }
        return StandaloneActionPlan.Snoozed(
            snoozedUntil = now + snoozeMinutes * 60_000L,
            reason = "推迟到 ${now + snoozeMinutes * 60_000L} 再提醒一次；原设定时间不变。"
        )
    }

    private fun rejected(
        rejectReason: StandaloneActionRejectReason,
        reason: String
    ) = StandaloneActionPlan.Rejected(rejectReason, reason)

    /**
     * 把计划施加到提醒上，得到一个**新的纯数据**（不写存储）。
     *
     * 对 [StandaloneActionPlan.Rejected] 抛 [IllegalStateException]：拒绝的计划本来就不该被施加，
     * 与其静默返回原值，不如让调用方立刻发现自己在用被拒绝的计划。
     */
    internal fun applyTo(reminder: StandaloneReminder, plan: StandaloneActionPlan): StandaloneReminder = when (plan) {
        is StandaloneActionPlan.Rejected ->
            throw IllegalStateException("拒绝的计划不能被施加：${plan.rejectReason} / ${plan.reason}")

        is StandaloneActionPlan.Completed -> reminder.copy(completedAt = plan.completedAt)

        // 只改下一次通知时间：triggerAt 原样保留（设计件把这条列为不得弱化的语义）。
        is StandaloneActionPlan.Snoozed -> reminder.copy(
            snoozedUntil = plan.snoozedUntil,
            deliveredAt = null
        )

        is StandaloneActionPlan.MovedToInbox -> if (plan.completesReminder) {
            // 计划说"移入即完成"却没有给完成时间 ⇒ 构造错误，响亮失败而不是编一个 0。
            reminder.copy(completedAt = requireNotNull(plan.completedAt) { "移入收集箱并标记完成时必须给定完成时间" })
        } else {
            reminder
        }
    }
}
