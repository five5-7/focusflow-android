package com.sakata.focusflow

import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * 阶段 7.4 重复规则恢复组：编解码（A/B）、真实捕获（C）、删除计划（D）、恢复组合（E）。
 *
 * 测试断言的是**最终行为**，不是常量或实现细节：
 * 组往返后逐字段相同；严格解码拒绝各类坏负载且保留原始输入；
 * 捕获集合精确等于真实 `stop` 命中集合；恢复组合产出可一次写入的结果。
 *
 * **时区契约**：夹具固定用 `Asia/Shanghai` 构造时刻，而恢复计划要求
 * `zoneId == ZoneId.systemDefault()`，因此本类在**每个用例前**把系统默认时区设为上海，
 * 并在**每个用例后**（含失败路径）恢复到进入本类时记录的起始时区。
 * 这样无论测试 JVM 以 UTC 还是上海启动，固定夹具用例都不会被时区守卫误拒。
 */
class Stage7RepeatClosurePlanTest {

    companion object {
        /** 进入本类时的系统默认时区；由 [restoreStartingZone] 断言每例都已复位。 */
        @JvmStatic
        @BeforeClass
        fun captureStartingZone() {
            startingZone = TimeZone.getDefault()
            // 若能读到本次运行期望的起始时区（由测试 JVM 的系统属性传入），
            // 就地校验参数确实到达了**测试工作进程**，而不是只改了 Gradle 主进程。
            requestedStartingZone?.let { requested ->
                val actual = ZoneId.systemDefault().id
                if (!actual.equals(requested, ignoreCase = true)) {
                    // 名称可能不同（如 Asia/Shanghai 与 GMT+08:00），用偏移兜底比较。
                    val requestedOffset = ZoneId.of(requested).rules.getOffset(java.time.Instant.now())
                    val actualOffset = TimeZone.getDefault().getOffset(System.currentTimeMillis())
                    assertEquals(
                        "test JVM started in zone $actual but $requested was requested " +
                            "(offset expected ${requestedOffset.totalSeconds}s)",
                        requestedOffset.totalSeconds * 1000,
                        actualOffset,
                    )
                }
            }
        }

        @JvmStatic
        @AfterClass
        fun restoreStartingZone() {
            // 走到这里说明每个用例的 @After 都执行过；再断言一次整体已复位。
            assertEquals(startingZone, TimeZone.getDefault())
        }

        private lateinit var startingZone: TimeZone

        /** 由 `-Dfocusflow.test.startZone=UTC` 传入；未传时为 null。 */
        private val requestedStartingZone: String? = System.getProperty("focusflow.test.startZone")
    }

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val day: LocalDate = LocalDate.of(2026, 9, 30)
    private val now: Long = at(day, LocalTime.of(9, 0))
    private val templateId = 700L

    /** 固定夹具用例一律在上海时区下运行；失败路径同样会走 [restoreDefaultZone]。 */
    @Before
    fun useShanghaiAsSystemZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After
    fun restoreDefaultZone() {
        TimeZone.setDefault(startingZone)
        assertEquals("each case must restore the starting system zone", startingZone, TimeZone.getDefault())
    }

    private fun at(date: LocalDate, time: LocalTime): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    private fun midnight(date: LocalDate): Long = at(date, LocalTime.MIDNIGHT)

    // ---------- 夹具 ----------

    private fun template(
        id: Long = templateId,
        title: String = "晨读",
        frequency: String = "daily",
        paused: Boolean = false,
        minute: Int = 9 * 60,
    ) = Item(
        id = id,
        title = title,
        detail = "每天重复",
        kind = "重复模板",
        repeatFrequency = frequency,
        repeatStartDay = midnight(day),
        repeatMinute = minute,
        repeatPaused = paused,
    )

    private fun pendingInstance(
        id: Long,
        templateId: Long = this.templateId,
        scheduledAt: Long? = at(day, LocalTime.of(21, 0)),
        occurrenceDay: Long? = midnight(day),
        dayOnly: Boolean = false,
        title: String = "晨读",
    ) = Item(
        id = id,
        title = title,
        detail = "09:00 · 30分钟",
        kind = "任务",
        scheduledAt = scheduledAt,
        dayOnly = dayOnly,
        repeatTemplateId = templateId,
        repeatOccurrenceDay = occurrenceDay,
        repeatMinute = 9 * 60,
    )

    /** 真实删除并捕获。 */
    private fun captureReal(
        items: List<Item>,
        template: Item,
        groupId: String = "group-1",
        operationId: String = "op-1",
        deletedAt: Long = now - 60_000L,
    ): Pair<RepeatClosureCapture, RepeatResult> {
        val deleted = RepeatActions.deleteRule(items, template, at = deletedAt)
        return RepeatClosureCaptureRequest.capture(items, template, deleted, groupId, operationId) to deleted
    }

    private fun captureOrFail(
        items: List<Item>,
        template: Item,
        groupId: String = "group-1",
        operationId: String = "op-1",
        deletedAt: Long = now - 60_000L,
    ): RepeatClosureRecord {
        val (result, _) = captureReal(items, template, groupId, operationId, deletedAt)
        return (result as RepeatClosureCapture.Ok).record
    }

    private fun roundTrip(record: RepeatClosureRecord): RepeatClosureRecord {
        val encoded = RepeatClosureCodec.encode(record)
        val decoded = RepeatClosureCodec.decode(encoded)
        assertTrue("expected a decodable payload, got $decoded", decoded is RepeatClosureDecode.Ok)
        return (decoded as RepeatClosureDecode.Ok).record
    }

    private fun expectInvalid(raw: String): RepeatClosureDecode.Invalid {
        val decoded = RepeatClosureCodec.decode(raw)
        assertTrue("expected Invalid for: ${raw.take(80)}", decoded is RepeatClosureDecode.Invalid)
        return decoded as RepeatClosureDecode.Invalid
    }

    private fun validJson(record: RepeatClosureRecord): String = RepeatClosureCodec.encode(record)

