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
    fun treatsTimestampsInTheSameMinuteAsTheSameVitalRecord() {
        val first = Instant.parse("2026-06-13T01:02:03Z")
        val latest = Instant.parse("2026-06-13T01:02:59Z")
        val measurements = latestVitalMeasurementsByMinute(
            listOf(
                VitalMeasurement(first, 120.0, 80.0, 70L),
                VitalMeasurement(latest, 121.0, 81.0, 71L)
            )
        )

        assertEquals(1, measurements.size)
        assertEquals(latest, measurements.single().measuredAt)
        assertEquals(121.0, measurements.single().systolic, 0.0)
    }

    @Test
    fun keepsVitalRecordsFromDifferentMinutes() {
        val first = Instant.parse("2026-06-13T01:02:59Z")
        val nextMinute = Instant.parse("2026-06-13T01:03:00Z")
        val measurements = latestVitalMeasurementsByMinute(
            listOf(
                VitalMeasurement(first, 120.0, 80.0, 70L),
                VitalMeasurement(nextMinute, 121.0, 81.0, 71L)
            )
        )

        assertEquals(listOf(first, nextMinute), measurements.map { it.measuredAt })
    }

    @Test
    fun considersVitalValuesEqualWhenOnlySecondsDiffer() {
        val source = VitalMeasurement(Instant.parse("2026-06-13T01:02:03Z"), 120.0, 80.0, 70L)
        val target = VitalMeasurement(Instant.parse("2026-06-13T01:02:59Z"), 120.0, 80.0, 70L)

        assertEquals(true, source.hasSameVitalValues(target))
    }

    @Test
    fun considersVitalValuesChangedWhenAnySyncedValueDiffers() {
        val source = VitalMeasurement(Instant.parse("2026-06-13T01:02:03Z"), 120.0, 80.0, 70L)

        assertEquals(false, source.hasSameVitalValues(source.copy(systolic = 121.0)))
        assertEquals(false, source.hasSameVitalValues(source.copy(diastolic = 81.0)))
        assertEquals(false, source.hasSameVitalValues(source.copy(heartRate = 71L)))
        assertEquals(false, source.hasSameVitalValues(source.copy(heartRate = null)))
    }

    @Test
    fun warnsWhenManualVitalBloodPressureIsReversed() {
        val warnings = vitalInputWarnings(
            measurement = VitalMeasurement(Instant.parse("2026-06-13T01:02:03Z"), 80.0, 120.0, 70L),
            recentMeasurements = emptyList()
        )

        assertEquals(listOf("最高血圧が最低血圧以下です。"), warnings)
    }

    @Test
    fun warnsWhenManualVitalDiffersFromRecentMedian() {
        val warnings = vitalInputWarnings(
            measurement = VitalMeasurement(Instant.parse("2026-06-13T01:02:03Z"), 165.0, 110.0, 115L),
            recentMeasurements = listOf(
                VitalMeasurement(Instant.parse("2026-06-10T01:02:03Z"), 120.0, 80.0, 70L),
                VitalMeasurement(Instant.parse("2026-06-11T01:02:03Z"), 122.0, 82.0, 72L),
                VitalMeasurement(Instant.parse("2026-06-12T01:02:03Z"), 124.0, 84.0, 74L)
            )
        )

        assertEquals(
            listOf(
                "最高血圧が最近の中央値122と大きく異なります。",
                "最低血圧が最近の中央値82と大きく異なります。",
                "脈拍が最近の中央値72と大きく異なります。"
            ),
            warnings
        )
    }
}
