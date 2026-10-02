package net.biahoi.stepnotionsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NanoVitalStateTest {
    @Test
    fun failedReadWithElapsedTimeDoesNotReturnValuesThatClearTheDraft() {
        val failed = NanoVitalState(processingMillis = 3_000, attempts = 1)
        assertNull(failed.candidateValues())
    }

    @Test
    fun rejectedResponseDoesNotBecomeAnEmptyCandidate() {
        val rejected = NanoVitalState(
            reading = parseNanoVitalReading("128\n76\n62"), processingMillis = 3_000,
        )
        assertNull(rejected.candidateValues())
    }

    @Test
    fun allUnknownFieldsDoNotReplaceTheDraft() {
        val unknown = NanoVitalState(
            reading = parseNanoVitalReading("SYS=UNKNOWN\nDIA=UNKNOWN\nPUL=UNKNOWN"),
            processingMillis = 3_000,
        )
        assertNull(unknown.candidateValues())
    }

    @Test
    fun unfinishedReadCannotApplyEvenWhenCandidatesAlreadyExist() {
        val pending = NanoVitalState(reading = NanoVitalReading(128, 76, 62))
        assertNull(pending.candidateValues())
        assertNull(pending.copy(busy = true, processingMillis = 3_000).candidateValues())
        assertEquals(arrayListOf("128", "76", "62"),
            pending.copy(processingMillis = 3_000).candidateValues())
    }

    @Test
    fun partialReadingKeepsUnknownFieldsEmptyForUserCorrection() {
        val partial = NanoVitalState(
            reading = parseNanoVitalReading("SYS=UNKNOWN\nDIA=76\nPUL=UNKNOWN"),
            processingMillis = 3_000,
        )
        assertEquals(arrayListOf("", "76", ""), partial.candidateValues())
    }
}
