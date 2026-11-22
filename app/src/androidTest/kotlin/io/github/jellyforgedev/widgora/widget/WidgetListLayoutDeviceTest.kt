package io.github.jellyforgedev.widgora.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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
import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether a list widget fits the box it says it fits — inside the frame a host actually puts it in.
 *
 * ### What this is for
 *
 * The line budgets of the three list layouts are arithmetic in a comment until something measures
 * them. Two items in 300 × 170 dp, four in 300 × 250 and eight in two columns of 640 × 320 are
 * claims about text at the project's floor of 18 sp, at font scales a user can choose, inside a
 * host view that keeps 24 dp of the box for itself. Every one of those numbers has been wrong in
 * this project before, and the failure it produces is a line cut in half on a television.
 *
 * So the measurement is the test, and the rule when it fails is stated in advance: carry fewer
 * items, never smaller text.
 *
 * ### What is measured
 *
 * That nothing ends below the widget's own bottom edge, and that no line is squeezed — a vertical
 * layout squeezes a child that does not fit rather than refusing to lay it out, so a line can be
 * inside the bounds and still have its glyphs clipped. On top of that, three things that may never
 * be shortened: the figure beside a name, the time, and the count of what was left out. The name
 * may be, and is: that is the one part a reader can recover by opening the application.
 */
@RunWith(AndroidJUnit4::class)
class WidgetListLayoutDeviceTest {

    private val baseContext: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val providerInfo: AppWidgetProviderInfo by lazy {
        val className = requireNotNull(WidgetProviders.classNameOf(WidgetKind.CERTIFICATES))
        AppWidgetManager.getInstance(baseContext)
            .getInstalledProvidersForPackage(baseContext.packageName, null)
            .first { info -> info.provider.className == className }
    }

    @Test
    fun everyStepFitsAtEveryFontScale() {
        for (step in STEPS) {
            for (scale in FONT_SCALES) {
                for ((name, model) in models()) {
                    val hosted = hosted(step, model, scale)
                    assertFitsIn("${step.description} at font scale $scale, $name", hosted)
                }
            }
        }
    }

    /**
     * Each step keeps room to spare at the default font scale.
     *
     * Fitting exactly is not fitting: the layout that fitted on an emulator was cut off on a
     * television, and the difference was a padding nobody had counted. There is no ceiling in the
     * other direction here, unlike the counts widget — a list of two items in a box built for eight
     * is genuinely sparse, and the honest answer to that is a shorter list rather than larger text.
     */
    @Test
    fun everyStepKeepsRoomToSpare() {
        for (step in STEPS) {
            for ((name, model) in models()) {
                val hosted = hosted(step, model, DEFAULT_FONT_SCALE)
                val root = widgetRoot(hosted)
                val ink = inkHeight(root)
                val free = (root.height - ink).toFloat() / root.height

                assertTrue(
                    "${step.description}, $name: only ${(free * 100).toInt()}% of the height is " +
                        "spare, ${(MIN_FREE_HEIGHT_FRACTION * 100).toInt()}% is the floor " +
                        "(${ink}px of text in ${root.height}px); by line: ${inkByLine(root)}",
                    free >= MIN_FREE_HEIGHT_FRACTION,
                )
            }
        }
    }

    /**
     * The figure beside a name is never shortened, at any font scale.
     *
     * It is the reason the row has two views. Half a number of days is nothing, while a shortened
     * name is still a name — so the name gives way and the figure does not.
     */
    @Test
    fun theFigureBesideANameIsNeverShortened() {
        for (step in STEPS) {
            for (scale in FONT_SCALES) {
                for ((name, model) in models()) {
                    val hosted = hosted(step, model, scale)
                    for (index in 0 until step.layout.rowBudget) {
                        val detail = hosted.findViewById<TextView?>(DETAIL_IDS[index]) ?: continue
                        if (detail.visibility != View.VISIBLE) continue

                        assertEquals(
                            "${step.description} at font scale $scale, $name: the figure on " +
                                "line $index was shortened ('${detail.text}')",
                            0,
                            detail.layout?.getEllipsisCount(0) ?: 0,
                        )
                    }
                }
            }
        }
    }

