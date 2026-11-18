package io.github.jellyforgedev.widgora.core.integration.kuma

import io.github.jellyforgedev.widgora.model.WidgetDisplayState

/**
 * Outcome of reading one metrics response.
 *
 * A failure carries an existing display reason rather than a second, integration-specific error
 * vocabulary, so that nothing has to translate between the two later on.
 */
sealed interface KumaReadResult {

    data class Success(val monitors: List<KumaMonitor>) : KumaReadResult

    data class Failure(val reason: WidgetDisplayState.Error.Reason) : KumaReadResult

    /**
     * The configured address was refused before anything was sent, because it is unusable or
     * because it is not encrypted.
     *
     * This is not a [Failure]: a display reason answers the question "why does this widget show
     * an error", while this answers "why was nothing even attempted". The settings layer is
     * expected to prevent it, and the wording the user sees belongs there, where the address can
     * be corrected.
     */
    data object AddressRejected : KumaReadResult
}
