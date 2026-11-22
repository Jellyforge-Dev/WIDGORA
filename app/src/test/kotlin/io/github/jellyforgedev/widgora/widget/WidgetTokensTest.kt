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
import io.github.jellyforgedev.widgora.widget.config.WidgetConfigActivity
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            "widget_text_names" to WidgoraText.Caption.size.asDimension(),
            "widget_padding_compact" to WidgoraSpacing.Small.asDimension(),
            "widget_padding_tiny" to WidgoraSpacing.Tiny.asDimension(),
            "widget_text_list_title" to WidgoraText.Label.size.asDimension(),
            "widget_text_list_row" to WidgoraText.Caption.size.asDimension(),
            "widget_text_compact_title" to WidgoraText.Label.size.asDimension(),
            "widget_text_compact_counts" to WidgoraText.WidgetValue.size.asDimension(),
            "widget_text_compact_line" to WidgoraText.Caption.size.asDimension(),
            "widget_counts_autosize_min" to WidgoraText.Minimum.asDimension(),
            "widget_counts_autosize_max" to WidgoraText.WidgetValueMaximum.asDimension(),
            "widget_counts_autosize_max_compact" to WidgoraText.TileValue.size.asDimension(),
            "widget_counts_autosize_max_named" to WidgoraText.TileValue.size.asDimension(),
            "widget_counts_autosize_step" to WidgoraText.AutoSizeStep.asDimension(),
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

    /**
     * The sizes the provider declares are the sizes the layouts are built for.
     *
     * This is the mismatch that produced a cut-off widget on a television: the metadata offered
     * a resize down to 240 × 120 dp while the smallest layout needed 300 × 160. A declared size
     * is a promise to every host that reads it, and a promise nothing held is how it came to be
     * made in the first place.
     */
    @Test
    fun theDeclaredSizesMatchTheSizesTheLayoutsAreBuiltFor() {
        val declared = providerAttributes()

        for (attribute in listOf("minWidth", "minResizeWidth")) {
            assertEquals(
                WidgetSizes.MIN_WIDTH_DP,
                dimension(declared.getValue(attribute), attribute).value.toInt(),
                attribute,
            )
        }
        for (attribute in listOf("minHeight", "minResizeHeight")) {
            assertEquals(
                WidgetSizes.MIN_HEIGHT_DP,
                dimension(declared.getValue(attribute), attribute).value.toInt(),
                attribute,
            )
        }

        // The largest box is a promise too, and the measurement test holds the content against
        // it. If the two drifted, the test would be measuring a size no host would ever give.
        assertEquals(
            WidgetSizes.LARGE_WIDTH_DP,
            dimension(declared.getValue("maxResizeWidth"), "maxResizeWidth").value.toInt(),
            "maxResizeWidth",
        )
        assertEquals(
            WidgetSizes.LARGE_HEIGHT_DP,
            dimension(declared.getValue("maxResizeHeight"), "maxResizeHeight").value.toInt(),
            "maxResizeHeight",
        )

        // The larger variant has to be reachable, or it would be built and never shown.
        assertTrue(
            WidgetSizes.LARGE_HEIGHT_DP >= WidgetSizes.FULL_HEIGHT_DP,
            "the full variant needs ${WidgetSizes.FULL_HEIGHT_DP}dp and a resize stops at " +
                "${WidgetSizes.LARGE_HEIGHT_DP}dp",
        )
    }

    /**
     * The range the counts are scaled within is a range, and it starts at the floor.
     *
     * Scaling can only ever be as safe as its lower bound: a minimum below the floor would let
     * the platform shrink the one line that matters until nobody across a room could read it.
     */
    @Test
    fun theScalingRangeStartsAtTheFloorAndGoesUp() {
        val dimens = parse("dimen")
        val min = dimension(dimens.getValue("widget_counts_autosize_min"), "min")
        val max = dimension(dimens.getValue("widget_counts_autosize_max"), "max")
        val step = dimension(dimens.getValue("widget_counts_autosize_step"), "step")

        assertEquals("sp", min.unit)
        assertEquals(WidgoraText.Minimum.value, min.value)
        assertTrue(max.value > min.value, "the range does not go up: ${min.value}..${max.value}")
        assertTrue(step.value > 0f, "the step is ${step.value}")
        assertTrue(
            step.value <= max.value - min.value,
            "a step of ${step.value} cannot move within ${min.value}..${max.value}",
        )
    }

    /**
     * The resize floor and the ceiling are the same promise for all three kinds.
     *
     * The box a kind is *placed* at differs — see below — but the range it accepts does not, and
     * that is what the layouts are built for: every kind has a compact, a full and a large variant,
     * measured at the same three boxes. A kind whose floor was higher would be a kind with a
     * variant no host could ask for.
     */
    @Test
    fun everyKindAcceptsTheSameRangeOfBoxes() {
        for ((name, path) in ALL_PROVIDER_PATHS) {
            val declared = providerAttributes(path)

            assertEquals(
                WidgetSizes.MIN_WIDTH_DP,
                dimension(declared.getValue("minResizeWidth"), name).value.toInt(),
                "$name minResizeWidth",
            )
            assertEquals(
                WidgetSizes.MIN_HEIGHT_DP,
                dimension(declared.getValue("minResizeHeight"), name).value.toInt(),
                "$name minResizeHeight",
            )
            assertEquals(
                WidgetSizes.LARGE_WIDTH_DP,
                dimension(declared.getValue("maxResizeWidth"), name).value.toInt(),
                "$name maxResizeWidth",
            )
            assertEquals(
                WidgetSizes.LARGE_HEIGHT_DP,
                dimension(declared.getValue("maxResizeHeight"), name).value.toInt(),
                "$name maxResizeHeight",
            )
        }
    }

    /**
     * Each kind is placed at the box its own content needs.
     *
     * Three figures in two places again — the metadata a host reads and the constants the code
     * states — so a change to one of them has to be a change to both. The figures themselves are
     * argued for where they are declared; what this holds is only that they agree.
     */
    @Test
    fun everyKindIsPlacedAtTheBoxItsContentNeeds() {
        val expected = mapOf(
            PROVIDER_PATH to WidgetSizes.Default.COUNTS,
            CERTIFICATES_PROVIDER_PATH to WidgetSizes.Default.CERTIFICATES,
            SELECTION_PROVIDER_PATH to WidgetSizes.Default.SELECTION,
        )

        for ((path, box) in expected) {
            val declared = providerAttributes(path)

            assertEquals(
                box.widthDp,
                dimension(declared.getValue("minWidth"), path).value.toInt(),
                "$path minWidth",
            )
            assertEquals(
                box.heightDp,
                dimension(declared.getValue("minHeight"), path).value.toInt(),
                "$path minHeight",
            )
            // A host with a cell grid reads the cells instead, so the two have to describe the
            // same box: three cells wide and 640dp wide would be two different answers to one
            // question. The cell size is taken from the counts widget, whose three by two cells
            // are its 300 by 170 dp, and the others are held to the same proportion.
            assertEquals(
                cellsFor(box.widthDp, WidgetSizes.MIN_WIDTH_DP, COUNTS_CELL_WIDTH),
                declared.getValue("targetCellWidth").toInt(),
                "$path targetCellWidth against ${box.widthDp}dp",
            )
            assertEquals(
                cellsFor(box.heightDp, WidgetSizes.MIN_HEIGHT_DP, COUNTS_CELL_HEIGHT),
                declared.getValue("targetCellHeight").toInt(),
                "$path targetCellHeight against ${box.heightDp}dp",
            )
        }
    }

    /** The widget framework's own periodic update stays switched off, for every kind. */
    @Test
    fun theWidgetFrameworkIsNotAskedForPeriodicUpdates() {
        for ((name, path) in ALL_PROVIDER_PATHS) {
            assertEquals("0", providerAttributes(path).getValue("updatePeriodMillis"), name)
        }
    }

    /** How many cells a box is, in the units the counts widget's own declaration sets. */
    private fun cellsFor(sizeDp: Int, referenceDp: Int, referenceCells: Int): Int {
        val cellSize = referenceDp.toFloat() / referenceCells
        return Math.round(sizeDp / cellSize)
    }

    /**
     * The declared configuration activity is the one that exists.
     *
     * The attribute is a class name in a resource file, so nothing about it is checked by the
     * compiler: a rename would leave a provider pointing at a class that is gone, and a host
     * would fail to start a configuration step with nothing in this repository saying why.
     */
    @Test
    fun theDeclaredConfigurationActivityIsTheOneThatExists() {
        for ((name, path) in ALL_PROVIDER_PATHS) {
            assertEquals(
                WidgetConfigActivity::class.java.name,
                providerAttributes(path).getValue("configure"),
                name,
            )
        }
    }

    /**
     * The widget is reconfigurable and its configuration step is **not** optional.
     *
     * Both halves are decisions rather than defaults, and the second is the one worth holding.
     * `configuration_optional` would let a host place the widget without asking anything, and the
     * instance question is the point of the step. It is also only honoured from API level 31,
     * while this application's minSdk is 28 — so on three platform versions the same package
     * would behave differently, which is the kind of difference nobody discovers until a second
     * television shows it.
     */
    @Test
    fun theConfigurationStepIsRequiredAndTheWidgetIsReconfigurable() {
        for ((name, path) in ALL_PROVIDER_PATHS) {
            val features = providerAttributes(path).getValue("widgetFeatures")
                .split("|")
                .map { flag -> flag.trim() }

            assertTrue("reconfigurable" in features, "$name: $features")
            assertFalse("configuration_optional" in features, "$name: $features")
        }
    }

    /**
     * The three list layouts are one layout with three line budgets.
     *
     * Each declares the first two, the first four or all eight item lines, in order, and every line
     * the renderer writes to has to exist in the layout it writes to. What this holds is both
     * halves of that: the number of lines each file has, and that the lines they share are
     * identical — a text size raised in one file and not the others would be three widgets of one
     * product with three different text sizes.
     */
    @Test
    fun theThreeListLayoutsDifferOnlyInHowManyLinesTheyHave() {
        val byPath = LIST_LAYOUT_PATHS.associateWith { path -> textViewAttributes(path) }

        for ((path, budget) in LIST_LAYOUT_BUDGETS) {
            val views = byPath.getValue(path)
            for (index in 0 until budget) {
                assertTrue("@+id/widget_row_$index" in views, "$path has no line $index")
                assertTrue(
                    "@+id/widget_row_${index}_detail" in views,
                    "$path has no figure beside line $index",
                )
            }
            assertFalse(
                "@+id/widget_row_$budget" in views,
                "$path declares a line beyond its budget of $budget",
            )
            for (id in LIST_SHARED_IDS) {
                assertTrue(id in views, "$path has no $id")
            }
        }

        val reference = byPath.getValue(LIST_LAYOUT_PATHS.first())
        for (path in LIST_LAYOUT_PATHS.drop(1)) {
            val other = byPath.getValue(path)
            for (id in reference.keys.intersect(other.keys)) {
                assertEquals(
                    reference.getValue(id),
                    other.getValue(id),
                    "'$id' differs between ${LIST_LAYOUT_PATHS.first()} and $path",
                )
            }
        }
    }

    /**
     * The two full layouts differ in exactly one attribute and nothing else.
     *
     * There are two of them because the step that names monitors needs a lower ceiling for the
     * counts line, and that ceiling cannot be set at runtime. Two near-identical files is a real
     * cost, and this is what keeps it from becoming a silent one: a change made to one line in
     * one file and not the other fails here rather than on a television.
     *
     * Only the lines both files have are compared. The naming layout has two more, which is the
     * point of it.
     */
    @Test
    fun theTwoFullLayoutsDifferOnlyInTheScalingCeiling() {
        val plain = textViewAttributes(FULL_LAYOUT_PATH)
        val named = textViewAttributes(NAMED_LAYOUT_PATH)

        val shared = plain.keys.intersect(named.keys)
        assertTrue(shared.isNotEmpty(), "the two layouts share no line at all")

        for (id in shared) {
            val here = plain.getValue(id)
            val there = named.getValue(id)
            val differing = (here.keys + there.keys)
                .filter { attribute -> here[attribute] != there[attribute] }

            assertEquals(
                emptyList(),
                differing - ALLOWED_DIFFERENCE,
                "'$id' differs in more than the scaling ceiling",
            )
        }

        // And the one allowed difference is actually there, or the second file would be a copy
        // with no reason to exist.
        assertTrue(
            plain.getValue(COUNTS_ID)[ALLOWED_DIFFERENCE] !=
                named.getValue(COUNTS_ID)[ALLOWED_DIFFERENCE],
            "the naming layout does not lower the ceiling at all",
        )
    }

    /** Every `TextView` of a layout by its identifier, with its attributes. */
    private fun textViewAttributes(path: String): Map<String, Map<String, String>> {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(resourceFile(path))
        val nodes = document.getElementsByTagName("TextView")

        return (0 until nodes.length).associate { index ->
            val attributes = nodes.item(index).attributes
            val byName = (0 until attributes.length).associate { position ->
                val attribute = attributes.item(position)
                attribute.nodeName.removePrefix("android:") to attribute.nodeValue
            }
            val id = byName["id"] ?: fail("a TextView in $path has no id")
            id to byName
        }
    }

    private fun providerAttributes(path: String = PROVIDER_PATH): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(resourceFile(path))
        val element = document.getElementsByTagName("appwidget-provider").item(0)
            ?: fail("the provider declaration has no appwidget-provider element")
        val attributes = element.attributes
        return (0 until attributes.length).associate { index ->
            val attribute = attributes.item(index)
            attribute.nodeName.removePrefix("android:") to attribute.nodeValue
        }
    }

    private fun parse(tag: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(resourceFile(TOKENS_PATH))
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
    private fun resourceFile(relativePath: String): File {
        var directory: File? = File("").absoluteFile
        repeat(SEARCH_DEPTH) {
            val here = directory ?: return@repeat
            for (candidate in listOf(relativePath, "app/$relativePath")) {
                val file = File(here, candidate)
                if (file.isFile) return file
            }
            directory = here.parentFile
        }
        fail("$relativePath was not found from ${File("").absolutePath}")
    }

    private companion object {

        const val TOKENS_PATH = "src/main/res/values/widget_tokens.xml"
        const val PROVIDER_PATH = "src/main/res/xml/counts_widget_info.xml"
        const val CERTIFICATES_PROVIDER_PATH = "src/main/res/xml/certificates_widget_info.xml"
        const val SELECTION_PROVIDER_PATH = "src/main/res/xml/selection_widget_info.xml"
        const val FULL_LAYOUT_PATH = "src/main/res/layout/widget_counts.xml"
        const val NAMED_LAYOUT_PATH = "src/main/res/layout/widget_counts_named.xml"

        /** Every provider declaration, by a name a failure message can carry. */
        val ALL_PROVIDER_PATHS = listOf(
            "counts" to PROVIDER_PATH,
            "certificates" to CERTIFICATES_PROVIDER_PATH,
            "selection" to SELECTION_PROVIDER_PATH,
        )

        /** The cells the counts widget declares, which set the cell size the others are held to. */
        const val COUNTS_CELL_WIDTH = 3
        const val COUNTS_CELL_HEIGHT = 2

        /** The list layouts, smallest first, with the number of item lines each one has. */
        val LIST_LAYOUT_BUDGETS = listOf(
            "src/main/res/layout/widget_list_compact.xml" to 2,
            "src/main/res/layout/widget_list.xml" to 4,
            "src/main/res/layout/widget_list_large.xml" to 8,
        )

        val LIST_LAYOUT_PATHS = LIST_LAYOUT_BUDGETS.map { (path, _) -> path }

        /** The lines every list layout has, whatever its budget. */
        val LIST_SHARED_IDS = listOf(
            "@+id/widget_title",
            "@+id/widget_title_more",
            "@+id/widget_empty",
            "@+id/widget_state",
            "@+id/widget_footer",
        )

        /** The one attribute the naming layout exists in order to change. */
        const val ALLOWED_DIFFERENCE = "autoSizeMaxTextSize"

        const val COUNTS_ID = "@+id/widget_counts"
        const val SEARCH_DEPTH = 5
        const val TOLERANCE = 0.001f
        const val MAX_WIDGET_CORNER_RADIUS_DP = 28f

        val DIMENSION = Regex("""([0-9]+(?:\.[0-9]+)?)(dp|sp)""")
    }
}