    /** The time and the count of what was left out are never shortened either. */
    @Test
    fun theTimeAndTheOverflowCountAreNeverShortened() {
        for (step in STEPS) {
            for (scale in FONT_SCALES) {
                val hosted = hosted(step, overflowing(), scale)
                val where = "${step.description} at font scale $scale"

                val footer = hosted.findViewById<TextView>(R.id.widget_footer)
                assertEquals(
                    "$where: the time was shortened",
                    0,
                    footer.layout?.getEllipsisCount(0) ?: 0,
                )

                val more = hosted.findViewById<TextView>(R.id.widget_title_more)
                assertEquals("$where: the overflow count is not shown", View.VISIBLE, more.visibility)
                assertEquals(
                    "$where: the overflow count was shortened",
                    0,
                    more.layout?.getEllipsisCount(0) ?: 0,
                )
            }
        }
    }

    /**
     * The empty statement is readable in full, in every box and at every font scale.
     *
     * For the certificates widget this is the ordinary, good case rather than an error: nothing
     * runs out within the horizon. A sentence that says so and is then cut off halfway would be
     * worse than no sentence at all.
     */
    @Test
    fun theEmptyStatementIsNeverCutOff() {
        for (step in STEPS) {
            for (scale in FONT_SCALES) {
                for ((name, model) in emptyModels()) {
                    val hosted = hosted(step, model, scale)
                    val where = "${step.description} at font scale $scale, $name"
                    assertFitsIn(where, hosted)

                    val empty = hosted.findViewById<TextView>(R.id.widget_empty)
                    assertEquals("$where: the statement is not shown", View.VISIBLE, empty.visibility)

                    val layout = requireNotNull(empty.layout) { "$where: it was not laid out" }
                    for (line in 0 until layout.lineCount) {
                        assertEquals(
                            "$where: the statement was cut off on line $line ('${empty.text}')",
                            0,
                            layout.getEllipsisCount(line),
                        )
                    }

                    // And with nothing to list, the title line gives its room to the statement.
                    val title = hosted.findViewById<TextView>(R.id.widget_title)
                    assertEquals("$where: the title is still shown", View.GONE, title.visibility)
                }
            }
        }
    }

    /** The items are actually drawn, which a measurement test is least able to notice. */
    @Test
    fun theItemsAndTheirFiguresAreActuallyDrawn() {
        for (step in STEPS) {
            val model = overflowing()
            val hosted = hosted(step, model, DEFAULT_FONT_SCALE)

            for (index in 0 until step.layout.rowBudget) {
                val line = hosted.findViewById<TextView>(ROW_IDS[index])
                assertEquals(
                    "${step.description}: line $index is not visible",
                    View.VISIBLE,
                    line.visibility,
                )
                assertTrue(
                    "${step.description}: line $index carries no text",
                    line.text.isNotEmpty(),
                )
            }
            // And not one line more than the box is built for. A layout does not declare the
            // lines beyond its budget at all, so the view is usually absent rather than hidden;
            // either answer is correct and a visible one is not.
            for (index in step.layout.rowBudget until ROW_IDS.size) {
                val beyond = hosted.findViewById<TextView?>(ROW_IDS[index])
                assertTrue(
                    "${step.description}: line $index is drawn beyond the budget of " +
                        "${step.layout.rowBudget}",
                    beyond == null || beyond.visibility != View.VISIBLE,
                )
            }
        }
    }

    // --- The measurement ----------------------------------------------------------------------

