package net.biahoi.stepnotionsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NanoVitalStateTest {
    @Test
    fun failedReadDoesNotReturnValuesThatClearTheDraft() {
        val failed = NanoVitalState(readCompleted = true)
        assertNull(failed.candidateValues())
        assertNull(failed.registrationValues())
    }

    @Test
    fun rejectedResponseDoesNotBecomeAnEmptyCandidate() {
        val rejected = NanoVitalState(
            reading = parseNanoVitalReading("128\n76\n62"), readCompleted = true,
        )
        assertNull(rejected.candidateValues())
        assertNull(rejected.registrationValues())
    }

    @Test
    fun allUnknownFieldsDoNotReplaceTheDraft() {
        val unknown = NanoVitalState(
            reading = parseNanoVitalReading("SYS=UNKNOWN\nDIA=UNKNOWN\nPUL=UNKNOWN"),
            readCompleted = true,
        )
        assertNull(unknown.candidateValues())
        assertNull(unknown.registrationValues())
    }

    @Test
    fun unfinishedReadCannotApplyEvenWhenCandidatesAlreadyExist() {
        val pending = NanoVitalState(reading = NanoVitalReading(128, 76, 62))
        assertNull(pending.candidateValues())
        assertNull(pending.registrationValues())
        assertNull(pending.copy(busy = true, readCompleted = true).candidateValues())
        assertNull(pending.copy(busy = true, readCompleted = true).registrationValues())
        assertEquals(arrayListOf("128", "76", "62"),
            pending.copy(readCompleted = true).candidateValues())
        assertEquals(arrayListOf("128", "76", "62"),
            pending.copy(readCompleted = true).registrationValues())
    }

    @Test
    fun partialReadingKeepsUnknownFieldsEmptyForUserCorrection() {
        val partial = NanoVitalState(
            reading = parseNanoVitalReading("SYS=UNKNOWN\nDIA=76\nPUL=UNKNOWN"),
            readCompleted = true,
        )
        assertEquals(arrayListOf("", "76", ""), partial.candidateValues())
        assertNull(partial.registrationValues())
    }

    @Test
    fun everyFieldIsRequiredForDirectRegistrationButPartialValuesRemainEditable() {
        listOf(
            "SYS=UNKNOWN\nDIA=76\nPUL=62",
            "SYS=128\nDIA=UNKNOWN\nPUL=62",
            "SYS=128\nDIA=76\nPUL=UNKNOWN",
            "SYS=76\nDIA=128\nPUL=62",
        ).forEach { response ->
            val state = NanoVitalState(reading = parseNanoVitalReading(response), readCompleted = true)
            assertNull(state.registrationValues())
            assertEquals(state.reading!!.inputValues(), state.candidateValues())
        }
    }
}
