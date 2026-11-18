package io.github.jellyforgedev.widgora.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DisplayStateRuleTest {

    @Test
    fun withoutASnapshotTheWidgetIsLoading() {
        assertEquals(
            WidgetDisplayState.Loading,
            DisplayStateRule.stateFor(snapshot = null, nowEpochMs = NOW, staleAfterMs = MINUTE),
        )
    }

    @Test
    fun freshDataIsOk() {
        assertEquals(WidgetDisplayState.Ok, stateAt(ageMs = 0))
    }

    @Test
    fun dataJustBelowTheThresholdIsStillOk() {
        assertEquals(WidgetDisplayState.Ok, stateAt(ageMs = MINUTE - 1))
    }

    @Test
    fun dataExactlyAtTheThresholdIsStale() {
        assertEquals(WidgetDisplayState.Stale, stateAt(ageMs = MINUTE))
    }

    @Test
    fun dataBeyondTheThresholdIsStale() {
        assertEquals(WidgetDisplayState.Stale, stateAt(ageMs = 10 * MINUTE))
    }

    @Test
    fun aBlankValueIsEmpty() {
        assertEquals(WidgetDisplayState.Empty, stateAt(ageMs = 0, primaryValue = ""))
    }

    @Test
    fun whitespaceOnlyCountsAsEmpty() {
        assertEquals(WidgetDisplayState.Empty, stateAt(ageMs = 0, primaryValue = "   "))
    }

    /** Age outranks emptiness, so an outdated empty value is not reported as current. */
    @Test
    fun anOutdatedBlankValueIsStaleAndNotEmpty() {
        assertEquals(WidgetDisplayState.Stale, stateAt(ageMs = 10 * MINUTE, primaryValue = ""))
    }

    @Test
    fun aTimestampAheadOfTheLocalClockCountsAsFresh() {
        assertEquals(WidgetDisplayState.Ok, stateAt(ageMs = -1))
        assertEquals(WidgetDisplayState.Ok, stateAt(ageMs = -(24 * 60 * MINUTE)))
    }

    @Test
    fun aTimestampAheadOfTheClockIsNotAnError() {
        val state = stateAt(ageMs = -(365 * 24 * 60 * MINUTE))
        assertEquals(WidgetDisplayState.Ok, state)
    }

    @Test
    fun aNonPositiveThresholdIsRejected() {
        assertFailsWith<IllegalArgumentException> { stateAt(ageMs = 0, staleAfterMs = 0) }
        assertFailsWith<IllegalArgumentException> { stateAt(ageMs = 0, staleAfterMs = -1) }
    }

    private fun stateAt(
        ageMs: Long,
        primaryValue: String = "12 up",
        staleAfterMs: Long = MINUTE,
    ): WidgetDisplayState = DisplayStateRule.stateFor(
        snapshot = WidgetSnapshot(
            widgetId = WidgetId("kuma-overall"),
            title = "Uptime Kuma",
            primaryValue = primaryValue,
            updatedAtEpochMs = NOW - ageMs,
            iconToken = "server",
            accentToken = "accent-ok",
        ),
        nowEpochMs = NOW,
        staleAfterMs = staleAfterMs,
    )

    private companion object {
        const val NOW = 1_700_000_000_000
        const val MINUTE = 60_000L
    }
}