    /** 临时把系统默认时区设为 [zone]，结束时必定恢复。 */
    private fun <T> withDefaultZone(zone: ZoneId, block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        return try {
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    /** 与真实 deleteRule 逐字段一致的删除输出，便于在测试里「夹带」修改。 */
    private fun realDeletion(items: List<Item>, template: Item, at: Long): RepeatResult =
        RepeatActions.deleteRule(items, template, at = at)

    /**
     * 断言恢复计划被拒绝，且**拒绝原因**符合预期。
     *
     * 只断言 `Rejected` 会让时区守卫、期限守卫等「错误原因」掩盖真正的失败点，
     * 因此这里同时核对 `reason` 关键字。
     */
    private fun assertRecoveryRejected(expectedReasonPart: String, result: RecoveryPlanResult): RecoveryPlanResult.Rejected {
        assertTrue("expected Rejected($expectedReasonPart), got $result", result is RecoveryPlanResult.Rejected)
        val rejected = result as RecoveryPlanResult.Rejected
        assertTrue(
            "rejected for the wrong reason: expected to contain \"$expectedReasonPart\", got \"${rejected.reason}\"",
            rejected.reason.contains(expectedReasonPart),
        )
        return rejected
    }

    /** 断言删除计划被拒绝，且拒绝原因符合预期。 */
    private fun assertDeleteRejected(expectedReasonPart: String, result: DeletePlanResult): DeletePlanResult.Rejected {
        assertTrue("expected Rejected($expectedReasonPart), got $result", result is DeletePlanResult.Rejected)
        val rejected = result as DeletePlanResult.Rejected
        assertTrue(
            "rejected for the wrong reason: expected to contain \"$expectedReasonPart\", got \"${rejected.reason}\"",
            rejected.reason.contains(expectedReasonPart),
        )
        return rejected
    }

    /** 断言捕获被拒绝，且拒绝原因符合预期。 */
    private fun assertCaptureRejected(expectedReasonPart: String, result: RepeatClosureCapture): RepeatClosureCapture.Rejected {
        assertTrue("expected Rejected($expectedReasonPart), got $result", result is RepeatClosureCapture.Rejected)
        val rejected = result as RepeatClosureCapture.Rejected
        assertTrue(
            "rejected for the wrong reason: expected to contain \"$expectedReasonPart\", got \"${rejected.reason}\"",
            rejected.reason.contains(expectedReasonPart),
        )
        return rejected
    }

    // ================= A. 完整组往返 =================

    @Test fun `group round trip preserves every item field explicitly`() {
        val rich = pendingInstance(id = 901L).copy(
            done = false,
            completionLevel = "完整版",
            completedAt = null,
            durationMinutes = 45,
            windowStartAt = now + 600_000L,
            windowEndAt = now + 900_000L,
            rescheduleCount = 3,
            lastRescheduledAt = now - 5_000L,
            recoverySourceScheduledAt = now - 9_000L,
            priority = "high",
            captureRoute = "progress",
            sourceDetail = "导图上的原始说明",
            userNote = "先看第三章",
            nextAction = "先找回课程",
            parentCaptureId = null,
            dueAt = now + 86_400_000L,
            checklist = listOf(ChecklistEntry(11L, "读一节", true), ChecklistEntry(12L, "做笔记", false)),
            planBucket = "later",
            planFocus = true,
            repeatPaused = true,
            trashedAt = null,
            trashSnapshot = null,
        )
        // 用真实删除产出 postState，确保往返的是真实形状。
        val tpl = template()
        val preItems = listOf(tpl, rich)
        val record = captureOrFail(preItems, tpl)
        val decoded = roundTrip(record)

        assertEquals(record, decoded)
        val member = decoded.instances.single()
        assertEquals(rich, member.preState)
        assertEquals("先看第三章", member.preState.userNote)
        assertEquals(2, member.preState.checklist.size)
        assertEquals("later", member.preState.planBucket)
        assertTrue(member.preState.planFocus)
        assertEquals(45, member.preState.durationMinutes)
        assertEquals("progress", member.preState.captureRoute)
        assertNull(member.preState.parentCaptureId)
    }

    @Test fun `group round trip keeps nulls distinct from empty values`() {
        val tpl = template()
        val instance = pendingInstance(id = 901L).copy(userNote = null, sourceDetail = "", trashSnapshot = null)
        val record = captureOrFail(listOf(tpl, instance), tpl)
        val decoded = roundTrip(record)
        assertNull(decoded.instances.single().preState.userNote)
        assertEquals("", decoded.instances.single().preState.sourceDetail)
        assertNull(decoded.instances.single().preState.trashSnapshot)
        assertNull(decoded.template.preState.repeatTemplateId)
    }

    @Test fun `handling reason and active status round trip`() {
        val tpl = template()
        val record = captureOrFail(listOf(tpl, pendingInstance(id = 901L)), tpl)
        assertEquals(RecoveryGroupStatus.ACTIVE, record.status)
        assertNull(record.restoration)
        val decoded = roundTrip(record)
        assertEquals(RecoveryGroupStatus.ACTIVE, decoded.status)
        assertNull(decoded.restoration)
        assertEquals("规则停止，取消本次", decoded.instances.single().handlingReason)
    }

    @Test fun `restored status with per instance results round trips`() {
        val tpl = template()
        val record = captureOrFail(listOf(tpl, pendingInstance(id = 901L), pendingInstance(id = 902L)), tpl)
        val restored = record.copy(
            status = RecoveryGroupStatus.RESTORED,
            restoration = RepeatClosureRestoration(
                restoredAt = now,
                instances = listOf(
                    RepeatClosureRestoredInstance(901L, RepeatClosureInstanceStatus.RESTORED),
                    RepeatClosureRestoredInstance(902L, RepeatClosureInstanceStatus.SKIPPED_PAST),
                ),
            ),
        )
        val decoded = roundTrip(restored)
        assertEquals(RecoveryGroupStatus.RESTORED, decoded.status)
        val restoration = requireNotNull(decoded.restoration)
        assertEquals(now, restoration.restoredAt)
        assertEquals(
            listOf(RepeatClosureInstanceStatus.RESTORED, RepeatClosureInstanceStatus.SKIPPED_PAST),
            restoration.instances.map { it.status },
        )
    }

    @Test fun `zero instance group round trips`() {
        val tpl = template()
        val record = captureOrFail(listOf(tpl), tpl)
        assertTrue(record.instances.isEmpty())
        val decoded = roundTrip(record)
        assertTrue(decoded.instances.isEmpty())
        assertEquals(record, decoded)
    }

    @Test fun `groupId and operationId stay stable through capture and encoding`() {
        val tpl = template()
        val record = captureOrFail(listOf(tpl, pendingInstance(id = 901L)), tpl, "fixed-group", "fixed-op")
        assertEquals("fixed-group", record.groupId)
        assertEquals("fixed-op", record.operationId)
        val decoded = roundTrip(record)
        assertEquals("fixed-group", decoded.groupId)
        assertEquals("fixed-op", decoded.operationId)
    }

    // ================= B. 严格解码 =================

    @Test fun `decode rejects an unknown version`() {
        val json = validJson(captureOrFail(listOf(template(), pendingInstance(901L)), template()))
        val invalid = expectInvalid(json.replace("\"version\":1", "\"version\":2"))
        // 失败时必须保留调用方**原始输入**（这里是篡改后的那份文本），不解释、不部分解码。
        assertEquals(json.replace("\"version\":1", "\"version\":2"), invalid.raw)
    }

    @Test fun `decode rejects an unknown kind`() {
        val json = validJson(captureOrFail(listOf(template(), pendingInstance(901L)), template()))
        expectInvalid(json.replace("\"kind\":\"repeat_rule_closure\"", "\"kind\":\"plan_binding_closure\""))
    }

    @Test fun `decode rejects a missing required key`() {
        val json = validJson(captureOrFail(listOf(template(), pendingInstance(901L)), template()))
        expectInvalid(json.replace("\"operationId\":\"op-1\",", ""))
    }

    @Test fun `decode rejects a missing nullable key instead of defaulting it`() {
        val json = validJson(captureOrFail(listOf(template(), pendingInstance(901L)), template()))
        expectInvalid(json.replace("\"userNote\":null,", ""))
    }

    @Test fun `decode rejects a non integral number`() {
        val json = validJson(captureOrFail(listOf(template(), pendingInstance(901L)), template()))
        val spliced = json.replaceFirst(Regex("\"durationMinutes\":\\d+"), "\"durationMinutes\":45.5")
        assertNotEquals(json, spliced)
        expectInvalid(spliced)
    }

    @Test fun `decode rejects an integer out of long range`() {
        val json = validJson(captureOrFail(listOf(template(), pendingInstance(901L)), template()))
        val spliced = json.replaceFirst(Regex("\"scheduledAt\":\\d+"), "\"scheduledAt\":99999999999999999999")
        assertNotEquals(json, spliced)
        expectInvalid(spliced)
    }

    @Test fun `decode rejects an integer written as a string`() {
        val json = validJson(captureOrFail(listOf(template(), pendingInstance(901L)), template()))
        val spliced = json.replaceFirst(Regex("\"durationMinutes\":\\d+"), "\"durationMinutes\":\"45\"")
        expectInvalid(spliced)
    }

    @Test fun `decode rejects a boolean written as a string`() {
        val json = validJson(captureOrFail(listOf(template(), pendingInstance(901L)), template()))
        expectInvalid(json.replaceFirst("\"done\":false", "\"done\":\"false\""))
    }

    @Test fun `decode rejects truncated json`() {
        val json = validJson(captureOrFail(listOf(template(), pendingInstance(901L)), template()))
        expectInvalid(json.substring(0, json.length / 2))
    }

    @Test fun `decode rejects trailing content`() {
        val json = validJson(captureOrFail(listOf(template(), pendingInstance(901L)), template()))
        expectInvalid("$json{}")
        expectInvalid("$json trailing")
    }

    @Test fun `decode rejects unknown keys`() {
        val json = validJson(captureOrFail(listOf(template(), pendingInstance(901L)), template()))
        expectInvalid(json.replaceFirst("\"version\":1", "\"version\":1,\"surprise\":1"))
    }

    @Test fun `decode rejects a duplicated member identity`() {
        val tpl = template()
        val record = captureOrFail(listOf(tpl, pendingInstance(id = 901L)), tpl)
        val duplicated = record.copy(instances = record.instances + record.instances)
        expectInvalid(validJson(duplicated))
    }

    @Test fun `decode rejects a member that collides with the template id`() {
        val tpl = template()
        val record = captureOrFail(listOf(tpl, pendingInstance(id = 901L)), tpl)
        val colliding = record.copy(instances = record.instances.map { it.copy(itemId = tpl.id) })
        expectInvalid(validJson(colliding))
    }

    @Test fun `decode rejects an illegal expiry`() {
        val tpl = template()
        val record = captureOrFail(listOf(tpl, pendingInstance(id = 901L)), tpl)
        expectInvalid(validJson(record.copy(expiresAt = record.expiresAt + 1)))
        expectInvalid(validJson(record.copy(deletedAt = 0L)))
    }

    @Test fun `decode rejects status and restoration disagreement`() {
        val tpl = template()
        val record = captureOrFail(listOf(tpl, pendingInstance(id = 901L)), tpl)

        // ACTIVE 不允许带恢复结果。
        expectInvalid(
            validJson(
                record.copy(
                    status = RecoveryGroupStatus.ACTIVE,
                    restoration = RepeatClosureRestoration(
                        now,
                        listOf(RepeatClosureRestoredInstance(901L, RepeatClosureInstanceStatus.RESTORED)),
                    ),
                ),
            ),
        )
        // RESTORED 必须带恢复结果。
        expectInvalid(validJson(record.copy(status = RecoveryGroupStatus.RESTORED, restoration = null)))
        // 结果必须覆盖每个成员且不重复。
        expectInvalid(
            validJson(
                record.copy(
                    status = RecoveryGroupStatus.RESTORED,
                    restoration = RepeatClosureRestoration(now, emptyList()),
                ),
            ),
        )
        expectInvalid(
            validJson(
                record.copy(
                    status = RecoveryGroupStatus.RESTORED,
                    restoration = RepeatClosureRestoration(
                        now,
                        listOf(
                            RepeatClosureRestoredInstance(901L, RepeatClosureInstanceStatus.RESTORED),
                            RepeatClosureRestoredInstance(901L, RepeatClosureInstanceStatus.RESTORED),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test fun `decode rejects a malformed payload without losing the original input`() {
        val invalid = expectInvalid("{\"version\":1,")
        assertEquals("{\"version\":1,", invalid.raw)
        assertTrue(invalid.reason.isNotBlank())
    }

    @Test fun `decode does not normalise identity or fields the way ItemsCodec does`() {
        // ItemsCodec 会把未知 captureRoute 落回 inbox、丢弃 class_day、并给重复 ID 换号；
        // 本 codec 必须原样保留，因为恢复组要还原的是删除前的**完整**状态。
        val tpl = template(frequency = "class_day")
        val odd = pendingInstance(id = 901L).copy(captureRoute = "future-value")
        val record = captureOrFail(listOf(tpl, odd), tpl)
        val decoded = roundTrip(record)
        assertEquals("future-value", decoded.instances.single().preState.captureRoute)
        assertEquals("class_day", decoded.template.preState.repeatFrequency)
        assertEquals(901L, decoded.instances.single().preState.id)

        // 对照：ItemsCodec 确实会改动同样的字段，说明这不是空断言。
        val viaItemsCodec = ItemsCodec.decode(ItemsCodec.encode(listOf(odd))).items.single()
        assertEquals(CaptureRoute.INBOX.storageKey, viaItemsCodec.captureRoute)
        assertNotEquals("future-value", viaItemsCodec.captureRoute)
    }

    // ================= C. 真实捕获 =================

    @Test fun `capture picks exactly the instances stop touched`() {
        val tpl = template()
        val items = listOf(
            tpl,
            pendingInstance(id = 901L),
            pendingInstance(id = 902L, scheduledAt = midnight(day), dayOnly = true),
            pendingInstance(id = 903L).copy(done = true),                      // 已完成：不纳入
            pendingInstance(id = 904L).copy(kind = "重复历史", scheduledAt = null), // 既有历史：不纳入
            pendingInstance(id = 905L, templateId = 999L),                     // 其它模板：不纳入
            Item(id = 906L, title = "无关任务", detail = "", kind = "任务"),      // 无关任务：不纳入
        )
        val (result, deleted) = captureReal(items, tpl)
        val record = (result as RepeatClosureCapture.Ok).record

        assertEquals(setOf(901L, 902L), record.instances.map { it.itemId }.toSet())
        // 精确等于真实 stop 的产物：postState 必须与删除输出逐个相同。
        record.instances.forEach { member ->
            assertEquals(deleted.items.single { it.id == member.itemId }, member.postState)
            assertEquals(member.preState, items.single { it.id == member.itemId })
        }
        assertFalse(record.instances.any { it.itemId == 903L })
        assertFalse(record.instances.any { it.itemId == 904L })
        assertFalse(record.instances.any { it.itemId == 905L })
    }

    @Test fun `capture rejects a stale request instead of choosing a same titled template`() {
        val live = template(id = 700L, title = "晨读")
        val other = template(id = 701L, title = "晨读") // 同名，不同 ID
        val items = listOf(live, other, pendingInstance(id = 901L, templateId = 700L))

        // 陈旧请求：模板已不在当前快照。
        val stale = live.copy(detail = "被改过的说明")
        val staleResult = RepeatActions.deleteRule(items, stale, at = now)
        val rejected = RepeatClosureCaptureRequest.capture(items, stale, staleResult, "g", "o")
        assertCaptureRejected("stale", rejected)

        // 真正删除的是 live，不会因为 other 同名就被当成一组。
        val record = captureOrFail(items, live)
        assertEquals(700L, record.template.templateId)
    }

    @Test fun `capture rejects duplicate ids in the current snapshot`() {
        val tpl = template()
        val instance = pendingInstance(id = 901L)
        val items = listOf(tpl, instance, instance)
        val deleted = RepeatActions.deleteRule(items, tpl, at = now - 60_000L)
        val result = RepeatClosureCaptureRequest.capture(items, tpl, deleted, "g", "o")
        assertCaptureRejected("duplicate item ids", result)
    }

    @Test fun `capture rejects when the deletion produced no tombstone`() {
        val tpl = template()
        val notDeleted = RepeatResult(listOf(tpl, pendingInstance(id = 901L)), emptyList())
        val result = RepeatClosureCaptureRequest.capture(
            listOf(tpl, pendingInstance(id = 901L)), tpl, notDeleted, "g", "o",
        )
        assertCaptureRejected("tombstone", result)
    }

    @Test fun `capture takes the deletion time from the real tombstone`() {
        val tpl = template()
        val deletedAt = now - 123_456L
        val record = captureOrFail(listOf(tpl, pendingInstance(id = 901L)), tpl, deletedAt = deletedAt)
        assertEquals(deletedAt, record.deletedAt)
        assertEquals(
            (RepeatClosureRecovery.expiresAtFor(deletedAt) as RecoveryExpiry.Ok).expiresAt,
            record.expiresAt,
        )
    }

    @Test fun `capture keeps the note materialisation produced by the real deletion`() {
        val tpl = template()
        val instance = pendingInstance(id = 901L).copy(userNote = null, sourceDetail = "导图原始说明")
        assertNull(instance.userNote)
        val record = captureOrFail(listOf(tpl, instance), tpl)
        val member = record.instances.single()
        assertNull(member.preState.userNote)
        assertEquals("导图原始说明", member.postState.userNote)
    }

    // ================= D. 删除计划 =================

    @Test fun `delete plan returns tasks and group in one payload`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val plan = RepeatClosureDeletePlan.plan(items, tpl, "group-1", "op-1", now = now - 60_000L)
        assertTrue(plan is DeletePlanResult.Ok)
        val ok = plan as DeletePlanResult.Ok

        assertEquals("回收站", ok.itemsToWrite.single { it.id == tpl.id }.kind)
        assertEquals("重复历史", ok.itemsToWrite.single { it.id == 901L }.kind)
        assertEquals(RecoveryGroupStatus.ACTIVE, ok.groupToWrite.status)
        assertEquals(listOf(901L), ok.groupToWrite.instances.map { it.itemId })
        assertTrue(ok.events.any { it.type == TaskEventType.TASK_DELETED })
    }

    @Test fun `delete plan refuses a repeated operation id`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val first = RepeatClosureDeletePlan.plan(items, tpl, "group-1", "op-1", now = now - 60_000L)
        val group = (first as DeletePlanResult.Ok).groupToWrite

        val second = RepeatClosureDeletePlan.plan(
            items, tpl, "group-2", "op-1", now = now - 60_000L, existingGroups = listOf(group),
        )
        assertDeleteRejected("already captured", second)

        // 换了 groupId 也不算新操作：operationId 已存在。
        val third = RepeatClosureDeletePlan.plan(
            items, tpl, "group-3", "op-1", now = now - 60_000L, existingGroups = listOf(group),
        )
        assertDeleteRejected("already captured", third)

        // 新 operationId 但 groupId 冲突，同样拒绝。
        val fourth = RepeatClosureDeletePlan.plan(
            items, tpl, "group-1", "op-2", now = now - 60_000L, existingGroups = listOf(group),
        )
        assertDeleteRejected("groupId", fourth)
    }

    @Test fun `delete plan does not treat other templates refresh as a delete member`() {
        val tpl = template(id = 700L)
        val other = template(id = 710L, title = "夜读")
        val items = listOf(tpl, other, pendingInstance(id = 901L, templateId = 700L))
        val plan = RepeatClosureDeletePlan.plan(items, tpl, "group-1", "op-1", now = now - 60_000L)
        assertTrue(plan is DeletePlanResult.Ok)
        val ok = plan as DeletePlanResult.Ok

        // 无关模板照常生成实例，但绝不能被算成删除成员。
        assertEquals(listOf(901L), ok.groupToWrite.instances.map { it.itemId })
        assertTrue(ok.itemsToWrite.any { it.repeatTemplateId == 710L })
        assertFalse(ok.groupToWrite.instances.any { it.itemId in ok.itemsToWrite.filter { it.repeatTemplateId == 710L }.map { i -> i.id } })
    }

    @Test fun `delete plan rejects a stale template`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val stale = tpl.copy(title = "改过标题")
        val plan = RepeatClosureDeletePlan.plan(items, stale, "group-1", "op-1", now = now)
        assertDeleteRejected("stale", plan)
    }

    // ================= E. 恢复组合 =================

    /** 真实删除 → 编码/解码 → 恢复 → 真实 refresh 的完整链路。 */
    private fun deleteThenRecover(
        items: List<Item>,
        tpl: Item,
        courses: List<Course> = emptyList(),
        recoverAt: Long = now,
    ): Triple<RecoveryPlanResult, RepeatClosureRecord, List<Item>> {
        val record = captureOrFail(items, tpl)
        val delivered = roundTrip(record)
        val plan = RepeatClosureRecoveryPlan.plan(delivered, itemStateAfterCapture(items, tpl, record), recoverAt, zone, courses)
        return Triple(plan, delivered, items)
    }

    /** 删除后应当传给恢复的当前条目快照。 */
    private fun itemStateAfterCapture(
        items: List<Item>,
        tpl: Item,
        record: RepeatClosureRecord,
    ): List<Item> {
        val deleted = RepeatActions.deleteRule(items, tpl, at = record.deletedAt)
        return deleted.items
    }

    @Test fun `recovery plan restores and refreshes in one payload`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L, scheduledAt = at(day.plusDays(1), LocalTime.of(21, 0))))
        val (plan, delivered, _) = deleteThenRecover(items, tpl)

        assertTrue("expected ok, got $plan", plan is RecoveryPlanResult.Ok)
        val ok = plan as RecoveryPlanResult.Ok
        assertEquals(RecoveryGroupStatus.RESTORED, ok.groupToWrite.status)
        assertEquals(recordIds(delivered), ok.restoration.instances.map { it.itemId }.toSet())
        // 还原后的实例回到「任务」。
        assertEquals("任务", ok.itemsToWrite.single { it.id == 901L }.kind)
        assertNotEquals("回收站", ok.itemsToWrite.single { it.id == tpl.id }.kind)
        // 组与任务一起出现在同一笔拟提交数据里。
        assertTrue(ok.itemsToWrite.any { it.id == tpl.id })
        assertEquals(ok.groupToWrite, delivered.copy(status = RecoveryGroupStatus.RESTORED, restoration = ok.restoration))
    }

    private fun recordIds(record: RepeatClosureRecord): Set<Long> = record.instances.mapTo(mutableSetOf()) { it.itemId }

    @Test fun `recovery plan keeps a paused template paused and generates nothing`() {
        val tpl = template(paused = true)
        val items = listOf(tpl, pendingInstance(id = 901L))
        val (plan, _, _) = deleteThenRecover(items, tpl)
        assertTrue(plan is RecoveryPlanResult.Ok)
        val ok = plan as RecoveryPlanResult.Ok
        val restoredTemplate = ok.itemsToWrite.single { it.id == tpl.id }
        assertEquals("重复模板", restoredTemplate.kind)
        assertTrue(restoredTemplate.repeatPaused)
        // 暂停模板不生成实例：901 已还原为任务，且没有新的重复实例。
        assertTrue(ok.events.none { it.type == TaskEventType.TASK_CREATED })
        assertEquals(1, ok.itemsToWrite.count { it.repeatTemplateId == tpl.id })
    }

    @Test fun `recovery plan keeps an instance with no scheduledAt in history`() {
        // stop 的筛选不要求 scheduledAt 非空，这种删除前状态真实存在。
        val tpl = template()
        val noTime = pendingInstance(id = 901L, scheduledAt = null)
        val items = listOf(tpl, noTime)
        val (plan, _, _) = deleteThenRecover(items, tpl)
        assertTrue(plan is RecoveryPlanResult.Ok)
        val ok = plan as RecoveryPlanResult.Ok
        val settled = ok.itemsToWrite.single { it.id == 901L }
        assertEquals("重复历史", settled.kind)
        assertNull(settled.scheduledAt)
        assertEquals(
            RepeatClosureInstanceStatus.SKIPPED_PAST,
            ok.restoration.instances.single { it.itemId == 901L }.status,
        )
    }

    @Test fun `recovery plan skips a class day instance when the course is gone`() {
        val tpl = template(frequency = "class_day")
        val future = day.plusDays(1)
        val items = listOf(
            tpl,
            pendingInstance(
                id = 901L,
                scheduledAt = at(future, LocalTime.of(10, 0)),
                occurrenceDay = midnight(future),
            ),
        )
        val (plan, _, _) = deleteThenRecover(items, tpl, courses = emptyList())
        assertTrue(plan is RecoveryPlanResult.Ok)
        val ok = plan as RecoveryPlanResult.Ok
        assertEquals(
            RepeatClosureInstanceStatus.SKIPPED_COURSE,
            ok.restoration.instances.single { it.itemId == 901L }.status,
        )
        assertEquals("重复历史", ok.itemsToWrite.single { it.id == 901L }.kind)
        assertEquals(RecoveryGroupStatus.RESTORED, ok.groupToWrite.status)
    }

    @Test fun `recovery plan restores a dayOnly instance scheduled today`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L, scheduledAt = midnight(day), dayOnly = true))
        val (plan, _, _) = deleteThenRecover(items, tpl)
        assertTrue(plan is RecoveryPlanResult.Ok)
        val ok = plan as RecoveryPlanResult.Ok
        val restored = ok.itemsToWrite.single { it.id == 901L }
        assertEquals("任务", restored.kind)
        assertEquals(midnight(day), restored.scheduledAt)
        assertTrue(restored.dayOnly)
    }

    @Test fun `recovery plan does not generate a second instance for the same occurrence day`() {
        val tpl = template()
        val sameDay = pendingInstance(id = 901L, scheduledAt = at(day, LocalTime.of(21, 0)), occurrenceDay = midnight(day))
        val items = listOf(tpl, sameDay)
        val (plan, _, _) = deleteThenRecover(items, tpl)
        assertTrue(plan is RecoveryPlanResult.Ok)
        val ok = plan as RecoveryPlanResult.Ok
        assertEquals(1, ok.itemsToWrite.count { it.repeatTemplateId == tpl.id && it.kind == "任务" })
    }

    @Test fun `recovery plan fails whole group on a member conflict and leaves input untouched`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val record = captureOrFail(items, tpl)
        val afterDelete = itemStateAfterCapture(items, tpl, record)

        // 成员被单独改动 → 整组冲突。
        val tampered = afterDelete.map { if (it.id == 901L) it.copy(userNote = "我改过") else it }
        val originalInput = tampered.toList()
        val plan = RepeatClosureRecoveryPlan.plan(roundTrip(record), tampered, now, zone, emptyList())
        // 必须因**成员冲突**被拒，而不是被时区/期限等错误守卫拦下。
        assertRecoveryRejected("CONFLICT", plan)
        assertEquals(originalInput, tampered)
    }

    @Test fun `recovery plan refuses an already restored group`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val record = captureOrFail(items, tpl)
        val afterDelete = itemStateAfterCapture(items, tpl, record)

        val first = RepeatClosureRecoveryPlan.plan(record, afterDelete, now, zone, emptyList())
        assertTrue(first is RecoveryPlanResult.Ok)
        val restoredRecord = (first as RecoveryPlanResult.Ok).groupToWrite
        val restoredItems = first.itemsToWrite

        val second = RepeatClosureRecoveryPlan.plan(restoredRecord, restoredItems, now, zone, emptyList())
        assertRecoveryRejected("already RESTORED", second)
        // 也通过既有组记录识别：已 RESTORED 的组不再恢复。
        val third = RepeatClosureRecoveryPlan.plan(
            record.copy(status = RecoveryGroupStatus.ACTIVE), restoredItems, now, zone,
            emptyList(), existingGroups = listOf(restoredRecord),
        )
        assertRecoveryRejected("already restored", third)
    }

    @Test fun `recovery plan with mixed restored and skipped instances is still terminal`() {
        val tpl = template()
        val items = listOf(
            tpl,
            // 未来定时实例 → 恢复
            pendingInstance(id = 901L, scheduledAt = at(day.plusDays(1), LocalTime.of(21, 0)), occurrenceDay = midnight(day.plusDays(1))),
            // 过去日期型实例 → 跳过（日期已过）
            pendingInstance(id = 902L, scheduledAt = midnight(day.minusDays(2)), dayOnly = true, occurrenceDay = midnight(day.minusDays(2))),
            // 空 scheduledAt → 跳过（时间不可判定）
            pendingInstance(id = 903L, scheduledAt = null),
        )
        val (plan, _, _) = deleteThenRecover(items, tpl)
        assertTrue("expected ok, got $plan", plan is RecoveryPlanResult.Ok)
        val ok = plan as RecoveryPlanResult.Ok

        assertEquals(RecoveryGroupStatus.RESTORED, ok.groupToWrite.status)
        val byId = ok.restoration.instances.associateBy { it.itemId }
        assertEquals(RepeatClosureInstanceStatus.RESTORED, byId.getValue(901L).status)
        assertEquals(RepeatClosureInstanceStatus.SKIPPED_PAST, byId.getValue(902L).status)
        assertEquals(RepeatClosureInstanceStatus.SKIPPED_PAST, byId.getValue(903L).status)
        // 恢复的回到任务，跳过的保持历史。
        assertEquals("任务", ok.itemsToWrite.single { it.id == 901L }.kind)
        assertEquals("重复历史", ok.itemsToWrite.single { it.id == 902L }.kind)
        assertEquals("重复历史", ok.itemsToWrite.single { it.id == 903L }.kind)
    }

    @Test fun `recovery plan refuses an expired group`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val record = captureOrFail(items, tpl)
        // 删除发生在很久以前：deletedAt/墓碑/expiresAt 三者自洽，且 expiresAt 已早于 now。
        val pastDeletedAt = now - 60L * 24 * 60 * 60 * 1000
        val pastExpiry = (RepeatClosureRecovery.expiresAtFor(pastDeletedAt) as RecoveryExpiry.Ok).expiresAt
        assertTrue("fixture must really be expired", pastExpiry < now)
        val expiredTombstone = record.template.postState.copy(trashedAt = pastDeletedAt)
        val expired = record.copy(
            deletedAt = pastDeletedAt,
            expiresAt = pastExpiry,
            template = record.template.copy(postState = expiredTombstone),
        )
        val afterDelete = itemStateAfterCapture(items, tpl, record)
            .map { if (it.id == tpl.id) expiredTombstone else it }

        val plan = RepeatClosureRecoveryPlan.plan(expired, afterDelete, now, zone, emptyList())
        // 必须因**到期**被拒（EXPIRED），而不是被结构或时区守卫拦下。
        assertRecoveryRejected("EXPIRED", plan)
    }

    @Test fun `recovery plan refuses a record whose expiry disagrees with its deletion time`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val record = captureOrFail(items, tpl)
        val afterDelete = itemStateAfterCapture(items, tpl, record)
        val inconsistent = record.copy(expiresAt = record.expiresAt + 1L)
        val plan = RepeatClosureRecoveryPlan.plan(inconsistent, afterDelete, now, zone, emptyList())
        assertRecoveryRejected("expiresAt disagrees", plan)
    }

    // ================= C2. 捕获完整性：外部删除输出必须能被真实重跑复现 =================

    @Test fun `capture rejects output carrying an unrelated modification`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val real = realDeletion(items, tpl, now - 60_000L)
        val tampered = real.copy(
            items = real.items.map { if (it.id == 901L) it.copy(userNote = "夹带的修改") else it },
        )
        val result = RepeatClosureCaptureRequest.capture(items, tpl, tampered, "g", "o")
        assertCaptureRejected("disagrees with a real deleteRule result", result)
    }

    @Test fun `capture rejects output that drops an item`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L), Item(id = 950L, title = "无关任务", detail = "", kind = "任务"))
        val real = realDeletion(items, tpl, now - 60_000L)
        val dropped = real.copy(items = real.items.filterNot { it.id == 950L })
        val result = RepeatClosureCaptureRequest.capture(items, tpl, dropped, "g", "o")
        assertCaptureRejected("does not match a real deleteRule result", result)
    }

    @Test fun `capture rejects output with an extra item`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val real = realDeletion(items, tpl, now - 60_000L)
        val extra = real.copy(
            items = real.items + Item(id = 960L, title = "凭空多出来的", detail = "", kind = "任务"),
        )
        val result = RepeatClosureCaptureRequest.capture(items, tpl, extra, "g", "o")
        assertCaptureRejected("does not match a real deleteRule result", result)
    }

    @Test fun `capture rejects output whose order was rearranged`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L), Item(id = 950L, title = "无关任务", detail = "", kind = "任务"))
        val real = realDeletion(items, tpl, now - 60_000L)
        val reversed = real.copy(items = real.items.reversed())
        val result = RepeatClosureCaptureRequest.capture(items, tpl, reversed, "g", "o")
        assertCaptureRejected("disagrees with a real deleteRule result", result)
    }

    @Test fun `capture rejects output with duplicate ids`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val real = realDeletion(items, tpl, now - 60_000L)
        val doubled = real.copy(items = real.items + real.items.first { it.id == 901L })
        val result = RepeatClosureCaptureRequest.capture(items, tpl, doubled, "g", "o")
        assertCaptureRejected("duplicate item ids", result)
    }

    @Test fun `capture rejects a forged template tombstone`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val real = realDeletion(items, tpl, now - 60_000L)
        val realTombstone = real.items.first { it.id == tpl.id }
        // 保持删除时刻不变，只把墓碑快照换成别的内容：真实重跑不会复现它。
        val forgedTombstone = realTombstone.copy(trashSnapshot = ItemsCodec.encode(listOf(tpl.copy(title = "伪造的原始模板"))))
        assertNotEquals(realTombstone.trashSnapshot, forgedTombstone.trashSnapshot)
        val forgedItems = real.items.map { if (it.id == tpl.id) forgedTombstone else it }

        val result = RepeatClosureCaptureRequest.capture(items, tpl, real.copy(items = forgedItems), "g", "o")
        assertCaptureRejected("disagrees with a real deleteRule result", result)
    }

    @Test fun `capture rejects a self inconsistent tombstone timestamp`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val real = realDeletion(items, tpl, now - 60_000L)
        // 墓碑没有删除时刻：无法证明这是一次真实删除。
        val noTime = real.items.map {
            if (it.id == tpl.id) it.copy(trashedAt = null) else it
        }
        val result = RepeatClosureCaptureRequest.capture(items, tpl, real.copy(items = noTime), "g", "o")
        assertCaptureRejected("no deletion time", result)
    }

    @Test fun `capture still accepts a real deletion output and ignores event ids`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L), Item(id = 950L, title = "无关任务", detail = "", kind = "任务"))
        val real = realDeletion(items, tpl, now - 60_000L)
        // 事件 ID 是随机生成的：即便完全换掉事件，也不影响捕获结论。
        val withOtherEvents = real.copy(events = real.events.map { it.copy(id = it.id + 777L) })
        val result = RepeatClosureCaptureRequest.capture(items, tpl, withOtherEvents, "g", "o")
        assertTrue("expected capture to succeed, got $result", result is RepeatClosureCapture.Ok)
        assertEquals(listOf(901L), (result as RepeatClosureCapture.Ok).record.instances.map { it.itemId })
    }

    // ================= C3. 删除计划的 postState 稳定性 =================

    @Test fun `delete plan keeps the deletion postState and never rewrites it from refresh`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val plan = RepeatClosureDeletePlan.plan(items, tpl, "group-1", "op-1", now = now - 60_000L)
        assertTrue(plan is DeletePlanResult.Ok)
        val ok = plan as DeletePlanResult.Ok
        val real = realDeletion(items, tpl, now - 60_000L)
        val member = ok.groupToWrite.instances.single()
        // postState 必须仍是真实删除留下的状态。
        assertEquals(real.items.single { it.id == 901L }, member.postState)
        assertEquals("重复历史", member.postState.kind)
        assertNull(member.postState.scheduledAt)
    }

    @Test fun `delete plan does not pollute the closure when an unrelated template refreshes`() {
        val tpl = template(id = 700L)
        val other = template(id = 710L, title = "夜读")
        val items = listOf(tpl, other, pendingInstance(id = 901L, templateId = 700L))
        val plan = RepeatClosureDeletePlan.plan(items, tpl, "group-1", "op-1", now = now - 60_000L)
        assertTrue(plan is DeletePlanResult.Ok)
        val ok = plan as DeletePlanResult.Ok
        val otherIds = ok.itemsToWrite.filter { it.repeatTemplateId == other.id }.map { it.id }.toSet()
        assertTrue("unrelated template should still generate", otherIds.isNotEmpty())
        assertTrue(otherIds.none { id -> ok.groupToWrite.instances.any { it.itemId == id } })
        assertEquals(setOf(901L), ok.groupToWrite.instances.map { it.itemId }.toSet())
    }

    // ================= C4. 完整模板字段往返（含非空关联字段） =================

    @Test fun `template round trip preserves non null links and every field`() {
        val linked = template().copy(
            goalId = 4242L,
            parentCaptureId = 5252L,
            userNote = "模板备注",
            sourceDetail = "模板来源",
            nextAction = "下一步",
            dueAt = now + 86_400_000L,
            durationMinutes = 25,
            priority = "high",
            planBucket = "later",
            planFocus = true,
            repeatStartDay = midnight(day),
            repeatMinute = 7 * 60 + 30,
            checklist = listOf(ChecklistEntry(31L, "模板检查项", true)),
        )
        val items = listOf(linked, pendingInstance(id = 901L))
        val record = captureOrFail(items, linked)
        val decoded = roundTrip(record)

        val pre = decoded.template.preState
        assertEquals(linked, pre)
        // 用**非空**关联字段证明关联被保留，而不是靠 null 蒙混。
        assertEquals(4242L, pre.goalId)
        assertEquals(5252L, pre.parentCaptureId)
        assertEquals("模板备注", pre.userNote)
        assertEquals("模板来源", pre.sourceDetail)
        assertEquals("下一步", pre.nextAction)
        assertEquals(now + 86_400_000L, pre.dueAt)
        assertEquals(25, pre.durationMinutes)
        assertEquals("high", pre.priority)
        assertEquals("later", pre.planBucket)
        assertTrue(pre.planFocus)
        assertEquals(listOf(ChecklistEntry(31L, "模板检查项", true)), pre.checklist)
        assertEquals(7 * 60 + 30, pre.repeatMinute)
    }

    @Test fun `template snapshot check ignores json key order but rejects changed content`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val record = captureOrFail(items, tpl)
        val afterDelete = itemStateAfterCapture(items, tpl, record)

        // 在不改变任何值的前提下，把 tombstone 快照与 preState 的无损 JSON 都做键旋转：
        // 自洽检查必须只看内容，不看键顺序。
        val originalSnapshot = requireNotNull(record.template.postState.trashSnapshot)
        val rotatedSnapshot = rotateTopLevelKey(originalSnapshot)
        assertNotEquals(originalSnapshot, rotatedSnapshot)
        val rotatedTombstone = record.template.postState.copy(trashSnapshot = rotatedSnapshot)
        val reordered = record.copy(template = record.template.copy(postState = rotatedTombstone))
        // 当前快照里的墓碑也换成同一份旋转过的 JSON，避免被当成「墓碑被改过」。
        val currentWithRotated = afterDelete.map { if (it.id == tpl.id) rotatedTombstone else it }
        val accepted = RepeatClosureRecoveryPlan.plan(reordered, currentWithRotated, now, zone, emptyList())
        assertTrue("reordered keys must still be consistent, got $accepted", accepted is RecoveryPlanResult.Ok)

        // 内容真的被改过时仍然拒绝，且必须因**快照不一致**而不是别的原因。
        val changed = record.copy(template = record.template.copy(preState = record.template.preState.copy(title = "改过")))
        val rejected = RepeatClosureRecoveryPlan.plan(changed, afterDelete, now, zone, emptyList())
        assertRecoveryRejected("SNAPSHOT_INCONSISTENT", rejected)
    }

    /** 把顶层单个对象 `{...}` 的第一个键值对移到最后，值完全不变。 */
    private fun rotateTopLevelKey(json: String): String {
        val start = json.indexOf('{')
        if (start < 0) return json
        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        var firstComma = -1
        json.forEachIndexed { index, ch ->
            if (index < start) return@forEachIndexed
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '{' -> depth++
                ch == '}' -> { depth--; if (depth == 0 && end < 0) end = index }
                ch == ',' && depth == 1 && firstComma < 0 -> firstComma = index
            }
        }
        if (end < 0 || firstComma < 0) return json
        val head = json.substring(start + 1, firstComma)
        val tail = json.substring(firstComma + 1, end)
        return json.substring(0, start + 1) + tail + "," + head + json.substring(end)
    }

    // ================= C5. 记录校验与计划入口守卫 =================

    @Test fun `recovery plan rejects a blank group or operation id`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val record = captureOrFail(items, tpl)
        val afterDelete = itemStateAfterCapture(items, tpl, record)

        assertRecoveryRejected("groupId must not be blank", RepeatClosureRecoveryPlan.plan(record.copy(groupId = " "), afterDelete, now, zone))
        assertRecoveryRejected("operationId must not be blank", RepeatClosureRecoveryPlan.plan(record.copy(operationId = ""), afterDelete, now, zone))
    }

    @Test fun `recovery plan rejects an active record that carries restoration results`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val record = captureOrFail(items, tpl)
        val afterDelete = itemStateAfterCapture(items, tpl, record)
        val inconsistent = record.copy(
            status = RecoveryGroupStatus.ACTIVE,
            restoration = RepeatClosureRestoration(
                now,
                listOf(RepeatClosureRestoredInstance(901L, RepeatClosureInstanceStatus.RESTORED)),
            ),
        )
        assertRecoveryRejected(
            "ACTIVE group must not carry restoration results",
            RepeatClosureRecoveryPlan.plan(inconsistent, afterDelete, now, zone),
        )
    }

    @Test fun `recovery plan rejects a non positive now`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val record = captureOrFail(items, tpl)
        val afterDelete = itemStateAfterCapture(items, tpl, record)
        assertRecoveryRejected("now must be positive", RepeatClosureRecoveryPlan.plan(record, afterDelete, 0L, zone))
        assertRecoveryRejected("now must be positive", RepeatClosureRecoveryPlan.plan(record, afterDelete, -1L, zone))
    }

    @Test fun `successful plan group round trips exactly`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val (plan, _, _) = deleteThenRecover(items, tpl)
        assertTrue(plan is RecoveryPlanResult.Ok)
        val ok = plan as RecoveryPlanResult.Ok
        val decoded = roundTrip(ok.groupToWrite)
        assertEquals(ok.groupToWrite, decoded)
        assertEquals(RecoveryGroupStatus.RESTORED, decoded.status)
        assertEquals(ok.restoration, decoded.restoration)
    }

    @Test fun `delete plan group round trips exactly`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val plan = RepeatClosureDeletePlan.plan(items, tpl, "group-1", "op-1", now = now - 60_000L)
        assertTrue(plan is DeletePlanResult.Ok)
        val ok = plan as DeletePlanResult.Ok
        assertEquals(ok.groupToWrite, roundTrip(ok.groupToWrite))
    }

    // ================= C6. 统一时区 =================

    @Test fun `recovery plan rejects a zone that disagrees with the system zone`() {
        val tpl = template()
        val items = listOf(tpl, pendingInstance(id = 901L))
        val record = captureOrFail(items, tpl)
        val afterDelete = itemStateAfterCapture(items, tpl, record)
        val mismatched = if (ZoneId.systemDefault() == ZoneId.of("Asia/Shanghai")) ZoneId.of("UTC") else ZoneId.of("Asia/Shanghai")
        val plan = RepeatClosureRecoveryPlan.plan(record, afterDelete, now, mismatched, emptyList())
        // 必须是**时区**守卫拒绝。
        assertRecoveryRejected("zoneId must match the system zone", plan)
    }

    @Test fun `today dayOnly instance survives recovery under both Shanghai and UTC`() {
        listOf(ZoneId.of("Asia/Shanghai"), ZoneId.of("UTC")).forEach { systemZone ->
            withDefaultZone(systemZone) {
                // 用**当前**系统时区构造「今天」的全天实例。
                val today = LocalDate.now(systemZone)
                val todayMidnight = today.atStartOfDay(systemZone).toInstant().toEpochMilli()
                val todayNoon = today.atTime(LocalTime.NOON).atZone(systemZone).toInstant().toEpochMilli()
                val tpl = template().copy(repeatStartDay = todayMidnight, repeatMinute = 9 * 60)
                val items = listOf(
                    tpl,
                    pendingInstance(
                        id = 901L,
                        scheduledAt = todayMidnight,
                        occurrenceDay = todayMidnight,
                        dayOnly = true,
                    ),
                )
                val record = captureOrFail(items, tpl, deletedAt = todayNoon - 60_000L)
                val afterDelete = itemStateAfterCapture(items, tpl, record)
                val plan = RepeatClosureRecoveryPlan.plan(record, afterDelete, todayNoon, systemZone, emptyList())

                assertTrue("$systemZone: expected ok, got $plan", plan is RecoveryPlanResult.Ok)
                val ok = plan as RecoveryPlanResult.Ok
                val restored = ok.itemsToWrite.single { it.id == 901L }
                // 不得立即变成过期历史。
                assertEquals("$systemZone: restored instance must stay a live task", "任务", restored.kind)
                assertEquals("$systemZone: scheduledAt must be unchanged", todayMidnight, restored.scheduledAt)
                // 也不得为同一发生日重复生成实例。
                assertEquals(
                    "$systemZone: must not duplicate the same occurrence day",
                    1,
                    ok.itemsToWrite.count { it.repeatTemplateId == tpl.id && it.kind == "任务" },
                )
                assertEquals(
                    RepeatClosureInstanceStatus.RESTORED,
                    ok.restoration.instances.single { it.itemId == 901L }.status,
                )
            }
        }
        // 两轮都结束后，系统时区应回到本用例进入时的上海基线（@Before 已设置）。
        assertEquals(zone, ZoneId.systemDefault())
    }
}
