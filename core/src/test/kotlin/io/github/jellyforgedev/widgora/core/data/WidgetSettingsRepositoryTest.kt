package io.github.jellyforgedev.widgora.core.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jellyforgedev.widgora.model.InstanceId
import java.io.IOException
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * What one placed widget was configured to show: written, read back, and refused where it must be.
 *
 * The stored key names appear in this file as literal strings on purpose. They are the storage
 * format, and a format nothing pins down is one a refactoring can rename without a test noticing —
 * which would silently orphan every entry already on a device. Writing them out here is also what
 * lets a test store a value the repository itself would never write, which is the only way to
 * exercise the reading rules against a file that is already wrong.
 */
class WidgetSettingsRepositoryTest {

    // --- Writing and reading -------------------------------------------------------------------

    @Test
    fun aStoredSelectionComesBackWithItsOrderIntact() = runBlocking {
        val world = World()

        world.repository.save(
            WIDGET,
            WidgetSettings(
                kind = WidgetKind.SELECTION,
                instanceId = INSTANCE,
                showNames = true,
                monitorIds = listOf("14", "3", "7"),
            ),
        )

        val stored = assertNotNull(world.repository.settingsFor(WIDGET))
        assertEquals(WidgetKind.SELECTION, stored.kind)
        assertEquals(INSTANCE, stored.instanceId)
        assertTrue(stored.showNames)
        // The order is the user's and part of the value. A set would have lost it.
        assertEquals(listOf("14", "3", "7"), stored.monitorIds)
    }

    @Test
    fun eachPlacementIsStoredOnItsOwn() = runBlocking {
        val world = World()

        world.repository.save(WIDGET, WidgetSettings(WidgetKind.STATUS, INSTANCE))
        world.repository.save(
            OTHER_WIDGET,
            WidgetSettings(WidgetKind.CERTIFICATES, INSTANCE, showNames = true),
        )

        val all = world.all()
        assertEquals(setOf(WIDGET, OTHER_WIDGET), all.keys)
        assertEquals(WidgetKind.STATUS, all.getValue(WIDGET).kind)
        assertFalse(all.getValue(WIDGET).showNames)
        assertEquals(WidgetKind.CERTIFICATES, all.getValue(OTHER_WIDGET).kind)
        assertTrue(all.getValue(OTHER_WIDGET).showNames)
    }

    /** Saving again replaces the entry rather than merging into it. */
    @Test
    fun savingAgainLeavesNothingOfThePreviousAnswers() = runBlocking {
        val world = World()

        world.repository.save(
            WIDGET,
            WidgetSettings(WidgetKind.SELECTION, INSTANCE, showNames = true, monitorIds = ONE_ID),
        )
        world.repository.save(WIDGET, WidgetSettings(WidgetKind.STATUS, INSTANCE))

        val stored = assertNotNull(world.repository.settingsFor(WIDGET))
        assertEquals(WidgetKind.STATUS, stored.kind)
        assertFalse(stored.showNames)
        assertEquals(emptyList(), stored.monitorIds)
    }

    /** Names are off until the user says otherwise, so no stored answer is the same as a no. */
    @Test
    fun anAbsentNamesAnswerMeansNamesAreOff() = runBlocking {
        val world = World()
        world.store(kind = "STATUS", instanceId = INSTANCE.value, showNames = null)

        assertFalse(assertNotNull(world.repository.settingsFor(WIDGET)).showNames)
    }

    @Test
    fun nothingStoredIsNotAnEntry() = runBlocking {
        assertNull(World().repository.settingsFor(WIDGET))
    }

    // --- Refusing what cannot be honoured ------------------------------------------------------

    /**
     * A kind this version does not know makes the widget unconfigured, not a widget of some other
     * kind. Falling back would show a counts widget to somebody who placed a certificate one.
     */
    @Test
    fun anUnknownKindMakesTheEntryUnusable() = runBlocking {
        val world = World()
        world.store(kind = "HISTOGRAM", instanceId = INSTANCE.value)

        assertNull(world.repository.settingsFor(WIDGET))
    }

    @Test
    fun aMissingInstanceMakesTheEntryUnusable() = runBlocking {
        val world = World()
        world.store(kind = "STATUS", instanceId = null)

        assertNull(world.repository.settingsFor(WIDGET))
    }

    @Test
    fun anInstanceIdentifierTheRulesRejectMakesTheEntryUnusable() = runBlocking {
        val world = World()
        world.store(kind = "STATUS", instanceId = "Not An Identifier")

        assertNull(world.repository.settingsFor(WIDGET))
    }

