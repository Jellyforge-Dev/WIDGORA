package io.github.jellyforgedev.widgora.widget

import io.github.jellyforgedev.widgora.core.data.CertificateInfo
import io.github.jellyforgedev.widgora.core.data.CertificateValidity
import io.github.jellyforgedev.widgora.core.data.MonitorEntry
import io.github.jellyforgedev.widgora.core.data.MonitorOverview
import io.github.jellyforgedev.widgora.core.data.MonitorState
import io.github.jellyforgedev.widgora.core.data.WidgetKind
import io.github.jellyforgedev.widgora.core.data.WidgetSettings
import io.github.jellyforgedev.widgora.core.data.WidgetView
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import io.github.jellyforgedev.widgora.model.WidgetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the two list widgets are told to draw, and what they are never told.
 *
 * The drawing itself is measured on a device. What is decided here is the part that can be wrong
 * without looking wrong: which items appear, in what order, what figure stands beside each, and —
 * the one that matters most — that a widget without consent is handed no name at all.
 */
class WidgetListRenderModelTest {

    // --- Consent ------------------------------------------------------------------------------

    /**
     * A selection is a consent of its own, and an empty one is not.
     *
     * The second half is the load-bearing one: it is what stops "the selection is the consent"
     * from becoming "a selection widget may always name monitors".
     */
    @Test
    fun aSelectionIsAConsentAndAnEmptyOneIsNot() {
        assertNotNull(
            NameConsent.from(
                WidgetSettings(
                    kind = WidgetKind.SELECTION,
                    instanceId = INSTANCE_ID,
                    monitorIds = listOf("1"),
                ),
            ),
        )
        assertNull(
            NameConsent.from(
                WidgetSettings(kind = WidgetKind.SELECTION, instanceId = INSTANCE_ID),
            ),
        )
    }

    /** A certificates widget without the stored yes is not a widget that may name anything. */
    @Test
    fun aCertificatesWidgetNeedsTheStoredYes() {
        assertNull(
            NameConsent.from(
                WidgetSettings(kind = WidgetKind.CERTIFICATES, instanceId = INSTANCE_ID),
            ),
        )
        assertNotNull(
            NameConsent.from(
                WidgetSettings(
                    kind = WidgetKind.CERTIFICATES,
                    instanceId = INSTANCE_ID,
                    showNames = true,
                ),
            ),
        )
    }

    /** Monitor identifiers on a kind that does not pick them are not a consent either. */
    @Test
    fun identifiersOnAnotherKindAreNotAConsent() {
        // The settings type refuses them outright, which is the first line of this defence.
        val refused = runCatching {
            WidgetSettings(
                kind = WidgetKind.CERTIFICATES,
                instanceId = INSTANCE_ID,
                monitorIds = listOf("1"),
            )
        }

        assertTrue(refused.isFailure, "a certificates widget accepted monitor identifiers")
    }

    // --- Certificates -------------------------------------------------------------------------

    @Test
    fun theCertificatesShownAreTheOnesRunningOutOrRejected() {
        val model = certificates(
            entry("ok", certificate = valid(days = 90)),
            entry("soon", certificate = valid(days = 20)),
            entry("rejected", certificate = rejected()),
            entry("plain", certificate = null),
        )

        assertEquals(2, model.total)
        assertEquals(2, model.rows.size)
        assertEquals(
            listOf(
                WidgetListRenderModel.Detail.Rejected,
                WidgetListRenderModel.Detail.Days(20),
            ),
            model.rows.map { row -> row.detail },
        )
    }

    /** The urgency of a figure is a property of the figure, not of how it is drawn. */
    @Test
    fun aFigureInTheSingleDigitsIsUrgentAndOneFurtherOffIsNot() {
        val model = certificates(
            entry("week", certificate = valid(days = 7)),
            entry("month", certificate = valid(days = 8)),
            entry("gone", certificate = valid(days = -2)),
        )

        assertEquals(
            listOf(
                WidgetListRenderModel.Emphasis.URGENT,
                WidgetListRenderModel.Emphasis.URGENT,
                WidgetListRenderModel.Emphasis.SOON,
            ),
            model.rows.map { row -> row.emphasis },
        )
        assertEquals(
            listOf(-2, 7, 8),
            model.rows.map { row ->
                (row.detail as WidgetListRenderModel.Detail.Days).days
            },
        )
    }

