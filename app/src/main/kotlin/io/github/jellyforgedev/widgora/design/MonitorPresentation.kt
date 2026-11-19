package io.github.jellyforgedev.widgora.design

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.data.MonitorNote
import io.github.jellyforgedev.widgora.core.data.MonitorState

/**
 * How one monitor state is presented: a mark, a colour and two kinds of wording.
 *
 * The mark comes from the state itself, because the same character has to appear in the
 * language-neutral snapshot value that the bridge may carry; see
 * [io.github.jellyforgedev.widgora.core.data.MonitorState]. What this type adds is everything
 * that belongs to the application's own screens.
 *
 * Shape and colour both carry the state, never colour alone. A test holds that: the five marks are
 * distinct, and so are the five colours.
 *
 * The colours are the existing state tokens. Their names record where each colour was first used,
 * not the only place it may be used, and every one of them is already checked for contrast
 * against the backdrop and against a surface.
 *
 * @param word the state in one word, for a detail list
 * @param countPhrase a whole phrase for a number of items in this state, used as the spoken
 *   description of a mark-and-number pair. These are the plural resources the summary already
 *   used, kept rather than duplicated: a screen reader should say "35 up", not "triangle 35".
 */
data class MonitorPresentation(
    val state: MonitorState,
    val color: Color,
    @param:StringRes val word: Int,
    @param:PluralsRes val countPhrase: Int,
) {

    val symbol: String get() = state.symbol

    companion object {

        fun of(state: MonitorState): MonitorPresentation = when (state) {
            MonitorState.ACTIVE -> MonitorPresentation(
                state = state,
                color = WidgoraColors.StateOk,
                word = R.string.monitor_state_active,
                countPhrase = R.plurals.summary_up,
            )

            MonitorState.FAILED -> MonitorPresentation(
                state = state,
                color = WidgoraColors.StateError,
                word = R.string.monitor_state_failed,
                countPhrase = R.plurals.summary_down,
            )

            MonitorState.PENDING -> MonitorPresentation(
                state = state,
                color = WidgoraColors.StateStale,
                word = R.string.monitor_state_pending,
                countPhrase = R.plurals.summary_pending,
            )

            MonitorState.MAINTENANCE -> MonitorPresentation(
                state = state,
                color = WidgoraColors.StateLoading,
                word = R.string.monitor_state_maintenance,
                countPhrase = R.plurals.summary_maintenance,
            )

            MonitorState.UNKNOWN -> MonitorPresentation(
                state = state,
                color = WidgoraColors.StateOffline,
                word = R.string.monitor_state_unknown,
                countPhrase = R.plurals.summary_unknown,
            )
        }

        val All: List<MonitorPresentation> = MonitorState.entries.map { state -> of(state) }
    }
}

/**
 * The wording of a monitor note, which is assembled from two sources rather than one.
 *
 * This object owns only the neutral half: the sentence that says what the matter is in words that
 * name no service. The other half is [MonitorNote.sourceAdvice], a resource belonging to the
 * integration that reported the item, and it is appended after this one. The split is the reason a
 * second integration can report the same condition without inheriting advice about the first.
 *
 * It is a plain function rather than a composable so that the mapping can be exercised in a unit
 * test, and because resolving a resource is the caller's business.
 */
object MonitorNoteText {

    /**
     * An exhaustive `when`, so that a new kind of note has to be given wording here before it
     * compiles, rather than reaching a screen as a blank line.
     */
    @StringRes
    fun reasonOf(kind: MonitorNote.Kind): Int = when (kind) {
        MonitorNote.Kind.CONTRADICTORY_SOURCE_DATA -> R.string.monitor_note_contradictory
    }
}
