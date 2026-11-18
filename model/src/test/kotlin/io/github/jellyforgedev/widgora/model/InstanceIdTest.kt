package io.github.jellyforgedev.widgora.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InstanceIdTest {

    @Test
    fun acceptsTheAllowedCharacterSet() {
        assertEquals("kuma-1_a0", InstanceId("kuma-1_a0").value)
    }

    @Test
    fun acceptsASingleCharacter() {
        assertEquals("a", InstanceId("a").value)
    }

    @Test
    fun acceptsTheMaximumLength() {
        val raw = "a".repeat(InstanceId.MAX_LENGTH)
        assertEquals(raw, InstanceId(raw).value)
    }

    @Test
    fun rejectsAnEmptyValue() {
        assertFailsWith<IllegalArgumentException> { InstanceId("") }
    }

    @Test
    fun rejectsOneCharacterTooMany() {
        assertFailsWith<IllegalArgumentException> { InstanceId("a".repeat(InstanceId.MAX_LENGTH + 1)) }
    }

    @Test
    fun rejectsUppercase() {
        assertFailsWith<IllegalArgumentException> { InstanceId("Kuma") }
    }

    @Test
    fun rejectsCharactersOutsideTheWhitelist() {
        val rejected = listOf(
            "kuma 1",
            "kuma.1",
            "kuma/1",
            "kuma:1",
            "kuma+1",
            "kuma%201",
            "kuma\u00e41",
            "kuma\u00001",
            "kuma\n1",
            "\u00fcber",
        )
        for (raw in rejected) {
            assertFailsWith<IllegalArgumentException>(raw) { InstanceId(raw) }
        }
    }

    @Test
    fun parseOrNullReturnsNullInsteadOfThrowing() {
        assertNull(InstanceId.parseOrNull("Kuma 1"))
        assertEquals("kuma1", InstanceId.parseOrNull("kuma1")?.value)
    }

    @Test
    fun theRejectedValueIsNotPartOfTheMessage() {
        val secretish = "a".repeat(InstanceId.MAX_LENGTH + 1)
        val failure = assertFailsWith<IllegalArgumentException> { InstanceId(secretish) }
        assertEquals(false, failure.message?.contains(secretish))
    }
}
