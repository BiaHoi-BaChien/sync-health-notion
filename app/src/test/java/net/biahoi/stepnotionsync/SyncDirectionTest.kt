package net.biahoi.stepnotionsync

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDirectionTest {
    @Test
    fun existingSettingsDefaultToHealthConnectToNotion() {
        assertEquals(SyncDirection.HEALTH_CONNECT_TO_NOTION, SyncDirection.from(null))
        assertEquals(SyncDirection.HEALTH_CONNECT_TO_NOTION, SyncDirection.from("unknown"))
    }

    @Test
    fun restoresSavedDirections() {
        assertEquals(SyncDirection.DISABLED, SyncDirection.from("disabled"))
        assertEquals(
            SyncDirection.NOTION_TO_HEALTH_CONNECT,
            SyncDirection.from("notion_to_health_connect")
        )
    }

    @Test
    fun exposesStableSpinnerOrderAndIndexes() {
        assertEquals(
            listOf("同期しない", "HealthConnect→Notion", "Notion→HealthConnect"),
            SyncDirection.entries.map { it.label }
        )
        assertEquals(0, SyncDirection.indexOf("disabled"))
        assertEquals(1, SyncDirection.indexOf("health_connect_to_notion"))
        assertEquals(2, SyncDirection.indexOf("notion_to_health_connect"))
    }

    @Test
    fun cardPresentationReflectsDirectionAndDisabledState() {
        val disabled = syncCardPresentation(SyncDirection.DISABLED)
        assertFalse(disabled.enabled)
        assertEquals("Health Connect", disabled.leftLabel)
        assertEquals("Notion", disabled.rightLabel)

        val forward = syncCardPresentation(SyncDirection.HEALTH_CONNECT_TO_NOTION)
        assertTrue(forward.enabled)
        assertTrue(forward.healthConnectFirst)
        assertEquals("Health Connect", forward.leftLabel)
        assertEquals("Notion", forward.rightLabel)

        val reverse = syncCardPresentation(SyncDirection.NOTION_TO_HEALTH_CONNECT)
        assertTrue(reverse.enabled)
        assertFalse(reverse.healthConnectFirst)
        assertEquals("Notion", reverse.leftLabel)
        assertEquals("Health Connect", reverse.rightLabel)
    }

    @Test
    fun disabledItemsAreExcludedFromConfiguredSync() {
        val config = config(
            stepsDirection = SyncDirection.DISABLED,
            vitalsDirection = SyncDirection.HEALTH_CONNECT_TO_NOTION,
            weightDirection = SyncDirection.DISABLED
        )

        assertFalse(config.hasStepsSettings())
        assertTrue(config.hasVitalsSettings())
        assertFalse(config.hasWeightSettings())
        assertTrue(config.hasAnySettings())
    }

    @Test
    fun allDisabledItemsProduceNoConfiguredSyncTargetOrPermissions() {
        val config = config(
            stepsDirection = SyncDirection.DISABLED,
            vitalsDirection = SyncDirection.DISABLED,
            weightDirection = SyncDirection.DISABLED
        )

        assertFalse(config.hasAnySettings())
        assertTrue(config.requiredSyncPermissions().isEmpty())
    }

    @Test
    fun activeDirectionStillRequiresCompleteNotionSettings() {
        val config = config(
            stepsDirection = SyncDirection.NOTION_TO_HEALTH_CONNECT,
            stepsDataSourceId = ""
        )

        assertFalse(config.hasStepsSettings())
        assertFalse(config.hasAnySettings())
    }

    @Test
    fun directionSelectsReadOrWritePermissionsPerDataType() {
        assertTrue(SyncDirection.DISABLED.stepsPermissions().isEmpty())
        assertEquals(
            setOf(HealthPermission.getReadPermission(StepsRecord::class)),
            SyncDirection.HEALTH_CONNECT_TO_NOTION.stepsPermissions()
        )
        assertEquals(
            setOf(
                HealthPermission.getReadPermission(StepsRecord::class),
                HealthPermission.getWritePermission(StepsRecord::class)
            ),
            SyncDirection.NOTION_TO_HEALTH_CONNECT.stepsPermissions()
        )

        assertEquals(
            setOf(
                HealthPermission.getReadPermission(BloodPressureRecord::class),
                HealthPermission.getReadPermission(HeartRateRecord::class)
            ),
            SyncDirection.HEALTH_CONNECT_TO_NOTION.vitalsPermissions()
        )
        assertEquals(
            setOf(
                HealthPermission.getReadPermission(BloodPressureRecord::class),
                HealthPermission.getWritePermission(BloodPressureRecord::class),
                HealthPermission.getWritePermission(HeartRateRecord::class)
            ),
            SyncDirection.NOTION_TO_HEALTH_CONNECT.vitalsPermissions()
        )

        assertEquals(
            setOf(HealthPermission.getReadPermission(WeightRecord::class)),
            SyncDirection.HEALTH_CONNECT_TO_NOTION.weightPermissions()
        )
        assertEquals(
            setOf(
                HealthPermission.getReadPermission(WeightRecord::class),
                HealthPermission.getWritePermission(WeightRecord::class)
            ),
            SyncDirection.NOTION_TO_HEALTH_CONNECT.weightPermissions()
        )
    }

    @Test
    fun mixedDirectionsAggregateOnlyConfiguredPermissions() {
        val config = config(
            stepsDirection = SyncDirection.HEALTH_CONNECT_TO_NOTION,
            vitalsDirection = SyncDirection.DISABLED,
            weightDirection = SyncDirection.NOTION_TO_HEALTH_CONNECT
        )

        assertEquals(
            setOf(
                HealthPermission.getReadPermission(StepsRecord::class),
                HealthPermission.getReadPermission(WeightRecord::class),
                HealthPermission.getWritePermission(WeightRecord::class)
            ),
            config.requiredSyncPermissions()
        )
    }

    private fun config(
        stepsDirection: SyncDirection = SyncDirection.HEALTH_CONNECT_TO_NOTION,
        vitalsDirection: SyncDirection = SyncDirection.DISABLED,
        weightDirection: SyncDirection = SyncDirection.DISABLED,
        stepsDataSourceId: String = "steps-data-source"
    ) = SyncConfig(
        token = "token",
        stepsDataSourceId = stepsDataSourceId,
        stepsDateProperty = "日付",
        stepsProperty = "歩数",
        vitalsDataSourceId = "vitals-data-source",
        vitalsMeasuredAtProperty = "日付",
        systolicProperty = "収縮期",
        diastolicProperty = "拡張期",
        heartRateProperty = "脈拍",
        weightDataSourceId = "weight-data-source",
        weightMeasuredAtProperty = "日付",
        weightProperty = "体重",
        stepsDirection = stepsDirection,
        vitalsDirection = vitalsDirection,
        weightDirection = weightDirection
    )
}
