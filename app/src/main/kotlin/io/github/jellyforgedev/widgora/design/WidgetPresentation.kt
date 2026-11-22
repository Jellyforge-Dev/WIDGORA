package io.github.jellyforgedev.widgora.design

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.data.InstanceNotice
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason

/**
 * How one state is presented: a word, a colour and a mark.
 *
 * All three are always present, and the colour is never the only difference. Someone who cannot
 * tell the colours apart still reads the label, and the mark gives a third channel. That is why
 * this is a value object rather than a colour lookup — it can be checked by a test, and no
 * screen can forget the label.
 *
 * ### What this line is about, and what it is not
 *
 * Every value here describes the **retrieved data** — its age, or the reason there is none. It
 * never describes the monitored services; that is what the counts and the detail list are for.
 * The distinction used to be invisible: a successful, fresh retrieval showed a green "OK Current"
 * directly beside `▼2`, and a line reading "OK" in the colour of a working monitor is read as a
 * verdict on those monitors. Three things follow, and a test holds each of them:
 *
 * - every label names its subject — "Data current", not "Current"; see `strings.xml`;
 * - no mark is a tick or anything else meaning "all good", and no mark of this line is one of the
 *   five monitor marks `▲ ▼ ◆ ■ ●`;
 * - no state of this line uses [WidgoraColors.StateOk]. Green belongs to a working monitor.
 *
 * @param label the localised word for the state
 * @param color the colour of the mark, already checked for contrast; see [WidgoraColors]
 * @param mark one or two characters shown next to the label, distinct per state
 * @param showsData whether a value may be shown alongside, which is false while there is none
 * @param valueIsOutdated whether a value shown alongside must read as no longer current
 */
data class WidgetPresentation(
    @param:StringRes val label: Int,
    val color: Color,
    val mark: String,
    val showsData: Boolean,
    val valueIsOutdated: Boolean,
) {

    companion object {

        /**
         * Nothing is configured: the mark says "add one", which is the only thing to do about it.
         *
         * This and [MARK_UNREADABLE] belong to render models that only a widget has, and they
         * live here rather than in the widget because the state line's set of marks has to be
         * checkable in one place — a mark colliding with a monitor mark is the kind of thing that
         * is obvious in a list and invisible in two files.
         */
        const val MARK_NOT_SET_UP = "+"

        /** The same mark the dashboard uses for a fault the user has to act on. */
        const val MARK_UNREADABLE = "!!"

        /**
         * The widget points at an instance that is gone: the mark asks rather than blames.
         *
         * Distinct from [MARK_NOT_SET_UP], because the two situations call for different things.
         * "Add one" is wrong here — there may be several instances, and the one this widget was
         * configured for is not among them. What is needed is to configure this widget again.
         */
        const val MARK_INSTANCE_GONE = "?"

        /**
         * The marks are deliberately plain characters rather than icons: the project adds no
         * icon library, and a glyph from the text font cannot go missing on a television.
         */
        fun of(state: WidgetViewState): WidgetPresentation = when (state) {
            is WidgetViewState.Display -> ofDisplayState(state.displayState)
            is WidgetViewState.Notice -> ofNotice(state.notice)
        }

        private fun ofDisplayState(state: WidgetDisplayState): WidgetPresentation = when (state) {
            WidgetDisplayState.Loading -> WidgetPresentation(
                label = R.string.dashboard_state_loading,
                color = WidgoraColors.StateLoading,
                mark = "…",
                showsData = false,
                valueIsOutdated = false,
            )

            // "=" is the one mark of this set that had to be argued for rather than picked.
            // It carries no verdict — it says the shown figures are what the source last
            // reported — and it is structurally unlike all five monitor marks, which are closed
            // outlines from Geometric Shapes. It is plain ASCII, so no television font can turn
            // it into a missing-glyph box, and it is not a tick. The one near pair left is "="
            // against the empty state's "–"; the two never appear together, because they are
            // states of the same single line, and the word beside the mark says which one it is.
            //
            // The colour is the muted foreground, the same one the timestamp underneath uses.
            // A successful, fresh retrieval is a quiet fact, not an achievement, and green here
            // claimed something about the monitors that this line does not know.
            WidgetDisplayState.Ok -> WidgetPresentation(
                label = R.string.dashboard_state_ok,
                color = WidgoraColors.OnSurfaceMuted,
                mark = "=",
                showsData = true,
                valueIsOutdated = false,
            )

            WidgetDisplayState.Empty -> WidgetPresentation(
                label = R.string.dashboard_state_empty,
                color = WidgoraColors.StateEmpty,
                mark = "–",
                showsData = true,
                valueIsOutdated = false,
            )

            WidgetDisplayState.Stale -> WidgetPresentation(
                label = R.string.dashboard_state_stale,
                color = WidgoraColors.StateStale,
                mark = "!",
                showsData = true,
                valueIsOutdated = true,
            )

            WidgetDisplayState.Offline -> WidgetPresentation(
                label = R.string.dashboard_state_offline,
                color = WidgoraColors.StateOffline,
                mark = "×",
                showsData = true,
                valueIsOutdated = true,
            )

            is WidgetDisplayState.Error -> WidgetPresentation(
                label = errorLabel(state.reason),
                color = WidgoraColors.StateError,
                mark = "!!",
                showsData = true,
                // A value next to an error is always from an earlier, successful retrieval.
                valueIsOutdated = true,
            )
        }

        private fun errorLabel(reason: Reason): Int = when (reason) {
            Reason.UNAUTHORIZED -> R.string.dashboard_state_error_unauthorized
            Reason.ENDPOINT_NOT_FOUND -> R.string.dashboard_state_error_endpoint_not_found
            Reason.UNREACHABLE -> R.string.dashboard_state_offline
            Reason.MALFORMED_RESPONSE -> R.string.dashboard_state_error_malformed
            Reason.OVERSIZED -> R.string.dashboard_state_error_oversized
            Reason.UNKNOWN -> R.string.dashboard_state_error_unknown
        }

        private fun ofNotice(notice: InstanceNotice): WidgetPresentation = when (notice) {
            InstanceNotice.DISABLED -> WidgetPresentation(
                label = R.string.dashboard_notice_disabled,
                color = WidgoraColors.StateOffline,
                mark = "–",
                showsData = false,
                valueIsOutdated = false,
            )

            InstanceNotice.SECRET_UNUSABLE -> WidgetPresentation(
                label = R.string.dashboard_notice_secret_unusable,
                color = WidgoraColors.StateError,
                mark = "!!",
                showsData = false,
                valueIsOutdated = false,
            )

            InstanceNotice.ENTRY_INVALID -> WidgetPresentation(
                label = R.string.dashboard_notice_entry_invalid,
                color = WidgoraColors.StateError,
                mark = "!!",
                showsData = false,
                valueIsOutdated = false,
            )
        }
    }
}
