package io.github.jellyforgedev.widgora.model

/**
 * One widget's prepared display values at a point in time.
 *
 * There is structurally no field for an address, a host name, a token or a raw response,
 * because an instance of this type is what a host application is handed through the bridge.
 * Widening the field set widens what WIDGORA gives away, which is why the set is frozen by a
 * test rather than left to review alone.
 *
 * Two kinds of input are treated differently on purpose. Display text comes from a service
 * response and is capped by [truncating]: an over-long monitor name must not be able to make a
 * widget disappear. Identifiers and tokens are rejected instead, because they originate in
 * WIDGORA's own code, where an invalid value is a defect and not untrusted input.
 *
 * The constructor itself stays strict, so the invariant holds for every instance however it was
 * built, including one produced by `copy`.
 */
data class WidgetSnapshot(
    val widgetId: WidgetId,
    val title: String,
    val primaryValue: String,
    val updatedAtEpochMs: Long,
    val iconToken: String,
    val accentToken: String,
) {

    init {
        // A nameless widget is unusable on a television, so an empty title is a malformed
        // response for the integration to classify, not something to render.
        require(title.isNotBlank()) { "title must not be blank" }
        require(title.codePointCount() <= MAX_DISPLAY_TEXT_LENGTH) { TITLE_TOO_LONG }
        require(primaryValue.codePointCount() <= MAX_DISPLAY_TEXT_LENGTH) { VALUE_TOO_LONG }
        require(updatedAtEpochMs >= 0) { "updatedAtEpochMs must not be negative" }
        require(IdentifierSyntax.isValid(iconToken, TOKEN_MAX_LENGTH)) { "iconToken: " + TOKEN_SYNTAX }
        require(IdentifierSyntax.isValid(accentToken, TOKEN_MAX_LENGTH)) { "accentToken: " + TOKEN_SYNTAX }
    }

    companion object {

        const val MAX_DISPLAY_TEXT_LENGTH = 64

        private const val TITLE_TOO_LONG = "title exceeds 64 code points; use truncating()"
        private const val VALUE_TOO_LONG = "primaryValue exceeds 64 code points; use truncating()"
        private const val TOKEN_MAX_LENGTH = 64
        private const val TOKEN_SYNTAX = "1 to 64 characters of a-z, 0-9, '-' or '_'"

        /**
         * Builds a snapshot from service-provided display text, capping [title] and
         * [primaryValue] at [MAX_DISPLAY_TEXT_LENGTH] code points.
         *
         * Capping counts code points and never cuts inside a surrogate pair, so a string ending
         * in an emoji cannot be reduced to an unpaired surrogate that a renderer would display
         * as a replacement glyph.
         */
        fun truncating(
            widgetId: WidgetId,
            title: String,
            primaryValue: String,
            updatedAtEpochMs: Long,
            iconToken: String,
            accentToken: String,
        ): WidgetSnapshot = WidgetSnapshot(
            widgetId = widgetId,
            title = title.truncateToCodePoints(MAX_DISPLAY_TEXT_LENGTH),
            primaryValue = primaryValue.truncateToCodePoints(MAX_DISPLAY_TEXT_LENGTH),
            updatedAtEpochMs = updatedAtEpochMs,
            iconToken = iconToken,
            accentToken = accentToken,
        )
    }
}

private fun String.codePointCount(): Int = codePointCount(0, length)

private fun String.truncateToCodePoints(maxCodePoints: Int): String {
    // One code point is at most two chars, so a short string can never exceed the limit and
    // does not need to be walked.
    if (length <= maxCodePoints) return this
    if (codePointCount(0, length) <= maxCodePoints) return this
    return substring(0, offsetByCodePoints(0, maxCodePoints))
}
