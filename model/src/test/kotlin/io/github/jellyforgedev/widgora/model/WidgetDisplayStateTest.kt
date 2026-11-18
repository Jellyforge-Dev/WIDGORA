package io.github.jellyforgedev.widgora.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WidgetDisplayStateTest {

    @Test
    fun errorFallsBackToTheUnknownReason() {
        assertEquals(WidgetDisplayState.Error.Reason.UNKNOWN, WidgetDisplayState.Error().reason)
    }

    @Test
    fun errorKeepsTheGivenReason() {
        val error = WidgetDisplayState.Error(WidgetDisplayState.Error.Reason.UNAUTHORIZED)
        assertEquals(WidgetDisplayState.Error.Reason.UNAUTHORIZED, error.reason)
    }

    @Test
    fun offlineIsNotTheSameAsAnError() {
        assertNotEquals<WidgetDisplayState>(WidgetDisplayState.Offline, WidgetDisplayState.Error())
    }

    @Test
    fun errorsWithDifferentReasonsAreNotEqual() {
        assertNotEquals(
            WidgetDisplayState.Error(WidgetDisplayState.Error.Reason.UNREACHABLE),
            WidgetDisplayState.Error(WidgetDisplayState.Error.Reason.OVERSIZED),
        )
    }

    @Test
    fun everyReasonIsDistinctAndTheSetIsFixed() {
        assertEquals(5, WidgetDisplayState.Error.Reason.entries.size)
    }

    /**
     * The `when` below is exhaustive over the sealed hierarchy. A seventh display state would
     * stop this file from compiling, which is a stronger signal than a failing assertion.
     */
    @Test
    fun exactlySixDisplayStatesExist() {
        val states = listOf<WidgetDisplayState>(
            WidgetDisplayState.Loading,
            WidgetDisplayState.Ok,
            WidgetDisplayState.Empty,
            WidgetDisplayState.Offline,
            WidgetDisplayState.Stale,
            WidgetDisplayState.Error(),
        )
        val names = states.map { state ->
            when (state) {
                WidgetDisplayState.Loading -> "loading"
                WidgetDisplayState.Ok -> "ok"
                WidgetDisplayState.Empty -> "empty"
                WidgetDisplayState.Offline -> "offline"
                WidgetDisplayState.Stale -> "stale"
                is WidgetDisplayState.Error -> "error"
            }
        }
        assertEquals(6, names.toSet().size)
    }
}
