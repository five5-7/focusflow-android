package com.sakata.focusflow

/** Visual spacing only; system insets are handled by the Compose container, not guessed here. */
internal object FloatingNavigationLayout {
    const val MIN_CONTENT_WIDTH_DP = 280
    const val MAX_BAR_WIDTH_DP = 640
    const val INNER_PADDING_DP = 8
    const val OUTER_RADIUS_DP = 28
    const val ITEM_RADIUS_DP = 20
    const val MIN_ITEM_HEIGHT_DP = 76

    /** 图标底色块（选中/子页圆环）的尺寸，也是图标所在方框的大小。 */
    const val ICON_BOX_DP = 48

    fun horizontalMarginDp(availableWidthDp: Float, fontScale: Float): Int = when {
        availableWidthDp < 280f -> 4
        availableWidthDp < 360f || fontScale >= 1.3f -> 8
        else -> 16
    }
}
