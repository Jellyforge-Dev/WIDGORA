package io.github.jellyforgedev.widgora.core.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jellyforgedev.widgora.core.security.EncryptedSecret
import io.github.jellyforgedev.widgora.core.security.SecretStore
import io.github.jellyforgedev.widgora.model.InstanceId
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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

        assertEquals(listOf(StoredInstance.Valid(config())), repository.listed())
    }

    @Test
    fun listsNothingWhenNothingWasSaved() = runBlocking {
        assertEquals(emptyList(), repository().listed())
    }

    /** An absent or empty file is a readable configuration that happens to hold nothing. */
    @Test
    fun anEmptyStoreIsReadableAndNotUnreadable() = runBlocking {
        assertEquals(StoredInstances.Readable(emptyList()), repository().instances.first())
    }

    @Test
    fun findsAnInstanceById() = runBlocking {
        val repository = repository()
        repository.save(config(), API_KEY)

        assertEquals(StoredInstance.Valid(config()), repository.instance(InstanceId("home-kuma")))
        assertNull(repository.instance(InstanceId("other")))
    }

    @Test
    fun listsSeveralInstancesInAStableOrder() = runBlocking {
        val repository = repository()
        repository.save(config(id = "second"), API_KEY)
        repository.save(config(id = "first"), API_KEY)

        assertEquals(listOf("first", "second"), repository.listed().map { it.instanceId.value })
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
     * A key the platform can no longer decrypt, after keystore material was discarded, is its
     * own case. Reporting it as absent would show a screen that looks as if nothing had ever
     * been configured, and the user would never learn that what they entered is gone.
     */
    @Test
    fun aSecretThatCannotBeDecryptedIsUnreadableAndNotMissing() = runBlocking {
        val secrets = FakeSecretStore()
        val repository = InstanceRepository(InMemoryPreferencesDataStore(), secrets)
        repository.save(config(), API_KEY)

        secrets.decryptionFails = true

        assertEquals(SecretState.Unreadable, repository.secretOf(InstanceId("home-kuma")))
    }

    @Test
    fun theThreeSecretStatesAreDistinct() = runBlocking {
        val secrets = FakeSecretStore()
        val repository = InstanceRepository(InMemoryPreferencesDataStore(), secrets)

        assertEquals(SecretState.Missing, repository.secretOf(InstanceId("home-kuma")))
        repository.save(config(), API_KEY)
        assertTrue(repository.secretOf(InstanceId("home-kuma")) is SecretState.Present)
        secrets.decryptionFails = true
        assertEquals(SecretState.Unreadable, repository.secretOf(InstanceId("home-kuma")))
    }

    @Test
    fun savingWithoutAKeyKeepsTheStoredOne() = runBlocking {
        val repository = repository()
        repository.save(config(), API_KEY)

        repository.save(config(displayName = "Renamed"), apiKey = null)

        val state = repository.secretOf(InstanceId("home-kuma"))
        assertTrue(state is SecretState.Present, state.toString())
        assertEquals(API_KEY, state.apiKey)
        assertEquals("Renamed", valid(repository.listed().single()).displayName)
    }

    @Test
    fun deleteRemovesTheConfigurationTheCiphertextAndTheKeyMaterial() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val secrets = FakeSecretStore()
        val repository = InstanceRepository(store, secrets)
        repository.save(config(), API_KEY)

        repository.delete(InstanceId("home-kuma"))

        assertEquals(emptyList(), repository.listed())
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

        assertEquals(listOf("second"), repository.listed().map { it.instanceId.value })
    }

    @Test
    fun theEnabledFlagSurvivesARoundTrip() = runBlocking {
        val repository = repository()
        repository.save(config(enabled = true), API_KEY)

        assertTrue(valid(repository.listed().single()).enabled)
    }

    /**
     * Stored values that no longer pass the current rules are reported as invalid, carrying what
     * was stored, rather than vanishing. A hidden entry is storage the user can neither see nor
     * correct nor remove.
     */
    @Test
    fun anEntryThatNoLongerValidatesIsReportedAsInvalid() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val repository = InstanceRepository(store, FakeSecretStore())
        repository.save(config(), API_KEY)

        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[stringPreferencesKey("instance.home-kuma.baseUrl")] = "http://insecure.invalid"
            }
        }

        val stored = repository.listed().single()
        assertEquals(
            StoredInstance.Invalid(
                instanceId = InstanceId("home-kuma"),
                displayName = "Uptime Kuma",
                baseUrl = "http://insecure.invalid",
            ),
            stored,
        )
    }

    @Test
    fun anEntryWithoutADisplayNameIsReportedAsInvalid() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val repository = InstanceRepository(store, FakeSecretStore())
        repository.save(config(), API_KEY)

        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                remove(stringPreferencesKey("instance.home-kuma.displayName"))
            }
        }

        val stored = repository.listed().single()
        assertTrue(stored is StoredInstance.Invalid, stored.toString())
        assertNull(stored.displayName)
        assertEquals("https://kuma.example.invalid", stored.baseUrl)
    }

    /** The point of reporting an invalid entry: it can be got rid of. */
    @Test
    fun anInvalidEntryCanBeDeleted() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val repository = InstanceRepository(store, FakeSecretStore())
        repository.save(config(), API_KEY)
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[stringPreferencesKey("instance.home-kuma.baseUrl")] = "http://insecure.invalid"
            }
        }

        repository.delete(InstanceId("home-kuma"))

        assertEquals(emptyList(), repository.listed())
    }

    // --- A configuration that cannot be read --------------------------------------------------

    /**
     * The defect this replaces.
     *
     * An unreadable file used to arrive as an empty list, which told the user they had never set
     * anything up while their instance and their encrypted key were still on disk. The two cases
     * are now distinguishable, and that is the whole point of the type.
     */
    @Test
    fun anUnreadableFileIsReportedAsUnreadableAndNotAsEmpty() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        store.failWith = IOException("file damaged")
        val repository = InstanceRepository(store, FakeSecretStore())

        val stored = repository.instances.first()

        assertEquals(StoredInstances.Unreadable, stored)
        assertTrue(stored !is StoredInstances.Readable)
    }

    /** DataStore reports a damaged file as a CorruptionException, which is an IOException. */
    @Test
    fun aCorruptFileIsAlsoReportedAsUnreadable() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        store.failWith = CorruptionException("not a preferences file")
        val repository = InstanceRepository(store, FakeSecretStore())

        assertEquals(StoredInstances.Unreadable, repository.instances.first())
    }

    /** Reading must not throw into the flow a screen is collecting. That is the "no crash" part. */
    @Test
    fun readingAnUnreadableFileDoesNotThrow() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        store.failWith = IOException("file damaged")
        val repository = InstanceRepository(store, FakeSecretStore())

        assertEquals(emptyList(), repository.listed())
        assertNull(repository.instance(InstanceId("home-kuma")))
        assertEquals(SecretState.Unreadable, repository.secretOf(InstanceId("home-kuma")))
    }

    /**
     * A secret behind an unreadable file is unreadable, not missing.
     *
     * Missing would mean nothing was ever stored, which is exactly the false statement this change
     * exists to stop making.
     */
    @Test
    fun aSecretBehindAnUnreadableFileIsUnreadableRatherThanMissing() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val repository = InstanceRepository(store, FakeSecretStore())
        repository.save(config(), API_KEY)
        store.failWith = IOException("file damaged")

        val state = repository.secretOf(InstanceId("home-kuma"))

        assertEquals(SecretState.Unreadable, state)
        assertTrue(state != SecretState.Missing)
    }

    /** A defect in this class must still fail loudly instead of being filed as bad storage. */
    @Test
    fun aFailureThatIsNotAnIoProblemIsNotSwallowed() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        store.failWith = IllegalStateException("a defect, not a damaged file")
        val repository = InstanceRepository(store, FakeSecretStore())

        assertFailsWith<IllegalStateException> { repository.instances.first() }
        Unit
    }

    // --- Writing to storage that will not take it ---------------------------------------------

    @Test
    fun aSuccessfulWriteSaysSo() = runBlocking {
        val repository = repository()

        assertEquals(StorageWriteResult.DONE, repository.save(config(), API_KEY))
        assertEquals(StorageWriteResult.DONE, repository.delete(InstanceId("home-kuma")))
    }

    /**
     * The crash this replaces.
     *
     * Saving over a damaged file raised an IOException out of a suspending call that nothing
     * caught. It was reachable by the obvious route: read the new "settings cannot be read"
     * message, enter the instance again, press save.
     */
    @Test
    fun savingToAnUnwritableFileIsReportedRatherThanThrown() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        store.failWith = IOException("file damaged")
        store.failWriteWith = IOException("file damaged")
        val repository = InstanceRepository(store, FakeSecretStore())

        assertEquals(StorageWriteResult.FAILED, repository.save(config(), API_KEY))
    }

    @Test
    fun deletingFromAnUnwritableFileIsReportedRatherThanThrown() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val secrets = FakeSecretStore()
        val repository = InstanceRepository(store, secrets)
        repository.save(config(), API_KEY)
        store.failWriteWith = IOException("file damaged")

        assertEquals(StorageWriteResult.FAILED, repository.delete(InstanceId("home-kuma")))
        // The entry is still there, so its key must still be there too. Destroying the key of an
        // instance that survived would leave the user with an instance nothing can authenticate.
        assertEquals(emptyList(), secrets.forgotten)
        assertTrue(repository.secretOf(InstanceId("home-kuma")) is SecretState.Present)
    }

    /**
     * The key store refusing is a failed save, not a crash and not a half-written instance.
     *
     * An instance stored without the key it needs would exist, be visible and never work.
     */
    @Test
    fun aKeyStoreThatRefusesMakesTheSaveFailAndWritesNothing() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val secrets = FakeSecretStore()
        secrets.encryptionFails = true
        val repository = InstanceRepository(store, secrets)

        assertEquals(StorageWriteResult.FAILED, repository.save(config(), API_KEY))
        assertEquals(emptyList(), repository.listed())
        assertEquals(emptyPreferences().asMap(), store.data.first().asMap())
    }

    /** A save without a key never touches the key store, so it cannot fail there. */
    @Test
    fun aSaveWithoutAKeyIsUnaffectedByTheKeyStore() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val secrets = FakeSecretStore()
        secrets.encryptionFails = true
        val repository = InstanceRepository(store, secrets)

        assertEquals(StorageWriteResult.DONE, repository.save(config(), apiKey = null))
    }

    // --- The reset ----------------------------------------------------------------------------

    @Test
    fun resetRemovesTheConfigurationAndAllKeyMaterial() = runBlocking {
        val secrets = FakeSecretStore()
        val repository = InstanceRepository(InMemoryPreferencesDataStore(), secrets)
        repository.save(config(id = "first"), API_KEY)
        repository.save(config(id = "second"), API_KEY)

        assertEquals(StorageWriteResult.DONE, repository.reset())

        assertEquals(emptyList(), repository.listed())
        assertTrue(secrets.forgotEverything)
        assertEquals(SecretState.Missing, repository.secretOf(InstanceId("first")))
        assertEquals(SecretState.Missing, repository.secretOf(InstanceId("second")))
    }

    /**
     * The case the reset exists for: a file nobody can read, so no identifier can be named.
     *
     * Every key has to go, because the only list of which keys exist is inside the file that
     * cannot be read. Removing the file is what makes the reset succeed here; writing through the
     * store fails, as it does for everything else in this state.
     */
    @Test
    fun resetSucceedsOnAFileThatCanNeitherBeReadNorWritten() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        store.failWith = IOException("file damaged")
        store.failWriteWith = IOException("file damaged")
        val secrets = FakeSecretStore()
        var removals = 0
        val repository = InstanceRepository(
            dataStore = store,
            secretStore = secrets,
            removeStorage = {
                removals++
                true
            },
        )

        assertEquals(StorageWriteResult.DONE, repository.reset())

        assertEquals(1, removals)
        assertTrue(secrets.forgotEverything)
    }

    /** Nothing could be removed, so nothing is claimed to have been. */
    @Test
    fun resetReportsFailureWhenNeitherTheFileNorTheValuesCouldGo() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        store.failWriteWith = IOException("file damaged")
        val repository = InstanceRepository(
            dataStore = store,
            secretStore = FakeSecretStore(),
            removeStorage = { false },
        )

        assertEquals(StorageWriteResult.FAILED, repository.reset())
    }

    /** Nothing destroys every key on its own. Only [InstanceRepository.reset] does. */
    @Test
    fun noOrdinaryOperationForgetsEveryKey() = runBlocking {
        val secrets = FakeSecretStore()
        val store = InMemoryPreferencesDataStore()
        val repository = InstanceRepository(store, secrets)

        repository.save(config(), API_KEY)
        repository.instances.first()
        repository.secretOf(InstanceId("home-kuma"))
        repository.delete(InstanceId("home-kuma"))
        store.failWith = IOException("file damaged")
        store.failWriteWith = IOException("file damaged")
        repository.instances.first()
        repository.save(config(), API_KEY)
        repository.secretOf(InstanceId("home-kuma"))

        assertFalse(secrets.forgotEverything)
    }

    @Test
    fun aPresentSecretDoesNotPrintTheKey() {
        val state: SecretState = SecretState.Present(API_KEY)
        assertFalse(state.toString().contains(API_KEY), state.toString())
    }

    private fun repository(): InstanceRepository =
        InstanceRepository(InMemoryPreferencesDataStore(), FakeSecretStore())

    private fun valid(stored: StoredInstance): InstanceConfig {
        assertTrue(stored is StoredInstance.Valid, stored.toString())
        return stored.config
    }

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

    /** The instances, or none when the configuration could not be read. */
    private suspend fun InstanceRepository.listed(): List<StoredInstance> =
        (instances.first() as? StoredInstances.Readable)?.instances.orEmpty()

    /**
     * A store that behaves like the real one without touching a file.
     *
     * [failWith] makes reads fail the way a damaged file does. It is a field rather than a
     * separate double so that a test can save first and then break the file, which is the order
     * the real situation happens in.
     */
    private class InMemoryPreferencesDataStore : DataStore<Preferences> {

        var failWith: Throwable? = null

        /**
         * Set to make every write fail the way an unwritable or damaged file does.
         *
         * Separate from [failWith], because the two are separate in reality as well: a file can be
         * readable and still refuse a write, and the case that matters most — the damaged file —
         * is both at once, which a test says by setting both.
         */
        var failWriteWith: Throwable? = null

        private val state = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> get() = flow {
            failWith?.let { throw it }
            emitAll(state)
        }

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            failWriteWith?.let { throw it }
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

        /** The platform key store refusing to produce or use a key, which it can. */
        var encryptionFails = false
        var forgotEverything = false
            private set
        val forgotten = mutableListOf<InstanceId>()

        private val keysByInstance = mutableMapOf<String, String>()
        private var vectorCounter = 0

        override fun encrypt(instanceId: InstanceId, secret: String): EncryptedSecret {
            if (encryptionFails) throw GeneralSecurityException("no key material")
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

        override fun forgetAll() {
            forgotEverything = true
            keysByInstance.clear()
        }

        private fun encode(value: String): String =
            Base64.getEncoder().encodeToString(value.toByteArray())
    }

    private companion object {
        const val API_KEY = "uk1_plain_secret_value"
    }
}
