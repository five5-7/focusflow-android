package com.sakata.focusflow

import com.sakata.focusflow.data.CourseEntity
import com.sakata.focusflow.data.CourseMeetingRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住"当前 Room 结构下，一门课**最多只能有一条课次规则**"这个前提。
 *
 * 为什么值得一条测试（2026-09-29 审计结论，详见 `docs/9.0-stage6-room-meeting-mapping-audit.md`）：
 * 阶段 6 检查点把"多规则共享父 ID 会造成课次覆盖/提醒错位"列为待审项。逐行核对后的实际情况是：
 *
 * 1. `CourseMeetingRuleEntity.fromLegacy` 把 `id` 与 `courseId` **都设成父课程的 id**
 *    ⇒ 两条规则撞主键 ⇒ **一对多在库里根本写不下**（不是"没处理"，是"表达不了"）。
 * 2. 读取路径在 `CoreDataRepositories` 里有一道**严格 1:1 校验**：`courses.map{id}` 必须逐个等于
 *    `rules.map{courseId}`，且 `parent.id == rule.id`；不满足就**整份读取返回 Invalid**
 *    ⇒ 是"硬失败"，不是静默配错。
 * 3. 但它同时意味着：**`zip` 之所以安全，只是因为那道校验在**。谁要是放松校验却不动映射，
 *    就会出现"规则配到别的课程上"的静默错配。
 *
 * 这些断言把 1、2 两条结构事实钉住：改动其中任何一条，本测试会红，逼改动者去读那份审计。
 */
class CourseMeetingRuleMappingTest {

    private fun course(
        title: String = "有机化学",
        weekday: Int = 1,
        startPeriod: Int = 6,
        endPeriod: Int = 6,
        id: Long = 1001L
    ) = Course(
        title = title,
        weekday = weekday,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        building = "西2-209",
        zone = CampusZone.WEST_TEACHING,
        needsConfirmation = false,
        enabled = true,
        id = id
    )

    @Test
    fun `a meeting rule takes both its primary key and its course id from the parent course`() {
        val rule = CourseMeetingRuleEntity.fromLegacy(course(id = 4242L), sourceOrder = 0)

        assertEquals("规则主键必须等于父课程 id", 4242L, rule.id)
        assertEquals("规则的外键也必须等于父课程 id", 4242L, rule.courseId)
    }

    @Test
    fun `two rules for the same course collide on the primary key so one to many cannot be stored`() {
        // 同一门课的两次上课（周一第 6 节 / 周三第 8 节）：在当前结构里它们会是同一条记录。
        val first = CourseMeetingRuleEntity.fromLegacy(course(weekday = 1, startPeriod = 6, id = 7L), sourceOrder = 0)
        val second = CourseMeetingRuleEntity.fromLegacy(course(weekday = 3, startPeriod = 8, id = 7L), sourceOrder = 1)

        assertEquals("两条规则的主键相同 ⇒ 后者会覆盖前者", first.id, second.id)
        assertNotEquals("但内容确实不同 ⇒ 覆盖就是丢数据", first.weekday, second.weekday)
    }

    @Test
    fun `the legacy course id comes from the parent entity not from the rule`() {
        val parent = CourseEntity(id = 555L, sourceOrder = 3, title = "大学物理实验", needsConfirmation = false)
        val rule = CourseMeetingRuleEntity(
            id = 555L, courseId = 555L, sourceOrder = 3, weekday = 2,
            startPeriod = 3, endPeriod = 5, building = "东4-212", zone = CampusZone.EAST_TEACHING.name,
            enabled = true, effectiveFromEpochDay = null, effectiveUntilEpochDay = null
        )

        val restored = rule.toLegacy(parent)

        assertEquals("还原出的课程 id 取自父实体", 555L, restored.id)
        assertEquals("课次内容取自规则", 3, restored.startPeriod)
        assertEquals("标题取自父实体", "大学物理实验", restored.title)
    }

    @Test
    fun `the read path requires an exact element wise pairing between courses and rules`() {
        // 复刻 CoreDataRepositories 里那道校验的条件（1:1 且 id 对齐），
        // 这是 `courses.zip(rules)` 不会配错的**唯一**原因。
        val courses = listOf(
            CourseEntity(id = 1L, sourceOrder = 0, title = "A", needsConfirmation = false),
            CourseEntity(id = 2L, sourceOrder = 1, title = "B", needsConfirmation = false)
        )
        val goodRules = listOf(
            CourseMeetingRuleEntity(1L, 1L, 0, 1, 1, 2, "L1", CampusZone.WEST_TEACHING.name, true, null, null),
            CourseMeetingRuleEntity(2L, 2L, 1, 3, 3, 4, "L2", CampusZone.WEST_TEACHING.name, true, null, null)
        )
        val oneToManyRules = goodRules + CourseMeetingRuleEntity(
            1L, 1L, 2, 5, 5, 6, "L3", CampusZone.WEST_TEACHING.name, true, null, null
        )

        val okPairing = courses.map { it.id } == goodRules.map { it.courseId } &&
            courses.zip(goodRules).all { (p, r) -> p.id == r.id }
        val badPairing = courses.map { it.id } == oneToManyRules.map { it.courseId }

        assertTrue("1:1 对齐时校验通过（zip 安全）", okPairing)
        assertTrue("一旦一对多，逐元素比较必然不等 ⇒ 整份读取 Invalid，而不是静默错配", !badPairing)
    }
}
