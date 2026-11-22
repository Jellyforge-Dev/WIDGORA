package io.github.jellyforgedev.widgora.widget.config

import io.github.jellyforgedev.widgora.core.data.InstanceConfig
import io.github.jellyforgedev.widgora.core.data.StorageWriteResult
import io.github.jellyforgedev.widgora.core.data.StoredInstance
import io.github.jellyforgedev.widgora.core.data.StoredInstances
import io.github.jellyforgedev.widgora.core.data.WidgetKind
import io.github.jellyforgedev.widgora.core.data.WidgetSettings
import io.github.jellyforgedev.widgora.model.InstanceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/**
 * The questions the configuration step asks, and what it stores.
 *
 * Every case here is one a user would notice. Pre-selecting nothing would mean a screen that
 * cannot be saved; defaulting the consent to yes would mean names appearing without anybody
 * agreeing; reporting success after a failed write would mean a host told a widget is configured
 * while nothing reached the file.
 */
class WidgetConfigStateHolderTest {

    @Test
    fun theTypeIsKnownBeforeAnythingIsRead() {
        val holder = holder(kind = WidgetKind.CERTIFICATES)

        assertEquals(WidgetKind.CERTIFICATES, holder.state.value.kind)
        assertFalse(holder.state.value.loaded)
    }

    /**
     * With one instance it is pre-selected, which is the whole of "pre-selected when only one
     * exists" — and the question is still asked, because that is what points a widget at *an*
     * instance rather than at whichever one comes first.
     */
    @Test
    fun theOnlyInstanceIsPreSelected() = runBlocking {
        val holder = holder()

        holder.load()

        val state = holder.state.value
        assertEquals(listOf(INSTANCE_ID), state.instances.map { it.instanceId })
        assertEquals(INSTANCE_ID, state.selectedInstanceId)
        assertTrue(state.canSave)
    }

    @Test
    fun theConsentIsOffUnlessSomethingSaysOtherwise() = runBlocking {
        val holder = holder()

        holder.load()

        assertFalse(holder.state.value.showNames)
    }

    @Test
    fun anInstanceIsNamedByItsDisplayName() = runBlocking {
        val holder = holder()

        holder.load()

        assertEquals(TITLE, holder.state.value.instances.single().label)
    }

    /**
     * An entry the current rules reject is still offered.
     *
     * Hiding it would leave the user unable to point a widget at an instance they can see in the
     * application's own settings.
     */
    @Test
    fun anInvalidEntryIsStillOfferedAndNamed() = runBlocking {
        val holder = holder(
            stored = StoredInstances.Readable(
                listOf(StoredInstance.Invalid(INSTANCE_ID, "Kuma at home", null)),
            ),
        )

        holder.load()

        assertEquals("Kuma at home", holder.state.value.instances.single().label)
    }

    /** An invalid entry without a name falls back to its identifier, as the dashboard does. */
    @Test
    fun anInvalidEntryWithoutANameFallsBackToItsIdentifier() = runBlocking {
        val holder = holder(
            stored = StoredInstances.Readable(
                listOf(StoredInstance.Invalid(INSTANCE_ID, null, null)),
            ),
        )

        holder.load()

        assertEquals(INSTANCE_ID.value, holder.state.value.instances.single().label)
    }

    @Test
    fun withoutAnyInstanceThereIsNothingToSave() = runBlocking {
        val holder = holder(stored = StoredInstances.Readable(emptyList()))

        holder.load()

        assertTrue(holder.state.value.instances.isEmpty())
        assertNull(holder.state.value.selectedInstanceId)
        assertFalse(holder.state.value.canSave)
    }

    /** An unreadable configuration is said so rather than looking like a fresh installation. */
    @Test
    fun anUnreadableConfigurationIsReported() = runBlocking {
        val holder = holder(stored = StoredInstances.Unreadable)

        holder.load()

        assertTrue(holder.state.value.configurationUnreadable)
        assertFalse(holder.state.value.canSave)
    }

    // --- Reconfiguration -----------------------------------------------------------------------

