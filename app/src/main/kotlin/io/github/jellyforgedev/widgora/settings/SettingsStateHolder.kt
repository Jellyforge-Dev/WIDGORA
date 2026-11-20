package io.github.jellyforgedev.widgora.settings

import io.github.jellyforgedev.widgora.core.data.InstanceAddress
import io.github.jellyforgedev.widgora.core.data.InstanceConfig
import io.github.jellyforgedev.widgora.core.data.SecretState
import io.github.jellyforgedev.widgora.core.data.StorageWriteResult
import io.github.jellyforgedev.widgora.core.data.StoredInstance
import io.github.jellyforgedev.widgora.core.data.StoredInstances
import io.github.jellyforgedev.widgora.model.InstanceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Whether a secret is stored for the instance being edited, as far as the screen must know. */
enum class StoredKey { NONE, STORED, UNREADABLE }

/**
 * A text field of this screen, named so that the state can say which one is being edited.
 *
 * Which field is being edited belongs in the state rather than inside a composable. Only one may
 * be edited at a time, and the rule that matters — having the focus is not the same as being
 * edited — is then a rule about a value a test can read instead of about three booleans living in
 * three places.
 */
enum class EditableField { DISPLAY_NAME, ADDRESS, API_KEY }

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
 * A write that did not happen, named so that the screen can say which one.
 *
 * One flag would have done for the message "something went wrong", which is the message that tells
 * the user nothing. Saving, deleting and resetting fail in the same way and mean different things.
 */
