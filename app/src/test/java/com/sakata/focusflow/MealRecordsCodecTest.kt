package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MealRecordsCodecTest {
    @Test fun roundtrip_preservesFields() {
        val record = MealRecord(
            id = 9L, mealType = MealType.LUNCH, lifeStage = "school",
            startedAt = 720000L, endedAt = 726000L, location = "西溪食堂",
            category = "一荤一素", merchant = "窗口1", amount = 12, payMethod = "校园卡",
            rating = 4, note = "好吃", recordedAt = 720010L
        )
        val decoded = MealRecordsCodec.decode(MealRecordsCodec.encode(listOf(record))).single()
        assertEquals(9L, decoded.id)
        assertEquals(MealType.LUNCH, decoded.mealType)
        assertEquals("school", decoded.lifeStage)
        assertEquals(720000L, decoded.startedAt)
        assertEquals(726000L, decoded.endedAt)
        assertEquals("西溪食堂", decoded.location)
        assertEquals("一荤一素", decoded.category)
        assertEquals("窗口1", decoded.merchant)
        assertEquals(12, decoded.amount)
        assertEquals("校园卡", decoded.payMethod)
        assertEquals(4, decoded.rating)
        assertEquals("好吃", decoded.note)
        assertEquals(720010L, decoded.recordedAt)
    }

    @Test fun roundtrip_openRecordKeepsNullEnd() {
        val open = MealRecord(id = 1L, mealType = MealType.DINNER, startedAt = 1000L, amount = -1)
        val decoded = MealRecordsCodec.decode(MealRecordsCodec.encode(listOf(open))).single()
        assertNull(decoded.endedAt)
        assertEquals(-1, decoded.amount)
    }

    @Test fun decode_unknownMealTypeFallsBackToLunch() {
        val raw = """[{"id":1,"mealType":"夜宵","startedAt":1000}]"""
        assertEquals(MealType.LUNCH, MealRecordsCodec.decode(raw).single().mealType)
    }

    @Test fun decode_badJson_returnsEmpty() {
        assertEquals(0, MealRecordsCodec.decode("not-json{{{").size)
    }

    @Test fun decode_badFieldTypes_degradesGracefully() {
        // 坏字段值按 org.json 尽力解析，不会崩溃（字段值不保证正确）
        assertEquals(1, MealRecordsCodec.decode("""[{"id":1,"mealType":"午餐","startedAt":"bad"}]""").size)
    }
}