    /**
     * A widget being set up again opens on the answers it already has.
     *
     * Opening on the defaults instead would quietly offer to turn the consent off every time the
     * screen is reached, and a user confirming the form would be making a change they never
     * intended.
     */
    @Test
    fun aReconfigurationOpensOnTheStoredAnswers() = runBlocking {
        val store = FakeStore(
            stored = mapOf(
                APP_WIDGET_ID to WidgetSettings(
                    kind = WidgetKind.STATUS,
                    instanceId = OTHER_INSTANCE_ID,
                    showNames = true,
                ),
            ),
        )
        val holder = holder(
            store = store,
            stored = StoredInstances.Readable(listOf(valid(), valid(OTHER_INSTANCE_ID, "Other"))),
        )

        holder.load()

        assertEquals(OTHER_INSTANCE_ID, holder.state.value.selectedInstanceId)
        assertTrue(holder.state.value.showNames)
    }

    /**
     * A stored instance that is gone does not leave the screen unsaveable.
     *
     * The user has to be able to repair the widget, and the repair is to choose an instance that
     * exists — so the first one is offered as a starting point rather than nothing at all.
     */
    @Test
    fun aStoredInstanceThatIsGoneFallsBackToOneThatExists() = runBlocking {
        val store = FakeStore(
            stored = mapOf(
                APP_WIDGET_ID to WidgetSettings(
                    kind = WidgetKind.STATUS,
                    instanceId = OTHER_INSTANCE_ID,
                ),
            ),
        )
        val holder = holder(store = store)

        holder.load()

        assertEquals(INSTANCE_ID, holder.state.value.selectedInstanceId)
    }

    // --- Changing the answers ------------------------------------------------------------------

    @Test
    fun anInstanceCanBeChosen() = runBlocking {
        val holder = holder(
            stored = StoredInstances.Readable(listOf(valid(), valid(OTHER_INSTANCE_ID, "Other"))),
        )
        holder.load()

        holder.selectInstance(OTHER_INSTANCE_ID)

        assertEquals(OTHER_INSTANCE_ID, holder.state.value.selectedInstanceId)
    }

    /** An instance that is not on the list cannot be chosen by a stray call. */
    @Test
    fun anInstanceThatIsNotOfferedCannotBeChosen() = runBlocking {
        val holder = holder()
        holder.load()

        holder.selectInstance(OTHER_INSTANCE_ID)

        assertEquals(INSTANCE_ID, holder.state.value.selectedInstanceId)
    }

    @Test
    fun theConsentCanBeGivenAndTakenBack() = runBlocking {
        val holder = holder()
        holder.load()

        holder.setShowNames(true)
        assertTrue(holder.state.value.showNames)

        holder.setShowNames(false)
        assertFalse(holder.state.value.showNames)
    }

    // --- Saving --------------------------------------------------------------------------------

    @Test
    fun savingWritesOneCompleteEntry() = runBlocking {
        val store = FakeStore()
        val holder = holder(store = store)
        holder.load()
        holder.setShowNames(true)

        assertTrue(holder.save())

        assertEquals(
            WidgetSettings(
                kind = WidgetKind.STATUS,
                instanceId = INSTANCE_ID,
                showNames = true,
                monitorIds = emptyList(),
            ),
            store.stored[APP_WIDGET_ID],
        )
    }

    /** The entry is written under the identifier this screen was started for and no other. */
    @Test
    fun theEntryIsWrittenUnderTheWidgetsOwnIdentifier() = runBlocking {
        val store = FakeStore()
        val holder = holder(store = store)
        holder.load()

        holder.save()

        assertEquals(setOf(APP_WIDGET_ID), store.stored.keys)
    }

    /** Without an instance nothing is written, and nothing is reported as written. */
    @Test
    fun withoutAnInstanceNothingIsWritten() = runBlocking {
        val store = FakeStore()
        val holder = holder(store = store, stored = StoredInstances.Readable(emptyList()))
        holder.load()

        assertFalse(holder.save())

        assertTrue(store.stored.isEmpty())
    }

