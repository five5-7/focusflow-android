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
 * - 恢复资格比较**当前状态与 [RecoveryInstanceMember.postState]**，绝不拿当前状态去比 `detail` 文案。
 * - 先区分 `dayOnly`，再判断时间：`dayOnly` 的 `scheduledAt` 是**当地当天零点**，不等于 null；
 *   当天及未来可恢复，过去跳过。有具体时间的实例只在 `preScheduledAt > now` 时恢复，`== now` 跳过。
 * - `class_day` 的课程条件**由模板的 `repeatFrequency` 推导**，不靠调用方另传标志。
 * - `repeatOccurrenceDay` 是**当地日期零点时间戳**，与 `TaskHistory.dayStartOf` 同一表示，
 *   不是 `LocalDate.epochDay`；课程判定内部再换算 epochDay。
 * - 组终态只有 `ACTIVE` 与 `RESTORED`；恢复成功后即使有实例被跳过也进入 `RESTORED`。
 * - 恢复幂等：`groupRestored == true` 时不再改数据。
 * - 恢复资格要求 `now < expiresAt`；`now == expiresAt` 已不可恢复。
 * - 结构与身份不合法一律返回 [RecoveryTemplateStatus.REJECTED]，**不抛未处理异常**。
 *
 * 本模块**不**负责：写回存储、追加事件、清理组负载、放宽 `repeatGenerated` 的永久删除保护。
 */

/** 组生命周期。本批只实现 ACTIVE 与 RESTORED 两个终态。 */
enum class RecoveryGroupStatus { ACTIVE, RESTORED }

/** 单个实例的恢复结果；名称即契约里的 `restored` / `skipped_past` / `skipped_course`。 */
enum class RecoveryInstanceStatus { RESTORED, SKIPPED_PAST, SKIPPED_COURSE }

/** 模板恢复结果的分类；[REJECTED] 表示结构与身份守卫不通过。 */
enum class RecoveryTemplateStatus { RESTORED, ALREADY_RESTORED, EXPIRED, CONFLICT, REJECTED }

/** 到期时刻计算失败的原因。 */
sealed interface RecoveryExpiry {
    data class Ok(val expiresAt: Long) : RecoveryExpiry
    data object InvalidDeletedAt : RecoveryExpiry
    data object Overflow : RecoveryExpiry
}

/** 结构校验失败的原因，供调用点区分拒绝来源。 */
enum class RecoveryRejection {
    KIND_MISMATCH,
    TEMPLATE_ID_MISMATCH,
    INSTANCE_ID_MISMATCH,
    EMPTY_INSTANCE_ID,
    DUPLICATE_MEMBER_ID,
    MEMBER_OVERLAPS_TEMPLATE,
    TEMPLATE_DELETED_AT_MISMATCH,
    SNAPSHOT_UNREADABLE,
    SNAPSHOT_INCONSISTENT,
    INSTANCE_MISSING_OCCURRENCE_DAY,
    INSTANCE_TEMPLATE_MISMATCH,
    /** 当前条目快照里同一 ID 出现多次。 */
    DUPLICATE_CURRENT_ID,
    /** 模板 `preState` 不是可删除的重复模板（种类或频率不合法）。 */
    INVALID_TEMPLATE_PRE_STATE,
    /** 模板 `postState` 不是删除产生的回收站墓碑。 */
    INVALID_TEMPLATE_POST_STATE,
    /** 实例 `preState` 不是未完成的普通任务。 */
    INVALID_INSTANCE_PRE_STATE,
    /** 实例 `postState` 不符合 `stop` 的真实删除输出。 */
    INVALID_INSTANCE_POST_STATE,
    /** `deletedAt` 非法（小于等于 0）或加上保留时长后溢出。 */
    INVALID_EXPIRY,
    /** 闭包自带的 `expiresAt` 与按 `deletedAt` 计算出的合法期限不一致。 */
    EXPIRY_MISMATCH,
}

/** 当前快照里是否出现了重复 ID（`associateBy` 会静默折叠，必须在使用前发现）。 */
internal fun hasDuplicateItemIds(items: List<Item>): Boolean {
    val seen = mutableSetOf<Long>()
    return items.any { !seen.add(it.id) }
}

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
    /** 仅在 [RecoveryTemplateStatus.REJECTED] 时给出具体原因。 */
    val rejection: RecoveryRejection? = null,
) {
    val isTerminal: Boolean get() = status == RecoveryGroupStatus.RESTORED

    fun restoredCount(): Int = instanceOutcomes.count { it.status == RecoveryInstanceStatus.RESTORED }

    fun skippedCount(): Int = instanceOutcomes.size - restoredCount()
}

