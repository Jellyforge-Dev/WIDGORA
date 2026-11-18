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
            templates = KumaSummaryTemplates.Numeric,
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
            templates = KumaSummaryTemplates.Numeric,
        )
        assertEquals(WidgetSnapshot.MAX_DISPLAY_TEXT_LENGTH, snapshot.title.length)
    }

    /**
     * The wording comes from outside; what this module decides is which parts appear, in which
     * order, and with which count. A language is free to put the number anywhere in its phrase,
     * which is why the templates are checked with a different word order than English uses.
     */
    @Test
    fun theSummaryUsesTheSuppliedPhrasesInTheModulesOwnOrder() {
        // Short phrases on purpose: five parts with long words exceed the 64 code points a
        // snapshot value may hold and would be capped, which is a property of the snapshot
        // rather than of this mapping.
        val german = KumaSummaryTemplates(
            up = { count -> "auf: $count" },
            down = { count -> "ab: $count" },
            pending = { count -> "warte: $count" },
            maintenance = { count -> "wartung: $count" },
            unknown = { count -> "unbek.: $count" },
            separator = " | ",
        )

        val snapshot = KumaSnapshotMapper.summarySnapshot(
            instanceId = InstanceId("home-kuma"),
            title = "Uptime Kuma",
            monitors = listOf(
                monitor("1", KumaMonitorStatus.UP),
                monitor("2", KumaMonitorStatus.DOWN),
                monitor("3", KumaMonitorStatus.PENDING),
                monitor("4", KumaMonitorStatus.MAINTENANCE),
                monitor("5", KumaMonitorStatus.UNKNOWN),
            ),
            retrievedAtEpochMs = 1_700_000_000_000,
            templates = german,
        )

        assertEquals(
            "auf: 1 | ab: 1 | warte: 1 | wartung: 1 | unbek.: 1",
            snapshot.primaryValue,
        )
    }

    @Test
    fun theThreeOptionalPartsAppearOnlyWhenTheyOccur() {
        val snapshot = snapshotOf(
            monitor("1", KumaMonitorStatus.UP),
            monitor("2", KumaMonitorStatus.PENDING),
        )

        assertEquals("1 up, 0 down, 1 pending", snapshot.primaryValue)
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
            templates = KumaSummaryTemplates.Numeric,
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
