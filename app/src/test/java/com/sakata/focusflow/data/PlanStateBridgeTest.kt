package com.sakata.focusflow.data

import com.sakata.focusflow.Goal
import com.sakata.focusflow.PlanState
import org.junit.Assert.assertEquals
import org.junit.Test

class PlanStateBridgeTest {
    @Test fun `all plan states round trip through existing Room state column`() {
        PlanState.entries.forEach { state ->
            val goal = Goal(id = 42, title = "方向", weeklyTarget = 1, durationMinutes = 30,
                sourceNotes = "保留原文", state = state)
            val entity = PlanEntity.fromLegacy(goal)
            assertEquals(state.key, entity.state)
            assertEquals(goal, entity.toLegacy())
        }
    }
}
