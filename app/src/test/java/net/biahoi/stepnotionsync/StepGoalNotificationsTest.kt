package net.biahoi.stepnotionsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StepGoalNotificationsTest {
    private val today = LocalDate.parse("2026-10-13")
    private val noPreviousNotice = StepGoalNotice("", 0, 0)

    @Test
    fun goalMustBeAPositiveWholeNumber() {
        assertEquals(10_000, parseStepGoal(" 10000 "))
        listOf("", "0", "-1", "1.5", "abc", "2147483648").forEach { assertNull(parseStepGoal(it)) }
        assertThrows(IllegalArgumentException::class.java) { StepGoalProgress(today, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { StepGoalProgress(today, -1, 10_000) }
    }

    @Test
    fun notifiesAtEachMilestoneOnlyOnce() {
        var previous = noPreviousNotice
        for (milestone in listOf(25, 50, 75, 100)) {
            val steps = milestone * 100L
            assertNull(StepGoalProgress(today, steps - 1, 10_000).nextNotice(previous))
            val progress = StepGoalProgress(today, steps, 10_000)
            val notice = progress.nextNotice(previous)!!
            assertEquals(milestone, notice.milestone)
            assertNull(progress.nextNotice(notice))
            previous = notice
        }
        assertNull(StepGoalProgress(today, 20_000, 10_000).nextNotice(previous))
    }

    @Test
    fun skippedMilestonesProduceOnlyTheLatestNotice() {
        val progress = StepGoalProgress(today, 8_000, 10_000)
        val notice = progress.nextNotice(noPreviousNotice)!!
        assertEquals(75, notice.milestone)
        assertNull(StepGoalProgress(today, 9_000, 10_000).nextNotice(notice))
        assertNull(StepGoalProgress(today, 4_000, 10_000).nextNotice(notice))
    }

    @Test
    fun restoredHistoryPreventsRepeatAndNewDayOrGoalRestartsMilestones() {
        val saved = StepGoalNotice(today.toString(), 10_000, 50)
        assertNull(StepGoalProgress(today, 6_000, 10_000).nextNotice(saved))
        assertEquals(50, StepGoalProgress(today.plusDays(1), 5_000, 10_000).nextNotice(saved)?.milestone)
        assertEquals(25, StepGoalProgress(today, 5_000, 20_000).nextNotice(saved)?.milestone)
        assertNull(StepGoalProgress(today.plusDays(1), 0, 10_000).nextNotice(saved))
    }

    @Test
    fun fractionalThresholdsRoundUpToTheNextWholeStep() {
        assertNull(StepGoalProgress(today, 2_500, 10_001).nextNotice(noPreviousNotice))
        assertEquals(25, StepGoalProgress(today, 2_501, 10_001).nextNotice(noPreviousNotice)?.milestone)
        assertEquals(100, StepGoalProgress(today, 1, 1).nextNotice(noPreviousNotice)?.milestone)
        assertEquals(100, StepGoalProgress(today, Long.MAX_VALUE, Int.MAX_VALUE).milestone)
    }

    @Test
    fun notificationShowsCurrentStepsGoalAndActualPercentage() {
        assertEquals("5,432 / 10,000歩（達成率 54％）", StepGoalProgress(today, 5_432, 10_000).summary())
        assertEquals("12,500 / 10,000歩（達成率 125％）", StepGoalProgress(today, 12_500, 10_000).summary())
        assertEquals(29L, StepGoalProgress(today, 29, 100).percent)
    }

    @Test
    fun usesLocalMidnightAndResetsAtTheVietnamDateBoundary() {
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        assertEquals(Instant.parse("2026-10-12T17:00:00Z"), stepGoalDayStart(Instant.parse("2026-10-13T16:59:59Z"), zone))
        assertEquals(Instant.parse("2026-10-13T17:00:00Z"), stepGoalDayStart(Instant.parse("2026-10-13T17:00:00Z"), zone))
    }

    @Test
    fun localDayStartRespectsDaylightSaving() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(Instant.parse("2026-03-08T05:00:00Z"), stepGoalDayStart(Instant.parse("2026-03-08T18:00:00Z"), zone))
        assertEquals(Instant.parse("2026-03-09T04:00:00Z"), stepGoalDayStart(Instant.parse("2026-03-09T18:00:00Z"), zone))
    }
}
