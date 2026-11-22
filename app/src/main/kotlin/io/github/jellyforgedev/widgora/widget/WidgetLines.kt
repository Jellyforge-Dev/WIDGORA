package io.github.jellyforgedev.widgora.widget

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import androidx.compose.ui.graphics.toArgb
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.design.MonitorMarks
import java.util.Date

/**
 * The lines every widget has, whatever kind it is.
 *
 * Two of the three widget kinds draw a list and one draws a counted summary, but all three say
 * when the data was obtained, all three put a mark in front of a state word, and all three colour
 * the marks the application's own way. This is the one implementation of those three things.
 *
 * It exists because there is now more than one renderer. A second copy of the wording rules would
 * be two places to keep in step, and the way that shows itself is a widget calling a value
 * outdated while the one beside it does not.
 */
internal object WidgetLines {

    /**
     * A thin space: it separates a mark from a word without reading as punctuation.
     *
     * Written as an escape rather than as the character itself. A thin space is invisible in a
     * diff and in most editors, and the one thing worse than a separator nobody can see is one
     * that was silently replaced by an ordinary space.
     */
    const val MARK_SEPARATOR = " "

    /**
     * The bottom line: when the data was obtained, or the statement that there is none.
     *
     * "No data yet" is not a placeholder standing in for a value — it is the true state of a widget
     * whose application has not retrieved anything since the process it belongs to was last
     * started. Everything a widget shows lives in memory, so that is the ordinary condition after
     * the system has reclaimed the application.
     *
     * @param sharesItsLine whether the time sits beside something else. On a shared line the long
     *   sentence would be cut off and the one part that has to survive is the time; the state
     *   beside it already says whether the value is current, so the sentence was saying it twice.
     */
    fun footer(
        context: Context,
        updatedAtEpochMs: Long?,
        valueIsOutdated: Boolean,
        sharesItsLine: Boolean,
    ): String {
        val updatedAt = updatedAtEpochMs
            ?: return context.getString(R.string.dashboard_no_data_yet)
        val time = DateFormat.getTimeFormat(context).format(Date(updatedAt))

        if (sharesItsLine) return context.getString(R.string.widget_updated_compact, time)

        val template = if (valueIsOutdated) {
            R.string.dashboard_last_updated_outdated
        } else {
            R.string.dashboard_last_updated
        }
        return context.getString(template, time)
    }

    /**
     * Mark and word on one line, and never the word alone.
     *
     * The mark is the second channel that keeps the states apart for someone who cannot tell the
     * colours apart, which is why it leads and why nothing is drawn without it.
     */
    fun marked(mark: String, label: String): String = mark + MARK_SEPARATOR + label

    /**
     * A line with each monitor mark in the colour of the state it stands for.
     *
     * A mark in the application is green, red, amber, blue or grey; in a widget the text colour is
     * fixed in the layout, so without this the marks would all be white. The shape already
     * distinguishes the five states, so this is not what makes them distinguishable — it is what
     * makes the widget look like the same product as the screen beside it.
     *
     * Only the marks are spanned. Everything else keeps the text colour of the view, so the figure
     * or the name a reader is actually after stays at full contrast against the surface rather than
     * inheriting a state colour.
     *
     * [ForegroundColorSpan] is a `ParcelableSpan`, which is what lets it survive the journey into
     * the host's process: a `RemoteViews` object is parcelled, and only spans the platform knows
     * how to write are carried across. An instrumented test parcels and unparcels the views and
     * reads the spans back out, rather than taking that for granted.
     */
    fun coloured(line: String): CharSequence {
        val spannable = SpannableString(line)
        for (mark in MonitorMarks.inCounts(line)) {
            spannable.setSpan(
                ForegroundColorSpan(mark.color.toArgb()),
                mark.startIndex,
                mark.endIndex,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
            )
        }
        return spannable
    }
}
