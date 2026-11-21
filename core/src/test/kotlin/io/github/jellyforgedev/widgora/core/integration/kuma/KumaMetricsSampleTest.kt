package io.github.jellyforgedev.widgora.core.integration.kuma

import io.github.jellyforgedev.widgora.core.data.CertificateValidity
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

    /**
     * The certificate data reaches the application's own item model, and only where it exists.
     *
     * The second half is the part that matters. Fifteen of these monitors terminate no TLS, and
     * for them the item carries no certificate value at all rather than one with empty fields —
     * which is what keeps "does not apply" apart from "nobody knows".
     */
    @Test
    fun onlyTheMonitorsWithCertificatesCarryACertificateInTheItemModel() {
        val entries = KumaMonitorMapping.overviewOf(monitors()).entries

        assertEquals(35, entries.size)
        assertEquals(20, entries.count { it.certificate != null })
        assertEquals(15, entries.count { it.certificate == null })
    }

    /** The figures arrive unchanged, in the source's own unit of whole days. */
    @Test
    fun theDaysRemainingOfTheSampleArriveInTheItemModel() {
        val days = KumaMonitorMapping.overviewOf(monitors()).entries
            .mapNotNull { entry -> entry.certificate?.daysRemaining }

        assertEquals(20, days.size)
        assertEquals(setOf(40, 43, 66, 651, 4139), days.toSet())
        // The spread is why the horizon for a certificate widget is a decision rather than a
        // derived figure: two of this installation's certificates are years out.
        assertEquals(2, days.count { it > 365 })
    }

    /**
     * The validity gauge is translated, and the sample holds both answers it can give.
     *
     * Eighteen rows say yes and two say no. Neither of the two is called expired anywhere: the
     * gauge is written as a no for every case that is not an explicit yes, so the only defensible
     * reading is that the source does not vouch for them.
     */
    @Test
    fun bothValidityAnswersOfTheSampleAreTranslated() {
        val validities = KumaMonitorMapping.overviewOf(monitors()).entries
            .mapNotNull { entry -> entry.certificate?.validity }

        assertEquals(18, validities.count { it == CertificateValidity.VALID_ACCORDING_TO_SOURCE })
        assertEquals(
            2,
            validities.count { it == CertificateValidity.NOT_VALID_ACCORDING_TO_SOURCE },
        )
        // Nothing here is unknown: every monitor with certificate rows carries both of them.
        assertEquals(0, validities.count { it == CertificateValidity.UNKNOWN })
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
        val overview = KumaMonitorMapping.overviewOf(monitors())
        val snapshot = KumaSnapshotMapper.summarySnapshot(
            instanceId = InstanceId("home-kuma"),
            title = "Uptime Kuma",
            overview = overview,
            retrievedAtEpochMs = 1_700_000_000_000,
        )

        assertEquals("▲35 ▼0", snapshot.primaryValue)
        assertEquals("kuma-home-kuma-summary", snapshot.widgetId.value)
        assertEquals(35, overview.counts.active)
        // Every monitor of this recording was working, so the tile names none of them.
        assertEquals(emptyList(), overview.affected)
    }

    /**
     * The recording describes a real installation, so its monitor names are real names. Checking
     * the snapshot against them is the sharpest available form of the rule that names must not
     * leave the application.
     */
    @Test
    fun noNameFromTheSampleReachesTheSnapshot() {
        val monitors = monitors()
        val snapshot = KumaSnapshotMapper.summarySnapshot(
            instanceId = InstanceId("home-kuma"),
            title = "Uptime Kuma",
            overview = KumaMonitorMapping.overviewOf(monitors),
            retrievedAtEpochMs = 1_700_000_000_000,
        )

        val rendered = snapshot.toString()
        for (name in monitors.mapNotNull { it.name }) {
            assertTrue(!rendered.contains(name), name)
        }
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
