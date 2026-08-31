package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameSessionsCodecTest {
    @Test fun roundtrip_preservesFields() {
        val open = GameSessionRecord(
            id = 1L, title = "原神", category = "游戏", packageName = "com.miHoYo.Yuanshen",
            plannedStartAt = 1000L, plannedEndAt = 2000L, actualEndAt = null,
            endedOnTime = false, overrunMinutes = 0, remindStart = true
        )
        val decoded = GameSessionsCodec.decode(GameSessionsCodec.encode(listOf(open))).single()
        assertEquals(1L, decoded.id)
        assertEquals("原神", decoded.title)
        assertEquals("游戏", decoded.category)
        assertEquals("com.miHoYo.Yuanshen", decoded.packageName)
        assertEquals(1000L, decoded.plannedStartAt)
        assertEquals(2000L, decoded.plannedEndAt)
        assertNull(decoded.actualEndAt)
        assertEquals(false, decoded.endedOnTime)
        assertEquals(true, decoded.remindStart)
    }

    @Test fun roundtrip_closedSessionKeepsActualEnd() {
        val closed = GameSessionRecord(
            id = 2L, title = "跑步", packageName = null, plannedStartAt = 1000L, plannedEndAt = 2000L,
            actualEndAt = 2500L, endedOnTime = false, overrunMinutes = 8
        )
        val decoded = GameSessionsCodec.decode(GameSessionsCodec.encode(listOf(closed))).single()
        assertEquals(2500L, decoded.actualEndAt)
        assertEquals(false, decoded.endedOnTime)
        assertEquals(8, decoded.overrunMinutes)
    }

    @Test fun decode_blankCategoryAndPackageFallBack() {
        val raw = GameSessionsCodec.encode(
            listOf(GameSessionRecord(id = 3L, title = "视频", category = "", packageName = null, plannedStartAt = 1L, plannedEndAt = 2L))
        )
        val decoded = GameSessionsCodec.decode(raw).single()
        assertEquals("游戏", decoded.category)
        assertNull(decoded.packageName)
    }

    @Test fun decode_badJson_returnsEmpty() {
        assertEquals(0, GameSessionsCodec.decode("not-json{{{").size)
    }
}