    /**
     * More items than the ceiling allows is refused rather than cut to size.
     *
     * Cutting would be the application deciding which twenty of somebody's hundred choices matter.
     * The entry is unusable and the widget says it is not set up, which the configuration step can
     * put right.
     */
    @Test
    fun tooManyItemsMakeTheEntryUnusable() = runBlocking {
        val world = World()
        val tooMany = (1..WidgetSettings.MAX_SELECTED_MONITORS + 1).map { it.toString() }
        world.store(
            kind = "SELECTION",
            instanceId = INSTANCE.value,
            monitorIds = tooMany.joinToString(WidgetSettings.MONITOR_ID_SEPARATOR),
        )

        assertNull(world.repository.settingsFor(WIDGET))

        // Exactly the ceiling is still fine, so this is a test about the limit and not about the
        // feature failing in general.
        world.store(
            kind = "SELECTION",
            instanceId = INSTANCE.value,
            monitorIds = tooMany.dropLast(1).joinToString(WidgetSettings.MONITOR_ID_SEPARATOR),
        )
        assertEquals(
            WidgetSettings.MAX_SELECTED_MONITORS,
            assertNotNull(world.repository.settingsFor(WIDGET)).monitorIds.size,
        )
    }

    @Test
    fun anItemIdentifierOfAnUnexpectedShapeMakesTheEntryUnusable() = runBlocking {
        for (bad in listOf("has space", "slash/inside", "", "a".repeat(65), "new\nline")) {
            val world = World()
            world.store(kind = "SELECTION", instanceId = INSTANCE.value, monitorIds = bad)

            assertNull(world.repository.settingsFor(WIDGET), bad)
        }
    }

    /**
     * Items on a widget that is not a selection is a contradiction, so the entry is unusable.
     *
     * It cannot arise from a write this class made. Honouring the half that makes sense would mean
     * guessing which half the user meant.
     */
    @Test
    fun itemsOnAWidgetThatIsNotASelectionMakeTheEntryUnusable() = runBlocking {
        val world = World()
        world.store(kind = "STATUS", instanceId = INSTANCE.value, monitorIds = "3")

        assertNull(world.repository.settingsFor(WIDGET))
    }

    @Test
    fun oneUnusableEntryDoesNotHideTheOthers() = runBlocking {
        val world = World()
        world.repository.save(WIDGET, WidgetSettings(WidgetKind.STATUS, INSTANCE))
        world.store(appWidgetId = OTHER_WIDGET, kind = "HISTOGRAM", instanceId = INSTANCE.value)

        assertEquals(setOf(WIDGET), world.all().keys)
        // Its keys are still there; it is the reading that refuses it, not the file that lost it.
        assertTrue(OTHER_WIDGET in world.repository.storedIds())
    }

