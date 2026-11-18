package io.github.jellyforgedev.widgora.dashboard

/**
 * The two intervals of the dashboard, in one place.
 *
 * They belong together: retrieving every minute while a threshold of three minutes decides what
 * counts as outdated means a tile reports itself outdated after the third attempt in a row has
 * failed to bring anything newer. Changing one without looking at the other would turn either
 * into nonsense.
 */
object DashboardTiming {

    /** How often the dashboard retrieves while it is in the foreground. */
    const val REFRESH_INTERVAL_MS = 60_000L

    /** How old data may be before a tile reports it as outdated. */
    const val STALE_AFTER_MS = 180_000L
}
