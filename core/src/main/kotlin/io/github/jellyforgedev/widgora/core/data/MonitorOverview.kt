package io.github.jellyforgedev.widgora.core.data

/**
 * The state of one monitored item, in terms that belong to no particular service.
 *
 * No value here is named after a vendor's vocabulary. An integration maps its own wire states
 * onto these five, which is what keeps the rest of the application free of service-specific
 * conditionals.
 *
 * ### Why a display character lives on a domain enum
 *
 * [symbol] is the one piece of presentation that cannot live in the design system alone. The same
 * mark appears in two places: on the tile, and inside the language-neutral value of a
 * [io.github.jellyforgedev.widgora.model.WidgetSnapshot], which is assembled in this module. Two
 * definitions would be two things to keep in step, and a drift between them would stay invisible
 * until someone compared a tile with a host launcher.
 *
 * ### Why these five characters
 *
 * All five are from the Unicode block Geometric Shapes, which the platform text font covers, so
 * none of them can turn into a missing-glyph box on a television. None is an emoji: an emoji is
 * drawn from a separate font with its own coverage, and it carries a colour of its own that would
 * fight the state colour. The five outlines — triangle up, triangle down, diamond, square, circle
 * — stay apart in shape alone, so the mark still distinguishes the states for someone who cannot
 * tell the colours apart. Diamond and square are the closest pair; both appear only next to a
 * number, and in a detail list next to a word as well.
 */
enum class MonitorState(val symbol: String) {

    /** Working. */
    ACTIVE("▲"),

    /** Not working. */
    FAILED("▼"),

    /** No result yet — a check that has not produced its first verdict. */
    PENDING("◆"),

    /** Deliberately paused by whoever runs the service. */
    MAINTENANCE("■"),

    /** A state the integration did not recognise. It is never treated as working. */
    UNKNOWN("●"),

    ;

    val isActive: Boolean get() = this == ACTIVE

    /** Named even at zero, because "none failed" is information a glance should get. */
    val isAlwaysCounted: Boolean get() = this == ACTIVE || this == FAILED

    companion object {

        /**
         * The states that are not [ACTIVE], most urgent first.
         *
         * A failure outranks everything. An unrecognised state comes next, because it may well be
         * a failure that is not understood yet, and treating it as the least interesting case
         * would be the one mistake with a real cost. Waiting and maintenance are both expected
         * conditions.
         */
        val AFFECTED_ORDER: List<MonitorState> = listOf(FAILED, UNKNOWN, PENDING, MAINTENANCE)

        /**
         * The order the counts are read in, which is deliberately not [AFFECTED_ORDER].
         *
         * A row of counts answers "how is the installation doing", and the two numbers that
         * answer it are the working and the failed one; they lead, the rest follow. A list of
         * names answers "what do I have to look at", which is a question about urgency. The two
         * orders differ because the two questions differ.
         */
        val COUNT_ORDER: List<MonitorState> =
            listOf(ACTIVE, FAILED, PENDING, MAINTENANCE, UNKNOWN)

        /** A detail list: everything needing attention first, the working items afterwards. */
        val DETAIL_ORDER: List<MonitorState> = AFFECTED_ORDER + ACTIVE
    }
}

/**
 * Why a monitored item is in the state it is, in the cases where the state alone does not say.
 *
 * A note is the exception rather than the rule. Most states explain themselves — a failed check is
 * a failed check — and a line of explanation under every item would be noise that teaches the eye
 * to skip explanations. A note exists only where the state would otherwise be a dead end for the
 * person reading it.
 *
 * The division of the wording is the point of the type. [kind] names a condition in terms that
 * belong to no service, and the neutral part of the sentence is resolved from it where the item is
 * drawn. [sourceAdvice] is the one part that may name a product, and it is supplied by the
 * integration that produced the item, because what to do about the condition depends entirely on
 * the service that caused it. An integration with nothing to add leaves it out, and the neutral
 * half still stands on its own.
 *
 * @param sourceAdvice a string resource of the integration's own module, resolved where the note is
 *   drawn. It is an identifier rather than a text because this module holds no wording in any
 *   language, and a text carried here could only be in one.
 */
data class MonitorNote(val kind: Kind, val sourceAdvice: Int? = null) {

    enum class Kind {

        /**
         * The source described this item more than once, and the descriptions disagreed.
         *
         * Nothing is wrong with the monitored item as far as anyone can tell: what is wrong is the
         * answer the source gave about it, and the state shown is unknown because agreeing with
         * one of two conflicting claims would be a guess.
         */
        CONTRADICTORY_SOURCE_DATA,
    }
}

