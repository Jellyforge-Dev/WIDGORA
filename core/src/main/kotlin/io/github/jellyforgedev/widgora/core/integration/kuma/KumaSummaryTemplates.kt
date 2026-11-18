package io.github.jellyforgedev.widgora.core.integration.kuma

/**
 * The wording of the summary, supplied from outside this module.
 *
 * Each entry is a whole phrase for one count, not a single word: a language has to be free to
 * place the number where its grammar wants it, and to use a different form for one than for
 * many. That is why these are functions of the count rather than strings with a placeholder
 * this module would fill in — the caller can resolve an Android plural and hand back the
 * finished phrase.
 *
 * What stays here is the arithmetic and the order of the parts. What the words are, and in which
 * language, is not this module's business.
 */
class KumaSummaryTemplates(
    val up: (Int) -> String,
    val down: (Int) -> String,
    val pending: (Int) -> String,
    val maintenance: (Int) -> String,
    val unknown: (Int) -> String,
    /** What joins the parts. A language that does not separate with a comma can change it. */
    val separator: String = ", ",
) {

    companion object {

        /**
         * Only for tests and previews: it names the counts without claiming to be a language.
         * Production text comes from resources.
         */
        val Numeric = KumaSummaryTemplates(
            up = { count -> "$count up" },
            down = { count -> "$count down" },
            pending = { count -> "$count pending" },
            maintenance = { count -> "$count in maintenance" },
            unknown = { count -> "$count unknown" },
        )
    }
}
