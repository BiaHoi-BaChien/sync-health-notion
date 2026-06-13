package net.biahoi.stepnotionsync

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class StepMeasurementTest {
    @Test
    fun reverseSyncSkipsExistingAndDuplicateMeasurementTimes() {
        val existingTime = Instant.parse("2026-06-12T12:00:00Z")
        val newTime = Instant.parse("2026-06-13T12:00:00Z")
        val measurements = listOf(
            DailyStepMeasurement(LocalDate.parse("2026-06-12"), existingTime, 1_000),
            DailyStepMeasurement(LocalDate.parse("2026-06-13"), newTime, 2_000),
            DailyStepMeasurement(LocalDate.parse("2026-06-13"), newTime, 3_000)
        )

        val unsynced = selectUnsyncedStepMeasurements(measurements, setOf(existingTime))

        assertEquals(1, unsynced.size)
        assertEquals(newTime, unsynced.single().recordedAt)
        assertEquals(2_000, unsynced.single().steps)
    }
}
