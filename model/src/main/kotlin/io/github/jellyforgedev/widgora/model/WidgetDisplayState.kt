package io.github.jellyforgedev.widgora.model

/**
 * How a widget presents itself. Every widget renders all six of these states.
 *
 * Kept separate from [IntegrationInstanceState] on purpose. The type lives here, the rendering
 * lives in the presentation layer.
 */
sealed interface WidgetDisplayState {

    /** No data yet, and a first retrieval is still expected. */
    data object Loading : WidgetDisplayState

    /** Current data is shown. */
    data object Ok : WidgetDisplayState

    /** The retrieval succeeded and there is genuinely nothing to show. */
    data object Empty : WidgetDisplayState

    /** The service could not be reached at all. */
    data object Offline : WidgetDisplayState

    /** Data is shown, but it is older than the configured threshold. */
    data object Stale : WidgetDisplayState

    /**
     * A failure the user needs to be told about, reduced to a fixed set of causes.
     *
     * The cause is an enum and never text from a server. Server messages routinely contain
     * endpoints and stack traces, and this value is read by the presentation layer and may reach
     * another application through the bridge.
     */
    data class Error(val reason: Reason = Reason.UNKNOWN) : WidgetDisplayState {

        enum class Reason {
            UNAUTHORIZED,
            UNREACHABLE,
            MALFORMED_RESPONSE,
            OVERSIZED,

            /**
             * Fallback for anything a classification does not recognise. It is the default so
             * that an unmapped condition can never silently become a more specific claim.
             */
            UNKNOWN,
        }
    }
}
