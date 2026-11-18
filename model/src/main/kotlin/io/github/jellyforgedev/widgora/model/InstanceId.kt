package io.github.jellyforgedev.widgora.model

/**
 * Identifies one configured integration instance.
 *
 * This type exists from the first line of the domain model because several installations of the
 * same service must be configurable, enablable and displayable entirely independently. Adding
 * the key later would mean changing every signature that stores or reads instance data.
 */
@JvmInline
value class InstanceId(val value: String) {

    init {
        // The rejected value is not part of the message: it can come from untrusted input and
        // would otherwise end up in a log.
        require(IdentifierSyntax.isValid(value)) { INVALID_MESSAGE }
    }

    override fun toString(): String = value

    companion object {

        const val MAX_LENGTH: Int = IdentifierSyntax.MAX_LENGTH

        private const val INVALID_MESSAGE =
            "InstanceId must be 1 to 64 characters of a-z, 0-9, '-' or '_'"

        fun parseOrNull(raw: String): InstanceId? =
            if (IdentifierSyntax.isValid(raw)) InstanceId(raw) else null
    }
}
