package io.github.jellyforgedev.widgora.widget

import android.content.Context
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.design.WidgetPresentation
import io.github.jellyforgedev.widgora.design.WidgoraColors

/**
 * The one place a widget's view hierarchy is built.
 *
 * It is a single function on purpose. Everything a launcher can read comes out of here, so there
 * is one piece of code to look at when asking what WIDGORA hands to another process, and one to
 * change when that answer has to change.
 *
 * ### What it may draw, and what it may not
 *
 * Only `LinearLayout` and `TextView` are used, and no bitmap is produced at any point. That keeps
 * the widget inside what `RemoteViews` supports without a fallback, and it means the per-widget
 * image memory ceiling the platform enforces cannot be reached: there are no images.
 *
 * The words and marks come from [WidgetPresentation], the same value the dashboard tile reads, so
 * a state cannot end up described one way on the tile and another way in the widget. Colours that
 * depend on the state are taken from that same value rather than from a second table of colour
 * resources, for the same reason. The colours and sizes that are fixed in the layout come from
 * `widget_tokens.xml`, and a test holds those against the design system's own tokens.
 */
object WidgetViews {

    /**
     * How much of the widget is drawn, which depends on how much room there is.
     *
     * The two differ in one line and nothing else. Both carry the state's mark and its word, and
     * both carry the time the data was obtained; the compact one puts those two on a shared line
     * instead of on two lines of their own, which is what makes it fit the smallest box the
     * widget is declared for.
     */
    internal enum class Layout(val resource: Int) {
        COMPACT(R.layout.widget_counts_compact),
        FULL(R.layout.widget_counts),

        /**
         * The full layout in a box tall enough to carry the monitor names as well.
         *
         * A resource of its own, and that was not the first attempt: the names were two hidden
         * lines in [FULL]'s layout, switched on for this step. They did not fit. What made the
         * difference is the one thing a second file can express and a runtime call cannot — the
         * ceiling the counts line may scale to. See the layout file and
         * [WidgetSizes.NAMES_HEIGHT_DP] for the measurements.
         *
         * Only [buildNaming] offers this step, so a widget without consent never reaches it.
         */
        FULL_NAMED(R.layout.widget_counts_named),
    }

    /**
     * Builds the views for one widget.
     *
     * From Android 12 the host is handed both variants with the size each one is for, and picks
     * per widget; the platform does the choosing, which is the only party that knows the box.
     * Below that there is no such mechanism, so the compact variant is used at every size — it
     * is correct everywhere, merely plainer than it needs to be in a large box.
     *
     * @param appWidgetId the widget this is for; it decides the pending intent, so two widgets
     *   never share one
     */
    fun build(context: Context, appWidgetId: Int, model: WidgetRenderModel): RemoteViews {
        val hostPadding = WidgetStepSizes.hostPaddingDp(context)
        return bySize(
            mapOf(
                Layout.COMPACT to compactStep(hostPadding),
                Layout.FULL to fullStep(hostPadding),
            ),
        ) { layout -> build(context, appWidgetId, model, layout) }
    }

    /**
     * Builds the views for one widget **that may name monitors**.
     *
     * A second entry point rather than a parameter on the first, and the two take one model each:
     * [NamedWidgetRenderModel] can only be obtained with a consent token, and there is no overload
     * accepting both models, so no call site can hand a nameless model to the naming renderer or
     * the reverse. See SECURITY.md, "A widget may name a monitor only by consent".
     *
     * ### Three size steps rather than two, and why
     *
     * Consent does not change the two smaller steps at all. The compact layout declares no line
     * for a name, and in the full step the names measurably do not fit — so in both of those a
     * widget with consent draws exactly what a widget without it draws. A third step is offered
     * for the box that can carry them; the figures are in [WidgetSizes.NAMES_HEIGHT_DP].
     *
     * The consequence is worth stating plainly: a user who allows names and keeps the widget
     * small sees none. That is better than the alternative, which was a widget whose bottom line
     * is cut off.
     */
    fun buildNaming(
        context: Context,
        appWidgetId: Int,
        model: NamedWidgetRenderModel,
    ): RemoteViews {
        val hostPadding = WidgetStepSizes.hostPaddingDp(context)
        return bySize(
            mapOf(
                Layout.COMPACT to compactStep(hostPadding),
                Layout.FULL to fullStep(hostPadding),
                Layout.FULL_NAMED to namedStep(hostPadding),
            ),
        ) { layout -> buildNaming(context, appWidgetId, model, layout) }
    }

    /**
     * Offers the host every variant with the size it is for, where the platform supports it.
     *
     * From Android 12 the host picks per widget, which is the only party that knows the box.
     * Below that there is no such mechanism, so the compact variant is used at every size — it is
     * correct everywhere, merely plainer than it needs to be in a large box.
     *
     * The sizes are what the widget is **offered** at each step, not the boxes it is declared
     * for: the platform compares these keys against the box minus the host's own padding. The
     * difference is 24dp, it is the whole reason the tallest step was unreachable, and
     * [WidgetStepSizes] carries the evidence.
     */
    private inline fun bySize(
        steps: Map<Layout, SizeF>,
        of: (Layout) -> RemoteViews,
    ): RemoteViews {
        val compact = of(Layout.COMPACT)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return compact

        return RemoteViews(
            steps.entries.associate { (layout, size) ->
                size to if (layout == Layout.COMPACT) compact else of(layout)
            },
        )
    }

