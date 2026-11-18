package io.github.jellyforgedev.widgora.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.jellyforgedev.widgora.model.InstanceId
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The real secret store, backed by the platform keystore.
 *
 * The Jetpack security library is not used: all of its APIs were deprecated in its last release
 * and the documented replacement is direct use of the Android keystore, which is what happens
 * here.
 *
 * Each instance gets its own key under its own alias, so deleting one instance cannot affect
 * another, and a key never leaves the keystore: only a handle to it does.
 *
 * Every encryption uses a fresh random initialisation vector. That is not merely convention
 * here — the key is generated with randomised encryption required, so the platform itself
 * refuses an operation that would reuse one. Reusing a vector with the same key breaks the mode
 * outright, which is why the guarantee is pushed down to where it cannot be forgotten.
 *
 * This class needs the platform and therefore cannot run in a plain unit test. Its behaviour is
 * covered by instrumented tests that run on a device or emulator.
 */
class AndroidKeystoreSecretStore(
    private val aliasPrefix: String = DEFAULT_ALIAS_PREFIX,
) : SecretStore {

    override fun encrypt(instanceId: InstanceId, secret: String): EncryptedSecret {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyFor(instanceId))
        val ciphertext = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        return EncryptedSecret(
            ciphertextBase64 = encode(ciphertext),
            ivBase64 = encode(cipher.iv),
        )
    }

    override fun decrypt(instanceId: InstanceId, encrypted: EncryptedSecret): String? {
        return try {
            val key = existingKey(aliasOf(instanceId)) ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = Base64.getDecoder().decode(encrypted.ivBase64)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            val plain = cipher.doFinal(Base64.getDecoder().decode(encrypted.ciphertextBase64))
            String(plain, Charsets.UTF_8)
        } catch (recoverable: GeneralSecurityException) {
            // A changed key, a tampered ciphertext and a wrong vector all land here. None of
            // them is worth a stack trace in a log that would stand next to the ciphertext, and
            // none of them is recoverable by retrying: the key has to be entered again.
            null
        } catch (malformedBase64: IllegalArgumentException) {
            null
        } catch (keystoreUnavailable: IOException) {
            null
        }
    }

    override fun forget(instanceId: InstanceId) {
        try {
            loadKeyStore().deleteEntry(aliasOf(instanceId))
        } catch (alreadyGone: GeneralSecurityException) {
            // Removing what is not there is the desired end state, not a failure.
        } catch (keystoreUnavailable: IOException) {
        }
    }

    private fun keyFor(instanceId: InstanceId): SecretKey {
        val alias = aliasOf(instanceId)
        return existingKey(alias) ?: generateKey(alias)
    }

    private fun existingKey(alias: String): SecretKey? =
        loadKeyStore().getKey(alias, null) as? SecretKey

    private fun generateKey(alias: String): SecretKey {
        val specification = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            // States the guarantee this class depends on rather than relying on the default.
            .setRandomizedEncryptionRequired(true)
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(specification)
        return generator.generateKey()
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun aliasOf(instanceId: InstanceId): String = aliasPrefix + instanceId.value

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    companion object {

        const val DEFAULT_ALIAS_PREFIX = "widgora.instance."

        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val TAG_LENGTH_BITS = 128
    }
}
