package io.github.jellyforgedev.widgora.core.data

import io.github.jellyforgedev.widgora.model.WidgetSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonitorOverviewTest {

    // --- Counts -------------------------------------------------------------------------------

    @Test
    fun theCountsAreOnePerState() {
        val overview = overviewOf(
            MonitorState.ACTIVE,
            MonitorState.ACTIVE,
            MonitorState.FAILED,
            MonitorState.PENDING,
            MonitorState.MAINTENANCE,
            MonitorState.UNKNOWN,
            MonitorState.UNKNOWN,
        )

        assertEquals(
            MonitorCounts(active = 2, failed = 1, pending = 1, maintenance = 1, unknown = 2),
            overview.counts,
        )
        assertEquals(7L, overview.counts.total)
        for (state in MonitorState.entries) {
            assertEquals(
                overview.entries.count { it.state == state },
                overview.counts.of(state),
                state.name,
            )
        }
    }

    @Test
    fun workingAndFailedAreNamedEvenAtZeroAndTheOthersAreNot() {
        val shown = overviewOf(MonitorState.ACTIVE).counts.shown
        assertEquals(listOf(MonitorState.ACTIVE, MonitorState.FAILED), shown.map { it.first })
        assertEquals(listOf(1, 0), shown.map { it.second })
    }

    @Test
    fun anOptionalCountAppearsOnlyWhenItOccurs() {
        val shown = overviewOf(MonitorState.ACTIVE, MonitorState.PENDING).counts.shown
        assertEquals(
            listOf(MonitorState.ACTIVE, MonitorState.FAILED, MonitorState.PENDING),
            shown.map { it.first },
        )
    }

    @Test
    fun aNegativeCountIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            MonitorCounts(active = -1, failed = 0, pending = 0, maintenance = 0, unknown = 0)
        }
    }

    // --- The language-neutral summary ---------------------------------------------------------

    @Test
    fun theSummaryIsMarksAndNumbers() {
        val overview = overviewOf(MonitorState.ACTIVE, MonitorState.ACTIVE, MonitorState.FAILED)
        assertEquals("▲2 ▼1", overview.counts.neutralSummary())
    }

    @Test
    fun theSummaryNamesEveryStateThatOccurs() {
        val overview = overviewOf(
            MonitorState.ACTIVE,
            MonitorState.FAILED,
            MonitorState.PENDING,
            MonitorState.MAINTENANCE,
            MonitorState.UNKNOWN,
        )
        assertEquals(
            "▲1 ▼1 ◆1 ■1 ●1",
            overview.counts.neutralSummary(),
        )
    }

    /** An unrecognised state is not folded into the working count. */
    @Test
    fun anUnknownStateIsNotCountedAsWorking() {
        val overview = overviewOf(MonitorState.UNKNOWN)
        assertEquals("▲0 ▼0 ●1", overview.counts.neutralSummary())
    }

    @Test
    fun noItemsYieldABlankSummaryRatherThanTwoZeroes() {
        assertEquals("", MonitorOverview.Empty.counts.neutralSummary())
    }

    /**
     * The worst case the format allows, not the worst case an installation can produce: every
     * state present and every count at the largest number an `Int` holds. The result still fits a
     * snapshot value, so the bound does not depend on how large an installation grows.
     */
    @Test
    fun theSummaryFitsASnapshotValueEvenInTheWorstCaseTheFormatAllows() {
        val counts = MonitorCounts(
            active = Int.MAX_VALUE,
            failed = Int.MAX_VALUE,
            pending = Int.MAX_VALUE,
            maintenance = Int.MAX_VALUE,
            unknown = Int.MAX_VALUE,
        )

        val summary = counts.neutralSummary()

        assertEquals(MonitorCounts.MAX_SUMMARY_LENGTH, summary.length)
        assertTrue(
            summary.length < WidgetSnapshot.MAX_DISPLAY_TEXT_LENGTH,
            summary.length.toString(),
        )
    }

    // --- Order --------------------------------------------------------------------------------

    @Test
    fun theAffectedAreOrderedFailedUnknownPendingMaintenance() {
        val overview = overviewOf(
            MonitorState.MAINTENANCE,
            MonitorState.ACTIVE,
            MonitorState.PENDING,
            MonitorState.UNKNOWN,
            MonitorState.FAILED,
        )

        assertEquals(
            listOf(
                MonitorState.FAILED,
                MonitorState.UNKNOWN,
                MonitorState.PENDING,
                MonitorState.MAINTENANCE,
            ),
            overview.affected.map { it.state },
        )
    }

    @Test
    fun nothingIsAffectedWhenEverythingIsWorking() {
        val overview = overviewOf(MonitorState.ACTIVE, MonitorState.ACTIVE)
        assertEquals(emptyList(), overview.affected)
        assertTrue(overview.affectedNames(limit = 3, maxNameCodePoints = 20).isEmpty)
    }

    /** Items in the same state keep the order the service listed them in. */
    @Test
    fun theOrderWithinOneStateIsTheOrderItArrivedIn() {
        val overview = MonitorOverview(
            listOf(
                entry("7", MonitorState.FAILED),
                entry("2", MonitorState.FAILED),
                entry("5", MonitorState.FAILED),
            ),
        )
        assertEquals(listOf("7", "2", "5"), overview.affected.map { it.monitorId })
    }

    @Test
    fun theDetailOrderPutsTheAffectedFirstAndTheWorkingLast() {
        val overview = overviewOf(
            MonitorState.ACTIVE,
            MonitorState.MAINTENANCE,
            MonitorState.ACTIVE,
            MonitorState.FAILED,
        )

        assertEquals(
            listOf(
                MonitorState.FAILED,
                MonitorState.MAINTENANCE,
                MonitorState.ACTIVE,
                MonitorState.ACTIVE,
            ),
            overview.inDetailOrder.map { it.state },
        )
        assertEquals(overview.entries.size, overview.inDetailOrder.size)
    }

    // --- The names a tile shows ---------------------------------------------------------------

    @Test
    fun atMostThreeNamesAreShownAndTheRestAreCounted() {
        val overview = MonitorOverview(
            (1..7).map { index -> entry(index.toString(), MonitorState.FAILED) },
        )

        val affected = overview.affectedNames(limit = 3, maxNameCodePoints = 20)

        assertEquals(listOf("Service 1", "Service 2", "Service 3"), affected.names)
        assertEquals(4, affected.remaining)
        assertFalse(affected.isEmpty)
    }

    @Test
    fun fewerAffectedThanTheLimitLeavesNothingOver() {
        val overview = MonitorOverview(listOf(entry("1", MonitorState.FAILED)))
        val affected = overview.affectedNames(limit = 3, maxNameCodePoints = 20)

        assertEquals(listOf("Service 1"), affected.names)
        assertEquals(0, affected.remaining)
    }

    /**
     * Shortening counts code points. The name here is built from a character outside the basic
     * plane, which occupies two chars; cutting by char index would leave half of one behind and a
     * renderer would draw a replacement box.
     */
    @Test
    fun aLongNameIsShortenedByCodePointsAndMarkedAsShortened() {
        val astral = "😀"
        val overview = MonitorOverview(
            listOf(
                MonitorEntry(
                    monitorId = "1",
                    name = "ab" + astral.repeat(10),
                    state = MonitorState.FAILED,
                    responseTimeMs = null,
                ),
            ),
        )

        val shortened = overview.affectedNames(limit = 1, maxNameCodePoints = 5).names.single()

        assertEquals("ab" + astral + astral + "…", shortened)
        assertEquals(5, shortened.codePointCount(0, shortened.length))
    }

    @Test
    fun aNameThatFitsIsLeftAlone() {
        val overview = MonitorOverview(listOf(entry("1", MonitorState.FAILED)))
        assertEquals(
            "Service 1",
            overview.affectedNames(limit = 1, maxNameCodePoints = 20).names.single(),
        )
    }

    @Test
    fun aLimitOfZeroShowsNoNamesAndCountsThemAll() {
        val overview = MonitorOverview(
            listOf(entry("1", MonitorState.FAILED), entry("2", MonitorState.FAILED)),
        )
        val affected = overview.affectedNames(limit = 0, maxNameCodePoints = 20)

        assertEquals(emptyList(), affected.names)
        assertEquals(2, affected.remaining)
    }

    // --- The stored entry ---------------------------------------------------------------------

    @Test
    fun anOverLongNameIsCappedByTheFactory() {
        val entry = MonitorEntry.truncating(
            monitorId = "1",
            name = "n".repeat(500),
            state = MonitorState.ACTIVE,
            responseTimeMs = null,
        )
        assertEquals(MonitorEntry.MAX_NAME_CODE_POINTS, entry.name.length)
    }

    @Test
    fun theConstructorRefusesWhatTheFactoryWouldHaveCapped() {
        assertFailsWith<IllegalArgumentException> {
            MonitorEntry(
                monitorId = "1",
                name = "n".repeat(500),
                state = MonitorState.ACTIVE,
                responseTimeMs = null,
            )
        }
    }

    @Test
    fun aBlankNameOrIdentifierIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            MonitorEntry("1", " ", MonitorState.ACTIVE, null)
        }
        assertFailsWith<IllegalArgumentException> {
            MonitorEntry(" ", "Service", MonitorState.ACTIVE, null)
        }
    }

    @Test
    fun aNegativeResponseTimeIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            MonitorEntry("1", "Service", MonitorState.ACTIVE, -1)
        }
    }

    // --- The marks ----------------------------------------------------------------------------

    @Test
    fun everyStateHasItsOwnSingleCharacterMark() {
        val symbols = MonitorState.entries.map { it.symbol }

        assertEquals(MonitorState.entries.size, symbols.distinct().size)
        for (symbol in symbols) {
            assertEquals(1, symbol.length, symbol)
            assertEquals(1, symbol.codePointCount(0, symbol.length), symbol)
        }
    }

    @Test
    fun theOrdersCoverEveryState() {
        assertEquals(MonitorState.entries.size, MonitorState.DETAIL_ORDER.size)
        assertEquals(MonitorState.entries.toSet(), MonitorState.DETAIL_ORDER.toSet())
        assertEquals(MonitorState.entries.size, MonitorState.COUNT_ORDER.size)
        assertEquals(MonitorState.entries.toSet(), MonitorState.COUNT_ORDER.toSet())
        assertFalse(MonitorState.AFFECTED_ORDER.contains(MonitorState.ACTIVE))
    }

    private fun overviewOf(vararg states: MonitorState): MonitorOverview = MonitorOverview(
        states.mapIndexed { index, state -> entry((index + 1).toString(), state) },
    )

    private fun entry(id: String, state: MonitorState): MonitorEntry = MonitorEntry(
        monitorId = id,
        name = "Service " + id,
        state = state,
        responseTimeMs = null,
    )
}
