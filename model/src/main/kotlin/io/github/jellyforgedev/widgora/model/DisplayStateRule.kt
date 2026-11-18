package io.github.jellyforgedev.widgora.model

/**
 * Derives what a widget shows from the snapshot it holds and the current time.
 *
 * [WidgetDisplayState.Error] and [WidgetDisplayState.Offline] are not produced here. They are
 * statements about an attempted retrieval rather than about a snapshot, and the retrieval layer
 * does not exist yet; inventing its result type now would be guesswork. Those two states are
 * therefore still raised by whoever performs the retrieval.
 */
object DisplayStateRule {

    /**
     * @param snapshot the newest snapshot, or `null` if nothing has been retrieved yet
     * @param nowEpochMs the current time, passed in rather than read, so the rule stays pure
     * @param staleAfterMs the age at which data counts as outdated; a caller's setting, with no
     *   default in the domain model, which knows nothing about configuration
     */
    fun stateFor(
        snapshot: WidgetSnapshot?,
        nowEpochMs: Long,
        staleAfterMs: Long,
    ): WidgetDisplayState {
        require(staleAfterMs > 0) { "staleAfterMs must be positive" }
        if (snapshot == null) return WidgetDisplayState.Loading

        // A timestamp ahead of the local clock means the two clocks disagree, not that data
        // arrived from the future. Counting that as age zero keeps a clock skew from presenting
        // itself to the user as a permanent problem.
        val ageMs = (nowEpochMs - snapshot.updatedAtEpochMs).coerceAtLeast(0L)

        return when {
            // Age outranks emptiness: an outdated empty value must not read as a current
            // "nothing to report".
            ageMs >= staleAfterMs -> WidgetDisplayState.Stale
            snapshot.primaryValue.isBlank() -> WidgetDisplayState.Empty
            else -> WidgetDisplayState.Ok
        }
    }
}
