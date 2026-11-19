package io.github.jellyforgedev.widgora.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * The design tokens of WIDGORA, in one place.
 *
 * ### Where the brand colours come from
 *
 * They were measured, not chosen by eye. `WIDGORA-Logo.png` (1254 × 1254, 8 bit RGBA) was
 * decoded and every one of its 877,551 fully opaque pixels examined. Pixels with a value of at
 * least 60 and a saturation of at least 0.25 were kept — 638,185 of them — and grouped into
 * 16-step colour buckets; the mean of a bucket is the colour below.
 *
 * - The largest chromatic clusters form one deep indigo family: `#161446` (2.28 % of chromatic
 *   pixels), `#363886` (1.45 %), `#283578` (1.34 %), `#364497` (1.00 %), `#2A3986` (0.97 %).
 *   [WidgoraColors.BrandIndigo] is `#364497`, the brightest of that family that is still
 *   clearly the same colour; its darker members are unusable as an accent on a dark screen.
 * - The second family is a light violet: `#D686FB` (1.08 %) and `#C878FB` (1.07 %).
 *   [WidgoraColors.BrandViolet] is `#C878FB`.
 * - The dark field of the logo measures `#000000` to `#010007`. [WidgoraColors.Backdrop] is
 *   that family lifted slightly, so a raised surface stays distinguishable from the background.
 *
 * The logo file itself is neither modified nor copied into the application.
 *
 * ### Contrast
 *
 * Every colour that carries text or indicates a state was checked against both the backdrop and
 * the surface with the WCAG relative-luminance formula; the ratios are in the comments below and
 * are asserted by a test, so a later change to a token cannot quietly fall below the threshold.
 */
object WidgoraColors {

    /** Page background. Derived from the logo's dark field (`#000000`–`#010007`). */
    val Backdrop = Color(0xFF06060D)

    /** A raised surface such as a tile. 1.09 : 1 against [Backdrop] — a surface, not text. */
    val Surface = Color(0xFF12121C)

    /** Primary text. 18.10 : 1 on [Backdrop], 16.67 : 1 on [Surface]. */
    val OnSurface = Color(0xFFF2F2F7)

    /** Secondary text such as a timestamp. 11.79 : 1 and 10.86 : 1. */
    val OnSurfaceMuted = Color(0xFFC3C5D4)

    /**
     * Measured brand indigo. 2.34 : 1 against the backdrop, so it is **never** used for text or
     * as a state indicator — only as a fill, and text on it is [OnSurface] at 7.74 : 1.
     */
    val BrandIndigo = Color(0xFF364497)

    /**
     * Measured brand violet, used for the primary action. 7.25 : 1 against the backdrop. Text on a
     * violet fill is [Backdrop] at 7.25 : 1, never [OnSurface], which would reach only 2.50 : 1.
     *
     * It is **not** the focus colour; see [Focus].
     */
    val BrandViolet = Color(0xFFC878FB)

    /** Hairline borders. 3.81 : 1 and 3.51 : 1, above the 3 : 1 asked of a non-text boundary. */
    val Outline = Color(0xFF666A85)

    /**
     * The one colour that means "this is where you are". The brightest mark the palette can
     * produce: 20.19 : 1 against [Backdrop] and 18.59 : 1 against [Surface], both asserted.
     *
     * It is deliberately neutral rather than a brand colour. Three reasons, in order of weight:
     *
     * 1. A focus mark says where the remote is pointing, which is not a statement about the
     *    product. Spending the brand colour on it leaves nothing to distinguish the primary action
     *    from whatever merely happens to be focused at the time.
     * 2. White is as far as this palette can get from [Outline], the resting border, and the whole
     *    job of the mark is that focused and unfocused are told apart across a room. A violet at
     *    7.25 : 1 beside an outline at 3.81 : 1 is a much smaller step than white at 20.19 : 1.
     * 3. It carries no hue, so it cannot be mistaken for one of the state colours. A violet ring
     *    around a red count is two colours competing to mean something.
     */
    val Focus = Color(0xFFFFFFFF)

