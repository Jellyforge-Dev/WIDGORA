package io.github.jellyforgedev.widgora.core.integration.kuma

import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // --- Repeated rows for one monitor --------------------------------------------------------

    /**
     * The defect this replaces. Two status rows used to reject the whole response, which turned
     * four renamed monitors into "answer not readable" for an entire installation.
     */
    @Test
    fun agreeingStatusRowsAreUsedRatherThanRejected() {
        val monitors = success(
            "monitor_status{monitor_id=\"7\",monitor_name=\"Service\"} 1",
            "monitor_status{monitor_id=\"7\",monitor_name=\"Service\"} 1",
        )
        assertEquals(1, monitors.size)
        assertEquals(KumaMonitorStatus.UP, monitors.single().status)
        assertEquals("Service", monitors.single().name)
        assertFalse(monitors.single().statusContradicted)
    }

    /**
     * A contradiction is never resolved in favour of either value.
     *
     * Choosing UP would hide an outage and choosing DOWN would invent one. UNKNOWN is the only
     * answer that is true, and it is visible as a state of its own rather than counted as working.
     */
    @Test
    fun contradictingStatusRowsMakeTheMonitorUnknown() {
        val monitor = success(
            "monitor_status{monitor_id=\"7\"} 1",
            "monitor_status{monitor_id=\"7\"} 0",
        ).single()
        assertEquals(KumaMonitorStatus.UNKNOWN, monitor.status)
    }

    @Test
    fun aContradictionBetweenAnyTwoStatusesIsUnknown() {
        val monitor = success(
            "monitor_status{monitor_id=\"7\"} 2",
            "monitor_status{monitor_id=\"7\"} 3",
        ).single()
        assertEquals(KumaMonitorStatus.UNKNOWN, monitor.status)
    }

    /**
     * The two roads to "unknown" are told apart.
     *
     * They look identical in [KumaMonitorStatus] and are not the same event: one says the service
     * answered twice and disagreed with itself, the other says the service reported a state this
     * version has never heard of. Only the first one can be explained to a user, and the emulator
     * check is what made that matter — four monitors sat there as unknown with nothing to say for
     * themselves while the service's own interface showed all four working.
     */
    @Test
    fun aContradictionIsMarkedAsOneAndAnUnrecognisedValueIsNot() {
        val contradicted = success(
            "monitor_status{monitor_id=\"7\"} 1",
            "monitor_status{monitor_id=\"7\"} 0",
        ).single()
        assertEquals(KumaMonitorStatus.UNKNOWN, contradicted.status)
        assertTrue(contradicted.statusContradicted)

        val unrecognised = success("monitor_status{monitor_id=\"7\"} 9").single()
        assertEquals(KumaMonitorStatus.UNKNOWN, unrecognised.status)
        assertFalse(unrecognised.statusContradicted)
    }

    /** Repetition on its own is not a contradiction, however many times it happens. */
    @Test
    fun manyIdenticalStatusRowsAreStillNotAContradiction() {
        val monitor = success(List(5) { "monitor_status{monitor_id=\"7\"} 1" }.joinToString("\n"))
            .single()

        assertEquals(KumaMonitorStatus.UP, monitor.status)
        assertFalse(monitor.statusContradicted)
    }

    /**
     * A disagreement about a number is not a disagreement about the state.
     *
     * The flag exists to explain an unknown state. Marking a monitor whose state everyone agrees
     * on would attach an explanation to something that needs none.
     */
    @Test
    fun aContradictionAboutSomethingOtherThanTheStatusIsNotMarked() {
        val monitor = success(
            "monitor_status{monitor_id=\"7\"} 1",
            "monitor_response_time{monitor_id=\"7\"} 134",
            "monitor_response_time{monitor_id=\"7\"} 512",
        ).single()

        assertEquals(KumaMonitorStatus.UP, monitor.status)
        assertNull(monitor.responseTimeMs)
        assertFalse(monitor.statusContradicted)
    }

    @Test
    fun agreeingResponseTimesAreKeptAndContradictingOnesAreDropped() {
        val agreeing = success(
            "monitor_status{monitor_id=\"7\"} 1",
            "monitor_response_time{monitor_id=\"7\"} 134",
            "monitor_response_time{monitor_id=\"7\"} 134",
        ).single()
        assertEquals(134L, agreeing.responseTimeMs)

        val contradicting = success(
            "monitor_status{monitor_id=\"7\"} 1",
            "monitor_response_time{monitor_id=\"7\"} 134",
            "monitor_response_time{monitor_id=\"7\"} 512",
        ).single()
        assertNull(contradicting.responseTimeMs)
    }

    /** "No response time" and a number are two different statements, so they contradict. */
    @Test
    fun aNumberAndAnAbsentResponseTimeContradictEachOther() {
        val monitor = success(
            "monitor_status{monitor_id=\"7\"} 1",
            "monitor_response_time{monitor_id=\"7\"} 134",
            "monitor_response_time{monitor_id=\"7\"} -1",
        ).single()
        assertNull(monitor.responseTimeMs)
    }

    /** Only the window that disagrees is lost. The others are still worth having. */
    @Test
    fun aContradictingUptimeWindowIsDroppedWithoutTakingTheOthersWithIt() {
        val monitor = success(
            "monitor_status{monitor_id=\"7\"} 1",
            "monitor_uptime_ratio{monitor_id=\"7\",window=\"1d\"} 1",
            "monitor_uptime_ratio{monitor_id=\"7\",window=\"1d\"} 0.5",
            "monitor_uptime_ratio{monitor_id=\"7\",window=\"30d\"} 0.98",
            "monitor_uptime_ratio{monitor_id=\"7\",window=\"30d\"} 0.98",
        ).single()
        assertEquals(mapOf("30d" to 0.98), monitor.uptimeRatioByWindow)
    }

    /**
     * The name rule, stated as a test so it cannot drift: the smallest by code point.
     *
     * It is deliberately independent of the order the rows arrive in, which is what makes the
     * displayed name the same after every retrieval. Both orders are asserted for that reason.
     */
    @Test
    fun theNameAndTypeAreTheSmallestOfTheCandidatesByCodePoint() {
        val forwards = success(
            "monitor_status{monitor_id=\"7\",monitor_name=\"Zulu\",monitor_type=\"port\"} 1",
            "monitor_status{monitor_id=\"7\",monitor_name=\"Alpha\",monitor_type=\"http\"} 1",
        ).single()
        assertEquals("Alpha", forwards.name)
        assertEquals("http", forwards.type)

        val backwards = success(
            "monitor_status{monitor_id=\"7\",monitor_name=\"Alpha\",monitor_type=\"http\"} 1",
            "monitor_status{monitor_id=\"7\",monitor_name=\"Zulu\",monitor_type=\"port\"} 1",
        ).single()
        assertEquals("Alpha", backwards.name)
        assertEquals("http", backwards.type)
    }

    /** An absent label says nothing, so it cannot contradict a row that does say something. */
    @Test
    fun anAbsentNameDoesNotCompeteWithAPresentOne() {
        val monitor = success(
            "monitor_status{monitor_id=\"7\",monitor_name=\"null\"} 1",
            "monitor_status{monitor_id=\"7\",monitor_name=\"Service\"} 1",
        ).single()
        assertEquals("Service", monitor.name)
    }

    @Test
    fun contradictingCertificateValuesAreDropped() {
        val monitor = success(
            "monitor_status{monitor_id=\"7\"} 1",
            "monitor_cert_is_valid{monitor_id=\"7\"} 1",
            "monitor_cert_is_valid{monitor_id=\"7\"} 0",
            "monitor_cert_days_remaining{monitor_id=\"7\"} 62",
            "monitor_cert_days_remaining{monitor_id=\"7\"} 9",
        ).single()
        assertNull(monitor.certificateValid)
        assertNull(monitor.certificateDaysRemaining)
    }

    /** Repeated rows share an identifier, so they must not count against the monitor limit. */
    @Test
    fun repeatedRowsDoNotCountTowardsTheMonitorLimit() {
        val doubled = statusLines(KumaMetricsReader.MAX_MONITORS) +
            statusLines(KumaMetricsReader.MAX_MONITORS)
        assertEquals(KumaMetricsReader.MAX_MONITORS, success(doubled).size)
    }

    /**
     * The regression test for the observed response.
     *
     * It reproduces the pattern reported from a running installation: after four monitors were
     * edited, exactly those four appeared twice in every monitor family at once, carrying their
     * previous label values, and the response was rejected in full. The identifiers are the ones
     * that were observed; the names are invented, because the real ones are an internal matter.
     */
    @Test
    fun theObservedPatternOfFourEditedMonitorsIsRead() {
        val edited = setOf(15, 23, 33, 35)
        val text = buildString {
            for (id in 1..35) {
                val repeats = if (id in edited) 2 else 1
                for (repeat in 1..repeats) {
                    // The duplicate carries the label values from before the edit.
                    val suffix = if (repeat == 1) "" else " (old)"
                    append("monitor_status{monitor_id=\"$id\",")
                    append("monitor_name=\"Service $id$suffix\",monitor_type=\"http\"} 1\n")
                    append("monitor_response_time{monitor_id=\"$id\"} ${id * 3}\n")
                    for (window in listOf("1d", "7d", "30d", "1y")) {
                        append("monitor_uptime_ratio{monitor_id=\"$id\",window=\"$window\"} 1\n")
                        append("monitor_response_time_seconds{monitor_id=\"$id\",")
                        append("window=\"$window\"} 0.5\n")
                    }
                }
            }
        }

        val monitors = success(text)
        assertEquals(35, monitors.size)
        assertTrue(monitors.all { it.status == KumaMonitorStatus.UP }, "every monitor is up")
        // The duplicated rows agreed about the status and the response time, so both survive.
        val edit = monitors.single { it.monitorId == "15" }
        assertEquals(45L, edit.responseTimeMs)
        assertEquals(1.0, edit.uptimeRatioByWindow["30d"])
        // The names disagreed, and the smaller code point is the one without the suffix.
        assertEquals("Service 15", edit.name)
        assertEquals("Service 1", monitors.single { it.monitorId == "1" }.name)
        // Both rows said the same thing about the state, so there is nothing to explain.
        assertTrue(monitors.none { it.statusContradicted })
    }

    /**
     * The same pattern as the test above, with what the emulator actually showed.
     *
     * The stale row does not only keep the old labels: it keeps the state the monitor had when it
     * was edited. That is what produced the observed result — exactly the four edited monitors
     * unknown while the service's own interface showed all 35 working, and all of them working
     * again after the service was restarted. The response is still read in full; four monitors are
     * unknown, and they are the four that can say why.
     */
    @Test
    fun aStaleStatusRowMakesExactlyTheEditedMonitorsUnknownAndExplainable() {
        val edited = setOf(15, 23, 33, 35)
        val text = buildString {
            for (id in 1..35) {
                append("monitor_status{monitor_id=\"$id\",monitor_name=\"Service $id\"} 1\n")
                if (id !in edited) continue
                // The row left behind by the edit, with the former name and the former state.
                append("monitor_status{monitor_id=\"$id\",monitor_name=\"Service $id (old)\"} 0\n")
            }
        }

        val monitors = success(text)

        assertEquals(35, monitors.size)
        val (contradicted, agreed) = monitors.partition { it.statusContradicted }
        assertEquals(
            edited.map { it.toString() }.sorted(),
            contradicted.map { it.monitorId }.sorted(),
        )
        assertTrue(contradicted.all { it.status == KumaMonitorStatus.UNKNOWN })
        assertEquals(31, agreed.size)
        assertTrue(agreed.all { it.status == KumaMonitorStatus.UP })
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
