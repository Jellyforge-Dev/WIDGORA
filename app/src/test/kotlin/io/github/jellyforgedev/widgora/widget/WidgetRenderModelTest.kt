package io.github.jellyforgedev.widgora.widget

import io.github.jellyforgedev.widgora.core.data.InstanceNotice
import io.github.jellyforgedev.widgora.core.data.MonitorEntry
import io.github.jellyforgedev.widgora.core.data.MonitorOverview
import io.github.jellyforgedev.widgora.core.data.MonitorState
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaSnapshotMapper
import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
import io.github.jellyforgedev.widgora.model.WidgetSnapshot
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetRenderModelTest {

    // --- The field set -------------------------------------------------------------------------

    /**
     * Freezes what a widget can carry.
     *
     * This is the test that stands in for a module boundary. Monitor names used to be unreachable
     * from anything a foreign application sees, because the bridge module could not reference the
     * type that holds them. A widget provider lives in the application module and can reference
     * everything, so the guarantee has to be made here instead — and the honest way to make it is
     * to freeze the field set, so that adding a place for a name fails rather than arriving as
     * part of a feature.
     */
    @Test
    fun theFieldSetOfAnInstanceIsFrozen() {
        val expected = listOf("counts", "state", "title", "updatedAtEpochMs")

        assertEquals(expected, fieldNamesOf(WidgetRenderModel.Instance::class.java))
    }

    /** The two stateless variants carry nothing at all, which is the point of them. */
    @Test
    fun theVariantsWithoutAnInstanceCarryNoFields() {
        assertEquals(emptyList(), fieldNamesOf(WidgetRenderModel.NotSetUp::class.java))
        assertEquals(emptyList(), fieldNamesOf(WidgetRenderModel.ConfigurationUnreadable::class.java))
    }

    /**
     * The field set says no name *can* be carried; this says none *is*.
     *
     * The names here have the shape of the real thing — a monitor name routinely contains an
     * internal host name — and the model is built from an overview holding all of them.
     */
    @Test
    fun nothingAMonitorIsCalledReachesTheModel() {
        val names = listOf("nas.kitchen.invalid", "pve.rack.invalid", "door-sensor.attic.invalid")
        val overview = MonitorOverview(
            names.mapIndexed { index, name -> entry(index, name, MonitorState.FAILED) },
        )

        val model = modelFor(overview)

        val rendered = model.title + " " + model.counts
        for (name in names) {
            assertFalse(rendered.contains(name), "a monitor name reached the widget: $rendered")
        }
    }

    /** What the widget shows as counts is exactly what the snapshot carries, with nothing added. */
    @Test
    fun theCountsAreTakenFromTheSnapshotUnchanged() {
        val snapshot = snapshotOf(overview(active = 4, failed = 1), at = NOW)

        val model = WidgetRenderModels.forInstance(TITLE, OK, snapshot, NOW)

        assertEquals(snapshot.primaryValue, model.counts)
    }

    // --- The counts ----------------------------------------------------------------------------

    /** The marks are the same five the dashboard shows, in the same order. */
    @Test
    fun theCountsAreMarksAndDigitsAndNothingElse() {
        assertEquals("▲35 ▼2 ●1", modelFor(overview(active = 35, failed = 2, unknown = 1)).counts)
    }

    /** Working and failed are named even at zero; the other three only when they occur. */
    @Test
    fun aHealthyInstanceStillNamesBothLeadingCounts() {
        assertEquals("▲12 ▼0", modelFor(overview(active = 12)).counts)
    }

    /** Every state has its own mark, and no two share one. */
    @Test
    fun eachStateContributesItsOwnMark() {
        val counts = modelFor(
            overview(active = 1, failed = 1, pending = 1, maintenance = 1, unknown = 1),
        ).counts

        assertEquals("▲1 ▼1 ◆1 ■1 ●1", counts)
        assertEquals(
            MonitorState.entries.size,
            MonitorState.entries.map { it.symbol }.distinct().size,
        )
    }

    /**
     * An installation with no items has nothing to count, which is not the same as all being
     * well. The empty value is what makes the state come out as "nothing to report".
     */
    @Test
    fun anInstallationWithoutItemsProducesNoCounts() {
        val model = modelFor(overview())

        assertEquals("", model.counts)
        assertEquals(WidgetViewState.Display(WidgetDisplayState.Empty), model.state)
    }

    /** A widget line stays within what a snapshot may hold, which is a bound of the format. */
    @Test
    fun theCountsStayWithinWhatASnapshotMayCarry() {
        val counts = modelFor(
            overview(active = 99, failed = 99, pending = 99, maintenance = 99, unknown = 99),
        ).counts

        assertTrue(
            counts.length <= WidgetSnapshot.MAX_DISPLAY_TEXT_LENGTH,
            "counts were ${counts.length} characters: $counts",
        )
    }

    // --- The title -----------------------------------------------------------------------------

    @Test
    fun aShortTitleIsLeftAlone() {
        assertEquals(TITLE, modelFor(overview(active = 1), title = TITLE).title)
    }

    @Test
    fun aLongTitleIsCutAndSaysSo() {
        val long = "x".repeat(WidgetRenderModels.MAX_TITLE_CODE_POINTS + 10)

        val title = modelFor(overview(active = 1), title = long).title

        assertEquals(WidgetRenderModels.MAX_TITLE_CODE_POINTS, codePointsIn(title))
        assertTrue(title.endsWith(ELLIPSIS))
    }

    /**
     * Cutting counts code points, so a character outside the basic plane is never halved.
     *
     * Half a surrogate pair is not a shorter name, it is a replacement box, and a widget drawn in
     * another process is the last place to discover that.
     */
    @Test
    fun aTitleIsNeverCutInsideACharacter() {
        val astral = "🌀".repeat(WidgetRenderModels.MAX_TITLE_CODE_POINTS)

        val title = modelFor(overview(active = 1), title = astral).title

        assertEquals(WidgetRenderModels.MAX_TITLE_CODE_POINTS, codePointsIn(title))
        assertTrue(hasNoLoneSurrogate(title), "a surrogate pair was cut in half: $title")
    }

    // --- The state -----------------------------------------------------------------------------

    @Test
    fun withoutASnapshotTheWidgetIsLoading() {
        val model = WidgetRenderModels.forInstance(TITLE, OK, snapshot = null, nowEpochMs = NOW)

        assertEquals(WidgetViewState.Display(WidgetDisplayState.Loading), model.state)
        assertNull(model.updatedAtEpochMs)
        assertEquals("", model.counts)
    }

    @Test
    fun theTimestampIsTheOneTheSnapshotCarries() {
        val obtainedAt = NOW - 90_000L

        val model = modelFor(overview(active = 2), ageMs = 90_000L)

        assertEquals(obtainedAt, model.updatedAtEpochMs)
    }

    /** Just inside the threshold. The dashboard would already call this outdated. */
    @Test
    fun dataYoungerThanTheWidgetThresholdIsCurrent() {
        val model = modelFor(overview(active = 3), ageMs = WidgetTiming.STALE_AFTER_MS - 1)

        assertEquals(WidgetViewState.Display(WidgetDisplayState.Ok), model.state)
    }

    @Test
    fun dataAtTheWidgetThresholdIsOutdated() {
        val model = modelFor(overview(active = 3), ageMs = WidgetTiming.STALE_AFTER_MS)

        assertEquals(WidgetViewState.Display(WidgetDisplayState.Stale), model.state)
    }

    /** The number itself, so that changing it is a decision rather than a side effect. */
    @Test
    fun theWidgetThresholdIsFortyFiveMinutes() {
        assertEquals(45L * 60L * 1000L, WidgetTiming.STALE_AFTER_MS)
    }

    /**
     * The widget and the dashboard may disagree about the same snapshot, and that is correct.
     *
     * Four minutes is outdated to a dashboard retrieving every minute, and current to a widget
     * that expects an update every half hour.
     */
    @Test
    fun theWidgetDisagreesWithTheDashboardAboutAFourMinuteOldSnapshot() {
        val model = modelFor(
            overview(active = 3),
            ageMs = 4L * 60L * 1000L,
            dashboardSaid = WidgetDisplayState.Stale,
        )

        assertEquals(WidgetViewState.Display(WidgetDisplayState.Ok), model.state)
    }

    /**
     * A failed retrieval is a fact about an attempt, not about an age.
     *
     * Re-deriving it from the timestamp would quietly turn "the key was rejected" into "this is a
     * bit old", which is a different and far less useful claim.
     */
    @Test
    fun aFailureIsNotReinterpretedAsAnAge() {
        for (state in listOf(
            WidgetDisplayState.Offline,
            WidgetDisplayState.Error(Reason.UNAUTHORIZED),
            WidgetDisplayState.Error(Reason.MALFORMED_RESPONSE),
        )) {
            val model = modelFor(overview(active = 3), dashboardSaid = state)
            assertEquals(WidgetViewState.Display(state), model.state, state.toString())
        }
    }

    /** A notice is about configuration. No timestamp can make it true or false. */
    @Test
    fun aNoticeIsPassedThroughUnchanged() {
        for (notice in InstanceNotice.entries) {
            val model = WidgetRenderModels.forInstance(
                title = TITLE,
                state = WidgetViewState.Notice(notice),
                snapshot = snapshotOf(overview(active = 3), at = NOW),
                nowEpochMs = NOW + WidgetTiming.STALE_AFTER_MS * 10,
            )
            assertEquals(WidgetViewState.Notice(notice), model.state, notice.name)
        }
    }

    /** A clock that disagrees with the server is not data from the future. */
    @Test
    fun aTimestampAheadOfTheClockIsNotOutdated() {
        val model = modelFor(overview(active = 3), ageMs = -WidgetTiming.STALE_AFTER_MS * 2)

        assertEquals(WidgetViewState.Display(WidgetDisplayState.Ok), model.state)
    }

    // --- The identifier an arriving intent carries ---------------------------------------------

    @Test
    fun anIdentifierThisApplicationHasIsAccepted() {
        assertEquals(7, WidgetLaunch.validAppWidgetId(7, intArrayOf(3, 7, 11)))
    }

    /**
     * The list the system gives is the only authority, and the test says exactly that.
     *
     * An earlier version of this test also demanded that a negative value be refused even when it
     * appeared in that list. That was a rule this function does not make and should not make:
     * `getAppWidgetIds` never reports one, so the condition could never occur, and a check for it
     * would be code no situation reaches, defended by a test no situation reaches either.
     */
    @Test
    fun anIdentifierFromNowhereIsIgnored() {
        assertNull(WidgetLaunch.validAppWidgetId(9, intArrayOf(3, 7, 11)))
        assertNull(WidgetLaunch.validAppWidgetId(0, intArrayOf(3, 7, 11)))
        assertNull(WidgetLaunch.validAppWidgetId(7, intArrayOf()))
        assertNull(WidgetLaunch.validAppWidgetId(-1, intArrayOf(3, 7, 11)))
    }

    // --- Helpers -------------------------------------------------------------------------------

    private fun modelFor(
        overview: MonitorOverview,
        title: String = TITLE,
        ageMs: Long = 0L,
        dashboardSaid: WidgetDisplayState = WidgetDisplayState.Ok,
    ): WidgetRenderModel.Instance = WidgetRenderModels.forInstance(
        title = title,
        state = WidgetViewState.Display(dashboardSaid),
        snapshot = snapshotOf(overview, at = NOW - ageMs),
        nowEpochMs = NOW,
    )

    private fun snapshotOf(overview: MonitorOverview, at: Long): WidgetSnapshot =
        KumaSnapshotMapper.summarySnapshot(
            instanceId = INSTANCE_ID,
            title = TITLE,
            overview = overview,
            retrievedAtEpochMs = at,
        )

    /**
     * An overview with the given counts, built from items rather than from a counts object, so
     * the summary is produced the way the application produces it.
     */
    private fun overview(
        active: Int = 0,
        failed: Int = 0,
        pending: Int = 0,
        maintenance: Int = 0,
        unknown: Int = 0,
    ): MonitorOverview {
        var next = 0
        val entries = buildList {
            for ((state, count) in listOf(
                MonitorState.ACTIVE to active,
                MonitorState.FAILED to failed,
                MonitorState.PENDING to pending,
                MonitorState.MAINTENANCE to maintenance,
                MonitorState.UNKNOWN to unknown,
            )) {
                repeat(count) {
                    val id = next++
                    add(entry(id, "monitor-$id.example.invalid", state))
                }
            }
        }
        return MonitorOverview(entries)
    }

    private fun entry(id: Int, name: String, state: MonitorState): MonitorEntry =
        MonitorEntry.truncating(
            monitorId = "m$id",
            name = name,
            state = state,
            responseTimeMs = null,
        )

    private fun fieldNamesOf(type: Class<*>): List<String> = type.declaredFields
        .filterNot { field -> field.isSynthetic || Modifier.isStatic(field.modifiers) }
        .map { field -> field.name }
        .sorted()

    private fun codePointsIn(text: String): Int = text.codePointCount(0, text.length)

    private fun hasNoLoneSurrogate(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char.isHighSurrogate() -> {
                    if (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) return false
                    index += 2
                }

                char.isLowSurrogate() -> return false
                else -> index += 1
            }
        }
        return true
    }

    private companion object {

        val INSTANCE_ID = InstanceId("widget-test")
        val OK = WidgetViewState.Display(WidgetDisplayState.Ok)
        const val TITLE = "Uptime Kuma"
        const val ELLIPSIS = "…"

        /** A fixed instant, so nothing in this file depends on when it runs. */
        const val NOW = 1_700_000_000_000L
    }
}
