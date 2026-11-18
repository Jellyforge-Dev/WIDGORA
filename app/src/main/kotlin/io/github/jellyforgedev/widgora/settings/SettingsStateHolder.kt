package io.github.jellyforgedev.widgora.settings

import io.github.jellyforgedev.widgora.core.data.InstanceAddress
import io.github.jellyforgedev.widgora.core.data.InstanceConfig
import io.github.jellyforgedev.widgora.core.data.SecretState
import io.github.jellyforgedev.widgora.core.data.StoredInstance
import io.github.jellyforgedev.widgora.model.InstanceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Whether a secret is stored for the instance being edited, as far as the screen must know. */
enum class StoredKey { NONE, STORED, UNREADABLE }

/** A field the user has to fill in before saving can succeed. */
enum class MissingField {
    DISPLAY_NAME,
    API_KEY,

    /**
     * A key is stored, but for a different address than the one now entered.
     *
     * It is a case of its own because the explanation differs: the user has a key saved and
     * needs to be told why it is not being used.
     */
    API_KEY_FOR_CHANGED_ADDRESS,
}

/**
 * Everything the settings screen renders.
 *
 * **There is no field for a stored key.** A key that was saved earlier is never read into this
 * state, not even masked: a value that is not in the state cannot be rendered, logged or caught
 * in a screenshot by accident. [storedKey] says only whether one exists.
 */
data class SettingsUiState(
    val loading: Boolean = true,
    val displayName: String = "",
    val address: String = "",
    val apiKeyInput: String = "",
    val storedKey: StoredKey = StoredKey.NONE,
    val existing: Boolean = false,
    val storedEntryInvalid: Boolean = false,
    val addressRejection: InstanceAddress.Rejection? = null,
    val missingField: MissingField? = null,
    val savedJustNow: Boolean = false,
    val testing: Boolean = false,
    val testOutcome: ConnectionTestResult? = null,
    val confirmingDelete: Boolean = false,
)

/**
 * The settings screen's state and the operations behind its buttons.
 *
 * It is a plain class with its collaborators handed to the constructor: no view model, no
 * dependency injection framework. Every operation that touches storage or the network is a
 * suspending function, so the caller owns the scope and a test can drive it without a scheduler.
 *
 * Only one instance is supported in this step. A second one is a later decision, which is why
 * the identifier is fixed rather than invented per save.
 */
