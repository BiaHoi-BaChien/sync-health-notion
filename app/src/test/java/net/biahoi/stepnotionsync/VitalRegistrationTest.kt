package net.biahoi.stepnotionsync

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class VitalRegistrationTest {
    private val now = Instant.parse("2026-10-13T03:00:00.123456Z")

    @Test
    fun retryAfterWriteResponseIsCancelledDoesNotCreateAnotherMeasurement() {
        val stored = mutableMapOf<String, Record>()
        val attempt = VitalEntryDraft(listOf("128", "76", "62"))
            .prepareRegistration(now).copy(writeAttempted = true)

        assertThrows(CancellationException::class.java) {
            runBlocking {
                val registrationJob = this
                withContext(Dispatchers.IO) {
                    store(stored, attempt.records())
                    registrationJob.cancel()
                }
                error("A cancelled write response must not be reported as successful")
            }
        }

        val retry = attempt.withRegistrationFailure("登録処理を中止しました。")
            .prepareRegistration(now.plusSeconds(30))
        store(stored, retry.records())

        assertEquals(2, stored.size)
        assertEquals(attempt.records().map { it.metadata.clientRecordId }, retry.records().map { it.metadata.clientRecordId })
        assertEquals(attempt.measuredAt, retry.measuredAt)
        assertEquals(Instant.parse("2026-10-13T03:00:00.123Z"), retry.measuredAt)
        assertEquals(retry.measuredAt, (stored.values.first { it is BloodPressureRecord } as BloodPressureRecord).time)
        assertTrue(retry.clientRecordVersion > attempt.clientRecordVersion)
    }

    @Test
    fun correctionAfterRestorationUpdatesTheSameRecordsAndRejectsAnOlderWrite() {
        val original = VitalEntryDraft(listOf("128", "76", "62"))
            .prepareRegistration(now).copy(writeAttempted = true)
        val stored = mutableMapOf<String, Record>()
        store(stored, original.records())
        val restored = VitalEntryDraft(
            values = original.values,
            measuredAt = Instant.ofEpochMilli(checkNotNull(original.measuredAt).toEpochMilli()),
            clientRecordVersion = original.clientRecordVersion,
            writeAttempted = original.writeAttempted,
            message = original.message,
        )
        val corrected = restored.withImageResult(listOf("130", "80", "65"))
            .prepareRegistration(now.plusSeconds(60))
        store(stored, corrected.records())
        store(stored, original.records())

        assertEquals(2, stored.size)
        assertEquals(original.measuredAt, corrected.measuredAt)
        val pressure = stored.values.first { it is BloodPressureRecord } as BloodPressureRecord
        val heartRate = stored.values.first { it is HeartRateRecord } as HeartRateRecord
        assertEquals(130.0, pressure.systolic.inMillimetersOfMercury, 0.0)
        assertEquals(80.0, pressure.diastolic.inMillimetersOfMercury, 0.0)
        assertEquals(65L, heartRate.samples.single().beatsPerMinute)
        assertEquals(corrected.clientRecordVersion, pressure.metadata.clientRecordVersion)
        assertEquals(corrected.clientRecordVersion, heartRate.metadata.clientRecordVersion)
    }

    @Test
    fun unconfirmedResultAndFailureReasonRemainInTheEditableDraft() {
        val attempt = VitalEntryDraft(listOf("128", "76", "62"))
            .prepareRegistration(now).copy(writeAttempted = true)
        assertTrue(attempt.feedback()!!.contains("保存済みの可能性"))

        val failed = attempt.withRegistrationFailure("Health Connectに接続できません。")
        val returned = failed.withImageResult(null)
        assertTrue(returned.feedback()!!.contains("Health Connectに接続できません。"))
        assertTrue(returned.feedback()!!.contains("保存済みの可能性"))
        assertEquals(failed.feedback(), returned.feedback())
        assertEquals(failed.measuredAt, returned.measuredAt)
    }

    @Test
    fun cancellationBeforeWritingDoesNotClaimThatRecordsMayHaveBeenSaved() {
        val cancelled = VitalEntryDraft(listOf("128", "76", "62"))
            .prepareRegistration(now)
            .withRegistrationFailure("登録処理を中止しました。")
        assertEquals("登録処理を中止しました。", cancelled.feedback())
        assertFalse(cancelled.writeAttempted)
    }

    @Test
    fun aNewEntryDoesNotReuseThePreviousMeasurementIdentity() {
        val first = VitalEntryDraft(listOf("128", "76", "62")).prepareRegistration(now)
        val next = VitalEntryDraft(first.values).prepareRegistration(now.plusSeconds(60))
        assertNotEquals(first.records().first().metadata.clientRecordId, next.records().first().metadata.clientRecordId)
        assertEquals(0L, next.clientRecordVersion)
    }

    private fun VitalEntryDraft.records(): List<Record> = VitalMeasurement(
        measuredAt = checkNotNull(measuredAt),
        systolic = values[0].toDouble(),
        diastolic = values[1].toDouble(),
        heartRate = values[2].toLong(),
    ).toHealthConnectRecords(clientRecordVersion = clientRecordVersion)

    // Health Connect upserts each client ID only when its supplied version is newer.
    private fun store(stored: MutableMap<String, Record>, records: List<Record>) {
        records.forEach { record ->
            val id = checkNotNull(record.metadata.clientRecordId)
            val existing = stored[id]
            if (existing == null || record.metadata.clientRecordVersion > existing.metadata.clientRecordVersion) {
                stored[id] = record
            }
        }
    }
}
