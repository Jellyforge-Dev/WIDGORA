package io.github.jellyforgedev.widgora.design

import androidx.compose.ui.graphics.Color
import io.github.jellyforgedev.widgora.core.data.InstanceNotice
import io.github.jellyforgedev.widgora.core.data.MonitorEntry
import io.github.jellyforgedev.widgora.core.data.MonitorNote
import io.github.jellyforgedev.widgora.core.data.MonitorOverview
import io.github.jellyforgedev.widgora.core.data.MonitorState
import io.github.jellyforgedev.widgora.core.data.WidgetView
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaSnapshotMapper
import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesignSystemTest {

    // --- Contrast -----------------------------------------------------------------------------

    /** Checks the formula itself before trusting it about the palette. */
    @Test
    fun theContrastFormulaMatchesItsKnownExtremes() {
        assertTrue(abs(Contrast.ratio(Color.Black, Color.White) - 21.0) < 0.01)
        assertTrue(abs(Contrast.ratio(Color.White, Color.White) - 1.0) < 0.01)
        assertTrue(abs(Contrast.relativeLuminance(Color.White) - 1.0) < 0.001)
        assertTrue(abs(Contrast.relativeLuminance(Color.Black)) < 0.001)
    }

    @Test
    fun everyTextAndStateTokenClearsAaOnTheBackdrop() {
        for ((name, color) in WidgoraColors.TextAndStateTokens) {
            val ratio = Contrast.ratio(color, WidgoraColors.Backdrop)
            assertTrue(ratio >= Contrast.AA_TEXT, "$name on the backdrop is only $ratio")
        }
    }

    @Test
    fun everyTextAndStateTokenClearsAaOnASurface() {
        for ((name, color) in WidgoraColors.TextAndStateTokens) {
            val ratio = Contrast.ratio(color, WidgoraColors.Surface)
            assertTrue(ratio >= Contrast.AA_TEXT, "$name on a surface is only $ratio")
        }
    }

    /** A border is not text, but it still has to be visible: WCAG asks 3 : 1 of it. */
    @Test
    fun theOutlineClearsTheNonTextThreshold() {
        assertTrue(
            Contrast.ratio(WidgoraColors.Outline, WidgoraColors.Backdrop) >= Contrast.AA_NON_TEXT,
        )
        assertTrue(
            Contrast.ratio(WidgoraColors.Outline, WidgoraColors.Surface) >= Contrast.AA_NON_TEXT,
        )
    }

    // --- Focus --------------------------------------------------------------------------------

    /**
     * The focus ring sits between a surface and the backdrop, so it is checked against both.
     *
     * It is held to the text threshold rather than the 3 : 1 asked of a boundary. A focus mark is
     * the one thing a viewer holding a remote has to be able to find, and there is no reason to
     * settle for the lower bar when the palette clears the higher one.
     */
    @Test
    fun theFocusMarkClearsTheTextThresholdOnBothSurfaces() {
        assertTrue(
            Contrast.ratio(WidgoraColors.Focus, WidgoraColors.Backdrop) >= Contrast.AA_TEXT,
            "focus on the backdrop",
        )
        assertTrue(
            Contrast.ratio(WidgoraColors.Focus, WidgoraColors.Surface) >= Contrast.AA_TEXT,
            "focus on a surface",
        )
    }

    /**
     * Focused and unfocused have to be told apart, which is a statement about the two borders
     * together and not about either of them alone.
     */
    @Test
    fun theFocusMarkStandsClearOfTheRestingBorder() {
        val focus = Contrast.ratio(WidgoraColors.Focus, WidgoraColors.Surface)
        val resting = Contrast.ratio(WidgoraColors.Outline, WidgoraColors.Surface)
        assertTrue(focus > resting * 2, "focus $focus is not clearly brighter than resting $resting")
        assertTrue(WidgoraFocus.BorderWidth > WidgoraFocus.RestingBorderWidth)
    }

    /** The brand colour is for the primary action. Focus is neutral; this holds them apart. */
    @Test
    fun theFocusMarkIsNotTheBrandColour() {
        assertTrue(WidgoraColors.Focus != WidgoraColors.BrandViolet)
        assertTrue(WidgoraColors.Focus != WidgoraColors.BrandIndigo)
        assertTrue(WidgoraColors.Focus != WidgoraColors.Outline)
    }

    /** Neutral means carrying no hue, which is what keeps it clear of the state colours. */
    @Test
    fun theFocusMarkCarriesNoHue() {
        assertEquals(WidgoraColors.Focus.red, WidgoraColors.Focus.green)
        assertEquals(WidgoraColors.Focus.green, WidgoraColors.Focus.blue)
        assertTrue(WidgoraColors.Focus !in WidgoraColors.TextAndStateTokens.values)
    }

    /**
     * The regression test for the clipped focus mark.
     *
     * A tile grows about its centre, so it needs half of its growth beside itself. Should a later
     * change widen the tile or strengthen the enlargement without raising the headroom, the mark
     * would be cut off at the first and last position of the row — and this fails instead.
     */
    @Test
    fun theScaleHeadroomCoversTheWidestFocusableElement() {
        val growthPerSide = WidgoraSizes.Tile.value * (WidgoraFocus.Scale - 1f) / 2f
        assertTrue(
            WidgoraFocus.ScaleHeadroom.value >= growthPerSide,
            "a focused tile grows $growthPerSide dp per side but only " +
                "${WidgoraFocus.ScaleHeadroom.value} dp is reserved",
        )
    }

    @Test
    fun theFocusHelpersReturnTheTokensAndNothingElse() {
        assertEquals(WidgoraFocus.BorderWidth, WidgoraFocus.borderWidth(focused = true))
        assertEquals(WidgoraFocus.RestingBorderWidth, WidgoraFocus.borderWidth(focused = false))
        assertEquals(WidgoraColors.Focus, WidgoraFocus.borderColor(focused = true))
        assertEquals(WidgoraColors.Outline, WidgoraFocus.borderColor(focused = false))
    }

    /**
     * The measured indigo is too dark to carry text against the backdrop. The test states that
     * on purpose: it is a fill, and text on it is the light foreground, which does clear AA.
     */
    @Test
    fun theIndigoIsAFillAndNotAForeground() {
        assertTrue(
            Contrast.ratio(WidgoraColors.BrandIndigo, WidgoraColors.Backdrop) < Contrast.AA_TEXT,
        )
        assertTrue(
            Contrast.ratio(WidgoraColors.OnSurface, WidgoraColors.BrandIndigo) >= Contrast.AA_TEXT,
        )
    }

    /** A violet fill carries the dark backdrop colour as its label, never the light one. */
    @Test
    fun aVioletFillCarriesTheDarkLabel() {
        assertTrue(
            Contrast.ratio(WidgoraColors.Backdrop, WidgoraColors.BrandViolet) >= Contrast.AA_TEXT,
        )
        assertTrue(
            Contrast.ratio(WidgoraColors.OnSurface, WidgoraColors.BrandViolet) < Contrast.AA_TEXT,
        )
    }

    // --- Text sizes ---------------------------------------------------------------------------

    @Test
    fun noTextSizeFallsBelowTheTelevisionMinimum() {
        for ((name, style) in WidgoraText.All) {
            assertTrue(
                style.size.value >= WidgoraText.Minimum.value,
                "$name is ${style.size.value} sp",
            )
        }
    }

    /**
     * The regression test for the overlap defect.
     *
     * A line height left at the ambient value while the size was raised drew the second line of a
     * wrapped text on top of the first. Every token now carries its own line height, and this
     * holds the ratio, so a later size change cannot silently reintroduce it.
     */
    @Test
    fun everyLineHeightKeepsWrappedLinesApart() {
        for ((name, style) in WidgoraText.All) {
            val factor = style.lineHeight.value / style.size.value
            assertTrue(
                factor >= WidgoraText.MIN_LINE_HEIGHT_FACTOR,
                "$name has a line height of only $factor times its size",
            )
        }
    }

    @Test
    fun everyTextStyleIsListedForTheseChecks() {
        assertEquals(5, WidgoraText.All.size)
        assertEquals(WidgoraText.All.size, WidgoraText.All.map { it.first }.distinct().size)
    }

    // --- State presentation -------------------------------------------------------------------

    @Test
    fun everyDisplayStateHasItsOwnPresentation() {
        val presentations = displayStates().map {
            WidgetPresentation.of(WidgetViewState.Display(it))
        }

        assertEquals(6, presentations.size)
        assertTrue(presentations.all { it.label != 0 })
        assertEquals(6, presentations.map { it.label }.distinct().size)
        assertEquals(6, presentations.map { it.mark }.distinct().size)
    }

    /** Colour alone never carries a state: the label and the mark say it too. */
    @Test
    fun noStateIsDistinguishedByColourAlone() {
        val all = displayStates().map { WidgetPresentation.of(WidgetViewState.Display(it)) } +
            InstanceNotice.entries.map { WidgetPresentation.of(WidgetViewState.Notice(it)) }

        val byColour = mutableMapOf<Color, MutableList<WidgetPresentation>>()
        for (presentation in all) {
            byColour.getOrPut(presentation.color) { mutableListOf() }.add(presentation)
        }
        for ((_, sharing) in byColour) {
            assertEquals(
                sharing.size,
                sharing.map { it.label }.distinct().size,
                sharing.toString(),
            )
        }
    }

    @Test
    fun everyErrorReasonIsNamed() {
        val labels = Reason.entries.map { reason ->
            WidgetPresentation.of(WidgetViewState.Display(WidgetDisplayState.Error(reason))).label
        }
        assertTrue(labels.all { it != 0 })
        // Each reason gets its own wording. The unreachable reason borrows the wording of the
        // offline state, because it is the same statement, but no two reasons share one.
        assertEquals(Reason.entries.size, labels.distinct().size)
        assertEquals(
            WidgetPresentation.of(WidgetViewState.Display(WidgetDisplayState.Offline)).label,
            WidgetPresentation.of(
                WidgetViewState.Display(WidgetDisplayState.Error(Reason.UNREACHABLE)),
            ).label,
        )
    }

    @Test
    fun everyNoticeHasItsOwnPresentation() {
        val presentations = InstanceNotice.entries.map {
            WidgetPresentation.of(WidgetViewState.Notice(it))
        }

        assertEquals(3, presentations.size)
        assertEquals(3, presentations.map { it.label }.distinct().size)
        // A notice never shows a value: nothing was retrieved.
        assertTrue(presentations.none { it.showsData })
        assertTrue(presentations.none { it.valueIsOutdated })
    }

    @Test
    fun onlyTheFailingStatesMarkTheirValueAsOutdated() {
        assertFalse(presentationOf(WidgetDisplayState.Ok).valueIsOutdated)
        assertFalse(presentationOf(WidgetDisplayState.Empty).valueIsOutdated)
        assertFalse(presentationOf(WidgetDisplayState.Loading).valueIsOutdated)
        assertTrue(presentationOf(WidgetDisplayState.Stale).valueIsOutdated)
        assertTrue(presentationOf(WidgetDisplayState.Offline).valueIsOutdated)
        assertTrue(presentationOf(WidgetDisplayState.Error(Reason.OVERSIZED)).valueIsOutdated)
    }

    @Test
    fun loadingShowsNoValueBecauseThereIsNone() {
        assertFalse(presentationOf(WidgetDisplayState.Loading).showsData)
        assertTrue(presentationOf(WidgetDisplayState.Ok).showsData)
    }

    // --- Monitor state presentation -----------------------------------------------------------

    @Test
    fun everyMonitorStateHasItsOwnMarkColourAndWords() {
        val all = MonitorPresentation.All

        assertEquals(MonitorState.entries.size, all.size)
        assertEquals(all.size, all.map { it.state }.distinct().size)
        assertEquals(all.size, all.map { it.symbol }.distinct().size)
        assertEquals(all.size, all.map { it.word }.distinct().size)
        assertEquals(all.size, all.map { it.countPhrase }.distinct().size)
        assertTrue(all.all { it.word != 0 && it.countPhrase != 0 })
    }

    /** Shape and colour both carry the state. Neither alone has to do the whole job. */
    @Test
    fun noMonitorStateIsDistinguishedByColourAlone() {
        val all = MonitorPresentation.All
        assertEquals(all.size, all.map { it.color }.distinct().size)
    }

    /** Every monitor colour is one of the tokens the contrast tests above already cover. */
    @Test
    fun everyMonitorColourIsACheckedToken() {
        val checked = WidgoraColors.TextAndStateTokens.values.toSet()
        for (presentation in MonitorPresentation.All) {
            assertTrue(
                presentation.color in checked,
                presentation.state.name + " uses a colour no contrast test covers",
            )
        }
    }

    /**
     * Every kind of note has its own neutral wording, and none of them borrows a state's word.
     *
     * The second half is the point. A note exists because "unknown" on its own leaves the reader
     * with nothing, so a note that merely repeated that word would be no explanation at all. The
     * sentence that names a service is deliberately not here: it belongs to the integration that
     * reported the item, and this module holds only the half that is true of any source.
     */
    @Test
    fun everyKindOfNoteHasItsOwnNeutralWording() {
        val wordings = MonitorNote.Kind.entries.map { kind -> MonitorNoteText.reasonOf(kind) }
        val stateWords = MonitorPresentation.All.map { presentation -> presentation.word }

        assertEquals(MonitorNote.Kind.entries.size, wordings.distinct().size)
        assertTrue(wordings.all { wording -> wording != 0 })
        assertTrue(wordings.none { wording -> wording in stateWords })
    }

    // --- What the centre key does on a tile ---------------------------------------------------

    /** All three notices are configuration, and the settings screen is where it is changed. */
    @Test
    fun everyNoticeSendsTheTileToTheSettings() {
        for (notice in InstanceNotice.entries) {
            assertEquals(
                TileAction.OPEN_SETTINGS,
                TileAction.of(viewWith(WidgetViewState.Notice(notice), overview = null)),
                notice.name,
            )
        }
    }

    /** A notice wins even when a list from before the problem is still held. */
    @Test
    fun aNoticeSendsTheTileToTheSettingsEvenWithAListStillHeld() {
        val view = viewWith(
            WidgetViewState.Notice(InstanceNotice.DISABLED),
            overview = overviewOf(MonitorState.FAILED),
        )
        assertEquals(TileAction.OPEN_SETTINGS, TileAction.of(view))
    }

    @Test
    fun aTileWithItemsOpensTheDetailScreen() {
        val view = displayView(WidgetDisplayState.Ok, overviewOf(MonitorState.ACTIVE))
        assertEquals(TileAction.OPEN_DETAILS, TileAction.of(view))
        assertTrue(TileAction.of(view).isFocusable)
    }

    /**
     * The list kept from the last success is still a list.
     *
     * This is the case the change is for: unreachable or failing, but the user can still see which
     * items were affected when the data was last obtained.
     */
    @Test
    fun anOutdatedListStillOpensTheDetailScreen() {
        for (state in listOf(
            WidgetDisplayState.Stale,
            WidgetDisplayState.Offline,
            WidgetDisplayState.Error(Reason.MALFORMED_RESPONSE),
        )) {
            assertEquals(
                TileAction.OPEN_DETAILS,
                TileAction.of(displayView(state, overviewOf(MonitorState.FAILED))),
                state.toString(),
            )
        }
    }

    /** Nothing retrieved yet: no notice to act on and no list to show, so no focus either. */
    @Test
    fun aTileWithoutAListAndWithoutANoticeIsNotFocusable() {
        val view = displayView(WidgetDisplayState.Loading, overview = null)
        assertEquals(TileAction.NONE, TileAction.of(view))
        assertFalse(TileAction.of(view).isFocusable)
    }

    /**
     * An installation that genuinely has no monitors is the same dead end as one never retrieved.
     *
     * An empty list is not something to open a screen for; the detail screen would say "no
     * monitors" and the viewer would have to press back to leave it again.
     */
    @Test
    fun aTileWhoseListIsEmptyIsNotFocusableEither() {
        val view = displayView(WidgetDisplayState.Empty, MonitorOverview.Empty)
        assertEquals(TileAction.NONE, TileAction.of(view))
    }

    @Test
    fun onlyNoneIsUnfocusable() {
        assertEquals(
            listOf(TileAction.NONE),
            TileAction.entries.filterNot { it.isFocusable },
        )
    }

    private fun displayView(state: WidgetDisplayState, overview: MonitorOverview?): WidgetView =
        viewWith(WidgetViewState.Display(state), overview)

    private fun viewWith(state: WidgetViewState, overview: MonitorOverview?): WidgetView =
        WidgetView(
            instanceId = TEST_INSTANCE,
            widgetId = KumaSnapshotMapper.summaryWidgetId(TEST_INSTANCE),
            title = "Instance",
            snapshot = null,
            state = state,
            overview = overview,
        )

    private fun overviewOf(vararg states: MonitorState): MonitorOverview = MonitorOverview(
        states.mapIndexed { index, state ->
            MonitorEntry(
                monitorId = index.toString(),
                name = "Item " + index,
                state = state,
                responseTimeMs = null,
            )
        },
    )

    private fun presentationOf(state: WidgetDisplayState): WidgetPresentation =
        WidgetPresentation.of(WidgetViewState.Display(state))

    private fun displayStates(): List<WidgetDisplayState> = listOf(
        WidgetDisplayState.Loading,
        WidgetDisplayState.Ok,
        WidgetDisplayState.Empty,
        WidgetDisplayState.Stale,
        WidgetDisplayState.Offline,
        WidgetDisplayState.Error(Reason.UNAUTHORIZED),
    )

    private companion object {
        val TEST_INSTANCE = InstanceId("kuma")
    }
}
