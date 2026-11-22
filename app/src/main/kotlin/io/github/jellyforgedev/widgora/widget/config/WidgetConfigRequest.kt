package io.github.jellyforgedev.widgora.widget.config

import io.github.jellyforgedev.widgora.core.data.WidgetKind
import io.github.jellyforgedev.widgora.widget.WidgetProviders

/** Whether a configuration request is one this application will act on. */
sealed interface WidgetConfigRequest {

    /** A widget of this package, of a type this version knows. */
    data class Configure(val appWidgetId: Int, val kind: WidgetKind) : WidgetConfigRequest

    /** Anything else. The screen closes without writing and without saying why. */
    data object Refuse : WidgetConfigRequest
}

/**
 * Who may ask this application to configure a widget.
 *
 * ### Why there is anything to check
 *
 * The configuration activity **has to be exported**: the party that starts it is the host
 * launcher, in another process, and an activity a launcher cannot start is a configuration step
 * that never runs. Exported means any application on the device can start it, with any identifier
 * in the intent.
 *
 * What that could achieve is narrow but real. The screen writes one entry into WIDGORA's own
 * per-widget settings file, keyed by the identifier it is handed. An arbitrary identifier would
 * therefore write an entry for a widget belonging to somebody else — or for no widget at all —
 * and the latter would be collected by the orphan sweep on the next push, which is noise rather
 * than harm. It is still a write on behalf of a caller that has no business asking for one.
 *
 * Nothing secret is reachable this way, and that is a property of the screen rather than of this
 * check: there is no address, no credential and no monitor name on it — only the names of the
 * instances the user set up, and offering that list is what the screen is for. The check is the
 * cheap half of the defence; the screen holding nothing worth taking is the other half.
 *
 * ### What is checked
 *
 * That the identifier names a widget whose provider is **in this package** and of a type this
 * version knows. The widget manager is the authority on both: it is asked what the identifier is
 * bound to, rather than the caller being asked what it meant.
 *
 * Written against plain values so the rule can be tested without a device.
 */
object WidgetConfigRequests {

    /**
     * @param appWidgetId the identifier from the intent, or the platform's "no widget" marker
     * @param providerPackageName the package of the provider the widget manager reports for that
     *   identifier, or `null` when it reports none — which is what an identifier naming no widget
     *   looks like
     * @param providerClassName the provider class the widget manager reports, or `null`
     * @param ownPackageName this application's own package
     */
    fun of(
        appWidgetId: Int,
        providerPackageName: String?,
        providerClassName: String?,
        ownPackageName: String,
    ): WidgetConfigRequest {
        if (appWidgetId == NO_APP_WIDGET_ID) return WidgetConfigRequest.Refuse
        if (providerPackageName != ownPackageName) return WidgetConfigRequest.Refuse

        // A provider of this package that this version has no mapping for cannot be configured
        // either, and that is deliberate: the screen's questions depend on the type, so an
        // unknown type means the screen does not know what it is asking about. It is refused
        // rather than defaulted to the one type that exists.
        val kind = providerClassName?.let { WidgetProviders.kindOf(it) }
            ?: return WidgetConfigRequest.Refuse

        return WidgetConfigRequest.Configure(appWidgetId, kind)
    }

    /**
     * The value `AppWidgetManager.INVALID_APPWIDGET_ID` carries.
     *
     * Written out rather than read from the platform class, so this file stays testable without a
     * device. A test asserts that the two agree, which is the only thing a copied constant needs.
     */
    const val NO_APP_WIDGET_ID = 0
}
