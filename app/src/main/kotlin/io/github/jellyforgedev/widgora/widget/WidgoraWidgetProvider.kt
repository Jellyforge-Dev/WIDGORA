package io.github.jellyforgedev.widgora.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import io.github.jellyforgedev.widgora.WidgoraApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What every WIDGORA widget provider does, which is the same thing three times over.
 *
 * ### Why a base class rather than three copies
 *
 * Nothing in any of these callbacks depends on the kind of widget. The push reads the stored
 * settings and decides per placement what it is looking at — see [WidgetModelSelection] — so a
 * provider's whole job is to say "something happened, draw again" and to keep the periodic refresh
 * in step with whether any widget exists at all.
 *
 * Three copies of that would be three places for a cleanup to be forgotten in. The one that
 * matters is [onDeleted]: a provider that failed to remove its settings would leave an entry for a
 * widget nobody can see, and the sweep that catches it runs on the push rather than here.
 *
 * ### What it does, and the two things it deliberately does not
 *
 * It **renders**, and it says when the periodic refresh ought to exist. It does not retrieve: no
 * network call happens on any path reachable from here. And it asks for no periodic update from the
 * widget framework — `updatePeriodMillis` is zero in every provider's metadata — because that
 * mechanism wakes a sleeping device in order to run, which on a television means waking up to fetch
 * data nobody is looking at. The retrieving is [WidgetRefreshWorker]'s, and these classes only
 * schedule and cancel it.
 *
 * ### Why the work is handed to a coroutine
 *
 * Reading the stored settings is file access, and a broadcast receiver's main thread is not the
 * place for it. `goAsync` is the platform's answer: it keeps the receiver alive past the return of
 * the method, within a budget of about ten seconds. The timeout below stays well inside that, and
 * the pending result is finished on every path — an unfinished one is an application the system
 * eventually complains about.
 */
abstract class WidgoraWidgetProvider : AppWidgetProvider() {

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
     * For the list widgets it is more than a formality: how many items a box holds is decided when
     * the views are built, so a host that has just given the widget a taller box has to be handed
     * views built for it. Sending the current picture is also cheaper than reasoning about whether
     * it was needed.
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
     * The number of placed widgets is not consulted here. This callback *is* the system saying one
     * exists, and counting them would be asking the party that just said so to say it again.
     * Queuing is idempotent under its unique name, so a second arrival of this callback — which the
     * platform documents at boot time, for a host that holds an instance — changes nothing, and so
     * does the arrival of it for a second kind of widget.
     */
    override fun onEnabled(context: Context) {
        WidgetRefreshScheduler.schedule(context)
    }

    /**
     * The last widget **of this provider** is gone, which is not the same as the last widget.
     *
     * The count is consulted here, unlike above, and that asymmetry matters more now than it did
     * with one kind of widget: this callback arrives when a kind disappears, while widgets of
     * another kind may well be sitting on the same screen. One periodic run serves them all, so the
     * question is whether *any* widget is left, which is what the scheduler asks.
     *
     * This is not the only route out of the queue. A callback can be missed — the receiver is not
     * running, the broadcast is dropped — so every start of the application applies the same rule
     * to the real count.
     */
    override fun onDisabled(context: Context) {
        WidgetRefreshScheduler.applyToPlacedWidgets(context)
    }

    /**
     * One or more widgets are gone, so what was stored for them should be too.
     *
     * The first half of a two-part cleanup, and the half that can be missed: a broadcast arrives at
     * a receiver that has to be running to hear it. The second half is a sweep against the widgets
     * that really exist, run on every push — see
     * [io.github.jellyforgedev.widgora.core.data.WidgetSettingsRepository.removeOrphans]. Either is
     * enough on a given occasion; neither is enough on its own.
     */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val application = context.applicationContext as? WidgoraApplication ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(WORK_BUDGET_MS) {
                    for (appWidgetId in appWidgetIds) {
                        application.widgetSettings.delete(appWidgetId)
                    }
                }
            } finally {
                pending.finish()
            }
        }
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
