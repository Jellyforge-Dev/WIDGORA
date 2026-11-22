package io.github.jellyforgedev.widgora.widget

import io.github.jellyforgedev.widgora.core.data.WidgetKind
import io.github.jellyforgedev.widgora.model.InstanceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What applies to a widget placed before there was anything to configure.
 *
 * This is a compatibility promise rather than a convenience, which is why it is a rule with a
 * test rather than a fallback inside the renderer. A widget on somebody's television worked
 * yesterday; after this change it must still work, and it must not quietly start naming monitors
 * nobody was asked about.
 */
class WidgetDefaultsTest {

    @Test
    fun aStatusWidgetWithoutAnEntryShowsTheFirstInstance() {
        val settings = assertNotNull(WidgetDefaults.withoutAnEntry(WidgetKind.STATUS, FIRST))

        assertEquals(WidgetKind.STATUS, settings.kind)
        assertEquals(FIRST, settings.instanceId)
    }

    /** The part that is a decision about the user's data rather than about convenience. */
    @Test
    fun theDefaultNeverTurnsNamesOn() {
        val settings = assertNotNull(WidgetDefaults.withoutAnEntry(WidgetKind.STATUS, FIRST))

        assertFalse(settings.showNames)
    }

    @Test
    fun theDefaultSelectsNoMonitors() {
        val settings = assertNotNull(WidgetDefaults.withoutAnEntry(WidgetKind.STATUS, FIRST))

        assertTrue(settings.monitorIds.isEmpty())
    }

    /** With nothing set up there is nothing to point at, and no default to offer. */
    @Test
    fun withoutAnyInstanceThereIsNoDefault() {
        assertNull(WidgetDefaults.withoutAnEntry(WidgetKind.STATUS, null))
    }

    /**
     * The two future types have no default, and that is the deliberate half of the rule.
     *
     * Neither has ever been placed, so there is no earlier behaviour to preserve. A selection is
     * a list only the user has, and a certificate widget pointed at whichever instance came first
     * would be the silent re-pointing the project rules out by name.
     */
    @Test
    fun theFutureTypesHaveNoDefault() {
        assertNull(WidgetDefaults.withoutAnEntry(WidgetKind.CERTIFICATES, FIRST))
        assertNull(WidgetDefaults.withoutAnEntry(WidgetKind.SELECTION, FIRST))
    }

    private companion object {

        val FIRST = InstanceId("kuma")
    }
}
