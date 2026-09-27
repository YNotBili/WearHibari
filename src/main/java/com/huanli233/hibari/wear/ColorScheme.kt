package com.huanli233.hibari.wear

import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import com.huanli233.hibari.wear.tokens.ColorTokens

/**
 * Ported from androidx.wear.compose.material3.ColorScheme.
 *
 * The 39 `default*ColorsCached` slots upstream keeps (material3/ColorScheme.kt:218-288) to avoid
 * re-allocating per-composition defaults are dropped: Hibari has no composition to cache against,
 * and [fromToken] is a plain `when` over the same table.
 */
class ColorScheme(
    val primary: Color = ColorTokens.Primary,
    val primaryDim: Color = ColorTokens.PrimaryDim,
    val primaryContainer: Color = ColorTokens.PrimaryContainer,
    val onPrimary: Color = ColorTokens.OnPrimary,
    val onPrimaryContainer: Color = ColorTokens.OnPrimaryContainer,
    val secondary: Color = ColorTokens.Secondary,
    val secondaryDim: Color = ColorTokens.SecondaryDim,
    val secondaryContainer: Color = ColorTokens.SecondaryContainer,
    val onSecondary: Color = ColorTokens.OnSecondary,
    val onSecondaryContainer: Color = ColorTokens.OnSecondaryContainer,
    val tertiary: Color = ColorTokens.Tertiary,
    val tertiaryDim: Color = ColorTokens.TertiaryDim,
    val tertiaryContainer: Color = ColorTokens.TertiaryContainer,
    val onTertiary: Color = ColorTokens.OnTertiary,
    val onTertiaryContainer: Color = ColorTokens.OnTertiaryContainer,
    val surfaceContainerLow: Color = ColorTokens.SurfaceContainerLow,
    val surfaceContainer: Color = ColorTokens.SurfaceContainer,
    val surfaceContainerHigh: Color = ColorTokens.SurfaceContainerHigh,
    val onSurface: Color = ColorTokens.OnSurface,
    val onSurfaceVariant: Color = ColorTokens.OnSurfaceVariant,
    val outline: Color = ColorTokens.Outline,
    val outlineVariant: Color = ColorTokens.OutlineVariant,
    val background: Color = ColorTokens.Background,
    val onBackground: Color = ColorTokens.OnBackground,
    val error: Color = ColorTokens.Error,
    val errorDim: Color = ColorTokens.ErrorDim,
    val errorContainer: Color = ColorTokens.ErrorContainer,
    val onError: Color = ColorTokens.OnError,
    val onErrorContainer: Color = ColorTokens.OnErrorContainer,
) {

    fun copy(
        primary: Color = this.primary,
        primaryDim: Color = this.primaryDim,
        primaryContainer: Color = this.primaryContainer,
        onPrimary: Color = this.onPrimary,
        onPrimaryContainer: Color = this.onPrimaryContainer,
        secondary: Color = this.secondary,
        secondaryDim: Color = this.secondaryDim,
        secondaryContainer: Color = this.secondaryContainer,
        onSecondary: Color = this.onSecondary,
        onSecondaryContainer: Color = this.onSecondaryContainer,
        tertiary: Color = this.tertiary,
        tertiaryDim: Color = this.tertiaryDim,
        tertiaryContainer: Color = this.tertiaryContainer,
        onTertiary: Color = this.onTertiary,
        onTertiaryContainer: Color = this.onTertiaryContainer,
        surfaceContainerLow: Color = this.surfaceContainerLow,
        surfaceContainer: Color = this.surfaceContainer,
        surfaceContainerHigh: Color = this.surfaceContainerHigh,
        onSurface: Color = this.onSurface,
        onSurfaceVariant: Color = this.onSurfaceVariant,
        outline: Color = this.outline,
        outlineVariant: Color = this.outlineVariant,
        background: Color = this.background,
        onBackground: Color = this.onBackground,
        error: Color = this.error,
        errorDim: Color = this.errorDim,
        errorContainer: Color = this.errorContainer,
        onError: Color = this.onError,
        onErrorContainer: Color = this.onErrorContainer,
    ): ColorScheme = ColorScheme(
        primary, primaryDim, primaryContainer, onPrimary, onPrimaryContainer,
        secondary, secondaryDim, secondaryContainer, onSecondary, onSecondaryContainer,
        tertiary, tertiaryDim, tertiaryContainer, onTertiary, onTertiaryContainer,
        surfaceContainerLow, surfaceContainer, surfaceContainerHigh,
        onSurface, onSurfaceVariant, outline, outlineVariant,
        background, onBackground,
        error, errorDim, errorContainer, onError, onErrorContainer,
    )

    /**
     * The content color to draw on [backgroundColor], resolved against the fixed table of
     * background roles rather than by contrast calculation. Returns [Color.Unspecified] for any
     * colour that is not itself a background role, leaving the caller to inherit.
     */
    fun contentColorFor(backgroundColor: Color): Color = when (backgroundColor) {
        primary, primaryDim -> onPrimary
        primaryContainer -> onPrimaryContainer
        secondary, secondaryDim -> onSecondary
        secondaryContainer -> onSecondaryContainer
        tertiary, tertiaryDim -> onTertiary
        tertiaryContainer -> onTertiaryContainer
        surfaceContainer, surfaceContainerLow, surfaceContainerHigh -> onSurface
        background -> onBackground
        error -> onError
        errorDim -> onError
        errorContainer -> onErrorContainer
        else -> Color.Unspecified
    }

    internal fun fromToken(token: ColorSchemeKeyTokens): Color = when (token) {
        ColorSchemeKeyTokens.Background -> background
        ColorSchemeKeyTokens.Error -> error
        ColorSchemeKeyTokens.ErrorContainer -> errorContainer
        ColorSchemeKeyTokens.ErrorDim -> errorDim
        ColorSchemeKeyTokens.OnBackground -> onBackground
        ColorSchemeKeyTokens.OnError -> onError
        ColorSchemeKeyTokens.OnErrorContainer -> onErrorContainer
        ColorSchemeKeyTokens.OnPrimary -> onPrimary
        ColorSchemeKeyTokens.OnPrimaryContainer -> onPrimaryContainer
        ColorSchemeKeyTokens.OnSecondary -> onSecondary
        ColorSchemeKeyTokens.OnSecondaryContainer -> onSecondaryContainer
        ColorSchemeKeyTokens.OnSurface -> onSurface
        ColorSchemeKeyTokens.OnSurfaceVariant -> onSurfaceVariant
        ColorSchemeKeyTokens.OnTertiary -> onTertiary
        ColorSchemeKeyTokens.OnTertiaryContainer -> onTertiaryContainer
        ColorSchemeKeyTokens.Outline -> outline
        ColorSchemeKeyTokens.OutlineVariant -> outlineVariant
        ColorSchemeKeyTokens.Primary -> primary
        ColorSchemeKeyTokens.PrimaryContainer -> primaryContainer
        ColorSchemeKeyTokens.PrimaryDim -> primaryDim
        ColorSchemeKeyTokens.Secondary -> secondary
        ColorSchemeKeyTokens.SecondaryContainer -> secondaryContainer
        ColorSchemeKeyTokens.SecondaryDim -> secondaryDim
        ColorSchemeKeyTokens.SurfaceContainer -> surfaceContainer
        ColorSchemeKeyTokens.SurfaceContainerHigh -> surfaceContainerHigh
        ColorSchemeKeyTokens.SurfaceContainerLow -> surfaceContainerLow
        ColorSchemeKeyTokens.Tertiary -> tertiary
        ColorSchemeKeyTokens.TertiaryContainer -> tertiaryContainer
        ColorSchemeKeyTokens.TertiaryDim -> tertiaryDim
    }

    override fun toString(): String = buildString {
        append("ColorScheme(primary=").append(primary)
        append(", primaryDim=").append(primaryDim)
        append(", primaryContainer=").append(primaryContainer)
        append(", onPrimary=").append(onPrimary)
        append(", onPrimaryContainer=").append(onPrimaryContainer)
        append(", secondary=").append(secondary)
        append(", secondaryDim=").append(secondaryDim)
        append(", secondaryContainer=").append(secondaryContainer)
        append(", onSecondary=").append(onSecondary)
        append(", onSecondaryContainer=").append(onSecondaryContainer)
        append(", tertiary=").append(tertiary)
        append(", tertiaryDim=").append(tertiaryDim)
        append(", tertiaryContainer=").append(tertiaryContainer)
        append(", onTertiary=").append(onTertiary)
        append(", onTertiaryContainer=").append(onTertiaryContainer)
        append(", surfaceContainerLow=").append(surfaceContainerLow)
        append(", surfaceContainer=").append(surfaceContainer)
        append(", surfaceContainerHigh=").append(surfaceContainerHigh)
        append(", onSurface=").append(onSurface)
        append(", onSurfaceVariant=").append(onSurfaceVariant)
        append(", outline=").append(outline)
        append(", outlineVariant=").append(outlineVariant)
        append(", background=").append(background)
        append(", onBackground=").append(onBackground)
        append(", error=").append(error)
        append(", errorDim=").append(errorDim)
        append(", errorContainer=").append(errorContainer)
        append(", onError=").append(onError)
        append(", onErrorContainer=").append(onErrorContainer)
        append(")")
    }

    companion object {
        const val DisabledContentAlpha = 0.38f
        const val DisabledContainerAlpha = 0.12f
        const val DisabledBorderAlpha = 0.20f
    }
}

internal fun Color.toDisabledColor(alpha: Float): Color = copy(alpha = this.alpha * alpha)
