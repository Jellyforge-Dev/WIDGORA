package io.github.jellyforgedev.widgora.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jellyforgedev.widgora.core.security.EncryptedSecret
import io.github.jellyforgedev.widgora.core.security.SecretStore
import io.github.jellyforgedev.widgora.model.InstanceId
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The repository against doubles: an in-memory preference store and a reversible fake secret
 * store. Both are necessary and sufficient here — the real keystore needs a device and has its
 * own instrumented tests, and faking the store is what makes it possible to look at exactly
 * what was written.
 */
class InstanceRepositoryTest {

    @Test
    fun savesAndListsAnInstance() = runBlocking {
        val repository = repository()

        repository.save(config(), API_KEY)

        assertEquals(listOf(config()), repository.instances.first())
    }

    @Test
    fun listsNothingWhenNothingWasSaved() = runBlocking {
        assertEquals(emptyList(), repository().instances.first())
    }

    @Test
    fun findsAnInstanceById() = runBlocking {
        val repository = repository()
        repository.save(config(), API_KEY)

        assertEquals(config(), repository.instance(InstanceId("home-kuma")))
        assertNull(repository.instance(InstanceId("other")))
    }

    @Test
    fun listsSeveralInstancesInAStableOrder() = runBlocking {
        val repository = repository()
        repository.save(config(id = "second"), API_KEY)
        repository.save(config(id = "first"), API_KEY)

        assertEquals(
            listOf("first", "second"),
            repository.instances.first().map { it.instanceId.value },
        )
    }

    /** The guarantee that matters most: the key itself is never written to the store. */
    @Test
    fun thePlainKeyIsNeverWritten() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val repository = InstanceRepository(store, FakeSecretStore())

        repository.save(config(), API_KEY)

        val written = store.data.first().asMap()
        assertTrue(written.isNotEmpty())
        for ((key, value) in written) {
            assertFalse(value.toString().contains(API_KEY), key.name + " contains the key")
        }
        assertTrue(written.keys.any { it.name.endsWith(".secret.ciphertext") })
        assertTrue(written.keys.any { it.name.endsWith(".secret.iv") })
    }

    @Test
    fun returnsTheStoredSecret() = runBlocking {
        val repository = repository()
        repository.save(config(), API_KEY)

        val state = repository.secretOf(InstanceId("home-kuma"))

        assertTrue(state is SecretState.Present, state.toString())
        assertEquals(API_KEY, state.apiKey)
    }

    @Test
    fun aSecretThatWasNeverStoredIsMissing() = runBlocking {
        val repository = repository()
        repository.save(config(), apiKey = null)

        assertEquals(SecretState.Missing, repository.secretOf(InstanceId("home-kuma")))
    }

    /**
     * A key the platform can no longer decrypt, after keystore material was discarded, is
     * reported as missing so that the user is asked for it again.
     */
    @Test
    fun aSecretThatCannotBeDecryptedIsMissing() = runBlocking {
        val secrets = FakeSecretStore()
        val repository = InstanceRepository(InMemoryPreferencesDataStore(), secrets)
        repository.save(config(), API_KEY)

        secrets.decryptionFails = true

        assertEquals(SecretState.Missing, repository.secretOf(InstanceId("home-kuma")))
    }

    @Test
    fun savingWithoutAKeyKeepsTheStoredOne() = runBlocking {
        val repository = repository()
        repository.save(config(), API_KEY)

        repository.save(config(displayName = "Renamed"), apiKey = null)

        val state = repository.secretOf(InstanceId("home-kuma"))
        assertTrue(state is SecretState.Present, state.toString())
        assertEquals(API_KEY, state.apiKey)
        assertEquals("Renamed", repository.instances.first().single().displayName)
    }

    @Test
    fun deleteRemovesTheConfigurationTheCiphertextAndTheKeyMaterial() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val secrets = FakeSecretStore()
        val repository = InstanceRepository(store, secrets)
        repository.save(config(), API_KEY)

        repository.delete(InstanceId("home-kuma"))

        assertEquals(emptyList(), repository.instances.first())
        val remaining = store.data.first().asMap().keys.map { it.name }
        assertFalse(remaining.any { it.contains("home-kuma") }, remaining.toString())
        assertEquals(listOf(InstanceId("home-kuma")), secrets.forgotten)
        assertEquals(SecretState.Missing, repository.secretOf(InstanceId("home-kuma")))
    }

    @Test
    fun deletingOneInstanceLeavesTheOther() = runBlocking {
        val repository = repository()
        repository.save(config(id = "first"), API_KEY)
        repository.save(config(id = "second"), API_KEY)

        repository.delete(InstanceId("first"))

        assertEquals(listOf("second"), repository.instances.first().map { it.instanceId.value })
    }

    @Test
    fun theEnabledFlagSurvivesARoundTrip() = runBlocking {
        val repository = repository()
        repository.save(config(enabled = true), API_KEY)

        assertTrue(repository.instances.first().single().enabled)
    }

    /**
     * Stored values that no longer pass the current rules are left out instead of taking the
     * list down. The entry is unusable for a request either way.
     */
    @Test
    fun anEntryThatNoLongerValidatesIsLeftOut() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val repository = InstanceRepository(store, FakeSecretStore())
        repository.save(config(), API_KEY)

        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[stringPreferencesKey("instance.home-kuma.baseUrl")] = "http://insecure.invalid"
            }
        }

        assertEquals(emptyList(), repository.instances.first())
    }

    @Test
    fun aPresentSecretDoesNotPrintTheKey() {
        val state: SecretState = SecretState.Present(API_KEY)
        assertFalse(state.toString().contains(API_KEY), state.toString())
    }

    private fun repository(): InstanceRepository =
        InstanceRepository(InMemoryPreferencesDataStore(), FakeSecretStore())

    private fun config(
        id: String = "home-kuma",
        displayName: String = "Uptime Kuma",
        enabled: Boolean = false,
    ): InstanceConfig = InstanceConfig(
        instanceId = InstanceId(id),
        displayName = displayName,
        baseUrl = "https://kuma.example.invalid",
        enabled = enabled,
    )

    /** A store that behaves like the real one without touching a file. */
    private class InMemoryPreferencesDataStore : DataStore<Preferences> {

        private val state = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> get() = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    /**
     * Reversible on purpose: the test has to prove that what the repository wrote is not the
     * key, and that what it reads back is. Base64 is not encryption and is not pretending to be.
     */
    private class FakeSecretStore : SecretStore {

        var decryptionFails = false
        val forgotten = mutableListOf<InstanceId>()

        private val keysByInstance = mutableMapOf<String, String>()
        private var vectorCounter = 0

        override fun encrypt(instanceId: InstanceId, secret: String): EncryptedSecret {
            keysByInstance[instanceId.value] = secret
            vectorCounter++
            return EncryptedSecret(
                ciphertextBase64 = encode(secret),
                ivBase64 = encode("vector-" + vectorCounter),
            )
        }

        override fun decrypt(instanceId: InstanceId, encrypted: EncryptedSecret): String? {
            if (decryptionFails) return null
            if (!keysByInstance.containsKey(instanceId.value)) return null
            return String(Base64.getDecoder().decode(encrypted.ciphertextBase64))
        }

        override fun forget(instanceId: InstanceId) {
            keysByInstance.remove(instanceId.value)
            forgotten.add(instanceId)
        }

        private fun encode(value: String): String =
            Base64.getEncoder().encodeToString(value.toByteArray())
    }

    private companion object {
        const val API_KEY = "uk1_plain_secret_value"
    }
}
