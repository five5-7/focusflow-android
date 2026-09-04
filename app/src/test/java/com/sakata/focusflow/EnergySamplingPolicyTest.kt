package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class EnergySamplingPolicyTest {
    private val zone = ZoneId.of("UTC")
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 9, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun records(slotHour: Int, count: Int, energy: (Int) -> String = { "正常" }) =
        (1..count).map { StatusCheckIn(energy(it), "学习", at(it.coerceAtMost(20), slotHour)) }

    @Test fun `building rotates one primary slot per day by default`() {
        val dayOne = EnergySamplingPolicy.nextPrompts(at(1, 7), at(1, 7), emptyList(), false, zone)
        val dayTwo = EnergySamplingPolicy.nextPrompts(at(2, 7), at(1, 7), emptyList(), false, zone)
        assertEquals(listOf(EnergyTimeSlot.MORNING), dayOne.map { it.slot })
        assertEquals(listOf(EnergyTimeSlot.AFTERNOON), dayTwo.map { it.slot })
    }

    @Test fun `accelerated mode schedules at most two prompts four hours apart`() {
        val prompts = EnergySamplingPolicy.nextPrompts(at(1, 7), at(1, 7), emptyList(), true, zone)
        assertEquals(2, prompts.size)
        assertTrue(prompts[1].triggerAt - prompts[0].triggerAt >= 4 * 60 * 60_000L)
    }

    @Test fun `stable slot is skipped while other slots are still building`() {
        val morning = records(9, 6)
        val state = EnergySamplingPolicy.progress(at(20, 12), morning, zone)
        assertTrue(EnergyTimeSlot.MORNING in state.completedSlots)
        val next = EnergySamplingPolicy.nextPrompts(at(21, 7), at(1, 7), morning, false, zone)
        assertTrue(next.single().slot != EnergyTimeSlot.MORNING)
    }

    @Test fun `variable slot stops after twelve samples to avoid endless questioning`() {
        val mixed = records(9, 12) { if (it % 3 == 0) "偏低" else if (it % 3 == 1) "正常" else "充足" }
        assertTrue(EnergyTimeSlot.MORNING in EnergySamplingPolicy.progress(at(20, 12), mixed, zone).completedSlots)
    }

    @Test fun `all completed slots enter maintenance with one prompt`() {
        val all = records(9, 6) + records(14, 6) + records(19, 6)
        val state = EnergySamplingPolicy.progress(at(20, 12), all, zone)
        assertEquals(EnergySamplingPhase.MAINTENANCE, state.phase)
        assertEquals(1, EnergySamplingPolicy.nextPrompts(at(20, 12), at(1, 7), all, true, zone).size)
    }
}
