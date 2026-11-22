package io.github.jellyforgedev.widgora.core.data

import io.github.jellyforgedev.widgora.core.integration.kuma.KumaMetricsReader
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaMonitorMapping
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaReadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the two list selections include, in what order, and what they refuse to claim.
 *
 * Most cases are built from values rather than read from the recording, because the recording has
 * no certificate inside thirty days and none in the single digits — the two cases the whole
 * ordering exists for. The recording is used for the one thing only it can show: that the rule
 * applied to a real installation picks out the items a person would pick out by hand.
 */
class MonitorPicksTest {

    // --- Certificates -------------------------------------------------------------------------

    @Test
    fun anItemWithoutACertificateRowNeverAppears() {
        val overview = MonitorOverview(
            listOf(
                entry("1", "ping-check", certificate = null),
                entry("2", "service", certificate = CertificateInfo(VALID, daysRemaining = 3)),
            ),
        )

        val watch = overview.certificatesWithin(HORIZON, LIMIT, NAME_LENGTH)

        assertEquals(listOf("2"), watch.items.map { it.monitorId })
        assertEquals(1, watch.total)
    }

    @Test
    fun aFigureBeyondTheHorizonIsLeftOut() {
        val overview = MonitorOverview(
            listOf(
                entry("in", "in-horizon", CertificateInfo(VALID, daysRemaining = HORIZON)),
                entry("out", "beyond", CertificateInfo(VALID, daysRemaining = HORIZON + 1)),
            ),
        )

        val watch = overview.certificatesWithin(HORIZON, LIMIT, NAME_LENGTH)

        assertEquals(listOf("in"), watch.items.map { it.monitorId })
    }

    /**
     * A certificate the source will not vouch for appears whatever its figure says, including
     * when it says nothing.
     */
    @Test
    fun whatTheSourceWillNotVouchForAppearsWithOrWithoutAFigure() {
        val overview = MonitorOverview(
            listOf(
                entry("far", "far", CertificateInfo(NOT_VALID, daysRemaining = 900)),
                entry("none", "none", CertificateInfo(NOT_VALID, daysRemaining = null)),
            ),
        )

        val watch = overview.certificatesWithin(HORIZON, LIMIT, NAME_LENGTH)

        assertEquals(2, watch.total)
        assertEquals(listOf("far", "none"), watch.items.map { it.monitorId })
    }

    /**
     * An unknown validity is not on its own a reason to appear.
     *
     * The source either gave a figure, in which case the figure decides, or it gave neither a
     * figure nor a yes — which is the no-statement value, not this one.
     */
    @Test
    fun anUnknownValidityWithoutAFigureIsNotAReasonToAppear() {
        val overview = MonitorOverview(
            listOf(entry("1", "unclear", CertificateInfo(UNKNOWN, daysRemaining = null))),
        )

        assertTrue(overview.certificatesWithin(HORIZON, LIMIT, NAME_LENGTH).isEmpty)
    }

    @Test
    fun whatTheSourceRejectsComesFirstAndTheRestByDaysAscending() {
        val overview = MonitorOverview(
            listOf(
                entry("d20", "twenty", CertificateInfo(VALID, daysRemaining = 20)),
                entry("r2", "rejected-two", CertificateInfo(NOT_VALID, daysRemaining = 5)),
                entry("d1", "one", CertificateInfo(VALID, daysRemaining = 1)),
                entry("r1", "rejected-one", CertificateInfo(NOT_VALID, daysRemaining = null)),
                entry("d7", "seven", CertificateInfo(UNKNOWN, daysRemaining = 7)),
            ),
        )

        val watch = overview.certificatesWithin(HORIZON, LIMIT, NAME_LENGTH)

        assertEquals(
            listOf("r2", "r1", "d1", "d7", "d20"),
            watch.items.map { it.monitorId },
        )
    }

