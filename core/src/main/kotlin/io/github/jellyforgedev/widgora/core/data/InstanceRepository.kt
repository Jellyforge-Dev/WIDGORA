package io.github.jellyforgedev.widgora.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.jellyforgedev.widgora.core.security.AndroidKeystoreSecretStore
import io.github.jellyforgedev.widgora.core.security.EncryptedSecret
import io.github.jellyforgedev.widgora.core.security.SecretStore
import io.github.jellyforgedev.widgora.model.InstanceId
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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

    /**
     * There is no usable secret, and the user has to enter one.
     *
     * Never stored and no longer decryptable are deliberately the same case. They call for the
     * same action, and distinguishing them would mean telling the user something about the state
     * of the keystore that they can do nothing with.
     */
    data object Missing : SecretState
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
) {

    /**
     * The configured instances, in a stable order.
     *
     * An entry that cannot be reconstructed — a stored address that no longer passes the current
     * check, for instance — is left out rather than thrown. A list that cannot be read at all is
     * worse than one that is short, and the alternative is an exception inside a flow that the
     * user interface collects.
     */
    val instances: Flow<List<InstanceConfig>> = dataStore.data
        .catch { failure ->
            // A damaged or unreadable file reads as "nothing configured" instead of taking the
            // screen down with it.
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }
        .map { preferences -> readAll(preferences) }

    suspend fun instance(instanceId: InstanceId): InstanceConfig? =
        instances.first().firstOrNull { it.instanceId == instanceId }

    /**
     * Writes [config], and the secret as well when [apiKey] is given.
     *
     * @param apiKey the plain key, encrypted here and never stored as given. Passing `null`
     *   leaves an already stored secret untouched, which is what editing a display name must do.
     */
    suspend fun save(config: InstanceConfig, apiKey: String? = null) {
        val encrypted = apiKey?.let { secretStore.encrypt(config.instanceId, it) }
        val id = config.instanceId.value

        change { preferences ->
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
        val preferences = dataStore.data.first()
        val ciphertext = preferences[ciphertextKey(id)]
        val iv = preferences[ivKey(id)]
        if (ciphertext.isNullOrBlank() || iv.isNullOrBlank()) return SecretState.Missing

        val stored = EncryptedSecret(ciphertextBase64 = ciphertext, ivBase64 = iv)
        val plain = secretStore.decrypt(instanceId, stored)
        return if (plain.isNullOrEmpty()) SecretState.Missing else SecretState.Present(plain)
    }

    /** Removes the configuration, the stored ciphertext and the key material, in that order. */
    suspend fun delete(instanceId: InstanceId) {
        val id = instanceId.value
        change { preferences ->
            preferences.remove(displayNameKey(id))
            preferences.remove(baseUrlKey(id))
            preferences.remove(enabledKey(id))
            preferences.remove(ciphertextKey(id))
            preferences.remove(ivKey(id))
            preferences[idsKey] = preferences[idsKey].orEmpty() - id
        }
        secretStore.forget(instanceId)
    }

    private suspend fun change(transform: (MutablePreferences) -> Unit) {
        dataStore.updateData { preferences ->
            val mutable = preferences.toMutablePreferences()
            transform(mutable)
            mutable
        }
    }

    private fun readAll(preferences: Preferences): List<InstanceConfig> =
        preferences[idsKey].orEmpty().sorted().mapNotNull { id -> read(preferences, id) }

    private fun read(preferences: Preferences, id: String): InstanceConfig? {
        val instanceId = InstanceId.parseOrNull(id) ?: return null
        val displayName = preferences[displayNameKey(id)] ?: return null
        val baseUrl = preferences[baseUrlKey(id)] ?: return null
        val enabled = preferences[enabledKey(id)] ?: false
        return try {
            InstanceConfig(
                instanceId = instanceId,
                displayName = displayName,
                baseUrl = baseUrl,
                enabled = enabled,
            )
        } catch (rejected: IllegalArgumentException) {
            // Stored values that no longer satisfy the current rules. Reporting the entry as
            // absent is the only honest option: it cannot be used for a request.
            null
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
            val store = PreferenceDataStoreFactory.create(
                produceFile = { File(context.filesDir, STORE_FILE_NAME) },
            )
            return InstanceRepository(store, secretStore)
        }
    }
}
