package com.sakata.focusflow

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Batch B geometry input. Coordinates are normalized to the source image. */
enum class VisionAnchorCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

data class VisionAnchor(
    val corner: VisionAnchorCorner,
    val x: Double,
    val y: Double,
    val source: String = "manual"
) {
    fun valid() = source == "manual" && x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0
}

data class VisionAnchorSet(val anchors: List<VisionAnchor>) {
    fun valid(): Boolean {
        if (anchors.size != 4 || anchors.map { it.corner }.toSet().size != 4 || anchors.any { !it.valid() }) return false
        val byCorner = anchors.associateBy { it.corner }
        val p = VisionAnchorCorner.values().map { byCorner[it]!! }
        if (p.zipWithNext().any { distance(it.first, it.second) < 1e-6 } || distance(p.last(), p.first()) < 1e-6) return false
        val signs = p.indices.map { i ->
            val a = p[i]; val b = p[(i + 1) % p.size]; val c = p[(i + 2) % p.size]
            cross(b.x - a.x, b.y - a.y, c.x - b.x, c.y - b.y)
        }.filter { abs(it) > 1e-8 }
        return signs.size == 4 && (signs.all { it > 0 } || signs.all { it < 0 })
    }

    internal fun ordered(): List<VisionAnchor> {
        require(valid())
        val byCorner = anchors.associateBy { it.corner }
        return VisionAnchorCorner.values().map { byCorner[it]!! }
    }

    private companion object {
        fun distance(a: VisionAnchor, b: VisionAnchor) = kotlin.math.hypot(a.x - b.x, a.y - b.y)
        fun cross(ax: Double, ay: Double, bx: Double, by: Double) = ax * by - ay * bx
    }
}

data class VisionMappedPoint(val x: Double, val y: Double) {
    fun valid() = x.isFinite() && y.isFinite()
}

data class VisionPerspectiveTransform(val matrix: List<Double>) {
    init { require(matrix.size == 9 && matrix.all { it.isFinite() }) }

    fun map(point: VisionMappedPoint): VisionMappedPoint? {
        val d = matrix[6] * point.x + matrix[7] * point.y + matrix[8]
        if (!d.isFinite() || abs(d) < 1e-10) return null
        val x = (matrix[0] * point.x + matrix[1] * point.y + matrix[2]) / d
        val y = (matrix[3] * point.x + matrix[4] * point.y + matrix[5]) / d
        return VisionMappedPoint(x, y).takeIf { it.valid() }
    }
}

data class VisionCorrectedBox(val source: VisionBox, val corrected: VisionBox?)

data class VisionGridCell(val day: Int, val startPeriod: Int, val endPeriod: Int)

data class VisionGridPreview(
    val geometry: VisionImageGeometry,
    val cells: Map<String, VisionGridCell?>,
    val correctedBoxes: Map<String, VisionCorrectedBox>,
    val warnings: List<String> = emptyList()
)

object VisionGridPipeline {
    /** Maps a photographed quadrilateral to the canonical unit square. */
    fun perspective(anchors: VisionAnchorSet): VisionPerspectiveTransform? {
        if (!anchors.valid()) return null
        val src = anchors.ordered()
        val target = listOf(
            VisionMappedPoint(0.0, 0.0), VisionMappedPoint(1.0, 0.0),
            VisionMappedPoint(1.0, 1.0), VisionMappedPoint(0.0, 1.0)
        )
        val a = Array(8) { DoubleArray(9) }
        src.forEachIndexed { i, p ->
            val x = p.x; val y = p.y; val u = target[i].x; val v = target[i].y; val r = i * 2
            a[r] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -x * u, -y * u, u)
            a[r + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -x * v, -y * v, v)
        }
        val solved = solve(a) ?: return null
        return VisionPerspectiveTransform(solved + 1.0)
    }

    fun correctedBox(box: VisionBox, transform: VisionPerspectiveTransform): VisionCorrectedBox {
        if (!box.valid()) return VisionCorrectedBox(box, null)
        val points = listOf(
            VisionMappedPoint(box.left, box.top), VisionMappedPoint(box.right, box.top),
            VisionMappedPoint(box.right, box.bottom), VisionMappedPoint(box.left, box.bottom)
        ).mapNotNull(transform::map)
        if (points.size != 4) return VisionCorrectedBox(box, null)
        return VisionCorrectedBox(box, VisionBox(
            points.minOf { it.x }.coerceIn(0.0, 1.0),
            points.minOf { it.y }.coerceIn(0.0, 1.0),
            points.maxOf { it.x }.coerceIn(0.0, 1.0),
            points.maxOf { it.y }.coerceIn(0.0, 1.0)
        ).takeIf { it.valid() })
    }

    fun locate(point: VisionMappedPoint, geometry: VisionImageGeometry): VisionGridCell? {
        if (!geometry.valid() || !point.valid()) return null
        val days = geometry.weekdays.filter { point.x >= it.start && point.x <= it.end }
        val periods = geometry.periods.filter { point.y >= it.start && point.y <= it.end }
        if (days.size != 1 || periods.isEmpty()) return null
        val orderedPeriods = periods.sortedBy { it.index }
        return VisionGridCell(days.single().index, orderedPeriods.first().index, orderedPeriods.last().index)
    }

    fun preview(
        geometry: VisionImageGeometry,
        candidates: List<VisionCandidate>,
        anchors: VisionAnchorSet?
    ): VisionGridPreview {
        if (!geometry.valid()) return VisionGridPreview(geometry, emptyMap(), emptyMap(), listOf("图像几何信息无效"))
        val transform = anchors?.let(::perspective)
        val boxes = candidates.associate { candidate ->
            val corrected = candidate.box?.let { box ->
                if (transform == null) VisionCorrectedBox(box, box.takeIf { it.valid() })
                else correctedBox(box, transform)
            }
            candidate.id to (candidate.box?.let { VisionCorrectedBox(it, corrected?.corrected) } ?: VisionCorrectedBox(VisionBox(0.0,0.0,0.0,0.0), null))
        }
        val cells = candidates.associate { candidate ->
            val box = boxes[candidate.id]?.corrected
            val center = box?.let { VisionMappedPoint((it.left + it.right) / 2, (it.top + it.bottom) / 2) }
            candidate.id to center?.let { locate(it, geometry) }
        }
        val warnings = buildList {
            if (anchors != null && transform == null) add("人工锚点无效，未应用透视修正")
            if (candidates.any { it.box != null && boxes[it.id]?.corrected == null }) add("部分候选框无法映射，需人工检查")
            if (cells.values.any { it == null }) add("部分候选无法唯一定位到星期或节次")
        }
        return VisionGridPreview(geometry, cells, boxes, warnings)
    }

    private fun solve(input: Array<DoubleArray>): List<Double>? {
        val a = input.map { it.copyOf() }.toTypedArray()
        for (column in 0 until 8) {
            var pivot = column
            for (row in column + 1 until 8) if (abs(a[row][column]) > abs(a[pivot][column])) pivot = row
            if (abs(a[pivot][column]) < 1e-10) return null
            val tmp = a[column]; a[column] = a[pivot]; a[pivot] = tmp
            val divisor = a[column][column]
            for (j in column until 9) a[column][j] /= divisor
            for (row in 0 until 8) if (row != column) {
                val factor = a[row][column]
                for (j in column until 9) a[row][j] -= factor * a[column][j]
            }
        }
        return (0 until 8).map { a[it][8] }
    }
}
