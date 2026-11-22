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
import kotlin.math.abs

/**
 * The one place a list widget's view hierarchy is built.
 *
 * A second renderer beside [WidgetViews], and not a parameter on it. The two draw different
 * surfaces from different models: one shows a counted summary that scales to fill its box, the
 * other shows named items at a fixed size. Sharing one function would mean a `when` over the model
 * inside every line of it.
 *
 * What they do share is in [WidgetLines] — the time, the mark in front of a state word, the colour
 * of a mark — so neither can describe the same situation in different words.
 *
 * ### What it may draw
 *
 * Only `LinearLayout` and `TextView`, and no bitmap at any point, exactly as the counts widget. The
 * per-widget image memory ceiling the platform enforces cannot be reached because there are no
 * images.
 *
 * ### Why there is only one entry point, and why it takes a consent model
 *
 * Every line this renderer draws beyond the state and the time is a monitor name. There is no
 * nameless version of a list widget to fall back to, so the model it takes is one that can only be
 * built with a [NameConsent] — see [WidgetListRenderModel]. A widget whose settings carry no
 * consent never reaches this renderer at all; it is drawn from [WidgetViews] like any other widget
 * with nothing to show.
 */
object WidgetListViews {

    /**
     * How many items are drawn, which depends on how much room there is.
     *
     * The budgets are measured rather than chosen, and the measurement is in each layout file. The
     * rule when one of them does not hold is to carry fewer items, never smaller text: 18sp is the
     * floor for a surface read from three metres away, and a list nobody can read is not a list.
     */
    internal enum class Layout(val resource: Int, val rowBudget: Int) {
        COMPACT(R.layout.widget_list_compact, rowBudget = 2),
        FULL(R.layout.widget_list, rowBudget = 4),

        /** Eight items, in two columns. One column of eight does not fit; see the layout file. */
        LARGE(R.layout.widget_list_large, rowBudget = 8),
    }

    /**
     * Builds the views for one list widget.
     *
     * From Android 12 the host is handed all three variants with the size each one is for and picks
     * per widget, which is the only party that knows the box. Below that there is no such
     * mechanism, so the compact variant is used at every size — it is correct everywhere, merely
     * shorter than it needs to be in a large box.
     *
     * @param appWidgetId the widget this is for; it decides the pending intent, so two widgets
     *   never share one
     */
    fun build(
        context: Context,
        appWidgetId: Int,
        model: WidgetListRenderModel,
    ): RemoteViews {
        val hostPadding = WidgetStepSizes.hostPaddingDp(context)
        val compact = build(context, appWidgetId, model, Layout.COMPACT)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return compact

        return RemoteViews(
            mapOf(
                step(WidgetSizes.MIN_WIDTH_DP, WidgetSizes.MIN_HEIGHT_DP, hostPadding) to compact,
                step(WidgetSizes.MIN_WIDTH_DP, WidgetSizes.FULL_HEIGHT_DP, hostPadding) to
                    build(context, appWidgetId, model, Layout.FULL),
                // Keyed to the declared maximum **width** as well as its height, unlike the step
                // that carries monitor names in the counts widget. There the width was irrelevant;
                // here it is the whole reason the step exists — eight items need two columns, and
                // two columns need the wide box.
                step(WidgetSizes.LARGE_WIDTH_DP, WidgetSizes.LARGE_HEIGHT_DP, hostPadding) to
                    build(context, appWidgetId, model, Layout.LARGE),
            ),
        )
    }

    /**
     * What is left of a declared box for the widget itself: the key the platform compares.
     *
     * Not the declared box. The platform asks for the variant deserved by the laid-out box minus
     * the host's own padding, and using a declared figure as a key is what once made the tallest
     * step of the counts widget unreachable. [WidgetStepSizes] carries the evidence.
     */
    private fun step(widthDp: Int, heightDp: Int, hostPaddingDp: SizeF): SizeF =
        WidgetStepSizes.offered(widthDp, heightDp, hostPaddingDp)