    /** The value type refuses the same things at construction, so a bad entry cannot be saved. */
    @Test
    fun theValueTypeRefusesWhatTheReaderRefuses() {
        assertFailsWith<IllegalArgumentException> {
            WidgetSettings(WidgetKind.STATUS, INSTANCE, monitorIds = ONE_ID)
        }
        assertFailsWith<IllegalArgumentException> {
            WidgetSettings(
                WidgetKind.SELECTION,
                INSTANCE,
                monitorIds = (0..WidgetSettings.MAX_SELECTED_MONITORS).map { it.toString() },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WidgetSettings(WidgetKind.SELECTION, INSTANCE, monitorIds = listOf("has space"))
        }
    }

    /** The separator is not storable, which is what makes the join reversible by construction. */
    @Test
    fun theSeparatorIsNotAStorableIdentifierCharacter() {
        assertFalse(WidgetSettings.isStorableMonitorId(WidgetSettings.MONITOR_ID_SEPARATOR))
        assertFalse(WidgetSettings.isStorableMonitorId("3,4"))
        assertTrue(WidgetSettings.isStorableMonitorId("3"))
        assertTrue(WidgetSettings.isStorableMonitorId("sensor.front_door:1"))
    }

    // --- Nothing secret or topological can be stored here --------------------------------------

    /**
     * The field list of the stored type, frozen.
     *
     * A widget points at an instance by identifier and by nothing else. A field for a credential,
     * an address or a host name would put a second copy of exactly the material the instance store
     * keeps in one place into a file that may be discarded without asking — so gaining one has to
     * be a decision somebody makes in review, which is what this test forces.
     */
    @Test
    fun theStoredTypeHasNoFieldForACredentialAnAddressOrAName() {
        val actual = WidgetSettings::class.java.declaredFields
            .filterNot { field -> field.isSynthetic || Modifier.isStatic(field.modifiers) }
            .map { field -> field.name }
            .sorted()

        assertEquals(listOf("instanceId", "kind", "monitorIds", "showNames"), actual)
    }

    // --- Cleanup -------------------------------------------------------------------------------

    @Test
    fun deletingAPlacementRemovesEveryKeyItHad() = runBlocking {
        val world = World()
        world.repository.save(
            WIDGET,
            WidgetSettings(WidgetKind.SELECTION, INSTANCE, showNames = true, monitorIds = ONE_ID),
        )

        assertEquals(StorageWriteResult.DONE, world.repository.delete(WIDGET))

        assertNull(world.repository.settingsFor(WIDGET))
        assertEquals(emptySet(), world.repository.storedIds())
    }

    @Test
    fun theSweepRemovesOnlyThePlacementsThatAreGone() = runBlocking {
        val world = World()
        world.repository.save(WIDGET, WidgetSettings(WidgetKind.STATUS, INSTANCE))
        world.repository.save(OTHER_WIDGET, WidgetSettings(WidgetKind.STATUS, INSTANCE))

        assertEquals(StorageWriteResult.DONE, world.repository.removeOrphans(setOf(WIDGET)))

        assertEquals(setOf(WIDGET), world.repository.storedIds())
        assertNotNull(world.repository.settingsFor(WIDGET))
    }

    /** With no widget left, everything stored is an orphan. */
    @Test
    fun theSweepClearsEverythingWhenNoWidgetIsPlaced() = runBlocking {
        val world = World()
        world.repository.save(WIDGET, WidgetSettings(WidgetKind.STATUS, INSTANCE))

        world.repository.removeOrphans(emptySet())

        assertEquals(emptySet(), world.repository.storedIds())
    }

    /**
     * A sweep with nothing to do does not write.
     *
     * It runs on every widget update, so this is the difference between a cleanup and a steady
     * stream of writes to a file. What is stored afterwards is the same either way, which is why
     * it has to be counted rather than looked at.
     */
    @Test
    fun aSweepWithNothingToRemoveWritesNothing() = runBlocking {
        val world = World()
        world.repository.save(WIDGET, WidgetSettings(WidgetKind.STATUS, INSTANCE))
        val writesAfterSave = world.preferences.writes

        world.repository.removeOrphans(setOf(WIDGET))

        assertEquals(writesAfterSave, world.preferences.writes)
    }

    /** An entry the reading refuses is still swept: it belongs to a widget that is gone. */
    @Test
    fun theSweepReachesEntriesTheReadingRefuses() = runBlocking {
        val world = World()
        world.store(kind = "HISTOGRAM", instanceId = INSTANCE.value)

        world.repository.removeOrphans(emptySet())

        assertEquals(emptySet(), world.repository.storedIds())
    }

    // --- A file that cannot be read ------------------------------------------------------------

    /**
     * An unreadable file leaves no settings, and never raises into a collector.
     *
     * The opposite of the instance store's rule, and deliberately so: nothing in this file is the
     * user's to lose, so "no settings" is the truth rather than a comfortable fiction. The widgets
     * then say they are not set up, which the configuration step can put right.
     */
    @Test
    fun anUnreadableFileLeavesNoSettings() = runBlocking {
        val world = World()
        world.repository.save(WIDGET, WidgetSettings(WidgetKind.STATUS, INSTANCE))
        world.preferences.failWith = IOException("damaged")

        assertEquals(emptyMap(), world.all())
        assertNull(world.repository.settingsFor(WIDGET))
        assertEquals(emptySet(), world.repository.storedIds())
    }

    /**
     * A write that cannot happen is reported, never raised.
     *
     * A damaged file is not the case being exercised here, and could not be: this store replaces a
     * damaged copy and writes on, which is its whole point. What remains reachable is a medium that
     * refuses the write — full, or no longer writable — and a configuration step has to be able to
     * say so rather than show the user answers that were never kept.
     */
    @Test
    fun aWriteThatCannotHappenIsReportedAndNotRaised() = runBlocking {
        val world = World()
        world.preferences.failWritesWith = IOException("medium full")

        assertEquals(
            StorageWriteResult.FAILED,
            world.repository.save(WIDGET, WidgetSettings(WidgetKind.STATUS, INSTANCE)),
        )
        assertEquals(StorageWriteResult.FAILED, world.repository.delete(WIDGET))
    }

    /** The pieces one test needs. */
    private class World {

        val preferences = InMemoryPreferences()
        val repository = WidgetSettingsRepository(preferences)

        suspend fun all(): Map<Int, WidgetSettings> = repository.settings.first()

        /**
         * Writes raw values, including ones the repository would refuse to write.
         *
         * That is the point. The reading rules exist for a file that is already wrong — written by
         * another version, or edited — and they cannot be exercised through a writer that refuses
         * to produce such a file.
         */
        suspend fun store(
            appWidgetId: Int = WIDGET,
            kind: String?,
            instanceId: String?,
            showNames: Boolean? = false,
            monitorIds: String? = null,
        ) {
            preferences.updateData { existing ->
                existing.toMutablePreferences().apply {
                    kind?.let { this[stringPreferencesKey("widget.$appWidgetId.kind")] = it }
                    instanceId?.let {
                        this[stringPreferencesKey("widget.$appWidgetId.instanceId")] = it
                    }
                    showNames?.let {
                        this[booleanPreferencesKey("widget.$appWidgetId.showNames")] = it
                    }
                    monitorIds?.let {
                        this[stringPreferencesKey("widget.$appWidgetId.monitorIds")] = it
                    }
                }
            }
        }
    }

    private companion object {

        const val WIDGET = 7
        const val OTHER_WIDGET = 13

        val INSTANCE = InstanceId("kuma")
        val ONE_ID = listOf("3")
    }
}
