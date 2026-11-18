package io.github.jellyforgedev.widgora.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
     * Measured brand violet, used for focus and for the primary action. 7.25 : 1 against the
     * backdrop. Text on a violet fill is [Backdrop] at 7.25 : 1, never [OnSurface], which would
     * reach only 2.50 : 1.
     */
    val BrandViolet = Color(0xFFC878FB)

    /** Hairline borders. 3.81 : 1 and 3.51 : 1, above the 3 : 1 asked of a non-text boundary. */
    val Outline = Color(0xFF666A85)

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
 * Text sizes for a screen three metres away.
 *
 * Google's own guidance states the distance — "the average distance between a TV and its viewers
 * is 3 meters (10 feet)" — and asks that text be "large enough to be seen from a distance", but
 * names no size. The sizes here are therefore this project's decision and not a quoted rule:
 * 18 sp is the floor for anything at all, so nothing ends up at the 14 sp that is ordinary on a
 * phone.
 */
object WidgoraText {

    val ScreenTitle = 34.sp
    val TileValue = 44.sp
    val TileTitle = 26.sp
    val Label = 20.sp
    val Caption = 18.sp

    /** Nothing in the application may be smaller than this. Asserted by a test. */
    val Minimum = 18.sp
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

    val Tile = RoundedCornerShape(12.dp)
    val Field = RoundedCornerShape(8.dp)
}

/**
 * Focus marks.
 *
 * A remote gives no pointer, so the focused element has to be unmistakable from across the
 * room: a thick violet border **and** a slight enlargement, so the mark survives a viewer who
 * cannot tell the border colour apart.
 */
object WidgoraFocus {

    val BorderWidth = 4.dp
    val RestingBorderWidth = 1.dp
    const val Scale = 1.04f
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
