package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 7.4 前置：重复规则的 `repeatFrequency` 必须能穿过 items 的 JSON 边界。
 *
 * `ItemsCodec.encode` 写入任意频率（见 `ItemsCodec.kt` 的 `put("repeatFrequency", ...)`），
 * 但解码侧曾把白名单限制为 `daily`/`weekly`，导致 `class_day` 在第一次往返后被清空为 `""`。
 * 后果不是外观问题：`RepeatActions.refresh` 的模板过滤器要求
 * `repeatFrequency in setOf("daily", "weekly", "class_day")`，因此规则会永久停止生成实例，
 * 而 `deleteRule` 的墓碑快照同样经此解码，恢复回来的 `class_day` 规则也是死的。
 */
class RepeatFrequencyCodecTest {

    private fun template(frequency: String) = Item(
        id = 7L,
        title = "复习今天课程",
        detail = "有课日重复",
        kind = "重复模板",
        repeatFrequency = frequency,
        repeatStartDay = 1_700_000_000_000L,
        repeatMinute = 9 * 60
    )

    /** 三个受支持的频率都必须逐字往返。 */
    @Test fun roundtrip_preservesEverySupportedRepeatFrequency() {
        listOf("daily", "weekly", "class_day").forEach { frequency ->
            val decoded = ItemsCodec.decode(ItemsCodec.encode(listOf(template(frequency)))).items.single()
            assertEquals(
                "repeatFrequency must survive the JSON boundary for $frequency",
                frequency,
                decoded.repeatFrequency
            )
        }
    }

    /** 回归断言：`class_day` 曾经在这一步被清空，规则随之永久失活。 */
    @Test fun roundtrip_keepsClassDayRuleAlive() {
        val decoded = ItemsCodec.decode(ItemsCodec.encode(listOf(template("class_day")))).items.single()
        assertTrue(
            "a class_day template must stay eligible for RepeatActions.refresh after a reload",
            decoded.repeatFrequency in setOf("daily", "weekly", "class_day")
        )
    }

    /** 与 repeatFrequency 同组的其余字段也必须一起往返，避免只修一半。 */
    @Test fun roundtrip_keepsClassDayCompanionFields() {
        val decoded = ItemsCodec.decode(ItemsCodec.encode(listOf(template("class_day")))).items.single()
        assertEquals("重复模板", decoded.kind)
        assertEquals(1_700_000_000_000L, decoded.repeatStartDay)
        assertEquals(9 * 60, decoded.repeatMinute)
    }

    /** 未知频率仍然落回空字符串——白名单语义不变，只是补上了 `class_day`。 */
    @Test fun decode_stillRejectsUnknownFrequency() {
        val decoded = ItemsCodec.decode(ItemsCodec.encode(listOf(template("yearly")))).items.single()
        assertEquals("", decoded.repeatFrequency)
    }

    /** 旧存档缺键时仍为空字符串。 */
    @Test fun decode_missingFrequencyStaysEmpty() {
        val decoded = ItemsCodec.decode("""[{"id":1,"title":"旧记录","detail":"","kind":"任务"}]""")
            .items.single()
        assertEquals("", decoded.repeatFrequency)
    }

    /** 已被删除的规则模板（kind=回收站）也要保住频率，否则墓碑快照无法还原规则。 */
    @Test fun roundtrip_keepsFrequencyOnTrashedTemplate() {
        val tombstone = template("class_day").copy(
            kind = "回收站",
            detail = "重复规则已删除",
            repeatFrequency = "",
            trashedAt = 1_700_000_000_000L,
            trashSnapshot = ItemsCodec.encode(listOf(template("class_day")))
        )
        val decoded = ItemsCodec.decode(ItemsCodec.encode(listOf(tombstone))).items.single()
        assertEquals("回收站", decoded.kind)
        val restored = ItemsCodec.decode(requireNotNull(decoded.trashSnapshot)).items.single()
        assertEquals(
            "the snapshot restored by TrashActions.restore must keep its rule alive",
            "class_day",
            restored.repeatFrequency
        )
    }
}
