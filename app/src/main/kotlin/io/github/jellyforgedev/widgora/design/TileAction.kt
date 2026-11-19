package io.github.jellyforgedev.widgora.design

import io.github.jellyforgedev.widgora.core.data.WidgetView
import io.github.jellyforgedev.widgora.core.data.WidgetViewState

/**
 * What the centre key does on a tile — and therefore whether the tile can be focused at all.
 *
 * It is a decision rather than a property of the data, which is why it is a function with a test
 * and not a conditional buried in a modifier chain. The rule, in the order it is applied:
 *
 * 1. A tile showing a **notice** opens the settings. All three notices — switched off, credential
 *    unusable, stored entry invalid — are configuration the user has to change, and the settings
 *    screen is the only place to change it. Sending them to the detail screen would offer a list
 *    that is either absent or, at best, left over from before the problem started.
 * 2. Otherwise a tile that **has a list** opens the detail screen. The list may be the current one
 *    or the one kept from the last success; in the second case the detail screen marks it as
 *    outdated, exactly as the tile does.
 * 3. Otherwise **nothing**, and the tile is not focusable. That is the case before the first
 *    retrieval returns, and for an installation that genuinely has no monitors. Letting the focus
 *    land there would offer a key press whose only result is a screen saying "nothing retrieved
 *    yet" — a dead end the viewer then has to back out of.
 *
 * Point 3 is why this is not simply "is there a notice". A tile is skipped by the focus rather
 * than being focusable-but-inert, because an element that takes the focus and then does nothing
 * reads as a fault in the application.
 */
enum class TileAction {

    /** Open the list of individual items. */
    OPEN_DETAILS,

    /** Open the settings, because what is wrong is configuration. */
    OPEN_SETTINGS,

    /** Nothing to open, so nothing to focus. */
    NONE,
    ;

    val isFocusable: Boolean get() = this != NONE

    companion object {

        fun of(view: WidgetView): TileAction = when {
            view.state is WidgetViewState.Notice -> OPEN_SETTINGS
            view.overview?.entries?.isNotEmpty() == true -> OPEN_DETAILS
            else -> NONE
        }
    }
}
