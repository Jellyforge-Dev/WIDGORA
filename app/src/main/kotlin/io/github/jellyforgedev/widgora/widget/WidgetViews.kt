package io.github.jellyforgedev.widgora.widget

import android.content.Context
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.design.MonitorMarks
import io.github.jellyforgedev.widgora.design.WidgetPresentation
import io.github.jellyforgedev.widgora.design.WidgoraColors
import java.util.Date

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
        val compact = build(context, appWidgetId, model, Layout.COMPACT)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return compact

        return RemoteViews(
            mapOf(
                SizeF(
                    WidgetSizes.MIN_WIDTH_DP.toFloat(),
                    WidgetSizes.MIN_HEIGHT_DP.toFloat(),
                ) to compact,
                SizeF(
                    WidgetSizes.MIN_WIDTH_DP.toFloat(),
                    WidgetSizes.FULL_HEIGHT_DP.toFloat(),
                ) to build(context, appWidgetId, model, Layout.FULL),
            ),
        )
    }

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
                mark = MARK_NOT_SET_UP,
                label = context.getString(R.string.widget_not_set_up),
                stateColor = WidgoraColors.StateEmpty.toArgb(),
                footer = null,
            )

            WidgetRenderModel.ConfigurationUnreadable -> views.fill(
                title = context.getString(R.string.app_name),
                counts = null,
                mark = MARK_UNREADABLE,
                label = context.getString(R.string.widget_config_unreadable),
                stateColor = WidgoraColors.StateError.toArgb(),
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
    ): String {
        val updatedAt = model.updatedAtEpochMs
            ?: return context.getString(R.string.dashboard_no_data_yet)
        val time = DateFormat.getTimeFormat(context).format(Date(updatedAt))

        // On a shared line the long sentence would be cut off, and the one part of it that has
        // to survive is the time. The state beside it already says whether the value is current,
        // so the sentence was saying it twice.
        if (layout == Layout.COMPACT) {
            return context.getString(R.string.widget_updated_compact, time)
        }

        val template = if (presentation.valueIsOutdated) {
            R.string.dashboard_last_updated_outdated
        } else {
            R.string.dashboard_last_updated
        }
        return context.getString(template, time)
    }

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
     * [ForegroundColorSpan] is a `ParcelableSpan`, which is what lets it survive the journey into
     * the host's process: a `RemoteViews` object is parcelled, and only spans the platform knows
     * how to write are carried across. An instrumented test parcels and unparcels the views and
     * reads the spans back out, rather than taking that for granted.
     */
    private fun coloured(counts: String): CharSequence {
        val spannable = SpannableString(counts)
        for (mark in MonitorMarks.inCounts(counts)) {
            spannable.setSpan(
                ForegroundColorSpan(mark.color.toArgb()),
                mark.startIndex,
                mark.endIndex,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
            )
        }
        return spannable
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

        setViewVisibility(R.id.widget_counts, if (counts == null) View.GONE else View.VISIBLE)
        setTextViewText(R.id.widget_counts, counts ?: "")

        // Mark and word on one line, and never the word alone: the mark is the second channel
        // that keeps the states apart for someone who cannot tell the colours apart.
        setTextViewText(R.id.widget_state, mark + MARK_SEPARATOR + label)
        setTextColor(R.id.widget_state, stateColor)

        setViewVisibility(R.id.widget_footer, if (footer == null) View.GONE else View.VISIBLE)
        setTextViewText(R.id.widget_footer, footer.orEmpty())
    }

    /** Nothing is configured: the mark says "add one", which is the only thing to do about it. */
    private const val MARK_NOT_SET_UP = "+"

    /** The same mark the dashboard uses for a fault the user has to act on. */
    private const val MARK_UNREADABLE = "!!"

    /** A thin space: it separates the mark from the word without reading as punctuation. */
    private const val MARK_SEPARATOR = " "
}
