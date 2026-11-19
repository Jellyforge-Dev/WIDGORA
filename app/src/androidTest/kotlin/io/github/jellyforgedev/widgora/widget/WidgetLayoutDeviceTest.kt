package io.github.jellyforgedev.widgora.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether the widget fits the box it says it fits — inside the frame a host actually puts it in.
 *
 * ### Why the first version of this test was worthless
 *
 * It inflated the views on their own and measured those. It passed, and the widget was still cut
 * off on a television at the same density and the same font scale. The reason is the thing it
 * left out: on a device the widget lives inside an [AppWidgetHostView], and that view applies a
 * padding of its own around whatever it hosts. The widget never gets the box the host was asked
 * for — it gets that box minus a margin the test knew nothing about.
 *
 * So this builds a real [AppWidgetHostView], hands it the real provider metadata, which is what
 * decides that padding, and measures what comes out. Every failure message names the padding, so
 * a surprise of the same kind reports itself instead of having to be guessed at.
 *
 * ### Why it measures three font scales
 *
 * A user can enlarge the system font, and there is nothing exotic about that. A layout that fits
 * only at the default scale is a layout that fits only on the developer's device. At the default
 * scale the widget must also keep [MIN_FREE_HEIGHT_FRACTION] of its height spare: fitting to the
 * pixel is how the previous layout came to fit on one device and not on another.
 */
@RunWith(AndroidJUnit4::class)
class WidgetLayoutDeviceTest {

    private val baseContext: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val providerInfo: AppWidgetProviderInfo by lazy {
        AppWidgetManager.getInstance(baseContext)
            .getInstalledProvidersForPackage(baseContext.packageName, null)
            .first { it.provider.className == PROVIDER }
    }

    /**
     * The cases that stress the lines rather than the pleasant ones.
     *
     * The error carries the longest wording the application has, which is the one most likely to
     * push a shared line past its end.
     */
    private val models: List<Pair<String, WidgetRenderModel>> = listOf(
        "current with counts" to instance(
            counts = "▲35 ▼2 ●1",
            state = WidgetViewState.Display(WidgetDisplayState.Ok),
        ),
        "every state counted" to instance(
            counts = "▲12 ▼3 ◆2 ■1 ●4",
            state = WidgetViewState.Display(WidgetDisplayState.Stale),
        ),
        "longest error wording" to instance(
            counts = "▲35 ▼2",
            state = WidgetViewState.Display(WidgetDisplayState.Error(Reason.ENDPOINT_NOT_FOUND)),
        ),
        "nothing retrieved yet" to WidgetRenderModel.Instance(
            title = "Uptime Kuma",
            state = WidgetViewState.Display(WidgetDisplayState.Loading),
            counts = "",
            updatedAtEpochMs = null,
        ),
        "not set up" to WidgetRenderModel.NotSetUp,
        "settings unreadable" to WidgetRenderModel.ConfigurationUnreadable,
    )

    @Test
    fun theCompactVariantFitsItsSmallestSizeAtEveryFontScale() {
        for (scale in FONT_SCALES) {
            for ((name, model) in models) {
                assertFits(
                    description = "compact at font scale $scale, $name",
                    layout = WidgetViews.Layout.COMPACT,
                    model = model,
                    heightDp = WidgetSizes.MIN_HEIGHT_DP,
                    fontScale = scale,
                )
            }
        }
    }

    @Test
    fun theFullVariantFitsItsSmallestSizeAtEveryFontScale() {
        for (scale in FONT_SCALES) {
            for ((name, model) in models) {
                assertFits(
                    description = "full at font scale $scale, $name",
                    layout = WidgetViews.Layout.FULL,
                    model = model,
                    heightDp = WidgetSizes.FULL_HEIGHT_DP,
                    fontScale = scale,
                )
            }
        }
    }

