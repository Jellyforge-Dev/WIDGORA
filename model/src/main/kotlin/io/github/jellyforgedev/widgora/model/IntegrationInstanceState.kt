package io.github.jellyforgedev.widgora.model

/**
 * Lifecycle of one integration instance.
 *
 * This is not the same concern as [WidgetDisplayState] and the two are never merged: a
 * configured instance is not automatically visible, and a visible widget can be outdated while
 * its instance is healthy. The state is owned and driven by the data layer; the type and its
 * transition rule live here because they are worth testing without a device.
 */
enum class IntegrationInstanceState {

    /** Credentials and address are stored, but the instance does not retrieve anything. */
    CONFIGURED,

    /** The user has switched the instance on; it participates in refreshes. */
    ENABLED,

    /** A retrieval is currently running. */
    FETCHING,

    /** The last retrieval failed. */
    FAILING,

    ;

    /**
     * Whether moving to [target] is a legal transition.
     *
     * A state onto itself is rejected on purpose: a repeated transition into the state a caller
     * is already in hides a logic error instead of surfacing it.
     */
    fun canTransitionTo(target: IntegrationInstanceState): Boolean = when (this) {
        CONFIGURED -> target == ENABLED
        ENABLED -> target == FETCHING || target == CONFIGURED
        FETCHING -> target == ENABLED || target == FAILING || target == CONFIGURED
        FAILING -> target == FETCHING || target == CONFIGURED
    }

    /**
     * Applies a transition.
     *
     * An illegal transition is a defect in the calling code, not a condition the user can cause,
     * so it fails loudly rather than being silently absorbed.
     */
    fun transitionTo(target: IntegrationInstanceState): IntegrationInstanceState {
        check(canTransitionTo(target)) { "Illegal transition: " + name + " -> " + target.name }
        return target
    }
}
