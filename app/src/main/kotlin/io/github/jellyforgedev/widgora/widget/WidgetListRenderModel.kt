package io.github.jellyforgedev.widgora.widget

import androidx.annotation.StringRes
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.data.CertificateValidity
import io.github.jellyforgedev.widgora.core.data.MonitorOverview
import io.github.jellyforgedev.widgora.core.data.MonitorState
import io.github.jellyforgedev.widgora.core.data.certificatesWithin
import io.github.jellyforgedev.widgora.core.data.selected

/**
 * Everything the two list widgets draw: a few named items and the statement of how many there are.
 *
 * ### Why a third model rather than a field on one of the first two
 *
 * [WidgetRenderModel.Instance] has no field that can hold a monitor name and a test freezes its
 * field list. [NamedWidgetRenderModel] can hold three, and only with consent. A list widget shows
 * eight of them with a figure beside each, which is a different surface rather than more of the
 * same one — so it is a model of its own, built the same way: the factory takes a [NameConsent],
 * the constructor is private, and there is no expression in the application that yields one of
 * these without a stored yes behind it. See SECURITY.md, "A widget may name a monitor only by
 * consent".
 *
 * ### Why the model carries more rows than any box shows
 *
 * How many lines there is room for is decided when the views are built, because that is where the
 * box is known — [WidgetListViews] holds the three budgets. The model therefore carries as many
 * rows as the largest box could use and says how many items exist in all; the renderer takes what
 * its box holds and puts the remainder in the line beside the title. Deriving the remainder from an
 * already shortened list is how "and 7 more" comes to say the wrong number.
 *
 * ### Why the wording is not in here
 *
 * A row carries a figure and not a sentence. The words around it — a day, two days, the statement
 * that the source will not vouch for a certificate — are resolved from resources where the row is
 * drawn, in the language of the device. A model holding text could only hold it in one language,
 * and this one is built in a widget update that may run while nobody is looking.
 */
