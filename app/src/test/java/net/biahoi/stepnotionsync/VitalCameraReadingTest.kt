package net.biahoi.stepnotionsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VitalCameraReadingTest {
    @Test
    fun acceptsEitherEngineButRejectsConflictingCompleteReadings() {
        val value = VitalCameraReading(104, 71, 89)
        val recognized = SevenSegmentVitalResult.Recognized(value)
        assertEquals(value, selectVitalCameraReading(null, recognized))
        assertEquals(value, selectVitalCameraReading(value, SevenSegmentVitalResult.NotDetected))
        assertEquals(value, selectVitalCameraReading(value, recognized))
        assertNull(selectVitalCameraReading(value.copy(systolic = 184), recognized))
        assertNull(selectVitalCameraReading(null, SevenSegmentVitalResult.NotDetected))
    }

    @Test
    fun uncertainPixelReadingRequiresRetryEvenWhenOcrSucceeds() {
        assertNull(selectVitalCameraReading(VitalCameraReading(183, 70, 60), SevenSegmentVitalResult.Uncertain))
        assertNull(selectVitalCameraReading(null, SevenSegmentVitalResult.Uncertain))
    }

    @Test
    fun doesNotBypassDatesTimesDecimalsAndSignsUsingThePixelFallback() {
        for (text in listOf("12.0", ".", "12:00", "9/18", "-120", "+120", "−", "120,0")) {
            assertFalse(text, allowsSevenSegmentFallback(column(text, "71", "89")))
        }
        assertTrue(allowsSevenSegmentFallback(column("I04", "7I", "89", "SYS.", "/min.")))
    }

    @Test
    fun readsNumbersInScreenOrderInsteadOfOcrBlockOrder() {
        assertEquals(VitalCameraReading(120, 80, 65), parseVitalCameraReading(listOf(
            number("65", 200), number("120", 0), number("80", 100)
        )))
    }

    @Test
    fun acceptsFullWidthDigitsAndSeparateUnitLabels() {
        assertEquals(VitalCameraReading(120, 80, 65), parseVitalCameraReading(listOf(
            number("１２０", 0), number("８０", 100), number("６５", 200),
            VitalOcrElement("SYS", 0, 0, 20, 30),
            VitalOcrElement("dia", 0, 100, 20, 130),
            VitalOcrElement("ＰＵＬ", 0, 200, 20, 230),
            VitalOcrElement("mmHg", 100, 100, 140, 130),
            VitalOcrElement("/min", 100, 200, 140, 230)
        )))
    }

    @Test
    fun doesNotTreatUnusualReadingsAsOcrMistakes() {
        assertEquals(VitalCameraReading(250, 160, 40), parseVitalCameraReading(column("250", "160", "40")))
    }

    @Test
    fun acceptsMicrolifeLabelsWithTrailingPeriods() {
        assertEquals(VitalCameraReading(104, 71, 89), parseVitalCameraReading(
            column("104", "71", "89") + listOf("SYS.", "DIA.", "PUL.", "/min.").map {
                VitalOcrElement(it, 100, 0, 140, 30)
            }
        ))
        assertNull(parseVitalCameraReading(column("104.", "71", "89")))
        assertNull(parseVitalCameraReading(column("104", "71", "89") + number("I.", 0)))
    }

    @Test
    fun rejectsMissingExtraAndSplitNumbers() {
        assertNull(parseVitalCameraReading(column("120", "80")))
        assertNull(parseVitalCameraReading(column("120", "80", "65", "18")))
        assertNull(parseVitalCameraReading(column("1", "20", "80", "65")))
        assertNull(parseVitalCameraReading(column("12080", "65")))
    }

    @Test
    fun rejectsSplitLeadingDigitRecognizedAsLetter() {
        for (glyph in listOf("I", "l", "|", "Ⅰ", "Ｉ")) {
            assertNull(glyph, parseVitalCameraReading(listOf(
                VitalOcrElement(glyph, 20, 0, 35, 40),
                number("80", 0), number("60", 100), number("65", 200)
            )))
        }
    }

    @Test
    fun rejectsSplitTrailingDigitRecognizedAsLetter() {
        for (glyph in listOf("O", "o", "Ｏ")) {
            assertNull(glyph, parseVitalCameraReading(listOf(
                number("120", 0),
                VitalOcrElement("8", 40, 100, 65, 140),
                VitalOcrElement(glyph, 70, 100, 90, 140),
                number("65", 200)
            )))
        }
    }

    @Test
    fun rejectsDatesTimesDecimalsSignsAndUncertainGlyphs() {
        for (uncertain in listOf("12:00", "9/18", "120.0", "-120", "+120", "1 20", "I20", "12O", "0120", "0")) {
            assertNull(uncertain, parseVitalCameraReading(column(uncertain, "80", "65")))
        }
        assertNull(parseVitalCameraReading(column("120", "80", "65") + number("-", 0)))
    }

    @Test
    fun rejectsAmbiguousLayoutAndInvalidMeasurements() {
        assertNull(parseVitalCameraReading(listOf(number("120", 0), number("80", 10), number("65", 200))))
        assertNull(parseVitalCameraReading(listOf(number("120", 0), number("80", 100).copy(left = 200, right = 250), number("65", 200))))
        assertNull(parseVitalCameraReading(column("80", "120", "65")))
        assertNull(parseVitalCameraReading(column("80", "80", "65")))
        assertNull(parseVitalCameraReading(column("120", "80", "301")))
    }

    private fun column(vararg values: String) = values.mapIndexed { index, value -> number(value, index * 100) }

    private fun number(value: String, top: Int) = VitalOcrElement(value, 40, top, 90, top + 40)
}