class SettingsStateHolder(
    private val store: InstanceStore,
    private val tester: ConnectionTester,
) {

    private val mutableState = MutableStateFlow(SettingsUiState())

    val state: StateFlow<SettingsUiState> get() = mutableState

    private var instanceId: InstanceId = SINGLE_INSTANCE_ID

    /**
     * The address the stored key was entered for, or `null` if there is none.
     *
     * A stored key may only be used against this address. Sending it anywhere else would hand a
     * credential to a server the user never typed it for, which a single character in the
     * address field would be enough to cause.
     */
    private var addressOfStoredKey: String? = null

    /** Reads what is stored. The secret itself is never part of what this produces. */
    suspend fun load() {
        val stored = store.instances.first().firstOrNull()
        if (stored == null) {
            instanceId = SINGLE_INSTANCE_ID
            addressOfStoredKey = null
            mutableState.value = SettingsUiState(loading = false)
            return
        }

        instanceId = stored.instanceId
        val storedKey = storedKeyOf(store.secretOf(stored.instanceId))
        // Only a valid stored entry has an address the key can be tied to. For an invalid one
        // there is nothing to compare against, so the key counts as belonging to no address.
        addressOfStoredKey = (stored as? StoredInstance.Valid)?.config?.baseUrl
        mutableState.value = when (stored) {
            is StoredInstance.Valid -> SettingsUiState(
                loading = false,
                displayName = stored.config.displayName,
                address = stored.config.baseUrl,
                storedKey = storedKey,
                existing = true,
            )

            is StoredInstance.Invalid -> SettingsUiState(
                loading = false,
                displayName = stored.displayName.orEmpty(),
                address = stored.baseUrl.orEmpty(),
                storedKey = storedKey,
                existing = true,
                storedEntryInvalid = true,
            )
        }
    }

    fun onDisplayNameChange(value: String) {
        mutableState.value = mutableState.value.copy(
            displayName = value,
            missingField = null,
            savedJustNow = false,
        )
    }

    fun onAddressChange(value: String) {
        // An earlier verdict says nothing about a changed address.
        mutableState.value = mutableState.value.copy(
            address = value,
            addressRejection = null,
            testOutcome = null,
            missingField = null,
            savedJustNow = false,
        )
    }

    fun onApiKeyChange(value: String) {
        mutableState.value = mutableState.value.copy(
            apiKeyInput = value,
            missingField = null,
            testOutcome = null,
            savedJustNow = false,
        )
    }

    /**
     * @return true when the instance was written.
     *
     * An empty key field is not an empty key: for an instance that already has one, it means
     * "keep what is stored".
     */
    suspend fun save(): Boolean {
        val current = mutableState.value
        val displayName = current.displayName.trim()
        if (displayName.isEmpty()) {
            mutableState.value = current.copy(missingField = MissingField.DISPLAY_NAME)
            return false
        }

        val address = InstanceAddress.normalise(current.address)
        if (address is InstanceAddress.Result.Invalid) {
            mutableState.value = current.copy(addressRejection = address.rejection)
            return false
        }

        val normalised = (address as InstanceAddress.Result.Valid).normalised
        val enteredKey = current.apiKeyInput.ifBlank { null }
        if (enteredKey == null && !storedKeyAppliesTo(normalised, current)) {
            mutableState.value = current.copy(missingField = missingKeyFieldFor(normalised, current))
            return false
        }
        store.save(
            InstanceConfig(
                instanceId = instanceId,
                displayName = displayName,
                baseUrl = normalised,
                // An instance the user has just set up is meant to be used. Whether it is also
                // shown is a question for the screen that displays widgets.
                enabled = true,
            ),
            apiKey = enteredKey,
        )
        addressOfStoredKey = normalised

        mutableState.value = current.copy(
            displayName = displayName,
            address = normalised,
            apiKeyInput = "",
            storedKey = StoredKey.STORED,
            existing = true,
            storedEntryInvalid = false,
            addressRejection = null,
            missingField = null,
            savedJustNow = true,
            testOutcome = null,
        )
        return true
    }

    /**
     * Tries the entered values without saving them.
     *
     * When the key field is empty and a readable key is stored **for this very address**, that
     * one is used, so that an existing instance can be tested without retyping the key. The key
     * is handed to the test and never put into the state.
     */
    suspend fun testConnection() {
        val current = mutableState.value
        val address = InstanceAddress.normalise(current.address)
        if (address is InstanceAddress.Result.Invalid) {
            mutableState.value = current.copy(addressRejection = address.rejection)
            return
        }

        val normalisedAddress = (address as InstanceAddress.Result.Valid).normalised
        val key = current.apiKeyInput.ifBlank { null }
            ?: storedKeyValue(normalisedAddress, current)
        if (key == null) {
            mutableState.value = current.copy(
                missingField = missingKeyFieldFor(normalisedAddress, current),
            )
            return
        }

        mutableState.value = current.copy(
            testing = true,
            testOutcome = null,
            addressRejection = null,
            missingField = null,
            savedJustNow = false,
        )
        val outcome = tester.test(normalisedAddress, key)
        mutableState.value = mutableState.value.copy(testing = false, testOutcome = outcome)
    }

    fun requestDelete() {
        mutableState.value = mutableState.value.copy(confirmingDelete = true, savedJustNow = false)
    }

    fun cancelDelete() {
        mutableState.value = mutableState.value.copy(confirmingDelete = false)
    }

    /** Removes configuration, ciphertext and key material through the repository. */
    suspend fun confirmDelete() {
        store.delete(instanceId)
        instanceId = SINGLE_INSTANCE_ID
        addressOfStoredKey = null
        mutableState.value = SettingsUiState(loading = false)
    }

    /**
     * Whether the stored key may be used for [normalisedAddress].
     *
     * It may only be used for the address it was entered for. An edited address is a different
     * server as far as a credential is concerned, however small the edit was.
     */
    private fun storedKeyAppliesTo(normalisedAddress: String, state: SettingsUiState): Boolean =
        state.storedKey == StoredKey.STORED && normalisedAddress == addressOfStoredKey

    private fun missingKeyFieldFor(
        normalisedAddress: String,
        state: SettingsUiState,
    ): MissingField = if (state.storedKey == StoredKey.STORED &&
        normalisedAddress != addressOfStoredKey
    ) {
        MissingField.API_KEY_FOR_CHANGED_ADDRESS
    } else {
        MissingField.API_KEY
    }

    private suspend fun storedKeyValue(
        normalisedAddress: String,
        state: SettingsUiState,
    ): String? {
        if (!storedKeyAppliesTo(normalisedAddress, state)) return null
        return (store.secretOf(instanceId) as? SecretState.Present)?.apiKey
    }

    private fun storedKeyOf(secret: SecretState): StoredKey = when (secret) {
        is SecretState.Present -> StoredKey.STORED
        SecretState.Unreadable -> StoredKey.UNREADABLE
        SecretState.Missing -> StoredKey.NONE
    }

    companion object {

        /** Only one instance is supported in this step, so its identifier is fixed. */
        val SINGLE_INSTANCE_ID = InstanceId("kuma")
    }
}
