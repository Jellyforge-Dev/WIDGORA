package io.github.jellyforgedev.widgora.design

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * The WCAG contrast calculation, so that a colour decision can be checked instead of argued.
 *
 * It lives in the production source rather than in a test because the tokens document their own
 * ratios, and a formula kept next to them cannot drift away from what those comments claim.
 */
object Contrast {

    /** The smallest ratio WCAG AA accepts for ordinary text. */
    const val AA_TEXT = 4.5

    /** The smallest ratio WCAG asks of a user interface boundary or a state indicator. */
    const val AA_NON_TEXT = 3.0

    /** Relative luminance as defined by WCAG 2: a channel curve, then the usual weighting. */
    fun relativeLuminance(color: Color): Double {
        val r = channel(color.red)
        val g = channel(color.green)
        val b = channel(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** The ratio between two colours, always at least 1 and at most 21. */
    fun ratio(first: Color, second: Color): Double {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
