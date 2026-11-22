package io.github.jellyforgedev.widgora.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.data.MonitorEntry
import io.github.jellyforgedev.widgora.core.data.MonitorOverview
import io.github.jellyforgedev.widgora.core.data.MonitorState
import io.github.jellyforgedev.widgora.core.data.WidgetKind
import io.github.jellyforgedev.widgora.core.data.WidgetSettings
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Which** variant a box actually gets, decided by the platform rather than by the test.
 *
 * ### Why this test exists, and what its absence cost
 *
 * On a television the largest box — 640 × 320 dp, names allowed, one monitor down — drew the full
 * variant with the failed monitor in its title, and no line of names anywhere. The step built for
 * names was never reached.
 *
 * Every other test of this widget builds one variant on purpose and measures it:
 * `WidgetViews.build(context, id, model, layout)` with the layout named in the test's own table.
 * That answers "does this variant fit this box", and it answers it well. What no test asked is the
 * question in between — **given a box, which variant does the platform hand over?** The table
 * pairing a box with a variant was an assumption, written by the same author as the code, and an
 * assumption shared between a test and the thing it tests is checked by neither.
 *
 * So this test names no layout. It hands over the whole size map, exactly as the application does,
 * puts it in a real [AppWidgetHostView] at one of the debug host's boxes, and reads back out of
 * the inflated tree which variant arrived.
 *
 * ### Why a host view, and why no call to `updateAppWidgetSize`
 *
 * The choice is made from the host view's own geometry, not from the options bundle. Read out of
 * the platform source for API 37 `[V]`:
 *
 * - `AppWidgetHostView.setAppWidget` applies the framework's own padding to the host view
 *   (`default_app_widget_padding_*`), which on Android 14 measures 12 / 4 / 12 / 20 dp.
 * - `AppWidgetHostView.onLayout` computes `computeSizeFromLayout`, the laid-out box **minus that
 *   padding**, and asks `RemoteViews.getRemoteViewsToApplyIfDifferent` for the variant that size
 *   deserves.
 * - `RemoteViews.findBestFitLayout` keeps every entry whose key `fitsIn` that size —
 *   `ceil(available) + 1 > key` per axis — and of those picks the smallest square distance. **If
 *   none fits, the entry with the smallest area is used**, which is the compact one.
 *
 * `AppWidgetHostView.updateAppWidgetSize`, which the debug host calls, subtracts the very same
 * padding before writing `OPTION_APPWIDGET_SIZES`. It reports to the provider; it does not pick a
 * layout. Laying the host view out at the box is therefore the whole of what a host does that
 * matters here, and it needs no bound widget identifier.
 *
 * ### The boxes
 *
 * The three the debug host offers: the declared minimum, the height at which the layout changes,
 * and the declared maximum. What somebody looks at on a television and what this measures are the
 * same three boxes.
 */
