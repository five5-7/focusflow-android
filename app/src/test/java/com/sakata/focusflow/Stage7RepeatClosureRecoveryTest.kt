package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 阶段 7.4 纯恢复策略的单元测试。
 *
 * 每条用例都在「规则被写反」时必然变红，例如：
 * - 把 `now < expiresAt` 判反 → `before boundary …` / `at boundary …`；
 * - 把 `dayOnly` 当成 `scheduledAt == null` → `dayOnly today morning …`；
 * - 用 `detail` 文案或部分字段代替完整 `postState` 比较 → 三条 `conflict …`；
 * - 用 `>= now` 代替 `> now` → `timed instance exactly at trigger is skipped`；
 * - 忘记按模板频率推导课程条件 → `class day …` 两条。
 *
 * 夹具完全确定性：固定时区与固定 `now`，不读系统时间。
 * [snapshot] 会去重，保证每个当前 ID 只出现一次（模板墓碑只加一次）。
 */
class Stage7RepeatClosureRecoveryTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 9, 30)
    private val now: Long = at(day, LocalTime.of(9, 0))
    private val todayStart: Long = RepeatClosureRecovery.dayStartOf(now, zone)
    private val deletedAt: Long = now - 60_000L
    private val templateId = 700L

    private fun at(date: LocalDate, time: LocalTime): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    // ---------- 夹具 ----------

    private fun templatePre(pausedBeforeDeletion: Boolean = false, frequency: String = "daily") = Item(
        id = templateId,
        title = "晨读",
        detail = "每天重复",
        kind = "重复模板",
        repeatFrequency = frequency,
        repeatStartDay = todayStart,
        repeatMinute = 9 * 60,
        repeatPaused = pausedBeforeDeletion,
    )

    /** 删除产生的墓碑，同时作为模板的 `postState`。 */
    private fun templatePost(pre: Item = templatePre()) = pre.copy(
        kind = "回收站",
        detail = "重复规则已删除",
        repeatFrequency = "",
        repeatPaused = true,
        trashedAt = deletedAt,
        trashSnapshot = ItemsCodec.encode(listOf(pre)),
    )

    /** 删除把实例改成「重复历史」并清空时间与窗口；`userNote` 物化与 `stop` 一致。 */
    private fun instanceMember(
        scheduledAt: Long,
        id: Long,
        dayOnly: Boolean = false,
        occurrenceDay: Long = todayStart,
        detailAfterDeletion: String = "规则停止，取消本次",
        handlingReason: String = detailAfterDeletion,
    ): RecoveryInstanceMember {
        val pre = Item(
            id = id,
            title = "晨读",
            detail = "09:00 · 30分钟",
            kind = "任务",
            scheduledAt = scheduledAt,
            dayOnly = dayOnly,
            repeatTemplateId = templateId,
            repeatOccurrenceDay = occurrenceDay,
            repeatMinute = 9 * 60,
        )
        val post = pre.preservingNote().copy(
            kind = "重复历史",
            detail = detailAfterDeletion,
            scheduledAt = null,
            dayOnly = false,
            windowStartAt = null,
            windowEndAt = null,
        )
        return RecoveryInstanceMember(id, pre, post, handlingReason)
    }

    private fun closure(
        members: List<RecoveryInstanceMember>,
        expiresAt: Long = RepeatClosureRecovery.expiresAtFor(deletedAt).let { (it as RecoveryExpiry.Ok).expiresAt },
        groupRestored: Boolean = false,
        pre: Item = templatePre(),
        kind: String = RepeatClosureRecovery.KIND,
        deletedAtOverride: Long = deletedAt,
    ) = RecoveryClosure(
        kind = kind,
        template = RecoveryTemplateMember(templateId, templatePost(pre), pre),
        instances = members,
        deletedAt = deletedAtOverride,
        expiresAt = expiresAt,
        groupRestored = groupRestored,
    )

    /** 当前快照：模板墓碑 + 给定条目，按 ID 去重，保证每个 ID 只出现一次。 */
    private fun snapshot(vararg items: Item): List<Item> =
        (listOf(templatePost()) + items.toList()).associateBy(Item::id).values.toList()

    /** 与给定模板 preState 一致的模板墓碑，用于当前快照。 */
    private fun templatePostFor(pre: Item): Item = templatePost(pre)
    private fun recoverWith(
        members: List<RecoveryInstanceMember>,
        courses: List<Course> = emptyList(),
        expiresAt: Long = (RepeatClosureRecovery.expiresAtFor(deletedAt) as RecoveryExpiry.Ok).expiresAt,
        groupRestored: Boolean = false,
        pre: Item = templatePre(),
        kind: String = RepeatClosureRecovery.KIND,
        deletedAtOverride: Long = deletedAt,
        /** 用于构造非法墓碑的测试；默认与 [pre] 一致。 */
        templatePostOverride: Item? = null,
        currentItems: List<Item> = (listOf(templatePostOverride ?: templatePost(pre)) + members.map { it.postState })
            .associateBy(Item::id).values.toList(),
    ): RecoveryOutcome {
        val effectivePost = templatePostOverride ?: templatePost(pre)
        val base = closure(members, expiresAt, groupRestored, pre, kind, deletedAtOverride)
        return RepeatClosureRecovery.recover(
            base.copy(template = RecoveryTemplateMember(templateId, effectivePost, pre)),
            currentItems,
            now,
            zone,
            courses,
        )
    }

    private fun course(weekday: Int, from: Long? = null, until: Long? = null): Course = Course(
        title = "高等数学",
        weekday = weekday,
        startPeriod = 1,
        endPeriod = 2,
        building = "东1-101",
        zone = CampusZone.EAST_TEACHING,
        needsConfirmation = false,
        enabled = true,
        effectiveFromEpochDay = from,
        effectiveUntilEpochDay = until,
    )

    // ---------- 日期与时间资格 ----------

    @Test fun `dayOnly today morning instance is still recoverable`() {
        val member = instanceMember(id = 901L, scheduledAt = todayStart, dayOnly = true)
        val outcome = recoverWith(listOf(member))
        assertEquals(RecoveryGroupStatus.RESTORED, outcome.status)
        assertEquals(RecoveryInstanceStatus.RESTORED, outcome.instanceOutcomes.single().status)
        assertEquals(todayStart, outcome.instanceOutcomes.single().restoredItem?.scheduledAt)
        assertEquals("任务", outcome.instanceOutcomes.single().restoredItem?.kind)
        assertTrue(outcome.instanceOutcomes.single().restoredItem?.dayOnly == true)
    }

    @Test fun `dayOnly past date is skipped`() {
        val yesterday = at(day.minusDays(1), LocalTime.MIDNIGHT)
        val member = instanceMember(id = 901L, scheduledAt = yesterday, dayOnly = true, occurrenceDay = yesterday)
        val outcome = recoverWith(listOf(member))
        assertEquals(RecoveryInstanceStatus.SKIPPED_PAST, outcome.instanceOutcomes.single().status)
        assertNull(outcome.instanceOutcomes.single().restoredItem)
        assertTrue(outcome.isTerminal)
        assertEquals(RecoveryGroupStatus.RESTORED, outcome.status)
    }

    @Test fun `timed instance one millisecond before trigger is recoverable`() {
        val member = instanceMember(id = 901L, scheduledAt = now + 1L)
        val outcome = recoverWith(listOf(member))
        assertEquals(RecoveryInstanceStatus.RESTORED, outcome.instanceOutcomes.single().status)
        assertEquals(now + 1L, outcome.instanceOutcomes.single().restoredItem?.scheduledAt)
    }

    @Test fun `timed instance exactly at trigger is skipped`() {
        val member = instanceMember(id = 901L, scheduledAt = now)
        val outcome = recoverWith(listOf(member))
        assertEquals(RecoveryInstanceStatus.SKIPPED_PAST, outcome.instanceOutcomes.single().status)
    }

    @Test fun `timed instance in the future is recovered`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member))
        assertEquals(RecoveryInstanceStatus.RESTORED, outcome.instanceOutcomes.single().status)
    }

    // ---------- 到期边界 ----------

    @Test fun `before boundary is recoverable`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member), expiresAt = now + 1L)
        assertEquals(RecoveryGroupStatus.RESTORED, outcome.status)
        assertEquals(RecoveryTemplateStatus.RESTORED, outcome.templateStatus)
    }

    @Test fun `at boundary is not recoverable`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member), expiresAt = now)
        assertEquals(RecoveryTemplateStatus.EXPIRED, outcome.templateStatus)
        assertEquals(RecoveryGroupStatus.ACTIVE, outcome.status)
        assertNull(outcome.restoredTemplate)
        assertTrue(outcome.instanceOutcomes.isEmpty())
    }

    @Test fun `expired group past its deadline is not recoverable`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member), expiresAt = deletedAt + 1000L)
        assertEquals(RecoveryTemplateStatus.EXPIRED, outcome.templateStatus)
    }

    // ---------- 到期时刻计算 ----------

    @Test fun `expiry rejects non positive deletedAt and overflow`() {
        assertEquals(RecoveryExpiry.InvalidDeletedAt, RepeatClosureRecovery.expiresAtFor(0L))
        assertEquals(RecoveryExpiry.InvalidDeletedAt, RepeatClosureRecovery.expiresAtFor(-1L))
        assertEquals(
            RecoveryExpiry.Ok(RepeatClosureRecovery.MAX_DELETED_AT + RepeatClosureRecovery.RETENTION_MS),
            RepeatClosureRecovery.expiresAtFor(RepeatClosureRecovery.MAX_DELETED_AT),
        )
        assertEquals(RecoveryExpiry.Overflow, RepeatClosureRecovery.expiresAtFor(RepeatClosureRecovery.MAX_DELETED_AT + 1))
        assertEquals(RecoveryExpiry.Overflow, RepeatClosureRecovery.expiresAtFor(Long.MAX_VALUE))
        assertEquals(
            Long.MAX_VALUE - RepeatClosureRecovery.RETENTION_MS,
            RepeatClosureRecovery.MAX_DELETED_AT,
        )
    }

    @Test fun `expiry keeps the thirty day window`() {
        val base = 1_000_000L
        assertEquals(RecoveryExpiry.Ok(base + 30L * 24 * 60 * 60 * 1000), RepeatClosureRecovery.expiresAtFor(base))
        assertFalse(RepeatClosureRecovery.isRecoverable(expiresAt = now, now = now))
        assertTrue(RepeatClosureRecovery.isRecoverable(expiresAt = now + 1L, now = now))
    }

    // ---------- class_day：由模板频率推导 ----------

    @Test fun `class day future instance is skipped when the course no longer matches`() {
        val future = day.plusDays(1)
        val member = instanceMember(
            id = 901L,
            scheduledAt = at(future, LocalTime.of(10, 0)),
            occurrenceDay = at(future, LocalTime.MIDNIGHT),
        )
        // 模板真实频率为 class_day；恢复当下该日没有课程 → 即使时间在未来也必须跳过。
        val outcome = recoverWith(listOf(member), pre = templatePre(frequency = "class_day"))
        assertEquals(RecoveryInstanceStatus.SKIPPED_COURSE, outcome.instanceOutcomes.single().status)
        assertTrue(outcome.isTerminal)
    }

    @Test fun `daily template does not require a course`() {
        val future = day.plusDays(1)
        val member = instanceMember(
            id = 901L,
            scheduledAt = at(future, LocalTime.of(10, 0)),
            occurrenceDay = at(future, LocalTime.MIDNIGHT),
        )
        // 同一个实例、同样的空课程表：模板是 daily 时不看课程，必须照常恢复。
        val outcome = recoverWith(listOf(member), pre = templatePre(frequency = "daily"))
        assertEquals(RecoveryInstanceStatus.RESTORED, outcome.instanceOutcomes.single().status)
    }

    @Test fun `class day future instance is recovered when the course still matches`() {
        val future = day.plusDays(1)
        val member = instanceMember(
            id = 901L,
            scheduledAt = at(future, LocalTime.of(10, 0)),
            occurrenceDay = at(future, LocalTime.MIDNIGHT),
        )
        val outcome = recoverWith(
            listOf(member),
            courses = listOf(course(future.dayOfWeek.value)),
            pre = templatePre(frequency = "class_day"),
        )
        assertEquals(RecoveryInstanceStatus.RESTORED, outcome.instanceOutcomes.single().status)
    }

    @Test fun `course occurrence day is read as a local day timestamp not an epoch day`() {
        val future = day.plusDays(1)
        val occurrenceDay = at(future, LocalTime.MIDNIGHT)
        assertTrue(RepeatClosureRecovery.hasCourseOn(occurrenceDay, listOf(course(future.dayOfWeek.value)), zone))
        assertFalse(RepeatClosureRecovery.hasCourseOn(occurrenceDay, listOf(course(future.dayOfWeek.value + 1)), zone))
    }

    @Test fun `pending course is not treated as a matching course`() {
        val future = day.plusDays(1)
        val member = instanceMember(
            id = 901L,
            scheduledAt = at(future, LocalTime.of(10, 0)),
            occurrenceDay = at(future, LocalTime.MIDNIGHT),
        )
        val pending = course(future.dayOfWeek.value).copy(needsConfirmation = true)
        val outcome = recoverWith(
            listOf(member),
            courses = listOf(pending),
            pre = templatePre(frequency = "class_day"),
        )
        assertEquals(RecoveryInstanceStatus.SKIPPED_COURSE, outcome.instanceOutcomes.single().status)
    }

    // ---------- postState 冲突判定 ----------

    @Test fun `conflict when userNote changes after deletion`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val edited = member.postState.copy(userNote = "恢复前我改了备注")
        val outcome = recoverWith(listOf(member), currentItems = snapshot(edited))
        assertEquals(RecoveryTemplateStatus.CONFLICT, outcome.templateStatus)
        assertEquals(RecoveryGroupStatus.ACTIVE, outcome.status)
        assertNull(outcome.restoredTemplate)
        assertTrue(outcome.instanceOutcomes.isEmpty())
    }

    @Test fun `conflict when checklist changes after deletion`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val edited = member.postState.copy(checklist = listOf(ChecklistEntry(1L, "新增检查项", true)))
        val outcome = recoverWith(listOf(member), currentItems = snapshot(edited))
        assertEquals(RecoveryTemplateStatus.CONFLICT, outcome.templateStatus)
    }

    @Test fun `conflict when detail is rewritten to look like a miss`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val rewritten = member.postState.copy(detail = "本次未处理")
        val outcome = recoverWith(listOf(member), currentItems = snapshot(rewritten))
        assertEquals(RecoveryTemplateStatus.CONFLICT, outcome.templateStatus)
    }

    @Test fun `conflict when a member is missing from the current snapshot`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member), currentItems = listOf(templatePost()))
        assertEquals(RecoveryTemplateStatus.CONFLICT, outcome.templateStatus)
    }

    @Test fun `untouched deletion matches postState and is not a conflict`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member))
        assertEquals(RecoveryTemplateStatus.RESTORED, outcome.templateStatus)
        assertEquals(RecoveryInstanceStatus.RESTORED, outcome.instanceOutcomes.single().status)
    }

    @Test fun `conflict when the template tombstone changed`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val tampered = templatePost().copy(detail = "用户改过的墓碑")
        val outcome = recoverWith(listOf(member), currentItems = snapshot(tampered, member.postState))
        assertEquals(RecoveryTemplateStatus.CONFLICT, outcome.templateStatus)
    }

    // ---------- 结构与身份守卫 ----------

    @Test fun `rejects a closure of the wrong kind`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member), kind = "ordinary_items")
        assertEquals(RecoveryTemplateStatus.REJECTED, outcome.templateStatus)
        assertEquals(RecoveryRejection.KIND_MISMATCH, outcome.rejection)
        assertEquals(RecoveryGroupStatus.ACTIVE, outcome.status)
    }

    @Test fun `rejects when template pre and post ids disagree with templateId`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val mismatched = RecoveryClosure(
            RepeatClosureRecovery.KIND,
            RecoveryTemplateMember(templateId, templatePost(), templatePre().copy(id = 999L)),
            listOf(member),
            deletedAt,
            (RepeatClosureRecovery.expiresAtFor(deletedAt) as RecoveryExpiry.Ok).expiresAt,
        )
        val outcome = RepeatClosureRecovery.recover(mismatched, snapshot(member.postState), now, zone, emptyList())
        assertEquals(RecoveryRejection.TEMPLATE_ID_MISMATCH, outcome.rejection)
    }

    @Test fun `rejects when an instance id disagrees with itemId`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val broken = member.copy(preState = member.preState.copy(id = 999L))
        val outcome = recoverWith(listOf(broken))
        assertEquals(RecoveryRejection.INSTANCE_ID_MISMATCH, outcome.rejection)
    }

    @Test fun `rejects duplicate member ids`() {
        val first = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val second = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(22, 0)))
        val outcome = recoverWith(listOf(first, second))
        assertEquals(RecoveryRejection.DUPLICATE_MEMBER_ID, outcome.rejection)
    }

    @Test fun `rejects a member that overlaps the template id`() {
        val member = instanceMember(id = templateId, scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member))
        assertEquals(RecoveryRejection.MEMBER_OVERLAPS_TEMPLATE, outcome.rejection)
    }

    @Test fun `rejects when the tombstone trashedAt disagrees with deletedAt`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member), deletedAtOverride = deletedAt + 1L)
        assertEquals(RecoveryTemplateStatus.REJECTED, outcome.templateStatus)
        assertEquals(RecoveryRejection.TEMPLATE_DELETED_AT_MISMATCH, outcome.rejection)
    }

    @Test fun `rejects an unreadable snapshot`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val broken = templatePost().copy(trashSnapshot = "not-json{{{")
        val outcome = recoverWith(listOf(member), templatePostOverride = broken)
        assertEquals(RecoveryTemplateStatus.REJECTED, outcome.templateStatus)
        assertEquals(RecoveryRejection.SNAPSHOT_UNREADABLE, outcome.rejection)
    }

    @Test fun `rejects a snapshot that decodes to more than one record`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val twoRecords = ItemsCodec.encode(listOf(templatePre(), templatePre().copy(id = 998L)))
        val broken = templatePost().copy(trashSnapshot = twoRecords)
        val outcome = recoverWith(listOf(member), templatePostOverride = broken)
        assertEquals(RecoveryTemplateStatus.REJECTED, outcome.templateStatus)
        assertEquals(RecoveryRejection.SNAPSHOT_UNREADABLE, outcome.rejection)
    }

    @Test fun `rejects a snapshot that disagrees with the template preState`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val otherTemplate = templatePre().copy(title = "完全不同的规则")
        val broken = templatePost().copy(trashSnapshot = ItemsCodec.encode(listOf(otherTemplate)))
        val outcome = recoverWith(listOf(member), templatePostOverride = broken)
        assertEquals(RecoveryTemplateStatus.REJECTED, outcome.templateStatus)
        assertEquals(RecoveryRejection.SNAPSHOT_INCONSISTENT, outcome.rejection)
    }

    @Test fun `rejects an instance with no occurrence day instead of throwing`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val broken = member.copy(
            preState = member.preState.copy(repeatOccurrenceDay = null),
            postState = member.postState.copy(repeatOccurrenceDay = null),
        )
        val outcome = recoverWith(listOf(broken))
        assertEquals(RecoveryRejection.INSTANCE_MISSING_OCCURRENCE_DAY, outcome.rejection)
    }

    @Test fun `rejects an instance that belongs to another template`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val broken = member.copy(
            preState = member.preState.copy(repeatTemplateId = 12345L),
            postState = member.postState.copy(repeatTemplateId = 12345L),
        )
        val outcome = recoverWith(listOf(broken))
        assertEquals(RecoveryRejection.INSTANCE_TEMPLATE_MISMATCH, outcome.rejection)
    }

    @Test fun `rejects when the current snapshot contains the same id twice`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        // 不经 snapshot() 去重：直接传入含重复 ID 的原始快照，恢复必须先发现它而不是静默折叠。
        val duplicated = listOf(templatePost(), member.postState, member.postState)
        val outcome = recoverWith(listOf(member), currentItems = duplicated)
        assertEquals(RecoveryTemplateStatus.REJECTED, outcome.templateStatus)
        assertEquals(RecoveryRejection.DUPLICATE_CURRENT_ID, outcome.rejection)
    }

    @Test fun `zero instance group is legal and still terminal`() {
        val outcome = recoverWith(emptyList(), currentItems = listOf(templatePost()))
        assertEquals(RecoveryTemplateStatus.RESTORED, outcome.templateStatus)
        assertEquals(RecoveryGroupStatus.RESTORED, outcome.status)
        assertTrue(outcome.isTerminal)
        assertTrue(outcome.instanceOutcomes.isEmpty())
    }

    // ---------- 真实删除产出 ----------

    @Test fun `recovers the real postState produced by RepeatActions deleteRule`() {
        val template = templatePre(frequency = "daily")
        val instance = Item(
            id = 901L,
            title = "晨读",
            detail = "09:00 · 30分钟",
            kind = "任务",
            scheduledAt = at(day, LocalTime.of(21, 0)),
            repeatTemplateId = templateId,
            repeatOccurrenceDay = todayStart,
            repeatMinute = 9 * 60,
        )
        val deletionAt = now - 60_000L
        val deleted = RepeatActions.deleteRule(listOf(template, instance), template, deletionAt)
        val tombstone = deleted.items.single { it.id == templateId }
        val history = deleted.items.single { it.id == 901L }

        // 真实删除后：模板是墓碑、实例是重复历史。
        assertEquals("回收站", tombstone.kind)
        assertEquals("重复历史", history.kind)
        assertNull(history.scheduledAt)

        val closure = RecoveryClosure(
            RepeatClosureRecovery.KIND,
            RecoveryTemplateMember(templateId, tombstone, template),
            listOf(RecoveryInstanceMember(901L, instance, history, handlingReason = history.detail)),
            deletedAt = deletionAt,
            expiresAt = (RepeatClosureRecovery.expiresAtFor(deletionAt) as RecoveryExpiry.Ok).expiresAt,
        )
        val outcome = RepeatClosureRecovery.recover(closure, listOf(tombstone, history), now, zone, emptyList())

        // 守卫不得误拒真实删除结果（stop 的 preservingNote 会物化笔记）。
        assertEquals(RecoveryTemplateStatus.RESTORED, outcome.templateStatus)
        val restored = outcome.instanceOutcomes.single()
        assertEquals(RecoveryInstanceStatus.RESTORED, restored.status)
        assertEquals("任务", restored.restoredItem?.kind)
        assertEquals(instance.scheduledAt, restored.restoredItem?.scheduledAt)
    }

    @Test fun `real deletion stays recoverable when the instance note is materialized`() {
        // userNote == null 时 stop 会物化为 editableNote；preState 仍是 null，
        // 快照守卫必须接受这一处差异。
        val template = templatePre(frequency = "daily")
        val instance = Item(
            id = 902L,
            title = "晨读",
            detail = "09:00 · 30分钟",
            kind = "任务",
            scheduledAt = at(day, LocalTime.of(21, 0)),
            repeatTemplateId = templateId,
            repeatOccurrenceDay = todayStart,
            userNote = null,
            sourceDetail = "导图上的原始说明",
        )
        assertNull(instance.userNote)
        val deletionAt = now - 60_000L
        val deleted = RepeatActions.deleteRule(listOf(template, instance), template, deletionAt)
        val tombstone = deleted.items.single { it.id == templateId }
        val history = deleted.items.single { it.id == 902L }

        val closure = RecoveryClosure(
            RepeatClosureRecovery.KIND,
            RecoveryTemplateMember(templateId, tombstone, template),
            listOf(RecoveryInstanceMember(902L, instance, history, handlingReason = history.detail)),
            deletedAt = deletionAt,
            expiresAt = (RepeatClosureRecovery.expiresAtFor(deletionAt) as RecoveryExpiry.Ok).expiresAt,
        )
        val outcome = RepeatClosureRecovery.recover(closure, listOf(tombstone, history), now, zone, emptyList())
        assertEquals(RecoveryTemplateStatus.RESTORED, outcome.templateStatus)
    }

    // ---------- 组终态、幂等、暂停与 handlingReason ----------

    @Test fun `successful recovery with skipped instances is still terminal`() {
        val recoverable = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val past = instanceMember(
            id = 902L,
            scheduledAt = at(day.minusDays(2), LocalTime.MIDNIGHT),
            dayOnly = true,
            occurrenceDay = at(day.minusDays(2), LocalTime.MIDNIGHT),
        )
        val outcome = recoverWith(listOf(recoverable, past))
        assertEquals(RecoveryGroupStatus.RESTORED, outcome.status)
        assertTrue(outcome.isTerminal)
        assertEquals(1, outcome.restoredCount())
        assertEquals(1, outcome.skippedCount())
        assertEquals(RecoveryInstanceStatus.SKIPPED_PAST, outcome.instanceOutcomes[1].status)
    }

    @Test fun `handlingReason is preserved and does not affect eligibility`() {
        val member = instanceMember(
            id = 901L,
            scheduledAt = at(day, LocalTime.of(21, 0)),
            detailAfterDeletion = "本次未处理",
            handlingReason = "删除时已错过",
        )
        val outcome = recoverWith(listOf(member))
        // handlingReason 只用于展示/审计：既不影响资格，也不改变还原结果。
        assertEquals(RecoveryInstanceStatus.RESTORED, outcome.instanceOutcomes.single().status)
        assertEquals("删除时已错过", member.handlingReason)
        assertEquals("任务", outcome.instanceOutcomes.single().restoredItem?.kind)
        assertEquals(at(day, LocalTime.of(21, 0)), outcome.instanceOutcomes.single().restoredItem?.scheduledAt)
    }

    @Test fun `recovery is idempotent once the group is restored`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member), groupRestored = true)
        assertEquals(RecoveryTemplateStatus.ALREADY_RESTORED, outcome.templateStatus)
        assertNull(outcome.restoredTemplate)
        assertTrue(outcome.instanceOutcomes.isEmpty())
    }

    @Test fun `paused before deletion stays paused after recovery`() {
        val pausedPre = templatePre(pausedBeforeDeletion = true)
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(
            listOf(member),
            pre = pausedPre,
            currentItems = snapshot(templatePost(pausedPre), member.postState),
        )
        val restored = requireNotNull(outcome.restoredTemplate)
        assertTrue(restored.repeatPaused)
        assertEquals("重复模板", restored.kind)
        assertEquals("daily", restored.repeatFrequency)
        assertNull(restored.trashedAt)
        assertNull(restored.trashSnapshot)
    }

    @Test fun `running template is restored as running`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member))
        val restored = requireNotNull(outcome.restoredTemplate)
        assertFalse(restored.repeatPaused)
        assertNull(restored.trashedAt)
        assertNull(restored.trashSnapshot)
    }

    @Test fun `restore writes only the fields the deletion changed`() {
        val member = instanceMember(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member))
        val restored = requireNotNull(outcome.instanceOutcomes.single().restoredItem)
        assertEquals("任务", restored.kind)
        assertEquals("09:00 · 30分钟", restored.detail)
        assertEquals(at(day, LocalTime.of(21, 0)), restored.scheduledAt)
        assertEquals(member.postState.userNote, restored.userNote)
        assertEquals(member.postState.priority, restored.priority)
        assertEquals(member.postState.durationMinutes, restored.durationMinutes)
        assertEquals(member.postState.repeatTemplateId, restored.repeatTemplateId)
        assertEquals(member.postState.repeatOccurrenceDay, restored.repeatOccurrenceDay)
    }
}
