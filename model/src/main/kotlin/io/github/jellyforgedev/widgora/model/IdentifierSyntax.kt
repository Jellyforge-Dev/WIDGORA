package io.github.jellyforgedev.widgora.model

/**
 * Shared syntax for every identifier that can leave this application.
 *
 * The allowed set is a whitelist, not a list of rejected characters, and it is deliberately
 * narrower than what a URI path segment permits: an identifier must never be able to introduce
 * a second path segment, a query string or a fragment into `widgora://widget/{widget_id}`.
 *
 * The maximum length is passed in rather than fixed here, because the identifier types do not
 * share one limit: a widget identifier is composed from an instance identifier, so the instance
 * limit has to leave room for the suffix.
 */
internal object IdentifierSyntax {

    fun isValid(raw: String, maxLength: Int): Boolean {
        if (raw.isEmpty() || raw.length > maxLength) return false
        for (character in raw) {
            val allowed = character in 'a'..'z' ||
                character in '0'..'9' ||
                character == '-' ||
                character == '_'
            if (!allowed) return false
        }
        return true
    }
}
