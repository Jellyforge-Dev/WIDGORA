package io.github.jellyforgedev.widgora.devhost

/** One size a widget is shown at, in density-independent pixels. */
data class HostSize(val widthDp: Int, val heightDp: Int)

/**
 * The sizes this host offers, derived from what the provider declares.
 *
 * ### Why the sizes are not simply written down here
 *
 * The point of looking at a widget in a host is to look at the widget as declared, not at a box
 * a test harness happened to choose. Both sizes therefore come from the provider's own metadata:
 * the smaller one is the default size it asks for, the larger one the maximum it says it can be
 * resized to.
 *
 * ### Why they are clamped
 *
 * A declared maximum can be larger than the screen it is shown on, and a widget drawn wider than
 * the display teaches nothing except that it was drawn wider than the display. The available
 * area is the screen minus the margin a television may cut off, so what is on screen is what a
 * viewer would actually see.
 *
 * Clamping can make the two sizes meet. That is reported as one size rather than as two
 * identical ones, because a switch between two things that look the same is a switch that
 * appears broken.
 */
object HostSizes {

    /**
     * The steps, smallest first.
     *
     * Three candidates are considered: the size the provider asks for by default, the size at
     * which it changes its layout, and the largest it says it can be resized to. Each is clamped
     * to the screen and to the step before it, and a candidate that lands on the step before it
     * is dropped — a switch between two sizes that look identical is a switch that appears
     * broken.
     *
     * The middle step exists because the two layouts are the thing there is to look at. Without
     * it, the smallest and the largest box would both be reachable and the boundary between the
     * two layouts would not be.
     *
     * @param declaredWidthDp the provider's default width
     * @param declaredHeightDp the provider's default height
     * @param breakpointWidthDp the width at which the provider changes layout
     * @param breakpointHeightDp the height at which the provider changes layout
     * @param maxWidthDp the provider's maximum resize width, or zero when it declares none
     * @param maxHeightDp the provider's maximum resize height, or zero when it declares none
     * @param availableWidthDp the usable width of the screen, overscan already subtracted
     * @param availableHeightDp the usable height of the screen
     */
    @Suppress("LongParameterList")
    fun offered(
        declaredWidthDp: Int,
        declaredHeightDp: Int,
        breakpointWidthDp: Int,
        breakpointHeightDp: Int,
        maxWidthDp: Int,
        maxHeightDp: Int,
        availableWidthDp: Int,
        availableHeightDp: Int,
    ): List<HostSize> {
        require(availableWidthDp > 0 && availableHeightDp > 0) {
            "the available area must have a size"
        }

        val candidates = listOf(
            declaredWidthDp to declaredHeightDp,
            breakpointWidthDp to breakpointHeightDp,
            // A provider need not declare a maximum. A zero never grows past the step before it
            // and is dropped, which is the truth about such a provider rather than a size
            // invented on its behalf.
            maxWidthDp to maxHeightDp,
        )

        val steps = mutableListOf<HostSize>()
        for ((width, height) in candidates) {
            val previous = steps.lastOrNull()
            val step = HostSize(
                widthDp = width
                    .coerceAtLeast(previous?.widthDp ?: 1)
                    .coerceIn(1, availableWidthDp),
                heightDp = height
                    .coerceAtLeast(previous?.heightDp ?: 1)
                    .coerceIn(1, availableHeightDp),
            )
            if (step != previous) steps += step
        }
        return steps
    }
}
