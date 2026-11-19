package io.github.jellyforgedev.widgora.core.security

import io.github.jellyforgedev.widgora.model.InstanceId

/**
 * Encrypts and decrypts one secret per instance.
 *
 * The interface exists so that everything above it can be tested without a device: the real
 * implementation needs a hardware-backed keystore, and a fake one is enough to prove that the
 * layers above never handle plain text themselves.
 *
 * An implementation never throws for a secret it cannot recover. A lost or replaced key is an
 * ordinary condition — it happens when the operating system discards keystore material — and it
 * is reported as absence, so that the user is asked for the key again instead of meeting a crash.
 */
interface SecretStore {

    /** Encrypts [secret] under a key belonging to [instanceId], creating that key if needed. */
    fun encrypt(instanceId: InstanceId, secret: String): EncryptedSecret

    /** @return the secret, or `null` if it cannot be recovered and must be entered again. */
    fun decrypt(instanceId: InstanceId, encrypted: EncryptedSecret): String?

    /** Destroys the key material of [instanceId]. Afterwards nothing it encrypted is readable. */
    fun forget(instanceId: InstanceId)

    /**
     * Destroys the key material of **every** instance this store owns.
     *
     * It exists for one situation: the stored configuration cannot be read, so the identifiers
     * needed to call [forget] one by one are exactly what is unavailable. Without it, a damaged
     * file would leave keys behind that nothing could ever name again.
     *
     * Only an action the user asked for calls this. Nothing in the application recovers by
     * destroying key material on its own.
     */
    fun forgetAll()
}

/**
 * What gets persisted: the ciphertext and the initialisation vector it was produced with, both
 * Base64 so that they survive a text-based store unchanged.
 *
 * Neither value is secret on its own, and neither is printed anyway: a stored blob in a log is
 * noise at best, and printing it invites the habit of printing the thing next to it.
 */
data class EncryptedSecret(val ciphertextBase64: String, val ivBase64: String) {

    init {
        require(ciphertextBase64.isNotBlank()) { "ciphertext must not be blank" }
        require(ivBase64.isNotBlank()) { "iv must not be blank" }
    }

    override fun toString(): String = "EncryptedSecret(ciphertext=***, iv=***)"
}
