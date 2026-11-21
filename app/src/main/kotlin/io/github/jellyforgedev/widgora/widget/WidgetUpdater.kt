package io.github.jellyforgedev.widgora.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import io.github.jellyforgedev.widgora.WidgoraApplication
import io.github.jellyforgedev.widgora.core.data.Clock
import kotlinx.coroutines.flow.first

/**
 * Pushes the current picture to every WIDGORA widget.
 *
 * ### Nothing here retrieves anything
 *
 * No path in this object contacts a network. A widget shows what the application already knows,
 * and the only thing that puts data there is the dashboard retrieving while someone is looking at
 * it. The periodic retrieval that will change that is a later step, and pretending to have data
 * in the meantime would mean inventing it.
 *
 * ### Why there are two paths and not one
 *
 * They differ in what they are allowed to assume. [pushFromMemory] runs inside the running
 * application, where the snapshot repository has already read storage, so an empty list of views
 * genuinely means nothing is configured. [pushFromStorage] serves an update arriving at a process
 * that may have just been started for it, where an empty list means only that nothing has been
 * retrieved *yet* — a different statement entirely. That path therefore reads the stored instance
 * list, and takes the opportunity to sweep stale per-widget settings while it is at it: two small
 * files and no network.
 *
 * Both of them prefer what is in memory wherever there is anything, and the decision itself lives
 * in [WidgetModelSelection], which is where it can be tested.
 */
object WidgetUpdater {

    /** The identifiers of this application's placed widgets. Empty when none are placed. */
    fun widgetIds(context: Context): IntArray = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, CountsWidgetProvider::class.java))

    /**
     * Pushes from what the running application holds.
     *
     * Called after each foreground retrieval, including one that failed. That is wider than
     * "after a successful retrieval" on purpose: an instance that has become unreachable is news,
     * and a widget left showing the last good value while the dashboard beside it says the
     * service is down would be the one outcome worth avoiding.
     */
    fun pushFromMemory(context: Context) {
        val application = context.applicationContext as? WidgoraApplication ?: return
        val ids = widgetIds(context)
        if (ids.isEmpty()) return

        push(
            context,
            ids,
            WidgetModelSelection.fromMemory(
                configurationUnreadable = application.snapshots.configurationUnreadable.value,
                views = application.snapshots.views.value,
                nowEpochMs = Clock.System.nowEpochMs(),
            ),
        )
    }

    /**
     * Pushes from storage plus whatever happens to be in memory.
     *
     * Reads one small preference file and nothing else. A file that cannot be read is not an
     * exception here: the repository reports it as an unreadable configuration, which is a state
     * the widget shows in its own words.
     */
    suspend fun pushFromStorage(context: Context) {
        val application = context.applicationContext as? WidgoraApplication ?: return
        val ids = widgetIds(context)

        // The second half of the per-widget settings cleanup. A host's deletion callback can fail
        // to arrive, and a stored entry for a widget nobody can see would then stay for good. This
        // is the one moment at which the application knows which widgets really exist, so it is
        // the moment to compare. It runs before the early return on purpose: no widget at all is
        // precisely the case where everything stored is an orphan. Nothing is written when nothing
        // is stale, so an ordinary update costs one read and no write.
        application.widgetSettings.removeOrphans(ids.toSet())

        if (ids.isEmpty()) return

        push(
            context,
            ids,
            WidgetModelSelection.fromStorage(
                stored = application.instanceStore.instances.first(),
                views = application.snapshots.views.value,
                nowEpochMs = Clock.System.nowEpochMs(),
            ),
        )
    }

    /**
     * The same picture to every widget.
     *
     * Each one still gets its own views object, because each carries a pending intent keyed to
     * its own identifier.
     */
    private fun push(context: Context, ids: IntArray, model: WidgetRenderModel) {
        val manager = AppWidgetManager.getInstance(context)
        for (id in ids) {
            manager.updateAppWidget(id, WidgetViews.build(context, id, model))
        }
    }
}
