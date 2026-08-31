package com.sakata.focusflow

/** Visual spacing only; system insets are handled by the Compose container, not guessed here. */
internal object FloatingNavigationLayout {
    const val MIN_CONTENT_WIDTH_DP = 280
    const val MAX_BAR_WIDTH_DP = 640

    fun horizontalMarginDp(availableWidthDp: Float, fontScale: Float): Int = when {
        availableWidthDp < 280f -> 4
        availableWidthDp < 360f || fontScale >= 1.3f -> 8
        else -> 16
    }
}
