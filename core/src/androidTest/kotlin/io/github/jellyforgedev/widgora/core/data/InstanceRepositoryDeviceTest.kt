package io.github.jellyforgedev.widgora.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jellyforgedev.widgora.core.security.AndroidKeystoreSecretStore
import io.github.jellyforgedev.widgora.model.InstanceId
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The repository with the real preference file and the real keystore, which is the combination
 * no unit test can cover. It does not run in continuous integration.
 *
 * Each run writes its own file and uses its own keystore alias prefix, and removes both
 * afterwards.
 */
@RunWith(AndroidJUnit4::class)
class InstanceRepositoryDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val aliasPrefix = "widgora.test." + System.nanoTime() + "."
    private val file = File(context.cacheDir, "instances-" + System.nanoTime() + ".preferences_pb")
    private val secretStore = AndroidKeystoreSecretStore(aliasPrefix = aliasPrefix)
    private val repository = InstanceRepository(
        PreferenceDataStoreFactory.create(produceFile = { file }),
        secretStore,
    )

    @After
    fun removeWhatTheTestCreated() {
        secretStore.forget(INSTANCE_ID)
        file.delete()
    }

    @Test
    fun anInstanceAndItsSecretSurviveARoundTripThroughRealStorage() = runBlocking {
        repository.save(config(), API_KEY)

        assertEquals(listOf(StoredInstance.Valid(config())), repository.instances.first())
        val state = repository.secretOf(INSTANCE_ID)
        assertTrue(state.toString(), state is SecretState.Present)
        assertEquals(API_KEY, (state as SecretState.Present).apiKey)
    }

    /**
     * The file is read as bytes rather than through the store: what matters is what actually
     * landed on disk, not what the store reports back.
     */
    @Test
    fun theWrittenFileDoesNotContainThePlainKey() = runBlocking {
        repository.save(config(), API_KEY)

        assertTrue(file.exists())
        assertTrue(file.length() > 0)
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains(API_KEY))
        assertTrue(raw.contains("secret.ciphertext"))
    }

    @Test
    fun deletingRemovesTheEntryFromTheFileAndTheKeyFromTheKeystore() = runBlocking {
        repository.save(config(), API_KEY)

        repository.delete(INSTANCE_ID)

        assertEquals(emptyList<StoredInstance>(), repository.instances.first())
        assertEquals(SecretState.Missing, repository.secretOf(INSTANCE_ID))
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("https://kuma.example.invalid"))
    }

    private fun config(): InstanceConfig = InstanceConfig(
        instanceId = INSTANCE_ID,
        displayName = "Uptime Kuma",
        baseUrl = "https://kuma.example.invalid",
        enabled = true,
    )

    private companion object {
        val INSTANCE_ID = InstanceId("device-probe")
        const val API_KEY = "uk1_device_probe_secret"
    }
}
