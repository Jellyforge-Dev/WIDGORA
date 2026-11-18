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
}
