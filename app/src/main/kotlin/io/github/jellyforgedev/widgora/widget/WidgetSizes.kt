package io.github.jellyforgedev.widgora.widget

/**
 * The sizes the widget is declared for, and the size at which it changes shape.
 *
 * ### Why these numbers exist in code as well as in the provider's metadata
 *
 * They have to agree. The metadata is a promise to every host that reads it — "this is the
 * smallest box I work in" — and the layouts are what makes the promise true. Declaring
 * 240 × 120 while the smallest layout needs 300 × 160 is not a cosmetic mismatch: a host that
 * takes the declaration at its word hands over a box in which the bottom line is cut in half,
 * which is exactly what happened on a television.
 *
 * A test reads the provider's XML and holds it against these figures, so the two cannot drift.
 *
 * ### The figures, and where they come from
 *
 * Measured on a device rather than estimated, after a television showed the compact variant cut
 * off at a size a test had declared fine:
 *
 * - A host does not hand the widget the box it was asked for. `AppWidgetHostView` applies a
 *   padding of its own — **12 / 4 / 12 / 20 dp** on Android 14, asymmetric towards the bottom,
 *   which is why the bottom line was the casualty. Twenty-four of the declared dp of height
 *   never reach the widget at all.
 * - One line of text occupies about **1.36 times** its own size in height.
 *
 * The compact layout therefore needs 16 + 1.36 × (20 + 28 + 18) ≈ **105dp** of content. Keeping
 * 15 % of the height spare at the default font scale asks for 124dp inside the widget, and the
 * same layout at a font scale of 1.3 asks for 132dp. Adding the host's 24dp to the larger of the
 * two gives 157dp, and [MIN_HEIGHT_DP] rounds that up with room left over.
 *
 * The full layout needs 32 + 1.36 × (26 + 44 + 20 + 18) ≈ **178dp**, which by the same two
 * conditions comes to 234dp and 246dp; [FULL_HEIGHT_DP] covers both.
 *
 * ### Why the minimum was raised rather than the text shrunk further
 *
 * Both, in fact, and in that order. The declared minimum was 240 × 120 dp, which this widget
 * cannot draw at any text size worth reading from a sofa, so it went up. The compact variant
 * also stopped borrowing the dashboard tile's 44sp value: a tile is 420dp wide and a widget is
 * 300dp minus two paddings, and at 44sp a row of five marks with their numbers was shortened —
 * throwing away the very thing the widget exists to show. Nothing went below the 18sp floor; the
 * smallest size in either variant is exactly that floor.
 */
object WidgetSizes {

    /** The smallest box the widget is declared for, and what the compact layout is built for. */
    const val MIN_WIDTH_DP = 300

    /** Below this the compact layout has no room to spare once the host has taken its share. */
    const val MIN_HEIGHT_DP = 170

    /** The height from which the full layout is offered instead. */
    const val FULL_HEIGHT_DP = 250
}
