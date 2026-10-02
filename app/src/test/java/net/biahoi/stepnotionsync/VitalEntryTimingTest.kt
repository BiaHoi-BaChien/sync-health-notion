package net.biahoi.stepnotionsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VitalEntryTimingTest {
    @Test
    fun includesCancelledReadWhenRetryIsLaterAccepted() {
        val cancelled = VitalEntryTiming(1_000).openImage().recordImageResult(
            imageStartedAt = 10_000, processingMillis = 3_000, attempts = 1, candidatesApplied = false,
        )
        assertEquals(VitalEntryMethod.IMAGE_EXPERIMENT, cancelled.method)
        assertTrue(cancelled.imageSelectionStarted)
        assertEquals(1, cancelled.attempts)

        val accepted = cancelled.openImage().recordImageResult(
            imageStartedAt = cancelled.startedAt, processingMillis = 3_600, attempts = 2, candidatesApplied = true,
        )
        assertEquals(10_000L, accepted.startedAt)
        assertEquals("画像入力: 登録完了まで20.0秒 / 試行2回 / 最終処理3.6秒", accepted.summary(30_000))
    }

    @Test
    fun preservesFirstSelectionEvenWhenPickerWasCancelledWithoutReading() {
        val cancelled = VitalEntryTiming(1_000).openImage().recordImageResult(10_000, null, 0, false)
        val accepted = cancelled.openImage().recordImageResult(cancelled.startedAt, 3_600, 1, true)
        assertTrue(cancelled.imageSelectionStarted)
        assertEquals(10_000L, accepted.startedAt)
        assertEquals(1, accepted.attempts)
    }

    @Test
    fun openingAndLeavingBeforeSelectionDoesNotStartImageClock() {
        val cancelled = VitalEntryTiming(1_000).openImage().recordImageResult(0, null, 0, false)
        assertFalse(cancelled.imageSelectionStarted)
        assertEquals(1_000L, cancelled.startedAt)
        val accepted = cancelled.openImage().recordImageResult(20_000, 3_600, 1, true)
        assertEquals(20_000L, accepted.startedAt)
    }

    @Test
    fun cancellingDuringInferenceKeepsAttemptButDoesNotReusePreviousDuration() {
        val first = VitalEntryTiming(1_000).openImage().recordImageResult(10_000, 3_600, 1, true)
        val interrupted = first.openImage().recordImageResult(first.startedAt, null, 2, false)
        assertEquals(10_000L, interrupted.startedAt)
        assertEquals(2, interrupted.attempts)
        assertNull(interrupted.processingMillis)
        assertEquals("画像入力: 登録完了まで20.0秒 / 試行2回", interrupted.summary(30_000))
    }

    @Test
    fun reopeningWithoutAnotherReadKeepsCompletedAttemptDetails() {
        val first = VitalEntryTiming(1_000).openImage().recordImageResult(10_000, 3_600, 1, true)
        val cancelled = first.openImage().recordImageResult(first.startedAt, null, 1, false)
        assertEquals(first, cancelled)
    }

    @Test
    fun voiceCorrectionRemainsMixedAfterCancelledAndAcceptedImageRetries() {
        val mixed = VitalEntryTiming(1_000).openImage().recordImageResult(10_000, 3_600, 1, true).useVoice()
        assertEquals(VitalEntryMethod.IMAGE_AND_VOICE, mixed.openImage().method)
        val cancelled = mixed.openImage().recordImageResult(mixed.startedAt, 3_000, 2, false)
        val accepted = cancelled.openImage().recordImageResult(cancelled.startedAt, 3_600, 3, true)
        assertEquals(VitalEntryMethod.IMAGE_AND_VOICE, cancelled.method)
        assertEquals(VitalEntryMethod.IMAGE_AND_VOICE, accepted.method)
        assertEquals("画像＋音声入力: 登録完了まで40.0秒 / 試行3回 / 最終処理3.6秒", accepted.summary(50_000))
    }

    @Test
    fun voiceBeforeImageKeepsVoiceTimeAndMixedClassification() {
        val voice = VitalEntryTiming(1_000).useVoice()
        assertEquals(VitalEntryMethod.VOICE, voice.method)
        val mixed = voice.openImage().recordImageResult(10_000, 3_600, 1, true)
        assertEquals(1_000L, mixed.startedAt)
        assertEquals(VitalEntryMethod.IMAGE_AND_VOICE, mixed.method)
        assertEquals("画像＋音声入力: 登録完了まで19.0秒 / 試行1回 / 最終処理3.6秒", mixed.summary(20_000))
    }
}
