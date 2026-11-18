package io.github.jellyforgedev.widgora.model

/**
 * Read-only access to the widget snapshots the application currently holds.
 *
 * It is declared in this module, which has no Android dependency, so that the bridge can consume
 * it without ever depending on the data layer or on an integration. The data layer implements
 * it and the application wires the two together.
 *
 * An implementation returns what it already holds. A call must not perform retrieval over the
 * network: a provider query runs on a binder thread with a caller waiting on it.
 */
interface WidgetSnapshotSource {

    fun all(): List<WidgetSnapshot>

    fun byId(widgetId: WidgetId): WidgetSnapshot?
}
