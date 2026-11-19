package io.github.jellyforgedev.widgora.devhost

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The steps the host offers.
 *
 * The arithmetic is simple and the cases are not: a maximum larger than the screen, a provider
 * that declares no maximum at all, a breakpoint that coincides with the size below it, and a
 * clamp that makes two steps meet. Each of those would otherwise show up as a widget drawn past
 * the edge of a television, or as a button that appears to do nothing.
 */
class HostSizesTest {

    @Test
    fun allThreeStepsAreOfferedWhenTheyFit() {
        assertEquals(
            listOf(HostSize(300, 170), HostSize(300, 250), HostSize(640, 320)),
            offered(),
        )
    }

    /** A declared maximum wider than the screen would be drawn past the edge of it. */
    @Test
    fun aMaximumLargerThanTheScreenIsBroughtBackToTheScreen() {
        assertEquals(
            listOf(HostSize(300, 170), HostSize(300, 250), HostSize(864, 486)),
            offered(maxWidthDp = 2000, maxHeightDp = 2000),
        )
    }

    /**
     * A provider need not declare a maximum, and one is not invented for it.
     *
     * The largest step simply does not appear, which is what that provider actually offers.
     */
    @Test
    fun aProviderWithoutAMaximumLosesItsLargestStep() {
        assertEquals(
            listOf(HostSize(300, 170), HostSize(300, 250)),
            offered(maxWidthDp = 0, maxHeightDp = 0),
        )
    }

    /** A maximum below the step before it is not a smaller option; it is no option. */
    @Test
    fun aMaximumBelowTheStepBeforeItIsDropped() {
        assertEquals(
            listOf(HostSize(300, 170), HostSize(300, 250)),
            offered(maxWidthDp = 100, maxHeightDp = 100),
        )
    }

    /**
     * A breakpoint at the declared size is not a step of its own.
     *
     * A widget with one layout declares it that way, and offering the same box twice would be a
     * switch that appears broken.
     */
    @Test
    fun aBreakpointAtTheDeclaredSizeIsDropped() {
        assertEquals(
            listOf(HostSize(300, 170), HostSize(640, 320)),
            offered(breakpointWidthDp = 300, breakpointHeightDp = 170),
        )
    }

    /**
     * When the screen is too small for any of them, they all meet and one is offered.
     *
     * A switch between sizes that look identical reads as a switch that is broken.
     */
    @Test
    fun aScreenSmallerThanEveryStepLeavesOne() {
        assertEquals(
            listOf(HostSize(200, 120)),
            offered(availableWidthDp = 200, availableHeightDp = 120),
        )
    }

    @Test
    fun aScreenWithNoAreaIsRefusedRatherThanGuessedAt() {
        assertFailsWith<IllegalArgumentException> { offered(availableWidthDp = 0) }
    }

    /**
     * The declared sizes of WIDGORA's own widget, on the usable area of a 1080p television:
     * 960 × 540 dp less the overscan margin of 48dp and 27dp on each side.
     */
    @Suppress("LongParameterList")
    private fun offered(
        declaredWidthDp: Int = 300,
        declaredHeightDp: Int = 170,
        breakpointWidthDp: Int = 300,
        breakpointHeightDp: Int = 250,
        maxWidthDp: Int = 640,
        maxHeightDp: Int = 320,
        availableWidthDp: Int = 864,
        availableHeightDp: Int = 486,
    ): List<HostSize> = HostSizes.offered(
        declaredWidthDp = declaredWidthDp,
        declaredHeightDp = declaredHeightDp,
        breakpointWidthDp = breakpointWidthDp,
        breakpointHeightDp = breakpointHeightDp,
        maxWidthDp = maxWidthDp,
        maxHeightDp = maxHeightDp,
        availableWidthDp = availableWidthDp,
        availableHeightDp = availableHeightDp,
    )
}
