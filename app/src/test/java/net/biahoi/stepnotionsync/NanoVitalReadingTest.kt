package net.biahoi.stepnotionsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NanoVitalReadingTest {
    @Test
    fun acceptsOnlyLabelledCandidateFields() {
        assertEquals(NanoVitalReading(128, 76, 62), parseNanoVitalReading("SYS=128\nDIA=76\nPUL=62"))
        assertEquals(NanoVitalReading(128, 76, 62), parseNanoVitalReading(" SYS=128\r\nDIA=76\r\nPUL=62\n"))
    }

    @Test
    fun preservesMissingFieldsWithoutBorrowingOtherValues() {
        assertEquals(arrayListOf("", "76", ""),
            parseNanoVitalReading("SYS=UNKNOWN\nDIA=76\nPUL=UNKNOWN")?.inputValues())
        assertEquals(NanoVitalReading(null, null, null),
            parseNanoVitalReading("SYS=UNKNOWN\nDIA=UNKNOWN\nPUL=UNKNOWN"))
    }

    @Test
    fun rejectsAmbiguousMalformedOrIncompleteResponses() {
        listOf(
            "128 76 62", "128\n76\n62", "SYS=128\nDIA=76", "SYS=128\nDIA=76\nPUL=62\nSYS=138",
            "Probably SYS=128\nDIA=76\nPUL=62", "SYS=128 or 138\nDIA=76\nPUL=62",
            "SYS=12?\nDIA=76\nPUL=62", "SYS=128.0\nDIA=76\nPUL=62",
            "SYS=-128\nDIA=76\nPUL=62", "SYS=0\nDIA=76\nPUL=62",
            "SYS=0128\nDIA=76\nPUL=62", "SYS=１２８\nDIA=76\nPUL=62",
            "SYS=128\nPUL=62\nDIA=76", "SYS=128\nDIA=76\nPUL=62 bpm",
            "```\nSYS=128\nDIA=76\nPUL=62\n```", "",
        ).forEach { assertNull(it, parseNanoVitalReading(it)) }
    }

    @Test
    fun clearsContradictoryPressuresWithoutSwappingOrInventingValues() {
        assertEquals(NanoVitalReading(null, null, 62), parseNanoVitalReading("SYS=76\nDIA=128\nPUL=62"))
        assertEquals(NanoVitalReading(null, null, 62), parseNanoVitalReading("SYS=76\nDIA=76\nPUL=62"))
    }
}
