package net.biahoi.stepnotionsync

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityRemediationTest {
    private val measuredAt = Instant.parse("2026-08-03T01:02:03Z")

    @Test
    fun acceptsOnlyHealthConnectRecordsOwnedByThisApplication() {
        assertTrue(isOwnedHealthConnectRecord("com.example.app", "com.example.app"))
        assertFalse(isOwnedHealthConnectRecord("com.other.app", "com.example.app"))
        assertEquals(
            listOf("com.example.app", "com.example.app"),
            listOf("com.example.app", "com.other.app", "com.example.app")
                .ownedByApplication("com.example.app") { it }
        )
        assertEquals(
            emptyList<String>(),
            listOf("com.other.app").ownedByApplication("com.example.app") { it }
        )
    }

    @Test
    fun acceptsNotionVitalsWithinHealthConnectConstraints() {
        val lowerBoundary = notionVitalMeasurementOrNull(
            measuredAt = measuredAt,
            systolic = 20.0,
            diastolic = 10.0,
            heartRate = 1.0
        )
        val upperBoundary = notionVitalMeasurementOrNull(
            measuredAt = measuredAt,
            systolic = 200.0,
            diastolic = 180.0,
            heartRate = 300.0
        )

        assertNotNull(lowerBoundary)
        assertEquals(1L, lowerBoundary?.heartRate)
        assertNotNull(upperBoundary)
        assertEquals(300L, upperBoundary?.heartRate)
    }

    @Test
    fun rejectsNotionVitalsThatCannotBeSafelyWrittenToHealthConnect() {
        val invalidValues = listOf(
            notionVitalMeasurementOrNull(measuredAt, 19.9, 10.0, 70.0),
            notionVitalMeasurementOrNull(measuredAt, 200.1, 80.0, 70.0),
            notionVitalMeasurementOrNull(measuredAt, 120.0, 9.9, 70.0),
            notionVitalMeasurementOrNull(measuredAt, 120.0, 180.1, 70.0),
            notionVitalMeasurementOrNull(measuredAt, 120.0, 80.0, 0.0),
            notionVitalMeasurementOrNull(measuredAt, 120.0, 80.0, 300.1),
            notionVitalMeasurementOrNull(measuredAt, 120.0, 80.0, 70.5),
            notionVitalMeasurementOrNull(measuredAt, 80.0, 80.0, 70.0),
            notionVitalMeasurementOrNull(measuredAt, Double.NaN, 80.0, 70.0)
        )

        assertTrue(invalidValues.all { it == null })
    }

    @Test
    fun acceptsMissingHeartRateWithoutReinterpretingIt() {
        val measurement = notionVitalMeasurementOrNull(measuredAt, 120.0, 80.0, null)

        assertNotNull(measurement)
        assertNull(measurement?.heartRate)
    }

    @Test
    fun validatesNotionWeightAgainstHealthConnectConstraints() {
        assertNotNull(notionWeightMeasurementOrNull(measuredAt, 0.1))
        assertNotNull(notionWeightMeasurementOrNull(measuredAt, 1000.0))
        assertNull(notionWeightMeasurementOrNull(measuredAt, 0.0))
        assertNull(notionWeightMeasurementOrNull(measuredAt, -0.1))
        assertNull(notionWeightMeasurementOrNull(measuredAt, 1000.1))
        assertNull(notionWeightMeasurementOrNull(measuredAt, Double.POSITIVE_INFINITY))
    }

    @Test
    fun acceptsValidNotionPaginationProgress() {
        val cursor = validateNotionMeasurementPage(
            pageNumber = 1,
            currentRowCount = 0,
            pageRowCount = 100,
            hasMore = true,
            nextCursor = "cursor-1",
            seenCursors = emptySet(),
            maxPages = 2,
            maxRows = 200
        )

        assertEquals("cursor-1", cursor)
        assertNull(
            validateNotionMeasurementPage(
                pageNumber = 2,
                currentRowCount = 100,
                pageRowCount = 100,
                hasMore = false,
                nextCursor = null,
                seenCursors = setOf("cursor-1"),
                maxPages = 2,
                maxRows = 200
            )
        )
    }

    @Test
    fun rejectsUnboundedOrCyclicNotionPaginationAsNonRetryable() {
        val failures = listOf(
            assertThrows(NotionSyncDataException::class.java) {
                validateNotionMeasurementPage(2, 100, 100, true, "cursor-2", setOf("cursor-1"), 2, 300)
            },
            assertThrows(NotionSyncDataException::class.java) {
                validateNotionMeasurementPage(1, 100, 101, false, null, emptySet(), 2, 200)
            },
            assertThrows(NotionSyncDataException::class.java) {
                validateNotionMeasurementPage(1, 0, 100, true, "cursor-1", setOf("cursor-1"), 2, 200)
            },
            assertThrows(NotionSyncDataException::class.java) {
                validateNotionMeasurementPage(1, 0, 100, true, null, emptySet(), 2, 200)
            }
        )

        assertTrue(failures.all { !isRetryableAutoSyncError(it) })
    }
}