    /** 11.29 : 1 and 10.40 : 1. */
    val StateOk = Color(0xFF5BD98A)

    /** 12.04 : 1 and 11.08 : 1. */
    val StateStale = Color(0xFFF2C14E)

    /** 9.25 : 1 and 8.52 : 1. */
    val StateOffline = Color(0xFFA9AFC4)

    /** 8.90 : 1 and 8.20 : 1. */
    val StateError = Color(0xFFFF8A8A)

    /** 8.79 : 1 and 8.10 : 1. */
    val StateLoading = Color(0xFF8FA7FF)

    /** 11.79 : 1 and 10.86 : 1. */
    val StateEmpty = Color(0xFFC3C5D4)

    /** Every token that carries text or indicates a state, for the contrast test. */
    val TextAndStateTokens: Map<String, Color> = mapOf(
        "OnSurface" to OnSurface,
        "OnSurfaceMuted" to OnSurfaceMuted,
        "BrandViolet" to BrandViolet,
        "StateOk" to StateOk,
        "StateStale" to StateStale,
        "StateOffline" to StateOffline,
        "StateError" to StateError,
        "StateLoading" to StateLoading,
        "StateEmpty" to StateEmpty,
    )
}

/**
 * A text size together with the line height that belongs to it.
 *
 * The two are one token rather than two, because of a defect this fixes. `Text` from
 * `androidx.tv.material3` takes its line height from the ambient text style — one of the
 * library's own typography tokens, sized for ordinary body text. Overriding only `fontSize` left
 * a 44 sp value sitting in a line box built for roughly 20 sp, and the moment the text wrapped,
 * the second line was drawn on top of the first. Nothing about that is visible until a string
 * happens to be long enough, which is why a size is no longer available without its line height:
 * a call site cannot take one and forget the other.
 */
data class WidgoraTextStyle(val size: TextUnit, val lineHeight: TextUnit)

/**
 * Text sizes for a screen three metres away.
 *
 * Google's own guidance states the distance — "the average distance between a TV and its viewers
 * is 3 meters (10 feet)" — and asks that text be "large enough to be seen from a distance", but
 * names no size. The sizes here are therefore this project's decision and not a quoted rule:
 * 18 sp is the floor for anything at all, so nothing ends up at the 14 sp that is ordinary on a
 * phone.
 *
 * Every line height is at least [MIN_LINE_HEIGHT_FACTOR] times its size, which a test asserts, so
 * that wrapped lines keep a gap rather than touching.
 */
object WidgoraText {

    val ScreenTitle = WidgoraTextStyle(size = 34.sp, lineHeight = 42.sp)
    val TileValue = WidgoraTextStyle(size = 44.sp, lineHeight = 54.sp)
    val TileTitle = WidgoraTextStyle(size = 26.sp, lineHeight = 32.sp)
    val Label = WidgoraTextStyle(size = 20.sp, lineHeight = 26.sp)
    val Caption = WidgoraTextStyle(size = 18.sp, lineHeight = 24.sp)

    /** Nothing in the application may be smaller than this. Asserted by a test. */
    val Minimum = 18.sp

    /** The smallest ratio of line height to size that still separates two wrapped lines. */
    const val MIN_LINE_HEIGHT_FACTOR = 1.2f

    /** Every style, for the tests that hold the floor and the line-height ratio. */
    val All: List<Pair<String, WidgoraTextStyle>> = listOf(
        "ScreenTitle" to ScreenTitle,
        "TileValue" to TileValue,
        "TileTitle" to TileTitle,
        "Label" to Label,
        "Caption" to Caption,
    )
}

/** Fixed element sizes. Here rather than in a screen, so a test can reason about them. */
object WidgoraSizes {

