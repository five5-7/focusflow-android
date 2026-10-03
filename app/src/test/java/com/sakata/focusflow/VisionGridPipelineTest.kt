package com.sakata.focusflow

import org.junit.Assert.*
import org.junit.Test

class VisionGridPipelineTest {
    private fun anchors() = VisionAnchorSet(listOf(
        VisionAnchor(VisionAnchorCorner.TOP_LEFT, 0.10, 0.12),
        VisionAnchor(VisionAnchorCorner.TOP_RIGHT, 0.92, 0.08),
        VisionAnchor(VisionAnchorCorner.BOTTOM_RIGHT, 0.97, 0.94),
        VisionAnchor(VisionAnchorCorner.BOTTOM_LEFT, 0.06, 0.90)
    ))

    private fun geometry() = VisionImageGeometry(
        1000, 800, listOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),
        (1..7).map { VisionGridAxis(it, (it - 1) / 7.0, it / 7.0, "manual") },
        (1..6).map { VisionGridAxis(it, (it - 1) / 6.0, it / 6.0, "manual") },
        listOf("manual")
    )

    @Test fun anchors_require_four_distinct_convex_corners() {
        assertTrue(anchors().valid())
        assertFalse(anchors().copy(anchors = anchors().anchors.drop(1)).valid())
        assertFalse(anchors().copy(anchors = anchors().anchors.map { it.copy(x = 0.5, y = 0.5) }).valid())
        assertFalse(anchors().copy(anchors = anchors().anchors.map { it.copy(source = "detected") }).valid())
    }

    @Test fun perspective_maps_photographed_corners_to_canonical_square() {
        val transform = VisionGridPipeline.perspective(anchors())
        assertNotNull(transform)
        val mapped = anchors().anchors.associate { it.corner to transform!!.map(VisionMappedPoint(it.x, it.y))!! }
        assertEquals(0.0, mapped[VisionAnchorCorner.TOP_LEFT]!!.x, 1e-6)
        assertEquals(0.0, mapped[VisionAnchorCorner.TOP_LEFT]!!.y, 1e-6)
        assertEquals(1.0, mapped[VisionAnchorCorner.TOP_RIGHT]!!.x, 1e-6)
        assertEquals(1.0, mapped[VisionAnchorCorner.BOTTOM_RIGHT]!!.y, 1e-6)
        assertEquals(0.0, mapped[VisionAnchorCorner.BOTTOM_LEFT]!!.x, 1e-6)
    }

    @Test fun preview_corrects_box_and_locates_a_unique_grid_cell() {
        val candidate = VisionCandidate(
            "c1", "数学", 1, 1, 1,
            VisionBox(0.10, 0.12, 0.22, 0.25), emptyList()
        )
        val preview = VisionGridPipeline.preview(geometry(), listOf(candidate), anchors())
        assertEquals(VisionGridCell(1, 1, 1), preview.cells["c1"])
        assertTrue(preview.correctedBoxes["c1"]!!.corrected!!.valid())
        assertTrue(preview.warnings.isEmpty())
    }

    @Test fun missing_coordinates_use_box_span_for_multi_period_review() {
        val candidate = VisionCandidate(
            "multi", "物理", null, null, null,
            VisionBox(0.10, 0.17, 0.20, 0.50), emptyList()
        )
        val preview = VisionGridPipeline.preview(geometry(), listOf(candidate), null)
        assertEquals(VisionGridCell(1, 2, 3), preview.cells["multi"])
    }

    @Test fun overlapping_axes_remain_unresolved_for_manual_review() {
        val g = geometry().copy(weekdays = listOf(
            VisionGridAxis(1, 0.0, 0.6, "manual"),
            VisionGridAxis(2, 0.5, 1.0, "manual")
        ))
        assertNull(VisionGridPipeline.locate(VisionMappedPoint(0.55, 0.2), g))
    }

    @Test fun invalid_anchors_produce_a_warning_and_leave_source_box_unchanged() {
        val candidate = VisionCandidate("c1", "数学", 1, 1, 1, VisionBox(0.2,0.2,0.3,0.3), emptyList())
        val invalid = anchors().copy(anchors = anchors().anchors.map { it.copy(x = 0.5, y = 0.5) })
        val preview = VisionGridPipeline.preview(geometry(), listOf(candidate), invalid)
        assertEquals(VisionBox(0.2,0.2,0.3,0.3), preview.correctedBoxes["c1"]!!.corrected)
        assertTrue(preview.warnings.any { it.contains("人工锚点无效") })
    }
}
