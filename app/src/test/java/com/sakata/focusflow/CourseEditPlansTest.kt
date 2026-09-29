package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组②纯函数的单测（不接线、不写存储）。
 *
 * 重点钉住三条**已定稿**的取舍（2026-09-29 用户确认）、"拆分↔合并"的往返性质，
 * 以及**空值 = 无边界**这条修掉阻断级缺陷后定下的语义（`mapNotNull{}.min()/max()`
 * 会把 `(null,null)` 往返成 `(150,149)` 这种倒置区间 ⇒ 课程从课表消失）。
 *
 * 不覆盖：真实存储读写、提醒重排、界面接线。
 */
class CourseEditPlansTest {

    private fun course(
        id: Long,
        title: String = "物理化学",
        weekday: Int = 1,
        startPeriod: Int = 6,
        endPeriod: Int = 7,
        building: String = "西1-306",
        from: Long? = null,
        until: Long? = null,
        enabled: Boolean = true,
        needsConfirmation: Boolean = false
    ) = Course(
        title = title,
        weekday = weekday,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        building = building,
        zone = CampusZone.WEST_TEACHING,
        needsConfirmation = needsConfirmation,
        enabled = enabled,
        effectiveFromEpochDay = from,
        effectiveUntilEpochDay = until,
        id = id
    )

    private fun appliedMerge(plan: CourseEditPlans.CourseMergePlan) =
        plan as CourseEditPlans.CourseMergePlan.Applied

    private fun appliedSplit(plan: CourseEditPlans.CourseSplitPlan) =
        plan as CourseEditPlans.CourseSplitPlan.Applied

    // ------------------------------------------------------------ 编辑范围

    @Test
    fun `only following sessions creates a record and the three descriptions differ`() {
        val scopes = CourseEditPlans.CourseEditScope.entries.map { CourseEditPlans.describeCourseEditScope(it) }

        assertEquals(1, scopes.count { it.createsNewRecord })
        assertEquals(
            CourseEditPlans.CourseEditScope.FOLLOWING_SESSIONS,
            scopes.first { it.createsNewRecord }.scope
        )
        assertEquals("三种作用域必须给出三种不同说明", scopes.size, scopes.map { it.description }.toSet().size)
    }

    @Test
    fun `single occurrence is the only scope limited to one day`() {
        val scopes = CourseEditPlans.CourseEditScope.entries.map { CourseEditPlans.describeCourseEditScope(it) }

        assertEquals(1, scopes.count { it.affectsOneOccurrenceOnly })
        assertEquals(
            CourseEditPlans.CourseEditScope.SINGLE_OCCURRENCE,
            scopes.first { it.affectsOneOccurrenceOnly }.scope
        )
    }

    // ------------------------------------------------------------ 拆分

    @Test
    fun `split narrows the original and opens the successor at the boundary`() {
        val plan = appliedSplit(
            CourseEditPlans.planCourseSplit(course(id = 5, from = 100, until = 200), boundaryEpochDay = 150, successorId = 99)
        )

        assertEquals(149L, plan.original.effectiveUntilEpochDay)
        assertEquals(5L, plan.original.id)
        assertEquals(150L, plan.successor.effectiveFromEpochDay)
        assertEquals(200L, plan.successor.effectiveUntilEpochDay)
        assertEquals(99L, plan.successor.id)
    }

    @Test
    fun `split keeps every course field on both halves`() {
        val original = course(
            id = 5, title = "大学物理", weekday = 3, startPeriod = 1, endPeriod = 2,
            building = "东2-101", enabled = false, needsConfirmation = true
        )

        val plan = appliedSplit(CourseEditPlans.planCourseSplit(original, boundaryEpochDay = 150, successorId = 99))

        for (half in listOf(plan.original, plan.successor)) {
            assertEquals("大学物理", half.title)
            assertEquals(3, half.weekday)
            assertEquals(1, half.startPeriod)
            assertEquals(2, half.endPeriod)
            assertEquals("东2-101", half.building)
            assertEquals(CampusZone.WEST_TEACHING, half.zone)
            assertFalse("停用状态也要原样带过去", half.enabled)
            assertTrue("待确认状态也要原样带过去", half.needsConfirmation)
        }
    }

