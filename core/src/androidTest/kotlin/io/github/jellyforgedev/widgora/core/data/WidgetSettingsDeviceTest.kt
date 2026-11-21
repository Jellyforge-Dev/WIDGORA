package io.github.jellyforgedev.widgora.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jellyforgedev.widgora.model.InstanceId
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The per-widget settings against a real file, which is the part no unit test can answer.
 *
 * Three questions need a device. Whether what was written survives being read by a repository that
 * did not write it — the ordinary situation once the process has been restarted for a widget
 * update. Whether a file this version cannot parse is really discarded rather than raising into
 * whatever asked. And whether writing here leaves the instance file, which obeys the opposite
 * rule, untouched.
 *
 * It does not run in continuous integration, like the other instrumented tests in this module.
 */
@RunWith(AndroidJUnit4::class)
class WidgetSettingsDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A file of this run's own, so two runs cannot read each other's leftovers. */
    private val file = File(
        context.cacheDir,
        "widget-settings-" + System.nanoTime() + ".preferences_pb",
    )

    @After
    fun removeWhatTheTestCreated() {
        file.delete()
        WidgetSettingsRepository.fileIn(context).delete()
    }

    /**
     * Written, then read by a repository that never saw the write.
     *
     * A second instance over the same file is what the application has after its process was
     * reclaimed and started again for a widget update: nothing is in memory, and everything has to
     * come off the disk.
     *
     * The first store's scope is ended before the second is built, and that is not tidiness — the
     * store refuses to be a second live instance over a file another one holds, loudly. Ending the
     * scope is what makes this a restart rather than two stores fighting, and the same rule is why
     * the application keeps exactly one of these.
     */
    @Test
    fun settingsSurviveARepositoryThatDidNotWriteThem() = runBlocking {
        val written = WidgetSettings(
            kind = WidgetKind.SELECTION,
            instanceId = INSTANCE,
            showNames = true,
            monitorIds = listOf("14", "3", "7"),
        )
        assertEquals(StorageWriteResult.DONE, withStore(file) { it.save(WIDGET, written) })

        val read = requireNotNull(withStore(file) { it.settings.first()[WIDGET] }) {
            "nothing was read back for the placement"
        }

        assertEquals(WidgetKind.SELECTION, read.kind)
        assertEquals(INSTANCE, read.instanceId)
        assertTrue(read.showNames)
        // The order came back as it went in, which is the one thing a set would have lost.
        assertEquals(listOf("14", "3", "7"), read.monitorIds)
    }

    /**
     * A file this version cannot parse is thrown away, and the widgets read as not set up.
     *
     * This is the rule that is the **opposite** of the instance file's, and the reason the two are
     * separate files. Nothing here is the user's to lose — a kind, an instance identifier, a
     * switch, a few item identifiers, every one of them asked again in the configuration step — so
     * replacing a damaged copy is cheap, and reporting it would be noise. The instance file holds
     * an address and an encrypted credential and is never replaced on the application's own
     * initiative.
     *
     * The damage is written as bytes rather than simulated, because what is under test is the
     * store's behaviour on a file it cannot read and not this test's idea of one.
     */
    @Test
    fun aDamagedFileIsDiscardedRatherThanReported() = runBlocking {
        withStore(file) { it.save(WIDGET, WidgetSettings(WidgetKind.STATUS, INSTANCE)) }
        assertTrue("nothing was written to damage", file.exists())

        file.writeBytes(NOT_A_PREFERENCE_FILE)

        withStore(file) { afterDamage ->
            // No exception, and no settings: the entry went with the file it was in.
            assertEquals(emptyMap<Int, WidgetSettings>(), afterDamage.settings.first())
            assertNull(afterDamage.settingsFor(WIDGET))
            assertEquals(emptySet<Int>(), afterDamage.storedIds())
        }
    }

    /** And it is usable again straight away: the replacement is a working, empty file. */
    @Test
    fun aDamagedFileIsUsableAgainAfterItIsDiscarded() = runBlocking {
        file.writeBytes(NOT_A_PREFERENCE_FILE)

        withStore(file) { repository ->
            assertEquals(
                StorageWriteResult.DONE,
                repository.save(WIDGET, WidgetSettings(WidgetKind.CERTIFICATES, INSTANCE)),
            )
            val stored = requireNotNull(repository.settingsFor(WIDGET)) {
                "the replacement file took no write"
            }
            assertEquals(WidgetKind.CERTIFICATES, stored.kind)
        }
    }

    /**
     * Writing widget settings leaves every other file of the application alone.
     *
     * The claim the separation rests on, so it is checked against the real directory and the real
     * factory rather than against a path this test chose. Every other file is compared by its
     * bytes: naming the instance file would only prove that one name differs, while this also
     * catches a store writing somewhere nobody expected.
     */
    @Test
    fun theWidgetFileIsTheOnlyFileThatChanges() = runBlocking {
        val widgetFile = WidgetSettingsRepository.fileIn(context)
        widgetFile.delete()
        val before = snapshotOfFilesDir()
        assertFalse("the widget file was already there", widgetFile.name in before.keys)

        WidgetSettingsRepository.create(context)
            .save(WIDGET, WidgetSettings(WidgetKind.STATUS, INSTANCE))

        assertTrue("the settings were not written at all", widgetFile.exists())
        val after = snapshotOfFilesDir() - widgetFile.name
        assertEquals(before.keys, after.keys)
        for ((name, bytes) in before) {
            assertTrue("$name changed", bytes.contentEquals(after.getValue(name)))
        }
    }

    /** Name and contents of every file the application holds, for comparison. */
    private fun snapshotOfFilesDir(): Map<String, ByteArray> =
        context.filesDir.listFiles().orEmpty()
            .filter { candidate -> candidate.isFile }
            .associate { candidate -> candidate.name to candidate.readBytes() }

    /**
     * Runs [block] against a store over [target], then ends that store's life.
     *
     * The cancellation is the point. Two live stores over one file raise, so a test that wants to
     * read what an earlier store wrote has to let the earlier one go first — which is also what
     * makes the next store's read come off the disk rather than out of memory.
     */
    private suspend fun <T> withStore(
        target: File,
        block: suspend (WidgetSettingsRepository) -> T,
    ): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return try {
            block(WidgetSettingsRepository.create(target, scope))
        } finally {
            scope.cancel()
        }
    }

    private companion object {

        const val WIDGET = 7

        val INSTANCE = InstanceId("kuma")

        /** Bytes no preference file could be, so the store has to fall back to its handler. */
        val NOT_A_PREFERENCE_FILE = "this is not a preference file".toByteArray()
    }
}
