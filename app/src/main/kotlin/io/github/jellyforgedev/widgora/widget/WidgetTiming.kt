package io.github.jellyforgedev.widgora.widget

/**
 * The widget's own sense of how old data may be.
 *
 * It is deliberately not the dashboard's threshold. The dashboard retrieves every minute while
 * someone is looking at it, so three minutes there means three attempts in a row brought nothing
 * newer. A widget is refreshed far less often — the plan is every thirty minutes — and reusing the
 * dashboard's figure would mark every widget outdated between two ordinary, successful runs.
 *
 * Forty-five minutes is one and a half of that interval, so a single deferred run is ordinary
 * rather than visible, and two in a row are not.
 *
 * **What this constant cannot do yet.** Nothing re-renders a widget on its own in this step: the
 * state is decided when an update is pushed, and a widget pushed as current keeps reading as
 * current on the host until the next push. The threshold therefore applies at push time only. The
 * periodic refresh that makes it apply continuously is a later step.
 */
object WidgetTiming {

    /** How old the data behind a widget may be before the widget says it is outdated. */
    const val STALE_AFTER_MS = 2_700_000L
}
