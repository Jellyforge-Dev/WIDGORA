package io.github.jellyforgedev.widgora.widget

import io.github.jellyforgedev.widgora.core.data.MonitorOverview
import io.github.jellyforgedev.widgora.core.data.MonitorState
import io.github.jellyforgedev.widgora.core.data.WidgetKind
import io.github.jellyforgedev.widgora.core.data.WidgetSettings

/**
 * Proof that the user allowed this widget to name monitors.
 *
 * ### What this replaces, and why the replacement is weaker
 *
 * Monitor names used to be unreachable from anything a foreign application sees, and the reason
 * was a module boundary: `:bridge` depends on `:model` and never on `:core`, so it could not
 * reference the type that holds the names even by mistake. A widget provider lives in the
 * application module and can reach everything, so that boundary does not apply here — while two
 * of the three widget types are meant to show names on purpose.
 *
 * So the guarantee moves from the build to the type system, and that is weaker: a module boundary
 * is checked by the compiler and nothing gets past it, whereas this is checked within one module
 * and a determined author can work around it. SECURITY.md states it as weaker rather than
 * describing it in the language of what it replaced.
 *
 * ### What the token actually achieves
 *
 * Not unforgeability — within a module nothing is unforgeable. What it achieves is that the
 * **only** material a token can be made from is a stored setting that says yes:
 *
 * - the constructor is private, so no call site outside this class can produce one;
 * - the one function that does produce one takes the widget's stored settings and returns `null`
 *   unless [WidgetSettings.showNames] is true.
 *
 * There is therefore no expression anywhere in the application that yields a consent token
 * without a real consent behind it, and adding one would mean editing this file — which is a
 * visible, deliberate change in review rather than a line that slips into a feature.
 */
class NameConsent private constructor() {

    companion object {

        /**
         * The consent a widget's stored settings carry, or `null` where they carry none.
         *
         * Absent settings are a no, not an unknown. A widget with no stored entry has never been
         * asked the question, and the project's rule is that names stay off until the user says
         * otherwise — so there is nothing to distinguish here.
         *
         * ### Why a selection is a consent of its own
         *
         * Two of the three kinds answer the question with a switch, and the user may say no to it.
         * A selection widget cannot: naming the monitors the user picked is the whole of what it
         * does, and the picking is done one monitor at a time on a screen that says so. Asking a
         * second time whether those names may be shown would be asking whether the widget may do
         * the thing it was just configured to do.
         *
         * The property this class exists for is untouched by that. A token can still only be made
         * from something the user stored: either a switch they turned on, or a list of monitors
         * they assembled. An empty list is not a consent — there is nothing in it that anybody
         * chose — and a selection widget with one draws no names, which is also all it could draw.
         */
        fun from(settings: WidgetSettings?): NameConsent? = when {
            settings == null -> null
            settings.showNames -> NameConsent()
            settings.kind == WidgetKind.SELECTION && settings.monitorIds.isNotEmpty() ->
                NameConsent()

            else -> null
        }
    }
}

/**
 * Everything a widget draws **when it may name monitors**, which is a second model and not a flag.
 *
 * ### Why a second type rather than a nullable field on the first
 *
 * [WidgetRenderModel.Instance] has no field that can hold a name and a test freezes its field
 * list. A nullable name field would end that: the field list would allow names for every widget,
 * and what kept them out of a widget without consent would be a condition at one call site. Two
 * types mean the question is settled before the renderer is chosen — and the renderers are two
 * separate functions with no overload taking both, so a nameless model cannot reach the naming
 * one or the reverse.
 *
 * ### Why the names are already cut to size here
 *
 * The same reason the nameless model shortens its title: what reaches the view hierarchy is what
 * crosses into the host's process, so the cut happens before that rather than in the renderer,
 * where a second caller could skip it.
 */
