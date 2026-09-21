package net.biahoi.stepnotionsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SevenSegmentVitalReaderTest {
    @Test
    fun readsDifferentMeasurementsWithoutAssumingTypicalValues() {
        for (values in listOf(listOf("104", "71", "89"), listOf("120", "80", "65"), listOf("250", "160", "40"),
            listOf("138", "92", "76"), listOf("146", "83", "52"))) {
            assertEquals(values.toString(), SevenSegmentVitalResult.Recognized(VitalCameraReading(values[0].toInt(), values[1].toInt(), values[2].toInt())),
                readSevenSegmentVitals(display(values), WIDTH, HEIGHT))
        }
    }

    @Test
    fun rejectsMissingExtraAndInvalidRows() {
        for (values in listOf(listOf("120", "80"), listOf("120", "80", "65", "18"),
            listOf("80", "120", "65"), listOf("120", "80", "301"))) {
            assertNull(values.toString(), selectVitalCameraReading(null, readSevenSegmentVitals(display(values), WIDTH, HEIGHT)))
        }
    }

    @Test
    fun rejectsBlankAndUnsupportedImages() {
        assertEquals(SevenSegmentVitalResult.NotDetected, readSevenSegmentVitals(IntArray(WIDTH * HEIGHT) { 0xff999999.toInt() }, WIDTH, HEIGHT))
        assertEquals(SevenSegmentVitalResult.NotDetected, readSevenSegmentVitals(IntArray(10), WIDTH, HEIGHT))
        assertEquals(SevenSegmentVitalResult.NotDetected, readSevenSegmentVitals(IntArray(100), 10, 10))
    }

    @Test
    fun doesNotDropPartiallyVisibleLeadingDigit() {
        val pixels = display(listOf("180", "60", "65"))
        assertEquals(SevenSegmentVitalResult.Recognized(VitalCameraReading(180, 60, 65)),
            readSevenSegmentVitals(pixels, WIDTH, HEIGHT))
        // Glare hides the lower half of the leading 1, leaving its upper segment.
        for (y in 70 until 125) for (x in 78 until 108) pixels[y * WIDTH + x] = 0xffaaaaaa.toInt()
        val elements = listOf(
            VitalOcrElement("I", 78, 20, 108, 68),
            VitalOcrElement("80", 115, 20, 275, 122),
            VitalOcrElement("60", 115, 155, 275, 257),
            VitalOcrElement("65", 115, 290, 275, 392)
        )
        assertNull(parseVitalCameraReading(elements))
        val segments = readSevenSegmentVitals(pixels, WIDTH, HEIGHT)
        assertEquals(SevenSegmentVitalResult.Uncertain, segments)
        assertNull(selectVitalCameraReading(parseVitalCameraReading(elements), segments))
        assertNull(selectVitalCameraReading(VitalCameraReading(80, 60, 65), segments))
    }

    @Test
    fun conflictingContrastReadingsCannotBeOverriddenByOcr() {
        val pixels = display(listOf("189", "70", "60"))
        assertEquals(SevenSegmentVitalResult.Recognized(VitalCameraReading(189, 70, 60)),
            readSevenSegmentVitals(pixels, WIDTH, HEIGHT))
        // The faded upper-left segment makes the last digit alternate between 9 and 3.
        for (y in 12 until 48) for (x in 0 until 10) {
            pixels[(20 + y) * WIDTH + 200 + x + (102 - y) / 6] = 0xff6e6e6e.toInt()
        }
        val segments = readSevenSegmentVitals(pixels, WIDTH, HEIGHT)
        assertEquals(SevenSegmentVitalResult.Uncertain, segments)
        assertNull(selectVitalCameraReading(VitalCameraReading(183, 70, 60), segments))
        assertNull(selectVitalCameraReading(VitalCameraReading(189, 70, 60), segments))
        assertNull(selectVitalCameraReading(null, segments))
    }

    private fun display(rows: List<String>): IntArray {
        val pixels = IntArray(WIDTH * HEIGHT) { 0xffaaaaaa.toInt() }
        val digits = mapOf('0' to "abcdef", '1' to "bc", '2' to "abdeg", '3' to "abcdg", '4' to "bcfg",
            '5' to "acdfg", '6' to "acdefg", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg")
        val segments = mapOf(
            'a' to intArrayOf(10, 0, 50, 10), 'b' to intArrayOf(50, 12, 60, 48),
            'c' to intArrayOf(50, 54, 60, 90), 'd' to intArrayOf(10, 92, 50, 102),
            'e' to intArrayOf(0, 54, 10, 90), 'f' to intArrayOf(0, 12, 10, 48),
            'g' to intArrayOf(10, 46, 50, 56)
        )
        for ((row, value) in rows.withIndex()) for ((column, digit) in value.withIndex()) {
            val left = 30 + (3 - value.length + column) * 85
            val top = 20 + row * 135
            for (segment in digits.getValue(digit)) {
                val box = segments.getValue(segment)
                for (y in box[1] until box[3]) for (x in box[0] until box[2]) {
                    val px = left + x + (102 - y) / 6
                    val py = top + y
                    if (py < HEIGHT) pixels[py * WIDTH + px] = 0xff222222.toInt()
                }
            }
        }
        return pixels
    }

    companion object {
        private const val WIDTH = 300
        private const val HEIGHT = 570
    }
}
