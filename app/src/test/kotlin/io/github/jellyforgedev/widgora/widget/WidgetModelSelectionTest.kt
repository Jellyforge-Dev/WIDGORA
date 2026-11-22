package io.github.jellyforgedev.widgora.widget

import io.github.jellyforgedev.widgora.core.data.InstanceConfig
import io.github.jellyforgedev.widgora.core.data.InstanceNotice
import io.github.jellyforgedev.widgora.core.data.MonitorEntry
import io.github.jellyforgedev.widgora.core.data.MonitorOverview
import io.github.jellyforgedev.widgora.core.data.MonitorState
import io.github.jellyforgedev.widgora.core.data.StoredInstance
import io.github.jellyforgedev.widgora.core.data.StoredInstances
import io.github.jellyforgedev.widgora.core.data.WidgetKind
import io.github.jellyforgedev.widgora.core.data.WidgetSettings
import io.github.jellyforgedev.widgora.core.data.WidgetView
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaSnapshotMapper
import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import io.github.jellyforgedev.widgora.model.WidgetSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a widget decides to show, from its own settings, from storage and from memory.
 *
 * The cases here are the ones that cost something when they are wrong, and every one of them is
 * a statement a user reads: "not set up" when they have set something up, "no data yet" when the
 * data is right there, one server's numbers under another server's name.
 */
class WidgetModelSelectionTest {

    // --- An update arriving at a process that may have just started ----------------------------

    @Test
    fun anUnreadableConfigurationSaysSo() {
        val render = fromStorage(stored = StoredInstances.Unreadable)

        assertEquals(WidgetRenderModel.ConfigurationUnreadable, nameless(render))
    }

    @Test
    fun noStoredInstanceMeansNotSetUp() {
        val render = fromStorage(stored = readable())

        assertEquals(WidgetRenderModel.NotSetUp, nameless(render))
    }

    /**
     * The case this test exists for: the application is running and holds data, and the update
     * arrives from the host rather than from the dashboard.
     *
     * Reading storage alone would produce "no data yet" here, which would be false — and falsest
     * exactly when the user is most likely to be looking, because they have WIDGORA open.
     */
    @Test
    fun dataAlreadyInMemoryIsUsedRatherThanReportedAsMissing() {
        val render = fromStorage(
            stored = readable(valid()),
            views = listOf(view(snapshot = snapshot(active = 35, failed = 2))),
        )

        val instance = instance(render)
        assertEquals(TITLE, instance.title)
        assertEquals("▲35 ▼2", instance.counts)
        assertEquals(NOW, instance.updatedAtEpochMs)
        assertEquals(WidgetViewState.Display(WidgetDisplayState.Ok), instance.state)
    }

    /** A notice in memory reaches the widget as a notice, not as missing data. */
    @Test
    fun aNoticeInMemoryIsUsedRatherThanReportedAsMissing() {
        val render = fromStorage(
            stored = readable(valid()),
            views = listOf(
                view(snapshot = null, state = WidgetViewState.Notice(InstanceNotice.DISABLED)),
            ),
        )

        assertEquals(WidgetViewState.Notice(InstanceNotice.DISABLED), instance(render).state)
    }

    /** Without anything in memory, the old path still applies and says what is true. */
    @Test
    fun withoutDataInMemoryTheWidgetSaysNothingHasBeenRetrieved() {
        val render = fromStorage(stored = readable(valid()))

        val instance = instance(render)
        assertEquals(TITLE, instance.title)
        assertEquals("", instance.counts)
        assertNull(instance.updatedAtEpochMs)
        assertEquals(WidgetViewState.Display(WidgetDisplayState.Loading), instance.state)
    }

    /**
     * Memory about a *different* instance is not memory about this one.
     *
     * Without the match on the identifier, a second configured instance would be enough for a
     * widget to show one server's numbers under another server's name.
     */
    @Test
    fun dataAboutAnotherInstanceIsNotUsed() {
        val render = fromStorage(
            stored = readable(valid()),
            views = listOf(
                view(
                    instanceId = OTHER_INSTANCE_ID,
                    title = "Somewhere else",
                    snapshot = snapshot(active = 9, failed = 9),
                ),
            ),
        )

        val instance = instance(render)
        assertEquals(TITLE, instance.title)
        assertEquals("", instance.counts)
        assertEquals(WidgetViewState.Display(WidgetDisplayState.Loading), instance.state)
    }

