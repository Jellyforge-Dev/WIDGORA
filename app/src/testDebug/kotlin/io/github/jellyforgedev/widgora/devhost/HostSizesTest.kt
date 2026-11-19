package io.github.jellyforgedev.widgora.devhost

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The two sizes the host offers.
 *
 * The arithmetic is simple and the cases are not: a maximum larger than the screen, a provider
 * that declares no maximum at all, and a clamp that makes both sizes meet. Each of those would
 * otherwise show up as a widget drawn past the edge of a television, or as a button that appears
 * to do nothing.
 */
class HostSizesTest {

    @Test
    fun bothSizesComeFromTheProviderWhenTheyFit() {
        val sizes = HostSizes.offered(
            declaredWidthDp = 300,
            declaredHeightDp = 160,
            maxWidthDp = 640,
            maxHeightDp = 320,
            availableWidthDp = 864,
            availableHeightDp = 486,
        )

        assertEquals(listOf(HostSize(300, 160), HostSize(640, 320)), sizes)
    }

    /** A declared maximum wider than the screen would be drawn past the edge of it. */
    @Test
    fun aMaximumLargerThanTheScreenIsBroughtBackToTheScreen() {
        val sizes = HostSizes.offered(
            declaredWidthDp = 300,
            declaredHeightDp = 160,
            maxWidthDp = 2000,
            maxHeightDp = 2000,
            availableWidthDp = 864,
            availableHeightDp = 486,
        )

        assertEquals(listOf(HostSize(300, 160), HostSize(864, 486)), sizes)
    }

    /**
     * A provider need not declare a maximum, and one is not invented for it.
     *
     * The larger option simply does not appear, which is what that provider actually offers.
     */
    @Test
    fun aProviderWithoutAMaximumGetsOneSize() {
        val sizes = HostSizes.offered(
            declaredWidthDp = 300,
            declaredHeightDp = 160,
            maxWidthDp = 0,
            maxHeightDp = 0,
            availableWidthDp = 864,
            availableHeightDp = 486,
        )

        assertEquals(listOf(HostSize(300, 160)), sizes)
    }

    /** A maximum smaller than the default is not a smaller option; it is no option. */
    @Test
    fun aMaximumBelowTheDefaultDoesNotShrinkTheWidget() {
        val sizes = HostSizes.offered(
            declaredWidthDp = 300,
            declaredHeightDp = 160,
            maxWidthDp = 100,
            maxHeightDp = 100,
            availableWidthDp = 864,
            availableHeightDp = 486,
        )

        assertEquals(listOf(HostSize(300, 160)), sizes)
    }

    /**
     * When the screen is too small for either, both end up at the screen and only one is
     * offered.
     *
     * A switch between two sizes that look identical reads as a switch that is broken.
     */
    @Test
    fun aScreenSmallerThanBothLeavesOneSize() {
        val sizes = HostSizes.offered(
            declaredWidthDp = 300,
            declaredHeightDp = 160,
            maxWidthDp = 640,
            maxHeightDp = 320,
            availableWidthDp = 200,
            availableHeightDp = 120,
        )

        assertEquals(listOf(HostSize(200, 120)), sizes)
    }

    @Test
    fun aScreenWithNoAreaIsRefusedRatherThanGuessedAt() {
        assertFailsWith<IllegalArgumentException> {
            HostSizes.offered(300, 160, 640, 320, availableWidthDp = 0, availableHeightDp = 486)
        }
    }
}