    @Test
    fun `a boundary on the last effective day is allowed and produces a single-day successor`() {
        val plan = appliedSplit(
            CourseEditPlans.planCourseSplit(course(id = 5, from = 100, until = 150), boundaryEpochDay = 150, successorId = 99)
        )

        assertEquals(149L, plan.original.effectiveUntilEpochDay)
        assertEquals(150L, plan.successor.effectiveFromEpochDay)
        assertEquals(150L, plan.successor.effectiveUntilEpochDay)
    }

    @Test
    fun `a boundary at or before the start leaves nothing to keep`() {
        for (boundary in listOf(100L, 99L)) {
            val plan = CourseEditPlans.planCourseSplit(course(id = 5, from = 100, until = 200), boundaryEpochDay = boundary)

            assertEquals(
                "boundary=$boundary",
                CourseEditPlans.SplitRejectReason.BOUNDARY_NOT_AFTER_START,
                (plan as CourseEditPlans.CourseSplitPlan.Rejected).rejectReason
            )
        }
    }

    @Test
    fun `a boundary after the last effective day is rejected`() {
        for (boundary in listOf(201L, 400L)) {
            val plan = CourseEditPlans.planCourseSplit(course(id = 5, from = 100, until = 200), boundaryEpochDay = boundary)

            assertEquals(
                "boundary=$boundary",
                CourseEditPlans.SplitRejectReason.BOUNDARY_AFTER_END,
                (plan as CourseEditPlans.CourseSplitPlan.Rejected).rejectReason
            )
        }
    }

    @Test
    fun `a course that starts and ends on the same day cannot be split`() {
        val single = course(id = 5, from = 100, until = 100)

        assertEquals(
            CourseEditPlans.SplitRejectReason.BOUNDARY_NOT_AFTER_START,
            (CourseEditPlans.planCourseSplit(single, boundaryEpochDay = 100) as CourseEditPlans.CourseSplitPlan.Rejected).rejectReason
        )
        assertEquals(
            CourseEditPlans.SplitRejectReason.BOUNDARY_AFTER_END,
            (CourseEditPlans.planCourseSplit(single, boundaryEpochDay = 101) as CourseEditPlans.CourseSplitPlan.Rejected).rejectReason
        )
    }

    @Test
    fun `an open-ended course splits and keeps the open end`() {
        val plan = appliedSplit(CourseEditPlans.planCourseSplit(course(id = 5), boundaryEpochDay = 300, successorId = 99))

        assertEquals(299L, plan.original.effectiveUntilEpochDay)
        assertEquals(300L, plan.successor.effectiveFromEpochDay)
        assertNull(plan.successor.effectiveUntilEpochDay)
    }

    // ------------------------------------------------------------ 往返：空值 = 无边界

    @Test
    fun `splitting and merging a course with no bounds restores no bounds`() {
        val original = course(id = 5)

        val split = appliedSplit(CourseEditPlans.planCourseSplit(original, boundaryEpochDay = 150, successorId = 99))
        val merged = appliedMerge(CourseEditPlans.planCourseMerge(listOf(split.original, split.successor)))

        assertNull("起点应保持无边界", merged.survivingCourse.effectiveFromEpochDay)
        assertNull("终点应保持无边界", merged.survivingCourse.effectiveUntilEpochDay)
    }

    @Test
    fun `splitting and merging keeps an open end open`() {
        val original = course(id = 5, from = 100)

        val split = appliedSplit(CourseEditPlans.planCourseSplit(original, boundaryEpochDay = 150, successorId = 99))
        val merged = appliedMerge(CourseEditPlans.planCourseMerge(listOf(split.original, split.successor)))

        assertEquals(100L, merged.survivingCourse.effectiveFromEpochDay)
        assertNull("开放结束不能被合成一个具体日期", merged.survivingCourse.effectiveUntilEpochDay)
    }

    @Test
    fun `splitting and merging keeps an open start open`() {
        val original = course(id = 5, until = 200)

        val split = appliedSplit(CourseEditPlans.planCourseSplit(original, boundaryEpochDay = 150, successorId = 99))
        val merged = appliedMerge(CourseEditPlans.planCourseMerge(listOf(split.original, split.successor)))

        assertNull("开放开始不能被合成一个具体日期", merged.survivingCourse.effectiveFromEpochDay)
        assertEquals(200L, merged.survivingCourse.effectiveUntilEpochDay)
    }

