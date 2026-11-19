package io.github.jellyforgedev.widgora.devhost

import android.appwidget.AppWidgetManager
import io.github.jellyforgedev.widgora.settings.DEBUG_WIDGET_HOST_ACTIVITY
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the host does with the identifier it was given last time.
 *
 * This is the part of the host worth testing; the rest is the platform. Getting it wrong costs
 * an identifier per start — a widget the system believes is placed somewhere, invisible to
 * everybody, accumulating quietly.
 *
 * It sits in the debug unit test source set because the code it tests sits in the debug source
 * set. A release unit test run can see neither.
 */
class HostWidgetIdsTest {

    private val ours = "io.github.jellyforgedev.widgora.widget.CountsWidgetProvider"

    @Test
    fun aFirstRunAsksForAnIdentifier() {
        assertEquals(
            HostWidgetDecision.Allocate,
            HostWidgetIds.decide(storedAppWidgetId = null, boundProviderClassName = null, ours),
        )
    }

    /**
     * A stored zero is the platform's "no widget" marker, not a widget numbered zero.
     *
     * Releasing it would ask the system to give back something it never handed out.
     */
    @Test
    fun aStoredNoWidgetMarkerIsNotSomethingToGiveBack() {
        assertEquals(
            HostWidgetDecision.Allocate,
            HostWidgetIds.decide(HostWidgetIds.NO_APP_WIDGET_ID, null, ours),
        )
    }

    @Test
    fun anIdentifierStillNamingOurWidgetIsUsedAgain() {
        assertEquals(
            HostWidgetDecision.Reuse(17),
            HostWidgetIds.decide(17, ours, ours),
        )
    }

    /**
     * Bound to nothing is the ordinary shape of a reinstalled application: the record survives
     * in this host's own storage while the system has long forgotten it.
     */
    @Test
    fun anIdentifierBoundToNothingIsGivenBackFirst() {
        assertEquals(
            HostWidgetDecision.ReleaseAndAllocate(17),
            HostWidgetIds.decide(17, null, ours),
        )
    }

    @Test
    fun anIdentifierBoundToAnotherProviderIsGivenBackFirst() {
        assertEquals(
            HostWidgetDecision.ReleaseAndAllocate(17),
            HostWidgetIds.decide(17, "com.example.other.SomeWidgetProvider", ours),
        )
    }

    /**
     * The copied constant agrees with the platform's.
     *
     * It is written out so that the decision stays testable without a device; this is the one
     * thing that makes such a copy safe, and it costs a line.
     */
    @Test
    fun theCopiedMarkerMatchesThePlatform() {
        assertEquals(AppWidgetManager.INVALID_APPWIDGET_ID, HostWidgetIds.NO_APP_WIDGET_ID)
    }

    /**
     * The settings screen names the host activity as a string, because in a release build that
     * class does not exist and cannot be referred to as a class.
     *
     * Here it does exist, so the string can be held against it. Without this, renaming the
     * activity would produce a button that opens nothing, and nothing would say so.
     */
    @Test
    fun theSettingsScreenNamesTheActivityThatExists() {
        assertEquals(WidgetHostActivity::class.java.name, DEBUG_WIDGET_HOST_ACTIVITY)
    }
}