    @Test
    fun aStoredEntryThatNoLongerValidatesIsStillNamed() {
        val render = fromStorage(
            stored = readable(StoredInstance.Invalid(INSTANCE_ID, "Kuma at home", null)),
        )

        assertEquals("Kuma at home", instance(render).title)
    }

    /** An invalid entry may have lost its name; then the identifier is all there is. */
    @Test
    fun anInvalidEntryWithoutANameFallsBackToItsIdentifier() {
        val render = fromStorage(stored = readable(StoredInstance.Invalid(INSTANCE_ID, null, null)))

        assertEquals(INSTANCE_ID.value, instance(render).title)
    }

    @Test
    fun aBlankNameIsTreatedAsNoName() {
        val render = fromStorage(
            stored = readable(StoredInstance.Invalid(INSTANCE_ID, "   ", null)),
        )

        assertEquals(INSTANCE_ID.value, instance(render).title)
    }

    // --- A widget with no stored entry ---------------------------------------------------------

    /**
     * A widget placed before there was a configuration step keeps working.
     *
     * This is the compatibility promise stated in [WidgetDefaults], checked where it actually
     * applies rather than only on the rule itself: no settings, and the widget shows the first
     * instance exactly as it did before.
     */
    @Test
    fun aWidgetWithoutAnEntryShowsTheFirstInstance() {
        val render = fromStorage(
            settings = null,
            stored = readable(valid()),
            views = listOf(view(snapshot = snapshot(active = 3, failed = 1))),
        )

        assertEquals("▲3 ▼1", instance(render).counts)
    }

    /** And it names nothing, because nobody was ever asked. */
    @Test
    fun aWidgetWithoutAnEntryNamesNothing() {
        val render = fromStorage(
            settings = null,
            stored = readable(valid()),
            views = listOf(viewWithAffected()),
        )

        assertTrue(render is WidgetRender.Nameless, render.toString())
    }

    /** With several instances the default is the first, and the order is storage's order. */
    @Test
    fun theDefaultIsTheFirstStoredInstanceAndNotJustAnyOfThem() {
        val render = fromStorage(
            settings = null,
            stored = readable(valid(), valid(OTHER_INSTANCE_ID, "Somewhere else")),
            views = listOf(
                view(snapshot = snapshot(active = 1, failed = 0)),
                view(
                    instanceId = OTHER_INSTANCE_ID,
                    title = "Somewhere else",
                    snapshot = snapshot(active = 9, failed = 9),
                ),
            ),
        )

        assertEquals(TITLE, instance(render).title)
    }

    // --- A widget pointed at an instance that is gone ------------------------------------------

    /**
     * The one outcome the project rules out by name: a widget quietly reporting on another server.
     *
     * The settings name an instance that is not in storage, and another one is. Falling back to
     * the first would make this widget show a different server under its own title, which is
     * worse than saying something is missing.
     */
    @Test
    fun aWidgetWhoseInstanceIsGoneSaysSoRatherThanShowingAnother() {
        val render = fromStorage(
            settings = settings(OTHER_INSTANCE_ID),
            stored = readable(valid()),
            views = listOf(view(snapshot = snapshot(active = 5, failed = 0))),
        )

        assertEquals(WidgetRenderModel.InstanceGone, nameless(render))
    }

    /**
     * With no instances at all it says "not set up" instead.
     *
     * Both are true statements and the action is the same, but this one is the statement about
     * the application a user can act on: there is nothing to point a widget at yet.
     */
    @Test
    fun withNoInstancesAtAllTheWidgetSaysNotSetUp() {
        val render = fromStorage(settings = settings(OTHER_INSTANCE_ID), stored = readable())

        assertEquals(WidgetRenderModel.NotSetUp, nameless(render))
    }

