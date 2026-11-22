package io.github.jellyforgedev.widgora.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
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
import io.github.jellyforgedev.widgora.design.WidgetPresentation
import io.github.jellyforgedev.widgora.design.WidgoraText
import io.github.jellyforgedev.widgora.model.InstanceId
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
        // An installation with nothing to check. Its wording is the second longest the line has,
        // and until the words of this line were made to name their subject it was short enough
        // not to be worth measuring. It is measured now.
        "no monitors at all" to WidgetRenderModel.Instance(
            title = "Uptime Kuma",
            state = WidgetViewState.Display(WidgetDisplayState.Empty),
            counts = "",
            updatedAtEpochMs = UPDATED_AT,
        ),
        "nothing retrieved yet" to WidgetRenderModel.Instance(
            title = "Uptime Kuma",
            state = WidgetViewState.Display(WidgetDisplayState.Loading),
            counts = "",
            updatedAtEpochMs = null,
        ),
        "not set up" to WidgetRenderModel.NotSetUp,
        "settings unreadable" to WidgetRenderModel.ConfigurationUnreadable,
        // The newest of the three notices, and the one with the longest wording: it has to name
        // the repair, because "add an instance" is the wrong instruction for it.
        "instance gone" to WidgetRenderModel.InstanceGone,
    )

    @Test
    fun everyStepFitsAtEveryFontScale() {
        for (step in STEPS) {
            for (scale in FONT_SCALES) {
                for ((name, model) in models) {
                    assertFits(
                        description = "${step.description} at font scale $scale, $name",
                        step = step,
                        model = model,
                        fontScale = scale,
                    )
                }
            }
        }
    }

    /**
     * Each step both fits and is used.
     *
     * Two failures, opposite in direction, and the widget has had one of each. Fitting exactly is
     * not fitting: the layout that fitted on an emulator was cut off on a television, and the
     * difference was a padding nobody had counted — hence the floor. And a box two thirds empty
     * is not a widget using its space: at 640 × 320 dp the content sat small in the top-left
     * corner — hence the ceiling.
     */
    @Test
    fun everyStepUsesItsHeightWithoutRunningOutOfRoom() {
        for (step in STEPS) {
            for ((name, model) in models) {
                assertSpareHeight("${step.description}, $name", step, model)
            }
        }
    }

    /**
     * A larger box shows larger numbers.
     *
     * This is the question the whole change turns on, and the reason it is answered by a test
     * rather than by reading documentation: the counts are scaled by the platform through layout
     * attributes, and whether that works at all inside `RemoteViews` — inflated in another
     * process from a parcelled description — is not something the attribute's own documentation
     * says. If the scaling is ignored, both sizes come back equal and this fails.
     */
    @Test
    fun theCountsGrowWithTheBox() {
        val model = instance(
            counts = "▲35 ▼2",
            state = WidgetViewState.Display(WidgetDisplayState.Ok),
        )

        val smallest = countsTextSizePx(STEPS.first(), model)
        val largest = countsTextSizePx(STEPS.last(), model)

        assertTrue(
            "the counts did not grow with the box: ${smallest}px at " +
                "${STEPS.first().description}, ${largest}px at ${STEPS.last().description}",
            largest >= smallest * MIN_GROWTH,
        )
    }

    /** Scaling never takes the counts below the floor the design system sets. */
    @Test
    fun theCountsNeverFallBelowTheFloor() {
        val crowded = instance(
            counts = "▲12 ▼3 ◆2 ■1 ●4",
            state = WidgetViewState.Display(WidgetDisplayState.Stale),
        )

        for (step in STEPS) {
            for (scale in FONT_SCALES) {
                val floor = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    WidgoraText.Minimum.value,
                    scaled(scale).resources.displayMetrics,
                )
                val actual = countsTextSizePx(step, crowded, scale)
                assertTrue(
                    "${step.description} at font scale $scale scaled the counts to " +
                        "${actual}px, below the floor of ${floor}px",
                    actual >= floor - TOLERANCE_PX,
                )
            }
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
                val hosted = hosted(STEPS.first(), model, scale)
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

    /**
     * The state keeps its mark even where the wording has to give way.
     *
     * Every model at every font scale, not only the longest wording. The words of this line were
     * lengthened to name their subject — "Data current" rather than "Current" — and that is
     * exactly the change that pushes a shared line past its end. The mark must not be the
     * casualty: it is what identifies the state for a viewer who cannot tell the colours apart.
     * The word may shorten. The mark and the time may not.
     */
    @Test
    fun theStateKeepsItsMarkOnTheCompactVariant() {
        for (scale in FONT_SCALES) {
            for ((name, model) in models) {
                val hosted = hosted(STEPS.first(), model, scale)
                val state = hosted.findViewById<TextView>(R.id.widget_state)
                val expected = markOf(model)

                assertTrue(
                    "the state line lost its mark at font scale $scale ($name): " +
                        "expected '$expected'",
                    state.text.startsWith(expected),
                )
                assertTrue("the state line has no width ($name)", state.width > 0)
            }
        }
    }

    /** The mark a model is expected to carry, from the source the views are built from. */
    private fun markOf(model: WidgetRenderModel): String = when (model) {
        WidgetRenderModel.NotSetUp -> WidgetPresentation.MARK_NOT_SET_UP
        WidgetRenderModel.ConfigurationUnreadable -> WidgetPresentation.MARK_UNREADABLE
        WidgetRenderModel.InstanceGone -> WidgetPresentation.MARK_INSTANCE_GONE
        is WidgetRenderModel.Instance -> WidgetPresentation.of(model.state).mark
    }

    // --- The variant that names monitors -------------------------------------------------------

    /**
     * A widget with consent fits too, at every size and every font scale.
     *
     * This is the measurement the naming variant turns on. Two lines were added to the full
     * layout and the box did not grow; whether three names still leave the spare height this
     * project asks for is a question for a host view rather than for arithmetic. The compact
     * variant is measured as well, where it must come out identical to a widget without consent:
     * its layout declares no line for a name.
     */
    @Test
    fun theNamingVariantFitsAtEveryFontScale() {
        for (step in NAMING_STEPS) {
            for (scale in FONT_SCALES) {
                for ((name, model) in namedModels()) {
                    val context = scaled(scale)
                    assertFitsIn(
                        description = "${step.description} at font scale $scale, $name",
                        hosted = hostedViews(
                            step,
                            WidgetViews.buildNaming(context, APP_WIDGET_ID, model, step.layout),
                            scale,
                        ),
                    )
                }
            }
        }
    }

    /**
     * And it keeps the spare height, which is the part more lines put at risk.
     *
     * The ceiling is asked only of the fullest model, which is the last one in the list and the
     * case the box was measured for; see the note where that exemption is applied.
     */
    @Test
    fun theNamingVariantKeepsItsSpareHeight() {
        val models = namedModels()
        for (step in NAMING_STEPS) {
            for ((index, entry) in models.withIndex()) {
                val (name, model) = entry
                assertSpareHeightIn(
                    description = "${step.description}, $name",
                    hosted = hostedViews(
                        step,
                        WidgetViews.buildNaming(
                            baseContext,
                            APP_WIDGET_ID,
                            model,
                            step.layout,
                        ),
                        DEFAULT_FONT_SCALE,
                    ),
                    fillsTheBox = index == models.lastIndex,
                )
            }
        }
    }

    /**
     * In the two smaller steps, consent changes exactly one line: the title.
     *
     * The names themselves still do not fit in those boxes — that finding produced the third size
     * step and stands — so what a widget with consent gains there is the line it already had. This
     * asserts both halves at once: the title differs, and every other line is identical to what a
     * widget without consent draws. Compared on the inflated trees rather than on the layout
     * files, because that is where a difference would show.
     */
    @Test
    fun inTheSmallerStepsConsentChangesTheTitleAndNothingElse() {
        val model = namedModels().last().second
        val headline = requireNotNull(model.headline) { "the model has no title line to check" }

        for (step in listOf(NAMING_STEPS[0], NAMING_STEPS[1])) {
            val withConsent = lines(
                hostedViews(
                    step,
                    WidgetViews.buildNaming(baseContext, APP_WIDGET_ID, model, step.layout),
                    DEFAULT_FONT_SCALE,
                ),
            )
            val without = lines(
                hostedViews(
                    step,
                    WidgetViews.build(baseContext, APP_WIDGET_ID, model.instance, step.layout),
                    DEFAULT_FONT_SCALE,
                ),
            )

            val title = requireNotNull(withConsent[TITLE_ID]) { "${step.description}: no title" }
            assertTrue(
                "${step.description}: the title does not name the affected monitor: $title",
                title.contains(headline.name) && title.startsWith(headline.mark),
            )

            // Everything the two have in common apart from the title and the count beside it.
            val changed = (withConsent.keys + without.keys)
                .filterNot { id -> id == TITLE_ID || id == TITLE_MORE_ID }
                .filter { id -> withConsent[id] != without[id] }
            assertEquals(
                "${step.description}: consent changed a line other than the title",
                emptyList<String>(),
                changed,
            )
        }
    }

    /**
     * The step that has lines for the names keeps the instance on its title line.
     *
     * The opposite decision from the two steps above, and deliberately so: there the title is the
     * only line available, here the affected items have lines of their own and the title is what
     * says which instance this is.
     */
    @Test
    fun theNamingStepLeavesTheTitleAlone() {
        val step = NAMING_STEPS.last()
        val model = namedModels().last().second

        val lines = lines(
            hostedViews(
                step,
                WidgetViews.buildNaming(baseContext, APP_WIDGET_ID, model, step.layout),
                DEFAULT_FONT_SCALE,
            ),
        )

        assertEquals("the naming step changed its title line", TITLE, lines[TITLE_ID])
        assertTrue(
            "the naming step showed a count beside its title: ${lines[TITLE_MORE_ID]}",
            lines[TITLE_MORE_ID] == null,
        )
    }

    /**
     * The title line keeps its mark and its count at every font scale.
     *
     * The same division the compact variant's bottom line already makes, and for the same reason:
     * when a line is too long something has to give, and what may give is the part that can be
     * recovered by looking elsewhere. The name may be shortened — the widget opens the application
     * where the whole list is. The mark may not, because it is what says what kind of trouble this
     * is to a viewer who cannot tell the colours apart, and the count may not, because nothing
     * else on the surface says how many others there are.
     *
     * The names are as long as the model permits, since a short name proves nothing about a line
     * that has to hold a mark and a count as well.
     */
    @Test
    fun theTitleLineKeepsItsMarkAndItsCountAtEveryFontScale() {
        val model = namedModels().last().second
        val headline = requireNotNull(model.headline) { "the model has no title line to check" }

        for (step in listOf(NAMING_STEPS[0], NAMING_STEPS[1])) {
            for (scale in FONT_SCALES) {
                val hosted = hostedViews(
                    step,
                    WidgetViews.buildNaming(scaled(scale), APP_WIDGET_ID, model, step.layout),
                    scale,
                )
                val where = "${step.description} at font scale $scale"
                assertFitsIn(where, hosted)

                val title = hosted.findViewById<TextView>(R.id.widget_title)
                assertTrue(
                    "$where: the title line lost its mark: '${title.text}'",
                    title.text.startsWith(headline.mark),
                )
                assertTrue("$where: the title line has no width", title.width > 0)

                val more = hosted.findViewById<TextView>(R.id.widget_title_more)
                assertEquals("$where: the count is not visible", View.VISIBLE, more.visibility)
                assertEquals(
                    "$where: the count was shortened, which is the one thing it may not be",
                    0,
                    more.layout?.getEllipsisCount(0) ?: 0,
                )
            }
        }
    }

    /** Every visible line of a hosted widget by its resource name. */
    private fun lines(hosted: AppWidgetHostView): Map<String, String> =
        visibleTextViews(widgetRoot(hosted)).associate { line ->
            name(line) to line.text.toString()
        }

    /**
     * In the box meant to carry names, they are actually drawn.
     *
     * Without this the two tests above could both pass on a widget that quietly draws nothing —
     * the failure a measurement test is least able to notice on its own.
     */
    @Test
    fun theFullVariantDrawsTheNamesItWasGiven() {
        val step = NAMING_STEPS.last()
        val model = namedModels().last().second

        val hosted = hostedViews(
            step,
            WidgetViews.buildNaming(baseContext, APP_WIDGET_ID, model, step.layout),
            DEFAULT_FONT_SCALE,
        )

        val names = hosted.findViewById<TextView>(R.id.widget_names)
        val more = hosted.findViewById<TextView>(R.id.widget_more)
        assertEquals("the names line is not visible", View.VISIBLE, names.visibility)
        for (shown in model.names) {
            assertTrue(
                "the names line does not carry '$shown': ${names.text}",
                names.text.contains(shown),
            )
        }
        assertEquals("the overflow line is not visible", View.VISIBLE, more.visibility)
        assertEquals("the overflow count was shortened", 0, more.layout?.getEllipsisCount(0) ?: 0)
    }

    /**
     * The naming models, each stressing the layout more than the one before.
     *
     * The names are as long as the model permits, because a short name proves nothing about a
     * line that has to hold three of them.
     */
    private fun namedModels(): List<Pair<String, NamedWidgetRenderModel>> {
        val longest = "x".repeat(NamedWidgetRenderModel.MAX_NAME_CODE_POINTS)
        val three = List(NamedWidgetRenderModel.MAX_NAMES) { index -> longest.dropLast(1) + index }
        return listOf(
            "one name" to named(listOf(longest), rest = 0),
            "the full set of names" to named(three, rest = 0),
            "the full set and more" to named(three, rest = 32),
        )
    }

    /**
     * A naming model, built the one way it can be built.
     *
     * The consent token comes from stored settings that say yes, exactly as it does in the
     * application: there is no other way to obtain one, which is the point of it.
     */
    private fun named(names: List<String>, rest: Int): NamedWidgetRenderModel {
        val consent = requireNotNull(
            NameConsent.from(
                WidgetSettings(
                    kind = WidgetKind.STATUS,
                    instanceId = InstanceId("kuma"),
                    showNames = true,
                ),
            ),
        ) { "settings that say yes produced no consent" }

        val entries = names.mapIndexed { index, name -> failed("m$index", name) } +
            List(rest) { index -> failed("r$index", "rest-$index.example.invalid") }

        return NamedWidgetRenderModel.of(
            consent = consent,
            instance = instance(
                counts = "▲35 ▼${entries.size}",
                state = WidgetViewState.Display(WidgetDisplayState.Ok),
            ),
            overview = MonitorOverview(entries),
        )
    }

    private fun failed(monitorId: String, name: String): MonitorEntry = MonitorEntry.truncating(
        monitorId = monitorId,
        name = name,
        state = MonitorState.FAILED,
        responseTimeMs = null,
    )

    // --- The measurement -----------------------------------------------------------------------

    private fun assertFits(
        description: String,
        step: Step,
        model: WidgetRenderModel,
        fontScale: Float,
    ) = assertFitsIn(description, hosted(step, model, fontScale))

    /**
     * The measurement itself, given a widget already inside a host view.
     *
     * Separated from the model so that both kinds of widget are measured by the same code. A
     * second copy of these two assertions for the naming variant is how one of them comes to be
     * relaxed on one side and not the other.
     */
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

    /**
     * How much of the height is not covered by text, which has to sit between two bounds.
     *
     * "Covered by text" is the height of the glyphs, row by row, not the height of the boxes
     * holding them. That distinction is the whole point: once the counts take the leftover room
     * as their box, a box-based measure reports every layout as full whether the text grew into
     * it or not, and the waste this test exists to catch would be invisible to it.
     */
    private fun assertSpareHeight(description: String, step: Step, model: WidgetRenderModel) =
        assertSpareHeightIn(description, hosted(step, model, DEFAULT_FONT_SCALE))

    /**
     * @param fillsTheBox whether this widget is expected to use the height it was given. False
     *   for a widget that has less to show than its box was measured for — see the ceiling below.
     */
    private fun assertSpareHeightIn(
        description: String,
        hosted: AppWidgetHostView,
        fillsTheBox: Boolean = true,
    ) {
        val root = widgetRoot(hosted)
        val ink = inkHeight(root)
        val free = (root.height - ink).toFloat() / root.height
        val percent = (free * 100).toInt()

        assertTrue(
            "$description: only $percent% of the height is spare, " +
                "${(MIN_FREE_HEIGHT_FRACTION * 100).toInt()}% is the floor " +
                "(${ink}px of text in ${root.height}px${hostPadding(hosted)}); " +
                "by line: ${inkByLine(root)}",
            free >= MIN_FREE_HEIGHT_FRACTION,
        )
        // The ceiling asks whether the content grew into its box, and only the counts can do
        // that — they are the line that scales. A widget with nothing to count has nothing to
        // fill the box with, and demanding that it fill one anyway would be demanding that it
        // invent something to show. The test found this itself, by failing on "nothing retrieved
        // yet" with 64 % of the height spare, which is the truth about that state.
        val counts = root.findViewById<View>(R.id.widget_counts)
        if (counts == null || counts.visibility != View.VISIBLE) return

        // The second exemption, and it has the same shape as the first. A widget that names
        // monitors is given a box measured for the full set of them — three names and a count of
        // the rest — and its counts line is capped so that the full set still fits. With one name
        // in that box the height is genuinely underused, and there is nothing it should be filled
        // with: growing the figures until it looks full is what the cap exists to prevent. So the
        // ceiling is asked of the full case, which is the case the box was measured for.
        if (!fillsTheBox) return

        assertTrue(
            "$description: $percent% of the height is spare, " +
                "${(MAX_FREE_HEIGHT_FRACTION * 100).toInt()}% is the ceiling — the content is " +
                "not using the box (${ink}px of text in ${root.height}px${hostPadding(hosted)})",
            free <= MAX_FREE_HEIGHT_FRACTION,
        )
    }

    /** The size the platform settled on for the counts, in pixels. */
    private fun countsTextSizePx(
        step: Step,
        model: WidgetRenderModel.Instance,
        fontScale: Float = DEFAULT_FONT_SCALE,
    ): Float = hosted(step, model, fontScale)
        .findViewById<TextView>(R.id.widget_counts)
        .textSize

    /**
     * The height the glyphs occupy, row by row.
     *
     * A row of the vertical stack contributes its own text height; a row that holds several
     * views side by side contributes the tallest of them, because they share one line.
     */
    private fun inkHeight(root: View): Int {
        if (root !is ViewGroup) return 0
        return (0 until root.childCount)
            .map { index -> root.getChildAt(index) }
            .filter { row -> row.visibility == View.VISIBLE }
            .sumOf { row -> rowInk(row) }
    }

    /**
     * Which line contributes how much, for the failure message.
     *
     * A sum says that something does not fit; it does not say what to change. This was written
     * after a failure that cost two wrong guesses: the line responsible turned out to be the one
     * that scales, and nothing in the message had pointed at it. Resource names and measurements
     * only — no text of the widget's own, so the message carries no instance or monitor name.
     */
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

    private fun rowInk(row: View): Int = when (row) {
        is TextView -> row.layout?.height ?: 0
        is ViewGroup -> visibleTextViews(row).maxOfOrNull { it.layout?.height ?: 0 } ?: 0
        else -> 0
    }

    /**
     * The widget inside a real host view, measured at the given box and font scale.
     *
     * The host view is what makes this test worth running: it applies the padding the platform
     * gives a widget of this provider, which is the margin the first version of this test spent
     * its whole life ignoring.
     */
    private fun hosted(
        step: Step,
        model: WidgetRenderModel,
        fontScale: Float,
    ): AppWidgetHostView = hostedViews(
        step,
        WidgetViews.build(scaled(fontScale), APP_WIDGET_ID, model, step.layout),
        fontScale,
    )

    /**
     * Any prepared views inside a real host view, measured at the given box and font scale.
     *
     * It takes the views rather than a model because there are two kinds of widget now and they
     * are built by two separate functions — which is the arrangement that keeps a nameless model
     * out of the naming renderer, and it holds here as well.
     */
    private fun hostedViews(
        step: Step,
        views: RemoteViews,
        fontScale: Float,
    ): AppWidgetHostView {
        val context = scaled(fontScale)
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

    private fun instance(counts: String, state: WidgetViewState) = WidgetRenderModel.Instance(
        title = "Uptime Kuma",
        state = state,
        counts = counts,
        updatedAtEpochMs = UPDATED_AT,
    )

    /**
     * One box the widget is declared for, and the layout the platform shows in it.
     *
     * The second half of that sentence is a claim, and for a while it was no more than one: every
     * test here builds the layout it names and measures that, so a box paired with the wrong
     * layout measures the wrong thing and passes. It did — the box carrying names was paired with
     * a layout the platform could not reach at that size. `WidgetStepChoiceDeviceTest` is what
     * holds the pairing to the platform's own choice, and the tables below are sound only because
     * it does.
     */
    private data class Step(
        val description: String,
        val layout: WidgetViews.Layout,
        val widthDp: Int,
        val heightDp: Int,
    )

    private companion object {

        const val PROVIDER = "io.github.jellyforgedev.widgora.widget.CountsWidgetProvider"
        const val APP_WIDGET_ID = 4714
        const val UPDATED_AT = 1_700_000_000_000L

        /** The instance name every model in this test carries, and the title without consent. */
        const val TITLE = "Uptime Kuma"

        /** The two lines of the title row, by resource name, as [lines] reports them. */
        const val TITLE_ID = "widget_title"
        const val TITLE_MORE_ID = "widget_title_more"

        const val DEFAULT_FONT_SCALE = 1.0f

        /** The default, plus the two steps a user is most likely to pick. */
        val FONT_SCALES = listOf(DEFAULT_FONT_SCALE, 1.15f, 1.3f)

        /** How much of the height has to be left over at the default font scale. */
        const val MIN_FREE_HEIGHT_FRACTION = 0.15f

        /**
         * How much of the height may be left over.
         *
         * Above this the widget is not using the box it was given, which was the state of the
         * largest size before the counts were made to scale.
         */
        const val MAX_FREE_HEIGHT_FRACTION = 0.40f

        /** How much larger the counts have to be in the largest box than in the smallest. */
        const val MIN_GROWTH = 1.5f

        /** One rounding step; a size snapped to whole pixels may land just under its floor. */
        const val TOLERANCE_PX = 0.5f

        /**
         * Every box the widget is declared for, smallest first, each with the layout the
         * platform picks for it.
         *
         * The three are the declared minimum, the height at which the layout changes, and the
         * declared maximum — the same three the debug host offers, so what a test measures and
         * what somebody looks at on a television are the same sizes.
         */
        val STEPS = listOf(
            Step(
                description = "compact at ${WidgetSizes.MIN_WIDTH_DP}×" +
                    "${WidgetSizes.MIN_HEIGHT_DP}dp",
                layout = WidgetViews.Layout.COMPACT,
                widthDp = WidgetSizes.MIN_WIDTH_DP,
                heightDp = WidgetSizes.MIN_HEIGHT_DP,
            ),
            Step(
                description = "full at ${WidgetSizes.MIN_WIDTH_DP}×" +
                    "${WidgetSizes.FULL_HEIGHT_DP}dp",
                layout = WidgetViews.Layout.FULL,
                widthDp = WidgetSizes.MIN_WIDTH_DP,
                heightDp = WidgetSizes.FULL_HEIGHT_DP,
            ),
            Step(
                description = "full at ${WidgetSizes.LARGE_WIDTH_DP}×" +
                    "${WidgetSizes.LARGE_HEIGHT_DP}dp",
                layout = WidgetViews.Layout.FULL,
                widthDp = WidgetSizes.LARGE_WIDTH_DP,
                heightDp = WidgetSizes.LARGE_HEIGHT_DP,
            ),
        )

        /**
         * The steps a widget **with consent** is offered, smallest first.
         *
         * The first two are the ones with no line for a name, where consent shows itself in the
         * title instead; the last two are the step that carries the names, measured at the
         * narrowest width it can be given as well as at the widest — three names on a 300dp line
         * wrap onto more rows than they do on a 640dp one, so the narrow case is the one that
         * decides whether the step fits.
         *
         * The order matters to the tests that take "the two smaller steps" by index: those are the
         * two whose title line changes.
         */
        val NAMING_STEPS = listOf(
            Step(
                description = "compact with consent at ${WidgetSizes.MIN_WIDTH_DP}×" +
                    "${WidgetSizes.MIN_HEIGHT_DP}dp",
                layout = WidgetViews.Layout.COMPACT,
                widthDp = WidgetSizes.MIN_WIDTH_DP,
                heightDp = WidgetSizes.MIN_HEIGHT_DP,
            ),
            Step(
                description = "full with consent at ${WidgetSizes.MIN_WIDTH_DP}×" +
                    "${WidgetSizes.FULL_HEIGHT_DP}dp",
                layout = WidgetViews.Layout.FULL,
                widthDp = WidgetSizes.MIN_WIDTH_DP,
                heightDp = WidgetSizes.FULL_HEIGHT_DP,
            ),
            Step(
                description = "named at ${WidgetSizes.MIN_WIDTH_DP}×" +
                    "${WidgetSizes.NAMES_HEIGHT_DP}dp",
                layout = WidgetViews.Layout.FULL_NAMED,
                widthDp = WidgetSizes.MIN_WIDTH_DP,
                heightDp = WidgetSizes.NAMES_HEIGHT_DP,
            ),
            Step(
                description = "named at ${WidgetSizes.LARGE_WIDTH_DP}×" +
                    "${WidgetSizes.NAMES_HEIGHT_DP}dp",
                layout = WidgetViews.Layout.FULL_NAMED,
                widthDp = WidgetSizes.LARGE_WIDTH_DP,
                heightDp = WidgetSizes.NAMES_HEIGHT_DP,
            ),
        )
    }
}
