package io.github.jellyforgedev.widgora.widget

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.util.SizeF

/**
 * The sizes a widget's size map is keyed by, which are not the boxes a host offers.
 *
 * ### The defect this exists to prevent, written down because it happened twice
 *
 * A widget may hand the platform several view hierarchies at once, each with the size it is meant
 * for, and the platform picks one per widget. The keys used to be the sizes WIDGORA *declares* —
 * 300 × 170, 300 × 250, 300 × 320 dp. That is the wrong unit, and it is wrong in one direction:
 *
 * - `AppWidgetHostView.setAppWidget` puts the framework's own padding on the host view,
 *   `default_app_widget_padding_*`, measured on Android 14 as 12 / 4 / 12 / 20 dp — 24 dp of
 *   width and 24 dp of height, asymmetric towards the bottom.
 * - `AppWidgetHostView.onLayout` then asks for the variant deserved by `computeSizeFromLayout`,
 *   which is the laid-out box **minus that padding**.
 * - `RemoteViews.findBestFitLayout` keeps the entries whose key fits in that size and, where none
 *   does, falls back to the entry with the smallest area.
 *
 * All of it read out of the platform source for API 37 `[V]`.
 *
 * A key of 300 × 320 is therefore unreachable: the largest box the widget declares is 640 × 320,
 * of which the widget is offered 616 × 296. Measured on a television and reproduced on an
 * emulator `[V]` — at 640 × 320 the step carrying the monitor names was never chosen, and at
 * 300 × 250 no key fitted at all, so the fallback gave the compact variant where the full one was
 * meant. Not one of the three steps got what the code intended, and one of the three looked right
 * regardless, which is why it survived.
 *
 * This is the same arithmetic error that cut the bottom line off a television once before — the
 * host's 24 dp missing from a calculation — and [WidgetSizes] carries the first instance of it.
 * That one was caught by measuring a widget; this one could not be, because what it produces is a
 * widget that fits beautifully and is the wrong one.
 *
 * ### Why the padding is asked of the platform rather than written down
 *
 * 24 dp is a measurement of one platform version, and the figure lives in a platform dimension
 * resource a later version may change. The accessor is public SDK and reads exactly those four
 * resources, so asking is both shorter and more durable than recording the answer.
 *
 * Its documentation addresses hosts, which need it to work out how many cells a widget deserves.
 * This is the same question from the other side — how much of a declared box actually reaches the
 * widget — and the answer has to be the same number, which is the reason for taking it from there.
 *
 * ### What it does not know
 *
 * The framework's default, not a particular host's choice. A host may call `setPadding` afterwards
 * and take more or less, and the launcher on the intended television is one WIDGORA has never
 * seen. A host that takes **less** is harmless: every key then fits with room to spare. A host
 * that takes **more** than the default loses the tallest step and draws the full variant instead,
 * which is a correct widget without names rather than a broken one `[U]`.
 */
internal object WidgetStepSizes {

    /**
     * The margin the framework's host view takes out of every box, in dp, width and height.
     *
     * Read once per rendering and passed to [offered] for each step, rather than read per step:
     * three reads of the same four resources would be three chances for the steps to disagree with
     * one another.
     *
     * The component is not passed on. The platform's own implementation forwards to an overload
     * that does not take one — `getDefaultPaddingForWidget(Context, ComponentName, Rect)` calls
     * `getDefaultPaddingForWidget(Context, Rect)` — so a component here would read as a dependency
     * that does not exist `[V]`.
     */
    fun hostPaddingDp(context: Context): SizeF {
        val padding = AppWidgetHostView.getDefaultPaddingForWidget(context, null, null)
        val density = context.resources.displayMetrics.density
        return SizeF(
            (padding.left + padding.right) / density,
            (padding.top + padding.bottom) / density,
        )
    }

    /**
     * What is left of a declared box for the widget itself: the key the platform compares.
     *
     * The floor is for an absurd case rather than a likely one. A padding wider than the box would
     * otherwise produce a key of zero or less, and the platform's `fitsIn` treats such a key as
     * fitting anywhere — a step that fits anywhere is a step that takes every box.
     */
    fun offered(declaredWidthDp: Int, declaredHeightDp: Int, hostPaddingDp: SizeF): SizeF = SizeF(
        (declaredWidthDp - hostPaddingDp.width).coerceAtLeast(SMALLEST_KEY_DP),
        (declaredHeightDp - hostPaddingDp.height).coerceAtLeast(SMALLEST_KEY_DP),
    )

    private const val SMALLEST_KEY_DP = 1f
}
