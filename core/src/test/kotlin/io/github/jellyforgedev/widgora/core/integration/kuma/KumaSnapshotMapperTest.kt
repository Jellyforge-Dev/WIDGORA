package io.github.jellyforgedev.widgora.core.integration.kuma

import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KumaSnapshotMapperTest {

    @Test
    fun theWidgetIdIsBuiltFromTheInstanceIdAlone() {
        val id = KumaSnapshotMapper.summaryWidgetId(InstanceId("home-kuma"))
        assertEquals("kuma-home-kuma-summary", id.value)
    }

    @Test
    fun theLongestInstanceIdStillFitsInAWidgetId() {
        val longest = InstanceId("i".repeat(InstanceId.MAX_LENGTH))
        val id = KumaSnapshotMapper.summaryWidgetId(longest)
        assertTrue(id.value.length <= 64, id.value.length.toString())
    }

    @Test
    fun countsUpAndDown() {
        val snapshot = snapshotOf(
            monitor("1", KumaMonitorStatus.UP),
            monitor("2", KumaMonitorStatus.UP),
            monitor("3", KumaMonitorStatus.DOWN),
        )
        assertEquals("2 up, 1 down", snapshot.primaryValue)
    }

    @Test
    fun namesPendingMaintenanceAndUnknownOnlyWhenPresent() {
        val snapshot = snapshotOf(
            monitor("1", KumaMonitorStatus.UP),
            monitor("2", KumaMonitorStatus.PENDING),
            monitor("3", KumaMonitorStatus.MAINTENANCE),
            monitor("4", KumaMonitorStatus.UNKNOWN),
        )
        assertEquals("1 up, 0 down, 1 pending, 1 in maintenance, 1 unknown", snapshot.primaryValue)
    }

    /** An unrecognised state is not absorbed into the up count. */
    @Test
    fun anUnknownStateIsNotCountedAsUp() {
        val snapshot = snapshotOf(monitor("1", KumaMonitorStatus.UNKNOWN))
        assertEquals("0 up, 0 down, 1 unknown", snapshot.primaryValue)
    }

    @Test
    fun noMonitorsYieldsAnEmptyValue() {
        assertEquals("", snapshotOf().primaryValue)
    }

    @Test
    fun theTimeOfRetrievalIsTheAgeOfTheData() {
        val snapshot = KumaSnapshotMapper.summarySnapshot(
            instanceId = InstanceId("home-kuma"),
            title = "Uptime Kuma",
            monitors = listOf(monitor("1", KumaMonitorStatus.UP)),
            retrievedAtEpochMs = 1_700_000_000_000,
        )
        assertEquals(1_700_000_000_000, snapshot.updatedAtEpochMs)
    }

    @Test
    fun anOverLongTitleIsCappedRatherThanRejected() {
        val snapshot = KumaSnapshotMapper.summarySnapshot(
            instanceId = InstanceId("home-kuma"),
            title = "t".repeat(200),
            monitors = listOf(monitor("1", KumaMonitorStatus.UP)),
            retrievedAtEpochMs = 1_700_000_000_000,
        )
        assertEquals(WidgetSnapshot.MAX_DISPLAY_TEXT_LENGTH, snapshot.title.length)
    }

    @Test
    fun theTokensAreSymbolicAndFixed() {
        val snapshot = snapshotOf(monitor("1", KumaMonitorStatus.UP))
        assertEquals("monitor", snapshot.iconToken)
        assertEquals("accent-default", snapshot.accentToken)
    }

    private fun snapshotOf(vararg monitors: KumaMonitor): WidgetSnapshot =
        KumaSnapshotMapper.summarySnapshot(
            instanceId = InstanceId("home-kuma"),
            title = "Uptime Kuma",
            monitors = monitors.toList(),
            retrievedAtEpochMs = 1_700_000_000_000,
        )

    private fun monitor(id: String, status: KumaMonitorStatus): KumaMonitor = KumaMonitor(
        monitorId = id,
        name = "Service " + id,
        type = "http",
        status = status,
        responseTimeMs = null,
        uptimeRatioByWindow = emptyMap(),
        certificateValid = null,
        certificateDaysRemaining = null,
    )
}