    /**
     * A write that failed is reported as failed.
     *
     * This is what the activity gates `RESULT_OK` on: a host told the widget is configured while
     * nothing reached the file would leave a widget that keeps asking to be set up.
     */
    @Test
    fun aFailedWriteIsReportedAndNotSwallowed() = runBlocking {
        val store = FakeStore(outcome = StorageWriteResult.FAILED)
        val holder = holder(store = store)
        holder.load()

        assertFalse(holder.save())

        assertTrue(holder.state.value.saveFailed)
    }

    /** Changing an answer clears the failure, so the message belongs to the last attempt. */
    @Test
    fun changingAnAnswerClearsAFailureFromBefore() = runBlocking {
        val holder = holder(store = FakeStore(outcome = StorageWriteResult.FAILED))
        holder.load()
        holder.save()

        holder.setShowNames(true)

        assertFalse(holder.state.value.saveFailed)
    }

    // --- The certificates widget ---------------------------------------------------------------

    /**
     * Saving a certificates widget records the consent the screen asked for as a statement.
     *
     * There is no switch on that screen, and the stored yes is what one rule reads back — see
     * NameConsent. A widget of this kind stored with a no would be a widget that can show nothing.
     */
    @Test
    fun savingACertificatesWidgetRecordsTheConsentItAskedFor() = runBlocking {
        val store = FakeStore()
        val holder = holder(kind = WidgetKind.CERTIFICATES, store = store)
        holder.load()

        assertTrue(holder.save())

        assertEquals(
            WidgetSettings(
                kind = WidgetKind.CERTIFICATES,
                instanceId = INSTANCE_ID,
                showNames = true,
                monitorIds = emptyList(),
            ),
            store.stored[APP_WIDGET_ID],
        )
    }

    // --- The selection widget ------------------------------------------------------------------

    @Test
    fun theMonitorsOfTheChosenInstanceAreRead() = runBlocking {
        val catalogue = FakeCatalogue(mapOf(INSTANCE_ID to listOf(monitor("1"), monitor("2"))))
        val holder = holder(kind = WidgetKind.SELECTION, monitors = catalogue)
        holder.load()

        holder.loadMonitors(INSTANCE_ID)

        assertEquals(listOf(INSTANCE_ID), catalogue.asked)
        assertEquals(
            listOf("1", "2"),
            (holder.state.value.monitors as MonitorCatalogue.Monitors)
                .choices
                .map { it.monitorId },
        )
    }

    /** A list that could not be read is said so, and is not an empty list. */
    @Test
    fun aListThatCouldNotBeReadIsReportedAsSuch() = runBlocking {
        val holder = holder(kind = WidgetKind.SELECTION, monitors = FakeCatalogue())
        holder.load()

        holder.loadMonitors(INSTANCE_ID)

        assertEquals(MonitorCatalogue.Unavailable, holder.state.value.monitors)
        assertFalse(holder.state.value.canSave)
    }

    /** An instance that answered and has no monitors is a different case, and says so. */
    @Test
    fun anInstanceWithNoMonitorsIsAnEmptyListAndNotAFailure() = runBlocking {
        val holder = holder(
            kind = WidgetKind.SELECTION,
            monitors = FakeCatalogue(mapOf(INSTANCE_ID to emptyList())),
        )
        holder.load()

        holder.loadMonitors(INSTANCE_ID)

        assertEquals(MonitorCatalogue.Monitors(emptyList()), holder.state.value.monitors)
    }

    /** An identifier storage could not round-trip is not offered at all. */
    @Test
    fun anIdentifierThatCannotBeStoredIsNotOffered() = runBlocking {
        val holder = holder(
            kind = WidgetKind.SELECTION,
            monitors = FakeCatalogue(
                mapOf(INSTANCE_ID to listOf(monitor("1"), monitor("a,b"), monitor("with space"))),
            ),
        )
        holder.load()

        holder.loadMonitors(INSTANCE_ID)

        assertEquals(
            listOf("1"),
            (holder.state.value.monitors as MonitorCatalogue.Monitors)
                .choices
                .map { it.monitorId },
        )
    }

