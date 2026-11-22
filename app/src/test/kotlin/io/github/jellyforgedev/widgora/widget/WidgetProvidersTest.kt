package io.github.jellyforgedev.widgora.widget

import io.github.jellyforgedev.widgora.core.data.WidgetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The mapping between a widget type and the provider that places it. */
class WidgetProvidersTest {

    @Test
    fun theStatusTypeIsServedByTheCountsProvider() {
        assertEquals(
            CountsWidgetProvider::class.java.name,
            WidgetProviders.classNameOf(WidgetKind.STATUS),
        )
    }

    /**
     * The two types that are decided and not implemented say so.
     *
     * Stated as a test rather than left implicit, so that adding a provider for one of them comes
     * with a decision here instead of being discovered through a widget that cannot be
     * configured.
     */
    @Test
    fun theTwoFutureTypesHaveNoProviderYet() {
        assertNull(WidgetProviders.classNameOf(WidgetKind.CERTIFICATES))
        assertNull(WidgetProviders.classNameOf(WidgetKind.SELECTION))
    }

    @Test
    fun aProviderClassNameMapsBackToItsType() {
        assertEquals(
            WidgetKind.STATUS,
            WidgetProviders.kindOf(CountsWidgetProvider::class.java.name),
        )
    }

    /** A class that is not one of ours maps to nothing rather than to the one type that exists. */
    @Test
    fun aForeignClassNameMapsToNothing() {
        assertNull(WidgetProviders.kindOf("com.example.other.SomeWidgetProvider"))
        assertNull(WidgetProviders.kindOf(""))
    }

    /** Every declared class is one a kind names, and no two kinds name the same class. */
    @Test
    fun theDeclaredClassesAreExactlyTheMappedOnes() {
        val declared = WidgetProviders.declaredClassNames

        assertEquals(declared.size, declared.distinct().size, declared.toString())
        for (className in declared) {
            assertNotNull(WidgetProviders.kindOf(className), className)
        }
        assertEquals(
            WidgetKind.entries.count { kind -> WidgetProviders.classNameOf(kind) != null },
            declared.size,
        )
    }
}