class WidgetListRenderModel private constructor(
    /** The same model a status widget would get: the state, the time, and the instance's name. */
    val instance: WidgetRenderModel.Instance,
    /** What the title line says. Already shortened for a widget. */
    val title: String,
    /** The items, at most [MAX_ROWS], each name already shortened. */
    val rows: List<Row>,
    /** How many items there are in all, which may exceed what any box shows. */
    val total: Int,
    /** What to say when there are none. A statement of fact, never an apology for empty space. */
    @param:StringRes val emptyMessage: Int,
    /**
     * The one figure [emptyMessage] takes, or `null` for a message that takes none.
     *
     * The certificates widget's message names the horizon it looked over, and that number lives in
     * this file. A resource with the figure written into its text would be a second copy of it, in
     * every language, and the day the horizon changed they would all be wrong.
     */
    val emptyMessageArgument: Int?,
    /**
     * What the state line says in place of the usual word while the data is current, or `null` to
     * keep the usual one.
     *
     * The certificates widget uses it to attribute every figure above it to the source, once,
     * rather than repeating that attribution in each row — where it would leave no room for a
     * name. It applies only while the data is current: an unreachable instance or an outdated
     * value is the more urgent thing to say, and that wording keeps the line.
     */
    @param:StringRes val currentStateLabel: Int?,
) {

    /**
     * Deliberately without the names, like every other type in this package that holds one.
     *
     * A monitor name is topology and this project does not log one. The count says as much as a
     * diagnosis needs.
     */
    override fun toString(): String = "WidgetListRenderModel(instance=$instance, " +
        "rows=${rows.size}, total=$total)"

    /**
     * One item on one line.
     *
     * @param text the item's name, with its state mark in front of it where it has one. The mark
     *   leads, as it does on every other surface of this application, so the line still says what
     *   kind of item this is for a viewer who cannot tell the colours apart.
     * @param detail the figure that belongs beside the name, or `null` where there is none. It is
     *   drawn in a view of its own, so the name gives way first when the line is too long — a
     *   shortened name is still a name, and half a number is nothing.
     * @param emphasis how urgent the figure is. It decides the figure's colour and nothing else:
     *   the name stays at full contrast, because the name is not the part being graded.
     */
    class Row internal constructor(
        val text: String,
        val detail: Detail?,
        val emphasis: Emphasis,
    ) {

        /** Without the text, for the same reason the model's own textual form is. */
        override fun toString(): String = "Row(detail=$detail, emphasis=$emphasis)"
    }

    /** The figure beside a name, as a value. The words come from resources where it is drawn. */
    sealed interface Detail {

        /**
         * How many days the source says are left. Never clamped: a figure that has run out is
         * still a figure, and zero or a negative number is the one case anybody needs to see.
         */
        data class Days(val days: Int) : Detail

        /** The source would not say the certificate is valid. Not the claim that it has expired. */
        data object Rejected : Detail
    }

    /** How a figure is coloured. Three steps, because a widget is read from three metres away. */
    enum class Emphasis { NONE, SOON, URGENT }

    companion object {

        /**
         * How many rows the model carries at most, which is the largest box's budget.
         *
         * Carrying more would be carrying names no surface can show, and a name that cannot be
         * drawn has no business being handed to another process.
         */
        const val MAX_ROWS = 8

        /**
         * How much of a name a row keeps.
         *
         * Tighter than the dashboard tile's twenty, and the reason is width rather than taste: a
         * row shares its line with a figure that may not be shortened, and at the project's text
         * floor a 300dp box holds about twenty-two characters at an enlarged font scale. Sixteen
         * for the name leaves the figure its room. The measurement test is what holds this.
         */
        const val MAX_NAME_CODE_POINTS = 16

        /** How far ahead the certificates widget looks, in whole days as the source counts them. */
        const val CERTIFICATE_HORIZON_DAYS = 30

        /** At or below this many days the figure is drawn as urgent rather than as a countdown. */
        const val CERTIFICATE_URGENT_DAYS = 7

        /** The source declined to say the certificate is valid. Never read as "expired". */
        private val REJECTED = CertificateValidity.NOT_VALID_ACCORDING_TO_SOURCE

        /**
         * The certificates the source reports as expiring soon, or as ones it will not vouch for.
         *
         * @param consent proof that the user allowed monitor names in this widget. Deliberately
         *   not read: there is nothing in it to read, and its whole purpose is that this function
         *   cannot be called without one — see [NameConsent].
         * @param overview the items behind the counts, or `null` while nothing has been retrieved
         */
        fun certificates(
            consent: NameConsent,
            instance: WidgetRenderModel.Instance,
            overview: MonitorOverview?,
        ): WidgetListRenderModel {
            val watch = overview?.certificatesWithin(
                horizonDays = CERTIFICATE_HORIZON_DAYS,
                limit = MAX_ROWS,
                maxNameCodePoints = MAX_NAME_CODE_POINTS,
            )

            return WidgetListRenderModel(
                instance = instance,
                title = instance.title,
                rows = watch?.items.orEmpty().map { item ->
                    val rejected = item.validity == REJECTED
                    val days = item.daysRemaining
                    Row(
                        // The mark appears only for an item whose own check is not running. A
                        // certificate figure from a paused or failing check is one nobody has
                        // re-tested, and that is the whole of what the mark says here; a mark on
                        // every row would be a column of triangles saying nothing.
                        text = prefixed(item.state.takeIf { state -> !state.isActive }, item.name),
                        // A rejected certificate shows that rather than its figure, even where
                        // the source gave one: the statement outranks the countdown, which is
                        // also why those items are sorted first.
                        detail = if (rejected) Detail.Rejected else days?.let(Detail::Days),
                        emphasis = when {
                            rejected -> Emphasis.URGENT
                            days == null -> Emphasis.NONE
                            days <= CERTIFICATE_URGENT_DAYS -> Emphasis.URGENT
                            else -> Emphasis.SOON
                        },
                    )
                },
                total = watch?.total ?: 0,
                emptyMessage = R.string.widget_certificates_none,
                emptyMessageArgument = CERTIFICATE_HORIZON_DAYS,
                // Every figure above this line is the source's claim and not a measurement of
                // WIDGORA's. Said once here, because a row has no room to say it.
                currentStateLabel = R.string.widget_certificates_source,
            )
        }

        /**
         * The items the user picked, with their state.
         *
         * The title line carries the counts of the whole selection in front of the instance's
         * name. That order is deliberate: the figure is what a glance is for, and the name is what
         * tells two widgets apart once the glance has happened.
         *
         * @param consent the selection itself is the consent, and it is turned into a token where
         *   the settings are read — the same gate every other named surface passes
         */
        fun selection(
            consent: NameConsent,
            instance: WidgetRenderModel.Instance,
            overview: MonitorOverview?,
            monitorIds: List<String>,
        ): WidgetListRenderModel {
            val picked = overview?.selected(
                monitorIds = monitorIds,
                limit = MAX_ROWS,
                maxNameCodePoints = MAX_NAME_CODE_POINTS,
            )
            val counts = picked?.counts?.neutralSummary().orEmpty()

            return WidgetListRenderModel(
                instance = instance,
                title = if (counts.isEmpty()) {
                    instance.title
                } else {
                    counts + TITLE_SEPARATOR + instance.title
                },
                rows = picked?.items.orEmpty().map { item ->
                    Row(
                        text = prefixed(item.state, item.name),
                        detail = null,
                        emphasis = Emphasis.NONE,
                    )
                },
                total = picked?.total ?: 0,
                emptyMessage = R.string.widget_selection_none,
                emptyMessageArgument = null,
                currentStateLabel = null,
            )
        }

        private fun prefixed(state: MonitorState?, name: String): String =
            if (state == null) name else WidgetLines.marked(state.symbol, name)

        /**
         * Between the counts and the instance's name on the title line.
         *
         * An ordinary space, unlike the thin one that ties a mark to its word: these are two
         * separate statements sharing a line, not a mark and the word it belongs to.
         */
        private const val TITLE_SEPARATOR = " "
    }
}
