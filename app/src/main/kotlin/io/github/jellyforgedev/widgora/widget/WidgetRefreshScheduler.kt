package io.github.jellyforgedev.widgora.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Puts the periodic refresh in the queue and takes it out again.
 *
 * ### One request, one name
 *
 * The work is enqueued under a unique name with [ExistingPeriodicWorkPolicy.KEEP], so asking for
 * it a second time changes nothing. That matters more than it looks: the request is renewed from
 * several directions — the first widget appearing, and every start of the application — and the
 * replacing policy would restart the interval each time, so an installation whose application is
 * opened every twenty minutes would never reach a periodic run at all.
 *
 * ### What is in the request, and what deliberately is not
 *
 * No input data. The worker is told nothing; it reads the instances the application has already
 * stored. WorkManager keeps requests in a database of its own, and an address or a credential
 * placed in input data would be a second copy of exactly the material SECURITY.md keeps in one
 * place. Beyond its interval the request carries one network constraint and nothing else.
 *
 * No expedited request and no foreground service either. A widget half an hour out of date is not
 * worth a notification, and asking for a foreground service would add a permission to the
 * manifest for the sake of one line of text.
 */
internal object WidgetRefreshScheduler {

    /**
     * The name the work is queued under.
     *
     * A constant rather than a derived string because it has to survive an application update:
     * afterwards the work already in the database is found by this name, and a name that had
     * changed would leave the old one running unnoticed beside the new one.
     */
    const val WORK_NAME = "widget-refresh"

    /**
     * Queues the work without asking how many widgets there are.
     *
     * This is the path for the system reporting that a first widget now exists. Counting them at
     * that moment would be asking the party that just said so to say it again.
     */
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request(),
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Brings the queue in line with the widgets that are actually placed.
     *
     * Used where the number is the only evidence available: a start of the application, and the
     * system reporting that the last widget is gone. It is also the repair for every way the two
     * can drift apart — an update installed over a version that queued nothing, work lost with
     * the application's data, or a cancellation that never arrived.
     */
    fun applyToPlacedWidgets(context: Context) {
        when (WidgetRefreshPlan.scheduleFor(WidgetUpdater.widgetIds(context).size)) {
            RefreshSchedule.ENQUEUE -> schedule(context)
            RefreshSchedule.CANCEL -> cancel(context)
        }
    }

    /**
     * The request: an interval, a network and nothing else.
     *
     * The network constraint is not an optimisation. Without it a run on a television with no
     * connection would spend its slot discovering that, and would push an unreachable instance to
     * every widget — which each widget would then show until the next run half an hour later.
     */
    private fun request(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<WidgetRefreshWorker>(
            WidgetTiming.REFRESH_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        ).build()
}