@RunWith(AndroidJUnit4::class)
class WidgetStepChoiceDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val providerInfo: AppWidgetProviderInfo by lazy {
        AppWidgetManager.getInstance(context)
            .getInstalledProvidersForPackage(context.packageName, null)
            .first { info -> info.provider.className == PROVIDER }
    }

    /**
     * That the three variants can be told apart at all, before anything is concluded from it.
     *
     * [variantIn] reads the structure of the inflated tree, and a reader of structure can be wrong
     * in a way that makes every assertion below pass for the wrong reason. So each variant is
     * built by name — the one place in this file where a layout is named — and has to be
     * recognised as itself.
     */
    @Test
    fun theThreeVariantsAreTellableApart() {
        val recognised = WidgetViews.Layout.entries.associateWith { layout ->
            variantIn(inflated(WidgetViews.build(context, APP_WIDGET_ID, namelessModel(), layout)))
        }

        assertEquals(
            "the inflated trees of the three variants are not distinguishable: $recognised",
            mapOf(
                WidgetViews.Layout.COMPACT to Variant.COMPACT,
                WidgetViews.Layout.FULL to Variant.FULL,
                WidgetViews.Layout.FULL_NAMED to Variant.NAMED,
            ),
            recognised,
        )
    }

    /** Without consent, each box gets the variant it is for. */
    @Test
    fun everyBoxGetsTheVariantItIsFor() {
        for (box in BOXES) {
            val hosted = hosted(box, WidgetViews.build(context, APP_WIDGET_ID, namelessModel()))
            assertEquals(
                "${box.description}: ${explain(box, hosted)}",
                box.nameless,
                variantIn(hosted),
            )
        }
    }

    /**
     * With consent the same — and the tallest box gets the variant that carries names.
     *
     * This is the failure the television showed. The two smaller boxes are asserted in the same
     * loop deliberately: a fix that reaches the tallest box by lowering every threshold would make
     * this half pass and the smaller boxes wrong, and one test should not be satisfiable that way.
     */
    @Test
    fun withConsentTheTallestBoxGetsTheVariantThatCarriesNames() {
        for (box in BOXES) {
            val hosted = hosted(box, WidgetViews.buildNaming(context, APP_WIDGET_ID, namedModel()))
            assertEquals(
                "${box.description}: ${explain(box, hosted)}",
                box.named,
                variantIn(hosted),
            )
        }
    }

    /**
     * The same box, asked the way a viewer asks it: what is on the screen?
     *
     * The variant above is a structural fact; this is its consequence. In the box that carries
     * names the title is the instance's own name — not a monitor's — and the names stand on a line
     * of their own. On the television it was the other way round in both respects, which is what a
     * skipped step looks like to somebody holding a remote.
     */
    @Test
    fun inTheTallestBoxTheNamesAreOnTheirOwnLineAndTheTitleIsTheInstance() {
        val box = BOXES.last()
        val model = namedModel()
        val hosted = hosted(box, WidgetViews.buildNaming(context, APP_WIDGET_ID, model))

        val title = hosted.findViewById<TextView>(R.id.widget_title)
        assertEquals(
            "${box.description}: the title is not the instance name — ${explain(box, hosted)}",
            TITLE,
            title.text.toString(),
        )

        val names = hosted.findViewById<TextView?>(R.id.widget_names)
        assertNotNull(
            "${box.description}: there is no line for names — ${explain(box, hosted)}",
            names,
        )
        assertEquals(
            "${box.description}: the names line is not visible",
            View.VISIBLE,
            names?.visibility,
        )
        assertTrue("the model carries no name, so this proves nothing", model.names.isNotEmpty())
        for (name in model.names) {
            assertTrue(
                "${box.description}: the names line does not carry '$name': ${names?.text}",
                names?.text?.contains(name) == true,
            )
        }
    }

    // --- Reading the chosen variant ------------------------------------------------------------

    /** Which of the three layouts an inflated tree is. */
    private enum class Variant { COMPACT, FULL, NAMED }

    /**
     * The variant, read off the tree rather than asked of the object that built it.
     *
     * Two structural facts separate the three, and both are properties of the layout files rather
     * than of anything set at runtime:
     *
     * - only the naming variant has a line for names at all;
     * - the compact variant puts the state and the time in a row of their own, so its state line
     *   is a grandchild of the root, while the full variant's is a child of it.
     *
     * The scaling ceiling of the counts would have been the shorter route and is not usable: the
     * compact and the naming variant share one, so it separates one of the three and conflates the
     * other two.
     */
    private fun variantIn(hosted: View): Variant {
        if (hosted.findViewById<View?>(R.id.widget_names) != null) return Variant.NAMED

        val root = requireNotNull(hosted.findViewById<View?>(R.id.widget_root)) {
            "there is no widget in this tree at all"
        }
        val state = requireNotNull(hosted.findViewById<View?>(R.id.widget_state)) {
            "the inflated widget has no state line"
        }
        return if (state.parent === root) Variant.FULL else Variant.COMPACT
    }

    /**
     * What the platform had to work with, in the terms it works in.
     *
     * Every assertion carries this, because the one figure that decides the outcome — the box
     * minus the host's padding — is invisible both on a screen and in a stack trace. The previous
     * defect of this kind took two rounds of argument and one measurement to settle. This is the
     * measurement, printed before anybody argues.
     */
    private fun explain(box: Box, hosted: AppWidgetHostView): String {
        val density = context.resources.displayMetrics.density
        val horizontal = (hosted.paddingLeft + hosted.paddingRight) / density
        val vertical = (hosted.paddingTop + hosted.paddingBottom) / density
        return "box ${box.widthDp}×${box.heightDp}dp, host padding " +
            "${hosted.paddingLeft}/${hosted.paddingTop}/${hosted.paddingRight}/" +
            "${hosted.paddingBottom}px (${horizontal}×${vertical}dp), so the widget is offered " +
            "${box.widthDp - horizontal}×${box.heightDp - vertical}dp"
    }

    /**
     * The views in a real host view at one box, laid out twice.
     *
     * Twice because the swap happens during layout: the first pass computes the size and applies
     * the variant that size deserves, and the second is the pass that variant is measured in. A
     * real view tree does the same across two frames.
     */
    private fun hosted(box: Box, views: RemoteViews): AppWidgetHostView {
        val widthPx = px(box.widthDp)
        val heightPx = px(box.heightDp)

        var hosted: AppWidgetHostView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = AppWidgetHostView(context)
            view.setAppWidget(APP_WIDGET_ID, providerInfo)
            view.updateAppWidget(views)
            repeat(2) {
                view.measure(exactly(widthPx), exactly(heightPx))
                view.layout(0, 0, widthPx, heightPx)
            }
            hosted = view
        }
        return requireNotNull(hosted) { "the widget was not laid out" }
    }

    /** One variant on its own, outside a host view, for the recognition test only. */
    private fun inflated(views: RemoteViews): View {
        var root: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            root = views.apply(context, null)
        }
        return requireNotNull(root) { "the views were not inflated" }
    }

    private fun exactly(sizePx: Int): Int =
        View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY)

    private fun px(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

    // --- The models ----------------------------------------------------------------------------

    private fun namelessModel(): WidgetRenderModel.Instance = WidgetRenderModel.Instance(
        title = TITLE,
        state = WidgetViewState.Display(WidgetDisplayState.Ok),
        counts = "▲35 ▼1",
        updatedAtEpochMs = UPDATED_AT,
    )

    /**
     * Consent, and one failed monitor: the smallest situation in which a name may be drawn.
     *
     * It is the situation the television was in. A model with nothing wrong would make every
     * assertion here pass on a widget that has no name to place.
     */
    private fun namedModel(): NamedWidgetRenderModel {
        val consent = requireNotNull(
            NameConsent.from(
                WidgetSettings(
                    kind = WidgetKind.STATUS,
                    instanceId = InstanceId("kuma"),
                    showNames = true,
                ),
            ),
        ) { "settings that say yes produced no consent" }

        return NamedWidgetRenderModel.of(
            consent = consent,
            instance = namelessModel(),
            overview = MonitorOverview(
                listOf(
                    MonitorEntry.truncating(
                        monitorId = "m0",
                        name = MONITOR,
                        state = MonitorState.FAILED,
                        responseTimeMs = null,
                    ),
                ),
            ),
        )
    }

    /** One box a host may offer, and the variant it is meant to get in each of the two cases. */
    private data class Box(
        val description: String,
        val widthDp: Int,
        val heightDp: Int,
        val nameless: Variant,
        val named: Variant,
    )

    private companion object {

        const val PROVIDER = "io.github.jellyforgedev.widgora.widget.CountsWidgetProvider"
        const val APP_WIDGET_ID = 4716
        const val UPDATED_AT = 1_700_000_000_000L

        /** The instance name, which is the title wherever a name has a line of its own. */
        const val TITLE = "Uptime Kuma"

        /** The one failed monitor: long enough to be recognised, short enough not to be cut. */
        const val MONITOR = "test-monitor"

        /**
         * The three boxes of the debug host, smallest first, each with what it is meant to get.
         *
         * Without consent the tallest box gets the full variant, because there is nothing to name;
         * with consent it gets the one that carries names. The two smaller boxes are the same in
         * both columns — consent shows itself in their title line, not in their layout.
         */
        val BOXES = listOf(
            Box(
                description = "the declared minimum",
                widthDp = WidgetSizes.MIN_WIDTH_DP,
                heightDp = WidgetSizes.MIN_HEIGHT_DP,
                nameless = Variant.COMPACT,
                named = Variant.COMPACT,
            ),
            Box(
                description = "the height the layout changes at",
                widthDp = WidgetSizes.MIN_WIDTH_DP,
                heightDp = WidgetSizes.FULL_HEIGHT_DP,
                nameless = Variant.FULL,
                named = Variant.FULL,
            ),
            Box(
                description = "the declared maximum",
                widthDp = WidgetSizes.LARGE_WIDTH_DP,
                heightDp = WidgetSizes.LARGE_HEIGHT_DP,
                nameless = Variant.FULL,
                named = Variant.NAMED,
            ),
        )
    }
}