    @Test
    fun `splitting and merging again restores a bounded range`() {
        val original = course(id = 5, from = 100, until = 200)

        val split = appliedSplit(CourseEditPlans.planCourseSplit(original, boundaryEpochDay = 150, successorId = 99))
        val merged = appliedMerge(CourseEditPlans.planCourseMerge(listOf(split.original, split.successor)))

        assertEquals(original.effectiveFromEpochDay, merged.survivingCourse.effectiveFromEpochDay)
        assertEquals(original.effectiveUntilEpochDay, merged.survivingCourse.effectiveUntilEpochDay)
    }

    @Test
    fun `an inverted range is never produced`() {
        // 只可能来自本身倒置的输入；宁可不合并，也不产出"任何一天都不生效"的课程。
        val plan = CourseEditPlans.planCourseMerge(listOf(course(id = 10, from = 500, until = 100), course(id = 20, from = 400, until = 300)))

        assertEquals(
            CourseEditPlans.MergeRejectReason.INVALID_EFFECTIVE_RANGE,
            (plan as CourseEditPlans.CourseMergePlan.Rejected).rejectReason
        )
    }

    @Test
    fun `an inverted record is rejected even if another record would hide it`() {
        val plan = CourseEditPlans.planCourseMerge(
            listOf(course(id = 10, from = 100, until = 50), course(id = 20, from = 10, until = 150))
        )
        assertEquals(
            CourseEditPlans.MergeRejectReason.INVALID_EFFECTIVE_RANGE,
            (plan as CourseEditPlans.CourseMergePlan.Rejected).rejectReason
        )
    }

    @Test
    fun `splitting a course whose own range is inverted is rejected`() {
        // 生效期倒置的课程：分界点不晚于起点即被拒，不会产出更离谱的区间。
        val inverted = course(id = 5, from = 500, until = 100)

        assertEquals(
            CourseEditPlans.SplitRejectReason.BOUNDARY_NOT_AFTER_START,
            (CourseEditPlans.planCourseSplit(inverted, boundaryEpochDay = 300) as CourseEditPlans.CourseSplitPlan.Rejected).rejectReason
        )
        assertEquals(
            CourseEditPlans.SplitRejectReason.BOUNDARY_AFTER_END,
            (CourseEditPlans.planCourseSplit(inverted, boundaryEpochDay = 501) as CourseEditPlans.CourseSplitPlan.Rejected).rejectReason
        )
    }

    // ------------------------------------------------------------ 合并：三条定稿规则

    @Test
    fun `the smallest id survives regardless of input order`() {
        val a = course(id = 30)
        val b = course(id = 10)
        val c = course(id = 20)

        val forward = appliedMerge(CourseEditPlans.planCourseMerge(listOf(a, b, c)))
        val shuffled = appliedMerge(CourseEditPlans.planCourseMerge(listOf(c, a, b)))

        assertEquals(10L, forward.survivingId)
        assertEquals(10L, shuffled.survivingId)
        assertEquals(listOf(20L, 30L), forward.deletedCourseIds)
        assertEquals(forward.deletedCourseIds, shuffled.deletedCourseIds)
        assertEquals(forward.survivingCourse, shuffled.survivingCourse)
        assertEquals(forward.mergedTemporaryLocations, shuffled.mergedTemporaryLocations)
    }

    @Test
    fun `the record the user is acting on wins the conflicting location`() {
        val overrides = listOf(
            CourseEditPlans.CourseOverrideSnapshot(10L, mapOf(100L to "西1-101")),
            CourseEditPlans.CourseOverrideSnapshot(20L, mapOf(100L to "西3-303", 300L to "西3-304")),
            CourseEditPlans.CourseOverrideSnapshot(30L, mapOf(100L to "西2-202", 200L to "西2-203"))
        )

        val plan = appliedMerge(
            CourseEditPlans.planCourseMerge(
                listOf(course(id = 10), course(id = 20), course(id = 30)),
                overrides,
                preferredCourseId = 30L
            )
        )

        assertEquals("西2-202", plan.mergedTemporaryLocations[100L])
        // 200/300 两天的存活记录本来没有 ⇒ 纯迁移；100 天存活记录有值且与别人冲突 ⇒ 记被取代。
        // 三项互斥：migrated 只看"存活记录本来没有的天"，因此不会与 superseded 重叠。
        assertEquals(2, plan.migratedTemporaryLocationCount)
        assertEquals(1, plan.supersededTemporaryLocationCount)
        assertEquals(1, plan.overwrittenSurvivorDayCount)
    }

