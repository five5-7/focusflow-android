package com.sakata.focusflow

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 阶段 7.4 · 重复规则闭合组的**纯**恢复策略。
 *
 * 本模块只做判定与产出：不读系统时间、不依赖 Context、不触碰存储，也不接入任何删除/恢复入口或清扫。
 * 所有时间基准（[now]、[zoneId]）与课程条件由调用方传入，便于纯 JVM 单元测试。
 *
 * 契约要点（与 `docs/9.0-stage7-4-design-proposal.md` 同步）：
 * - 恢复资格比较**当前状态与 [RecoveryInstanceMember.postState]**，绝不拿当前状态去比 `preDetail` 文案。
 * - 先区分 `dayOnly`，再判断时间：`dayOnly` 的 `scheduledAt` 是**当地当天零点**，不等于 null；
 *   当天及未来可恢复，过去跳过。有具体时间的实例只在 `preScheduledAt > now` 时恢复，`== now` 跳过。
 * - `class_day` 还必须满足**恢复当下**该日仍有课程（由调用方经 [RecoveryInstanceMember.requiresCourse] 声明）。
 * - `repeatOccurrenceDay` 是**当地日期零点时间戳**，与 `TaskHistory.dayStartOf` 同一表示，
 *   不是 `LocalDate.epochDay`；课程判定内部再换算 epochDay。
 * - 组终态只有 `ACTIVE` 与 `RESTORED`；恢复成功后即使有实例被跳过也进入 `RESTORED`。
 * - 恢复幂等：`groupRestored == true` 时不再改数据。
 * - 恢复资格要求 `now < expiresAt`；`now == expiresAt` 已不可恢复。
 *
 * 本模块**不**负责：写回存储、追加事件、清理组负载、放宽 `repeatGenerated` 的永久删除保护。
 */

/** 组生命周期。本批只实现 ACTIVE 与 RESTORED 两个终态。 */
enum class RecoveryGroupStatus { ACTIVE, RESTORED }

/** 单个实例的恢复结果；名称即契约里的 `restored` / `skipped_past` / `skipped_course`。 */
enum class RecoveryInstanceStatus { RESTORED, SKIPPED_PAST, SKIPPED_COURSE }

/** 模板恢复结果的分类。 */
enum class RecoveryTemplateStatus { RESTORED, ALREADY_RESTORED, EXPIRED, CONFLICT }

data class RecoveryTemplateMember(
    val templateId: Long,
    /** 删除产生的墓碑（含 `trashedAt`、`trashSnapshot`），用于与当前状态核对。 */
    val postState: Item,
    /** 删除**前**的规则模板，用于取回删除动作改掉的字段。 */
    val preState: Item,
)

data class RecoveryInstanceMember(
    val itemId: Long,
    /** 删除**前**的实例，成功恢复时按字段还原到它。 */
    val preState: Item,
    /** 删除**后**的实例（本次删除留下的状态）：恢复资格与冲突判定都以它为准。 */
    val postState: Item,
    /** 删除时对该实例的处理原因，随组记录、用于展示与审计；**不**参与恢复资格判定。 */
    val handlingReason: String,
    /** 该实例是否属于 `class_day` 规则：为 true 时还要满足「恢复当下该日仍有课程」。 */
    val requiresCourse: Boolean = false,
)

data class RecoveryClosure(
    val kind: String,
    val template: RecoveryTemplateMember,
    val instances: List<RecoveryInstanceMember>,
    val deletedAt: Long,
    val expiresAt: Long,
    /** 组已恢复标记；为 true 时恢复幂等，不再改数据。 */
    val groupRestored: Boolean = false,
)

data class RecoveryInstanceOutcome(
    val itemId: Long,
    val status: RecoveryInstanceStatus,
    /** 成功恢复时为还原后的项；跳过时为 null。 */
    val restoredItem: Item?,
)

data class RecoveryOutcome(
    val status: RecoveryGroupStatus,
    val templateStatus: RecoveryTemplateStatus,
    /** 成功恢复时为还原后的模板；否则为 null。 */
    val restoredTemplate: Item?,
    val instanceOutcomes: List<RecoveryInstanceOutcome>,
) {
    val isTerminal: Boolean get() = status == RecoveryGroupStatus.RESTORED

    fun restoredCount(): Int = instanceOutcomes.count { it.status == RecoveryInstanceStatus.RESTORED }

    fun skippedCount(): Int = instanceOutcomes.size - restoredCount()
}

