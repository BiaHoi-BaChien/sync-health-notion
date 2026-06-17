package net.biahoi.stepnotionsync

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class NotionDateConversionTest {
    @Test
    fun convertsInstantToNotionDateTimeWithoutChangingInstant() {
        val measuredAt = Instant.parse("2026-06-13T01:02:03Z")

        val notionDateTime = measuredAt.toNotionDateTime()

        assertEquals(measuredAt, Instant.parse(notionDateTime))
    }

    @Test
    fun convertsNotionDateTimeAndDateOnlyValuesToDateValue() {
        val dateTime = "2026-06-13T01:02:03Z".toNotionDateValue()
        val dateOnly = "2026-06-13".toNotionDateValue()

        assertEquals(NotionDateValue(LocalDate.parse("2026-06-13"), Instant.parse("2026-06-13T01:02:03Z")), dateTime)
        assertEquals(NotionDateValue(LocalDate.parse("2026-06-13"), null), dateOnly)
    }

    @Test
    fun missingNotionTimestampSortsOlderThanRealTimestamp() {
        val timestamp = Instant.parse("2026-06-13T01:02:03Z")

        assertEquals(Instant.EPOCH, notionTimestampSortValue(null))
        assertEquals(timestamp, notionTimestampSortValue(timestamp))
    }
}
