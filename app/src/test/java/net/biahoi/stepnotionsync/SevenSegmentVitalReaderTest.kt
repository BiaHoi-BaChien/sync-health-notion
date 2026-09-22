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

    @Test
    fun readsCloseRowsWithHorizontalFrameAndSmallFaintPulse() {
        val pixels = display(listOf("110", "74", "77"), compact = true)
        assertEquals(SevenSegmentVitalResult.Recognized(VitalCameraReading(110, 74, 77)),
            readSevenSegmentVitals(pixels, WIDTH, HEIGHT))
    }

    @Test
    fun doesNotDropPartialLeadingDigitInCloseRows() {
        val pixels = display(listOf("180", "60", "65"), compact = true)
        assertEquals(SevenSegmentVitalResult.Recognized(VitalCameraReading(180, 60, 65)),
            readSevenSegmentVitals(pixels, WIDTH, HEIGHT))
        for (y in 70 until 125) for (x in 78 until 108) pixels[y * WIDTH + x] = 0xffaaaaaa.toInt()
        val segments = readSevenSegmentVitals(pixels, WIDTH, HEIGHT)
        assertEquals(SevenSegmentVitalResult.Uncertain, segments)
        assertNull(selectVitalCameraReading(VitalCameraReading(80, 60, 65), segments))
    }

    @Test
    fun rejectsNarrowLeadingDigitFragmentsAtFrameEdge() {
        for (compact in listOf(false, true)) {
            val original = display(listOf("180", "60", "65"), compact)
            for (shift in listOf(98, 100, 102, 103)) {
                val pixels = IntArray(WIDTH * HEIGHT) { 0xffaaaaaa.toInt() }
                for (y in 0 until HEIGHT) {
                    original.copyInto(pixels, y * WIDTH, y * WIDTH + shift, (y + 1) * WIDTH)
                }
                // The leading 1 still has a 2..7 px wide stroke touching the left edge.
                val segments = readSevenSegmentVitals(pixels, WIDTH, HEIGHT)
                assertEquals("compact=$compact shift=$shift", SevenSegmentVitalResult.Uncertain, segments)
                assertNull(selectVitalCameraReading(null, segments))
                assertNull(selectVitalCameraReading(VitalCameraReading(80, 60, 65), segments))
            }
        }
    }

    @Test
    fun rejectsNarrowTrailingFragmentsAtFrameEdge() {
        for (compact in listOf(false, true)) for (fragmentWidth in listOf(1, 3, 7)) {
            val pixels = display(listOf("180", "60", "65"), compact)
            for (y in 32 until 68) for (x in WIDTH - fragmentWidth until WIDTH) {
                pixels[y * WIDTH + x] = 0xff222222.toInt()
            }
            val segments = readSevenSegmentVitals(pixels, WIDTH, HEIGHT)
            assertEquals("compact=$compact width=$fragmentWidth", SevenSegmentVitalResult.Uncertain, segments)
            assertNull(selectVitalCameraReading(null, segments))
            assertNull(selectVitalCameraReading(VitalCameraReading(180, 60, 65), segments))
        }
    }

    @Test
    fun rejectsLeadingDigitFragmentsConnectedToFrameLine() {
        for (compact in listOf(false, true)) for (shift in listOf(94, 96, 98, 100, 102, 103)) {
            val original = display(listOf("180", "60", "65"), compact)
            for (lineWidth in listOf(1, 2, 3, 6)) {
                val pixels = IntArray(WIDTH * HEIGHT) { 0xffaaaaaa.toInt() }
                for (y in 0 until HEIGHT) original.copyInto(pixels, y * WIDTH, y * WIDTH + shift, (y + 1) * WIDTH)
                // A long LCD edge must not hide an attached fragment of the leading 1.
                for (y in 20 until 400) for (x in 0 until lineWidth) pixels[y * WIDTH + x] = 0xff222222.toInt()
                val segments = readSevenSegmentVitals(pixels, WIDTH, HEIGHT)
                assertEquals("compact=$compact shift=$shift line=$lineWidth", SevenSegmentVitalResult.Uncertain, segments)
                assertNull(selectVitalCameraReading(null, segments))
                assertNull(selectVitalCameraReading(VitalCameraReading(80, 60, 65), segments))
            }
        }
    }

    @Test
    fun stillReadsCompleteLeadingDigitNearFrameEdge() {
        for (compact in listOf(false, true)) {
            val original = display(listOf("180", "60", "65"), compact)
            val pixels = IntArray(WIDTH * HEIGHT) { 0xffaaaaaa.toInt() }
            for (y in 0 until HEIGHT) original.copyInto(pixels, y * WIDTH, y * WIDTH + 80, (y + 1) * WIDTH)
            assertEquals("compact=$compact", SevenSegmentVitalResult.Recognized(VitalCameraReading(180, 60, 65)),
                readSevenSegmentVitals(pixels, WIDTH, HEIGHT))
        }
    }

    @Test
    fun stillIgnoresSparseEdgeSpeckles() {
        for (compact in listOf(false, true)) {
            val pixels = display(listOf("180", "60", "65"), compact)
            for (y in 20..120 step 20) for (x in listOf(0, WIDTH - 1)) pixels[y * WIDTH + x] = 0xff222222.toInt()
            assertEquals("compact=$compact", SevenSegmentVitalResult.Recognized(VitalCameraReading(180, 60, 65)),
                readSevenSegmentVitals(pixels, WIDTH, HEIGHT))
        }
    }

    @Test
    fun doesNotDiscardFourthRowBesideSmallFaintPulse() {
        val pixels = display(listOf("110", "74", "77", "18"), compact = true)
        val segments = readSevenSegmentVitals(pixels, WIDTH, HEIGHT)
        assertEquals(SevenSegmentVitalResult.Uncertain, segments)
        assertNull(selectVitalCameraReading(null, segments))
        assertNull(selectVitalCameraReading(VitalCameraReading(110, 74, 77), segments))
    }

    @Test
    fun extraDigitRowCannotBeOverriddenByOcr() {
        for (compact in listOf(false, true)) for (values in listOf(
            listOf("120", "80", "65", "18"), listOf("250", "160", "65", "202")
        )) {
            val pixels = display(values, compact)
            val segments = readSevenSegmentVitals(pixels, WIDTH, HEIGHT)
            assertEquals("compact=$compact values=$values", SevenSegmentVitalResult.Uncertain, segments)
            assertNull(selectVitalCameraReading(null, segments))
            // OCR can omit the date/memory row while finding three otherwise valid values.
            assertNull(selectVitalCameraReading(VitalCameraReading(values[0].toInt(), values[1].toInt(), values[2].toInt()), segments))
        }
    }

    @Test
    fun fullyDecodedInvalidValuesCannotBeOverriddenByOcr() {
        for (compact in listOf(false, true)) {
            for (values in listOf(listOf("80", "120", "65"), listOf("120", "80", "301"))) {
                val segments = readSevenSegmentVitals(display(values, compact), WIDTH, HEIGHT)
                assertEquals("compact=$compact values=$values", SevenSegmentVitalResult.Uncertain, segments)
                assertNull(selectVitalCameraReading(null, segments))
                assertNull(selectVitalCameraReading(VitalCameraReading(120, 80, 65), segments))
            }
        }
    }

    private fun display(rows: List<String>, compact: Boolean = false): IntArray {
        val pixels = IntArray(WIDTH * HEIGHT) { 0xffaaaaaa.toInt() }
        if (compact) {
            // Full-width LCD edges and an eight-pixel blood-pressure row gap.
            for (y in (4 until 12) + (432 until 440)) for (x in 0 until WIDTH) {
                pixels[y * WIDTH + x] = 0xff222222.toInt()
            }
        }
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
            val top = if (compact) listOf(20, 130, 248, 330)[row] else 20 + row * 135
            val scale = if (compact && row >= 2) 2.0 / 3 else 1.0
            val color = if (compact && row >= 2) 0xff666666.toInt() else 0xff222222.toInt()
            // Microlife's 7 also lights the upper-left segment.
            val litSegments = if (compact && digit == '7') "abcf" else digits.getValue(digit)
            for (segment in litSegments) {
                val box = segments.getValue(segment).copyOf()
                // LCD vertical strokes reach the baseline even without a bottom segment.
                if (compact && segment in "ce") box[3] = 102
                if (compact && row >= 2 && segment in "bcef") {
                    // Preserve the visibly thick strokes of the smaller pulse digits.
                    if (segment in "bc") box[0] = 44 else box[2] = 16
                }
                for (y in box[1] until box[3]) for (x in box[0] until box[2]) {
                    val px = (275 + (left + x + (102 - y) / 6 - 275) * scale).toInt()
                    val py = top + (y * scale).toInt()
                    if (py < HEIGHT) pixels[py * WIDTH + px] = color
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
