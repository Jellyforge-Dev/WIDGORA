package io.github.jellyforgedev.widgora.design

import androidx.compose.ui.graphics.Color
import io.github.jellyforgedev.widgora.core.data.MonitorState

/**
 * Where the state marks sit inside a language-neutral counts line, and what colour each one is.
 *
 * ### Why this is a lookup and not a parser
 *
 * A counts line such as `▲35 ▼2 ●1` is assembled in the data module from marks and digits, and a
 * surface that wants to colour the marks has to know which character means what. This object
 * answers that by looking for the five characters the states themselves declare — a closed set of
 * five — rather than by taking the line apart into marks, numbers and separators.
 *
 * The difference matters. A parser would depend on the separator, on the order of the states and
 * on the digits being digits, and would turn into either an exception or a silently uncoloured
 * line the moment any of that changed. A lookup is indifferent to all of it: whatever else the
 * line contains, each mark in it is found and coloured, and anything that is not a mark is left
 * exactly as it was. The numbers keep the text colour of whatever draws them, because nothing
 * here touches them.
 *
 * The colours are not defined here. They come from [MonitorPresentation], which is what the
 * dashboard and the detail list read, so a mark cannot be green on one surface and grey on
 * another.
 */
object MonitorMarks {

    /**
     * One mark found in a counts line.
     *
     * @param startIndex the index of the mark in the line
     * @param endIndex the index after it, exclusive, so the pair can be used as a span range
     */
    data class Mark(
        val startIndex: Int,
        val endIndex: Int,
        val state: MonitorState,
        val color: Color,
    )

    /** Every mark in [counts], in the order they appear. Anything else is ignored. */
    fun inCounts(counts: String): List<Mark> = counts.mapIndexedNotNull { index, char ->
        byChar[char]?.let { state ->
            Mark(
                startIndex = index,
                endIndex = index + 1,
                state = state,
                color = MonitorPresentation.of(state).color,
            )
        }
    }

    /**
     * The marks by their single character.
     *
     * A state whose mark were more than one character would silently drop out of this map, so a
     * test asserts that none does. Filtering rather than insisting keeps that a failing test
     * instead of an exception while the class is being loaded, which in a widget provider would
     * mean a crash inside another process.
     */
    internal val byChar: Map<Char, MonitorState> = MonitorState.entries
        .filter { state -> state.symbol.length == 1 }
        .associateBy { state -> state.symbol[0] }
}
