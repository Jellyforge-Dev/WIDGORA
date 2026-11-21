package io.github.jellyforgedev.widgora.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The widget showing one instance's counts.
 *
 * ### What it does, and the two things it deliberately does not
 *
 * It **renders**, and it says when the periodic refresh ought to exist. It does not retrieve: no
 * network call happens on any path reachable from here. And it asks for no periodic update from
 * the widget framework — `updatePeriodMillis` is zero in the provider's metadata — because that
 * mechanism wakes a sleeping device in order to run, which on a television means waking up to
 * fetch data nobody is looking at. The retrieving is [WidgetRefreshWorker]'s, and this class only
 * schedules and cancels it.
 *
 * ### Why the work is handed to a coroutine
 *
 * Reading the stored instance list is file access, and a broadcast receiver's main thread is not
 * the place for it. `goAsync` is the platform's answer: it keeps the receiver alive past the
 * return of this method, within a budget of about ten seconds. The timeout below stays well
 * inside that, and the pending result is finished on every path — an unfinished one is an
 * application the system eventually complains about.
 */
class CountsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        render(context)
    }

    /**
     * A resize is a reason to draw again.
     *
     * The content does not depend on the size yet, so this is about the one thing that does: a
     * host that has just given the widget a different box re-reads what it is given, and sending
     * it the current picture is cheaper than reasoning about whether it needed one.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        render(context)
    }

    /**
     * The first widget of this provider now exists, so the periodic refresh should.
     *
     * The number of placed widgets is not consulted here. This callback *is* the system saying
     * one exists, and counting them would be asking the party that just said so to say it again.
     * Queuing is idempotent under its unique name, so a second arrival of this callback — which
     * the platform documents at boot time, for a host that holds an instance — changes nothing.
     */
    override fun onEnabled(context: Context) {
        WidgetRefreshScheduler.schedule(context)
    }

    /**
     * The last widget is gone, so nothing is left to refresh for.
     *
     * The count is consulted here, unlike above, and the asymmetry is deliberate: this path ends
     * in a cancellation, and the one thing worth being sure of before cancelling is that nobody
     * is still looking. If a widget somehow remains, the work is renewed instead of removed.
     *
     * This is not the only route out of the queue. A callback can be missed — the receiver is not
     * running, the broadcast is dropped — so every start of the application applies the same rule
     * to the real count.
     */
    override fun onDisabled(context: Context) {
        WidgetRefreshScheduler.applyToPlacedWidgets(context)
    }

    private fun render(context: Context) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(WORK_BUDGET_MS) { WidgetUpdater.pushFromStorage(context) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {

        /**
         * Comfortably inside the platform's budget for a receiver kept alive with `goAsync`.
         *
         * The work behind it is one small file read, so reaching this timeout means something is
         * wrong rather than slow, and a widget left as it was is a better outcome than a receiver
         * the system kills on its own terms.
         */
        const val WORK_BUDGET_MS = 5_000L
    }
}