internal object RepeatClosureRecovery {
    /** 闭合组种类；恢复入口只接受它。 */
    const val KIND: String = "repeat_rule_closure"

    /** `RepeatActions.stop` 会写入的两种实例文案；其余文案不属真实删除产物。 */
    private val STOP_DETAILS = setOf("本次未处理", "规则停止，取消本次")

    /** 新闭合组的保留时长：30 × 24 小时（与既有普通组一致）。 */
    const val RETENTION_MS: Long = 30L * 24L * 60L * 60L * 1000L

    /** 合法 `deletedAt` 的最大值：加上保留时长后仍不溢出。 */
    const val MAX_DELETED_AT: Long = Long.MAX_VALUE - RETENTION_MS

    /**
     * 计算到期时刻；`deletedAt` 非法或相加溢出时返回失败，而不是静默产出错值。
     * 恢复侧的合法 `deletedAt` 是正数（与既有普通组 `deletedAt > 0` 的口径一致）。
     */
    fun expiresAtFor(deletedAt: Long): RecoveryExpiry = when {
        deletedAt <= 0L -> RecoveryExpiry.InvalidDeletedAt
        deletedAt > MAX_DELETED_AT -> RecoveryExpiry.Overflow
        else -> RecoveryExpiry.Ok(deletedAt + RETENTION_MS)
    }

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
     * 先做结构与身份守卫（kind、ID 归属、成员唯一性、墓碑与快照自洽、必要发生日），
     * 再做逐项资格判定与冲突检测；任一冲突即整组拒绝、零修改。
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
        structureRejection(closure)?.let {
            return rejected(RecoveryGroupStatus.ACTIVE, RecoveryTemplateStatus.REJECTED, it)
        }
        if (!isRecoverable(closure.expiresAt, now)) {
            return rejected(RecoveryGroupStatus.ACTIVE, RecoveryTemplateStatus.EXPIRED)
        }
        // 当前快照自身必须自洽：同一 ID 出现两次即拒绝，否则后面的 associateBy 会把重复静默折叠掉。
        if (hasDuplicateItemIds(currentItems)) {
            return rejected(RecoveryGroupStatus.ACTIVE, RecoveryTemplateStatus.REJECTED, RecoveryRejection.DUPLICATE_CURRENT_ID)
        }
        val byId = currentItems.associateBy(Item::id)
        if (byId[closure.template.templateId] != closure.template.postState) {
            return rejected(RecoveryGroupStatus.ACTIVE, RecoveryTemplateStatus.CONFLICT)
        }

        val requiresCourse = closure.template.preState.repeatFrequency == "class_day"
        val today = dayStartOf(now, zoneId)
        val plans = closure.instances.map { member ->
            member to planInstance(member, byId[member.itemId], now, today, zoneId, courses, requiresCourse)
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
        // 有实例被跳过仍是成功恢复：组进入 RESTORED 终态。零实例组同样合法。
        return RecoveryOutcome(
            status = RecoveryGroupStatus.RESTORED,
            templateStatus = RecoveryTemplateStatus.RESTORED,
            restoredTemplate = restoreTemplateFields(closure.template.preState, closure.template.postState),
            instanceOutcomes = outcomes,
        )
    }

