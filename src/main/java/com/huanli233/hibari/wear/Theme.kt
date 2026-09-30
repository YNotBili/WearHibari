package com.huanli233.hibari.wear

import com.huanli233.hibari.runtime.ProvidableTunationLocal
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.TunationLocalProvider
import com.huanli233.hibari.runtime.staticTunationLocalOf
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.wear.tokens.TypographyTokens

/**
 * Ported from androidx.wear.compose.material3.{MaterialTheme,Providers}.
 *
 * `LocalIndication`, `LocalTextSelectionColors` and the swipe-to-dismiss scrim locals are not
 * ported: ripples arrive with [ContainerDrawable]'s own state handling. `LocalMotionScheme` is
 * ported and lives in [MotionScheme.kt][com.huanli233.hibari.wear.MotionScheme], provided here.
 */

internal val LocalColorScheme = staticTunationLocalOf { ColorScheme() }
internal val LocalTypography = staticTunationLocalOf { Typography() }
internal val LocalShapes = staticTunationLocalOf { Shapes() }

/**
 * Upstream seeds this with `Color.White` (material3/ContentColor.kt:34). [Color.Unspecified] is the
 * seed here because a `TextView`'s colour is only written when specified
 * (`Modifier.textColorIfSpecified`), so an unset ambient leaves the view's own XML theme colour
 * standing, while seeding white would force it onto every label outside a provider.
 */
internal val LocalContentColor = staticTunationLocalOf { Color.Unspecified }

/**
 * Upstream seeds `LocalTextStyle` with the near-empty `DefaultTextStyle` (material3/Text.kt:239) and
 * has [MaterialTheme] layer `typography.bodyLarge` over the ambient style through `ProvideTextStyle`
 * (material3/Text.kt:249-252). This port replaces instead of merging: Hibari's `TextStyle.merge`
 * treats a style's non-nullable `fontWeight` as always set, so merging a hand-built partial role
 * would drop the ambient weight to 400. The seed therefore carries the body role outright.
 */
internal val LocalTextStyle = staticTunationLocalOf { TypographyTokens.BodyLarge }

object MaterialTheme {
    val colorScheme: ColorScheme
        @Tunable get() = LocalColorScheme.current

    val typography: Typography
        @Tunable get() = LocalTypography.current

    val shapes: Shapes
        @Tunable get() = LocalShapes.current

    /** Upstream declares this as an object member too (material3/MaterialTheme.kt:88-89). */
    val motionScheme: MotionScheme
        @Tunable get() = LocalMotionScheme.current
}

/**
 * @param colorScheme, @param typography, @param shapes Default to null and fall back to the ambient
 *   value in the body. Upstream writes these as `MaterialTheme.colorScheme` etc. directly, but in
 *   Hibari a default expression is evaluated in the hoisted `$default`, where the tuner is copied in
 *   as a parameter yet never threaded into the nested call, so the read would land on the locals'
 *   static initial values instead of the theme currently in scope.
 */
@Tunable
fun MaterialTheme(
    colorScheme: ColorScheme? = null,
    typography: Typography? = null,
    shapes: Shapes? = null,
    motionScheme: MotionScheme? = null,
    content: @Tunable () -> Unit,
) {
    val resolvedColors = colorScheme ?: MaterialTheme.colorScheme
    val resolvedTypography = typography ?: MaterialTheme.typography
    val resolvedShapes = shapes ?: MaterialTheme.shapes
    val resolvedMotionScheme = motionScheme ?: MaterialTheme.motionScheme
    TunationLocalProvider(
        LocalColorScheme provides resolvedColors,
        LocalTypography provides resolvedTypography,
        LocalShapes provides resolvedShapes,
        LocalMotionScheme provides resolvedMotionScheme,
        // Upstream's slot ends in `ProvideTextStyle(value = typography.bodyLarge, content)`
        // (material3/MaterialTheme.kt:74); without this the theme's own body role never reaches a
        // [Text] that was handed no explicit style.
        LocalTextStyle provides resolvedTypography.bodyLarge,
        content = content,
    )
}

/**
 * Resolve the colour to draw content with, in upstream's order (material3/ColorScheme.kt:347-350):
 * the scheme's mapping for [backgroundColor] wins over the ambient [LocalContentColor]. The extra
 * [color] has no slot upstream — its `Text` folds an explicit colour in at its own call site
 * (material3/Text.kt:207) — and wins over both here. When neither route answers the result stays
 * [Color.Unspecified], where upstream would still hand back `LocalContentColor`'s `Color.White`.
 */
@Tunable
fun contentColorFor(backgroundColor: Color, color: Color = Color.Unspecified): Color {
    // The locals are read directly: `current` needs the @Tunable accessor scope, which a lambda
    // passed to `takeOrElse` does not have.
    val ambient = LocalContentColor.current
    val fromScheme = MaterialTheme.colorScheme.contentColorFor(backgroundColor)
    return when {
        color.isSpecified -> color
        fromScheme.isSpecified -> fromScheme
        else -> ambient
    }
}

@Tunable
fun currentTextStyle(): TextStyle = LocalTextStyle.current

/**
 * Scope a container's resolved content colour to its children — upstream's
 * `provideScopeContent(color, content)` (material3/Providers.kt:80-84). [Text] reads the value back
 * off [LocalContentColor] when it was given no explicit `color` (material3/Text.kt:207, here
 * `Text.kt:55`).
 */
@Tunable
internal fun provideContentColor(color: Color, content: @Tunable () -> Unit) {
    TunationLocalProvider(LocalContentColor provides color, content = content)
}

/**
 * Scope a container's content colour *and* text style together — upstream's
 * `provideScopeContent(contentColor, textStyle, content)` (material3/Providers.kt:53-62): a
 * container that restyles its children has to hand both down, because a `Text` with no explicit
 * `style` reads [LocalTextStyle] and with no explicit `color` reads [LocalContentColor]. Upstream
 * provides the style outright there rather than merging it into the ambient one, so this does too.
 * Only `ProvideTextStyle` (material3/Text.kt:249-252) merges upstream; see [LocalTextStyle] for why
 * nothing here does.
 */
@Tunable
internal fun provideContentColorAndStyle(
    color: Color,
    style: TextStyle,
    content: @Tunable () -> Unit,
) {
    TunationLocalProvider(
        LocalContentColor provides color,
        LocalTextStyle provides style,
        content = content,
    )
}
