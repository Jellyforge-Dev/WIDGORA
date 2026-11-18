package io.github.jellyforgedev.widgora.core.integration.kuma

import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KumaMetricsReaderTest {

    @Test
    fun readsOneMonitorFromAllItsFamilies() {
        val monitors = success(
            "monitor_status{monitor_id=\"7\",monitor_name=\"Service 01\",monitor_type=\"http\"} 1",
            "monitor_response_time{monitor_id=\"7\"} 134",
            "monitor_uptime_ratio{monitor_id=\"7\",window=\"1d\"} 1",
            "monitor_uptime_ratio{monitor_id=\"7\",window=\"30d\"} 0.98",
            "monitor_cert_is_valid{monitor_id=\"7\"} 1",
            "monitor_cert_days_remaining{monitor_id=\"7\"} 62",
        )
        assertEquals(1, monitors.size)
        val monitor = monitors.single()
        assertEquals("7", monitor.monitorId)
        assertEquals("Service 01", monitor.name)
        assertEquals("http", monitor.type)
        assertEquals(KumaMonitorStatus.UP, monitor.status)
        assertEquals(134L, monitor.responseTimeMs)
        assertEquals(mapOf("1d" to 1.0, "30d" to 0.98), monitor.uptimeRatioByWindow)
        assertEquals(true, monitor.certificateValid)
        assertEquals(62, monitor.certificateDaysRemaining)
    }

    @Test
    fun readsEveryStatusValue() {
        assertEquals(KumaMonitorStatus.DOWN, statusOf("0"))
        assertEquals(KumaMonitorStatus.UP, statusOf("1"))
        assertEquals(KumaMonitorStatus.PENDING, statusOf("2"))
        assertEquals(KumaMonitorStatus.MAINTENANCE, statusOf("3"))
        assertEquals(KumaMonitorStatus.UNKNOWN, statusOf("4"))
        assertEquals(KumaMonitorStatus.UNKNOWN, statusOf("NaN"))
    }

    @Test
    fun theStringNullMeansNoValue() {
        val monitor = success(
            "monitor_status{monitor_id=\"7\",monitor_name=\"null\",monitor_type=\"null\"} 1",
        ).single()
        assertNull(monitor.name)
        assertNull(monitor.type)
    }

    @Test
    fun aResponseTimeOfMinusOneMeansNoResponseTime() {
        val monitor = success(
            "monitor_status{monitor_id=\"7\"} 1",
            "monitor_response_time{monitor_id=\"7\"} -1",
        ).single()
        assertNull(monitor.responseTimeMs)
    }

    @Test
    fun aFractionalResponseTimeIsRounded() {
        val monitor = success(
            "monitor_status{monitor_id=\"7\"} 1",
            "monitor_response_time{monitor_id=\"7\"} 0.6",
        ).single()
        assertEquals(1L, monitor.responseTimeMs)
    }

    @Test
    fun twoMonitorsSharingANameRemainTwoMonitors() {
        val monitors = success(
            "monitor_status{monitor_id=\"7\",monitor_name=\"Service 09\"} 1",
            "monitor_status{monitor_id=\"8\",monitor_name=\"Service 09\"} 0",
        )
        assertEquals(listOf("7", "8"), monitors.map { it.monitorId })
        assertEquals(KumaMonitorStatus.UP, monitors.first().status)
        assertEquals(KumaMonitorStatus.DOWN, monitors.last().status)
    }

    @Test
    fun theLabelOrderDoesNotMatter() {
        val monitor = success(
            "monitor_status{monitor_name=\"Service 01\",tag_09=\"x\",monitor_id=\"7\"} 1",
        ).single()
        assertEquals("7", monitor.monitorId)
        assertEquals("Service 01", monitor.name)
    }

    @Test
    fun familiesWithoutAMonitorAreIgnored() {
        val monitors = success(
            "process_open_fds 42",
            "nodejs_version_info{version=\"0\"} 1",
            "expressjs_number_of_open_connections 1",
            "app_version{version=\"0\"} 1",
            "monitor_status{monitor_id=\"7\"} 1",
        )
        assertEquals(1, monitors.size)
    }

    /** An addition to the endpoint must not break a working installation. */
    @Test
    fun unknownMonitorFamiliesAreIgnored() {
        val monitors = success(
            "monitor_status{monitor_id=\"7\"} 1",
            "monitor_response_time_seconds{monitor_id=\"7\",window=\"1d\"} 0.13",
            "monitor_something_new{monitor_id=\"7\"} 5",
        )
        assertEquals(1, monitors.size)
        assertEquals(emptyMap(), monitors.single().uptimeRatioByWindow)
    }

    @Test
    fun anEmptyOrCommentOnlyResponseIsMalformed() {
        assertEquals(Reason.MALFORMED_RESPONSE, failure(""))
        assertEquals(Reason.MALFORMED_RESPONSE, failure("# HELP monitor_status Monitor Status"))
    }

    @Test
    fun aResponseWithoutAnyMonitorIsMalformed() {
        assertEquals(Reason.MALFORMED_RESPONSE, failure("process_open_fds 42"))
    }

    @Test
    fun aMonitorMetricWithoutAnIdentifierIsMalformed() {
        assertEquals(Reason.MALFORMED_RESPONSE, failure("monitor_status{monitor_name=\"x\"} 1"))
        assertEquals(Reason.MALFORMED_RESPONSE, failure("monitor_status{monitor_id=\"\"} 1"))
        assertEquals(Reason.MALFORMED_RESPONSE, failure("monitor_status{monitor_id=\"null\"} 1"))
    }

    @Test
    fun aTruncatedResponseIsMalformed() {
        val reason = failure(
            "monitor_status{monitor_id=\"7\"} 1",
            "monitor_status{monitor_id=\"8\",monitor_nam",
        )
        assertEquals(Reason.MALFORMED_RESPONSE, reason)
    }

    @Test
    fun twoStatusSamplesForOneMonitorAreMalformed() {
        val reason = failure(
            "monitor_status{monitor_id=\"7\"} 1",
            "monitor_status{monitor_id=\"7\"} 0",
        )
        assertEquals(Reason.MALFORMED_RESPONSE, reason)
    }

    /**
     * This is the case that exposes a response whose window label was renamed or dropped. The
     * uptime value would otherwise be filed under an empty window and silently misread.
     */
    @Test
    fun anUptimeSampleWithoutItsWindowIsMalformed() {
        val missing = failure(
            "monitor_status{monitor_id=\"7\"} 1",
            "monitor_uptime_ratio{monitor_id=\"7\"} 0.98",
        )
        assertEquals(Reason.MALFORMED_RESPONSE, missing)
        val empty = failure(
            "monitor_status{monitor_id=\"7\"} 1",
            "monitor_uptime_ratio{monitor_id=\"7\",window=\"\"} 0.98",
        )
        assertEquals(Reason.MALFORMED_RESPONSE, empty)
    }

    @Test
    fun aMonitorDescribedWithoutAStatusIsMalformed() {
        val reason = failure(
            "monitor_status{monitor_id=\"7\"} 1",
            "monitor_cert_is_valid{monitor_id=\"8\"} 1",
        )
        assertEquals(Reason.MALFORMED_RESPONSE, reason)
    }

    @Test
    fun aResponseBeyondTheCharacterLimitIsOversized() {
        val oversized = "m 1\n".repeat(KumaMetricsReader.MAX_INPUT_CHARS / 4 + 1)
        assertTrue(oversized.length > KumaMetricsReader.MAX_INPUT_CHARS)
        val result = KumaMetricsReader.read(oversized)
        assertTrue(result is KumaReadResult.Failure)
        assertEquals(Reason.OVERSIZED, result.reason)
    }

    @Test
    fun theMonitorLimitIsEnforcedAndNotExceeded() {
        val atLimit = success(statusLines(KumaMetricsReader.MAX_MONITORS))
        assertEquals(KumaMetricsReader.MAX_MONITORS, atLimit.size)
        val result = KumaMetricsReader.read(statusLines(KumaMetricsReader.MAX_MONITORS + 1))
        assertTrue(result is KumaReadResult.Failure)
        assertEquals(Reason.OVERSIZED, result.reason)
    }

    private fun statusLines(count: Int): String = buildString {
        for (index in 1..count) {
            append("monitor_status{monitor_id=\"")
            append(index)
            append("\"} 1\n")
        }
    }

    private fun statusOf(wireValue: String): KumaMonitorStatus =
        success("monitor_status{monitor_id=\"7\"} " + wireValue).single().status

    private fun success(vararg lines: String): List<KumaMonitor> = success(lines.joinToString("\n"))

    private fun success(text: String): List<KumaMonitor> {
        val result = KumaMetricsReader.read(text)
        assertTrue(result is KumaReadResult.Success, result.toString())
        return result.monitors
    }

    private fun failure(vararg lines: String): Reason {
        val result = KumaMetricsReader.read(lines.joinToString("\n"))
        assertTrue(result is KumaReadResult.Failure, result.toString())
        return result.reason
    }
}
