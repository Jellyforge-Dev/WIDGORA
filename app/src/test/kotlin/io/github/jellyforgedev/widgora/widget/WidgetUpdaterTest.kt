package io.github.jellyforgedev.widgora.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which providers are asked about placed widgets, and what comes of their answers.
 *
 * This is the correction of a real defect, and the defect was invisible: the orphan sweep removed
 * every stored entry that did not belong to a widget of **one** provider. With one provider that
 * was right. The day a second type is declared, every entry belonging to it looks like an entry
 * for a widget nobody placed — and the next push deletes the settings of a widget sitting on
 * somebody's television.
 *
 * So the two halves are checked separately: that every named provider is asked, and that the
 * names asked about include the ones a mapping knows even when the package manager is reticent.
 */
class WidgetUpdaterTest {

    /** The one that matters: a second provider's widgets are counted, not ignored. */
    @Test
    fun everyProviderIsAskedAndAllTheirWidgetsCount() {
        val asked = mutableListOf<String>()

        val placed = WidgetUpdater.placedIds(listOf(FIRST, SECOND)) { className ->
            asked += className
            when (className) {
                FIRST -> intArrayOf(7, 11)
                SECOND -> intArrayOf(23)
                else -> IntArray(0)
            }
        }

        assertEquals(listOf(FIRST, SECOND), asked)
        assertEquals(setOf(7, 11, 23), placed)
    }

    /** No provider means no placed widget, which is what makes everything stored an orphan. */
    @Test
    fun withoutAnyProviderNothingIsPlaced() {
        assertEquals(emptySet(), WidgetUpdater.placedIds(emptyList()) { IntArray(0) })
    }

    /** A provider named twice must not make one widget look like two. */
    @Test
    fun aProviderNamedTwiceDoesNotDuplicateItsWidgets() {
        val placed = WidgetUpdater.placedIds(listOf(FIRST, FIRST)) { intArrayOf(7) }

        assertEquals(setOf(7), placed)
    }

    // --- Which providers end up being asked ----------------------------------------------------

    /**
     * An installed provider this version has no mapping for is still asked.
     *
     * Otherwise its widgets would not be counted, and the entries belonging to them would be
     * swept away as orphans — which is exactly the defect this file exists for, one version
     * later.
     */
    @Test
    fun anInstalledProviderWithoutAMappingIsStillAsked() {
        val names = WidgetUpdater.providerClassNames(listOf(UNMAPPED))

        assertTrue(UNMAPPED in names, names.toString())
    }

    /**
     * A mapped provider the package manager does not name is asked anyway.
     *
     * The asymmetry is deliberate, and the reason is what being wrong costs in each direction. A
     * provider asked for nothing costs one call returning an empty array; a provider not asked
     * about costs the stored settings of every widget it has.
     */
    @Test
    fun aMappedProviderIsAskedEvenIfTheInstalledListIsShort() {
        val names = WidgetUpdater.providerClassNames(emptyList())

        assertEquals(WidgetProviders.declaredClassNames, names)
    }

    /** A provider in both lists is asked once. */
    @Test
    fun aProviderInBothListsIsNamedOnce() {
        val declared = WidgetProviders.declaredClassNames
        val names = WidgetUpdater.providerClassNames(declared)

        assertEquals(declared, names)
        assertEquals(names.size, names.distinct().size, names.toString())
    }

    private companion object {

        const val FIRST = "io.github.jellyforgedev.widgora.widget.CountsWidgetProvider"
        const val SECOND = "io.github.jellyforgedev.widgora.widget.FutureProvider"
        const val UNMAPPED = "io.github.jellyforgedev.widgora.widget.NotMappedYetProvider"
    }
}
