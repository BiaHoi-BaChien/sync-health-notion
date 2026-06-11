package net.biahoi.stepnotionsync

import java.time.Duration
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSyncScheduleTest {
    @Test
    fun schedulesLaterTimeOnSameDay() {
        val now = LocalDateTime.of(2026, 6, 11, 21, 30)

        val delay = initialAutoSyncDelayMillis("22:00", now)

        assertEquals(Duration.ofMinutes(30).toMillis(), delay)
    }

    @Test
    fun schedulesPassedTimeOnNextDay() {
        val now = LocalDateTime.of(2026, 6, 11, 22, 30)

        val delay = initialAutoSyncDelayMillis("22:00", now)

        assertEquals(Duration.ofHours(23).plusMinutes(30).toMillis(), delay)
    }

    @Test
    fun schedulesExactTimeOnNextDay() {
        val now = LocalDateTime.of(2026, 6, 11, 22, 0)

        val delay = initialAutoSyncDelayMillis("22:00", now)

        assertEquals(Duration.ofDays(1).toMillis(), delay)
    }
}
