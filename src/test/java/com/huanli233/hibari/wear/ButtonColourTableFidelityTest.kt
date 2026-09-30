package com.huanli233.hibari.wear

import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.wear.tokens.ChildButtonTokens
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import com.huanli233.hibari.wear.tokens.FilledTonalButtonTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour tables the button family is drawn from, pinned on the JVM.
 *
 * `ButtonDefaults.filledTonalButtonColors()` and `ButtonDefaults.childButtonColors()` are `@Tunable`
 * and read `MaterialTheme.colorScheme`, so they need a live tuner and cannot be called from a unit
 * test; the two steps they perform however are pure and are pinned here one at a time:
 *
 *  1. which `ColorSchemeKeyTokens` each role of the token table names (`tokens/FilledTonalButtonTokens.kt`,
 *     read by `Button.kt`'s private `ColorScheme.filledTonalButtonColors`);
 *  2. which `ColorScheme` slot each of those tokens resolves to (`ColorScheme.fromToken`, the port of
 *     upstream's `material3/ColorScheme.kt:356-401`).
 *
 * A repoint in either step is invisible in review and shows up on the watch as one wrong grey, so
 * step 2 is checked against a scheme whose 29 slots all hold a different colour: every key is then
 * individually observable, which the real palette is not.
 */
class ButtonColourTableFidelityTest {

    /** 29 mutually distinct sentinels, one per `ColorScheme` slot. */
    private fun sentinel(i: Int) = Color(0xFF000000L or i.toLong())

    private val scheme = ColorScheme(
        primary = sentinel(1),
        primaryDim = sentinel(2),
        primaryContainer = sentinel(3),
        onPrimary = sentinel(4),
        onPrimaryContainer = sentinel(5),
        secondary = sentinel(6),
        secondaryDim = sentinel(7),
        secondaryContainer = sentinel(8),
        onSecondary = sentinel(9),
        onSecondaryContainer = sentinel(10),
        tertiary = sentinel(11),
        tertiaryDim = sentinel(12),
        tertiaryContainer = sentinel(13),
        onTertiary = sentinel(14),
        onTertiaryContainer = sentinel(15),
        surfaceContainerLow = sentinel(16),
        surfaceContainer = sentinel(17),
        surfaceContainerHigh = sentinel(18),
        onSurface = sentinel(19),
        onSurfaceVariant = sentinel(20),
        outline = sentinel(21),
        outlineVariant = sentinel(22),
        background = sentinel(23),
        onBackground = sentinel(24),
        error = sentinel(25),
        errorDim = sentinel(26),
        errorContainer = sentinel(27),
        onError = sentinel(28),
        onErrorContainer = sentinel(29),
    )

    /**
     * Hand-copied from upstream `material3/ColorScheme.kt:357-385`, key by key. Written as data so the
     * `when` in `ColorScheme.fromToken` has something outside itself to be checked against.
     */
    private val resolveTable = listOf(
        ColorSchemeKeyTokens.Background to scheme.background,
        ColorSchemeKeyTokens.Error to scheme.error,
        ColorSchemeKeyTokens.ErrorContainer to scheme.errorContainer,
        ColorSchemeKeyTokens.ErrorDim to scheme.errorDim,
        ColorSchemeKeyTokens.OnBackground to scheme.onBackground,
        ColorSchemeKeyTokens.OnError to scheme.onError,
        ColorSchemeKeyTokens.OnErrorContainer to scheme.onErrorContainer,
        ColorSchemeKeyTokens.OnPrimary to scheme.onPrimary,
        ColorSchemeKeyTokens.OnPrimaryContainer to scheme.onPrimaryContainer,
        ColorSchemeKeyTokens.OnSecondary to scheme.onSecondary,
        ColorSchemeKeyTokens.OnSecondaryContainer to scheme.onSecondaryContainer,
        ColorSchemeKeyTokens.OnSurface to scheme.onSurface,
        ColorSchemeKeyTokens.OnSurfaceVariant to scheme.onSurfaceVariant,
        ColorSchemeKeyTokens.OnTertiary to scheme.onTertiary,
        ColorSchemeKeyTokens.OnTertiaryContainer to scheme.onTertiaryContainer,
        ColorSchemeKeyTokens.Outline to scheme.outline,
        ColorSchemeKeyTokens.OutlineVariant to scheme.outlineVariant,
        ColorSchemeKeyTokens.Primary to scheme.primary,
        ColorSchemeKeyTokens.PrimaryContainer to scheme.primaryContainer,
        ColorSchemeKeyTokens.PrimaryDim to scheme.primaryDim,
        ColorSchemeKeyTokens.Secondary to scheme.secondary,
        ColorSchemeKeyTokens.SecondaryContainer to scheme.secondaryContainer,
        ColorSchemeKeyTokens.SecondaryDim to scheme.secondaryDim,
        ColorSchemeKeyTokens.SurfaceContainer to scheme.surfaceContainer,
        ColorSchemeKeyTokens.SurfaceContainerHigh to scheme.surfaceContainerHigh,
        ColorSchemeKeyTokens.SurfaceContainerLow to scheme.surfaceContainerLow,
        ColorSchemeKeyTokens.Tertiary to scheme.tertiary,
        ColorSchemeKeyTokens.TertiaryContainer to scheme.tertiaryContainer,
        ColorSchemeKeyTokens.TertiaryDim to scheme.tertiaryDim,
    )

    @Test
    fun everyColourTokenResolvesToItsOwnSchemeSlot() {
        // One row per enum entry: an unlisted key would still compile, so pin the size too.
        assertEquals(29, resolveTable.size)
        assertEquals(
            "the sentinels must be distinct or the check below is void",
            29,
            resolveTable.map { it.second }.distinct().size,
        )
        for ((token, expected) in resolveTable) {
            assertEquals(token.toString(), expected, token.resolve(scheme))
        }
    }

    @Test
    fun defaultSchemeSlotsAreTheVerbatimPaletteColours() {
        // `ColorScheme`'s defaults (ColorScheme.kt:15-43) name a `ColorTokens` entry each, and those
        // are the 8-bit literals in tokens/PaletteTokens.kt. These are the four the filled tonal and
        // child buttons actually draw with, plus the background pair.
        val d = ColorScheme()
        assertEquals(Color(0xFFE9DDFF), d.primary) // Primary90 = (233, 221, 255), PaletteTokens.kt:74
        assertEquals(Color(0xFF332E3C), d.surfaceContainer) // Neutral20 = (51, 46, 60), :43
        assertEquals(Color(0xFFF6EDFF), d.onSurface) // Neutral95 = (246, 237, 255), :51
        assertEquals(Color(0xFFCAC4D0), d.onSurfaceVariant) // NeutralVariant80 = (202, 196, 208), :61
        assertEquals(Color(0xFF000000), d.background) // Neutral0
        assertEquals(Color(0xFFFFFFFF), d.onBackground) // Neutral100
    }

    @Test
    fun filledTonalAndChildRolesComeFromTheseTokens() {
        // tokens/FilledTonalButtonTokens.kt:25-38 and tokens/ChildButtonTokens.kt:25-36, which
        // Button.kt reads as `FilledTonalButtonTokens.ContainerColor.resolve(...)` and friends.
        assertEquals(ColorSchemeKeyTokens.SurfaceContainer, FilledTonalButtonTokens.ContainerColor)
        assertEquals(ColorSchemeKeyTokens.OnSurface, FilledTonalButtonTokens.LabelColor)
        assertEquals(
            ColorSchemeKeyTokens.OnSurfaceVariant,
            FilledTonalButtonTokens.SecondaryLabelColor,
        )
        assertEquals(ColorSchemeKeyTokens.Primary, FilledTonalButtonTokens.IconColor)
        // Both disabled roles name `OnSurface`; only the opacity differs, and the container is
        // knocked down harder than the text (0.12 vs 0.38).
        assertEquals(ColorSchemeKeyTokens.OnSurface, FilledTonalButtonTokens.DisabledContainerColor)
        assertEquals(0.12f, FilledTonalButtonTokens.DisabledContainerOpacity, 0f)
        assertEquals(ColorSchemeKeyTokens.OnSurface, FilledTonalButtonTokens.DisabledContentColor)
        assertEquals(0.38f, FilledTonalButtonTokens.DisabledContentOpacity, 0f)
        // A child button has no container token at all, which is why Button.kt hard-codes
        // `Color.Transparent` for both of its container roles: it has one disabled opacity, not the
        // container-plus-content pair the filled tonal set carries.
        assertEquals(ColorSchemeKeyTokens.OnSurface, ChildButtonTokens.LabelColor)
        assertEquals(ColorSchemeKeyTokens.OnSurfaceVariant, ChildButtonTokens.SecondaryLabelColor)
        assertEquals(ColorSchemeKeyTokens.Primary, ChildButtonTokens.IconColor)
        assertEquals(ColorSchemeKeyTokens.OnSurface, ChildButtonTokens.DisabledContentColor)
        assertEquals(0.38f, ChildButtonTokens.DisabledContentOpacity, 0f)
        assertNotEquals(
            ColorSchemeKeyTokens.OnSurface,
            FilledTonalButtonTokens.ContainerColor,
        )
    }

    @Test
    fun resolvingThoseRolesAgainstTheDefaultSchemeGivesThePalette() {
        // The nearest reachable stand-in for `ButtonDefaults.filledTonalButtonColors()`, which needs a
        // tuner: same token, same scheme, so the four resolved colours are the four literals.
        val d = ColorScheme()
        assertEquals(Color(0xFF332E3C), FilledTonalButtonTokens.ContainerColor.resolve(d))
        assertEquals(Color(0xFFF6EDFF), FilledTonalButtonTokens.LabelColor.resolve(d))
        assertEquals(Color(0xFFCAC4D0), FilledTonalButtonTokens.SecondaryLabelColor.resolve(d))
        assertEquals(Color(0xFFE9DDFF), FilledTonalButtonTokens.IconColor.resolve(d))
        assertEquals(Color(0xFFE9DDFF), ChildButtonTokens.IconColor.resolve(d))
    }

    @Test
    fun contentColourIsATableLookUpNotAContrastCalculation() {
        // upstream `material3/ColorScheme.kt:307-327`, ported at ColorScheme.kt:91-104.
        assertEquals(scheme.onPrimary, scheme.contentColorFor(scheme.primary))
        assertEquals(scheme.onPrimary, scheme.contentColorFor(scheme.primaryDim))
        assertEquals(scheme.onPrimaryContainer, scheme.contentColorFor(scheme.primaryContainer))
        // Three container tones share one content colour...
        assertEquals(scheme.onSurface, scheme.contentColorFor(scheme.surfaceContainer))
        assertEquals(scheme.onSurface, scheme.contentColorFor(scheme.surfaceContainerLow))
        assertEquals(scheme.onSurface, scheme.contentColorFor(scheme.surfaceContainerHigh))
        // ...and there is no `onErrorDim`, so `errorDim` borrows `onError` (upstream :323).
        assertEquals(scheme.onError, scheme.contentColorFor(scheme.errorDim))
        assertEquals(scheme.onError, scheme.contentColorFor(scheme.error))
        // Anything that is not a background role is left unspecified for the caller to inherit.
        assertEquals(Color.Unspecified, scheme.contentColorFor(scheme.onSurface))
        assertEquals(Color.Unspecified, scheme.contentColorFor(scheme.outline))
        assertEquals(Color.Unspecified, scheme.contentColorFor(Color(0xFF112233)))
    }

    @Test
    fun disabledColoursScaleTheExistingAlphaRatherThanReplacingIt() {
        // ColorScheme.kt:178, port of upstream `material3/ColorScheme.kt:403-404`
        // (`copy(alpha = this.alpha * disabledAlpha)`).
        val halfTransparent = Color(0x80FF8033)
        val scaled = halfTransparent.toDisabledColor(0.5f)
        // 128/255 * 0.5 = 64.000005, and the Float factory truncates 64.500005 to 64.
        assertEquals(0x40, scaled.toArgb() ushr 24)
        // Only the alpha moves; the hue rides through the round trip.
        assertEquals(0xFF, (scaled.toArgb() ushr 16) and 0xFF)
        assertEquals(0x80, (scaled.toArgb() ushr 8) and 0xFF)
        assertEquals(0x33, scaled.toArgb() and 0xFF)
        // The other form in the same table replaces the alpha instead: `variantButtonColors` uses
        // `copy(alpha = VariantSecondaryLabelOpacity)` for the secondary label and `toDisabledColor`
        // for the disabled three (Button.kt:292-302), and for an opaque token the two coincide -
        // which is why only a non-opaque colour can tell them apart.
        assertEquals(0x80, halfTransparent.copy(alpha = 0.5f).toArgb() ushr 24)
    }

    @Test
    fun disabledAlphaConstantsMatchUpstream() {
        // ColorScheme.kt:172-174 vs upstream `material3/ColorScheme.kt:413-415`.
        assertEquals(0.38f, ColorScheme.DisabledContentAlpha, 0f)
        assertEquals(0.12f, ColorScheme.DisabledContainerAlpha, 0f)
        assertEquals(0.20f, ColorScheme.DisabledBorderAlpha, 0f)
    }

    @Test
    fun containerIsEmptyOnlyWhenNothingWouldBePainted() {
        // ContainerSpec.kt:62-64: `Color.Transparent` is not the test, a zero alpha is - so a colour
        // that carries the palette's hue at alpha 0 still skips the background.
        val transparentHue = Color(0x00FF8033)
        val colors = ButtonColors(
            containerColor = transparentHue,
            contentColor = Color(0xFFF6EDFF),
            secondaryContentColor = Color(0xFFCAC4D0),
            iconColor = Color(0xFFE9DDFF),
            disabledContainerColor = transparentHue.toDisabledColor(0.12f),
            disabledContentColor = transparentHue.toDisabledColor(0.38f),
            disabledSecondaryContentColor = transparentHue.toDisabledColor(0.38f),
            disabledIconColor = transparentHue.toDisabledColor(0.38f),
        )
        val spec = colors.containerSpec(ButtonDefaults.shape)
        assertTrue(spec.isEmpty)
        assertEquals(transparentHue, spec.containerColor)
        // The disabled pair rides along; an outlined button's border has nowhere else to go.
        assertEquals(colors.disabledContainerColor, spec.disabledContainerColor)
        assertNotEquals(colors.contentColor, spec.containerColor)
    }
}
