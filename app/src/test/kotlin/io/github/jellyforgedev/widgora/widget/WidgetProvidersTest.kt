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

    @Test
    fun theOtherTwoTypesAreServedByTheirOwnProviders() {
        assertEquals(
            CertificatesWidgetProvider::class.java.name,
            WidgetProviders.classNameOf(WidgetKind.CERTIFICATES),
        )
        assertEquals(
            SelectionWidgetProvider::class.java.name,
            WidgetProviders.classNameOf(WidgetKind.SELECTION),
        )
    }

    /**
     * Every kind has a provider, and no two kinds share one.
     *
     * The second half is the one with teeth. A host binds a placement to a provider class name and
     * the configuration step reads the kind back from it, so two kinds behind one class would mean
     * a widget placed as one and configured as the other.
     */
    @Test
    fun everyKindHasAProviderAndNoneIsShared() {
        val classNames = WidgetKind.entries.map { kind -> WidgetProviders.classNameOf(kind) }

        assertEquals(emptyList(), classNames.filter { it == null }, "a kind has no provider")
        assertEquals(classNames.size, classNames.distinct().size, classNames.toString())
    }

    @Test
    fun aProviderClassNameMapsBackToItsType() {
        for (kind in WidgetKind.entries) {
            val className = assertNotNull(WidgetProviders.classNameOf(kind), kind.name)
            assertEquals(kind, WidgetProviders.kindOf(className), className)
        }
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