    @Test
    fun theRunningApplicationAlsoReportsAnInstanceThatIsGone() {
        val render = fromMemory(
            settings = settings(OTHER_INSTANCE_ID),
            views = listOf(view(snapshot = snapshot(active = 2, failed = 0))),
        )

        assertEquals(WidgetRenderModel.InstanceGone, nameless(render))
    }

    // --- Consent -------------------------------------------------------------------------------

    /** Stored settings that say no produce the nameless model, which cannot carry a name. */
    @Test
    fun withoutConsentTheNamelessModelIsUsed() {
        val render = fromStorage(
            settings = settings(showNames = false),
            stored = readable(valid()),
            views = listOf(viewWithAffected()),
        )

        assertTrue(render is WidgetRender.Nameless, render.toString())
    }

    /** Stored settings that say yes produce the model that can, and it carries the names. */
    @Test
    fun withConsentTheNamesAreCarried() {
        val render = fromStorage(
            settings = settings(showNames = true),
            stored = readable(valid()),
            views = listOf(viewWithAffected()),
        )

        val named = (render as WidgetRender.Named).model
        assertEquals(listOf(FAILED_NAME), named.names)
        assertEquals(0, named.remaining)
    }

    /**
     * Consent without anything retrieved yet is consent with nothing to name.
     *
     * The alternative would be a widget saying "no data yet" above a list of names from a process
     * that has since been reclaimed.
     */
    @Test
    fun consentWithNothingRetrievedNamesNothing() {
        val render = fromStorage(
            settings = settings(showNames = true),
            stored = readable(valid()),
            views = emptyList(),
        )

        val named = (render as WidgetRender.Named).model
        assertEquals(emptyList(), named.names)
        assertEquals(0, named.remaining)
    }

    /** More affected items than there is room for are counted rather than dropped. */
    @Test
    fun consentWithMoreAffectedItemsThanFitReportsTheRest() {
        val entries = (1..7).map { index ->
            entry(index, MonitorState.FAILED, "down-$index.example.invalid")
        }
        val render = fromStorage(
            settings = settings(showNames = true),
            stored = readable(valid()),
            views = listOf(
                view(snapshot = snapshot(active = 0, failed = 7), overview = MonitorOverview(entries)),
            ),
        )

        val named = (render as WidgetRender.Named).model
        assertEquals(NamedWidgetRenderModel.MAX_NAMES, named.names.size)
        assertEquals(entries.size - NamedWidgetRenderModel.MAX_NAMES, named.remaining)
    }

    /** An unreadable configuration outranks consent: there is no instance to name anything of. */
    @Test
    fun anUnreadableConfigurationOutranksConsent() {
        val render = fromStorage(
            settings = settings(showNames = true),
            stored = StoredInstances.Unreadable,
            views = listOf(viewWithAffected()),
        )

        assertEquals(WidgetRenderModel.ConfigurationUnreadable, nameless(render))
    }

    // --- A push from the running application ---------------------------------------------------

    @Test
    fun theRunningApplicationReportsAnUnreadableConfiguration() {
        val render = fromMemory(configurationUnreadable = true)

        assertEquals(WidgetRenderModel.ConfigurationUnreadable, nameless(render))
    }

    /**
     * Here an empty list really does mean nothing is configured.
     *
     * The repository has read storage by the time this path runs, which is the whole reason the
     * two paths are separate: the same emptiness means two different things depending on who is
     * asking.
     */
    @Test
    fun theRunningApplicationWithNoViewsMeansNotSetUp() {
        val render = fromMemory(settings = null)

        assertEquals(WidgetRenderModel.NotSetUp, nameless(render))
    }

    @Test
    fun theRunningApplicationRendersWhatItHolds() {
        val render = fromMemory(views = listOf(view(snapshot = snapshot(active = 7, failed = 0))))

        val instance = instance(render)
        assertEquals("▲7 ▼0", instance.counts)
        assertEquals(NOW, instance.updatedAtEpochMs)
    }

    /** An unreadable configuration outranks anything left in memory from before. */
    @Test
    fun anUnreadableConfigurationOutranksWhatIsLeftInMemory() {
        val render = fromMemory(
            configurationUnreadable = true,
            views = listOf(view(snapshot = snapshot(active = 7, failed = 0))),
        )

        assertEquals(WidgetRenderModel.ConfigurationUnreadable, nameless(render))
    }

