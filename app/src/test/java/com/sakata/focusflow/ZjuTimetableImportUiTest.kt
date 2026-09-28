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
    fun `a failure without a current stage never marks a step as failed`() {
        // 防御性契约：currentStage 为 null 时（同步兜底路径），每一步都只能是 UPCOMING，
        // 绝不能在没有任何进度信息时标红某一步。
        assertEquals(
            ZjuStepVisualState.UPCOMING,
            zjuStepVisualState(
                ZjuImportStage.AUTHENTICATING,
                null,
                running = false,
                failed = true
            )
        )
        assertEquals(
            ZjuStepVisualState.UPCOMING,
            zjuStepVisualState(
                ZjuImportStage.CONNECTING,
                null,
                running = true,
                failed = false
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

    @Test
    fun `duplicate semester labels are marked with readable indices`() {
        val labels = zjuSemesterOptionLabels(
            listOf(
                ZjuSemesterOption("1|秋", "秋", true),
                ZjuSemesterOption("1|短", "短", false),
                ZjuSemesterOption("2|短", "短", false)
            )
        )
        assertEquals(listOf("秋", "短（1）", "短（2）"), labels)
    }
}
