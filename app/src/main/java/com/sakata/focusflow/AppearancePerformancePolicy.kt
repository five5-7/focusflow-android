package com.sakata.focusflow

/**
 * Small, deterministic quality knobs for expensive appearance effects.
 *
 * The default path keeps the material profile's visual quality unchanged. When the
 * user turns off rich motion (or disables motion completely), the UI also lowers
 * the input resolution used by the shared glass capture. This keeps the choice
 * useful on devices where blur is the most expensive part of scrolling and disclosure
 * animations, without changing the selected material or stored appearance settings.
 */
internal object AppearancePerformancePolicy {
    private const val REDUCED_SCALE_FACTOR = 0.72f
    private const val MIN_REDUCED_SCALE = 0.35f

    fun glassInputScale(baseScale: Float, richForms: Boolean, durationScale: Float): Float {
        val safeBase = baseScale.coerceIn(0.1f, 1f)
        return if (richForms && durationScale > 0f) {
            safeBase
        } else {
            (safeBase * REDUCED_SCALE_FACTOR).coerceAtLeast(MIN_REDUCED_SCALE)
        }
    }
}