    // --- Helpers -------------------------------------------------------------------------------

    private fun fromStorage(
        stored: StoredInstances,
        settings: WidgetSettings? = settings(),
        views: List<WidgetView> = emptyList(),
        kind: WidgetKind = WidgetKind.STATUS,
    ): WidgetRender = WidgetModelSelection.fromStorage(
        kind = kind,
        settings = settings,
        stored = stored,
        views = views,
        nowEpochMs = NOW,
    )

    private fun fromMemory(
        settings: WidgetSettings? = settings(),
        configurationUnreadable: Boolean = false,
        views: List<WidgetView> = emptyList(),
        kind: WidgetKind = WidgetKind.STATUS,
    ): WidgetRender = WidgetModelSelection.fromMemory(
        kind = kind,
        settings = settings,
        configurationUnreadable = configurationUnreadable,
        views = views,
        nowEpochMs = NOW,
    )

    private fun settings(
        instanceId: InstanceId = INSTANCE_ID,
        showNames: Boolean = false,
    ): WidgetSettings = WidgetSettings(
        kind = WidgetKind.STATUS,
        instanceId = instanceId,
        showNames = showNames,
    )

    private fun nameless(render: WidgetRender): WidgetRenderModel =
        (render as WidgetRender.Nameless).model

    private fun instance(render: WidgetRender): WidgetRenderModel.Instance =
        nameless(render) as WidgetRenderModel.Instance

    private fun readable(vararg instances: StoredInstance) =
        StoredInstances.Readable(instances.toList())

    private fun valid(
        instanceId: InstanceId = INSTANCE_ID,
        displayName: String = TITLE,
    ): StoredInstance.Valid = StoredInstance.Valid(
        InstanceConfig(
            instanceId = instanceId,
            displayName = displayName,
            baseUrl = BASE_URL,
            enabled = true,
        ),
    )

    private fun view(
        instanceId: InstanceId = INSTANCE_ID,
        title: String = TITLE,
        snapshot: WidgetSnapshot?,
        overview: MonitorOverview? = null,
        state: WidgetViewState = WidgetViewState.Display(WidgetDisplayState.Ok),
    ): WidgetView = WidgetView(
        instanceId = instanceId,
        widgetId = KumaSnapshotMapper.summaryWidgetId(instanceId),
        title = title,
        snapshot = snapshot,
        state = state,
        overview = overview,
    )

    /** One failed item with a name of the shape a real one has, plus two working ones. */
    private fun viewWithAffected(): WidgetView {
        val entries = listOf(
            entry(1, MonitorState.ACTIVE, "up-1.example.invalid"),
            entry(2, MonitorState.FAILED, FAILED_NAME),
            entry(3, MonitorState.ACTIVE, "up-3.example.invalid"),
        )
        return view(
            snapshot = snapshot(active = 2, failed = 1),
            overview = MonitorOverview(entries),
        )
    }

    private fun snapshot(active: Int, failed: Int): WidgetSnapshot {
        var next = 0
        val entries = buildList {
            repeat(active) { add(entry(next++, MonitorState.ACTIVE)) }
            repeat(failed) { add(entry(next++, MonitorState.FAILED)) }
        }
        return KumaSnapshotMapper.summarySnapshot(
            instanceId = INSTANCE_ID,
            title = TITLE,
            overview = MonitorOverview(entries),
            retrievedAtEpochMs = NOW,
        )
    }

    private fun entry(
        id: Int,
        state: MonitorState,
        name: String = "monitor-$id.example.invalid",
    ): MonitorEntry = MonitorEntry.truncating(
        monitorId = "m$id",
        name = name,
        state = state,
        responseTimeMs = null,
    )

    private companion object {

        val INSTANCE_ID = InstanceId("kuma")
        val OTHER_INSTANCE_ID = InstanceId("kuma-two")
        const val TITLE = "Uptime Kuma"
        const val BASE_URL = "https://kuma.example.invalid"
        const val FAILED_NAME = "nas.kitchen.invalid"

        /** A fixed instant, so nothing in this file depends on when it runs. */
        const val NOW = 1_700_000_000_000L
    }
}
