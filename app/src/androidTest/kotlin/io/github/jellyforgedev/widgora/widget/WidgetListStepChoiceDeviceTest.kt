package io.github.jellyforgedev.widgora.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.data.CertificateInfo
import io.github.jellyforgedev.widgora.core.data.CertificateValidity
import io.github.jellyforgedev.widgora.core.data.MonitorEntry
import io.github.jellyforgedev.widgora.core.data.MonitorOverview
import io.github.jellyforgedev.widgora.core.data.MonitorState
import io.github.jellyforgedev.widgora.core.data.WidgetKind
import io.github.jellyforgedev.widgora.core.data.WidgetSettings
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Which** list variant a box actually gets, decided by the platform rather than by the test.
 *
 * The same question `WidgetStepChoiceDeviceTest` asks of the counts widget, and it is asked again
 * here because the answer is not transferable: these two kinds declare different placement boxes,
 * their largest step is keyed to a width as well as a height, and the thing that goes wrong is
 * invisible — a widget that fits beautifully and is the wrong one.
 *
 * The mechanism, read out of the platform source for API 37 `[V]`, is written up in
 * [WidgetStepSizes]: the host view takes its own padding, asks for the variant deserved by what is
 * left, and falls back to the entry with the smallest area when no key fits. So this test names no
 * layout. It hands over the whole size map, exactly as the application does, puts it in a real
 * [AppWidgetHostView] at one of the three boxes, and reads out of the inflated tree which variant
 * arrived.
 *
 * It does so for both kinds. They share every layout and every key, so one kind passing says
 * nothing about the other's metadata — and the metadata is what the host view is handed.
 */
