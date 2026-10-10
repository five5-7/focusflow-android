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

    private fun previousMarginDp(availableWidthDp: Float, fontScale: Float): Int = when {
        availableWidthDp < 280f -> 4
        availableWidthDp < 360f || fontScale >= 1.3f -> 8
        else -> 16
    }

    private fun rc2MarginDp(availableWidthDp: Float, fontScale: Float): Int = when {
        availableWidthDp < 280f -> 4
        availableWidthDp < 360f || fontScale >= 1.3f -> 6
        else -> 8
    }

    /** 同一安全区内左右和底部使用相同留白；小屏多让出 2dp，保留箭头触控边界。 */
    fun horizontalMarginDp(availableWidthDp: Float, fontScale: Float): Int = when {
        availableWidthDp < 280f -> 4
        availableWidthDp < 360f || fontScale >= 1.3f -> 4
        else -> 8
    }

    /**
     * 相对已分发的 rc.2，每边再外移 2dp；按屏幕绝对坐标计算，包含胶囊锚点变化。
     * 旧箭头在胶囊边缘外 4dp，因此新箭头最多仍在新边缘外 4dp：总移动不超过拓宽量。
     * 极窄窗口不能出安全区，平板限宽不能虚算拓宽；这些边界按实际余量限幅。
     */
    fun historyArrowOffsetDp(availableWidthDp: Float, fontScale: Float): Float {
        val priorMargin = rc2MarginDp(availableWidthDp, fontScale)
        val margin = horizontalMarginDp(availableWidthDp, fontScale)
        val priorBarWidth = minOf(MAX_BAR_WIDTH_DP.toFloat(), availableWidthDp - priorMargin * 2)
        val barWidth = minOf(MAX_BAR_WIDTH_DP.toFloat(), availableWidthDp - margin * 2)
        val priorEdge = (availableWidthDp - priorBarWidth) / 2f
        val edge = (availableWidthDp - barWidth) / 2f
        val priorAddedWidth = previousMarginDp(availableWidthDp, fontScale) - priorMargin
        val priorOffset = if (availableWidthDp > MAX_BAR_WIDTH_DP + 2 * priorMargin) {
            4f
        } else {
            (4 + minOf(4, priorAddedWidth) - priorAddedWidth).toFloat()
        }
        val requestedLeft = priorEdge - priorOffset - 2f
        val minimumLeft = maxOf(0f, edge - 4f)
        return edge - maxOf(requestedLeft, minimumLeft)
    }
}
