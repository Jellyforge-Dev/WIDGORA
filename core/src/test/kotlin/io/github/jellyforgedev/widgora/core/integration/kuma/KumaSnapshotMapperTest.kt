package io.github.jellyforgedev.widgora.core.integration.kuma

import io.github.jellyforgedev.widgora.core.data.MonitorOverview
import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * The value carries marks and digits, never words. A snapshot can be handed to a host
     * launcher through the bridge, which shares neither this application's resources nor its
     * colours, so a localised phrase would be unreadable there and a distinction made by colour
     * alone would not arrive at all.
     */
    @Test
    fun theValueIsLanguageNeutral() {
        val snapshot = snapshotOf(
            monitor("1", KumaMonitorStatus.UP),
            monitor("2", KumaMonitorStatus.UP),
            monitor("3", KumaMonitorStatus.DOWN),
        )
        assertEquals("▲2 ▼1", snapshot.primaryValue)
    }

    @Test
    fun namesPendingMaintenanceAndUnknownOnlyWhenPresent() {
        val all = snapshotOf(
            monitor("1", KumaMonitorStatus.UP),
            monitor("2", KumaMonitorStatus.PENDING),
            monitor("3", KumaMonitorStatus.MAINTENANCE),
            monitor("4", KumaMonitorStatus.UNKNOWN),
        )
        assertEquals("▲1 ▼0 ◆1 ■1 ●1", all.primaryValue)

        val onlyPending = snapshotOf(
            monitor("1", KumaMonitorStatus.UP),
            monitor("2", KumaMonitorStatus.PENDING),
        )
        assertEquals("▲1 ▼0 ◆1", onlyPending.primaryValue)
    }

    /** An unrecognised state is not absorbed into the working count. */
    @Test
    fun anUnknownStateIsNotCountedAsUp() {
        val snapshot = snapshotOf(monitor("1", KumaMonitorStatus.UNKNOWN))
        assertEquals("▲0 ▼0 ●1", snapshot.primaryValue)
    }

    @Test
    fun noMonitorsYieldsAnEmptyValue() {
        assertEquals("", snapshotOf().primaryValue)
    }

    /**
     * The largest response the reader accepts, spread over all five states, still produces a
     * value the snapshot does not have to cap. Checked against the mapping's own output as well
     * as against the limit, so that a silent truncation fails this test rather than passing it.
     */
    @Test
    fun theValueIsNeverCappedEvenForTheLargestAcceptedResponse() {
        val states = KumaMonitorStatus.entries
        val monitors = (1..KumaMetricsReader.MAX_MONITORS).map { index ->
            monitor(index.toString(), states[index % states.size])
        }
        val overview = KumaMonitorMapping.overviewOf(monitors)

        val snapshot = KumaSnapshotMapper.summarySnapshot(
            instanceId = InstanceId("home-kuma"),
            title = "Uptime Kuma",
            overview = overview,
            retrievedAtEpochMs = RETRIEVED_AT,
        )

        assertEquals(overview.counts.neutralSummary(), snapshot.primaryValue)
        assertTrue(
            snapshot.primaryValue.length <= WidgetSnapshot.MAX_DISPLAY_TEXT_LENGTH,
            snapshot.primaryValue,
        )
    }

    @Test
    fun theTimeOfRetrievalIsTheAgeOfTheData() {
        val snapshot = snapshotOf(monitor("1", KumaMonitorStatus.UP))
        assertEquals(RETRIEVED_AT, snapshot.updatedAtEpochMs)
    }

    @Test
    fun anOverLongTitleIsCappedRatherThanRejected() {
        val snapshot = KumaSnapshotMapper.summarySnapshot(
            instanceId = InstanceId("home-kuma"),
            title = "t".repeat(200),
            overview = KumaMonitorMapping.overviewOf(listOf(monitor("1", KumaMonitorStatus.UP))),
            retrievedAtEpochMs = RETRIEVED_AT,
        )
        assertEquals(WidgetSnapshot.MAX_DISPLAY_TEXT_LENGTH, snapshot.title.length)
    }

    /**
     * No monitor name may enter a snapshot. The names here are distinctive, so that a mapping
     * which ever started putting one into the title or the value would fail this test.
     */
    @Test
    fun noMonitorNameEntersTheSnapshot() {
        val snapshot = KumaSnapshotMapper.summarySnapshot(
            instanceId = InstanceId("home-kuma"),
            title = "Uptime Kuma",
            overview = KumaMonitorMapping.overviewOf(
                listOf(
                    monitor("1", KumaMonitorStatus.DOWN, name = "nas.internal.example"),
                    monitor("2", KumaMonitorStatus.UP, name = "router.internal.example"),
                ),
            ),
            retrievedAtEpochMs = RETRIEVED_AT,
        )

        val rendered = snapshot.toString()
        assertFalse(rendered.contains("internal.example"), rendered)
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
            overview = if (monitors.isEmpty()) {
                MonitorOverview.Empty
            } else {
                KumaMonitorMapping.overviewOf(monitors.toList())
            },
            retrievedAtEpochMs = RETRIEVED_AT,
        )

    private fun monitor(
        id: String,
        status: KumaMonitorStatus,
        name: String? = "Service " + id,
    ): KumaMonitor = KumaMonitor(
        monitorId = id,
        name = name,
        type = "http",
        status = status,
        responseTimeMs = null,
        uptimeRatioByWindow = emptyMap(),
        certificateValid = null,
        certificateDaysRemaining = null,
    )

    private companion object {
        const val RETRIEVED_AT = 1_700_000_000_000
    }
}
