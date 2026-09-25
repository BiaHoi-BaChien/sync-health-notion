package net.biahoi.stepnotionsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticVitalRowsTest {
    private val numbers = listOf(
        VitalOcrElement("109", 260, 150, 550, 280),
        VitalOcrElement("72", 360, 330, 550, 460),
        VitalOcrElement("78", 395, 515, 550, 600)
    )

    @Test
    fun locatesThreeRowsAnywhereInThePhotoWithSmallerPulseAndSurroundingText() {
        for ((dx, dy) in listOf(0 to 0, 220 to 110)) {
            val shifted = numbers.map { it.copy(left = it.left + dx, right = it.right + dx, top = it.top + dy, bottom = it.bottom + dy) }
            val elements = shifted.reversed() + listOf(
                VitalOcrElement("microlife", 200, 20, 480, 65),
                VitalOcrElement("SYS.", 630, 190, 680, 210),
                VitalOcrElement("12:35", 330, 900, 400, 918)
            )
            val rows = automaticVitalRows(elements, 1200, 1200)
            assertNotNull(rows)
            assertEquals(3, rows!!.size)
            rows.zip(shifted).forEach { (region, digits) ->
                assertTrue(region.left < digits.left && region.right > digits.right)
                assertTrue(region.top < digits.top && region.bottom > digits.bottom)
            }
            assertTrue(rows.zipWithNext().all { (a, b) -> a.bottom <= b.top })
        }
    }

    @Test
    fun keepsContextForLeadingDigitsThatFullPhotoOcrMayMiss() {
        val initial = numbers.toMutableList().apply { this[0] = this[0].copy(text = "09", left = 390) }
        val rows = automaticVitalRows(initial, 900, 1200)!!
        assertTrue(rows[0].left < numbers[0].left)
        val split = initial + VitalOcrElement("I", 260, 160, 285, 220)
        val splitRows = automaticVitalRows(split, 900, 1200)!!
        assertEquals(setOf("I", "09"), elementsInVitalRegion(split, splitRows[0]).map { it.text }.toSet())
    }

    @Test
    fun rejectsMissingExtraOverlappingAndSeparateColumns() {
        assertNull(automaticVitalRows(numbers.take(2), 900, 1200))
        assertNull(automaticVitalRows(numbers + VitalOcrElement("18", 390, 710, 550, 810), 900, 1200))
        assertNull(automaticVitalRows(numbers.mapIndexed { index, e -> if (index == 1) e.copy(top = 200) else e }, 900, 1200))
        assertNull(automaticVitalRows(numbers.mapIndexed { index, e -> if (index == 2) e.copy(left = 700, right = 820) else e }, 900, 1200))
        assertNull(automaticVitalRows(numbers + numbers.map { it.copy(left = it.left + 700, right = it.right + 700) }, 1800, 1200))
    }

    @Test
    fun invalidCoordinatesAndTinyDetectionsDoNotCreateCrops() {
        assertNull(automaticVitalRows(emptyList(), 900, 1200))
        assertNull(automaticVitalRows(numbers, 0, 0))
        for (invalid in listOf(numbers[0].copy(left = -1), numbers[0].copy(right = 901),
            numbers[0].copy(top = 280), numbers[0].copy(bottom = 1201))) {
            assertNull(automaticVitalRows(listOf(invalid) + numbers.drop(1), 900, 1200))
        }
        assertNull(automaticVitalRows(numbers.map { it.copy(bottom = it.top + 10) }, 900, 1200))
    }

    @Test
    fun retainsSignsAndFragmentsThatStraddleAnAutomaticCrop() {
        val region = VitalImageRegion(100, 100, 400, 300)
        val sign = VitalOcrElement("-", 90, 150, 110, 160)
        val elements = elementsInVitalRegion(listOf(sign, VitalOcrElement("120", 150, 120, 290, 270)), region)
        assertEquals(-10, elements[0].left)
        assertNull(selectAutomaticVitalCameraNumber(elements, elements.drop(1), 300, 200, SevenSegmentNumberResult.Recognized(120)))
    }

    @Test
    fun croppingCannotOverrideConflictingOcrOrPixelEvidence() {
        fun ocr(value: String) = listOf(VitalOcrElement(value, 30, 20, 180, 150))
        val pixels = SevenSegmentNumberResult.Recognized(180)
        assertEquals(180, selectAutomaticVitalCameraNumber(ocr("180"), ocr("180"), 300, 200, pixels))
        assertNull(selectAutomaticVitalCameraNumber(ocr("180"), ocr("80"), 300, 200, pixels))
        assertNull(selectAutomaticVitalCameraNumber(ocr("80"), ocr("180"), 300, 200, pixels))
        assertNull(selectAutomaticVitalCameraNumber(ocr("180"), ocr("180"), 300, 200, SevenSegmentNumberResult.Uncertain))
        assertNull(selectAutomaticVitalCameraNumber(ocr("180"), ocr("80"), 300, 200, SevenSegmentNumberResult.NotDetected))
        val split = ocr("80") + VitalOcrElement("I", 10, 20, 20, 90)
        assertNull(selectAutomaticVitalCameraNumber(split, ocr("80"), 300, 200, SevenSegmentNumberResult.NotDetected))
    }

    @Test
    fun ocrOnlyRowsRequireCorrespondingMeasurementLabels() {
        val content = VitalImageRegion(180, 0, 720, 1200)
        val labels = listOf(
            VitalOcrElement("SYS.", 640, 200, 690, 220),
            VitalOcrElement("DIA.", 640, 375, 690, 395),
            VitalOcrElement("PUL.", 640, 550, 690, 570)
        )
        fun locate(elements: List<VitalOcrElement>) = labelledAutomaticVitalRows(elements, 900, 1200, content)
        val rows = locate(numbers + labels)!!
        assertTrue(rows.all { it.left >= content.left && it.right <= content.right })
        assertNull(locate(numbers))
        assertNull(locate(numbers + labels.dropLast(1)))
        assertNull(locate(numbers + labels + labels.last()))
        assertNull(locate(numbers + labels.map { if (it.text == "PUL.") it.copy(top = 750, bottom = 770) else it }))
        // A missed pulse must not let a lower memory number inherit the pulse label.
        assertNull(locate(numbers.take(2) + numbers.last().copy(text = "18", top = 730, bottom = 815) + labels))
        assertNull(locate(numbers + labels + VitalOcrElement("18", 470, 750, 550, 790)))
        assertNull(locate(numbers + labels + VitalOcrElement("12:30", 460, 850, 550, 870)))
    }

    @Test
    fun wholeImagePixelsRequireSpatialAgreementWithEveryAvailableOcrNumber() {
        val display = SevenSegmentDisplay(SevenSegmentVitalResult.Recognized(VitalCameraReading(109, 72, 78)), numbers)
        for (omitted in numbers.indices) {
            assertEquals(VitalCameraReading(109, 72, 78), selectWholeImageVitalReading(numbers.filterIndexed { index, _ -> index != omitted }, display))
        }
        assertEquals(VitalCameraReading(109, 72, 78), selectWholeImageVitalReading(emptyList(), display))
        for (text in listOf("79", "-78", "7.8", "7:8")) {
            assertNull(text, selectWholeImageVitalReading(numbers.dropLast(1) + numbers.last().copy(text = text), display))
        }
        assertNull(selectWholeImageVitalReading(listOf(numbers[1].copy(text = "78"), numbers[2].copy(text = "72")), display))
        assertNull(selectWholeImageVitalReading(numbers + VitalOcrElement("18", 395, 710, 550, 795), display))
        assertNull(selectWholeImageVitalReading(numbers + numbers.first().copy(text = "108"), display))
        assertNull(selectWholeImageVitalReading(numbers + VitalOcrElement("PUL.", 640, 375, 690, 395), display))
        assertEquals(VitalCameraReading(109, 72, 78),
            selectWholeImageVitalReading(numbers + VitalOcrElement("PUL.", 640, 550, 690, 570), display))
        assertNull(selectWholeImageVitalReading(numbers, SevenSegmentDisplay(SevenSegmentVitalResult.Uncertain)))
        assertNull(selectWholeImageVitalReading(numbers, SevenSegmentDisplay(SevenSegmentVitalResult.NotDetected)))
    }
}
