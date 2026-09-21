package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class ZjuTimetableImportUiTest {
    @Test
    fun `steps before active stage are complete and later steps remain upcoming`() {
        assertEquals(
            ZjuStepVisualState.COMPLETE,
            zjuStepVisualState(
                ZjuImportStage.ENCRYPTING,
                ZjuImportStage.ESTABLISHING_SESSION,
                running = true,
                failed = false
            )
        )
        assertEquals(
            ZjuStepVisualState.ACTIVE,
            zjuStepVisualState(
                ZjuImportStage.ESTABLISHING_SESSION,
                ZjuImportStage.ESTABLISHING_SESSION,
                running = true,
                failed = false
            )
        )
        assertEquals(
            ZjuStepVisualState.UPCOMING,
            zjuStepVisualState(
                ZjuImportStage.FETCHING_TIMETABLE,
                ZjuImportStage.ESTABLISHING_SESSION,
                running = true,
                failed = false
            )
        )
    }

    @Test
    fun `failure marks only current stage as failed`() {
        assertEquals(
            ZjuStepVisualState.FAILED,
            zjuStepVisualState(
                ZjuImportStage.AUTHENTICATING,
                ZjuImportStage.AUTHENTICATING,
                running = false,
                failed = true
            )
        )
        assertEquals(
            ZjuStepVisualState.UPCOMING,
            zjuStepVisualState(
                ZjuImportStage.ESTABLISHING_SESSION,
                ZjuImportStage.AUTHENTICATING,
                running = false,
                failed = true
            )
        )
    }

    @Test
    fun `done stage completes every visible step`() {
        assertEquals(
            ZjuStepVisualState.COMPLETE,
            zjuStepVisualState(
                ZjuImportStage.PARSING,
                ZjuImportStage.DONE,
                running = false,
                failed = false
            )
        )
    }
}
