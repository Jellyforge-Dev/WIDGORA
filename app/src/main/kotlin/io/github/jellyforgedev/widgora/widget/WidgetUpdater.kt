package io.github.jellyforgedev.widgora.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import io.github.jellyforgedev.widgora.WidgoraApplication
import io.github.jellyforgedev.widgora.core.data.Clock
import io.github.jellyforgedev.widgora.core.data.WidgetKind
import kotlinx.coroutines.flow.first

/**
 * Pushes the current picture to every WIDGORA widget.
 *
 * ### Nothing here retrieves anything
 *
 * No path in this object contacts a network. A widget shows what the application already knows,
 * and what puts data there is a retrieval — the dashboard's while someone is looking, or the
 * periodic worker's. Both call in here afterwards.
 *
 * ### Why there are two paths and not one
 *
 * They differ in what they are allowed to assume. [pushFromMemory] runs inside the running
 * application, where the snapshot repository has already read storage, so an empty list of views
 * genuinely means nothing is configured. [pushFromStorage] serves an update arriving at a process
 * that may have just been started for it, where an empty list means only that nothing has been
 * retrieved *yet* — a different statement entirely. That path therefore reads the stored instance
 * list as well.
 *
 * Both of them prefer what is in memory wherever there is anything, and the decision itself lives
 * in [WidgetModelSelection], which is where it can be tested.
 *
 * ### Why each widget is drawn on its own
 *
 * Because each one has its own settings now. Two widgets of the same provider can point at
 * different instances and differ in whether they may name monitors, so a single model pushed to
 * all of them would be wrong for one of the two. The loop is per placement, and the per-widget
 * pending intent — which always was per placement — rides along with it.
 */
object WidgetUpdater {

    /**
     * The identifiers of **every** WIDGORA widget, whatever its type.
     *
     * This is what the orphan sweep compares against, and the reason it exists. The previous
     * version compared against the widgets of one provider, so the moment a second type is
     * declared, every settings entry belonging to it would look like an entry for a widget nobody
     * placed — and would be removed on the next push.
     */
    fun allWidgetIds(context: Context): IntArray {
        val manager = AppWidgetManager.getInstance(context)
        return placedIds(providerClassNames(installedProviderClassNames(context))) { className ->
            manager.getAppWidgetIds(ComponentName(context.packageName, className))
        }.toIntArray()
    }

