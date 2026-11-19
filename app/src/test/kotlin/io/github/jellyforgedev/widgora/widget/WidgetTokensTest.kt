package io.github.jellyforgedev.widgora.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import io.github.jellyforgedev.widgora.design.Contrast
import io.github.jellyforgedev.widgora.design.MonitorPresentation
import io.github.jellyforgedev.widgora.design.WidgoraColors
import io.github.jellyforgedev.widgora.design.WidgoraFocus
import io.github.jellyforgedev.widgora.design.WidgoraShapes
import io.github.jellyforgedev.widgora.design.WidgoraSpacing
import io.github.jellyforgedev.widgora.design.WidgoraText
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Holds the widget's resource tokens against the design system's Kotlin tokens.
 *
 * A widget is drawn from an XML layout in another process, so the values that layout fixes cannot
 * read the Kotlin tokens. They exist a second time as resources, and two copies of a number is
 * how two surfaces of one product quietly drift apart — a widget still wearing last year's
 * surface colour is not something anybody notices until they put it beside the dashboard.
 *
 * The resource file is read as text rather than through the Android resource system, which keeps
 * this an ordinary unit test that runs in continuous integration and needs neither a device nor
 * an additional dependency. The parser is the one in the Java runtime.
 */
class WidgetTokensTest {

    @Test
    fun everyColourMatchesItsDesignToken() {
        val colors = parse("color")
        val expected = mapOf(
            "widget_surface" to WidgoraColors.Surface,
            "widget_on_surface" to WidgoraColors.OnSurface,
            "widget_on_surface_muted" to WidgoraColors.OnSurfaceMuted,
            "widget_outline" to WidgoraColors.Outline,
            "widget_focus" to WidgoraColors.Focus,
        )

        assertEquals(expected.keys.sorted(), colors.keys.sorted(), "the set of colours differs")
        for ((name, token) in expected) {
            assertEquals(argb(token), colors.getValue(name).lowercase(), name)
        }
    }

    @Test
    fun everyMeasurementMatchesItsDesignToken() {
        val dimens = parse("dimen")
        val expected = mapOf(
            "widget_corner_radius" to WidgoraShapes.TileCornerRadius.asDimension(),
            "widget_padding" to WidgoraSpacing.Medium.asDimension(),
            "widget_focus_border" to WidgoraFocus.BorderWidth.asDimension(),
            "widget_resting_border" to WidgoraFocus.RestingBorderWidth.asDimension(),
            "widget_text_title" to WidgoraText.TileTitle.size.asDimension(),
            "widget_text_counts" to WidgoraText.TileValue.size.asDimension(),
            "widget_text_state" to WidgoraText.Label.size.asDimension(),
            "widget_text_footer" to WidgoraText.Caption.size.asDimension(),
        )

        assertEquals(expected.keys.sorted(), dimens.keys.sorted(), "the set of measurements differs")
        for ((name, token) in expected) {
            val resource = dimension(dimens.getValue(name), name)
            assertEquals(token.unit, resource.unit, "$name has the wrong unit")
            assertTrue(
                abs(token.value - resource.value) < TOLERANCE,
                "$name is ${resource.value}${resource.unit}, the token is " +
                    "${token.value}${token.unit}",
            )
        }
    }

    /**
     * The platform states that a widget background's corner radius is never larger than 28dp.
     * Exceeding it would fail nowhere — it would simply be ignored, which is worse, because the
     * widget would then look different from the tile for no visible reason.
     */
    @Test
    fun theCornerRadiusStaysWithinWhatAHostWillHonour() {
        val radius = dimension(parse("dimen").getValue("widget_corner_radius"), "radius")

        assertEquals("dp", radius.unit)
        assertTrue(radius.value <= MAX_WIDGET_CORNER_RADIUS_DP, "radius is ${radius.value}dp")
    }

    /**
     * Every mark colour is readable on the surface the widget actually draws.
     *
     * The surface is taken from the resource file rather than from the Kotlin token, which makes
     * this a question about the widget as it is drawn and not about the two happening to agree —
     * they are held equal by another test in this file, and this one would survive that test
     * being changed.
     *
     * The marks became coloured in order to match the application; a colour that is merely
     * decorative would not need checking, but these carry a state, and a mark nobody can make
     * out carries nothing.
     */
    @Test
    fun everyMarkColourIsReadableOnTheWidgetSurface() {
        val surface = colorOf(parse("color").getValue("widget_surface"))

        for (presentation in MonitorPresentation.All) {
            val ratio = Contrast.ratio(presentation.color, surface)
            assertTrue(
                ratio >= Contrast.AA_TEXT,
                "the mark for ${presentation.state.name} reaches only $ratio on the widget surface",
            )
        }
    }

    /** Text in a widget obeys the same floor as text anywhere else in the application. */
    @Test
    fun noTextSizeFallsBelowTheFloor() {
        val dimens = parse("dimen").filterKeys { it.startsWith("widget_text_") }

        assertTrue(dimens.isNotEmpty(), "no text sizes were found")
        for ((name, raw) in dimens) {
            val size = dimension(raw, name)
            assertEquals("sp", size.unit, "$name is not in sp")
            assertTrue(size.value >= WidgoraText.Minimum.value, "$name is only ${size.value}sp")
        }
    }

    // --- Reading the resource file -------------------------------------------------------------

    private data class Dimension(val value: Float, val unit: String)

    private fun Dp.asDimension() = Dimension(value, "dp")

    private fun TextUnit.asDimension(): Dimension {
        assertTrue(isSp, "a text token is not expressed in sp")
        return Dimension(value, "sp")
    }

    private fun argb(color: Color): String = "#%08x".format(color.toArgb())

    /** Reads a resource colour back into a value the contrast formula can take. */
    private fun colorOf(raw: String): Color {
        val digits = raw.trim().removePrefix("#")
        assertEquals(8, digits.length, "a colour is not in #AARRGGBB form: '$raw'")
        return Color(digits.toLong(16).toInt())
    }

    private fun dimension(raw: String, name: String): Dimension {
        val match = DIMENSION.matchEntire(raw.trim())
            ?: fail("$name is not a measurement: '$raw'")
        return Dimension(match.groupValues[1].toFloat(), match.groupValues[2])
    }

    private fun parse(tag: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(tokenFile())
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            val name = node.attributes?.getNamedItem("name")?.nodeValue
                ?: fail("a <$tag> element has no name")
            name to node.textContent.trim()
        }
    }

    /**
     * Finds the resource file without assuming which directory the test was started from.
     *
     * A unit test's working directory is set by the build, and relying on it is exactly the kind
     * of assumption that makes a test pass on one machine and fail on another.
     */
    private fun tokenFile(): File {
        var directory: File? = File("").absoluteFile
        repeat(SEARCH_DEPTH) {
            val here = directory ?: return@repeat
            for (candidate in listOf(RELATIVE_PATH, "app/$RELATIVE_PATH")) {
                val file = File(here, candidate)
                if (file.isFile) return file
            }
            directory = here.parentFile
        }
        fail("widget_tokens.xml was not found from ${File("").absolutePath}")
    }

    private companion object {

        const val RELATIVE_PATH = "src/main/res/values/widget_tokens.xml"
        const val SEARCH_DEPTH = 5
        const val TOLERANCE = 0.001f
        const val MAX_WIDGET_CORNER_RADIUS_DP = 28f

        val DIMENSION = Regex("""([0-9]+(?:\.[0-9]+)?)(dp|sp)""")
    }
}
