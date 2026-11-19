package io.github.jellyforgedev.widgora.widget

import android.os.Parcel
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.RemoteViews
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.design.MonitorMarks
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether the coloured marks actually arrive in the host's process.
 *
 * This cannot be a unit test, and it is not merely inconvenient to write as one — the question
 * *is* the transport. A `RemoteViews` object is handed to a launcher by being parcelled, and only
 * spans the platform knows how to write survive that. `ForegroundColorSpan` is documented as a
 * `ParcelableSpan`, and this test is the difference between relying on that and knowing it: the
 * views are written to a parcel, read back out of it, inflated, and the spans are read off the
 * text that comes out the other side.
 *
 * Inflation happens on the main thread, because that is where a host inflates.
 */
@RunWith(AndroidJUnit4::class)
class WidgetViewsDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun eachMarkKeepsItsStateColourAcrossTheParcel() {
        val spanned = inflatedCounts(model(COUNTS)).text

        assertTrue("the counts line carries no spans at all", spanned is Spanned)
        spanned as Spanned
        assertEquals(COUNTS, spanned.toString())

        val expected = MonitorMarks.inCounts(COUNTS)
        assertEquals(3, expected.size)
        for (mark in expected) {
            val spans = spanned.getSpans(
                mark.startIndex,
                mark.endIndex,
                ForegroundColorSpan::class.java,
            )
            assertEquals("the mark for ${mark.state.name} lost its span", 1, spans.size)
            assertEquals(
                "the mark for ${mark.state.name} arrived in the wrong colour",
                mark.color.toArgb(),
                spans[0].foregroundColor,
            )
        }
    }

    /**
     * The numbers carry no span, which is what keeps them in the view's own text colour.
     *
     * A number tinted with a state colour would be the figure a reader is actually after, drawn
     * at a contrast the surface was never checked for.
     */
    @Test
    fun theNumbersAreLeftInTheViewsOwnColour() {
        val spanned = inflatedCounts(model(COUNTS)).text as Spanned

        val markIndices = MonitorMarks.inCounts(COUNTS).map { it.startIndex }.toSet()
        for (index in COUNTS.indices) {
            if (index in markIndices) continue
            val spans = spanned.getSpans(index, index + 1, ForegroundColorSpan::class.java)
            assertEquals("'${COUNTS[index]}' at $index was coloured", 0, spans.size)
        }
    }

    /** All five at once, so no state is coloured only because it came first in the line. */
    @Test
    fun allFiveMarksSurviveTogether() {
        val spanned = inflatedCounts(model(ALL_FIVE)).text as Spanned

        for (mark in MonitorMarks.inCounts(ALL_FIVE)) {
            val spans = spanned.getSpans(
                mark.startIndex,
                mark.endIndex,
                ForegroundColorSpan::class.java,
            )
            assertEquals("the mark for ${mark.state.name} lost its span", 1, spans.size)
            assertEquals(mark.color.toArgb(), spans[0].foregroundColor)
        }
    }

    private fun model(counts: String) = WidgetRenderModel.Instance(
        title = TITLE,
        state = WidgetViewState.Display(WidgetDisplayState.Ok),
        counts = counts,
        updatedAtEpochMs = UPDATED_AT,
    )

    /**
     * Builds the views, sends them through a parcel, and inflates what comes back.
     *
     * The parcel is the whole point. Inflating the original object would prove only that the span
     * was applied, which is the easy half.
     */
    private fun inflatedCounts(model: WidgetRenderModel): TextView {
        val restored = parcelAndBack(WidgetViews.build(context, APP_WIDGET_ID, model))
        var root: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            root = restored.apply(context, null)
        }
        val inflated = requireNotNull(root) { "the views were not inflated" }
        return inflated.findViewById(R.id.widget_counts)
    }

    private fun parcelAndBack(views: RemoteViews): RemoteViews {
        val parcel = Parcel.obtain()
        return try {
            views.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            RemoteViews.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private companion object {

        const val APP_WIDGET_ID = 4713
        const val TITLE = "Uptime Kuma"
        const val UPDATED_AT = 1_700_000_000_000L
        const val COUNTS = "▲35 ▼2 ●1"
        const val ALL_FIVE = "▲1 ▼1 ◆1 ■1 ●1"
    }
}
