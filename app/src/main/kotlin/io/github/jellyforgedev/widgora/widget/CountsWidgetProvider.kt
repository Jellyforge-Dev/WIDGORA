package io.github.jellyforgedev.widgora.widget

/**
 * The widget showing one instance's counts per state.
 *
 * Everything it does is in [WidgoraWidgetProvider]: nothing about rendering, scheduling or cleanup
 * depends on the kind of widget, because the push reads each placement's own settings and decides
 * there what it is drawing. What makes this class a kind of its own is its metadata — see
 * `res/xml/counts_widget_info.xml` — and the entry for it in [WidgetProviders].
 *
 * The class therefore stays, empty, rather than being replaced by the base class in the manifest.
 * A host stores the provider's class name with every widget it has placed, so the name is part of
 * what is already on people's screens.
 */
class CountsWidgetProvider : WidgoraWidgetProvider()