internal object RepeatClosureRecovery {
    /** 新闭合组的保留时长：30 × 24 小时（与既有普通组一致）。 */
    const val RETENTION_MS: Long = 30L * 24L * 60L * 60L * 1000L

    /** `deletedAt` 的上限；超过即拒绝创建，避免 `expiresAt` 溢出（沿用既有普通组的口径）。 */
    fun retentionLimitAt(deletedAt: Long): Long = Long.MAX_VALUE - RETENTION_MS

    fun expiresAtFor(deletedAt: Long): Long = deletedAt + RETENTION_MS

    /** 恢复资格：`now < expiresAt` 才可恢复；`now == expiresAt` 已不可恢复。 */
    fun isRecoverable(expiresAt: Long, now: Long): Boolean = now < expiresAt

    /** `dayOnly` / 课程判定共用的当地当天零点；与 `TaskHistory.dayStartOf` 同一表示。 */
    fun dayStartOf(millis: Long, zoneId: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()

    /** 该日是否仍有课程：与 `RepeatActions.coursesOn` 同一口径（未待确认、星期一致、当天生效）。 */
    fun hasCourseOn(occurrenceDay: Long, courses: List<Course>, zoneId: ZoneId): Boolean {
        val epochDay = Instant.ofEpochMilli(occurrenceDay).atZone(zoneId).toLocalDate().toEpochDay()
        val weekday = LocalDate.ofEpochDay(epochDay).dayOfWeek.value
        return courses.any { course ->
            !course.needsConfirmation && course.weekday == weekday && CourseActivationPolicy.isActiveOn(course, epochDay)
        }
    }

    /**
     * 恢复一个重复规则闭合组。
     *
     * @param currentItems 当前条目快照；用于核对成员 `postState` 并产出还原结果。
     * @param courses 恢复**当下**的课程表；`class_day` 用它判定该日是否仍有课。
     * @param zoneId 当地时区；`dayOnly` 的当天判定与 `class_day` 的 epochDay 换算都用它。
     */
    fun recover(
        closure: RecoveryClosure,
        currentItems: List<Item>,
        now: Long,
        zoneId: ZoneId,
        courses: List<Course>,
    ): RecoveryOutcome {
        if (closure.groupRestored) {
            return rejected(RecoveryGroupStatus.RESTORED, RecoveryTemplateStatus.ALREADY_RESTORED)
        }
        if (!isRecoverable(closure.expiresAt, now)) {
            return rejected(RecoveryGroupStatus.ACTIVE, RecoveryTemplateStatus.EXPIRED)
        }
        val byId = currentItems.associateBy(Item::id)
        val currentTemplate = byId[closure.template.templateId]
        if (currentTemplate != closure.template.postState || !hasIntactSnapshot(closure.template.postState)) {
            return rejected(RecoveryGroupStatus.ACTIVE, RecoveryTemplateStatus.CONFLICT)
        }

        val today = dayStartOf(now, zoneId)
        // 先算逐项结果再决定是否放行：任一冲突即整组拒绝、零修改。
        val plans = closure.instances.map { member ->
            member to planInstance(member, byId[member.itemId], now, today, zoneId, courses)
        }
        if (plans.any { (_, plan) -> plan == InstancePlan.Conflict }) {
            return rejected(RecoveryGroupStatus.ACTIVE, RecoveryTemplateStatus.CONFLICT)
        }

        val outcomes = plans.map { (member, plan) ->
            when (plan) {
                is InstancePlan.Restore -> RecoveryInstanceOutcome(
                    member.itemId,
                    RecoveryInstanceStatus.RESTORED,
                    restoreInstanceFields(member.preState, member.postState),
                )
                InstancePlan.SkipPast -> RecoveryInstanceOutcome(member.itemId, RecoveryInstanceStatus.SKIPPED_PAST, null)
                InstancePlan.SkipCourse ->
                    RecoveryInstanceOutcome(member.itemId, RecoveryInstanceStatus.SKIPPED_COURSE, null)
                InstancePlan.Conflict -> error("conflict is rejected before this point")
            }
        }
        // 有实例被跳过仍是成功恢复：组进入 RESTORED 终态。
        return RecoveryOutcome(
            status = RecoveryGroupStatus.RESTORED,
            templateStatus = RecoveryTemplateStatus.RESTORED,
            restoredTemplate = restoreTemplateFields(closure.template.preState, closure.template.postState),
            instanceOutcomes = outcomes,
        )
    }

    /**
     * 模板还原：只取删除动作改掉的字段，并**正确清掉**本次删除产生的 `trashedAt` 与 `trashSnapshot`，
     * 同时恢复删除前的暂停状态。其余字段保持现值，避免覆盖用户后续编辑。
     */
    fun restoreTemplateFields(preState: Item, postState: Item): Item = postState.copy(
        kind = preState.kind,
        detail = preState.detail,
        repeatFrequency = preState.repeatFrequency,
        repeatPaused = preState.repeatPaused,
        repeatStartDay = preState.repeatStartDay,
        repeatMinute = preState.repeatMinute,
        trashedAt = null,
        trashSnapshot = null,
    )

    /**
     * 实例还原：**只还原删除动作实际修改的字段**（`kind` / `detail` / `scheduledAt` / `dayOnly` / 窗口），
     * 其余字段（`userNote`、`checklist`、`priority`、`done` 等）保持现值。
     */
    fun restoreInstanceFields(preState: Item, postState: Item): Item = postState.copy(
        kind = preState.kind,
        detail = preState.detail,
        scheduledAt = preState.scheduledAt,
        dayOnly = preState.dayOnly,
        windowStartAt = preState.windowStartAt,
        windowEndAt = preState.windowEndAt,
    )

    /** 恢复资格判定（供调用方在需要时单独复用；语义与 [recover] 内一致）。 */
    fun instanceStatus(
        member: RecoveryInstanceMember,
        currentItems: List<Item>,
        now: Long,
        zoneId: ZoneId,
        courses: List<Course>,
    ): RecoveryInstanceStatus? {
        val current = currentItems.associateBy(Item::id)[member.itemId]
        return when (planInstance(member, current, now, dayStartOf(now, zoneId), zoneId, courses)) {
            is InstancePlan.Restore -> RecoveryInstanceStatus.RESTORED
            InstancePlan.SkipPast -> RecoveryInstanceStatus.SKIPPED_PAST
            InstancePlan.SkipCourse -> RecoveryInstanceStatus.SKIPPED_COURSE
            InstancePlan.Conflict -> null
        }
    }

    private sealed interface InstancePlan {
        data class Restore(val item: Item) : InstancePlan
        data object SkipPast : InstancePlan
        data object SkipCourse : InstancePlan
        data object Conflict : InstancePlan
    }

    private fun planInstance(
        member: RecoveryInstanceMember,
        current: Item?,
        now: Long,
        today: Long,
        zoneId: ZoneId,
        courses: List<Course>,
    ): InstancePlan {
        // 资格与冲突都只看「当前 vs postState」，绝不比对 detail 文案。
        if (current == null || current != member.postState) return InstancePlan.Conflict
        val pre = member.preState
        if (!timeQualifies(pre, now, today, zoneId)) return InstancePlan.SkipPast
        if (!occurrenceDayQualifies(pre, today)) return InstancePlan.SkipPast
        if (member.requiresCourse && !hasCourseOn(requireNotNull(pre.repeatOccurrenceDay), courses, zoneId)) {
            return InstancePlan.SkipCourse
        }
        return InstancePlan.Restore(pre)
    }

    /**
     * 先区分 `dayOnly`，再判断时间。
     * `dayOnly` 的 `scheduledAt` 是当地当天零点（可能非 null）：按日比较，当天与未来可恢复，过去跳过。
     * 有具体时间的实例只在 `preScheduledAt > now` 时恢复；`== now` 跳过。
     */
    private fun timeQualifies(pre: Item, now: Long, today: Long, zoneId: ZoneId): Boolean {
        val at = pre.scheduledAt ?: return false
        return if (pre.dayOnly) dayStartOf(at, zoneId) >= today else at > now
    }

    /** `dayOnly` 的日判定与 `repeatOccurrenceDay` 的日判定共用同一表示（当地零点时间戳）。 */
    private fun occurrenceDayQualifies(pre: Item, today: Long): Boolean {
        val occurrenceDay = pre.repeatOccurrenceDay ?: return true
        return occurrenceDay >= today
    }

    private fun hasIntactSnapshot(tombstone: Item): Boolean =
        tombstone.kind == "回收站" && (tombstone.trashedAt ?: 0L) > 0L && tombstone.trashSnapshot != null

    private fun rejected(status: RecoveryGroupStatus, templateStatus: RecoveryTemplateStatus) =
        RecoveryOutcome(status, templateStatus, null, emptyList())
}
