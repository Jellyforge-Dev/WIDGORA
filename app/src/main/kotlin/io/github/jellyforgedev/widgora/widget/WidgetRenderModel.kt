package io.github.jellyforgedev.widgora.widget

import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.model.DisplayStateRule
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import io.github.jellyforgedev.widgora.model.WidgetSnapshot

/**
 * Everything a widget draws, and deliberately nothing else.
 *
 * ### Why this type exists at all
 *
 * A widget hands a prepared view hierarchy to another process — a launcher — which can read every
 * string in it. Until now the one boundary that kept monitor names away from a foreign application
 * was structural: the bridge module depends on the domain module and never on the data module, so
 * it could not reference the type that holds the names even by mistake. A widget provider lives in
 * this module and can reach everything, so that boundary does not apply to it.
 *
 * This type is the replacement, and it is weaker than a module boundary, which is worth stating
 * plainly. What it does provide:
 *
 * - **No variant has a field that can hold a monitor name**, not even a nullable one. A name could
 *   only arrive by someone adding a field, which a test refuses.
 * - [Instance.title] is the name the *user* typed for their instance, not a name any service
 *   reported, and [Instance.counts] is marks and digits with no word of any language in it.
 * - The function that builds this model is not given the item list, so it cannot read one.
 */
sealed interface WidgetRenderModel {

    /** Storage was read and holds no instance. Not the same as "nothing retrieved yet". */
    data object NotSetUp : WidgetRenderModel

    /** A stored configuration exists and could not be read. */
    data object ConfigurationUnreadable : WidgetRenderModel

    /**
     * One instance, as a widget shows it.
     *
     * @param title the name the user gave the instance, shortened for a widget
     * @param state what the widget says about the data, already decided with the widget's own
     *   staleness threshold rather than the dashboard's
     * @param counts the summary as marks and digits, empty when there is nothing to count
     * @param updatedAtEpochMs when the data shown was obtained, or `null` when there is none
     */
    data class Instance(
        val title: String,
        val state: WidgetViewState,
        val counts: String,
        val updatedAtEpochMs: Long?,
    ) : WidgetRenderModel
}

/** Builds a [WidgetRenderModel]. Separate from the type so the type stays a plain value. */
object WidgetRenderModels {

    /**
     * How much of an instance name a widget keeps.
     *
     * A widget is smaller than a tile and its title is one line, so the cut is tighter than the
     * 64 characters storage allows. Counting code points rather than chars matters for the same
     * reason it does everywhere else in this project: cutting inside a surrogate pair leaves half
     * a character, which a renderer draws as a replacement box.
     */
    const val MAX_TITLE_CODE_POINTS = 32

    private const val ELLIPSIS = "…"

    /**
     * The model for one instance.
     *
     * The parameters are the three things a widget is allowed to know. The item list is not among
     * them, and that is the point: this function cannot show a monitor name because it is never
     * given one.
     *
     * @param title the instance's display name as the user entered it
     * @param state what the dashboard last determined, which is reinterpreted here for some of its
     *   cases and passed through for the rest
     * @param snapshot the newest snapshot, or `null` if nothing has been retrieved in this process
     * @param nowEpochMs the current time, passed in so the decision stays testable
     */
    fun forInstance(
        title: String,
        state: WidgetViewState,
        snapshot: WidgetSnapshot?,
        nowEpochMs: Long,
    ): WidgetRenderModel.Instance = WidgetRenderModel.Instance(
        title = title.shortenToCodePoints(MAX_TITLE_CODE_POINTS),
        state = stateFor(state, snapshot, nowEpochMs),
        counts = snapshot?.primaryValue.orEmpty(),
        updatedAtEpochMs = snapshot?.updatedAtEpochMs,
    )

    /**
     * The state a widget reports, which is not always the one the dashboard reports.
     *
     * Three groups, and the division is not arbitrary:
     *
     * - A **notice** is a statement about configuration — switched off, credential unusable, entry
     *   invalid. Nothing about it depends on how old any data is, so it passes through unchanged.
     * - **Offline** and **error** are statements about an attempted retrieval. They are facts
     *   about something that happened, and re-deriving them from a timestamp would be inventing a
     *   different claim.
     * - Everything else is derived from the snapshot's age, and that is where the widget's own
     *   threshold applies. The dashboard may call the same snapshot outdated after three minutes
     *   while the widget still calls it current, which is correct rather than a contradiction:
     *   the two surfaces are refreshed at different rates, and "outdated" means "older than this
     *   surface expects".
     */
    private fun stateFor(
        state: WidgetViewState,
        snapshot: WidgetSnapshot?,
        nowEpochMs: Long,
    ): WidgetViewState = when (state) {
        is WidgetViewState.Notice -> state

        is WidgetViewState.Display -> when (state.displayState) {
            WidgetDisplayState.Offline, is WidgetDisplayState.Error -> state

            WidgetDisplayState.Loading,
            WidgetDisplayState.Ok,
            WidgetDisplayState.Empty,
            WidgetDisplayState.Stale,
            -> WidgetViewState.Display(
                DisplayStateRule.stateFor(
                    snapshot = snapshot,
                    nowEpochMs = nowEpochMs,
                    staleAfterMs = WidgetTiming.STALE_AFTER_MS,
                ),
            )
        }
    }

    private fun String.shortenToCodePoints(maxCodePoints: Int): String {
        if (length <= maxCodePoints) return this
        if (codePointCount(0, length) <= maxCodePoints) return this
        return substring(0, offsetByCodePoints(0, maxCodePoints - 1)) + ELLIPSIS
    }
}
