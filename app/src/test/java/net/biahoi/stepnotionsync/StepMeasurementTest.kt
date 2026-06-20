package net.biahoi.stepnotionsync

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class StepMeasurementTest {
    @Test
    fun treatsStepDataAsEqualWhenMinuteAndCountMatch() {
        val date = LocalDate.parse("2026-06-13")
        val source = DailyStepMeasurement(date, Instant.parse("2026-06-13T12:00:03Z"), 2_000)
        val target = DailyStepMeasurement(date, Instant.parse("2026-06-13T12:00:59Z"), 2_000)

        assertEquals(true, source.hasSameStepData(target))
    }

    @Test
    fun treatsStepDataAsChangedWhenMinuteOrCountDiffers() {
        val date = LocalDate.parse("2026-06-13")
        val source = DailyStepMeasurement(date, Instant.parse("2026-06-13T12:00:03Z"), 2_000)

        assertEquals(false, source.hasSameStepData(source.copy(recordedAt = Instant.parse("2026-06-13T12:01:00Z"))))
        assertEquals(false, source.hasSameStepData(source.copy(steps = 2_001)))
    }
}