    /**
     * An item whose own check is not running carries its mark; a working one does not.
     *
     * The figure of a paused check is one nobody has re-tested, and that is the whole of what the
     * mark says here. A mark on every row would be a column of triangles saying nothing.
     */
    @Test
    fun onlyAnItemWhoseCheckIsNotRunningCarriesItsMark() {
        val model = certificates(
            entry("up", certificate = valid(days = 3), state = MonitorState.ACTIVE),
            entry(
                "paused",
                certificate = valid(days = 4),
                state = MonitorState.MAINTENANCE,
            ),
        )

        assertFalse(
            model.rows[0].text.startsWith(MonitorState.ACTIVE.symbol),
            model.rows[0].text,
        )
        assertTrue(
            model.rows[1].text.startsWith(MonitorState.MAINTENANCE.symbol),
            model.rows[1].text,
        )
    }

    /** The model carries no more rows than the largest box can show, but the whole count. */
    @Test
    fun theModelCarriesTheLargestBudgetAndTheWholeCount() {
        val many = (1..WidgetListRenderModel.MAX_ROWS + 5).map { index ->
            entry("m$index", certificate = valid(days = index))
        }

        val model = certificates(*many.toTypedArray())

        assertEquals(WidgetListRenderModel.MAX_ROWS, model.rows.size)
        assertEquals(many.size, model.total)
    }

    @Test
    fun aCertificatesWidgetTitleIsTheInstanceAndItsStateLineNamesTheSource() {
        val model = certificates(entry("one", certificate = valid(days = 1)))

        assertEquals(TITLE, model.title)
        assertNotNull(model.currentStateLabel)
        assertNotNull(model.emptyMessageArgument)
        assertEquals(WidgetListRenderModel.CERTIFICATE_HORIZON_DAYS, model.emptyMessageArgument)
    }

    /** Nothing retrieved is not "no certificate runs out": the model carries no rows at all. */
    @Test
    fun withNothingRetrievedTheCertificatesWidgetHasNoRows() {
        val model = WidgetListRenderModel.certificates(
            consent = consent(),
            instance = instance(),
            overview = null,
        )

        assertEquals(0, model.total)
        assertTrue(model.rows.isEmpty())
    }

    // --- The selection ------------------------------------------------------------------------

    @Test
    fun thePickedItemsAreShownWithTheirMarkInTheOrderTheyWerePicked() {
        val model = selection(
            monitorIds = listOf("b", "a"),
            entry("a", state = MonitorState.ACTIVE),
            entry("b", state = MonitorState.FAILED),
        )

        assertEquals(2, model.total)
        assertEquals(
            listOf(
                MonitorState.FAILED.symbol + " " + "monitor-b",
                MonitorState.ACTIVE.symbol + " " + "monitor-a",
            ),
            model.rows.map { row -> row.text },
        )
        assertTrue(model.rows.all { row -> row.detail == null })
    }

    /** The title carries the counts of the whole selection in front of the instance's name. */
    @Test
    fun theTitleCarriesTheCountsOfTheWholeSelection() {
        val model = selection(
            monitorIds = listOf("a", "b", "c"),
            entry("a", state = MonitorState.ACTIVE),
            entry("b", state = MonitorState.FAILED),
            entry("c", state = MonitorState.ACTIVE),
        )

        assertEquals("▲2 ▼1 $TITLE", model.title)
    }

    /** An item the source no longer lists is skipped, and the count says what is left. */
    @Test
    fun anItemTheSourceNoLongerListsIsSkippedSilently() {
        val model = selection(monitorIds = listOf("a", "gone"), entry("a"))

        assertEquals(1, model.total)
        assertEquals(1, model.rows.size)
    }

    @Test
    fun aSelectionWithNothingLeftFallsBackToTheInstanceName() {
        val model = selection(monitorIds = listOf("gone"), entry("a"))

        assertEquals(TITLE, model.title)
        assertEquals(0, model.total)
        assertNull(model.currentStateLabel)
    }

    // --- Which model a widget gets ------------------------------------------------------------

    /**
     * The kind decides the model, and the renderers take one model each.
     *
     * This is where a widget placed as one kind could end up drawn as another, so it is asserted
     * rather than read off the code.
     */
    @Test
    fun eachKindIsHandedItsOwnModel() {
        val overview = MonitorOverview(listOf(entry("a", certificate = valid(days = 2))))

        assertIs<WidgetRender.Named>(renderOf(statusSettings(), overview))
        assertIs<WidgetRender.Listed>(renderOf(certificateSettings(), overview))
        assertIs<WidgetRender.Listed>(
            renderOf(selectionSettings(listOf("a")), overview),
        )
    }