@RunWith(AndroidJUnit4::class)
class WidgetListStepChoiceDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * That the three variants can be told apart at all, before anything is concluded from it.
     *
     * [variantIn] reads the structure of the inflated tree, and a reader of structure can be wrong
     * in a way that makes every assertion below pass for the wrong reason. So each variant is built
     * by name — the one place in this file where a layout is named — and has to be recognised as
     * itself.
     */
    @Test
    fun theThreeVariantsAreTellableApart() {
        val recognised = WidgetListViews.Layout.entries.associateWith { layout ->
            variantIn(inflated(WidgetListViews.build(context, APP_WIDGET_ID, model(), layout)))
        }

        assertEquals(
            "the inflated trees of the three list variants are not distinguishable: $recognised",
            mapOf(
                WidgetListViews.Layout.COMPACT to WidgetListViews.Layout.COMPACT,
                WidgetListViews.Layout.FULL to WidgetListViews.Layout.FULL,
                WidgetListViews.Layout.LARGE to WidgetListViews.Layout.LARGE,
            ),
            recognised,
        )
    }

    /** Every box gets the variant it is for, for both kinds that draw a list. */
    @Test
    fun everyBoxGetsTheVariantItIsFor() {
        for (kind in LIST_KINDS) {
            for (box in BOXES) {
                val hosted = hosted(
                    kind,
                    box,
                    WidgetListViews.build(context, APP_WIDGET_ID, model()),
                )
                assertEquals(
                    "${kind.name} at ${box.description}: ${explain(box, hosted)}",
                    box.expected,
                    variantIn(hosted),
                )
            }
        }
    }

    /**
     * The same boxes, asked the way a viewer asks them: how many items are on the screen?
     *
     * The variant is a structural fact; this is its consequence, and it is the one a user would
     * notice. A model with more items than any box can hold is used, so each box shows exactly its
     * own budget and says how many it left out.
     */
    @Test
    fun eachBoxShowsItsOwnNumberOfItemsAndSaysWhatIsLeft() {
        val model = model()
        assertTrue(
            "the model carries fewer items than the largest budget",
            model.rows.size == WidgetListRenderModel.MAX_ROWS,
        )

        for (box in BOXES) {
            val hosted = hosted(
                WidgetKind.CERTIFICATES,
                box,
                WidgetListViews.build(context, APP_WIDGET_ID, model),
            )
            val shown = (0 until WidgetListRenderModel.MAX_ROWS).count { index ->
                val line = hosted.findViewById<View?>(rowIdOf(index))
                line != null && line.visibility == View.VISIBLE
            }

            assertEquals(
                "${box.description}: the wrong number of items — ${explain(box, hosted)}",
                box.expected.rowBudget,
                shown,
            )

            val more = hosted.findViewById<View?>(R.id.widget_title_more)
            assertEquals(
                "${box.description}: the count of what was left out is not shown",
                View.VISIBLE,
                more?.visibility,
            )
        }
    }

    // --- Reading the chosen variant ------------------------------------------------------------

    /**
     * Which of the three layouts an inflated tree is, read off the tree.
     *
     * The three differ in how many item lines they declare, which is a property of the layout files
     * rather than of anything set at runtime — a line a layout does not have cannot be switched on.
     */
    private fun variantIn(hosted: View): WidgetListViews.Layout {
        requireNotNull(hosted.findViewById<View?>(R.id.widget_root)) {
            "there is no widget in this tree at all"
        }
        val declared = (0 until WidgetListRenderModel.MAX_ROWS).count { index ->
            hosted.findViewById<View?>(rowIdOf(index)) != null
        }
        return WidgetListViews.Layout.entries.firstOrNull { layout ->
            layout.rowBudget == declared
        } ?: error("a list layout with $declared item lines is none of the three")
    }

    private fun rowIdOf(index: Int): Int = ROW_IDS[index]

    /**
     * What the platform had to work with, in the terms it works in.
     *
     * Every assertion carries this, because the one figure that decides the outcome — the box minus
     * the host's padding — is invisible both on a screen and in a stack trace.
     */
    private fun explain(box: Box, hosted: AppWidgetHostView): String {
        val density = context.resources.displayMetrics.density
        val horizontal = (hosted.paddingLeft + hosted.paddingRight) / density
        val vertical = (hosted.paddingTop + hosted.paddingBottom) / density
        return "box ${box.widthDp}×${box.heightDp}dp, host padding " +
            "${horizontal}×${vertical}dp, so the widget is offered " +
            "${box.widthDp - horizontal}×${box.heightDp - vertical}dp"
    }

    /**
     * The views in a real host view at one box, laid out twice.
     *
     * Twice because the swap happens during layout: the first pass computes the size and applies
     * the variant that size deserves, and the second is the pass that variant is measured in.
     *
     * The provider info is the one of the kind under test, because that is what decides the host
     * view's padding and it is the per-kind part of this question.
     */
    private fun hosted(kind: WidgetKind, box: Box, views: RemoteViews): AppWidgetHostView {
        val widthPx = px(box.widthDp)
        val heightPx = px(box.heightDp)
        val info = providerInfoOf(kind)

        var hosted: AppWidgetHostView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = AppWidgetHostView(context)
            view.setAppWidget(APP_WIDGET_ID, info)
            view.updateAppWidget(views)
            repeat(2) {
                view.measure(exactly(widthPx), exactly(heightPx))
                view.layout(0, 0, widthPx, heightPx)
            }
            hosted = view
        }
        return requireNotNull(hosted) { "the widget was not laid out" }
    }

    private fun providerInfoOf(kind: WidgetKind): AppWidgetProviderInfo {
        val className = requireNotNull(WidgetProviders.classNameOf(kind)) {
            "${kind.name} has no provider"
        }
        return AppWidgetManager.getInstance(context)
            .getInstalledProvidersForPackage(context.packageName, null)
            .first { info -> info.provider.className == className }
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

    // --- The model ----------------------------------------------------------------------------

    /**
     * More certificates than any box can list, so every box has something left over.
     *
     * Built through the factory with a consent token, which is the only way one of these models can
     * be obtained — in a test as in the application.
     */
    private fun model(): WidgetListRenderModel {
        val consent = requireNotNull(
            NameConsent.from(
                WidgetSettings(
                    kind = WidgetKind.CERTIFICATES,
                    instanceId = InstanceId("kuma"),
                    showNames = true,
                ),
            ),
        ) { "settings that say yes produced no consent" }

        val entries = (1..WidgetListRenderModel.MAX_ROWS + 3).map { index ->
            MonitorEntry.truncating(
                monitorId = "m$index",
                name = "test-monitor-$index",
                state = MonitorState.ACTIVE,
                responseTimeMs = null,
                certificate = CertificateInfo(
                    validity = CertificateValidity.VALID_ACCORDING_TO_SOURCE,
                    daysRemaining = index,
                ),
            )
        }

        return WidgetListRenderModel.certificates(
            consent = consent,
            instance = WidgetRenderModel.Instance(
                title = TITLE,
                state = WidgetViewState.Display(WidgetDisplayState.Ok),
                counts = "▲35",
                updatedAtEpochMs = UPDATED_AT,
            ),
            overview = MonitorOverview(entries),
        )
    }

    /** One box a host may offer, and the variant it is meant to get. */
    private data class Box(
        val description: String,
        val widthDp: Int,
        val heightDp: Int,
        val expected: WidgetListViews.Layout,
    )

    private companion object {

        const val APP_WIDGET_ID = 4718
        const val UPDATED_AT = 1_700_000_000_000L
        const val TITLE = "Uptime Kuma"

        val LIST_KINDS = listOf(WidgetKind.CERTIFICATES, WidgetKind.SELECTION)

        val ROW_IDS = listOf(
            R.id.widget_row_0,
            R.id.widget_row_1,
            R.id.widget_row_2,
            R.id.widget_row_3,
            R.id.widget_row_4,
            R.id.widget_row_5,
            R.id.widget_row_6,
            R.id.widget_row_7,
        )

        /**
         * The three boxes a list widget is declared for, smallest first.
         *
         * The resize floor, the box the certificates widget is placed at, and the declared
         * maximum — which is also the box the selection widget is placed at.
         */
        val BOXES = listOf(
            Box(
                description = "the resize floor",
                widthDp = WidgetSizes.MIN_WIDTH_DP,
                heightDp = WidgetSizes.MIN_HEIGHT_DP,
                expected = WidgetListViews.Layout.COMPACT,
            ),
            Box(
                description = "the box four items fit in",
                widthDp = WidgetSizes.MIN_WIDTH_DP,
                heightDp = WidgetSizes.FULL_HEIGHT_DP,
                expected = WidgetListViews.Layout.FULL,
            ),
            Box(
                description = "the declared maximum",
                widthDp = WidgetSizes.LARGE_WIDTH_DP,
                heightDp = WidgetSizes.LARGE_HEIGHT_DP,
                expected = WidgetListViews.Layout.LARGE,
            ),
        )
    }
}
