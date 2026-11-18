package io.github.jellyforgedev.widgora.core.data

import io.github.jellyforgedev.widgora.model.InstanceId
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class InstanceConfigTest {

    @Test
    fun keepsWhatItWasGiven() {
        val config = config()
        assertEquals(InstanceId("home-kuma"), config.instanceId)
        assertEquals("Uptime Kuma", config.displayName)
        assertEquals("https://kuma.example.invalid", config.baseUrl)
        assertFalse(config.enabled)
    }

    @Test
    fun aBlankDisplayNameIsRejected() {
        assertFailsWith<IllegalArgumentException> { config(displayName = "") }
        assertFailsWith<IllegalArgumentException> { config(displayName = "   ") }
    }

    @Test
    fun anOverLongDisplayNameIsRejected() {
        config(displayName = "n".repeat(InstanceConfig.MAX_DISPLAY_NAME_LENGTH))
        assertFailsWith<IllegalArgumentException> {
            config(displayName = "n".repeat(InstanceConfig.MAX_DISPLAY_NAME_LENGTH + 1))
        }
    }

    /**
     * The point of the strict constructor: an address is checked where the user can still fix
     * it, not when a fetch fails much later.
     */
    @Test
    fun anAddressThatIsNotNormalisedIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            config(baseUrl = "http://kuma.example.invalid")
        }
        assertFailsWith<IllegalArgumentException> {
            config(baseUrl = "https://kuma.example.invalid/")
        }
        assertFailsWith<IllegalArgumentException> { config(baseUrl = "kuma.example.invalid") }
        assertFailsWith<IllegalArgumentException> {
            config(baseUrl = "https://user:pass@kuma.example.invalid")
        }
        assertFailsWith<IllegalArgumentException> {
            config(baseUrl = "https://kuma.example.invalid?a=b")
        }
    }

    @Test
    fun copyCannotEscapeTheRules() {
        assertFailsWith<IllegalArgumentException> {
            config().copy(baseUrl = "http://x.example.invalid")
        }
        assertFailsWith<IllegalArgumentException> { config().copy(displayName = "") }
    }

    @Test
    fun anAddressWithAPathIsAccepted() {
        assertEquals(
            "https://host.example.invalid/kuma",
            config(baseUrl = "https://host.example.invalid/kuma").baseUrl,
        )
    }

    /**
     * Freezes the field set. This is the type that describes where a service lives, so a new
     * field is a decision about what the application keeps about the user's infrastructure.
     * A field for the API key, or one permitting cleartext, must never appear here.
     */
    @Test
    fun theFieldSetIsFrozen() {
        val expected = listOf("baseUrl", "displayName", "enabled", "instanceId")
        val actual = InstanceConfig::class.java.declaredFields
            .filterNot { field -> field.isSynthetic || Modifier.isStatic(field.modifiers) }
            .map { field -> field.name }
            .sorted()
        assertEquals(expected, actual)
    }

    private fun config(
        displayName: String = "Uptime Kuma",
        baseUrl: String = "https://kuma.example.invalid",
        enabled: Boolean = false,
    ): InstanceConfig = InstanceConfig(
        instanceId = InstanceId("home-kuma"),
        displayName = displayName,
        baseUrl = baseUrl,
        enabled = enabled,
    )
}
