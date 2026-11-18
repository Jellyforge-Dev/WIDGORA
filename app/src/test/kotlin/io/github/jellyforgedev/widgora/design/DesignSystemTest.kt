package io.github.jellyforgedev.widgora.design

import androidx.compose.ui.graphics.Color
import io.github.jellyforgedev.widgora.core.data.InstanceNotice
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
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
        val sizes = listOf(
            "ScreenTitle" to WidgoraText.ScreenTitle,
            "TileValue" to WidgoraText.TileValue,
            "TileTitle" to WidgoraText.TileTitle,
            "Label" to WidgoraText.Label,
            "Caption" to WidgoraText.Caption,
        )
        for ((name, size) in sizes) {
            assertTrue(size.value >= WidgoraText.Minimum.value, "$name is ${size.value} sp")
        }
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
}