    @Test
    fun `deleted records are written in ascending id order`() {
        // 这条顺序唯一的可观测面是**结果 Map 的插入顺序**（值由"优先最后写"决定，与顺序无关）。
        // 非优先按 10 → 20 写：先写 100（来自 10）、再写 300（来自 20）；优先 30 又写 100（已存在，不新增键）
        // ⇒ 键序 [100, 300]。若实现按 id 降序并入 ⇒ [300, 100]，本断言变红。
        val overrides = listOf(
            CourseEditPlans.CourseOverrideSnapshot(30L, mapOf(100L to "preferred")),
            CourseEditPlans.CourseOverrideSnapshot(20L, mapOf(300L to "only-in-20")),
            CourseEditPlans.CourseOverrideSnapshot(10L, mapOf(100L to "survivor"))
        )

        val plan = appliedMerge(
            CourseEditPlans.planCourseMerge(
                listOf(course(id = 30), course(id = 20), course(id = 10)),
                overrides,
                preferredCourseId = 30L
            )
        )

        assertEquals("preferred", plan.mergedTemporaryLocations[100L])
        assertEquals("only-in-20", plan.mergedTemporaryLocations[300L])
        assertEquals(listOf(100L, 300L), plan.mergedTemporaryLocations.keys.toList())
        assertEquals(1, plan.migratedTemporaryLocationCount)
    }

    @Test
    fun `the survivor wins the day when the record being acted on is the survivor itself`() {
        // 让 `winnerId` 的"优先记录有该天则它胜"这一分支变成承重：默认 preferredId = 存活记录，
        // 若实现无条件取 `holders.maxOrNull()`（= id 更大的被删除记录），overwrittenSurvivor 会算成 1。
        val overrides = listOf(
            CourseEditPlans.CourseOverrideSnapshot(10L, mapOf(100L to "SURV")),
            CourseEditPlans.CourseOverrideSnapshot(20L, mapOf(100L to "DEL"))
        )

        val plan = appliedMerge(CourseEditPlans.planCourseMerge(listOf(course(id = 10), course(id = 20)), overrides))

        assertEquals("SURV", plan.mergedTemporaryLocations[100L])
        assertEquals(0, plan.overwrittenSurvivorDayCount)
        assertEquals(1, plan.supersededTemporaryLocationCount)
    }

    @Test
    fun `a higher-id deleted record overwrites the surviving records own value and says so`() {
        // 复核给过的反例：优先记录没有快照 ⇒ 存活记录（最小 id）的值会被 id 更大的被删除记录覆盖。
        val overrides = listOf(
            CourseEditPlans.CourseOverrideSnapshot(10L, mapOf(100L to "SURVIVOR")),
            CourseEditPlans.CourseOverrideSnapshot(20L, mapOf(100L to "DELETED-20"))
        )

        val plan = appliedMerge(
            CourseEditPlans.planCourseMerge(
                listOf(course(id = 10), course(id = 20), course(id = 30)),
                overrides,
                preferredCourseId = 30L
            )
        )

        assertEquals("DELETED-20", plan.mergedTemporaryLocations[100L])
        assertEquals(0, plan.migratedTemporaryLocationCount)
        assertEquals(1, plan.supersededTemporaryLocationCount)
        assertEquals(1, plan.overwrittenSurvivorDayCount)
        assertTrue(
            "覆盖了保留记录自己的值就必须写出来：${plan.confirmationText}",
            plan.confirmationText.contains("覆盖了保留记录原有的本次地点")
        )
    }

    @Test
    fun `the reminder override follows the same priority`() {
        val preferredWins = appliedMerge(
            CourseEditPlans.planCourseMerge(
                listOf(course(id = 30), course(id = 20), course(id = 10)),
                listOf(
                    CourseEditPlans.CourseOverrideSnapshot(10L, reminderEnabled = null),
                    CourseEditPlans.CourseOverrideSnapshot(20L, reminderEnabled = false),
                    CourseEditPlans.CourseOverrideSnapshot(30L, reminderEnabled = true)
                ),
                preferredCourseId = 30L
            )
        )
        assertEquals(true, preferredWins.mergedReminderEnabled)

        val preferredSilent = appliedMerge(
            CourseEditPlans.planCourseMerge(
                listOf(course(id = 30), course(id = 20), course(id = 10)),
                listOf(
                    CourseEditPlans.CourseOverrideSnapshot(10L, reminderEnabled = null),
                    CourseEditPlans.CourseOverrideSnapshot(20L, reminderEnabled = false),
                    CourseEditPlans.CourseOverrideSnapshot(30L, reminderEnabled = null)
                ),
                preferredCourseId = 30L
            )
        )
        assertEquals("优先记录没设过时，取其余记录里最后一个非空值", false, preferredSilent.mergedReminderEnabled)
    }