    /**
     * 结构与身份守卫。返回 null 表示通过。
     * 零实例组是合法情况；实例 ID 必须唯一、不得与模板重叠，且当前快照里不得有重复 ID。
     */
    fun structureRejection(closure: RecoveryClosure): RecoveryRejection? {
        if (closure.kind != KIND) return RecoveryRejection.KIND_MISMATCH
        val template = closure.template
        if (template.templateId <= 0L || template.preState.id != template.templateId ||
            template.postState.id != template.templateId
        ) {
            return RecoveryRejection.TEMPLATE_ID_MISMATCH
        }
        // 模板必须真的是「删除前的重复模板」与「删除后的回收站墓碑」这一对。
        if (template.preState.kind != "重复模板" ||
            template.preState.repeatFrequency !in setOf("daily", "weekly", "class_day")
        ) {
            return RecoveryRejection.INVALID_TEMPLATE_PRE_STATE
        }
        if (template.postState.kind != "回收站") return RecoveryRejection.INVALID_TEMPLATE_POST_STATE
        // 墓碑必须与组记录同一删除时刻。
        if (template.postState.trashedAt != closure.deletedAt) {
            return RecoveryRejection.TEMPLATE_DELETED_AT_MISMATCH
        }
        // 期限自洽：非法 deletedAt、溢出、以及与合法计算结果不一致的伪造期限都在此拒绝，
        // 不把这项检查推迟到 Repository 接入。
        when (val expiry = expiresAtFor(closure.deletedAt)) {
            is RecoveryExpiry.Ok -> if (closure.expiresAt != expiry.expiresAt) {
                return RecoveryRejection.EXPIRY_MISMATCH
            }
            RecoveryExpiry.InvalidDeletedAt, RecoveryExpiry.Overflow -> return RecoveryRejection.INVALID_EXPIRY
        }
        when (RepeatClosureSnapshots.checkTemplateSnapshot(template)) {
            RepeatClosureSnapshots.Check.Consistent -> Unit
            RepeatClosureSnapshots.Check.Unreadable -> return RecoveryRejection.SNAPSHOT_UNREADABLE
            RepeatClosureSnapshots.Check.Inconsistent -> return RecoveryRejection.SNAPSHOT_INCONSISTENT
        }
        val seen = mutableSetOf<Long>()
        closure.instances.forEach { member ->
            if (member.itemId <= 0L) return RecoveryRejection.EMPTY_INSTANCE_ID
            if (member.preState.id != member.itemId || member.postState.id != member.itemId) {
                return RecoveryRejection.INSTANCE_ID_MISMATCH
            }
            if (member.itemId == template.templateId) return RecoveryRejection.MEMBER_OVERLAPS_TEMPLATE
            if (!seen.add(member.itemId)) return RecoveryRejection.DUPLICATE_MEMBER_ID
            if (member.preState.repeatTemplateId != template.templateId ||
                member.postState.repeatTemplateId != template.templateId
            ) {
                return RecoveryRejection.INSTANCE_TEMPLATE_MISMATCH
            }
            // 发生日缺失无法判定日期资格：拒绝，而不是抛异常或猜一个日子。
            if (member.preState.repeatOccurrenceDay == null) {
                return RecoveryRejection.INSTANCE_MISSING_OCCURRENCE_DAY
            }
            // 删除前必须是未完成的普通任务。
            if (!isPendingTask(member.preState)) return RecoveryRejection.INVALID_INSTANCE_PRE_STATE
            // 删除后必须正好是 stop 的产物（含合法的备注物化）。
            if (!matchesStopOutput(member)) return RecoveryRejection.INVALID_INSTANCE_POST_STATE
        }
        return null
    }

    private fun isPendingTask(item: Item): Boolean =
        item.kind == "任务" && !item.done && item.trashedAt == null && item.trashSnapshot == null

    /**
     * 实例 `postState` 是否正好是 `RepeatActions.stop` 的输出。
     *
     * `stop` 对命中谓词的实例做 `preservingNote().copy(kind="重复历史", detail=…, scheduledAt=null,
     * dayOnly=false, windowStartAt=null, windowEndAt=null)`；`preservingNote` 在 `userNote == null` 时
     * 把它物化为 `editableNote()`。因此允许两种合法形式：未物化，或恰好物化一次。
     * 其余字段必须原样保留，`trashedAt`/`trashSnapshot` 必须为空（受影响实例从来不是墓碑）。
     */
    private fun matchesStopOutput(member: RecoveryInstanceMember): Boolean {
        val post = member.postState
        if (post.kind != "重复历史" || post.scheduledAt != null || post.dayOnly ||
            post.windowStartAt != null || post.windowEndAt != null ||
            post.trashedAt != null || post.trashSnapshot != null
        ) {
            return false
        }
        // stop 只会写这两条文案之一。
        if (post.detail !in STOP_DETAILS) return false
        return post == member.preState.preservingNote().copy(
            kind = "重复历史",
            detail = post.detail,
            scheduledAt = null,
            dayOnly = false,
            windowStartAt = null,
            windowEndAt = null,
        ) || post == member.preState.copy(
            kind = "重复历史",
            detail = post.detail,
            scheduledAt = null,
            dayOnly = false,
            windowStartAt = null,
            windowEndAt = null,
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
        requiresCourse: Boolean,
    ): InstancePlan {
        // 资格与冲突都只看「当前 vs postState」，绝不比对 detail 文案。
        if (current == null || current != member.postState) return InstancePlan.Conflict
        val pre = member.preState
        if (!timeQualifies(pre, now, today, zoneId)) return InstancePlan.SkipPast
        if (!occurrenceDayQualifies(pre, today)) return InstancePlan.SkipPast
        val occurrenceDay = pre.repeatOccurrenceDay
        if (requiresCourse && occurrenceDay != null && !hasCourseOn(occurrenceDay, courses, zoneId)) {
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

    private fun occurrenceDayQualifies(pre: Item, today: Long): Boolean =
        requireNotNull(pre.repeatOccurrenceDay) >= today

    private fun rejected(
        status: RecoveryGroupStatus,
        templateStatus: RecoveryTemplateStatus,
        rejection: RecoveryRejection? = null,
    ) = RecoveryOutcome(status, templateStatus, null, emptyList(), rejection)
}
