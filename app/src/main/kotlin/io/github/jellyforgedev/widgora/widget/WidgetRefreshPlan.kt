package io.github.jellyforgedev.widgora.widget

/** Whether the periodic refresh belongs in the queue or out of it. */
internal enum class RefreshSchedule { ENQUEUE, CANCEL }

/** What a finished run reports back to the scheduler. */
internal enum class RefreshOutcome { SUCCESS, RETRY }

/**
 * What one run of the refresh actually did, which is not the same as what it found.
 *
 * That distinction is the whole point of the type. An instance that could not be reached, or that
 * rejected the credential, is a **result**: it is pushed to the widget, and the run did its job.
 * Only a run that broke off technically, before anything reached a widget, has anything left to
 * retry.
 */
internal sealed interface RefreshRun {

    /**
     * The retrieval finished and the widgets were pushed, whatever the retrieval found.
     *
     * It deliberately carries no notion of the retrieval having succeeded or failed, so that the
     * decision below cannot come to depend on one.
     */
    data object Pushed : RefreshRun

    /** Nothing was attempted: there is no widget to push to. */
    data object NothingToPushTo : RefreshRun

    /** The run broke off before anything reached a widget. */
    data class BrokeOffBeforePush(val cause: Throwable) : RefreshRun
}

/**
 * The two decisions behind the periodic widget refresh, with no Android type in sight.
 *
 * Both look obvious and both have a wrong answer that would be invisible on a device, which is
 * why they are here rather than written inline at the call site:
 *
 * - Queuing work nobody will look at means retrieving from a server every thirty minutes for
 *   nothing, and failing to cancel it leaves an installation with no widget left doing exactly
 *   that forever.
 * - Reporting a retry for an unreachable server would turn one failed retrieval into a run that
 *   keeps retrying with backoff — the opposite of what was decided. A failure is news and belongs
 *   on the widget, and the next periodic run is the retry.
 */
internal object WidgetRefreshPlan {

    /**
     * Whether the work should be queued, from the number of this application's placed widgets.
     *
     * One widget is enough, and that is the only thing the count is asked. It is deliberately not
     * asked at the moment the system reports the first widget appearing: that callback is the
     * system stating one exists, and asking it again would be asking the same party the same
     * question twice.
     */
    fun scheduleFor(placedWidgets: Int): RefreshSchedule {
        require(placedWidgets >= 0) { "a count of placed widgets cannot be negative" }
        return if (placedWidgets > 0) RefreshSchedule.ENQUEUE else RefreshSchedule.CANCEL
    }

    /**
     * What a run reports, decided from what it did rather than from what it found.
     *
     * A run that found nothing to push to also reports success. There is nothing to retry, and
     * the cancellation that should already have happened is repaired by the next application
     * start, which applies [scheduleFor] to the real count — not by this run, which would mean a
     * worker scheduling itself out of existence from inside its own run.
     */
    fun outcomeOf(run: RefreshRun): RefreshOutcome = when (run) {
        RefreshRun.Pushed, RefreshRun.NothingToPushTo -> RefreshOutcome.SUCCESS
        is RefreshRun.BrokeOffBeforePush -> RefreshOutcome.RETRY
    }
}