    @Test
    fun `the confirmation text always states how many records will be deleted and that there is no undo`() {
        val plan = appliedMerge(CourseEditPlans.planCourseMerge(listOf(course(id = 10), course(id = 20), course(id = 30))))

        assertTrue(plan.confirmationText, plan.confirmationText.contains("将删除 2 条记录"))
        assertTrue(plan.confirmationText, plan.confirmationText.contains("不支持撤销"))
    }

    @Test
    fun `the effective range becomes the union of all records`() {
        val plan = appliedMerge(
            CourseEditPlans.planCourseMerge(
                listOf(course(id = 10, from = 150, until = 200), course(id = 20, from = 100, until = 149))
            )
        )

        assertEquals(100L, plan.survivingCourse.effectiveFromEpochDay)
        assertEquals(200L, plan.survivingCourse.effectiveUntilEpochDay)
    }

    @Test
    fun `different weekly meetings cannot be collapsed into one Course`() {
        val monday = course(id = 10, weekday = 1, startPeriod = 1, endPeriod = 2)
        val wednesday = course(id = 20, weekday = 3, startPeriod = 3, endPeriod = 4)

        val plan = CourseEditPlans.planCourseMerge(listOf(monday, wednesday))

        assertEquals(
            CourseEditPlans.MergeRejectReason.DIFFERENT_MEETINGS,
            (plan as CourseEditPlans.CourseMergePlan.Rejected).rejectReason
        )
    }

    @Test
    fun `separate active ranges cannot create lessons in the gap`() {
        val plan = CourseEditPlans.planCourseMerge(
            listOf(course(id = 10, from = 100, until = 120), course(id = 20, from = 140, until = 160))
        )

        assertEquals(
            CourseEditPlans.MergeRejectReason.DISCONNECTED_EFFECTIVE_RANGES,
            (plan as CourseEditPlans.CourseMergePlan.Rejected).rejectReason
        )
    }

    @Test
    fun `an open end does not hide a gap before an earlier bounded record`() {
        val plan = CourseEditPlans.planCourseMerge(
            listOf(course(id = 10, from = 140), course(id = 20, from = 100, until = 120))
        )

        assertEquals(
            CourseEditPlans.MergeRejectReason.DISCONNECTED_EFFECTIVE_RANGES,
            (plan as CourseEditPlans.CourseMergePlan.Rejected).rejectReason
        )
    }

    @Test
    fun `adjacent active ranges can still be merged`() {
        val plan = appliedMerge(CourseEditPlans.planCourseMerge(
            listOf(course(id = 10, from = 140, until = 160), course(id = 20, from = 100, until = 139))
        ))

        assertEquals(100L, plan.survivingCourse.effectiveFromEpochDay)
        assertEquals(160L, plan.survivingCourse.effectiveUntilEpochDay)
    }

    @Test
    fun `an open start and open end may merge when their ranges overlap`() {
        val plan = appliedMerge(CourseEditPlans.planCourseMerge(
            listOf(course(id = 10, until = 120), course(id = 20, from = 115))
        ))

        assertNull(plan.survivingCourse.effectiveFromEpochDay)
        assertNull(plan.survivingCourse.effectiveUntilEpochDay)
    }

    @Test
    fun `merging keeps a course enabled when any record was enabled`() {
        val plan = appliedMerge(
            CourseEditPlans.planCourseMerge(
                listOf(course(id = 10, enabled = false), course(id = 20, enabled = true)),
                preferredCourseId = 10L
            )
        )

        assertTrue("合并不能让课程静默停用", plan.survivingCourse.enabled)
    }

    @Test
    fun `merging keeps the confirmation flag when any record needed confirmation`() {
        val plan = appliedMerge(
            CourseEditPlans.planCourseMerge(
                listOf(course(id = 10, needsConfirmation = false), course(id = 20, needsConfirmation = true)),
                preferredCourseId = 10L
            )
        )

        assertTrue("合并不能跳过尚待确认的记录", plan.survivingCourse.needsConfirmation)
    }

