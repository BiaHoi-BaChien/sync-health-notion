package net.biahoi.stepnotionsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VitalScanLayoutTest {
    @Test
    fun guidesWorkInPortraitLandscapeAndLargeWindows() {
        for ((width, height) in listOf(400 to 700, 800 to 240, 1920 to 300, 300 to 1000)) {
            val layout = VitalScanLayout.centered(width, height)
            assertTrue("$width x $height", layout.isValid())
            val ranges = layout.rowRanges(641)
            assertEquals((0 until 641).toList(), ranges.flatMap { it.toList() })
            assertEquals(3, ranges.size)
        }
    }

    @Test
    fun rejectCrossedCornersDegenerateShapesAndInvalidDividers() {
        val layout = VitalScanLayout.centered(400, 700)
        assertFalse(layout.copy(corners = layout.corners.reversed()).isValid())
        assertFalse(layout.copy(corners = List(4) { VitalScanPoint(0.5f, 0.5f) }).isValid())
        assertFalse(layout.copy(corners = layout.corners.toMutableList().apply { this[0] = VitalScanPoint(Float.NaN, 0f) }).isValid())
        for (divisions in listOf(listOf(0.7f, 0.3f), listOf(0.4f, 0.41f), listOf(-0.2f, 0.7f), listOf(Float.NaN, 0.7f))) {
            assertFalse(layout.copy(divisions = divisions).isValid())
        }
        for (inset in listOf(VitalScanInset(0.8f, 0.2f), VitalScanInset(0.5f, 0.51f),
            VitalScanInset(-0.1f, 1f), VitalScanInset(0f, Float.NaN))) {
            assertFalse(layout.copy(rowInsets = listOf(VitalScanInset(), VitalScanInset(), inset)).isValid())
        }
        assertTrue(layout.copy(rowInsets = listOf(VitalScanInset(), VitalScanInset(), VitalScanInset(0.45f, 0.97f))).isValid())
    }
}
