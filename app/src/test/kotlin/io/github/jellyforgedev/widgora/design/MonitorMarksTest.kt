package io.github.jellyforgedev.widgora.design

import io.github.jellyforgedev.widgora.core.data.MonitorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The marks inside a counts line, and the colour each one is given.
 *
 * This is the half of the widget's coloured counts that can be checked without a device: which
 * character at which position gets which colour. That the colours then survive the journey into
 * a host's process is a separate question, and an instrumented test answers it.
 */
class MonitorMarksTest {

    /**
     * Every state's mark is a single character, which is what the lookup relies on.
     *
     * A mark of two characters would drop out of the lookup silently and simply never be
     * coloured. Failing here rather than there is the point of this test.
     */
    @Test
    fun everyStateContributesExactlyOneCharacter() {
        assertEquals(MonitorState.entries.size, MonitorMarks.byChar.size)
        for (state in MonitorState.entries) {
            assertEquals(
                1,
                state.symbol.length,
                "${state.name} has a mark of ${state.symbol.length} characters",
            )
        }
    }

    @Test
    fun eachMarkIsFoundWhereItStandsAndNamedForItsState() {
        val marks = MonitorMarks.inCounts(COUNTS)

        assertEquals(3, marks.size)
        assertEquals(listOf(0, 4, 7), marks.map { it.startIndex })
        assertEquals(listOf(1, 5, 8), marks.map { it.endIndex })
        assertEquals(
            listOf(MonitorState.ACTIVE, MonitorState.FAILED, MonitorState.UNKNOWN),
            marks.map { it.state },
        )
    }

    /**
     * The colour is the one the application already uses, not a second definition.
     *
     * If these ever diverged, the widget would show a state in one colour and the dashboard the
     * same state in another, which reads as two different products.
     */
    @Test
    fun everyMarkCarriesTheStatesOwnDesignToken() {
        for (mark in MonitorMarks.inCounts(ALL_FIVE)) {
            assertEquals(MonitorPresentation.of(mark.state).color, mark.color, mark.state.name)
        }
    }

    @Test
    fun allFiveStatesAreRecognisedInOneLine() {
        val marks = MonitorMarks.inCounts(ALL_FIVE)

        assertEquals(MonitorState.entries.toSet(), marks.map { it.state }.toSet())
    }

    /** No span ever covers a digit, which is how the numbers keep the view's text colour. */
    @Test
    fun nothingButAMarkIsEverCovered() {
        for (line in listOf(COUNTS, ALL_FIVE)) {
            for (mark in MonitorMarks.inCounts(line)) {
                val covered = line.substring(mark.startIndex, mark.endIndex)
                assertEquals(mark.state.symbol, covered)
                assertTrue(covered.none { it.isDigit() || it.isWhitespace() }, covered)
            }
        }
    }

    /** An instance with nothing to count has no marks either. */
    @Test
    fun anEmptyLineHasNoMarks() {
        assertEquals(emptyList(), MonitorMarks.inCounts(""))
    }

    /** Anything that is not one of the five marks is left alone rather than guessed at. */
    @Test
    fun textWithoutMarksIsLeftAlone() {
        assertEquals(emptyList(), MonitorMarks.inCounts("35 up, 2 down"))
    }

    /** Colour alone never carries a state, but it must still distinguish them. */
    @Test
    fun theFiveColoursAreDistinct() {
        val colors = MonitorState.entries.map { MonitorPresentation.of(it).color }

        assertEquals(colors.size, colors.distinct().size, "two states share a colour: $colors")
    }

    private companion object {

        /** The shape of a real line: mark, number, separator. */
        const val COUNTS = "▲35 ▼2 ●1"

        /** Every state at once, which no healthy installation produces but the format allows. */
        const val ALL_FIVE = "▲1 ▼1 ◆1 ■1 ●1"
    }
}