    @Test
    fun `an empty title on the preferred record falls back to the smallest non-empty one`() {
        val plan = appliedMerge(
            CourseEditPlans.planCourseMerge(
                listOf(course(id = 10, title = "物理化学"), course(id = 20, title = "  ")),
                preferredCourseId = 20L
            )
        )

        assertEquals("物理化学", plan.survivingCourse.title)

        // 区分性输入：**最小 id 的标题才是空的** ⇒ 回退必须按"id 最小的非空标题"，而不是"存活记录的标题"。
        val minIdBlank = appliedMerge(
            CourseEditPlans.planCourseMerge(
                listOf(course(id = 10, title = ""), course(id = 20, title = "大学物理"))
            )
        )

        assertEquals("大学物理", minIdBlank.survivingCourse.title)
    }

    @Test
    fun `blank places are treated as no value and never delete an existing one`() {
        // 真实存储里清空 = 键缺席，不存在空串值。空白必须**既不写入也不删除**：
        // 若实现改成"空白=删除"，下面的第二条断言会变红（存活记录自己的地点被静默删掉）。
        val plan = appliedMerge(
            CourseEditPlans.planCourseMerge(
                listOf(course(id = 10), course(id = 20)),
                listOf(
                    CourseEditPlans.CourseOverrideSnapshot(10L, mapOf(100L to "西1-101")),
                    CourseEditPlans.CourseOverrideSnapshot(20L, mapOf(200L to "   "))
                )
            )
        )

        assertEquals(mapOf(100L to "西1-101"), plan.mergedTemporaryLocations)
        assertEquals(0, plan.migratedTemporaryLocationCount)

        val blankFromHigherPriority = appliedMerge(
            CourseEditPlans.planCourseMerge(
                listOf(course(id = 10), course(id = 20)),
                listOf(
                    CourseEditPlans.CourseOverrideSnapshot(10L, mapOf(100L to "西1-101")),
                    CourseEditPlans.CourseOverrideSnapshot(20L, mapOf(100L to "  "))
                ),
                preferredCourseId = 20L
            )
        )

        assertEquals(
            "优先记录给的是空白——它是「没有值」，不能把存活记录的地点删掉",
            mapOf(100L to "西1-101"),
            blankFromHigherPriority.mergedTemporaryLocations
        )
    }

    @Test
    fun `duplicate snapshots for the same course are rejected in either order`() {
        // "取哪条快照"没有合理默认：稳定排序只是把入参顺序换个样子，结果仍依赖入参 ⇒ 明确拒绝。
        val first = CourseEditPlans.CourseOverrideSnapshot(20L, mapOf(100L to "FIRST"))
        val last = CourseEditPlans.CourseOverrideSnapshot(20L, mapOf(100L to "LAST"))
        val courses = listOf(course(id = 10), course(id = 20))

        for (snapshots in listOf(listOf(first, last), listOf(last, first))) {
            val plan = CourseEditPlans.planCourseMerge(courses, snapshots)

            assertEquals(
                CourseEditPlans.MergeRejectReason.DUPLICATE_OVERRIDES,
                (plan as CourseEditPlans.CourseMergePlan.Rejected).rejectReason
            )
        }
    }

    // ------------------------------------------------------------ 合并：拒绝路径

    @Test
    fun `merging needs at least two records`() {
        val plan = CourseEditPlans.planCourseMerge(listOf(course(id = 10)))

        assertEquals(
            CourseEditPlans.MergeRejectReason.NEEDS_TWO_RECORDS,
            (plan as CourseEditPlans.CourseMergePlan.Rejected).rejectReason
        )
    }

    @Test
    fun `duplicate ids are rejected`() {
        val plan = CourseEditPlans.planCourseMerge(listOf(course(id = 10), course(id = 10)))

        assertEquals(
            CourseEditPlans.MergeRejectReason.DUPLICATE_IDS,
            (plan as CourseEditPlans.CourseMergePlan.Rejected).rejectReason
        )
    }

    @Test
    fun `all blank titles are rejected instead of assumed equal`() {
        val plan = CourseEditPlans.planCourseMerge(listOf(course(id = 10, title = ""), course(id = 20, title = "   ")))

        assertEquals(
            CourseEditPlans.MergeRejectReason.MISSING_TITLE,
            (plan as CourseEditPlans.CourseMergePlan.Rejected).rejectReason
        )
    }

    @Test
    fun `the survivor is never reported as deleted`() {
        val plan = appliedMerge(CourseEditPlans.planCourseMerge(listOf(course(id = 10), course(id = 20), course(id = 30))))

        assertFalse(plan.deletedCourseIds.contains(plan.survivingId))
        assertNotEquals(0, plan.deletedCourseIds.size)
    }
}