    @Test
    fun aMonitorIsPickedAndUnpickedInTheOrderItWasPicked() = runBlocking {
        val holder = selectionHolder(listOf("1", "2", "3"))

        holder.toggleMonitor("3")
        holder.toggleMonitor("1")
        assertEquals(listOf("3", "1"), holder.state.value.selectedMonitorIds)
        assertTrue(holder.state.value.canSave)

        holder.toggleMonitor("3")
        assertEquals(listOf("1"), holder.state.value.selectedMonitorIds)
    }

    /** Nothing picked is nothing to show and nothing to consent to, so there is nothing to save. */
    @Test
    fun aSelectionWidgetWithNothingPickedCannotBeSaved() = runBlocking {
        val store = FakeStore()
        val holder = selectionHolder(listOf("1"), store = store)

        assertFalse(holder.state.value.canSave)
        assertFalse(holder.save())
        assertTrue(store.stored.isEmpty())
    }

    /**
     * A pick beyond the stored limit is refused and says so.
     *
     * The alternatives are both worse: a press that does nothing reads as a broken remote, and
     * dropping the oldest pick would be the application deciding which monitor matters least.
     */
    @Test
    fun aPickBeyondTheLimitIsRefusedAndSaysSo() = runBlocking {
        val offered = (1..WidgetSettings.MAX_SELECTED_MONITORS + 1).map { it.toString() }
        val holder = selectionHolder(offered)

        for (monitorId in offered) holder.toggleMonitor(monitorId)

        assertEquals(
            WidgetSettings.MAX_SELECTED_MONITORS,
            holder.state.value.selectedMonitorIds.size,
        )
        assertTrue(holder.state.value.pickRefused)

        // And the refusal is about the last press rather than a state the screen gets stuck in.
        holder.toggleMonitor(offered.first())
        assertFalse(holder.state.value.pickRefused)
    }

    @Test
    fun savingASelectionWidgetStoresWhatWasPickedAndNoSwitch() = runBlocking {
        val store = FakeStore()
        val holder = selectionHolder(listOf("1", "2"), store = store)
        holder.toggleMonitor("2")

        assertTrue(holder.save())

        assertEquals(
            WidgetSettings(
                kind = WidgetKind.SELECTION,
                instanceId = INSTANCE_ID,
                showNames = false,
                monitorIds = listOf("2"),
            ),
            store.stored[APP_WIDGET_ID],
        )
    }

    /**
     * A picked monitor the instance no longer lists is dropped once the list has been read.
     *
     * The screen cannot offer it to be unpicked, so keeping it would keep an entry the user can
     * neither see nor remove — and it would count towards the limit for as long as the widget
     * exists.
     */
    @Test
    fun aPickedMonitorTheInstanceNoLongerListsIsDropped() = runBlocking {
        val store = FakeStore(
            stored = mapOf(
                APP_WIDGET_ID to WidgetSettings(
                    kind = WidgetKind.SELECTION,
                    instanceId = INSTANCE_ID,
                    monitorIds = listOf("1", "gone"),
                ),
            ),
        )
        val holder = holder(
            kind = WidgetKind.SELECTION,
            store = store,
            monitors = FakeCatalogue(mapOf(INSTANCE_ID to listOf(monitor("1")))),
        )

        holder.load()
        assertEquals(listOf("1", "gone"), holder.state.value.selectedMonitorIds)

        holder.loadMonitors(INSTANCE_ID)
        assertEquals(listOf("1"), holder.state.value.selectedMonitorIds)
    }

    /** A failed read drops nothing: a failed read is not evidence about anything. */
    @Test
    fun aFailedReadDoesNotDropWhatWasPicked() = runBlocking {
        val store = FakeStore(
            stored = mapOf(
                APP_WIDGET_ID to WidgetSettings(
                    kind = WidgetKind.SELECTION,
                    instanceId = INSTANCE_ID,
                    monitorIds = listOf("1"),
                ),
            ),
        )
        val holder = holder(
            kind = WidgetKind.SELECTION,
            store = store,
            monitors = FakeCatalogue(),
        )
        holder.load()

        holder.loadMonitors(INSTANCE_ID)

        assertEquals(listOf("1"), holder.state.value.selectedMonitorIds)
    }