    private fun compactStep(hostPaddingDp: SizeF) = WidgetStepSizes.offered(
        WidgetSizes.MIN_WIDTH_DP,
        WidgetSizes.MIN_HEIGHT_DP,
        hostPaddingDp,
    )

    private fun fullStep(hostPaddingDp: SizeF) = WidgetStepSizes.offered(
        WidgetSizes.MIN_WIDTH_DP,
        WidgetSizes.FULL_HEIGHT_DP,
        hostPaddingDp,
    )

    /**
     * The step that carries the names, keyed by the narrowest box it may be given.
     *
     * The width is the declared minimum rather than the declared maximum, and that is deliberate:
     * the names fit on a 300dp line, measured at three font scales, so a tall narrow box deserves
     * them as much as a tall wide one does. Keying it to 640dp would make the step depend on a
     * width it does not need.
     */
    private fun namedStep(hostPaddingDp: SizeF) = WidgetStepSizes.offered(
        WidgetSizes.MIN_WIDTH_DP,
        WidgetSizes.NAMES_HEIGHT_DP,
        hostPaddingDp,
    )

    /**
     * One variant on its own.
     *
     * Visible beyond this file so that a test can measure each variant at the smallest size it
     * is declared for. Through the public entry point the platform picks, which is correct in a
     * host and useless in a test: it would measure whichever variant the platform happened to
     * choose and report that as both.
     */
    internal fun build(
        context: Context,
        appWidgetId: Int,
        model: WidgetRenderModel,
        layout: Layout,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layout.resource)
        views.setOnClickPendingIntent(
            R.id.widget_root,
            WidgetLaunch.pendingIntentFor(context, appWidgetId),
        )

