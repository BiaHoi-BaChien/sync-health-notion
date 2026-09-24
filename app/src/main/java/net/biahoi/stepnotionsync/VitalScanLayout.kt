package net.biahoi.stepnotionsync

import kotlin.math.hypot
import kotlin.math.min

internal data class VitalScanPoint(val x: Float, val y: Float)
internal data class VitalScanInset(val left: Float = 0f, val right: Float = 1f)

/** Clockwise corners in preview coordinates, plus row boundaries in the rectified column. */
internal data class VitalScanLayout(
    val corners: List<VitalScanPoint>,
    val divisions: List<Float> = listOf(0.40f, 0.77f),
    val rowInsets: List<VitalScanInset> = List(3) { VitalScanInset() }
) {
    fun isValid(): Boolean {
        if (corners.size != 4 || divisions.size != 2 || rowInsets.size != 3) return false
        if (rowInsets.any { !it.left.isFinite() || !it.right.isFinite() || it.left < 0f || it.right > 1f || it.right - it.left < 0.15f }) return false
        if (corners.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return false
        val cuts = listOf(0f) + divisions + 1f
        if (cuts.zipWithNext().any { (a, b) -> !b.isFinite() || b - a < 0.12f }) return false
        // Convex, clockwise, with enough room for each value; prevent mirrored/folded crops.
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val c = corners[(i + 2) % 4]
            if (hypot(b.x - a.x, b.y - a.y) < 0.03f) return false
            if ((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) < 0.001f) return false
        }
        return corners[0].x < corners[1].x && corners[3].x < corners[2].x &&
            corners[0].y < corners[3].y && corners[1].y < corners[2].y
    }

    fun rowRanges(height: Int): List<IntRange> {
        require(isValid() && height >= 32)
        val cuts = listOf(0) + divisions.map { (height * it).toInt() } + height
        return cuts.zipWithNext().map { (top, bottom) -> top until bottom }
    }

    companion object {
        fun centered(width: Int, height: Int): VitalScanLayout {
            val columnWidth = min(width * 0.68f, height * 0.78f * 0.70f)
            val halfWidth = columnWidth / width / 2f
            val halfHeight = columnWidth / 0.70f / height / 2f
            return VitalScanLayout(listOf(
                VitalScanPoint(0.5f - halfWidth, 0.5f - halfHeight),
                VitalScanPoint(0.5f + halfWidth, 0.5f - halfHeight),
                VitalScanPoint(0.5f + halfWidth, 0.5f + halfHeight),
                VitalScanPoint(0.5f - halfWidth, 0.5f + halfHeight)
            ))
        }
    }
}
