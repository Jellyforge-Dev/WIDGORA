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
 * ### These are declared boxes, and none of them is a key the platform compares
 *
 * Every figure below is a box a host may offer. What the widget is *given* is that box minus the
 * host's own padding, so the keys of the size map are these figures minus that padding;
 * [WidgetStepSizes] does the subtraction and carries the evidence. Using a figure from here as a
 * key directly is the defect that made the tallest step unreachable — a reader reaching for one of
 * these constants to pair with a layout is reaching for the wrong number.
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

    /**
     * The largest box the widget is declared for.
     *
     * Named here as well as in the provider's metadata, and held against it by a test, for the
     * same reason the minimum is: both are promises, and a promise nothing checks is how the
     * minimum came to be one the widget could not keep.
     *
     * The content scales to fill it. Before it did not, and a widget of this size showed the
     * same small digits as the smallest one, with two thirds of its area left empty.
     */
    const val LARGE_WIDTH_DP = 640

    /** The largest height the widget is declared for. */
    const val LARGE_HEIGHT_DP = 320

    /**
     * The height from which a widget with consent also draws the monitor names.
     *
     * **Measured, and the measurement overturned the plan.** The intention was names in the full
     * step and the large one alike. In the full step at 300 × 250 dp they do not fit: with a
     * single name only 14 % of the height was left spare against a floor of 15 %, and with three
     * the counts line was squeezed to 82 px for 83 px of text — both read out of a real host view
     * on an emulator `[V]`. Two lines were added and the box did not grow.
     *
     * So consent does not change what a widget draws in a box that size. It draws exactly what a
     * widget without consent draws, and the names appear once the host gives it a taller box. The
     * alternatives were worse: raising the full step would take the full layout away from every
     * widget that has no names to show, and shortening the names to fit would spend the width on
     * something nobody can read from a sofa.
     *
     * The figure is the declared maximum height. **That is what makes the step reachable, and for
     * one round of this work it was what made it unreachable** — the figure was used as the size
     * map's key as well, where it had to be the maximum *minus the host's padding*, and a key
     * above every box a host can give is a step that never applies. A television showed exactly
     * that: names allowed, a monitor down, the largest box, and the full variant drawn with no
     * names in it. The key is now derived in [WidgetStepSizes]; this stays the declared box.
     */
    const val NAMES_HEIGHT_DP = LARGE_HEIGHT_DP

}
