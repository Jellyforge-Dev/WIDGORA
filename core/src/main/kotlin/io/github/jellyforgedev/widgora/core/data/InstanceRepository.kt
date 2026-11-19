package io.github.jellyforgedev.widgora.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.jellyforgedev.widgora.core.security.AndroidKeystoreSecretStore
import io.github.jellyforgedev.widgora.core.security.EncryptedSecret
import io.github.jellyforgedev.widgora.core.security.SecretStore
import io.github.jellyforgedev.widgora.model.InstanceId
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

/** The state of an instance's secret, as far as it can be established. */
sealed interface SecretState {

    /**
     * The secret is available.
     *
     * [toString] is masked: this value exists to be handed to a request, not to be printed.
     */
    data class Present(val apiKey: String) : SecretState {

        override fun toString(): String = "Present(apiKey=***)"
    }

    /** Nothing was ever stored for this instance. */
    data object Missing : SecretState

    /**
     * A secret is stored but can no longer be decrypted, because the key material behind it is
     * gone or was replaced.
     *
     * It is a case of its own rather than absence: the user has to be told that what they
     * entered is lost, instead of being shown a screen that looks as if they had never
     * configured anything.
     */
    data object Unreadable : SecretState
}

/**
 * A stored instance, whether or not it still satisfies the rules.
 *
 * An entry that no longer validates is reported rather than hidden. Hiding it leaves the user
 * with storage they can neither see, correct nor remove.
 */
sealed interface StoredInstance {

    val instanceId: InstanceId

    data class Valid(val config: InstanceConfig) : StoredInstance {

        override val instanceId: InstanceId get() = config.instanceId
    }

    /**
     * The stored values cannot form a configuration — an address that the current rules reject,
     * for instance. What was stored is carried along so that a screen can offer it for
     * correction.
     */
    data class Invalid(
        override val instanceId: InstanceId,
        val displayName: String?,
        val baseUrl: String?,
    ) : StoredInstance
}

/**
 * The outcome of reading the stored configuration.
 *
 * The two cases are kept apart because they are not the same situation and must not lead to the
 * same screen. Until now an unreadable file was reported as an empty list, which told the user
 * they had never set anything up — while their instance, their address and their encrypted key
 * were all still on disk. They would have entered everything again to fix something that was not
 * broken, and nothing would have pointed at the actual fault.
 */
sealed interface StoredInstances {

    /**
     * The configuration was read. An empty list genuinely means nothing is configured, which is
     * also the state of a device whose file has not been created yet.
     */
    data class Readable(val instances: List<StoredInstance>) : StoredInstances

    /**
     * A configuration file exists and could not be read — damaged, or written in a format this
     * version does not understand.
     *
     * Nothing is known about its contents, so no instance can be named. Saying so is the whole
     * point; a screen that looks like a fresh installation is not.
     */
    data object Unreadable : StoredInstances
}

/**
 * What came of an attempt to change what is stored.
 *
 * Writing can fail for reasons that have nothing to do with the values offered: the file may be
 * damaged, the medium full, the key material unusable. Reporting that is the point of the type.
 * Without it a failed write left the user looking at a screen that showed what they had entered as
 * though it had been kept.
 */
enum class StorageWriteResult {

    DONE,

    /** Nothing was written. What was stored before, if anything, is unchanged. */
    FAILED,
}

/**
 * Stores the configured instances and their secrets.
 *
 * The configuration goes into a preference file; the secret goes through [SecretStore] and only
 * its ciphertext and initialisation vector are written. **No code path writes the plain key.**
 *
 * The storage type is not part of this class's surface: callers construct it with [create] and
 * see only the types above, so replacing the store later does not reach them.
 */
class InstanceRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val secretStore: SecretStore,
    /**
     * Removes the file behind [dataStore], reporting whether it is gone afterwards.
     *
     * It is a function rather than a path because the storage type is not part of this class's
     * surface, and it is separate from [dataStore] because the one case it serves is the case the
     * store itself cannot handle: a file so damaged that every read and every write through it
     * fails. The default says there is no separate file to remove, which is true of the in-memory
     * store the unit tests use.
     */
    private val removeStorage: () -> Boolean = { false },
) {

    /**
     * The configured instances, in a stable order, or the statement that they cannot be read.
     *
     * An entry that cannot be reconstructed — a stored address that no longer passes the current
     * check, for instance — is reported as [StoredInstance.Invalid] rather than thrown, because
     * the user can still correct or delete it. A file that cannot be read at all is a different
     * matter and is reported as such: it never throws into the flow the user interface collects,
     * and it never pretends to be an empty configuration.
     */
    val instances: Flow<StoredInstances> = dataStore.data
        .map<Preferences, StoredInstances> { preferences ->
            StoredInstances.Readable(readAll(preferences))
        }
        .catch { failure ->
            // Only an IOException is a damaged file; DataStore reports a corrupt one as
            // CorruptionException, which is an IOException. Anything else is a defect in this
            // class and is left to fail loudly rather than being filed as unreadable storage.
            if (failure is IOException) emit(StoredInstances.Unreadable) else throw failure
        }

    suspend fun instance(instanceId: InstanceId): StoredInstance? =
        readableInstances().firstOrNull { it.instanceId == instanceId }

    /** The instances, or none when the configuration cannot be read. */
    private suspend fun readableInstances(): List<StoredInstance> =
        (instances.first() as? StoredInstances.Readable)?.instances.orEmpty()

    /**
     * Writes [config], and the secret as well when [apiKey] is given.
     *
     * **This never throws.** Writing to storage that cannot be written is an ordinary condition,
     * not a defect: the file may be the damaged one that [StoredInstances.Unreadable] describes,
     * and the key material may have become unusable since it was created. A screen has to be able
     * to say so, and a user acting on an unreadable configuration must not be met with a crash
     * for trying the obvious thing.
     *
     * @param apiKey the plain key, encrypted here and never stored as given. Passing `null`
     *   leaves an already stored secret untouched, which is what editing a display name must do.
     */
    suspend fun save(config: InstanceConfig, apiKey: String? = null): StorageWriteResult {
        val encrypted = try {
            apiKey?.let { secretStore.encrypt(config.instanceId, it) }
        } catch (keystoreRefused: GeneralSecurityException) {
            // Encrypting is the one step of a save that needs the platform's key store. It can
            // refuse — material gone, hardware backing unavailable — and that is a failed save
            // and nothing worse. Writing the configuration without its key would be worse: the
            // instance would exist and be unusable.
            return StorageWriteResult.FAILED
        } catch (providerBroken: ProviderException) {
            // The keystore provider reports some failures unchecked. It is the same situation.
            return StorageWriteResult.FAILED
        }
        val id = config.instanceId.value

        return write { preferences ->
            preferences[idsKey] = preferences[idsKey].orEmpty() + id
            preferences[displayNameKey(id)] = config.displayName
            preferences[baseUrlKey(id)] = config.baseUrl
            preferences[enabledKey(id)] = config.enabled
            if (encrypted != null) {
                preferences[ciphertextKey(id)] = encrypted.ciphertextBase64
                preferences[ivKey(id)] = encrypted.ivBase64
            }
        }
    }

    suspend fun secretOf(instanceId: InstanceId): SecretState {
        val id = instanceId.value
        // An unreadable file is reported as a secret that cannot be read, which is exactly what
        // the situation is: something may well be stored, and this code cannot get at it. The
        // alternative, letting the IOException out, would take down whichever screen asked.
        val preferences = readablePreferences() ?: return SecretState.Unreadable
        val ciphertext = preferences[ciphertextKey(id)]
        val iv = preferences[ivKey(id)]
        if (ciphertext.isNullOrBlank() || iv.isNullOrBlank()) return SecretState.Missing

        val stored = EncryptedSecret(ciphertextBase64 = ciphertext, ivBase64 = iv)
        val plain = secretStore.decrypt(instanceId, stored)
        // Something was stored, so an unsuccessful decryption is a loss to report, not absence.
        return if (plain.isNullOrEmpty()) SecretState.Unreadable else SecretState.Present(plain)
    }

    /**
     * Removes the configuration, the stored ciphertext and the key material, in that order.
     *
     * Like [save], it reports rather than throws. The key material is destroyed only once the
     * entry is actually gone from the file: the other order would leave a configuration behind
     * whose key no longer exists, which is the state the user is least able to make sense of.
     */
    suspend fun delete(instanceId: InstanceId): StorageWriteResult {
        val id = instanceId.value
        val written = write { preferences ->
            preferences.remove(displayNameKey(id))
            preferences.remove(baseUrlKey(id))
            preferences.remove(enabledKey(id))
            preferences.remove(ciphertextKey(id))
            preferences.remove(ivKey(id))
            preferences[idsKey] = preferences[idsKey].orEmpty() - id
        }
        if (written == StorageWriteResult.FAILED) return StorageWriteResult.FAILED
        secretStore.forget(instanceId)
        return StorageWriteResult.DONE
    }

    /**
     * Throws away everything that is stored: the configuration file and the key material of every
     * instance.
     *
     * This is the way out of a configuration that cannot be read, and it is the only operation in
     * the application that destroys data the user did not name. It therefore runs when the user
     * asks for it and at no other time — never as a repair attempt, and never because a read
     * failed. Replacing a damaged file silently would mean deciding on the user's behalf that
     * their address and their key are worth less than a working screen.
     *
     * Both halves are attempted, in this order:
     *
     * 1. The key material goes first. It cannot be addressed one instance at a time here — the
     *    identifiers are in the file that cannot be read — so [SecretStore.forgetAll] is the only
     *    way to leave nothing behind.
     * 2. The file is removed, and the open store is then cleared as well. The second step is what
     *    makes an in-memory store empty; against a real file it re-establishes an empty state in
     *    the instance that is already open. Removing the file under a store is otherwise something
     *    to avoid, and it is defensible exactly here: a store whose every read has failed holds no
     *    value that the removal could contradict.
     *
     * @return [StorageWriteResult.DONE] when nothing is stored any more, by either step.
     */
    suspend fun reset(): StorageWriteResult {
        secretStore.forgetAll()
        val removed = removeStorage()
        val cleared = write { preferences -> preferences.clear() } == StorageWriteResult.DONE
        return if (removed || cleared) StorageWriteResult.DONE else StorageWriteResult.FAILED
    }

    /** The stored preferences, or `null` when the file cannot be read. */
    private suspend fun readablePreferences(): Preferences? =
        dataStore.data.catch { failure -> if (failure !is IOException) throw failure }.firstOrNull()

    /**
     * Applies [transform] to what is stored, reporting failure instead of raising it.
     *
     * Only an [IOException] is caught, which is every way the store itself can refuse: a damaged
     * file arrives as a CorruptionException, which is one. Anything else is a defect in this class
     * and still fails loudly — the same line the read path draws.
     */
    private suspend fun write(transform: (MutablePreferences) -> Unit): StorageWriteResult = try {
        dataStore.updateData { preferences ->
            val mutable = preferences.toMutablePreferences()
            transform(mutable)
            mutable
        }
        StorageWriteResult.DONE
    } catch (unwritable: IOException) {
        StorageWriteResult.FAILED
    }

    private fun readAll(preferences: Preferences): List<StoredInstance> =
        preferences[idsKey].orEmpty().sorted().mapNotNull { id -> read(preferences, id) }

    private fun read(preferences: Preferences, id: String): StoredInstance? {
        // An identifier the current rules reject cannot be addressed at all: it could not be
        // passed to a delete either. Such an entry is the one case that is skipped.
        val instanceId = InstanceId.parseOrNull(id) ?: return null
        val displayName = preferences[displayNameKey(id)]
        val baseUrl = preferences[baseUrlKey(id)]
        val enabled = preferences[enabledKey(id)] ?: false
        return try {
            StoredInstance.Valid(
                InstanceConfig(
                    instanceId = instanceId,
                    displayName = requireNotNull(displayName) { "displayName missing" },
                    baseUrl = requireNotNull(baseUrl) { "baseUrl missing" },
                    enabled = enabled,
                ),
            )
        } catch (rejected: IllegalArgumentException) {
            // Stored values that no longer satisfy the current rules. The entry is reported as
            // invalid, with what was stored, so that it can be corrected or removed.
            StoredInstance.Invalid(
                instanceId = instanceId,
                displayName = displayName,
                baseUrl = baseUrl,
            )
        }
    }

    companion object {

        private const val STORE_FILE_NAME = "widgora-instances.preferences_pb"

        private val idsKey = stringSetPreferencesKey("instance.ids")

        private fun displayNameKey(id: String) = stringPreferencesKey("instance.$id.displayName")

        private fun baseUrlKey(id: String) = stringPreferencesKey("instance.$id.baseUrl")

        private fun enabledKey(id: String) = booleanPreferencesKey("instance.$id.enabled")

        private fun ciphertextKey(id: String) =
            stringPreferencesKey("instance.$id.secret.ciphertext")

        private fun ivKey(id: String) = stringPreferencesKey("instance.$id.secret.iv")

        /** The ordinary way to obtain a repository. */
        fun create(
            context: Context,
            secretStore: SecretStore = AndroidKeystoreSecretStore(),
        ): InstanceRepository {
            val file = File(context.filesDir, STORE_FILE_NAME)
            val store = PreferenceDataStoreFactory.create(produceFile = { file })
            return InstanceRepository(
                dataStore = store,
                secretStore = secretStore,
                // A file that was never created is already in the state a removal aims for.
                removeStorage = { !file.exists() || file.delete() },
            )
        }
    }
}
