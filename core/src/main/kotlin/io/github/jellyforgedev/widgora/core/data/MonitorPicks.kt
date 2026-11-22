package io.github.jellyforgedev.widgora.core.data

/**
 * The two selections a list surface makes out of an overview, and nothing else.
 *
 * ### Why these live beside [MonitorOverview] rather than in the application module
 *
 * Both are decisions about data — which items qualify, in what order, how many — and neither needs
 * Android to be exercised. That is the project's own division: the part worth testing is put where
 * a unit test reaches it, and the application module is left with drawing.
 *
 * ### Why the shortening happens here
 *
 * [MonitorOverview.affectedNames] already cuts names for the dashboard tile, counting code points
 * and never inside a surrogate pair. A second implementation of that in the widget module is how
 * the two would come to disagree, so these functions do the cutting too and hand out names that
 * are already short enough for the surface asking.
 *
 * ### Why a total is returned beside the items
 *
 * A widget's line budget depends on the box the host gives it, which is decided when the views are
 * built and not here. So these return the items a caller could possibly show, capped, together
 * with how many there are in all; the caller shows as many as its box holds and says how many it
 * left out. Without the total, "and 7 more" would have to be derived from a list that had already
 * been cut.
 */

/**
 * One item whose certificate the source has something to say about.
 *
 * Every field is the source's claim rather than this application's. [validity] carries the wording
 * discipline — see [CertificateValidity], which exists because a boolean invites the one claim that
 * must not be made — and [daysRemaining] is passed through unclamped: a figure that has run out is
 * still a figure, and a zero or a negative number is the single case anybody needs to see.
 *
 * @param state the item's own monitor state, because a certificate figure from a check that is not
 *   running is a figure nobody has re-tested. Whatever shows one is obliged to show this beside it.
 */
data class CertificateItem(
    val monitorId: String,
    val name: String,
    val state: MonitorState,
    val validity: CertificateValidity,
    val daysRemaining: Int?,
)

/**
 * The certificates worth showing, soonest first, and how many there are in all.
 *
 * @param items at most the limit asked for, each name already shortened
 * @param total how many items qualified, which may exceed [items]
 */
data class CertificateWatch(val items: List<CertificateItem>, val total: Int) {

    init {
        require(total >= items.size) { "the total cannot be smaller than the items shown" }
    }

    val isEmpty: Boolean get() = total == 0
}

/** One item the user picked, as a list shows it. */
data class SelectedMonitor(
    val monitorId: String,
    val name: String,
    val state: MonitorState,
)

/**
 * The picked items that still exist, in the order the user put them in.
 *
 * @param items at most the limit asked for, each name already shortened
 * @param total how many of the picked items were found, which is not how many were picked: an item
 *   the source no longer lists is skipped silently, because a widget cannot repair it and a line
 *   reading "one monitor is gone" would be a reproach rather than information
 * @param counts the states of every found item, not only of those shown — the figure a list puts
 *   at the top has to describe the whole selection or it describes nothing
 */
data class MonitorSelection(
    val items: List<SelectedMonitor>,
    val total: Int,
    val counts: MonitorCounts,
) {

    init {
        require(total >= items.size) { "the total cannot be smaller than the items shown" }
    }

    val isEmpty: Boolean get() = total == 0
}

/**
 * The items whose certificate the source reports as expiring within [horizonDays], or as not valid.
 *
 * ### What qualifies
 *
 * An item qualifies when the source says its certificate is not valid, **or** when the source
 * states a number of days at or below the horizon. An item with no certificate row at all never
 * qualifies: the source said nothing about it, and a list of certificates is no place to guess.
 *
 * A stated validity of unknown is not on its own a reason to appear. The source either gave a
 * figure — in which case the figure decides — or it gave neither a figure nor a yes, which is the
 * case the no-statement value stands for and is already covered by the first condition.
 *
 * ### The order
 *
 * Items the source will not vouch for come first, then the remaining days ascending. Both parts
 * are deliberate: a certificate the source rejects is a present condition rather than a countdown,
 * so no number of days outranks it, and within each group the stable sort keeps the order the
 * source listed the items in — a list that reshuffles between retrievals is one the eye cannot
 * learn. An item without a figure sorts last within its group, because nothing is known about when
 * it runs out.
 *
 * @param horizonDays how far ahead to look, in whole days as the source counts them
 * @param limit how many items the caller could show at most
 * @param maxNameCodePoints how much of a name the caller's surface holds
 */
fun MonitorOverview.certificatesWithin(
    horizonDays: Int,
    limit: Int,
    maxNameCodePoints: Int,
): CertificateWatch {
    require(horizonDays >= 0) { "the horizon must not be negative" }
    require(limit >= 0) { "limit must not be negative" }
    // One code point has to be left for the ellipsis that says a name was cut.
    require(maxNameCodePoints >= 2) { "maxNameCodePoints must be at least 2" }

    val qualifying = entries
        .filter { entry -> entry.certificate?.qualifiesWithin(horizonDays) == true }
        .sortedWith(
            compareBy(
                { entry -> if (entry.certificate?.isRejected == true) 0 else 1 },
                { entry -> entry.certificate?.daysRemaining ?: Int.MAX_VALUE },
            ),
        )

    return CertificateWatch(
        items = qualifying.take(limit).map { entry ->
            val certificate = requireNotNull(entry.certificate) { "a filtered item lost its row" }
            CertificateItem(
                monitorId = entry.monitorId,
                name = entry.name.shortenToCodePoints(maxNameCodePoints),
                state = entry.state,
                validity = certificate.validity,
                daysRemaining = certificate.daysRemaining,
            )
        },
        total = qualifying.size,
    )
}

/**
 * The picked items that the source still lists, in the order they were picked.
 *
 * An identifier the source no longer lists is skipped without a word. The alternative — a line
 * saying an item is missing — spends a line of a very small surface on something the surface cannot
 * do anything about, and a monitor removed on purpose would make every widget that named it look
 * broken. The count of what is shown is the honest statement, and it is in [MonitorSelection.total].
 *
 * A duplicate identifier is honoured once. Storage refuses nothing about duplicates, and showing
 * one item twice would both waste a line and make the count disagree with the list.
 */
fun MonitorOverview.selected(
    monitorIds: List<String>,
    limit: Int,
    maxNameCodePoints: Int,
): MonitorSelection {
    require(limit >= 0) { "limit must not be negative" }
    require(maxNameCodePoints >= 2) { "maxNameCodePoints must be at least 2" }

    val byId = entries.associateBy { entry -> entry.monitorId }
    val found = monitorIds.distinct().mapNotNull { monitorId -> byId[monitorId] }

    return MonitorSelection(
        items = found.take(limit).map { entry ->
            SelectedMonitor(
                monitorId = entry.monitorId,
                name = entry.name.shortenToCodePoints(maxNameCodePoints),
                state = entry.state,
            )
        },
        total = found.size,
        counts = MonitorCounts.of(found),
    )
}

/** Whether the source's statement about this certificate is one a watch list should carry. */
private fun CertificateInfo.qualifiesWithin(horizonDays: Int): Boolean =
    isRejected || (daysRemaining != null && daysRemaining <= horizonDays)

/** Whether the source declined to say the certificate is valid. */
private val CertificateInfo.isRejected: Boolean
    get() = validity == CertificateValidity.NOT_VALID_ACCORDING_TO_SOURCE