    /**
     * One variant on its own.
     *
     * Visible beyond this file so that a test can measure each variant at the box it is built for.
     * Through the public entry point the platform picks, which is correct in a host and useless in
     * a test: it would measure whichever variant the platform happened to choose.
     */
    internal fun build(
        context: Context,
        appWidgetId: Int,
        model: WidgetListRenderModel,
        layout: Layout,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layout.resource)
        views.setOnClickPendingIntent(
            R.id.widget_root,
            WidgetLaunch.pendingIntentFor(context, appWidgetId),
        )

        val presentation = WidgetPresentation.of(model.instance.state)

        // A notice, or a state with no data behind it, has nothing to list: no retrieval produced
        // the items. The condition is the same one the counts line uses in the other renderer, so
        // a name can never appear beside a value that is hidden.
        val shown = if (presentation.showsData) {
            model.rows.take(layout.rowBudget)
        } else {
            emptyList()
        }
        val total = if (presentation.showsData) model.total else 0

        // Retrieved, and there is nothing to list. Not the same as a state with no data behind it:
        // there the state line is what says so, and claiming that nothing runs out soon would be a
        // claim about data nobody has.
        val nothingToList = presentation.showsData && total == 0

        fillHeader(views, context, model, shown.size, total, nothingToList)
        fillRows(views, context, layout, shown)
        fillEmpty(views, context, model, nothingToList)
        fillBottom(views, context, model, presentation)
        return views
    }

    /**
     * The title line, and beside it how many items this box had no room for.
     *
     * Both are hidden while there is nothing to list, and only then. The message takes the surface
     * in that one case, and the instance's name is the thing on it nobody needs: a widget with no
     * items is not reporting on an instance, it is reporting that there is nothing to report. While
     * a retrieval has produced nothing at all the title stays — the state line beside it says what
     * is going on, and a widget with no title and no items would look broken rather than busy.
     */
    private fun fillHeader(
        views: RemoteViews,
        context: Context,
        model: WidgetListRenderModel,
        shownRows: Int,
        total: Int,
        nothingToList: Boolean,
    ) {
        views.setViewVisibility(R.id.widget_title, visibility(!nothingToList))
        views.setTextViewText(R.id.widget_title, WidgetLines.coloured(model.title))

        val left = total - shownRows
        views.setViewVisibility(R.id.widget_title_more, visibility(left > 0))
        views.setTextViewText(
            R.id.widget_title_more,
            if (left > 0) context.getString(R.string.widget_title_more, left) else "",
        )
    }

    /**
     * One line per item, and nothing at all on the lines the box did not need.
     *
     * Only the lines this layout declares are written to. `RemoteViews` happens to ignore an
     * identifier the inflated tree does not contain, but relying on that would mean a layout and a
     * renderer disagreeing about how many lines there are and nothing saying so.
     */
    private fun fillRows(
        views: RemoteViews,
        context: Context,
        layout: Layout,
        rows: List<WidgetListRenderModel.Row>,
    ) {
        for (index in 0 until layout.rowBudget) {
            val row = rows.getOrNull(index)
            val nameId = ROW_IDS[index]
            val detailId = ROW_DETAIL_IDS[index]

            views.setViewVisibility(nameId, visibility(row != null))
            views.setTextViewText(nameId, row?.let { WidgetLines.coloured(it.text) } ?: "")

            val detail = row?.detail
            views.setViewVisibility(detailId, visibility(detail != null))
            if (detail == null) {
                views.setTextViewText(detailId, "")
                continue
            }

            views.setTextViewText(detailId, detailText(context, detail))
            views.setTextColor(detailId, colourOf(row.emphasis))
        }
    }

    /**
     * The words for a figure, resolved here rather than carried in the model.
     *
     * The model holds a number and a statement; what they are called is a question for the device's
     * language. The day count is a plural, which no model can decide for every language at once.
     */
    private fun detailText(
        context: Context,
        detail: WidgetListRenderModel.Detail,
    ): String = when (detail) {
        is WidgetListRenderModel.Detail.Days -> context.resources.getQuantityString(
            R.plurals.widget_certificate_days,
            // A plural is chosen by a count, and minus three is not a count. The figure itself is
            // passed through unchanged as the argument; only the choice of wording uses its
            // absolute value, so a figure that has run out reads as a plural rather than as
            // whatever the platform makes of a negative quantity.
            abs(detail.days),
            detail.days,
        )

        WidgetListRenderModel.Detail.Rejected ->
            context.getString(R.string.widget_certificate_rejected)
    }

    /**
     * The empty statement, which is a fact and not an apology for empty space.
     *
     * It is shown for an instance that has been retrieved and has nothing to list. While nothing
     * has been retrieved the state line already says so, and a widget would otherwise claim that
     * no certificate expires soon at a moment when it has not looked.
     */
    private fun fillEmpty(
        views: RemoteViews,
        context: Context,
        model: WidgetListRenderModel,
        visible: Boolean,
    ) {
        views.setViewVisibility(R.id.widget_empty, visibility(visible))
        views.setTextViewText(
            R.id.widget_empty,
            when {
                !visible -> ""
                model.emptyMessageArgument == null -> context.getString(model.emptyMessage)
                else -> context.getString(model.emptyMessage, model.emptyMessageArgument)
            },
        )
    }

    /**
     * The state with its mark, and the time beside it.
     *
     * The state word may be replaced while the data is current — the certificates widget uses that
     * to attribute its figures to the source once rather than in every row. An outdated value, an
     * unreachable instance or a notice keeps its own wording: that is the more urgent thing to say,
     * and it is also the only line on the surface saying it.
     */
    private fun fillBottom(
        views: RemoteViews,
        context: Context,
        model: WidgetListRenderModel,
        presentation: WidgetPresentation,
    ) {
        val label = model.currentStateLabel
            ?.takeIf { presentation.showsData && !presentation.valueIsOutdated }
            ?: presentation.label

        views.setTextViewText(
            R.id.widget_state,
            WidgetLines.marked(presentation.mark, context.getString(label)),
        )
        views.setTextColor(R.id.widget_state, presentation.color.toArgb())

        views.setTextViewText(
            R.id.widget_footer,
            WidgetLines.footer(
                context = context,
                updatedAtEpochMs = model.instance.updatedAtEpochMs,
                valueIsOutdated = presentation.valueIsOutdated,
                // Always, in every box: a list widget has no variant in which the time has a line
                // of its own.
                sharesItsLine = true,
            ),
        )
    }

    /** What colour a figure carries. The name beside it never changes colour; see the model. */
    private fun colourOf(emphasis: WidgetListRenderModel.Emphasis): Int = when (emphasis) {
        WidgetListRenderModel.Emphasis.NONE -> WidgoraColors.OnSurfaceMuted
        WidgetListRenderModel.Emphasis.SOON -> WidgoraColors.StateStale
        WidgetListRenderModel.Emphasis.URGENT -> WidgoraColors.StateError
    }.toArgb()

    private fun visibility(visible: Boolean): Int = if (visible) View.VISIBLE else View.GONE

    /**
     * The identifiers of the item lines, in fill order.
     *
     * The three layouts declare the first two, the first four and all eight of these, in this
     * order, and each is written to only as far as its own budget — a test holds the files to that.
     */
    private val ROW_IDS = listOf(
        R.id.widget_row_0,
        R.id.widget_row_1,
        R.id.widget_row_2,
        R.id.widget_row_3,
        R.id.widget_row_4,
        R.id.widget_row_5,
        R.id.widget_row_6,
        R.id.widget_row_7,
    )

    private val ROW_DETAIL_IDS = listOf(
        R.id.widget_row_0_detail,
        R.id.widget_row_1_detail,
        R.id.widget_row_2_detail,
        R.id.widget_row_3_detail,
        R.id.widget_row_4_detail,
        R.id.widget_row_5_detail,
        R.id.widget_row_6_detail,
        R.id.widget_row_7_detail,
    )
}
