package io.github.jellyforgedev.widgora.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jellyforgedev.widgora.model.InstanceId
import java.security.KeyStore
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real secret store, on a real keystore. These tests need the platform and therefore do not
 * run in the ordinary build or in continuous integration.
 *
 * Each run uses its own alias prefix and removes its keys afterwards, so that running the suite
 * does not leave material behind on the device.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSecretStoreTest {

    private val aliasPrefix = "widgora.test." + System.nanoTime() + "."
    private val store = AndroidKeystoreSecretStore(aliasPrefix = aliasPrefix)
    private val instanceId = InstanceId("probe")

    @After
    fun removeTestKeys() {
        store.forget(instanceId)
    }

    @Test
    fun aSecretSurvivesEncryptionAndDecryption() {
        val encrypted = store.encrypt(instanceId, SECRET)

        assertEquals(SECRET, store.decrypt(instanceId, encrypted))
    }

    /**
     * The key is generated with randomised encryption required, so the platform supplies a fresh
     * initialisation vector each time. Reusing one with the same key would break the mode, which
     * is why this is asserted rather than assumed.
     */
    @Test
    fun theSamePlainTextEncryptsDifferentlyEveryTime() {
        val first = store.encrypt(instanceId, SECRET)
        val second = store.encrypt(instanceId, SECRET)

        assertNotEquals(first.ivBase64, second.ivBase64)
        assertNotEquals(first.ciphertextBase64, second.ciphertextBase64)
        assertEquals(SECRET, store.decrypt(instanceId, first))
        assertEquals(SECRET, store.decrypt(instanceId, second))
    }

    @Test
    fun theCiphertextDoesNotContainThePlainText() {
        val encrypted = store.encrypt(instanceId, SECRET)
        val raw = String(
            Base64.getDecoder().decode(encrypted.ciphertextBase64),
            Charsets.ISO_8859_1,
        )

        assertFalse(raw.contains(SECRET))
    }

    @Test
    fun forgettingRemovesTheAliasAndMakesTheSecretUnreadable() {
        val encrypted = store.encrypt(instanceId, SECRET)
        assertTrue(aliasExists())

        store.forget(instanceId)

        assertFalse(aliasExists())
        assertNull(store.decrypt(instanceId, encrypted))
    }

    /** A tampered ciphertext fails the authentication tag and is reported as unreadable. */
    @Test
    fun aTamperedCiphertextIsRefused() {
        val encrypted = store.encrypt(instanceId, SECRET)
        val bytes = Base64.getDecoder().decode(encrypted.ciphertextBase64)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        val tampered = encrypted.copy(
            ciphertextBase64 = Base64.getEncoder().encodeToString(bytes),
        )

        assertNull(store.decrypt(instanceId, tampered))
    }

    @Test
    fun aTamperedVectorIsRefused() {
        val encrypted = store.encrypt(instanceId, SECRET)
        val bytes = Base64.getDecoder().decode(encrypted.ivBase64)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        val tampered = encrypted.copy(ivBase64 = Base64.getEncoder().encodeToString(bytes))

        assertNull(store.decrypt(instanceId, tampered))
    }

    @Test
    fun twoInstancesDoNotShareAKey() {
        val other = InstanceId("probe-two")
        try {
            val encrypted = store.encrypt(instanceId, SECRET)
            store.encrypt(other, SECRET)

            store.forget(other)

            // Removing one instance must leave the other readable.
            assertEquals(SECRET, store.decrypt(instanceId, encrypted))
        } finally {
            store.forget(other)
        }
    }

    private fun aliasExists(): Boolean {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.containsAlias(aliasPrefix + instanceId.value)
    }

    private companion object {
        const val SECRET = "uk1_instrumented_probe_secret"
    }
}