    /** A list widget without consent is drawn from the nameless model and lists nothing. */
    @Test
    fun aListWidgetWithoutConsentIsDrawnWithoutNames() {
        val overview = MonitorOverview(listOf(entry("a", certificate = valid(days = 2))))

        val certificates = renderOf(
            WidgetSettings(kind = WidgetKind.CERTIFICATES, instanceId = INSTANCE_ID),
            overview,
        )
        val selection = renderOf(selectionSettings(emptyList()), overview)

        assertIs<WidgetRender.Nameless>(certificates)
        assertIs<WidgetRender.Nameless>(selection)
    }

    /** Neither list model's textual form carries a name, for the reason none of them does. */
    @Test
    fun theTextualFormCarriesNoName() {
        val model = certificates(entry("secret-host", certificate = valid(days = 1)))

        assertFalse(model.toString().contains("secret-host"), model.toString())
        assertFalse(
            model.rows.first().toString().contains("secret-host"),
            model.rows.first().toString(),
        )
    }

    // --- Helpers ------------------------------------------------------------------------------

    private fun renderOf(settings: WidgetSettings, overview: MonitorOverview): WidgetRender =
        WidgetModelSelection.fromMemory(
            kind = settings.kind,
            settings = settings,
            configurationUnreadable = false,
            views = listOf(view(overview)),
            nowEpochMs = NOW,
        )

    /**
     * One instance as the application holds it.
     *
     * No snapshot, deliberately: what these two cases ask is which **model** a kind is handed, and
     * that is decided from the stored settings before anything is drawn. A snapshot here would add
     * a dependency on a type that has nothing to do with the question.
     */
    private fun view(overview: MonitorOverview) = WidgetView(
        instanceId = INSTANCE_ID,
        widgetId = WidgetId("kuma-summary"),
        title = TITLE,
        snapshot = null,
        state = WidgetViewState.Display(WidgetDisplayState.Ok),
        overview = overview,
    )

    private fun statusSettings() = WidgetSettings(
        kind = WidgetKind.STATUS,
        instanceId = INSTANCE_ID,
        showNames = true,
    )

    private fun certificateSettings() = WidgetSettings(
        kind = WidgetKind.CERTIFICATES,
        instanceId = INSTANCE_ID,
        showNames = true,
    )

    private fun selectionSettings(monitorIds: List<String>) = WidgetSettings(
        kind = WidgetKind.SELECTION,
        instanceId = INSTANCE_ID,
        monitorIds = monitorIds,
    )

    private fun certificates(vararg entries: MonitorEntry): WidgetListRenderModel =
        WidgetListRenderModel.certificates(
            consent = consent(),
            instance = instance(),
            overview = MonitorOverview(entries.toList()),
        )

    private fun selection(
        monitorIds: List<String>,
        vararg entries: MonitorEntry,
    ): WidgetListRenderModel = WidgetListRenderModel.selection(
        consent = consent(),
        instance = instance(),
        overview = MonitorOverview(entries.toList()),
        monitorIds = monitorIds,
    )

    /** The one way a consent token can be obtained, in a test as in the application. */
    private fun consent(): NameConsent = requireNotNull(
        NameConsent.from(certificateSettings()),
    ) { "settings that say yes produced no consent" }

    private fun instance() = WidgetRenderModel.Instance(
        title = TITLE,
        state = WidgetViewState.Display(WidgetDisplayState.Ok),
        counts = "▲1",
        updatedAtEpochMs = NOW,
    )

    private fun entry(
        monitorId: String,
        certificate: CertificateInfo? = null,
        state: MonitorState = MonitorState.ACTIVE,
    ) = MonitorEntry.truncating(
        monitorId = monitorId,
        name = "monitor-$monitorId",
        state = state,
        responseTimeMs = null,
        certificate = certificate,
    )

    private fun valid(days: Int) = CertificateInfo(
        validity = CertificateValidity.VALID_ACCORDING_TO_SOURCE,
        daysRemaining = days,
    )

    private fun rejected() = CertificateInfo(
        validity = CertificateValidity.NOT_VALID_ACCORDING_TO_SOURCE,
        daysRemaining = null,
    )

    private companion object {

        val INSTANCE_ID = InstanceId("kuma")
        const val TITLE = "Uptime Kuma"
        const val NOW = 1_700_000_000_000L
    }
}
