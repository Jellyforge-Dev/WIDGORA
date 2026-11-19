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
 * It **renders**. It does not retrieve: no network call happens on any path reachable from here.
 * And it asks for no periodic update from the widget framework — `updatePeriodMillis` is zero in
 * the provider's metadata — because that mechanism has a floor of thirty minutes and wakes a
 * sleeping device to run, which on a television means waking up to fetch data nobody is looking
 * at.
 *
 * What updates a widget today is the application itself, while it is in the foreground. That
 * leaves a gap this step does not close: with the application gone, a widget keeps showing
 * whatever was last pushed to it, carrying the timestamp of that moment. The refresh that closes
 * the gap is the next step.
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
