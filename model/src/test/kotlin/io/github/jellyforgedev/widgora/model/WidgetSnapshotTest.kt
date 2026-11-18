package io.github.jellyforgedev.widgora.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WidgetSnapshotTest {

    @Test
    fun keepsDisplayTextThatIsShortEnough() {
        val snapshot = WidgetSnapshot.truncating(
            widgetId = WidgetId("kuma-overall"),
            title = "Uptime Kuma",
            primaryValue = "12 up, 1 down",
            updatedAtEpochMs = 1_700_000_000_000,
            iconToken = "server",
            accentToken = "accent-ok",
        )
        assertEquals("Uptime Kuma", snapshot.title)
        assertEquals("12 up, 1 down", snapshot.primaryValue)
    }

    @Test
    fun capsAnOverLongTitle() {
        val snapshot = snapshot(title = "a".repeat(200))
        assertEquals(WidgetSnapshot.MAX_DISPLAY_TEXT_LENGTH, snapshot.title.length)
    }

    @Test
    fun capsAnOverLongPrimaryValue() {
        val snapshot = snapshot(primaryValue = "b".repeat(200))
        assertEquals(WidgetSnapshot.MAX_DISPLAY_TEXT_LENGTH, snapshot.primaryValue.length)
    }

    @Test
    fun cappingCountsCodePointsAndNeverSplitsASurrogatePair() {
        val snapshot = snapshot(title = EMOJI.repeat(100))
        val title = snapshot.title
        assertEquals(64, title.codePointCount(0, title.length))
        assertEquals(128, title.length)
        assertTrue(title[title.length - 2].isHighSurrogate())
        assertTrue(title[title.length - 1].isLowSurrogate())
    }

    @Test
    fun dropsAnEmojiThatWouldNotFitWholly() {
        val snapshot = snapshot(title = "a".repeat(64) + EMOJI)
        assertEquals("a".repeat(64), snapshot.title)
    }

    @Test
    fun keepsAnEmojiThatStillFitsCompletely() {
        val snapshot = snapshot(title = "a".repeat(63) + EMOJI + "b".repeat(10))
        assertEquals("a".repeat(63) + EMOJI, snapshot.title)
        assertEquals(65, snapshot.title.length)
        assertEquals(64, snapshot.title.codePointCount(0, snapshot.title.length))
    }

    @Test
    fun textThatIsLongInCharsButShortInCodePointsIsKeptWhole() {
        val original = EMOJI.repeat(35)
        val snapshot = snapshot(title = original)
        assertEquals(original, snapshot.title)
        assertEquals(70, snapshot.title.length)
    }

    @Test
    fun theConstructorRejectsTextTheFactoryWouldHaveCapped() {
        assertFailsWith<IllegalArgumentException> { strict(title = "a".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { strict(primaryValue = "b".repeat(65)) }
    }

    @Test
    fun theConstructorLimitIsCountedInCodePointsToo() {
        strict(title = EMOJI.repeat(64))
        assertFailsWith<IllegalArgumentException> { strict(title = EMOJI.repeat(65)) }
    }

    @Test
    fun copyCannotEscapeTheLimit() {
        assertFailsWith<IllegalArgumentException> { strict().copy(title = "a".repeat(65)) }
    }

    @Test
    fun aBlankTitleIsRejected() {
        assertFailsWith<IllegalArgumentException> { strict(title = "   ") }
    }

    @Test
    fun aBlankPrimaryValueIsAccepted() {
        assertEquals("", strict(primaryValue = "").primaryValue)
    }

    @Test
    fun aNegativeTimestampIsRejected() {
        assertFailsWith<IllegalArgumentException> { strict(updatedAtEpochMs = -1) }
    }

    @Test
    fun tokensAreRejectedRatherThanCapped() {
        assertFailsWith<IllegalArgumentException> { strict(iconToken = "Server Icon") }
        assertFailsWith<IllegalArgumentException> { strict(accentToken = "accent/ok") }
        assertFailsWith<IllegalArgumentException> { strict(iconToken = "") }
    }

    private fun snapshot(
        title: String = "Uptime Kuma",
        primaryValue: String = "12 up",
    ): WidgetSnapshot = WidgetSnapshot.truncating(
        widgetId = WidgetId("kuma-overall"),
        title = title,
        primaryValue = primaryValue,
        updatedAtEpochMs = 1_700_000_000_000,
        iconToken = "server",
        accentToken = "accent-ok",
    )

    private fun strict(
        title: String = "Uptime Kuma",
        primaryValue: String = "12 up",
        updatedAtEpochMs: Long = 1_700_000_000_000,
        iconToken: String = "server",
        accentToken: String = "accent-ok",
    ): WidgetSnapshot = WidgetSnapshot(
        widgetId = WidgetId("kuma-overall"),
        title = title,
        primaryValue = primaryValue,
        updatedAtEpochMs = updatedAtEpochMs,
        iconToken = iconToken,
        accentToken = accentToken,
    )

    private companion object {
        /** THUMBS UP SIGN, U+1F44D: one code point, two chars. */
        const val EMOJI = "\uD83D\uDC4D"
    }
}
