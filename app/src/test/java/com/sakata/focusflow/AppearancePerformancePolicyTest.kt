package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearancePerformancePolicyTest {
    @Test fun defaultRichModeKeepsProfileQuality() {
        assertEquals(
            0.66f,
            AppearancePerformancePolicy.glassInputScale(0.66f, richForms = true, durationScale = 1f),
            0.0001f
        )
    }

    @Test fun reducedEffectsLowerCaptureResolutionWithoutChangingMaterial() {
        val reduced = AppearancePerformancePolicy.glassInputScale(
            0.66f,
            richForms = false,
            durationScale = 1f
        )
        assertEquals(0.4752f, reduced, 0.0001f)
    }

    @Test fun disabledMotionAlsoUsesReducedCaptureResolution() {
        val reduced = AppearancePerformancePolicy.glassInputScale(
            0.66f,
            richForms = true,
            durationScale = 0f
        )
        assertEquals(0.4752f, reduced, 0.0001f)
    }

    @Test fun invalidProfileValuesAreClamped() {
        assertEquals(0.35f, AppearancePerformancePolicy.glassInputScale(0f, false, 1f), 0.0001f)
        assertEquals(1f, AppearancePerformancePolicy.glassInputScale(4f, true, 1f), 0.0001f)
    }
}