    /**
     * The width of a dashboard tile.
     *
     * It is the widest focusable element in the application, which makes it the one that decides
     * how much room a focused enlargement needs; see [WidgoraFocus.ScaleHeadroom].
     */
    val Tile = 420.dp
}

/** Spacing steps. One scale, so that no screen invents its own rhythm. */
object WidgoraSpacing {

    val Tiny = 4.dp
    val Small = 8.dp
    val Medium = 16.dp
    val Large = 24.dp
    val ExtraLarge = 32.dp
}

/**
 * The safe area a television may cut off.
 *
 * Quoted from Google's TV layout guidance: "position the elements with a 5% margin of 48dp on
 * the left and right sides, and 27dp on the top and bottom of a layout". The same page
 * elsewhere names 58 dp and 28 dp without reconciling the two figures; the 5 % statement is the
 * one followed here, because it is the one tied to a stated rule.
 */
object WidgoraOverscan {

    val Horizontal = 48.dp
    val Vertical = 27.dp
}

/** Corner shapes. Modest, because a television sits far away and sharp edges read better. */
object WidgoraShapes {

    /**
     * The radius a tile's corners are rounded by.
     *
     * Named separately from [Tile] because a second surface needs the number itself rather than
     * the shape: a widget is drawn from an XML layout in another process and states its radius as
     * a dimension resource. A test compares the two, which it can only do if the figure is
     * readable here rather than buried inside a shape.
     */
    val TileCornerRadius = 12.dp

    val Tile = RoundedCornerShape(TileCornerRadius)
    val Field = RoundedCornerShape(8.dp)
}

/**
 * Focus marks. One set of them, for every focusable element on every screen.
 *
 * A remote gives no pointer, so the focused element has to be unmistakable from across the room:
 * a thick border in [WidgoraColors.Focus] and, where there is room for it, a slight enlargement,
 * so the mark survives a viewer who cannot tell the border colour apart.
 *
 * [borderWidth] and [borderColor] exist so that a screen cannot assemble its own version of the
 * mark. Three screens were each writing out the same conditional, and that is exactly how one of
 * them ends up a different colour from the others.
 */
object WidgoraFocus {

    val BorderWidth = 4.dp
    val RestingBorderWidth = 1.dp
    const val Scale = 1.04f

    /**
     * The room a focused element needs beside itself, applied as content padding by every list
     * whose children enlarge.
     *
     * [Scale] enlarges about the centre, so an element of width *w* grows *w* × ([Scale] − 1) / 2
     * past each edge. The widest focusable element is a tile at [WidgoraSizes.Tile], needing
     * 8.4 dp; this is that figure rounded up with a little to spare, and a test holds the
     * relationship so that a wider tile or a stronger enlargement cannot outgrow it unnoticed.
     *
     * Without it the mark is cut off exactly where it is needed most — at the first and last
     * position of a list, which is where the focus starts and where it ends up.
     */
    val ScaleHeadroom = 12.dp

    fun borderWidth(focused: Boolean): Dp = if (focused) BorderWidth else RestingBorderWidth

    fun borderColor(focused: Boolean): Color =
        if (focused) WidgoraColors.Focus else WidgoraColors.Outline
}

/**
 * The theme every screen is wrapped in.
 *
 * It replaces the library's own dark scheme, which is where the colours came from until now,
 * with the measured brand palette.
 */
@Composable
fun WidgoraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = WidgoraColors.BrandViolet,
            onPrimary = WidgoraColors.Backdrop,
            secondary = WidgoraColors.BrandIndigo,
            onSecondary = WidgoraColors.OnSurface,
            background = WidgoraColors.Backdrop,
            onBackground = WidgoraColors.OnSurface,
            surface = WidgoraColors.Surface,
            onSurface = WidgoraColors.OnSurface,
            surfaceVariant = WidgoraColors.Surface,
            onSurfaceVariant = WidgoraColors.OnSurfaceMuted,
            border = WidgoraColors.Outline,
            error = WidgoraColors.StateError,
        ),
        content = content,
    )
}
