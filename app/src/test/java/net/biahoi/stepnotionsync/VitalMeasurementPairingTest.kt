package net.biahoi.stepnotionsync

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VitalMeasurementPairingTest {
    @Test
    fun pairsHeartRateOnlyWhenMeasurementTimeMatchesExactly() {
        val measuredAt = Instant.parse("2026-06-13T01:02:03Z")
        val measurements = pairVitalMeasurements(
            bloodPressures = listOf(
                BloodPressureMeasurement(measuredAt, systolic = 120.0, diastolic = 80.0)
            ),
            heartRatesByTime = mapOf(
                measuredAt to 70L,
                measuredAt.plusSeconds(1) to 99L
            )
        )

        assertEquals(1, measurements.size)
        assertEquals(70L, measurements.single().heartRate)
    }

    @Test
    fun ignoresHeartRateOnlyRecordsAndKeepsBloodPressureWithoutHeartRate() {
        val measuredAt = Instant.parse("2026-06-13T01:02:03Z")
        val measurements = pairVitalMeasurements(
            bloodPressures = listOf(
                BloodPressureMeasurement(measuredAt, systolic = 120.0, diastolic = 80.0)
            ),
            heartRatesByTime = mapOf(measuredAt.plusSeconds(1) to 70L)
        )

        assertEquals(1, measurements.size)
        assertNull(measurements.single().heartRate)
    }

    @Test
    fun returnsNoMeasurementForHeartRateOnlyRecords() {
        val measurements = pairVitalMeasurements(
            bloodPressures = emptyList(),
            heartRatesByTime = mapOf(Instant.parse("2026-06-13T01:02:03Z") to 70L)
        )

        assertEquals(emptyList<VitalMeasurement>(), measurements)
    }

    @Test
    fun skipsNotionTimestampsAndDuplicateHealthConnectTimestamps() {
        val existingTime = Instant.parse("2026-06-13T01:02:03Z")
        val newTime = existingTime.plusSeconds(60)
        val measurements = listOf(
            VitalMeasurement(existingTime, 120.0, 80.0, 70L),
            VitalMeasurement(newTime, 121.0, 81.0, 71L),
            VitalMeasurement(newTime, 122.0, 82.0, 72L)
        )

        val unsynced = selectUnsyncedVitalMeasurements(measurements, setOf(existingTime))

        assertEquals(1, unsynced.size)
        assertEquals(newTime, unsynced.single().measuredAt)
        assertEquals(121.0, unsynced.single().systolic, 0.0)
    }
}
