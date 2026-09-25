package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleModeColumnsTest {
    @Test fun `mode choices use available width and font scale`() {
        assertEquals(3, scheduleModeColumns(312, 1f))
        assertEquals(2, scheduleModeColumns(311, 1f))
        assertEquals(2, scheduleModeColumns(312, 1.2f))
        assertEquals(1, scheduleModeColumns(223, 1f))
        assertEquals(1, scheduleModeColumns(312, 1.5f))
    }
}
