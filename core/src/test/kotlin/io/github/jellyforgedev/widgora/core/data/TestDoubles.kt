package io.github.jellyforgedev.widgora.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.jellyforgedev.widgora.core.security.EncryptedSecret
import io.github.jellyforgedev.widgora.core.security.SecretStore
import io.github.jellyforgedev.widgora.model.InstanceId
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** A preference store that behaves like the real one without touching a file. */
internal class InMemoryPreferences : DataStore<Preferences> {

    /**
     * Set to make every read fail the way a damaged file does.
     *
     * A field rather than a separate double, so that a test can configure an instance first and
     * then break the file — which is the order the real situation arises in.
     */
    var failWith: Throwable? = null

    /**
     * Set to make every write fail, the way a full or unwritable medium does.
     *
     * Separate from [failWith] rather than folded into it, because a store that cannot be read is
     * not necessarily one that cannot be written: the widget settings file replaces a damaged copy
     * and writes on, while the instance file reports the failure. Two flags keep the two
     * situations apart instead of making every test about the one that is easier to produce.
     */
    var failWritesWith: Throwable? = null

    /**
     * How many times a write was attempted.
     *
     * It serves one claim that cannot be made any other way: that a cleanup sweep with nothing to
     * clean up does not write. The sweep runs on every widget update, so "no change, no write" is
     * the difference between a cleanup and a steady stream of file writes — and the stored result
     * of both is identical, which is exactly why it has to be counted rather than observed.
     */
    var writes = 0
        private set

    private val state = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> get() = flow {
        failWith?.let { throw it }
        emitAll(state)
    }

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
        writes++
        failWritesWith?.let { throw it }
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

/**
 * A reversible secret store.
 *
 * Reversible on purpose: a test has to be able to show both that what was written is not the
 * credential and that what is read back is. Base64 is not encryption and does not pretend to be.
 */
internal class ReversibleSecretStore : SecretStore {

    var decryptionFails = false

    /** Whether every key was destroyed at once, which only a reset is allowed to do. */
    var forgotEverything = false
        private set

    private val byInstance = mutableMapOf<String, String>()
    private var vectorCounter = 0

    override fun encrypt(instanceId: InstanceId, secret: String): EncryptedSecret {
        byInstance[instanceId.value] = secret
        vectorCounter++
        return EncryptedSecret(
            ciphertextBase64 = encode(secret),
            ivBase64 = encode("vector-" + vectorCounter),
        )
    }

    override fun decrypt(instanceId: InstanceId, encrypted: EncryptedSecret): String? {
        if (decryptionFails) return null
        if (!byInstance.containsKey(instanceId.value)) return null
        return String(Base64.getDecoder().decode(encrypted.ciphertextBase64))
    }

    override fun forget(instanceId: InstanceId) {
        byInstance.remove(instanceId.value)
    }

    override fun forgetAll() {
        forgotEverything = true
        byInstance.clear()
    }

    private fun encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray())
}

/** A clock whose reading the test sets. */
internal class SettableClock(private var nowMs: Long) : Clock {

    override fun nowEpochMs(): Long = nowMs

    fun advanceBy(millis: Long) {
        nowMs += millis
    }
}
