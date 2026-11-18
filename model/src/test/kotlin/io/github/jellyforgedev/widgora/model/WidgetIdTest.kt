package io.github.jellyforgedev.widgora.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WidgetIdTest {

    @Test
    fun acceptsTheAllowedCharacterSet() {
        assertEquals("kuma_overall-1", WidgetId("kuma_overall-1").value)
    }

    @Test
    fun acceptsTheMaximumLength() {
        val raw = "w".repeat(WidgetId.MAX_LENGTH)
        assertEquals(raw, WidgetId(raw).value)
    }

    @Test
    fun rejectsAnEmptyValue() {
        assertFailsWith<IllegalArgumentException> { WidgetId("") }
    }

    @Test
    fun rejectsOneCharacterTooMany() {
        assertFailsWith<IllegalArgumentException> { WidgetId("w".repeat(WidgetId.MAX_LENGTH + 1)) }
    }

    /**
     * These are the characters that would let an identifier reshape
     * `widgora://widget/{widget_id}` into something else: a second path segment, a query
     * string, a fragment, an authority or a scheme of its own.
     */
    @Test
    fun rejectsCharactersThatCouldReshapeADeepLink() {
        val rejected = listOf(
            "widget/other",
            "widget?a=b",
            "widget#top",
            "widget\\other",
            "widget@host",
            "widget:1",
            "widget//host",
            "..",
            "%2f",
            "widget 1",
        )
        for (raw in rejected) {
            assertFailsWith<IllegalArgumentException>(raw) { WidgetId(raw) }
        }
    }

    @Test
    fun parseOrNullReturnsNullInsteadOfThrowing() {
        assertNull(WidgetId.parseOrNull("widget/other"))
        assertEquals("widget", WidgetId.parseOrNull("widget")?.value)
    }
}
