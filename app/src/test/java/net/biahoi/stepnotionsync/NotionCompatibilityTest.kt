package net.biahoi.stepnotionsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class NotionCompatibilityTest {
    @Test
    fun retryDelayUsesRetryAfterForRateLimits() {
        assertEquals(2_000L, notionRetryDelayMillis(429, "2", completedAttempts = 0))
        assertEquals(60_000L, notionRetryDelayMillis(529, "120", completedAttempts = 0))
    }

    @Test
    fun retryDelayUsesExponentialBackoffForTemporaryServerErrors() {
        assertEquals(500L, notionRetryDelayMillis(503, null, completedAttempts = 0))
        assertEquals(1_000L, notionRetryDelayMillis(503, null, completedAttempts = 1))
        assertEquals(500L, notionRetryDelayMillis(409, null, completedAttempts = 0))
        assertNull(notionRetryDelayMillis(401, null, completedAttempts = 0))
        assertNull(notionRetryDelayMillis(403, null, completedAttempts = 0))
    }

    @Test
    fun retryDelayIgnoresInvalidRetryAfterAndCapsExponentialBackoff() {
        assertEquals(500L, notionRetryDelayMillis(429, "not-a-number", completedAttempts = 0))
        assertEquals(0L, notionRetryDelayMillis(429, "-1", completedAttempts = 0))
        assertEquals(60_000L, notionRetryDelayMillis(503, null, completedAttempts = 20))
    }

    @Test
    fun incompleteQueryIsReportedInsteadOfSilentlyAccepted() {
        assertEquals(
            "Notion APIの検索結果が不完全です(query_result_limit_reached)。同期範囲を短くしてください。",
            incompleteQueryError("incomplete", "query_result_limit_reached")
        )
        assertEquals(
            "Notion APIの検索結果が不完全です(unknown)。同期範囲を短くしてください。",
            incompleteQueryError("incomplete", "")
        )
        assertNull(incompleteQueryError("complete", null))
    }

    @Test
    fun notionDatabaseUrlNormalizesHyphenatedIds() {
        assertEquals(
            "https://www.notion.so/2f26ee68df304251aad48ddc420cba3d",
            notionDatabaseUrl("2f26ee68-df30-4251-aad4-8ddc420cba3d")
        )
        assertEquals(
            "https://www.notion.so/2f26ee68df304251aad48ddc420cba3d",
            notionDatabaseUrl("2f26ee68df304251aad48ddc420cba3d")
        )
    }

    @Test
    fun autoSyncRetriesNameResolutionErrorsWithoutRecordingFailure() {
        assertTrue(isRetryableAutoSyncError(UnknownHostException("api.notion.com")))
    }
}
