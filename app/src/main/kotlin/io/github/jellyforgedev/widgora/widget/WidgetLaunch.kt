package io.github.jellyforgedev.widgora.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.github.jellyforgedev.widgora.MainActivity

/**
 * What happens when someone presses a widget: WIDGORA opens, and nothing else can.
 *
 * ### Why this is stricter than the rule it replaces
 *
 * The deferred bridge carried a deep link as a string, `widgora://widget/{id}`, and the project
 * had to write down at length that a custom scheme is not an identity claim: any application may
 * register an intent filter for it, so a host resolving that link could open other software and
 * the user would have no way to notice. Both sides had to defend against that.
 *
 * None of it arises here. The intent below is built inside WIDGORA's own process, names a WIDGORA
 * component directly, and is wrapped in a [PendingIntent] marked immutable, so a host can send it
 * and can do nothing else with it: it cannot change the target and it cannot add an extra. There
 * is no scheme to register and no resolution to subvert.
 *
 * What remains from the old rule is the part that was never about the scheme: the receiving side
 * validates the identifier it is handed rather than trusting it. See [validAppWidgetId].
 */
object WidgetLaunch {

    /**
     * The intent a widget press delivers.
     *
     * Explicit twice over, which is less redundant than it looks: the component names the class,
     * and `setPackage` keeps the restriction in place for any later change that would otherwise
     * leave an implicit intent behind. The only thing carried is the widget's own identifier.
     */
    fun intentFor(context: Context, appWidgetId: Int): Intent =
        Intent(context, MainActivity::class.java).apply {
            component = ComponentName(context, MainActivity::class.java)
            setPackage(context.packageName)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }

    /**
     * The pending intent handed to the host.
     *
     * [PendingIntent.FLAG_IMMUTABLE] is the load-bearing flag: without it a host could fill in
     * parts of the intent it was given. The request code is the widget's own identifier, so two
     * widgets get two distinct pending intents rather than quietly sharing one — with a shared
     * request code, [PendingIntent.FLAG_UPDATE_CURRENT] would make the widget updated last the
     * one that every widget points at.
     */
    fun pendingIntentFor(context: Context, appWidgetId: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            appWidgetId,
            intentFor(context, appWidgetId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * The identifier an arriving intent carries, if it is one of ours.
     *
     * A value that is absent, is the platform's "no widget" marker, or names a widget this
     * application does not have is not an error to report — it is simply not a widget launch, and
     * the activity opens as it otherwise would. Taking the caller's word for it would mean acting
     * on an identifier chosen by whoever sent the intent.
     *
     * Written against plain integers rather than an `Intent` so that it can be tested without a
     * device.
     *
     * @param candidate the value read from the intent, or [AppWidgetManager.INVALID_APPWIDGET_ID]
     *   when there was none
     * @param knownIds the identifiers of this application's own widgets
     */
    fun validAppWidgetId(candidate: Int, knownIds: IntArray): Int? = candidate
        .takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }
        ?.takeIf { it in knownIds }

    /** Reads and validates in one step, for a caller that does have an intent and a context. */
    fun validAppWidgetIdFrom(context: Context, intent: Intent?): Int? {
        val candidate = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Every widget of this application, whatever its type. A press on a certificate widget is
        // a widget launch exactly as a press on a counts widget is, and asking only one provider
        // would make the second type's widgets open the application as though from nowhere.
        return validAppWidgetId(candidate, WidgetUpdater.allWidgetIds(context))
    }
}