    /** A figure that has run out is still a figure. Nothing is clamped on the way through. */
    @Test
    fun aFigureThatHasRunOutIsPassedThroughUnchanged() {
        val overview = MonitorOverview(
            listOf(
                entry("gone", "gone", CertificateInfo(VALID, daysRemaining = -3)),
                entry("today", "today", CertificateInfo(VALID, daysRemaining = 0)),
            ),
        )

        val watch = overview.certificatesWithin(HORIZON, LIMIT, NAME_LENGTH)

        assertEquals(listOf(-3, 0), watch.items.map { it.daysRemaining })
    }

    /** The item's own state travels with it: a figure from a check that is not running. */
    @Test
    fun theItemsOwnStateTravelsWithIt() {
        val overview = MonitorOverview(
            listOf(
                entry(
                    "1",
                    "paused",
                    CertificateInfo(VALID, daysRemaining = 2),
                    state = MonitorState.MAINTENANCE,
                ),
            ),
        )

        val watch = overview.certificatesWithin(HORIZON, LIMIT, NAME_LENGTH)

        assertEquals(MonitorState.MAINTENANCE, watch.items.single().state)
    }

    @Test
    fun theLimitCutsTheItemsAndNotTheTotal() {
        val overview = MonitorOverview(
            (1..10).map { index ->
                entry("m$index", "name-$index", CertificateInfo(VALID, daysRemaining = index))
            },
        )

        val watch = overview.certificatesWithin(HORIZON, limit = 3, maxNameCodePoints = NAME_LENGTH)

        assertEquals(3, watch.items.size)
        assertEquals(10, watch.total)
        assertFalse(watch.isEmpty)
    }

    @Test
    fun aNameIsShortenedAndSaysSo() {
        val name = "a".repeat(40)
        val overview = MonitorOverview(
            listOf(entry("1", name, CertificateInfo(VALID, daysRemaining = 1))),
        )

        val shown = overview.certificatesWithin(HORIZON, LIMIT, maxNameCodePoints = 10)
            .items
            .single()
            .name

        assertEquals(10, shown.length)
        assertTrue(shown.endsWith("…"), shown)
    }

    /**
     * The rule against the recording of a real installation.
     *
     * Twenty of its thirty-five items carry certificate rows, none of them inside thirty days, and
     * two of them are items the source will not vouch for. So the watch list is exactly those two —
     * which is the answer somebody reading the recording by hand arrives at.
     */
    @Test
    fun theRecordedInstallationYieldsTheTwoItsSourceWillNotVouchFor() {
        val overview = recordedOverview()

        val watch = overview.certificatesWithin(HORIZON, LIMIT, NAME_LENGTH)

        assertEquals(2, watch.total)
        assertTrue(
            watch.items.all { item -> item.validity == NOT_VALID },
            watch.items.map { it.validity }.toString(),
        )
        assertEquals(
            overview.entries.count { entry ->
                entry.certificate?.validity == NOT_VALID
            },
            watch.total,
        )
    }

    @Test
    fun aHorizonLargeEnoughReachesEveryStatedFigureOfTheRecording() {
        val overview = recordedOverview()

        val watch = overview.certificatesWithin(
            horizonDays = Int.MAX_VALUE,
            limit = Int.MAX_VALUE,
            maxNameCodePoints = NAME_LENGTH,
        )

        assertEquals(
            overview.entries.count { entry -> entry.certificate != null },
            watch.total,
        )
    }

    // --- The picked items ---------------------------------------------------------------------

    @Test
    fun thePickedItemsKeepTheOrderTheyWerePickedIn() {
        val overview = MonitorOverview(
            listOf(entry("a", "first"), entry("b", "second"), entry("c", "third")),
        )

        val selection = overview.selected(listOf("c", "a"), LIMIT, NAME_LENGTH)

        assertEquals(listOf("c", "a"), selection.items.map { it.monitorId })
    }