    /**
     * At the default font scale the widget keeps room to spare.
     *
     * Fitting exactly is not fitting. The previous layout fitted exactly on one device and was
     * cut off on another, and the difference was a padding nobody had counted.
     */
    @Test
    fun theCompactVariantKeepsRoomToSpareAtTheDefaultFontScale() {
        for ((name, model) in models) {
            assertKeepsSpareHeight(
                description = "compact, $name",
                layout = WidgetViews.Layout.COMPACT,
                model = model,
                heightDp = WidgetSizes.MIN_HEIGHT_DP,
            )
        }
    }

    @Test
    fun theFullVariantKeepsRoomToSpareAtTheDefaultFontScale() {
        for ((name, model) in models) {
            assertKeepsSpareHeight(
                description = "full, $name",
                layout = WidgetViews.Layout.FULL,
                model = model,
                heightDp = WidgetSizes.FULL_HEIGHT_DP,
            )
        }
    }

    /**
     * The time is never shortened, at any font scale.
     *
     * On the compact variant it shares a line with the state, and the state is the side that
     * gives way: a shortened time would be worthless, while a shortened state still carries its
     * mark.
     */
    @Test
    fun theTimeIsNeverCutOnTheCompactVariant() {
        for (scale in FONT_SCALES) {
            for ((name, model) in models) {
                if (model !is WidgetRenderModel.Instance) continue
                val hosted =
                    hosted(WidgetViews.Layout.COMPACT, model, WidgetSizes.MIN_HEIGHT_DP, scale)
                val footer = hosted.findViewById<TextView>(R.id.widget_footer)
                if (footer.visibility != View.VISIBLE) continue

                val layout = requireNotNull(footer.layout) { "the time was not laid out: $name" }
                assertEquals(
                    "the time was shortened at font scale $scale ($name)",
                    0,
                    layout.getEllipsisCount(0),
                )
            }
        }
    }

    /** The state keeps its mark even where the wording has to give way. */
    @Test
    fun theStateKeepsItsMarkOnTheCompactVariant() {
        val model = instance(
            counts = "▲35 ▼2",
            state = WidgetViewState.Display(WidgetDisplayState.Error(Reason.ENDPOINT_NOT_FOUND)),
        )
        val hosted = hosted(
            WidgetViews.Layout.COMPACT,
            model,
            WidgetSizes.MIN_HEIGHT_DP,
            DEFAULT_FONT_SCALE,
        )
        val state = hosted.findViewById<TextView>(R.id.widget_state)

        assertTrue("the state line lost its mark", state.text.startsWith("!!"))
        assertTrue("the state line has no width", state.width > 0)
    }

    // --- The measurement -----------------------------------------------------------------------

    private fun assertFits(
        description: String,
        layout: WidgetViews.Layout,
        model: WidgetRenderModel,
        heightDp: Int,
        fontScale: Float,
    ) {
        val hosted = hosted(layout, model, heightDp, fontScale)
        val root = widgetRoot(hosted)
        val lines = visibleTextViews(root)
        assertTrue("$description: nothing visible at all", lines.isNotEmpty())

        for (line in lines) {
            val bottom = bottomWithin(root, line)
            assertTrue(
                "$description: '${name(line)}' ends at ${bottom}px of the widget's " +
                    "${root.height}px${hostPadding(hosted)}",
                bottom <= root.height,
            )

            // A vertical layout squeezes a child that does not fit rather than refusing to lay
            // it out, so a line can be inside the bounds and still have its glyphs clipped.
            val textHeight = line.layout?.height ?: 0
            assertTrue(
                "$description: '${name(line)}' was squeezed to ${line.height}px for " +
                    "${textHeight}px of text${hostPadding(hosted)}",
                line.height >= textHeight,
            )
        }
    }

