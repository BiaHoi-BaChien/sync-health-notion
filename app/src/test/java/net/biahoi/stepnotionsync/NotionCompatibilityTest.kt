package net.biahoi.stepnotionsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
    fun incompleteQueryIsReportedInsteadOfSilentlyAccepted() {
        assertEquals(
            "Notion APIの検索結果が不完全です(query_result_limit_reached)。同期範囲を短くしてください。",
            incompleteQueryError("incomplete", "query_result_limit_reached")
        )
        assertNull(incompleteQueryError("complete", null))
    }
}