    @Test
    fun anItemTheSourceNoLongerListsIsSkippedWithoutAWord() {
        val overview = MonitorOverview(listOf(entry("a", "first")))

        val selection = overview.selected(listOf("gone", "a", "also-gone"), LIMIT, NAME_LENGTH)

        assertEquals(listOf("a"), selection.items.map { it.monitorId })
        assertEquals(1, selection.total)
    }

    @Test
    fun aPickedItemNamedTwiceAppearsOnce() {
        val overview = MonitorOverview(listOf(entry("a", "first")))

        val selection = overview.selected(listOf("a", "a"), LIMIT, NAME_LENGTH)

        assertEquals(1, selection.total)
        assertEquals(1, selection.items.size)
    }

    /** The figure at the top describes the whole selection, not the part that fitted. */
    @Test
    fun theCountsDescribeEveryFoundItemAndNotOnlyThoseShown() {
        val overview = MonitorOverview(
            listOf(
                entry("a", "a", state = MonitorState.ACTIVE),
                entry("b", "b", state = MonitorState.FAILED),
                entry("c", "c", state = MonitorState.ACTIVE),
            ),
        )

        val selection = overview.selected(
            monitorIds = listOf("a", "b", "c"),
            limit = 1,
            maxNameCodePoints = NAME_LENGTH,
        )

        assertEquals(1, selection.items.size)
        assertEquals(3, selection.total)
        assertEquals(
            MonitorCounts(active = 2, failed = 1, pending = 0, maintenance = 0, unknown = 0),
            selection.counts,
        )
    }

    @Test
    fun nothingPickedIsNothingShown() {
        val overview = MonitorOverview(listOf(entry("a", "first")))

        val selection = overview.selected(emptyList(), LIMIT, NAME_LENGTH)

        assertTrue(selection.isEmpty)
        assertEquals(0L, selection.counts.total)
    }

    @Test
    fun theArgumentsAreChecked() {
        val overview = MonitorOverview(listOf(entry("a", "first")))

        assertFailsWith<IllegalArgumentException> {
            overview.certificatesWithin(horizonDays = -1, limit = LIMIT, maxNameCodePoints = 8)
        }
        assertFailsWith<IllegalArgumentException> {
            overview.certificatesWithin(horizonDays = HORIZON, limit = -1, maxNameCodePoints = 8)
        }
        assertFailsWith<IllegalArgumentException> {
            overview.certificatesWithin(horizonDays = HORIZON, limit = LIMIT, maxNameCodePoints = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            overview.selected(listOf("a"), limit = -1, maxNameCodePoints = 8)
        }
        assertFailsWith<IllegalArgumentException> {
            overview.selected(listOf("a"), limit = LIMIT, maxNameCodePoints = 1)
        }
    }

    // --- Helpers ------------------------------------------------------------------------------

    private fun entry(
        monitorId: String,
        name: String,
        certificate: CertificateInfo? = null,
        state: MonitorState = MonitorState.ACTIVE,
    ): MonitorEntry = MonitorEntry.truncating(
        monitorId = monitorId,
        name = name,
        state = state,
        responseTimeMs = null,
        certificate = certificate,
    )

    private fun recordedOverview(): MonitorOverview {
        val stream = checkNotNull(javaClass.getResourceAsStream(RESOURCE)) {
            "test resource $RESOURCE is missing"
        }
        val text = stream.bufferedReader().use { reader -> reader.readText() }
        val result = KumaMetricsReader.read(text)
        assertTrue(result is KumaReadResult.Success, result.toString())
        return KumaMonitorMapping.overviewOf(result.monitors)
    }

    private companion object {

        const val RESOURCE = "/kuma/metrics-sample.txt"
        const val HORIZON = 30
        const val LIMIT = 8
        const val NAME_LENGTH = 20

        val VALID = CertificateValidity.VALID_ACCORDING_TO_SOURCE
        val NOT_VALID = CertificateValidity.NOT_VALID_ACCORDING_TO_SOURCE
        val UNKNOWN = CertificateValidity.UNKNOWN
    }
}
