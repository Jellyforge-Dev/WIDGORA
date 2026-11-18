package io.github.jellyforgedev.widgora.model

/**
 * Opaque, stable identifier of a single widget.
 *
 * A widget identifier is the only information a deep link carries, so its syntax is part of the
 * bridge's security boundary rather than a formatting preference. The whitelist keeps a
 * `widgora://widget/{widget_id}` reference from gaining a path segment, a query string or a
 * fragment. The constraint is enforced here, on the emitting side, and again where such a
 * reference is received.
 */
@JvmInline
value class WidgetId(val value: String) {

    init {
        require(IdentifierSyntax.isValid(value)) { INVALID_MESSAGE }
    }

    override fun toString(): String = value

    companion object {

        const val MAX_LENGTH: Int = IdentifierSyntax.MAX_LENGTH

        private const val INVALID_MESSAGE =
            "WidgetId must be 1 to 64 characters of a-z, 0-9, '-' or '_'"

        fun parseOrNull(raw: String): WidgetId? =
            if (IdentifierSyntax.isValid(raw)) WidgetId(raw) else null
    }
}