    private fun assertKeepsSpareHeight(
        description: String,
        layout: WidgetViews.Layout,
        model: WidgetRenderModel,
        heightDp: Int,
    ) {
        val hosted = hosted(layout, model, heightDp, DEFAULT_FONT_SCALE)
        val root = widgetRoot(hosted)
        val used = visibleTextViews(root).maxOfOrNull { bottomWithin(root, it) } ?: 0
        val free = (root.height - used).toFloat() / root.height

        assertTrue(
            "$description: only ${(free * 100).toInt()}% of the height is spare, " +
                "${(MIN_FREE_HEIGHT_FRACTION * 100).toInt()}% is the floor " +
                "(${used}px used of ${root.height}px${hostPadding(hosted)})",
            free >= MIN_FREE_HEIGHT_FRACTION,
        )
    }

    /**
     * The widget inside a real host view, measured at the given box and font scale.
     *
     * The host view is what makes this test worth running: it applies the padding the platform
     * gives a widget of this provider, which is the margin the first version of this test spent
     * its whole life ignoring.
     */
    private fun hosted(
        layout: WidgetViews.Layout,
        model: WidgetRenderModel,
        heightDp: Int,
        fontScale: Float,
    ): AppWidgetHostView {
        val context = scaled(fontScale)
        val widthPx = px(context, WidgetSizes.MIN_WIDTH_DP)
        val heightPx = px(context, heightDp)

        var hosted: AppWidgetHostView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = AppWidgetHostView(context)
            view.setAppWidget(APP_WIDGET_ID, providerInfo)
            view.updateAppWidget(WidgetViews.build(context, APP_WIDGET_ID, model, layout))
            view.measure(exactly(widthPx), exactly(heightPx))
            view.layout(0, 0, widthPx, heightPx)
            hosted = view
        }
        return requireNotNull(hosted) { "the widget was not laid out" }
    }

    private fun widgetRoot(hosted: AppWidgetHostView): View =
        hosted.findViewById(R.id.widget_root) ?: hosted

    private fun hostPadding(hosted: AppWidgetHostView): String =
        ", host padding ${hosted.paddingLeft}/${hosted.paddingTop}/" +
            "${hosted.paddingRight}/${hosted.paddingBottom}px"

    private fun scaled(fontScale: Float): Context {
        if (fontScale == DEFAULT_FONT_SCALE) return baseContext
        val configuration = Configuration(baseContext.resources.configuration)
        configuration.fontScale = fontScale
        return baseContext.createConfigurationContext(configuration)
    }

    private fun exactly(sizePx: Int): Int =
        View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY)

    private fun px(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    /** The bottom edge of [view] in [root]'s coordinates, however deeply it is nested. */
    private fun bottomWithin(root: View, view: View): Int {
        var bottom = view.bottom
        var parent = view.parent
        while (parent is View && parent !== root) {
            bottom += parent.top
            parent = parent.parent
        }
        return bottom
    }

    private fun visibleTextViews(view: View): List<TextView> = when {
        view.visibility != View.VISIBLE -> emptyList()
        view is TextView -> listOf(view)
        view is ViewGroup -> (0 until view.childCount).flatMap { index ->
            visibleTextViews(view.getChildAt(index))
        }

        else -> emptyList()
    }

    /** The identifier of a line rather than its text: a failure report carries no user data. */
    private fun name(view: View): String = runCatching {
        baseContext.resources.getResourceEntryName(view.id)
    }.getOrNull() ?: "unnamed line"

    private fun instance(counts: String, state: WidgetViewState) = WidgetRenderModel.Instance(
        title = "Uptime Kuma",
        state = state,
        counts = counts,
        updatedAtEpochMs = UPDATED_AT,
    )

    private companion object {

        const val PROVIDER = "io.github.jellyforgedev.widgora.widget.CountsWidgetProvider"
        const val APP_WIDGET_ID = 4714
        const val UPDATED_AT = 1_700_000_000_000L

        const val DEFAULT_FONT_SCALE = 1.0f

        /** The default, plus the two steps a user is most likely to pick. */
        val FONT_SCALES = listOf(DEFAULT_FONT_SCALE, 1.15f, 1.3f)

        /** How much of the height has to be left over at the default font scale. */
        const val MIN_FREE_HEIGHT_FRACTION = 0.15f
    }
}
