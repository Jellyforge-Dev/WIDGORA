package io.github.jellyforgedev.widgora.widget

/**
 * The widget's own sense of how old data may be.
 *
 * It is deliberately not the dashboard's threshold. The dashboard retrieves every minute while
 * someone is looking at it, so three minutes there means three attempts in a row brought nothing
 * newer. A widget is refreshed every [REFRESH_INTERVAL_MINUTES] minutes, and reusing the
 * dashboard's figure would mark every widget outdated between two ordinary, successful runs.
 *
 * Forty-five minutes is one and a half of that interval, so a single deferred run is ordinary
 * rather than visible, and two in a row are not. A test holds that relationship, because the two
 * figures only mean anything together: raising the interval without raising the threshold would
 * leave every widget reading as outdated most of the time.
 *
 * **What the threshold still cannot do.** A widget's state is decided when an update is pushed,
 * and a widget pushed as current keeps reading as current on its host until something pushes
 * again. The periodic run is what bounds that, and it is the reason the threshold is one and a
 * half runs rather than one.
 */
object WidgetTiming {

    /** How old the data behind a widget may be before the widget says it is outdated. */
    const val STALE_AFTER_MS = 2_700_000L

    /**
     * How often the background refresh runs.
     *
     * Above the platform's floor of fifteen minutes for periodic work, deliberately rather than
     * incidentally: power management defers such work further, and a request sitting exactly on
     * the floor would have every deferral show up as a late run. Thirty minutes leaves room for
     * one deferral inside the staleness threshold above.
     *
     * The widget framework's own periodic update is **not** used for this and stays switched off
     * in the provider's metadata. Its floor is the same thirty minutes, but it wakes a sleeping
     * device in order to run, which on a television means waking up to fetch data nobody is
     * looking at.
     */
    const val REFRESH_INTERVAL_MINUTES = 30L
}
