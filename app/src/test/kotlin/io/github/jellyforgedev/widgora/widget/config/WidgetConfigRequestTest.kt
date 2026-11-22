package io.github.jellyforgedev.widgora.widget.config

import android.appwidget.AppWidgetManager
import io.github.jellyforgedev.widgora.core.data.WidgetKind
import io.github.jellyforgedev.widgora.widget.CountsWidgetProvider
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Who may ask this application to configure a widget.
 *
 * The screen has to be exported — a launcher in another process is what starts it — so anything
 * on the device can start it with any identifier in the intent. These are the conditions under
 * which such a request is acted on.
 */
class WidgetConfigRequestTest {

    @Test
    fun aWidgetOfThisPackageIsConfigured() {
        assertEquals(WidgetConfigRequest.Configure(17, WidgetKind.STATUS), of(appWidgetId = 17))
    }

    /** No identifier is no request. It is the value an intent without the extra produces. */
    @Test
    fun theNoWidgetMarkerIsRefused() {
        assertEquals(
            WidgetConfigRequest.Refuse,
            of(appWidgetId = WidgetConfigRequests.NO_APP_WIDGET_ID),
        )
    }

    /**
     * An identifier the widget manager knows nothing about is refused.
     *
     * This is the shape a made-up number has: the manager reports no provider for it, so there is
     * neither a package to compare nor a type to configure.
     */
    @Test
    fun anIdentifierTheManagerDoesNotKnowIsRefused() {
        assertEquals(
            WidgetConfigRequest.Refuse,
            of(appWidgetId = 17, providerPackageName = null, providerClassName = null),
        )
    }

    /** A widget of another application is refused, however well-formed the request looks. */
    @Test
    fun aWidgetOfAnotherPackageIsRefused() {
        assertEquals(
            WidgetConfigRequest.Refuse,
            of(
                appWidgetId = 17,
                providerPackageName = "com.example.other",
                providerClassName = CountsWidgetProvider::class.java.name,
            ),
        )
    }

    /**
     * A provider of this package that this version has no mapping for is refused too.
     *
     * Deliberately not defaulted to the one type that exists: the screen's questions depend on
     * the type, so an unknown type means it does not know what it is asking about.
     */
    @Test
    fun aProviderOfOursWithoutAKnownTypeIsRefused() {
        assertEquals(
            WidgetConfigRequest.Refuse,
            of(
                appWidgetId = 17,
                providerClassName = "io.github.jellyforgedev.widgora.widget.FutureProvider",
            ),
        )
    }

    /**
     * The copied marker agrees with the platform's.
     *
     * It is written out so the rule stays testable without a device; this is the one thing such a
     * copy needs, and it costs a line.
     */
    @Test
    fun theCopiedMarkerMatchesThePlatform() {
        assertEquals(AppWidgetManager.INVALID_APPWIDGET_ID, WidgetConfigRequests.NO_APP_WIDGET_ID)
    }

    private fun of(
        appWidgetId: Int,
        providerPackageName: String? = OWN_PACKAGE,
        providerClassName: String? = CountsWidgetProvider::class.java.name,
    ): WidgetConfigRequest = WidgetConfigRequests.of(
        appWidgetId = appWidgetId,
        providerPackageName = providerPackageName,
        providerClassName = providerClassName,
        ownPackageName = OWN_PACKAGE,
    )

    private companion object {

        const val OWN_PACKAGE = "io.github.jellyforgedev.widgora"
    }
}