enum class FailedWrite { SAVE, DELETE, RESET }

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
    /**
     * Whether what was typed into the key field is shown in clear text.
     *
     * It is a statement about [apiKeyInput] and about nothing else. A stored key is not in this
     * state at all, so there is nothing here that revealing could expose beyond what the user
     * has just typed themselves.
     */
    val apiKeyVisible: Boolean = false,
    /**
     * The field the user is editing, or `null` while they are only navigating.
     *
     * A television is operated with a remote, and a text field that begins editing the moment the
     * focus reaches it opens the on-screen keyboard on the way past. So the screen keeps the two
     * apart: a focused field shows its focus mark, and editing starts on a deliberate press.
     */
    val editingField: EditableField? = null,
    val storedKey: StoredKey = StoredKey.NONE,
    val existing: Boolean = false,
    val storedEntryInvalid: Boolean = false,
    /**
     * A configuration file exists and could not be read.
     *
     * The form is empty in this case and [existing] is false, because nothing is known about what
     * is stored — not even an identifier. It is not the same as nothing being stored, and it is
     * the only state in which the screen offers to throw storage away.
     */
    val configurationUnreadable: Boolean = false,
    val addressRejection: InstanceAddress.Rejection? = null,
    val missingField: MissingField? = null,
    val savedJustNow: Boolean = false,
    val testing: Boolean = false,
    val testOutcome: ConnectionTestResult? = null,
    val confirmingDelete: Boolean = false,
    val confirmingReset: Boolean = false,
    val resetJustNow: Boolean = false,
    /** The last write that failed, or `null`. Cleared as soon as the user changes anything. */
    val failedWrite: FailedWrite? = null,
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
        val storage = store.instances.first()
        val stored = (storage as? StoredInstances.Readable)?.instances?.firstOrNull()
        if (stored == null) {
            instanceId = SINGLE_INSTANCE_ID
            addressOfStoredKey = null
            // An unreadable file leaves the form empty, and deliberately says nothing about a
            // stored key either: whether one exists is part of what could not be read, and
            // guessing would produce either a false reassurance or a false alarm.
            mutableState.value = SettingsUiState(
                loading = false,
                configurationUnreadable = storage is StoredInstances.Unreadable,
            )
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
            // A verdict about an earlier attempt, kept beside values the user is still editing,
            // reads as a verdict about the values now on screen.
            failedWrite = null,
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
            failedWrite = null,
        )
    }

    fun onApiKeyChange(value: String) {
        mutableState.value = mutableState.value.copy(
            apiKeyInput = value,
            missingField = null,
            testOutcome = null,
            savedJustNow = false,
            failedWrite = null,
        )
    }

    /**
     * Begins editing [field], which is what opens the keyboard.
     *
     * Editing another field ends the one before it rather than adding to it: two open editors
     * would mean two places the next keystroke could land.
     */
    fun startEditing(field: EditableField) {
        mutableState.value = mutableState.value.copy(editingField = field)
    }

    /** Ends editing and leaves the focus where it was, so navigation can continue. */
    fun stopEditing() {
        mutableState.value = mutableState.value.copy(editingField = null)
    }

    /**
     * Shows or hides what was typed into the key field.
     *
     * It reads nothing and writes nothing: the value it uncovers is the one the user has just
     * entered, which is already in the state. A stored key is never fetched for this — it is not
     * read back into a screen at all, and pressing this must not be the exception.
     */
    fun toggleApiKeyVisible() {
        val current = mutableState.value
        mutableState.value = current.copy(apiKeyVisible = !current.apiKeyVisible)
    }

    /**
     * Called when the screen is left.
     *
     * A revealed key must not still be on screen the next time the settings are opened, and an
     * editor must not be left open either. The screen cannot be relied upon to forget this by
     * itself: whether its state survives depends on where the holder happens to be remembered,
     * which is a decision made elsewhere and can change.
     */
    fun onLeaveScreen() {
        mutableState.value = mutableState.value.copy(apiKeyVisible = false, editingField = null)
    }

    /**
     * @return true when the instance was written.
     *
     * An empty key field is not an empty key: for an instance that already has one, it means
     * "keep what is stored".
     *
     * A refusal by storage is reported like a refused field rather than raised. It is the one
     * outcome the user cannot correct by typing, which is exactly why it has to be said: the
     * previous version let the attempt fail unnoticed and left the screen looking saved.
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
        val written = store.save(
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
        if (written == StorageWriteResult.FAILED) {
            // Nothing was stored, so nothing about the stored state may be claimed: the key field
            // keeps what was typed, and whether a key is stored is left as it was.
            mutableState.value = current.copy(failedWrite = FailedWrite.SAVE, savedJustNow = false)
            return false
        }
        addressOfStoredKey = normalised

        mutableState.value = current.copy(
            displayName = displayName,
            address = normalised,
            apiKeyInput = "",
            // There is nothing left in the field to reveal, so a revealing switch left on would
            // be a switch about nothing — and on next time a key is typed. A refused save is
            // deliberately different: it keeps what was typed, and with it the user's decision
            // to look at it, because nothing has moved and they may be about to correct it.
            apiKeyVisible = false,
            editingField = null,
            storedKey = StoredKey.STORED,
            existing = true,
            storedEntryInvalid = false,
            // What was unreadable has just been overwritten by something readable.
            configurationUnreadable = false,
            addressRejection = null,
            missingField = null,
            savedJustNow = true,
            testOutcome = null,
            failedWrite = null,
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
        mutableState.value = mutableState.value.copy(
            confirmingDelete = true,
            savedJustNow = false,
            failedWrite = null,
        )
    }

    fun cancelDelete() {
        mutableState.value = mutableState.value.copy(confirmingDelete = false)
    }

    /** Removes configuration, ciphertext and key material through the repository. */
    suspend fun confirmDelete() {
        if (store.delete(instanceId) == StorageWriteResult.FAILED) {
            // The instance is still there, so the form must keep showing it.
            mutableState.value = mutableState.value.copy(
                confirmingDelete = false,
                failedWrite = FailedWrite.DELETE,
            )
            return
        }
        instanceId = SINGLE_INSTANCE_ID
        addressOfStoredKey = null
        mutableState.value = SettingsUiState(loading = false)
    }

    /**
     * Offers to throw the stored configuration away.
     *
     * Deliberately only reachable while the configuration is unreadable, and deliberately not
     * automatic: this is the one action that destroys something the user did not name — a key they
     * may still have a copy of nowhere else. The confirmation is the same shape as the one for
     * deleting, because the consequence is of the same kind.
     */
    fun requestReset() {
        mutableState.value = mutableState.value.copy(
            confirmingReset = true,
            savedJustNow = false,
            resetJustNow = false,
            failedWrite = null,
        )
    }

    fun cancelReset() {
        mutableState.value = mutableState.value.copy(confirmingReset = false)
    }

    /** Removes the stored file and all key material, then presents an empty form. */
    suspend fun confirmReset() {
        if (store.reset() == StorageWriteResult.FAILED) {
            mutableState.value = mutableState.value.copy(
                confirmingReset = false,
                failedWrite = FailedWrite.RESET,
            )
            return
        }
        instanceId = SINGLE_INSTANCE_ID
        addressOfStoredKey = null
        // Nothing is stored any more, so nothing is unreadable any more either. The form says so
        // rather than going quiet, because the user has just been told their settings were lost
        // and is owed the confirmation that it was this action that did it.
        mutableState.value = SettingsUiState(loading = false, resetJustNow = true)
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
