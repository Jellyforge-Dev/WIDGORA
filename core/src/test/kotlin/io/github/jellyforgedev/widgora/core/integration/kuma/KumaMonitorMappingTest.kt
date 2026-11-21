package io.github.jellyforgedev.widgora.core.integration.kuma

import io.github.jellyforgedev.widgora.core.R
import io.github.jellyforgedev.widgora.core.data.CertificateValidity
import io.github.jellyforgedev.widgora.core.data.MonitorEntry
import io.github.jellyforgedev.widgora.core.data.MonitorNote
import io.github.jellyforgedev.widgora.core.data.MonitorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KumaMonitorMappingTest {

    /**
     * Every wire state is covered and no two of them collapse into one neutral state. Written
     * over the enum's own entries rather than a hand-written list, so that adding a wire state
     * makes this test fail rather than leaving the new state untested.
     */
    @Test
    fun everyWireStateMapsToItsOwnNeutralState() {
        val mapped = KumaMonitorStatus.entries.associateWith { status ->
            KumaMonitorMapping.overviewOf(listOf(monitor("1", status))).entries.single().state
        }

        assertEquals(KumaMonitorStatus.entries.size, mapped.values.distinct().size)
        assertEquals(MonitorState.ACTIVE, mapped[KumaMonitorStatus.UP])
        assertEquals(MonitorState.FAILED, mapped[KumaMonitorStatus.DOWN])
        assertEquals(MonitorState.PENDING, mapped[KumaMonitorStatus.PENDING])
        assertEquals(MonitorState.MAINTENANCE, mapped[KumaMonitorStatus.MAINTENANCE])
        assertEquals(MonitorState.UNKNOWN, mapped[KumaMonitorStatus.UNKNOWN])
    }

    @Test
    fun theNameAndTheIdentifierAreCarriedThrough() {
        val entry = KumaMonitorMapping
            .overviewOf(listOf(monitor("42", KumaMonitorStatus.UP, name = "Mail server")))
            .entries
            .single()

        assertEquals("42", entry.monitorId)
        assertEquals("Mail server", entry.name)
    }

    /** A missing name arrives as an absent label; the identifier is then all there is. */
    @Test
    fun aMonitorWithoutANameIsAddressedByItsIdentifier() {
        val entry = KumaMonitorMapping
            .overviewOf(listOf(monitor("42", KumaMonitorStatus.UP, name = null)))
            .entries
            .single()

        assertEquals("42", entry.name)
    }

    @Test
    fun aBlankNameCountsAsAbsent() {
        val entry = KumaMonitorMapping
            .overviewOf(listOf(monitor("42", KumaMonitorStatus.UP, name = "   ")))
            .entries
            .single()

        assertEquals("42", entry.name)
    }

    @Test
    fun anOverLongNameIsCappedRatherThanRejected() {
        val entry = KumaMonitorMapping
            .overviewOf(listOf(monitor("1", KumaMonitorStatus.UP, name = "n".repeat(900))))
            .entries
            .single()

        assertEquals(MonitorEntry.MAX_NAME_CODE_POINTS, entry.name.length)
    }

    @Test
    fun theResponseTimeIsCarriedThroughAndMayBeAbsent() {
        val measured = KumaMonitorMapping
            .overviewOf(listOf(monitor("1", KumaMonitorStatus.UP, responseTimeMs = 183)))
            .entries
            .single()
        assertEquals(183, measured.responseTimeMs)

        val unmeasured = KumaMonitorMapping
            .overviewOf(listOf(monitor("1", KumaMonitorStatus.DOWN, responseTimeMs = null)))
            .entries
            .single()
        assertNull(unmeasured.responseTimeMs)
    }

    @Test
    fun theOrderOfTheResponseIsPreserved() {
        val overview = KumaMonitorMapping.overviewOf(
            listOf(
                monitor("3", KumaMonitorStatus.UP),
                monitor("1", KumaMonitorStatus.UP),
                monitor("2", KumaMonitorStatus.UP),
            ),
        )

        assertEquals(listOf("3", "1", "2"), overview.entries.map { it.monitorId })
    }

    @Test
    fun aResponseWithoutMonitorsYieldsAnEmptyOverview() {
        val overview = KumaMonitorMapping.overviewOf(emptyList())

        assertEquals(emptyList(), overview.entries)
        assertEquals(0L, overview.counts.total)
    }

    // --- The reason behind an unknown state ---------------------------------------------------

    /**
     * A monitor that is unknown because the service contradicted itself carries both halves of an
     * explanation: the condition in neutral terms, and the advice of the integration that saw it.
     *
     * The advice is compared against the resource identifier rather than against a text, because
     * the wording is resolved on the screen in the language of the device.
     */
    @Test
    fun aContradictedMonitorCarriesTheReasonAndTheIntegrationsAdvice() {
        val contradicted = monitor("1", KumaMonitorStatus.UNKNOWN, statusContradicted = true)
        val entry = KumaMonitorMapping.overviewOf(listOf(contradicted)).entries.single()

        assertEquals(MonitorState.UNKNOWN, entry.state)
        val note = entry.note
        assertNotNull(note)
        assertEquals(MonitorNote.Kind.CONTRADICTORY_SOURCE_DATA, note.kind)
        assertEquals(R.string.kuma_note_contradicting_rows, note.sourceAdvice)
    }

    /**
     * An unrecognised wire value is also unknown and deliberately says nothing.
     *
     * There is no advice to give: the user cannot act on a state WIDGORA has not learned, and an
     * invented sentence would be worse than silence.
     */
    @Test
    fun anUnrecognisedStatusCarriesNoNote() {
        val entry = KumaMonitorMapping
            .overviewOf(listOf(monitor("1", KumaMonitorStatus.UNKNOWN)))
            .entries
            .single()

        assertEquals(MonitorState.UNKNOWN, entry.state)
        assertNull(entry.note)
    }

    /** A note is the exception. No state on its own produces one. */
    @Test
    fun noStateOnItsOwnCarriesANote() {
        val notes = KumaMonitorStatus.entries.mapNotNull { status ->
            KumaMonitorMapping.overviewOf(listOf(monitor("1", status))).entries.single().note
        }

        assertEquals(emptyList(), notes)
    }

    // --- Certificates --------------------------------------------------------------------------

    /**
     * A monitor the source wrote no certificate row for carries no certificate at all.
     *
     * The ordinary case: a ping check, a plain connection test. Fifteen of a captured
     * installation's thirty-five monitors are in this position. Handing them a value with empty
     * fields would make "this does not apply" look like "nobody knows".
     */
    @Test
    fun aMonitorWithoutCertificateRowsCarriesNoCertificate() {
        assertNull(mapped(monitor("1", KumaMonitorStatus.UP)).certificate)
    }

    /**
     * The two answers the gauge can give, translated into claims that can be defended.
     *
     * A no from this source is neither "expired" nor "invalid": the gauge is written as zero for
     * every case that is not an explicit yes, a missing statement included. The enum value says
     * exactly that much and no more, which is the whole reason it is not a `Boolean`.
     */
    @Test
    fun theValidityGaugeBecomesAStatementAboutTheSource() {
        val valid = mapped(
            monitor("1", KumaMonitorStatus.UP, certificateValid = true, certificateReported = true),
        )
        val notValid = mapped(
            monitor(
                "2",
                KumaMonitorStatus.UP,
                certificateValid = false,
                certificateReported = true,
            ),
        )

        assertEquals(
            CertificateValidity.VALID_ACCORDING_TO_SOURCE,
            assertNotNull(valid.certificate).validity,
        )
        assertEquals(
            CertificateValidity.NOT_VALID_ACCORDING_TO_SOURCE,
            assertNotNull(notValid.certificate).validity,
        )
    }

    /**
     * Certificate information exists and its validity does not: that is the third state.
     *
     * It is reached two ways — the source wrote a value this version does not recognise, or two
     * rows for the same monitor disagreed — and both leave the same `null` behind. Neither is the
     * same as having no certificate, which is why the item still carries one.
     */
    @Test
    fun aReportedCertificateWithoutAValidityStatementIsUnknown() {
        val entry = mapped(
            monitor(
                "1",
                KumaMonitorStatus.UP,
                certificateValid = null,
                certificateDaysRemaining = 12,
                certificateReported = true,
            ),
        )

        val certificate = assertNotNull(entry.certificate)
        assertEquals(CertificateValidity.UNKNOWN, certificate.validity)
        assertEquals(12, certificate.daysRemaining)
    }

    /** A figure that has run out is the one figure anybody needs, so it is not clamped away. */
    @Test
    fun aFigureAtOrBelowZeroSurvivesTheMapping() {
        for (days in listOf(0, -1, -400)) {
            val entry = mapped(
                monitor(
                    "1",
                    KumaMonitorStatus.UP,
                    certificateValid = false,
                    certificateDaysRemaining = days,
                    certificateReported = true,
                ),
            )
            assertEquals(days, assertNotNull(entry.certificate).daysRemaining)
        }
    }

    private fun mapped(monitor: KumaMonitor): MonitorEntry =
        KumaMonitorMapping.overviewOf(listOf(monitor)).entries.single()

    private fun monitor(
        id: String,
        status: KumaMonitorStatus,
        name: String? = "Service " + id,
        responseTimeMs: Long? = null,
        statusContradicted: Boolean = false,
        certificateValid: Boolean? = null,
        certificateDaysRemaining: Int? = null,
        certificateReported: Boolean = false,
    ): KumaMonitor = KumaMonitor(
        monitorId = id,
        name = name,
        type = "http",
        status = status,
        responseTimeMs = responseTimeMs,
        uptimeRatioByWindow = emptyMap(),
        certificateValid = certificateValid,
        certificateDaysRemaining = certificateDaysRemaining,
        certificateReported = certificateReported,
        statusContradicted = statusContradicted,
    )
}
