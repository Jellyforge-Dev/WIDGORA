package io.github.jellyforgedev.widgora.model

/**
 * Shared syntax for every identifier that can leave this application.
 *
 * The allowed set is a whitelist, not a list of rejected characters, and it is deliberately
 * narrower than what a URI path segment permits: an identifier must never be able to introduce
 * a second path segment, a query string or a fragment into `widgora://widget/{widget_id}`.
 */
internal object IdentifierSyntax {

    const val MAX_LENGTH = 64

    fun isValid(raw: String): Boolean {
        if (raw.isEmpty() || raw.length > MAX_LENGTH) return false
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
