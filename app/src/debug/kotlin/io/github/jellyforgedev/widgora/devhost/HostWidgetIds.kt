package io.github.jellyforgedev.widgora.devhost

/**
 * What to do with the widget identifier this host was given last time.
 *
 * ### Why an identifier has to be looked after at all
 *
 * A host asks the system for an identifier, and the system keeps that record until the host gives
 * it back. Allocating a fresh one at every start would leave a trail of records nothing points
 * at, each of them a widget the system believes is placed somewhere. The platform notices
 * eventually; the developer notices never, because nothing on the screen changes.
 *
 * So the identifier is stored and reused. That is only safe while it still names the widget this
 * host exists for: the application may have been reinstalled, the binding may have been revoked,
 * or the stored number may be left over from a provider that no longer exists. In each of those
 * the old identifier is released before a new one is asked for.
 *
 * The decision is stated as plain values so that it can be tested without a device, which is the
 * whole reason it is separate from the activity that acts on it.
 */
sealed interface HostWidgetDecision {

    /** The stored identifier still names our widget. Use it again. */
    data class Reuse(val appWidgetId: Int) : HostWidgetDecision

    /** The stored identifier names something else, or nothing. Give it back, then ask anew. */
    data class ReleaseAndAllocate(val staleAppWidgetId: Int) : HostWidgetDecision

    /** Nothing usable was stored. Ask for one. */
    data object Allocate : HostWidgetDecision
}

object HostWidgetIds {

    /**
     * @param storedAppWidgetId what this host wrote down last time, or `null` on a first run
     * @param boundProviderClassName the provider the system says that identifier is bound to, or
     *   `null` when it is bound to nothing — which is what an uninstalled application or a
     *   binding that was never finished looks like
     * @param ourProviderClassNames every provider this host may show, which is every provider of
     *   the application. A list rather than one name because there are three widget types to
     *   come: a stored identifier bound to the second of them is still ours and still reusable,
     *   whereas the previous single-name form would have released it and allocated a new one on
     *   every start.
     */
    fun decide(
        storedAppWidgetId: Int?,
        boundProviderClassName: String?,
        ourProviderClassNames: List<String>,
    ): HostWidgetDecision = when {
        storedAppWidgetId == null -> HostWidgetDecision.Allocate

        // A stored zero is the platform's "no widget" marker rather than a widget numbered zero,
        // and treating it as one would ask the system to give back something it never gave out.
        storedAppWidgetId == NO_APP_WIDGET_ID -> HostWidgetDecision.Allocate

        boundProviderClassName != null && boundProviderClassName in ourProviderClassNames ->
            HostWidgetDecision.Reuse(storedAppWidgetId)

        else -> HostWidgetDecision.ReleaseAndAllocate(storedAppWidgetId)
    }

    /**
     * The value `AppWidgetManager.INVALID_APPWIDGET_ID` carries.
     *
     * Written out rather than read from the platform class, so that this file stays testable
     * without a device. A test asserts that the two agree, which is the only thing a copied
     * constant needs.
     */
    const val NO_APP_WIDGET_ID = 0
}
