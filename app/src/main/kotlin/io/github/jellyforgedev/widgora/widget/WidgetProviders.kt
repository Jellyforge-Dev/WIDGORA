package io.github.jellyforgedev.widgora.widget

import io.github.jellyforgedev.widgora.core.data.WidgetKind

/**
 * Which provider class serves which kind of widget.
 *
 * ### Why this mapping is written down once
 *
 * Three questions need the same answer and used to derive it separately: the configuration step
 * has to know what kind of widget it is configuring, the push has to know which widgets a given
 * model applies to, and the cleanup has to know every provider this package declares. A second
 * answer written somewhere else is how a future type comes to be configured as one thing and
 * drawn as another.
 *
 * ### Why the mapping is a `when` and not a map
 *
 * The compiler checks a `when` over an enum for completeness. Adding a kind to [WidgetKind]
 * therefore fails to compile until it is named here, which is the point: a kind added without a
 * provider would silently be one nothing can place, and a provider added without a kind would be
 * a widget whose configuration step does not know what it is looking at.
 *
 * Two of the three kinds have no provider yet. That is stated as `null` rather than left out, so
 * the absence is a decision in this file rather than a gap in it.
 */
object WidgetProviders {

    /**
     * The provider class that places widgets of [kind], or `null` while none exists.
     *
     * The class is named through its own class object rather than as a string literal, so a
     * rename is a compile error instead of a mapping that quietly stops matching.
     */
    fun classNameOf(kind: WidgetKind): String? = when (kind) {
        WidgetKind.STATUS -> CountsWidgetProvider::class.java.name

        // Decided and not implemented; see ARCHITECTURE.md section 5a. The configuration screen
        // is built so that adding a provider here is all it needs on this side.
        WidgetKind.CERTIFICATES, WidgetKind.SELECTION -> null
    }

    /** The kind a provider class serves, or `null` for a class that is not one of ours. */
    fun kindOf(providerClassName: String): WidgetKind? =
        WidgetKind.entries.firstOrNull { kind -> classNameOf(kind) == providerClassName }

    /** Every provider class this package declares, in the order the kinds are declared in. */
    val declaredClassNames: List<String>
        get() = WidgetKind.entries.mapNotNull { kind -> classNameOf(kind) }
}
