package io.github.jellyforgedev.widgora.widget

import io.github.jellyforgedev.widgora.core.data.InstanceConfig
import io.github.jellyforgedev.widgora.core.data.InstanceNotice
import io.github.jellyforgedev.widgora.core.data.MonitorEntry
import io.github.jellyforgedev.widgora.core.data.MonitorOverview
import io.github.jellyforgedev.widgora.core.data.MonitorState
import io.github.jellyforgedev.widgora.core.data.StoredInstance
import io.github.jellyforgedev.widgora.core.data.StoredInstances
import io.github.jellyforgedev.widgora.core.data.WidgetView
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaSnapshotMapper
import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import io.github.jellyforgedev.widgora.model.WidgetSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a widget decides to show, from storage and from memory.
 *
 * The cases here are the ones that cost something when they are wrong, and every one of them is
 * a statement a user reads: "not set up" when they have set something up, "no data yet" when the
 * data is right there, one server's numbers under another server's name.
 */
class WidgetModelSelectionTest {

    // --- An update arriving at a process that may have just started ----------------------------

    @Test
    fun anUnreadableConfigurationSaysSo() {
        val model = WidgetModelSelection.fromStorage(StoredInstances.Unreadable, emptyList(), NOW)

        assertEquals(WidgetRenderModel.ConfigurationUnreadable, model)
    }

    @Test
    fun noStoredInstanceMeansNotSetUp() {
        val model = WidgetModelSelection.fromStorage(readable(), emptyList(), NOW)

        assertEquals(WidgetRenderModel.NotSetUp, model)
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
        val snapshot = snapshot(active = 35, failed = 2)

        val model = WidgetModelSelection.fromStorage(
            stored = readable(valid()),
            views = listOf(view(snapshot = snapshot)),
            nowEpochMs = NOW,
        )

        val instance = model as WidgetRenderModel.Instance
        assertEquals(TITLE, instance.title)
        assertEquals("▲35 ▼2", instance.counts)
        assertEquals(NOW, instance.updatedAtEpochMs)
        assertEquals(WidgetViewState.Display(WidgetDisplayState.Ok), instance.state)
    }

    /** A notice in memory reaches the widget as a notice, not as missing data. */
    @Test
    fun aNoticeInMemoryIsUsedRatherThanReportedAsMissing() {
        val model = WidgetModelSelection.fromStorage(
            stored = readable(valid()),
            views = listOf(
                view(snapshot = null, state = WidgetViewState.Notice(InstanceNotice.DISABLED)),
            ),
            nowEpochMs = NOW,
        )

        val instance = model as WidgetRenderModel.Instance
        assertEquals(WidgetViewState.Notice(InstanceNotice.DISABLED), instance.state)
    }

    /** Without anything in memory, the old path still applies and says what is true. */
    @Test
    fun withoutDataInMemoryTheWidgetSaysNothingHasBeenRetrieved() {
        val model = WidgetModelSelection.fromStorage(readable(valid()), emptyList(), NOW)

        val instance = model as WidgetRenderModel.Instance
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
        val model = WidgetModelSelection.fromStorage(
            stored = readable(valid()),
            views = listOf(
                view(
                    instanceId = OTHER_INSTANCE_ID,
                    title = "Somewhere else",
                    snapshot = snapshot(active = 9, failed = 9),
                ),
            ),
            nowEpochMs = NOW,
        )

        val instance = model as WidgetRenderModel.Instance
        assertEquals(TITLE, instance.title)
        assertEquals("", instance.counts)
        assertEquals(WidgetViewState.Display(WidgetDisplayState.Loading), instance.state)
    }

    @Test
    fun aStoredEntryThatNoLongerValidatesIsStillNamed() {
        val model = WidgetModelSelection.fromStorage(
            stored = readable(StoredInstance.Invalid(INSTANCE_ID, "Kuma at home", null)),
            views = emptyList(),
            nowEpochMs = NOW,
        )

        assertEquals("Kuma at home", (model as WidgetRenderModel.Instance).title)
    }