    /** The identifiers of the widgets of one type, which is what a push is built for. */
    fun widgetIdsOf(context: Context, kind: WidgetKind): IntArray {
        val className = WidgetProviders.classNameOf(kind) ?: return IntArray(0)
        return AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context.packageName, className))
    }

    /**
     * Pushes from what the running application holds.
     *
     * Called after each foreground retrieval, including one that failed. That is wider than
     * "after a successful retrieval" on purpose: an instance that has become unreachable is news,
     * and a widget left showing the last good value while the dashboard beside it says the
     * service is down would be the one outcome worth avoiding.
     *
     * It suspends because it reads one small file — the per-widget settings. Holding a second copy
     * of them in memory, in step with the file, would be the alternative, for the sake of avoiding
     * a read that happens once per retrieval.
     */
    suspend fun pushFromMemory(context: Context) {
        val application = context.applicationContext as? WidgoraApplication ?: return
        if (allWidgetIds(context).isEmpty()) return

        val settings = application.widgetSettings.settings.first()
        val views = application.snapshots.views.value
        val configurationUnreadable = application.snapshots.configurationUnreadable.value
        val now = Clock.System.nowEpochMs()

        forEachWidget(context) { kind, appWidgetId ->
            WidgetModelSelection.fromMemory(
                kind = kind,
                settings = settings[appWidgetId],
                configurationUnreadable = configurationUnreadable,
                views = views,
                nowEpochMs = now,
            )
        }
    }

    /**
     * Pushes from storage plus whatever happens to be in memory.
     *
     * Reads two small preference files and nothing else. A file that cannot be read is not an
     * exception here: the instance repository reports it as an unreadable configuration, which is
     * a state the widget shows in its own words.
     *
     * @param justConfigured a placement whose entry was written moments ago and must survive the
     *   orphan sweep even if the widget manager does not list it yet. It exists for exactly one
     *   caller — the configuration step, pushing immediately after its own write — and for the one
     *   case where a stored entry is legitimately newer than what the manager reports. Without it
     *   that write could be undone by the sweep in the same breath.
     */
    suspend fun pushFromStorage(context: Context, justConfigured: Int? = null) {
        val application = context.applicationContext as? WidgoraApplication ?: return
        val placed = allWidgetIds(context)

        // The second half of the per-widget settings cleanup. A host's deletion callback can fail
        // to arrive, and a stored entry for a widget nobody can see would then stay for good. This
        // is the one moment at which the application knows which widgets really exist, so it is
        // the moment to compare. It runs before the early return on purpose: no widget at all is
        // precisely the case where everything stored is an orphan. Nothing is written when nothing
        // is stale, so an ordinary update costs one read and no write.
        application.widgetSettings.removeOrphans(
            placed.toMutableSet().apply { justConfigured?.let(::add) },
        )

        if (placed.isEmpty()) return

        val settings = application.widgetSettings.settings.first()
        val stored = application.instanceStore.instances.first()
        val views = application.snapshots.views.value
        val now = Clock.System.nowEpochMs()

        forEachWidget(context) { kind, appWidgetId ->
            WidgetModelSelection.fromStorage(
                kind = kind,
                settings = settings[appWidgetId],
                stored = stored,
                views = views,
                nowEpochMs = now,
            )
        }
    }

    /**
     * Every provider class to ask about placed widgets.
     *
     * The declared classes are added to the installed ones rather than replacing them, and the
     * union is what makes this safe in both directions. An installed provider this version has no
     * mapping for still has its widgets counted, so none of them is swept away as an orphan; and a
     * provider the package manager does not name on some occasion is still asked, so a list that
     * comes back short cannot turn every stored entry into an orphan. One of those two mistakes
     * deletes settings, which is why the cheap defence is worth the line.
     *
     * Written against plain strings so it can be tested without a device.
     */
    internal fun providerClassNames(installed: List<String>): List<String> =
        (installed + WidgetProviders.declaredClassNames).distinct()

    /**
     * The placed widgets of all the given providers, as one set.
     *
     * A set because the question is which widgets exist, not how they are grouped, and because a
     * provider named twice must not make a widget appear twice.
     *
     * Separated from the widget manager so that the thing worth checking — that **every** named
     * provider is asked — is a test rather than an argument.
     */
    internal fun placedIds(
        providerClassNames: List<String>,
        idsOf: (String) -> IntArray,
    ): Set<Int> = providerClassNames.flatMapTo(mutableSetOf()) { className ->
        idsOf(className).toList()
    }

    /** The provider classes the package manager reports for this package. */
    private fun installedProviderClassNames(context: Context): List<String> =
        AppWidgetManager.getInstance(context)
            .getInstalledProvidersForPackage(context.packageName, null)
            .map { info -> info.provider.className }

    /**
     * Builds and pushes one widget at a time, for every type that has a provider.
     *
     * The kind comes from the provider the identifier belongs to and not from the stored entry.
     * That is the right way round: what a widget *is* was decided when it was placed, and a stored
     * entry naming another kind is a mismatch to be refused rather than a fact to be believed.
     */
    private inline fun forEachWidget(
        context: Context,
        render: (WidgetKind, Int) -> WidgetRender,
    ) {
        val manager = AppWidgetManager.getInstance(context)
        for (kind in WidgetKind.entries) {
            for (appWidgetId in widgetIdsOf(context, kind)) {
                manager.updateAppWidget(
                    appWidgetId,
                    viewsFor(context, appWidgetId, render(kind, appWidgetId)),
                )
            }
        }
    }

    /**
     * The one place the two renderers are chosen between.
     *
     * A `when` over a sealed type rather than an overload: the two build functions take one model
     * each and neither can be reached with the other's model, so there is no call on which a
     * nameless model could end up in the naming renderer.
     */
    internal fun viewsFor(
        context: Context,
        appWidgetId: Int,
        render: WidgetRender,
    ): RemoteViews = when (render) {
        is WidgetRender.Nameless -> WidgetViews.build(context, appWidgetId, render.model)
        is WidgetRender.Named -> WidgetViews.buildNaming(context, appWidgetId, render.model)
    }
}
