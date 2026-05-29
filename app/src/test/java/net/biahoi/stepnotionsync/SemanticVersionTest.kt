package net.biahoi.stepnotionsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {
    @Test
    fun parsesPlainPrefixedAndBuildTaggedVersions() {
        listOf("0.0.8", "v0.0.8", "v0.0.8-b3e15e3", "v0.0.8+build.1").forEach { value ->
            val version = value.toSemanticVersion()

            assertEquals(SemanticVersion(0, 0, 8, "0.0.8"), version)
        }
    }

    @Test
    fun rejectsNonSemanticVersions() {
        listOf("0.0", "0.0.x", "release-v0.0.8").forEach { value ->
            assertNull(value.toSemanticVersion())
        }
    }

    @Test
    fun comparesParsedReleaseVersionWithCurrentVersion() {
        val currentVersion = "0.0.7".toSemanticVersion()
        val releaseVersion = "v0.0.8".toSemanticVersion()

        assertTrue(releaseVersion!! > currentVersion!!)
    }
}
