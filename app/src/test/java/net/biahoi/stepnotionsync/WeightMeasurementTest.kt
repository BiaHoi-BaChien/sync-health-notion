package net.biahoi.stepnotionsync

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WeightMeasurementTest {
    @Test
    fun treatsWeightDataAsEqualWhenMinuteAndValueMatch() {
        val source = WeightMeasurement(Instant.parse("2026-06-13T01:02:03Z"), 61.25)
        val target = WeightMeasurement(Instant.parse("2026-06-13T01:02:59Z"), 61.25)

        assertEquals(true, source.hasSameWeightData(target))
    }

    @Test
    fun treatsWeightDataAsChangedWhenMinuteOrValueDiffers() {
        val source = WeightMeasurement(Instant.parse("2026-06-13T01:02:03Z"), 61.25)

        assertEquals(false, source.hasSameWeightData(source.copy(measuredAt = Instant.parse("2026-06-13T01:03:00Z"))))
        assertEquals(false, source.hasSameWeightData(source.copy(kilograms = 61.3)))
    }

    @Test
    fun keepsLatestWeightMeasurementWithinTheSameMinute() {
        val first = WeightMeasurement(Instant.parse("2026-06-13T01:02:03Z"), 61.2)
        val latest = WeightMeasurement(Instant.parse("2026-06-13T01:02:59Z"), 61.3)

        assertEquals(listOf(latest), latestWeightMeasurementsByMinute(listOf(first, latest)))
    }

    @Test
    fun formatsConfiguredSyncCountsByItem() {
        val result = SyncResultCounts(steps = 2, vitals = null, weight = 1)

        assertEquals("歩数2件、体重1件を同期しました。", result.toDisplayMessage())
    }

    @Test
    fun formatsOnlyDestinationWritesForConfiguredSyncCounts() {
        val result = SyncResultCounts(steps = 0, vitals = 2, weight = 0)

        assertEquals("バイタル2件を同期しました。", result.toDisplayMessage())
    }

    @Test
    fun reportsAlreadyCurrentWhenAllConfiguredSyncCountsAreZero() {
        val result = SyncResultCounts(steps = 0, vitals = 0, weight = 0)

        assertEquals("すでに最新です。", result.toDisplayMessage())
    }

    @Test
    fun formatsIndividualDestinationWriteCount() {
        assertEquals("歩数データを2件同期しました。", syncCountMessage("歩数データ", 2))
        assertEquals("すでに最新です。", syncCountMessage("歩数データ", 0))
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

    @Test
    fun warnsWhenManualWeightDiffersFromRecentMedian() {
        val warnings = weightInputWarnings(
            measurement = WeightMeasurement(Instant.parse("2026-06-13T01:02:03Z"), 68.2),
            recentMeasurements = listOf(
                WeightMeasurement(Instant.parse("2026-06-10T01:02:03Z"), 61.0),
                WeightMeasurement(Instant.parse("2026-06-11T01:02:03Z"), 61.2),
                WeightMeasurement(Instant.parse("2026-06-12T01:02:03Z"), 61.4)
            )
        )

        assertEquals(listOf("体重が最近の中央値61.2kgと大きく異なります。"), warnings)
    }
}
