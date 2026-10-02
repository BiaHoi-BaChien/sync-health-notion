package net.biahoi.stepnotionsync

import com.google.mlkit.genai.common.FeatureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VitalImageInputTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun unavailableOrFailedChecksRouteToSettings() {
        listOf(null, FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING, FeatureStatus.UNAVAILABLE).forEach {
            assertEquals(NanoImageDestination.SETTINGS, NanoVitalState(status = it).imageDestination())
        }
        assertEquals(NanoImageDestination.IMAGE, NanoVitalState(status = FeatureStatus.AVAILABLE).imageDestination())
    }

    @Test
    fun waitsForCheckOrDownloadCompletionEvenWhenLastStatusWasAvailable() {
        listOf(null, FeatureStatus.AVAILABLE, FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING).forEach {
            assertEquals(NanoImageDestination.WAIT, NanoVitalState(status = it, busy = true).imageDestination())
        }
    }

    @Test
    fun firstEntryRequestsSourceChoiceButRotationDoesNotRelaunchPendingCamera() {
        val session = VitalImageInputSession()
        assertFalse(session.open(restoredActivity = false))
        assertTrue(session.chooseSource)
        session.begin(VitalImageSource.CAMERA)
        assertFalse(session.open(restoredActivity = true))
        assertFalse(session.chooseSource)
        assertEquals(VitalImageSource.CAMERA, session.pendingSource)
        assertTrue(session.complete(VitalImageSource.CAMERA))
        assertNull(session.pendingSource)
        assertFalse(session.complete(VitalImageSource.CAMERA))
    }

    @Test
    fun rotatingAnOpenChoiceRetainsTheChoiceWithoutStartingARead() {
        val session = VitalImageInputSession()
        session.open(restoredActivity = false)
        session.open(restoredActivity = true)
        assertTrue(session.chooseSource)
        assertNull(session.pendingSource)
    }

    @Test
    fun processDeathRejectsOldCameraAndPickerResultsUntilUserSelectsAgain() {
        val session = VitalImageInputSession()
        assertTrue(session.open(restoredActivity = true))
        assertTrue(session.interrupted)
        assertFalse(session.chooseSource)
        assertFalse(session.complete(VitalImageSource.CAMERA))
        assertFalse(session.complete(VitalImageSource.PICKER))
        session.begin(VitalImageSource.PICKER)
        assertFalse(session.interrupted)
        assertTrue(session.complete(VitalImageSource.PICKER))
    }

    @Test
    fun cancelledOrFailedAcquisitionAllowsAnotherSource() {
        val session = VitalImageInputSession()
        session.open(restoredActivity = false)
        session.begin(VitalImageSource.CAMERA)
        assertTrue(session.complete(VitalImageSource.CAMERA))
        session.begin(VitalImageSource.PICKER)
        assertFalse(session.complete(VitalImageSource.CAMERA))
        assertTrue(session.complete(VitalImageSource.PICKER))
    }

    @Test
    fun settingsRoundTripWithoutAReadKeepsDraftVoiceHistoryAndAttempts() {
        val timing = VitalEntryTiming(1_000).useVoice().openImage().recordImageResult(2_000, 500, 1, true)
        val draft = VitalEntryDraft(listOf("128", "76", "62"), timing)
        assertEquals(draft, draft.withImageResult(null, timing.startedAt, null, 1))
    }

    @Test
    fun cancelledReadUpdatesTimingWithoutReplacingUserEdits() {
        val draft = VitalEntryDraft(listOf("130", "", "65"), VitalEntryTiming(1_000).useVoice().openImage())
        val result = draft.withImageResult(null, 2_000, 600, 1)
        assertEquals(draft.values, result.values)
        assertEquals(VitalEntryMethod.IMAGE_AND_VOICE, result.timing.method)
        assertEquals(1_000L, result.timing.startedAt)
        assertEquals(1, result.timing.attempts)
    }

    @Test
    fun confirmedPartialReadingReturnsEditableCandidates() {
        val draft = VitalEntryDraft(listOf("130", "80", "65"), VitalEntryTiming(1_000).openImage())
        val result = draft.withImageResult(listOf("128", "", "62"), 2_000, 600, 1)
        assertEquals(listOf("128", "", "62"), result.values)
        assertEquals(VitalEntryMethod.IMAGE, result.timing.method)
        assertEquals(1, result.timing.attempts)
    }

    @Test
    fun captureFilesAreUniqueAndConfinedToPrivateCaptureCache() {
        val cache = temporaryFolder.newFolder("cache")
        val files = VitalCaptureFiles(cache)
        val first = files.create()
        val second = files.create()
        assertTrue(first.isFile)
        assertTrue(second.isFile)
        assertFalse(first == second)
        assertEquals(File(cache, "vital-captures"), first.parentFile)
    }

    @Test
    fun abandonedCaptureCleanupDoesNotRemoveOtherCacheFiles() {
        val cache = temporaryFolder.newFolder("cache")
        val files = VitalCaptureFiles(cache)
        val capture = files.create().apply { writeText("synthetic test fixture") }
        val other = File(cache, "other-cache").apply { writeText("keep") }
        val unknown = File(capture.parentFile, "unrelated.jpg").apply { writeText("keep") }
        files.clear()
        assertFalse(capture.exists())
        assertTrue(other.exists())
        assertTrue(unknown.exists())
    }
}
