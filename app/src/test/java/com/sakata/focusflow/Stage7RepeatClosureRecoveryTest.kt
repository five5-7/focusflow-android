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
 * - 把 `now < expiresAt` 判反 → `before boundary is recoverable` / `at boundary is not recoverable`；
 * - 把 `dayOnly` 当成 `scheduledAt == null` → `dayOnly today morning instance is still recoverable`；
 * - 用 `preDetail` 文案或部分字段代替完整 `postState` 比较 → 三条 `conflict ...`；
 * - 用 `>= now` 代替 `> now` → `timed instance exactly at trigger is skipped`。
 *
 * 夹具完全确定性：固定时区与固定 `now`，不读系统时间。
 * 所有用例都经 [recoverWith] 提交条目快照，它会把模板墓碑一并放进当前快照——
 * 这是恢复路径的前置条件（缺了它模板查找会失败并被判为冲突）。
 */
class Stage7RepeatClosureRecoveryTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 9, 30)
    private val now: Long = at(day, LocalTime.of(9, 0))
    private val todayStart: Long = RepeatClosureRecovery.dayStartOf(now, zone)
    private val deletedAt: Long = now - 60_000L
    private val templateId = 700L
    private val instanceId = 900L

    private fun at(date: LocalDate, time: LocalTime): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    /** 删除前的规则模板。 */
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

    private val tplPost: Item get() = templatePost()

    /** 删除把实例改成「重复历史」并清空时间与窗口。 */
    private fun instanceMember(
        scheduledAt: Long,
        id: Long = instanceId,
        dayOnly: Boolean = false,
        requiresCourse: Boolean = false,
        occurrenceDay: Long = todayStart,
        detailAfterDeletion: String = "规则停止，取消本次",
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
        val post = pre.copy(
            kind = "重复历史",
            detail = detailAfterDeletion,
            scheduledAt = null,
            dayOnly = false,
            windowStartAt = null,
            windowEndAt = null,
        )
        return RecoveryInstanceMember(id, pre, post, detailAfterDeletion, requiresCourse)
    }

    private fun closure(
        members: List<RecoveryInstanceMember>,
        expiresAt: Long = RepeatClosureRecovery.expiresAtFor(deletedAt),
        groupRestored: Boolean = false,
        pre: Item = templatePre(),
    ) = RecoveryClosure(
        kind = "repeat_rule_closure",
        template = RecoveryTemplateMember(templateId, templatePost(pre), pre),
        instances = members,
        deletedAt = deletedAt,
        expiresAt = expiresAt,
        groupRestored = groupRestored,
    )

    /** 当前快照：模板墓碑 + 给定实例，顺序与恢复无关。 */
    private fun snapshot(vararg members: Item): List<Item> = listOf(tplPost) + members.toList()

    private fun recoverWith(
        members: List<RecoveryInstanceMember>,
        courses: List<Course> = emptyList(),
        expiresAt: Long = RepeatClosureRecovery.expiresAtFor(deletedAt),
        groupRestored: Boolean = false,
        pre: Item = templatePre(),
        currentItems: List<Item> = snapshot(*members.map { it.postState }.toTypedArray()),
    ) = RepeatClosureRecovery.recover(
        closure(members, expiresAt, groupRestored, pre),
        currentItems,
        now,
        zone,
        courses,
    )

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
        val member = instanceMember(scheduledAt = todayStart, dayOnly = true)
        val outcome = recoverWith(listOf(member))
        assertEquals(RecoveryGroupStatus.RESTORED, outcome.status)
        assertEquals(RecoveryInstanceStatus.RESTORED, outcome.instanceOutcomes.single().status)
        assertEquals(todayStart, outcome.instanceOutcomes.single().restoredItem?.scheduledAt)
        assertEquals("任务", outcome.instanceOutcomes.single().restoredItem?.kind)
        assertTrue(outcome.instanceOutcomes.single().restoredItem?.dayOnly == true)
    }

    @Test fun `dayOnly past date is skipped`() {
        val yesterday = at(day.minusDays(1), LocalTime.MIDNIGHT)
        val member = instanceMember(scheduledAt = yesterday, dayOnly = true, occurrenceDay = yesterday)
        val outcome = recoverWith(listOf(member))
        assertEquals(RecoveryInstanceStatus.SKIPPED_PAST, outcome.instanceOutcomes.single().status)
        assertNull(outcome.instanceOutcomes.single().restoredItem)
        // 有实例被跳过仍是成功恢复的终态。
        assertTrue(outcome.isTerminal)
        assertEquals(RecoveryGroupStatus.RESTORED, outcome.status)
    }

    @Test fun `timed instance one millisecond before trigger is recoverable`() {
        val member = instanceMember(scheduledAt = now + 1L)
        val outcome = recoverWith(listOf(member))
        assertEquals(RecoveryInstanceStatus.RESTORED, outcome.instanceOutcomes.single().status)
        assertEquals(now + 1L, outcome.instanceOutcomes.single().restoredItem?.scheduledAt)
    }

    @Test fun `timed instance exactly at trigger is skipped`() {
        val member = instanceMember(scheduledAt = now)
        val outcome = recoverWith(listOf(member))
        assertEquals(RecoveryInstanceStatus.SKIPPED_PAST, outcome.instanceOutcomes.single().status)
    }

    @Test fun `timed instance in the future is recovered`() {
        val member = instanceMember(scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member))
        assertEquals(RecoveryInstanceStatus.RESTORED, outcome.instanceOutcomes.single().status)
    }

    // ---------- 到期边界 ----------

    @Test fun `before boundary is recoverable`() {
        val member = instanceMember(scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member), expiresAt = now + 1L)
        assertEquals(RecoveryGroupStatus.RESTORED, outcome.status)
        assertEquals(RecoveryTemplateStatus.RESTORED, outcome.templateStatus)
    }

    @Test fun `at boundary is not recoverable`() {
        val member = instanceMember(scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member), expiresAt = now)
        assertEquals(RecoveryTemplateStatus.EXPIRED, outcome.templateStatus)
        assertEquals(RecoveryGroupStatus.ACTIVE, outcome.status)
        assertNull(outcome.restoredTemplate)
        assertTrue(outcome.instanceOutcomes.isEmpty())
    }

    @Test fun `expired group past its deadline is not recoverable`() {
        val member = instanceMember(scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member), expiresAt = deletedAt + 1000L)
        assertEquals(RecoveryTemplateStatus.EXPIRED, outcome.templateStatus)
    }

    // ---------- class_day 课程资格 ----------

    @Test fun `class day future instance is skipped when the course no longer matches`() {
        val future = day.plusDays(1)
        val member = instanceMember(
            scheduledAt = at(future, LocalTime.of(10, 0)),
            requiresCourse = true,
            occurrenceDay = at(future, LocalTime.MIDNIGHT),
        )
        // 恢复当下该日没有任何课程：即使时间在未来也必须跳过。
        val outcome = recoverWith(listOf(member))
        assertEquals(RecoveryInstanceStatus.SKIPPED_COURSE, outcome.instanceOutcomes.single().status)
        assertTrue(outcome.isTerminal)
    }

    @Test fun `class day future instance is recovered when the course still matches`() {
        val future = day.plusDays(1)
        val member = instanceMember(
            scheduledAt = at(future, LocalTime.of(10, 0)),
            requiresCourse = true,
            occurrenceDay = at(future, LocalTime.MIDNIGHT),
        )
        val outcome = recoverWith(listOf(member), courses = listOf(course(future.dayOfWeek.value)))
        assertEquals(RecoveryInstanceStatus.RESTORED, outcome.instanceOutcomes.single().status)
    }

    @Test fun `course occurrence day is read as a local day timestamp not an epoch day`() {
        val future = day.plusDays(1)
        val occurrenceDay = at(future, LocalTime.MIDNIGHT)
        // 若把 repeatOccurrenceDay 误当 epochDay，这个时间戳会被解释成 1970 年附近的日期，课程判定必然失配。
        assertTrue(RepeatClosureRecovery.hasCourseOn(occurrenceDay, listOf(course(future.dayOfWeek.value)), zone))
        assertFalse(RepeatClosureRecovery.hasCourseOn(occurrenceDay, listOf(course(future.dayOfWeek.value + 1)), zone))
    }

    @Test fun `pending course is not treated as a matching course`() {
        val future = day.plusDays(1)
        val member = instanceMember(
            scheduledAt = at(future, LocalTime.of(10, 0)),
            requiresCourse = true,
            occurrenceDay = at(future, LocalTime.MIDNIGHT),
        )
        val pending = course(future.dayOfWeek.value).copy(needsConfirmation = true)
        val outcome = recoverWith(listOf(member), courses = listOf(pending))
        assertEquals(RecoveryInstanceStatus.SKIPPED_COURSE, outcome.instanceOutcomes.single().status)
    }

    // ---------- postState 冲突判定 ----------

    @Test fun `conflict when userNote changes after deletion`() {
        val member = instanceMember(scheduledAt = at(day, LocalTime.of(21, 0)))
        val edited = member.postState.copy(userNote = "恢复前我改了备注")
        val outcome = recoverWith(listOf(member), currentItems = snapshot(tplPost, edited))
        assertEquals(RecoveryTemplateStatus.CONFLICT, outcome.templateStatus)
        assertEquals(RecoveryGroupStatus.ACTIVE, outcome.status)
        assertNull(outcome.restoredTemplate)
        assertTrue(outcome.instanceOutcomes.isEmpty())
    }

    @Test fun `conflict when checklist changes after deletion`() {
        val member = instanceMember(scheduledAt = at(day, LocalTime.of(21, 0)))
        val edited = member.postState.copy(checklist = listOf(ChecklistEntry(1L, "新增检查项", true)))
        val outcome = recoverWith(listOf(member), currentItems = snapshot(tplPost, edited))
        assertEquals(RecoveryTemplateStatus.CONFLICT, outcome.templateStatus)
    }

    @Test fun `conflict when detail is rewritten to look like a miss`() {
        // 用 detail 文案反推 missed 是错误做法：把它改成另一条历史文案同样属状态不符，必须按 postState 判冲突。
        val member = instanceMember(scheduledAt = at(day, LocalTime.of(21, 0)))
        val rewritten = member.postState.copy(detail = "本次未处理")
        val outcome = recoverWith(listOf(member), currentItems = snapshot(tplPost, rewritten))
        assertEquals(RecoveryTemplateStatus.CONFLICT, outcome.templateStatus)
    }

    @Test fun `conflict when a member is missing from the current snapshot`() {
        val member = instanceMember(scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member), currentItems = listOf(tplPost))
        assertEquals(RecoveryTemplateStatus.CONFLICT, outcome.templateStatus)
    }

    @Test fun `untouched deletion matches postState and is not a conflict`() {
        val member = instanceMember(scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member))
        assertEquals(RecoveryTemplateStatus.RESTORED, outcome.templateStatus)
        assertEquals(RecoveryInstanceStatus.RESTORED, outcome.instanceOutcomes.single().status)
    }

    @Test fun `conflict when the template tombstone changed`() {
        val member = instanceMember(scheduledAt = at(day, LocalTime.of(21, 0)))
        val tampered = tplPost.copy(detail = "用户改过的墓碑")
        val outcome = recoverWith(listOf(member), currentItems = snapshot(tampered, member.postState))
        assertEquals(RecoveryTemplateStatus.CONFLICT, outcome.templateStatus)
    }

    // ---------- 组终态、幂等与暂停状态 ----------

    @Test fun `successful recovery with skipped instances is still terminal`() {
        val recoverable = instanceMember(scheduledAt = at(day, LocalTime.of(21, 0)))
        val past = instanceMember(
            id = 901L,
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

    @Test fun `recovery is idempotent once the group is restored`() {
        val member = instanceMember(scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member), groupRestored = true)
        assertEquals(RecoveryTemplateStatus.ALREADY_RESTORED, outcome.templateStatus)
        assertNull(outcome.restoredTemplate)
        assertTrue(outcome.instanceOutcomes.isEmpty())
    }

    @Test fun `paused before deletion stays paused after recovery`() {
        val pausedPre = templatePre(pausedBeforeDeletion = true)
        val member = instanceMember(scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(
            listOf(member),
            pre = pausedPre,
            currentItems = snapshot(templatePost(pausedPre), member.postState),
        )
        val restored = requireNotNull(outcome.restoredTemplate)
        assertTrue(restored.repeatPaused)
        assertEquals("重复模板", restored.kind)
        assertEquals("daily", restored.repeatFrequency)
        // 删除产生的墓碑字段必须被清掉。
        assertNull(restored.trashedAt)
        assertNull(restored.trashSnapshot)
    }

    @Test fun `running template is restored as running`() {
        val member = instanceMember(scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member))
        val restored = requireNotNull(outcome.restoredTemplate)
        assertFalse(restored.repeatPaused)
        assertNull(restored.trashedAt)
        assertNull(restored.trashSnapshot)
    }

    @Test fun `restore writes only the fields the deletion changed`() {
        val member = instanceMember(scheduledAt = at(day, LocalTime.of(21, 0)))
        val outcome = recoverWith(listOf(member))
        val restored = requireNotNull(outcome.instanceOutcomes.single().restoredItem)
        assertEquals("任务", restored.kind)
        assertEquals("09:00 · 30分钟", restored.detail)
        assertEquals(at(day, LocalTime.of(21, 0)), restored.scheduledAt)
        // 未受删除影响的字段保持删除后的现值。
        assertEquals(member.postState.userNote, restored.userNote)
        assertEquals(member.postState.priority, restored.priority)
        assertEquals(member.postState.durationMinutes, restored.durationMinutes)
        assertEquals(member.postState.repeatTemplateId, restored.repeatTemplateId)
        assertEquals(member.postState.repeatOccurrenceDay, restored.repeatOccurrenceDay)
    }

    @Test fun `retention window is thirty days`() {
        val base = 1_000_000L
        assertEquals(base + 30L * 24 * 60 * 60 * 1000, RepeatClosureRecovery.expiresAtFor(base))
        assertTrue(RepeatClosureRecovery.retentionLimitAt(base) > base)
        assertEquals(
            Long.MAX_VALUE - RepeatClosureRecovery.RETENTION_MS,
            RepeatClosureRecovery.retentionLimitAt(0L),
        )
        assertFalse(RepeatClosureRecovery.isRecoverable(expiresAt = now, now = now))
        assertTrue(RepeatClosureRecovery.isRecoverable(expiresAt = now + 1L, now = now))
    }
}