        when (model) {
            WidgetRenderModel.NotSetUp -> views.fill(
                title = context.getString(R.string.app_name),
                counts = null,
                mark = WidgetPresentation.MARK_NOT_SET_UP,
                label = context.getString(R.string.widget_not_set_up),
                stateColor = WidgoraColors.StateEmpty.toArgb(),
                footer = null,
            )

            WidgetRenderModel.ConfigurationUnreadable -> views.fill(
                title = context.getString(R.string.app_name),
                counts = null,
                mark = WidgetPresentation.MARK_UNREADABLE,
                label = context.getString(R.string.widget_config_unreadable),
                stateColor = WidgoraColors.StateError.toArgb(),
                footer = null,
            )

            // The instance this widget was configured for is gone. The title is the product
            // rather than the instance, because the instance is exactly what is no longer there,
            // and no part of this says which one it was: a name is of no use on a surface that
            // cannot offer the repair, and the repair is named in the line instead.
            WidgetRenderModel.InstanceGone -> views.fill(
                title = context.getString(R.string.app_name),
                counts = null,
                mark = WidgetPresentation.MARK_INSTANCE_GONE,
                label = context.getString(R.string.widget_instance_gone),
                stateColor = WidgoraColors.StateStale.toArgb(),
                footer = null,
            )

            is WidgetRenderModel.Instance -> {
                val presentation = WidgetPresentation.of(model.state)
                views.fill(
                    title = model.title,
                    // The value is hidden rather than left standing wherever the state says there
                    // is none to show: a notice means nothing was even attempted.
                    counts = model.counts
                        .takeIf { presentation.showsData && it.isNotBlank() }
                        ?.let(::coloured),
                    mark = presentation.mark,
                    label = context.getString(presentation.label),
                    stateColor = presentation.color.toArgb(),
                    footer = footerFor(context, model, presentation, layout),
                )
            }
        }
        return views
    }

    /**
     * One variant of a widget that may name monitors.
     *
     * It builds the nameless views first, from the nameless model inside, and then adds the two
     * lines that carry names. Nothing is duplicated and nothing about the rest of the widget
     * changes: a widget with consent and a widget without it differ in exactly these two lines.
     *
     * Visible beyond this file for the same reason the nameless variant is — a test has to measure
     * each layout at the smallest size it is declared for.
     */
    internal fun buildNaming(
        context: Context,
        appWidgetId: Int,
        model: NamedWidgetRenderModel,
        layout: Layout,
    ): RemoteViews {
        val views = build(context, appWidgetId, model.instance, layout)

        // A notice or a loading state has nothing to name: no retrieval produced the list. The
        // condition is the same one the counts line uses, so a name cannot appear beside a
        // hidden value.
        val showsData = WidgetPresentation.of(model.instance.state).showsData

        // The two smaller steps have no line to add — the names were measured not to fit, see
        // WidgetSizes.NAMES_HEIGHT_DP — so what they have is the line they already own. With
        // consent and something actually wrong, the title names the first affected monitor
        // instead of the instance; otherwise it stays exactly what a widget without consent
        // shows. In the step with lines of its own the title is left alone.
        if (layout != Layout.FULL_NAMED) {
            model.headline?.takeIf { showsData }?.let { headline ->
                views.fillHeadline(context, headline)
            }
            return views
        }

        val names = if (showsData) model.names else emptyList()
        val remaining = if (showsData) model.remaining else 0

        views.setViewVisibility(
            R.id.widget_names,
            if (names.isEmpty()) View.GONE else View.VISIBLE,
        )
        views.setTextViewText(
            R.id.widget_names,
            names.joinToString(context.getString(R.string.summary_separator)),
        )

        views.setViewVisibility(
            R.id.widget_more,
            if (remaining > 0) View.VISIBLE else View.GONE,
        )
        views.setTextViewText(
            R.id.widget_more,
            if (remaining > 0) {
                context.resources.getQuantityString(
                    R.plurals.dashboard_affected_more,
                    remaining,
                    remaining,
                )
            } else {
                ""
            },
        )
        return views
    }

    /**
     * The bottom line: when the data was obtained, or the statement that there is none.
     *
     * "No data yet" is not a placeholder standing in for a value — it is the true state of a
     * widget whose application has not retrieved anything since the process it belongs to was
     * last started. Everything a widget shows lives in memory, so that is the ordinary condition
     * after the system has reclaimed the application.
     */
    private fun footerFor(
        context: Context,
        model: WidgetRenderModel.Instance,
        presentation: WidgetPresentation,
        layout: Layout,
    ): String = WidgetLines.footer(
        context = context,
        updatedAtEpochMs = model.updatedAtEpochMs,
        valueIsOutdated = presentation.valueIsOutdated,
        // Only the compact variant shares the bottom line with the state; the other two give the
        // time a line of its own and can afford the whole sentence.
        sharesItsLine = layout == Layout.COMPACT,
    )

    /**
     * The counts line with each mark in the colour of the state it stands for.
     *
     * A mark in the application is green, red, amber, blue or grey; in the widget it used to be
     * white, because a widget's text colour is fixed in the layout and nothing said otherwise.
     * The shape already distinguished the five states, so this is not what makes them
     * distinguishable — it is what makes the widget look like the same product as the screen
     * beside it.
     *
     * Only the marks are spanned. The numbers keep the text colour of the view, so the figure a
     * reader is actually after stays at full contrast against the surface rather than inheriting
     * a state colour.
     *
     * The colouring itself is in [WidgetLines], because the list widgets colour their marks the
     * same way, and two implementations of it is how a mark comes to be green on one widget and
     * white on another.
     */
    private fun coloured(counts: String): CharSequence = WidgetLines.coloured(counts)

    /**
     * Puts the first affected monitor in the title line, with the count of the others beside it.
     *
     * The mark leads, as it does on the state line and in the counts, so the line still says what
     * kind of trouble it is for a viewer who cannot tell the colours apart. The count goes into
     * the second view rather than onto the end of the text: that view keeps its width while the
     * name is shortened, and a "+2" on the end of a single text would be the first thing an
     * ellipsis took.
     *
     * No colour is set. The title is the one line of the widget drawn in the plain text colour,
     * and a title that changed colour with the state would be saying what the state line below it
     * already says, twice, in the place a reader looks for the subject rather than the verdict.
     */
    private fun RemoteViews.fillHeadline(
        context: Context,
        headline: NamedWidgetRenderModel.Headline,
    ) {
        setTextViewText(R.id.widget_title, WidgetLines.marked(headline.mark, headline.name))

        val others = headline.others
        setViewVisibility(R.id.widget_title_more, if (others > 0) View.VISIBLE else View.GONE)
        setTextViewText(
            R.id.widget_title_more,
            if (others > 0) context.getString(R.string.widget_title_more, others) else "",
        )
    }

    private fun RemoteViews.fill(
        title: String,
        counts: CharSequence?,
        mark: String,
        label: String,
        stateColor: Int,
        footer: String?,
    ) {
        setTextViewText(R.id.widget_title, title)

        // Stated rather than left to the layout's own default. A widget without consent has no
        // count beside its title, and this is the function that describes such a widget in full;
        // the naming renderer runs after it and switches the view back on where there is one.
        setViewVisibility(R.id.widget_title_more, View.GONE)

        setViewVisibility(R.id.widget_counts, if (counts == null) View.GONE else View.VISIBLE)
        setTextViewText(R.id.widget_counts, counts ?: "")

        setTextViewText(R.id.widget_state, WidgetLines.marked(mark, label))
        setTextColor(R.id.widget_state, stateColor)

        setViewVisibility(R.id.widget_footer, if (footer == null) View.GONE else View.VISIBLE)
        setTextViewText(R.id.widget_footer, footer.orEmpty())
    }

}
