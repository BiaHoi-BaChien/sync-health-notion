package net.biahoi.stepnotionsync

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WeightMeasurementTest {
    @Test
    fun skipsNotionTimestampsAndDuplicateHealthConnectTimestamps() {
        val existingTime = Instant.parse("2026-06-13T01:02:03Z")
        val newTime = existingTime.plusSeconds(60)
        val measurements = listOf(
            WeightMeasurement(existingTime, 60.0),
            WeightMeasurement(newTime, 61.25),
            WeightMeasurement(newTime, 62.0)
        )

        val unsynced = selectUnsyncedWeightMeasurements(measurements, setOf(existingTime))

        assertEquals(1, unsynced.size)
        assertEquals(newTime, unsynced.single().measuredAt)
        assertEquals(61.25, unsynced.single().kilograms, 0.0)
    }

    @Test
    fun formatsConfiguredSyncCountsByItem() {
        val result = SyncResultCounts(steps = 2, vitals = null, weight = 1)

        assertEquals("歩数2件、体重1件を同期しました。", result.toDisplayMessage())
    }

    @Test
    fun formatsAllConfiguredSyncCountsIncludingZero() {
        val result = SyncResultCounts(steps = 0, vitals = 2, weight = 0)

        assertEquals("歩数0件、バイタル2件、体重0件を同期しました。", result.toDisplayMessage())
    }

    @Test
    fun acceptsManualWeightWithOneDecimalPlace() {
        assertEquals(61.2, parseManualWeight("61.2"), 0.0)
        assertEquals(61.0, parseManualWeight("61"), 0.0)
        assertEquals(61.2, parseManualWeight(" 61.2 "), 0.0)
    }

    @Test
    fun rejectsManualWeightWithMoreThanOneDecimalPlace() {
        assertThrows(IllegalArgumentException::class.java) {
            parseManualWeight("61.25")
        }
    }

    @Test
    fun rejectsManualWeightThatIsBlankZeroNegativeOrNonNumeric() {
        listOf("", "0", "-1", "61,2", "61 kg").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                parseManualWeight(value)
            }
        }
    }

    @Test
    fun formatsVoiceWeightToOneDecimalPlace() {
        assertEquals("61.3", formatManualWeight(61.25))
        assertEquals("61.0", formatManualWeight(61.0))
    }
}
