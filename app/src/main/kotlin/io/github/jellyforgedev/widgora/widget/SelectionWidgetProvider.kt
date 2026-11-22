package io.github.jellyforgedev.widgora.widget

/**
 * The widget listing the monitors the user picked, with the state of each.
 *
 * A class of its own with no code in it, for the same two reasons the other two kinds have one: a
 * host binds a placement to a provider class name, and the metadata declaring the sizes this kind
 * is placed at hangs off this class — see `res/xml/selection_widget_info.xml`. It is declared for a
 * larger default box than the others, because a list the user assembled by hand is one they mean to
 * read.
 */
class SelectionWidgetProvider : WidgoraWidgetProvider()
