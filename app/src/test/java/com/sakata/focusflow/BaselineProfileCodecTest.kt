package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineProfileCodecTest {
    @Test fun roundtrip_fullProfile() {
        val profile = BaselineProfile(
            lifeStage = LifeStage.SCHOOL,
            wakeMinute = 420, sleepMinute = 1380,
            meals = listOf(
                MealTimeline(MealType.BREAKFAST, 510, 25),
                MealTimeline(MealType.LUNCH, 720, 30)
            ),
            entertainmentWindow = "21:00-22:00",
            variantName = "考试周压缩版",
            dayGroups = listOf(
                DayGroup("周五", setOf(5), 8 * 60, 24 * 60 + 30, listOf(MealTimeline(MealType.DINNER, 1080, 20)))
            )
        )
        val decoded = BaselineProfileCodec.parse(org.json.JSONObject(BaselineProfileCodec.encode(profile)))
        assertEquals(LifeStage.SCHOOL, decoded.lifeStage)
        assertEquals(420, decoded.wakeMinute)
        assertEquals(1380, decoded.sleepMinute)
        assertEquals(2, decoded.meals.size)
        assertEquals(MealType.BREAKFAST, decoded.meals[0].type)
        assertEquals(510, decoded.meals[0].typicalStartMinute)
        assertEquals(25, decoded.meals[0].typicalMinutes)
        assertEquals("21:00-22:00", decoded.entertainmentWindow)
        assertEquals("考试周压缩版", decoded.variantName)
        assertEquals(1, decoded.dayGroups.size)
        assertEquals(setOf(5), decoded.dayGroups[0].days)
        assertEquals(8 * 60, decoded.dayGroups[0].wakeMinute)
        assertEquals(MealType.DINNER, decoded.dayGroups[0].meals[0].type)
    }

    @Test fun roundtrip_emptyProfile() {
        val decoded = BaselineProfileCodec.parse(org.json.JSONObject(BaselineProfileCodec.encode(BaselineProfile())))
        assertNull(decoded.lifeStage)
        assertEquals(-1, decoded.wakeMinute)
        assertEquals(0, decoded.meals.size)
        assertEquals(0, decoded.dayGroups.size)
    }

    @Test fun decode_unknownLifeStageIterationStaysNull() {
        val raw = BaselineProfileCodec.encode(BaselineProfile()).replace("\"lifeStage\":\"\"", "\"lifeStage\":\"future\"")
        val decoded = BaselineProfileCodec.parse(org.json.JSONObject(raw))
        assertNull(decoded.lifeStage)
    }

    @Test fun decode_unknownMealTypeFallsBackToBreakfast480() {
        val raw = """{"lifeStage":"school","meals":[{"type":"午宵","typicalStartMinute":0,"typicalMinutes":1}]}"""
        val decoded = BaselineProfileCodec.parse(org.json.JSONObject(raw))
        assertEquals(1, decoded.meals.size)
        assertEquals(MealType.BREAKFAST, decoded.meals[0].type)
        assertEquals(480, decoded.meals[0].typicalStartMinute)
        assertEquals(20, decoded.meals[0].typicalMinutes)
    }

    @Test fun decode_malformedMealsArray_degradesToEmpty() {
        val raw = """{"lifeStage":"school","meals":{"a":1}}"""
        val decoded = BaselineProfileCodec.parse(org.json.JSONObject(raw))
        assertEquals(0, decoded.meals.size)
        assertTrue(decoded.dayGroups.isEmpty())
    }
}