    private fun assertFitsIn(description: String, hosted: AppWidgetHostView) {
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

            val textHeight = line.layout?.height ?: 0
            assertTrue(
                "$description: '${name(line)}' was squeezed to ${line.height}px for " +
                    "${textHeight}px of text${hostPadding(hosted)}",
                line.height >= textHeight,
            )
        }
    }

    /**
     * The height the glyphs occupy, row by row.
     *
     * A row of the vertical stack contributes its own text height; a row that holds several views
     * side by side contributes the tallest of them, because they share one line. The two columns of
     * the largest layout are such a row, which is exactly why eight items fit in four lines.
     */
    private fun inkHeight(root: View): Int = rowInk(root)

    /**
     * One subtree's share of the height.
     *
     * Three cases, and the middle one is what makes two columns of four items four lines tall
     * rather than eight: views stacked vertically add up, views side by side contribute the
     * tallest of them, and a piece of text contributes its glyphs.
     */
    private fun rowInk(view: View): Int {
        if (view.visibility != View.VISIBLE) return 0
        if (view is TextView) return view.layout?.height ?: 0
        if (view !is ViewGroup) return 0

        val children = (0 until view.childCount).map { index -> view.getChildAt(index) }
        val vertical = (view as? LinearLayout)?.orientation == LinearLayout.VERTICAL
        return if (vertical) {
            children.sumOf { child -> rowInk(child) }
        } else {
            children.maxOfOrNull { child -> rowInk(child) } ?: 0
        }
    }

    /** Which line contributes how much, for the failure message. Identifiers only, no text. */
    private fun inkByLine(root: View): String {
        if (root !is ViewGroup) return "not a group"
        return (0 until root.childCount)
            .map { index -> root.getChildAt(index) }
            .filter { row -> row.visibility == View.VISIBLE }
            .joinToString(", ") { row ->
                val size = (row as? TextView)?.textSize?.toInt() ?: 0
                "${name(row)}=${rowInk(row)}px@${size}px"
            }
    }

    private fun hosted(
        step: Step,
        model: WidgetListRenderModel,
        fontScale: Float,
    ): AppWidgetHostView {
        val context = scaled(fontScale)
        val views = WidgetListViews.build(context, APP_WIDGET_ID, model, step.layout)
        val widthPx = px(context, step.widthDp)
        val heightPx = px(context, step.heightDp)

        var hosted: AppWidgetHostView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = AppWidgetHostView(context)
            view.setAppWidget(APP_WIDGET_ID, providerInfo)
            view.updateAppWidget(views)
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

    // --- The models ---------------------------------------------------------------------------

    /**
     * The cases that stress the lines rather than the pleasant ones.
     *
     * Names as long as the model permits, figures of every shape the source can produce — a
     * rejected certificate, a single digit, one that has run out — and the longest state wording
     * the application has beside the time.
     */
    private fun models(): List<Pair<String, WidgetListRenderModel>> = listOf(
        "a full list and more" to overflowing(),
        "one certificate" to certificates(1, state = okState()),
        "a full list while the value is outdated" to certificates(
            WidgetListRenderModel.MAX_ROWS,
            state = WidgetViewState.Display(WidgetDisplayState.Stale),
        ),
        "a full list with the longest error wording" to certificates(
            WidgetListRenderModel.MAX_ROWS,
            state = WidgetViewState.Display(WidgetDisplayState.Error(Reason.ENDPOINT_NOT_FOUND)),
        ),
        "a full selection" to selection(WidgetListRenderModel.MAX_ROWS + 2),
    )

    /** Both kinds with nothing to list, which is the case their statements are written for. */
    private fun emptyModels(): List<Pair<String, WidgetListRenderModel>> = listOf(
        "no certificate runs out" to certificates(0, state = okState()),
        "nothing picked is in the data" to selection(0),
    )

    private fun overflowing(): WidgetListRenderModel =
        certificates(WidgetListRenderModel.MAX_ROWS + 4, state = okState())

    /**
     * A certificates model with [count] items in it, each name as long as a row keeps.
     *
     * The first item is one the source will not vouch for, the next few run out within a week, and
     * one of them has run out — the three wordings a figure can take, in one model.
     */
    private fun certificates(count: Int, state: WidgetViewState): WidgetListRenderModel =
        WidgetListRenderModel.certificates(
            consent = consent(),
            instance = instance(state),
            overview = MonitorOverview(
                List(count) { index ->
                    MonitorEntry.truncating(
                        monitorId = "m$index",
                        name = longName(index),
                        state = if (index == 1) MonitorState.MAINTENANCE else MonitorState.ACTIVE,
                        responseTimeMs = null,
                        certificate = when (index) {
                            0 -> CertificateInfo(
                                validity = CertificateValidity.NOT_VALID_ACCORDING_TO_SOURCE,
                                daysRemaining = null,
                            )

                            1 -> CertificateInfo(
                                validity = CertificateValidity.VALID_ACCORDING_TO_SOURCE,
                                daysRemaining = -3,
                            )

                            else -> CertificateInfo(
                                validity = CertificateValidity.VALID_ACCORDING_TO_SOURCE,
                                daysRemaining = index,
                            )
                        },
                    )
                },
            ),
        )

    /** A selection model with [count] picked items, each in a different state. */
    private fun selection(count: Int): WidgetListRenderModel {
        val entries = List(count) { index ->
            MonitorEntry.truncating(
                monitorId = "m$index",
                name = longName(index),
                state = MonitorState.entries[index % MonitorState.entries.size],
                responseTimeMs = null,
            )
        }
        return WidgetListRenderModel.selection(
            consent = consent(),
            instance = instance(okState()),
            overview = MonitorOverview(entries),
            monitorIds = entries.map { entry -> entry.monitorId },
        )
    }

    /**
     * A name as long as a row keeps, because a short name proves nothing about a line that also
     * has to hold a figure.
     */
    private fun longName(index: Int): String =
        "x".repeat(WidgetListRenderModel.MAX_NAME_CODE_POINTS + 10) + index

    private fun consent(): NameConsent = requireNotNull(
        NameConsent.from(
            WidgetSettings(
                kind = WidgetKind.CERTIFICATES,
                instanceId = InstanceId("kuma"),
                showNames = true,
            ),
        ),
    ) { "settings that say yes produced no consent" }

    private fun okState() = WidgetViewState.Display(WidgetDisplayState.Ok)

    private fun instance(state: WidgetViewState) = WidgetRenderModel.Instance(
        title = TITLE,
        state = state,
        counts = "▲35 ▼2",
        updatedAtEpochMs = UPDATED_AT,
    )

    /** One box a list widget is declared for, and the layout the platform shows in it. */
    private data class Step(
        val description: String,
        val layout: WidgetListViews.Layout,
        val widthDp: Int,
        val heightDp: Int,
    )

    private companion object {

        const val APP_WIDGET_ID = 4719
        const val UPDATED_AT = 1_700_000_000_000L
        const val TITLE = "Uptime Kuma"

        const val DEFAULT_FONT_SCALE = 1.0f

        /** The default, plus the two steps a user is most likely to pick. */
        val FONT_SCALES = listOf(DEFAULT_FONT_SCALE, 1.15f, 1.3f)

        /** How much of the height has to be left over at the default font scale. */
        const val MIN_FREE_HEIGHT_FRACTION = 0.15f

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

        val DETAIL_IDS = listOf(
            R.id.widget_row_0_detail,
            R.id.widget_row_1_detail,
            R.id.widget_row_2_detail,
            R.id.widget_row_3_detail,
            R.id.widget_row_4_detail,
            R.id.widget_row_5_detail,
            R.id.widget_row_6_detail,
            R.id.widget_row_7_detail,
        )

        /**
         * The three boxes, each with the layout the platform shows in it.
         *
         * The pairing is a claim, and `WidgetListStepChoiceDeviceTest` is what holds it to the
         * platform's own choice. Without that test a box paired with the wrong layout would
         * measure the wrong thing and pass.
         */
        val STEPS = listOf(
            Step(
                description = "two items at ${WidgetSizes.MIN_WIDTH_DP}×" +
                    "${WidgetSizes.MIN_HEIGHT_DP}dp",
                layout = WidgetListViews.Layout.COMPACT,
                widthDp = WidgetSizes.MIN_WIDTH_DP,
                heightDp = WidgetSizes.MIN_HEIGHT_DP,
            ),
            Step(
                description = "four items at ${WidgetSizes.MIN_WIDTH_DP}×" +
                    "${WidgetSizes.FULL_HEIGHT_DP}dp",
                layout = WidgetListViews.Layout.FULL,
                widthDp = WidgetSizes.MIN_WIDTH_DP,
                heightDp = WidgetSizes.FULL_HEIGHT_DP,
            ),
            Step(
                description = "eight items at ${WidgetSizes.LARGE_WIDTH_DP}×" +
                    "${WidgetSizes.LARGE_HEIGHT_DP}dp",
                layout = WidgetListViews.Layout.LARGE,
                widthDp = WidgetSizes.LARGE_WIDTH_DP,
                heightDp = WidgetSizes.LARGE_HEIGHT_DP,
            ),
        )
    }
}