    /** An invalid entry may have lost its name; then the identifier is all there is. */
    @Test
    fun anInvalidEntryWithoutANameFallsBackToItsIdentifier() {
        val model = WidgetModelSelection.fromStorage(
            stored = readable(StoredInstance.Invalid(INSTANCE_ID, null, null)),
            views = emptyList(),
            nowEpochMs = NOW,
        )

        assertEquals(INSTANCE_ID.value, (model as WidgetRenderModel.Instance).title)
    }

    @Test
    fun aBlankNameIsTreatedAsNoName() {
        val model = WidgetModelSelection.fromStorage(
            stored = readable(StoredInstance.Invalid(INSTANCE_ID, "   ", null)),
            views = emptyList(),
            nowEpochMs = NOW,
        )

        assertEquals(INSTANCE_ID.value, (model as WidgetRenderModel.Instance).title)
    }

    // --- A push from the running application ---------------------------------------------------

    @Test
    fun theRunningApplicationReportsAnUnreadableConfiguration() {
        val model = WidgetModelSelection.fromMemory(
            configurationUnreadable = true,
            views = emptyList(),
            nowEpochMs = NOW,
        )

        assertEquals(WidgetRenderModel.ConfigurationUnreadable, model)
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
        val model = WidgetModelSelection.fromMemory(false, emptyList(), NOW)

        assertEquals(WidgetRenderModel.NotSetUp, model)
    }

    @Test
    fun theRunningApplicationRendersWhatItHolds() {
        val model = WidgetModelSelection.fromMemory(
            configurationUnreadable = false,
            views = listOf(view(snapshot = snapshot(active = 7, failed = 0))),
            nowEpochMs = NOW,
        )

        val instance = model as WidgetRenderModel.Instance
        assertEquals("▲7 ▼0", instance.counts)
        assertEquals(NOW, instance.updatedAtEpochMs)
    }

    /** An unreadable configuration outranks anything left in memory from before. */
    @Test
    fun anUnreadableConfigurationOutranksWhatIsLeftInMemory() {
        val model = WidgetModelSelection.fromMemory(
            configurationUnreadable = true,
            views = listOf(view(snapshot = snapshot(active = 7, failed = 0))),
            nowEpochMs = NOW,
        )

        assertEquals(WidgetRenderModel.ConfigurationUnreadable, model)
    }

    // --- Helpers -------------------------------------------------------------------------------

    private fun readable(vararg instances: StoredInstance) =
        StoredInstances.Readable(instances.toList())

    private fun valid(): StoredInstance.Valid = StoredInstance.Valid(
        InstanceConfig(
            instanceId = INSTANCE_ID,
            displayName = TITLE,
            baseUrl = BASE_URL,
            enabled = true,
        ),
    )

    private fun view(
        instanceId: InstanceId = INSTANCE_ID,
        title: String = TITLE,
        snapshot: WidgetSnapshot?,
        state: WidgetViewState = WidgetViewState.Display(WidgetDisplayState.Ok),
    ): WidgetView = WidgetView(
        instanceId = instanceId,
        widgetId = KumaSnapshotMapper.summaryWidgetId(instanceId),
        title = title,
        snapshot = snapshot,
        state = state,
    )

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

    private fun entry(id: Int, state: MonitorState): MonitorEntry = MonitorEntry.truncating(
        monitorId = "m$id",
        name = "monitor-$id.example.invalid",
        state = state,
        responseTimeMs = null,
    )

    private companion object {

        val INSTANCE_ID = InstanceId("kuma")
        val OTHER_INSTANCE_ID = InstanceId("kuma-two")
        const val TITLE = "Uptime Kuma"
        const val BASE_URL = "https://kuma.example.invalid"

        /** A fixed instant, so nothing in this file depends on when it runs. */
        const val NOW = 1_700_000_000_000L
    }
}