    /** Another instance has other monitors, so a selection made against one does not travel. */
    @Test
    fun choosingAnotherInstanceClearsTheSelection() = runBlocking {
        val holder = holder(
            kind = WidgetKind.SELECTION,
            stored = StoredInstances.Readable(listOf(valid(), valid(OTHER_INSTANCE_ID, "Other"))),
            monitors = FakeCatalogue(mapOf(INSTANCE_ID to listOf(monitor("1")))),
        )
        holder.load()
        holder.loadMonitors(INSTANCE_ID)
        holder.toggleMonitor("1")

        holder.selectInstance(OTHER_INSTANCE_ID)

        assertTrue(holder.state.value.selectedMonitorIds.isEmpty())
        assertEquals(MonitorCatalogue.Loading, holder.state.value.monitors)
    }

    /** A monitor that is not on the list cannot be picked by a stray call. */
    @Test
    fun aMonitorThatIsNotOfferedCannotBePicked() = runBlocking {
        val holder = selectionHolder(listOf("1"))

        holder.toggleMonitor("other")

        assertTrue(holder.state.value.selectedMonitorIds.isEmpty())
    }

    // --- Helpers -------------------------------------------------------------------------------

    /** A loaded selection screen whose instance reports the given monitors. */
    private suspend fun selectionHolder(
        monitorIds: List<String>,
        store: WidgetSettingsStore = FakeStore(),
    ): WidgetConfigStateHolder {
        val holder = holder(
            kind = WidgetKind.SELECTION,
            store = store,
            monitors = FakeCatalogue(
                mapOf(INSTANCE_ID to monitorIds.map { monitorId -> monitor(monitorId) }),
            ),
        )
        holder.load()
        holder.loadMonitors(INSTANCE_ID)
        return holder
    }

    private fun monitor(monitorId: String) =
        MonitorChoice(monitorId = monitorId, label = "monitor-$monitorId")

    private fun holder(
        kind: WidgetKind = WidgetKind.STATUS,
        stored: StoredInstances = StoredInstances.Readable(listOf(valid())),
        store: WidgetSettingsStore = FakeStore(),
        monitors: MonitorCatalogueSource = FakeCatalogue(),
    ) = WidgetConfigStateHolder(
        appWidgetId = APP_WIDGET_ID,
        kind = kind,
        instances = flowOf(stored),
        settings = store,
        monitors = monitors,
    )

    private fun valid(
        instanceId: InstanceId = INSTANCE_ID,
        displayName: String = TITLE,
    ) = StoredInstance.Valid(
        InstanceConfig(
            instanceId = instanceId,
            displayName = displayName,
            baseUrl = BASE_URL,
            enabled = true,
        ),
    )

    /**
     * A monitor list in memory, or the refusal to produce one.
     *
     * It also records which instance it was asked about: a selection made against one server means
     * nothing on another, so asking about the right one is part of what is checked.
     */
    private class FakeCatalogue(
        private val monitors: Map<InstanceId, List<MonitorChoice>?> = emptyMap(),
    ) : MonitorCatalogueSource {

        var asked: List<InstanceId> = emptyList()
            private set

        override suspend fun monitorsOf(instanceId: InstanceId): List<MonitorChoice>? {
            asked = asked + instanceId
            return monitors[instanceId]
        }
    }

    /** A store in memory. It records what it was asked to write, which is what is checked. */
    private class FakeStore(
        var stored: Map<Int, WidgetSettings> = emptyMap(),
        private val outcome: StorageWriteResult = StorageWriteResult.DONE,
    ) : WidgetSettingsStore {

        override suspend fun settingsFor(appWidgetId: Int): WidgetSettings? = stored[appWidgetId]

        override suspend fun save(
            appWidgetId: Int,
            settings: WidgetSettings,
        ): StorageWriteResult {
            if (outcome == StorageWriteResult.DONE) {
                stored = stored + (appWidgetId to settings)
            }
            return outcome
        }
    }

    private companion object {

        const val APP_WIDGET_ID = 17
        val INSTANCE_ID = InstanceId("kuma")
        val OTHER_INSTANCE_ID = InstanceId("kuma-two")
        const val TITLE = "Uptime Kuma"
        const val BASE_URL = "https://kuma.example.invalid"
    }
}
