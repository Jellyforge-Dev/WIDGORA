package io.github.jellyforgedev.widgora.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.jellyforgedev.widgora.WidgoraApplication
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One periodic run: retrieve, then push what was retrieved to every placed widget.
 *
 * This closes the gap the widget shipped with. Until now a widget showed whatever the dashboard
 * last pushed to it while someone was looking at the application, and it carried the timestamp of
 * that moment for as long as nobody opened the application again.
 *
 * ### Why a failed retrieval is a successful run
 *
 * Those are two different questions and this worker answers only the second. An instance that
 * cannot be reached, or that rejects the credential, is a **result**: it is pushed, the widget
 * says so, and the next periodic run is the retry. Reporting a retry instead would turn one
 * unreachable server into a run that keeps retrying with backoff while the widget still showed
 * the last comfortable picture — the one outcome worth avoiding.
 *
 * Nothing special is needed to achieve that, which is worth stating because its absence looks
 * like an oversight: `SnapshotRepository.refresh` does not raise on a failed retrieval. It records
 * the failure as that instance's state and keeps the last good snapshot beside it. A test in
 * `:core` holds that contract, because this worker rests on it.
 *
 * Only a technical break-off before anything reached a widget asks to be retried. The decision
 * itself lives in [WidgetRefreshPlan], where it can be exercised without a device.
 *
 * ### What it holds
 *
 * Nothing. No field, no cached snapshot, no bitmap. What it retrieves goes into the repository the
 * running application already owns, and what it draws is text in a view hierarchy. The limits on
 * an answer apply here exactly as they do to a foreground retrieval, because it is the same
 * client: an oversized body and an implausible number of monitors are both refused before
 * anything is built from them.
 */
internal class WidgetRefreshWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result =
        when (WidgetRefreshPlan.outcomeOf(attempt())) {
            RefreshOutcome.SUCCESS -> Result.success()
            RefreshOutcome.RETRY -> Result.retry()
        }

    /**
     * The run itself, reported as what it did.
     *
     * The order is the one the two statements above describe: retrieve first, push second, and
     * report [RefreshRun.Pushed] only once the push has returned. Anything breaking off earlier
     * has produced nothing a widget could show.
     */
    private suspend fun attempt(): RefreshRun {
        val application = applicationContext as? WidgoraApplication
            ?: return RefreshRun.NothingToPushTo
        if (WidgetUpdater.allWidgetIds(applicationContext).isEmpty()) {
            return RefreshRun.NothingToPushTo
        }

        return try {
            // The retrieval blocks the coroutine it runs in — the repository says so in its own
            // contract — so it is given a dispatcher that may be blocked.
            withContext(Dispatchers.IO) { application.snapshots.refresh() }
            WidgetUpdater.pushFromStorage(applicationContext)
            RefreshRun.Pushed
        } catch (cancellation: CancellationException) {
            // The run was stopped: the system reclaiming the slot, or the work being cancelled.
            // Not a failure to report, and swallowing it into a retry would hide a cancellation
            // from the machinery that issued it.
            throw cancellation
        } catch (failure: Exception) {
            RefreshRun.BrokeOffBeforePush(failure)
        }
    }
}
