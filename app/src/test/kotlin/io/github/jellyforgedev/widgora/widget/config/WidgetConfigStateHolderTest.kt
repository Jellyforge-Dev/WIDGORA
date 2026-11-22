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

    // --- Helpers -------------------------------------------------------------------------------

    private fun holder(
        kind: WidgetKind = WidgetKind.STATUS,
        stored: StoredInstances = StoredInstances.Readable(listOf(valid())),
        store: WidgetSettingsStore = FakeStore(),
    ) = WidgetConfigStateHolder(
        appWidgetId = APP_WIDGET_ID,
        kind = kind,
        instances = flowOf(stored),
        settings = store,
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