/**
 * One monitored item as the application holds it.
 *
 * **This type never reaches the bridge.** [name] is the reason: a monitor name routinely contains
 * an internal host name, and the bridge carries prepared display values to foreign applications.
 * The separation is structural rather than a matter of care — `:bridge` depends on `:model` and
 * never on this module, so it cannot reach this type even by mistake.
 *
 * The constructor is strict and the factory truncates, for the same reason the snapshot type
 * draws that line: a name comes from a service and is untrusted input to be capped, while an
 * identifier comes from the application's own code, where a bad value is a defect.
 */
data class MonitorEntry(
    val monitorId: String,
    val name: String,
    val state: MonitorState,
    /** The newest measurement, or `null` where the service reported none. */
    val responseTimeMs: Long?,
    /**
     * Why the state is what it is, where that needs saying, or `null` — which is the ordinary
     * case. It is not part of [state] because it explains a state rather than being one: adding a
     * sixth state would change every count, every ordering and the summary the bridge carries.
     */
    val note: MonitorNote? = null,
    /**
     * What the source says about this item's certificate, or `null` where it says nothing at all.
     *
     * Nullable rather than a value with empty fields, and the null case is the common one: most
     * items terminate no TLS. See [CertificateInfo] for why the difference is kept.
     */
    val certificate: CertificateInfo? = null,
) {

    init {
        require(monitorId.isNotBlank()) { "monitorId must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }
        require(name.codePointCount() <= MAX_NAME_CODE_POINTS) { NAME_TOO_LONG }
        require(responseTimeMs == null || responseTimeMs >= 0) {
            "responseTimeMs must not be negative"
        }
    }

    companion object {

        /**
         * A cap on what is kept in memory, not a display width. A response may be a megabyte of
         * text, and a name taken from it should not be able to reach that size.
         */
        const val MAX_NAME_CODE_POINTS = 120

        private const val NAME_TOO_LONG = "name exceeds 120 code points; use truncating()"

        fun truncating(
            monitorId: String,
            name: String,
            state: MonitorState,
            responseTimeMs: Long?,
            note: MonitorNote? = null,
            certificate: CertificateInfo? = null,
        ): MonitorEntry = MonitorEntry(
            monitorId = monitorId,
            name = name.truncateToCodePoints(MAX_NAME_CODE_POINTS),
            state = state,
            responseTimeMs = responseTimeMs,
            note = note,
            certificate = certificate,
        )
    }
}

/**
 * How many monitored items are in each state.
 *
 * A type of its own rather than a map, so that no caller has to decide what a missing key means,
 * and so that the summary format can be exercised with counts no real installation produces.
 */
data class MonitorCounts(
    val active: Int,
    val failed: Int,
    val pending: Int,
    val maintenance: Int,
    val unknown: Int,
) {

    init {
        require(active >= 0 && failed >= 0 && pending >= 0 && maintenance >= 0 && unknown >= 0) {
            "a count must not be negative"
        }
    }

    /** A `Long`, because five counts near the upper end of `Int` would otherwise overflow. */
    val total: Long
        get() = active.toLong() + failed.toLong() + pending.toLong() +
            maintenance.toLong() + unknown.toLong()

    fun of(state: MonitorState): Int = when (state) {
        MonitorState.ACTIVE -> active
        MonitorState.FAILED -> failed
        MonitorState.PENDING -> pending
        MonitorState.MAINTENANCE -> maintenance
        MonitorState.UNKNOWN -> unknown
    }

    /**
     * The counts worth showing, in reading order.
     *
     * Working and failed are always named, even at zero. The other three appear only when they
     * occur, because naming three zeroes buries the two numbers that matter.
     */
    val shown: List<Pair<MonitorState, Int>>
        get() = MonitorState.COUNT_ORDER
            .map { state -> state to of(state) }
            .filter { (state, count) -> state.isAlwaysCounted || count > 0 }

    /**
     * The counts as marks and numbers, with no word in any language.
     *
     * This is what a snapshot carries, because a snapshot may be handed to a host launcher that
     * neither shares WIDGORA's resources nor transports its colours. A mark survives both.
     *
     * No installation can reach the cap of a snapshot value: five parts, each a single-character
     * mark plus at most the ten digits of `Int.MAX_VALUE`, joined by four separators, is
     * [MAX_SUMMARY_LENGTH] characters. That bound is a property of the format and not of any
     * particular installation, which is why a test asserts it against counts no server could
     * report.
     */
    fun neutralSummary(): String {
        // No items at all is not "none failed": it is nothing to report, and a blank value is
        // what makes the widget say so.
        if (total == 0L) return ""
        return shown.joinToString(SEPARATOR) { (state, count) -> state.symbol + count }
    }

    companion object {

        /** A space, not a comma: a comma is punctuation and belongs to a language. */
        const val SEPARATOR = " "

        /** The longest result [neutralSummary] can produce. Asserted by a test. */
        const val MAX_SUMMARY_LENGTH = 59

        fun of(entries: List<MonitorEntry>): MonitorCounts = MonitorCounts(
            active = entries.count { it.state == MonitorState.ACTIVE },
            failed = entries.count { it.state == MonitorState.FAILED },
            pending = entries.count { it.state == MonitorState.PENDING },
            maintenance = entries.count { it.state == MonitorState.MAINTENANCE },
            unknown = entries.count { it.state == MonitorState.UNKNOWN },
        )
    }
}

/** The names a tile had room for, and how many it had to leave out. */
data class AffectedNames(val names: List<String>, val remaining: Int) {

    init {
        require(remaining >= 0) { "remaining must not be negative" }
    }

    val isEmpty: Boolean get() = names.isEmpty() && remaining == 0
}

/**
 * Everything the application knows about one instance's monitored items.
 *
 * Held in memory by [SnapshotRepository], one per instance, and read by the dashboard and the
 * detail screen. It is **not** part of a snapshot and never crosses the bridge; see [MonitorEntry]
 * for why.
 *
 * The derived lists are computed once here rather than at every recomposition, and they sit
 * outside the constructor on purpose, so that two overviews built from the same entries still
 * compare equal.
 */
data class MonitorOverview(val entries: List<MonitorEntry>) {

    val counts: MonitorCounts = MonitorCounts.of(entries)

    /**
     * Everything that is not working, most urgent first.
     *
     * `sortedBy` is a stable sort, so items in the same state keep the order the service listed
     * them in. That matters for a screen a user reads repeatedly: an order that reshuffles on
     * every retrieval is one the eye cannot learn.
     */
    val affected: List<MonitorEntry> = entries
        .filterNot { entry -> entry.state.isActive }
        .sortedBy { entry -> MonitorState.AFFECTED_ORDER.indexOf(entry.state) }

    /** Every item, ordered for a detail list. */
    val inDetailOrder: List<MonitorEntry> = entries
        .sortedBy { entry -> MonitorState.DETAIL_ORDER.indexOf(entry.state) }

    /**
     * The first [limit] affected names, each shortened to [maxNameCodePoints], plus how many were
     * left out.
     *
     * Shortening counts code points and never cuts inside a surrogate pair, so a name ending in a
     * character outside the basic plane cannot be reduced to half of one, which a renderer would
     * show as a replacement box.
     */
    fun affectedNames(limit: Int, maxNameCodePoints: Int): AffectedNames {
        require(limit >= 0) { "limit must not be negative" }
        // One code point has to be left for the ellipsis that says a name was cut.
        require(maxNameCodePoints >= 2) { "maxNameCodePoints must be at least 2" }

        val shown = affected.take(limit).map { entry ->
            entry.name.shortenToCodePoints(maxNameCodePoints)
        }
        return AffectedNames(names = shown, remaining = affected.size - shown.size)
    }

    companion object {

        /** Nothing retrieved, or an installation with no monitored items. */
        val Empty = MonitorOverview(emptyList())
    }
}

private const val ELLIPSIS = "…"

internal fun String.codePointCount(): Int = codePointCount(0, length)

/** Cuts to at most [maxCodePoints] code points, adding nothing. */
internal fun String.truncateToCodePoints(maxCodePoints: Int): String {
    // One code point is at most two chars, so a short string can never exceed the limit and does
    // not need to be walked.
    if (length <= maxCodePoints) return this
    if (codePointCount() <= maxCodePoints) return this
    return substring(0, offsetByCodePoints(0, maxCodePoints))
}

/**
 * Cuts to exactly [maxCodePoints] code points and ends the result with an ellipsis, so that a
 * shortened name reads as shortened rather than as a different, shorter name.
 */
internal fun String.shortenToCodePoints(maxCodePoints: Int): String {
    if (length <= maxCodePoints) return this
    if (codePointCount() <= maxCodePoints) return this
    return truncateToCodePoints(maxCodePoints - 1) + ELLIPSIS
}
