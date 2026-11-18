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

            WidgetDisplayState.Ok -> WidgetPresentation(
                label = R.string.dashboard_state_ok,
                color = WidgoraColors.StateOk,
                mark = "OK",
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
