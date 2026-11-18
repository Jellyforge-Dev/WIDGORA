package io.github.jellyforgedev.widgora.core.integration.kuma

import io.github.jellyforgedev.widgora.model.InstanceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reads a captured response of a real installation, with identifying values replaced.
 *
 * It is the only test that proves the reader against the shape an actual endpoint produces:
 * varying tag labels, duplicate monitor names, missing values written as text, and a majority
 * of families that have nothing to do with monitors.
 */
class KumaMetricsSampleTest {

    @Test
    fun readsEveryMonitorOfTheSample() {
        assertEquals(35, monitors().size)
    }

    @Test
    fun identityIsTheMonitorIdAndNotTheName() {
        val monitors = monitors()
        assertEquals(35, monitors.map { it.monitorId }.distinct().size)
        // One name is used by two different monitors, which is why the name cannot be the key.
        assertEquals(34, monitors.mapNotNull { it.name }.distinct().size)
        val shared = monitors.filter { it.name == "Service 09" }
        assertEquals(2, shared.size)
        assertEquals(2, shared.map { it.monitorId }.distinct().size)
    }

    @Test
    fun theSampleDescribesOnlyWorkingMonitors() {
        assertTrue(monitors().all { it.status == KumaMonitorStatus.UP })
    }

    @Test
    fun everyMonitorCarriesAllThreeUptimeWindows() {
        for (monitor in monitors()) {
            assertEquals(
                setOf("1d", "30d", "365d"),
                monitor.uptimeRatioByWindow.keys,
                monitor.monitorId,
            )
        }
    }

    @Test
    fun uptimeRatiosStayWithinTheirDefinedRange() {
        for (monitor in monitors()) {
            for (ratio in monitor.uptimeRatioByWindow.values) {
                assertTrue(ratio in 0.0..1.0, monitor.monitorId + ": " + ratio)
            }
        }
    }

    @Test
    fun theMonitorsWithoutAResponseTimeAreRecognised() {
        assertEquals(3, monitors().count { it.responseTimeMs == null })
    }

    @Test
    fun onlyTheMonitorsWithCertificatesCarryCertificateData() {
        assertEquals(20, monitors().count { it.certificateValid != null })
        assertEquals(20, monitors().count { it.certificateDaysRemaining != null })
    }

    @Test
    fun aValueWrittenAsTheTextNullIsNotMistakenForAValue() {
        // The endpoint writes a missing value as the string null rather than omitting the
        // label, so the text must never survive as a name or a type.
        val monitors = monitors()
        assertTrue(monitors.none { it.name == "null" })
        assertTrue(monitors.none { it.type == "null" })
        // A monitor of type group has neither host nor port, and still has a name.
        assertNotNull(monitors.first { it.type == "group" }.name)
    }

    @Test
    fun theSummaryOfTheSampleCountsAllMonitors() {
        val snapshot = KumaSnapshotMapper.summarySnapshot(
            instanceId = InstanceId("home-kuma"),
            title = "Uptime Kuma",
            monitors = monitors(),
            retrievedAtEpochMs = 1_700_000_000_000,
            templates = KumaSummaryTemplates.Numeric,
        )
        assertEquals("35 up, 0 down", snapshot.primaryValue)
        assertEquals("kuma-home-kuma-summary", snapshot.widgetId.value)
    }

    private fun monitors(): List<KumaMonitor> {
        val result = KumaMetricsReader.read(sampleText())
        assertTrue(result is KumaReadResult.Success, result.toString())
        return result.monitors
    }

    private fun sampleText(): String {
        val stream = checkNotNull(javaClass.getResourceAsStream(RESOURCE)) {
            "test resource " + RESOURCE + " is missing"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private companion object {
        const val RESOURCE = "/kuma/metrics-sample.txt"
    }
}