class NamedWidgetRenderModel private constructor(
    /** The same model a widget without consent would get, unchanged. */
    val instance: WidgetRenderModel.Instance,
    /** The names of the affected items, at most [MAX_NAMES], each already shortened. */
    val names: List<String>,
    /** How many affected items had no room for their name. */
    val remaining: Int,
    /**
     * The one affected item a widget with no line for a name can still report, or `null`.
     *
     * `null` whenever nothing is wrong, and whenever what is wrong is an expected condition — see
     * [Headline] for which states qualify and why only those.
     */
    val headline: Headline?,
) {

    /**
     * Deliberately without the names.
     *
     * A monitor name is topology and this project does not log one. A `toString` carrying the
     * names would be exactly the accident that rule exists to prevent — a model written into a
     * log line, a crash report or an assertion message. The count says as much as a diagnosis
     * needs.
     */
    override fun toString(): String = "NamedWidgetRenderModel(instance=$instance, " +
        "names=${names.size}, remaining=$remaining, headline=$headline)"

    /**
     * One affected item, named in the widget's own title line.
     *
     * ### Why the title and not a line of its own
     *
     * In the two smaller boxes there is no line to add — that is the measured finding the third
     * size step came out of, and adding one anyway is what cut the bottom line off. The title is
     * the one line those boxes already have, and in a box that small the instance's own name is
     * the least useful thing on the surface: a user with one instance knows which one it is, and
     * what they cannot know is which monitor is down.
     *
     * So with consent and something actually wrong, the title says what is wrong instead. Without
     * consent, or with nothing wrong, it stays the instance's name — the widget does not change
     * shape for a condition that has not occurred.
     *
     * ### Which states take the title over, and which do not
     *
     * Only [MonitorState.FAILED] and [MonitorState.UNKNOWN]. Both are reasons to go and look:
     * one is a failure and the other may be a failure that is not understood yet. Waiting and
     * maintenance are expected conditions, and a widget that replaces its title because something
     * is deliberately paused is a widget that cries wolf.
     *
     * The decision follows from the first affected item alone, because the application's own
     * urgency order puts those two states first: if the first affected item is neither, there is
     * no failed and no unrecognised item at all.
     *
     * ### What guards it, and what does not
     *
     * Not this constructor. It is visible within the module, because Kotlin does not let an outer
     * class reach a nested class's private constructor and the factory below has to build one.
     * What guards it is that a headline only ever reaches a view through the naming renderer, and
     * that renderer takes this model — which cannot be built without a consent token. A headline
     * made by hand somewhere else is a value nothing draws.
     *
     * @param mark the state's own mark, the same character the counts line and the tile use
     * @param name the item's name, already cut to [MAX_HEADLINE_NAME_CODE_POINTS]
     * @param others how many further affected items there are, which may be 0
     */
    class Headline internal constructor(
        val mark: String,
        val name: String,
        val others: Int,
    ) {

        /** Without the name, for the same reason the model's own textual form is. */
        override fun toString(): String = "Headline(mark=$mark, others=$others)"
    }

    companion object {

        /**
         * How many names a widget shows.
         *
         * The same three the dashboard tile shows. A widget is narrower than a tile, so it is an
         * upper bound taken from the larger surface rather than a figure chosen here; the
         * measurement test is what says whether three of them fit at every font scale.
         */
        const val MAX_NAMES = 3

        /** Long enough to recognise a name, as on the tile, and no longer on a smaller surface. */
        const val MAX_NAME_CODE_POINTS = 20

        /**
         * How much of a name the title line carries.
         *
         * The same cap as the lines below, deliberately, and **not** a width: the title shares its
         * line with the count of the others, and in a 300dp box at an enlarged font scale the name
         * is shortened further by the view. That is why the layout puts the count in a view of its
         * own — it keeps its width while the name gives way, the same arrangement the compact
         * variant already uses for the state and the time. What this cap does is stop a name of a
         * hundred characters from being handed to another process to be thrown away there.
         */
        const val MAX_HEADLINE_NAME_CODE_POINTS = MAX_NAME_CODE_POINTS

        /**
         * The states that take the title line over. See [Headline] for why only these two.
         */
        private val HEADLINE_STATES = setOf(MonitorState.FAILED, MonitorState.UNKNOWN)

        /**
         * The model for a widget that may name monitors.
         *
         * @param consent proof that the user allowed it. It is deliberately not read: there is
         *   nothing in it to read. Its whole purpose is that this function cannot be called
         *   without one, and one cannot be obtained without a stored yes — see [NameConsent].
         * @param instance the nameless model, built exactly as it is for any other widget
         * @param overview the items behind the counts, or `null` while nothing has been retrieved
         */
        fun of(
            consent: NameConsent,
            instance: WidgetRenderModel.Instance,
            overview: MonitorOverview?,
        ): NamedWidgetRenderModel {
            // `consent` is required and not read. Asserting anything about it would be a check
            // the type system has already made: a non-null reference of a type nobody else can
            // construct is the entire statement.
            val affected = overview?.affectedNames(MAX_NAMES, MAX_NAME_CODE_POINTS)
            return NamedWidgetRenderModel(
                instance = instance,
                names = affected?.names.orEmpty(),
                remaining = affected?.remaining ?: 0,
                headline = headlineOf(overview),
            )
        }

        /**
         * The first affected item, where it is one worth taking the title line for.
         *
         * The shortening is not done here. It is asked of the overview, which is the one place in
         * the application that cuts a name — counting code points and never inside a surrogate
         * pair — and a second implementation of that here is how the two would come to disagree.
         * Asking for one name also yields the count of the rest, which is exactly what the title
         * needs beside it.
         */
        private fun headlineOf(overview: MonitorOverview?): Headline? {
            val first = overview?.affected?.firstOrNull() ?: return null
            if (first.state !in HEADLINE_STATES) return null

            val cut = overview.affectedNames(1, MAX_HEADLINE_NAME_CODE_POINTS)
            val name = cut.names.firstOrNull() ?: return null
            return Headline(mark = first.state.symbol, name = name, others = cut.remaining)
        }
    }
}

/**
 * Which of the three models a widget is to be drawn from.
 *
 * One decision point, made where the stored settings are read, so the renderers themselves have
 * nothing to decide. Each variant carries one model, and no variant carries two: a nameless model
 * cannot reach a renderer that draws names, and neither of the naming models can reach the other's.
 */
sealed interface WidgetRender {

    /** No names may appear. The ordinary case, and the case for every widget without consent. */
    data class Nameless(val model: WidgetRenderModel) : WidgetRender

    /** Names may appear, and the model that carries them could only be built with consent. */
    data class Named(val model: NamedWidgetRenderModel) : WidgetRender

    /**
     * A list of named items, which is what the two newer widget kinds are.
     *
     * Its model, like the one above, can only be built with a consent token — and unlike the one
     * above there is no nameless form of such a widget: naming the items is what it is for. A
     * widget of either kind whose settings carry no consent is drawn from [Nameless] instead, with
     * nothing to list.
     */
    data class Listed(val model: WidgetListRenderModel) : WidgetRender
}
