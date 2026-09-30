package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Row
import com.huanli233.hibari.foundation.RowScope
import com.huanli233.hibari.foundation.attributes.minHeight
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.wear.attributes.clickable
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.tokens.ChildButtonTokens
import com.huanli233.hibari.wear.tokens.FilledTonalButtonTokens
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import com.huanli233.hibari.wear.tokens.FilledButtonTokens
import com.huanli233.hibari.wear.tokens.OutlinedButtonTokens
import com.huanli233.hibari.wear.tokens.ShapeKeyTokens
import com.huanli233.hibari.wear.tokens.ShapeTokens

/**
 * Ported from androidx.wear.compose.material3.ButtonColors.
 *
 * Upstream animates every field through `animateColor` driven by `interactionSource`. Here the
 * pressed and disabled variants ride inside [ContainerSpec] and [ContainerDrawable] swaps them on
 * drawable state, so the pixels match while the press transition is instant instead of sprung.
 */
data class ButtonColors(
    val containerColor: Color,
    val contentColor: Color,
    val secondaryContentColor: Color,
    val iconColor: Color,
    val disabledContainerColor: Color,
    val disabledContentColor: Color,
    val disabledSecondaryContentColor: Color,
    val disabledIconColor: Color,
    val border: BorderStroke? = null,
    val disabledBorder: BorderStroke? = null,
) {

    fun containerSpec(shape: Shape): ContainerSpec = ContainerSpec(
        shape = shape,
        containerColor = containerColor,
        border = border,
        disabledContainerColor = disabledContainerColor,
        disabledBorder = disabledBorder,
    )
}

/**
 * Ported from androidx.wear.compose.material3.Button.
 *
 * The `containerPainter` overloads (image background plus scrim) are not ported: they need
 * Compose's `Painter`/`ContentScale` alignment maths, whose Views equivalent is a `Drawable` a
 * caller can supply through [Modifier.container]. `onLongClick`, `interactionSource` and
 * `transformation` go with them.
 *
 * @param colors Defaults to `null` and resolves in the body: `ButtonDefaults.buttonColors()` reads
 *   `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable` `$default`
 *   method that cannot.
 */
@Tunable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Tunable RowScope.() -> Unit,
) {
    val resolved = colors ?: ButtonDefaults.buttonColors()
    val contentColor = if (enabled) resolved.contentColor else resolved.disabledContentColor
    val scope = content
    Row(
        // Upstream routes every Button overload through `Modifier.buttonSizeModifier()`
        // (`material3/Button.kt:138` for this one, and `:230, :318, :405, :491, :609, :728, :846,
        // :959` for the rest of the public variants), which is
        // `defaultMinSize(minHeight = ButtonDefaults.Height)` (`:2359-2360`), applied immediately
        // after the caller's modifier. [FilledTonalButton] and [OutlinedButton] delegate here, so all
        // three variants get the floor from this line. `Modifier.minHeight` writes `View.minimumHeight`
        // (`hibari-foundation/.../ViewAttributes.kt:89-92`), which only bites when the height is
        // unspecified or at-most, so a caller's explicit `height` still wins - the precedence
        // `defaultMinSize` has upstream.
        modifier = modifier
            .minHeight(ButtonDefaults.Height)
            .container(resolved.containerSpec(shape))
            .clickable(enabled, onClick)
            .padding(contentPadding),
    ) {
        provideContentColor(contentColor) { scope() }
    }
}

@Tunable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Tunable RowScope.() -> Unit,
) {
    val resolved = colors ?: ButtonDefaults.filledTonalButtonColors()
    Button(onClick, modifier, enabled, shape, resolved, contentPadding, content)
}

@Tunable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Tunable RowScope.() -> Unit,
) {
    val resolved = colors ?: ButtonDefaults.outlinedButtonColors()
    Button(onClick, modifier, enabled, shape, resolved, contentPadding, content)
}

object ButtonDefaults {
    val shape: Shape = ShapeTokens.CornerLarge

    val ButtonHorizontalPadding: Dp = 14.dp
    val ButtonVerticalPadding: Dp = 6.dp
    val CompactButtonHorizontalPadding: Dp = 12.dp
    val CompactButtonVerticalPadding: Dp = 0.dp

    val ContentPadding: PaddingValues =
        PaddingValues(horizontal = ButtonHorizontalPadding, vertical = ButtonVerticalPadding)

    val CompactContentPadding: PaddingValues = PaddingValues(
        horizontal = CompactButtonHorizontalPadding,
        vertical = CompactButtonVerticalPadding,
    )

    /**
     * `ButtonDefaults.Height` (`material3/Button.kt:1893`), whose value is
     * `FilledButtonTokens.ContainerHeight` — 52.dp, and the same 52.dp as `ChildButtonTokens.ContainerHeight`.
     *
     * Renamed from this port's former `ContainerHeight`, which took the *token's* name for the
     * *member*: upstream names the member `Height` and only the token `ContainerHeight`, exactly as
     * `CardDefaults.Height` (`material3/Card.kt:1088`) already reads here, and as
     * `CompactButtonDefaults.Height` (`material3/Button.kt:2210`) does one object below it. Upstream
     * does keep one `*Height` member with a prefix — `CompactButtonHeight` (`:1932`) — and that one is
     * `@Deprecated` with `ReplaceWith("CompactButtonDefaults.Height")` (`:1925-1931`), i.e. their own
     * migration for a name that sat on the wrong object.
     */
    val Height: Dp = FilledButtonTokens.ContainerHeight

    @Tunable
    fun buttonColors(): ButtonColors = MaterialTheme.colorScheme.filledButtonColors()

    /**
     * `ButtonDefaults.buttonColors(containerColor, contentColor, secondaryContentColor, iconColor,
     * disabledContainerColor, disabledContentColor, disabledSecondaryContentColor, disabledIconColor)`
     * (`material3/Button.kt:1797-1816`): the default filled set with any of its eight roles replaced,
     * each `Color.Unspecified` keeping the default — upstream routes every argument through
     * `ButtonColors.copy`'s `takeOrElse` (`:2268-2287`).
     *
     * The merge is spelled out here rather than through a `copy` member because this module's
     * [ButtonColors] is a data class, whose generated `copy` already takes these eight plus the two
     * border fields with *plain* defaults: a second, eight-parameter `copy` overload would make every
     * colour-only `copy(...)` call ambiguous, and the generated one would hand back
     * [Color.Unspecified] for a role the caller never mentioned.
     *
     * This object's `border` / `disabledBorder` are a port-side addition with no upstream counterpart
     * (`OutlinedButtonTokens` carries them instead), so an override here never touches them: they ride
     * in from the defaults, which is what upstream's `copy` does for the fields it does not name.
     */
    @Tunable
    fun buttonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        secondaryContentColor: Color = Color.Unspecified,
        iconColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
        disabledSecondaryContentColor: Color = Color.Unspecified,
        disabledIconColor: Color = Color.Unspecified,
    ): ButtonColors = MaterialTheme.colorScheme.filledButtonColors().buttonColorsOverriding(
        containerColor = containerColor,
        contentColor = contentColor,
        secondaryContentColor = secondaryContentColor,
        iconColor = iconColor,
        disabledContainerColor = disabledContainerColor,
        disabledContentColor = disabledContentColor,
        disabledSecondaryContentColor = disabledSecondaryContentColor,
        disabledIconColor = disabledIconColor,
    )

    @Tunable
    fun filledTonalButtonColors(): ButtonColors =
        MaterialTheme.colorScheme.filledTonalButtonColors()

    @Tunable
    fun outlinedButtonColors(): ButtonColors = MaterialTheme.colorScheme.outlinedButtonColors()

    /**
     * `ButtonDefaults.childButtonColors()` (`material3/Button.kt:1601-1603`): the transparent,
     * lowest-emphasis set upstream recommends for a `ChildButton`, built from `ChildButtonTokens`
     * with the three disabled roles all resolved from the one `DisabledContentColor`
     * (`material3/Button.kt:2071-2097`).
     *
     * [ChildButton] already called this name: `ChildButton.kt` declares the same two factories as
     * `ButtonDefaults` extensions because this object used to have no room for them. A member beats an
     * extension with the same signature, so those call sites now bind here and the extensions are dead
     * code awaiting removal - their bodies are the same token table, so no colour moves.
     */
    @Tunable
    fun childButtonColors(): ButtonColors = MaterialTheme.colorScheme.childButtonColors()

    /**
     * The overriding overload (`material3/Button.kt:1622-1640`). It names six roles, not eight:
     * upstream hard-codes `containerColor` and `disabledContainerColor` to
     * [Color.Transparent] through `ButtonColors.copy` and gives a caller no way to replace them,
     * because a child button has no container by design. [buttonColorsOverriding] is the same fold
     * this object's other factory uses.
     */
    @Tunable
    fun childButtonColors(
        contentColor: Color = Color.Unspecified,
        secondaryContentColor: Color = Color.Unspecified,
        iconColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
        disabledSecondaryContentColor: Color = Color.Unspecified,
        disabledIconColor: Color = Color.Unspecified,
    ): ButtonColors = MaterialTheme.colorScheme.childButtonColors().buttonColorsOverriding(
        containerColor = Color.Transparent,
        contentColor = contentColor,
        secondaryContentColor = secondaryContentColor,
        iconColor = iconColor,
        disabledContainerColor = Color.Transparent,
        disabledContentColor = disabledContentColor,
        disabledSecondaryContentColor = disabledSecondaryContentColor,
        disabledIconColor = disabledIconColor,
    )
}

/**
 * Upstream's `ButtonColors.copy` (`material3/Button.kt:2268-2287`): every named role replaced, every
 * `Color.Unspecified` kept, and the two border fields carried through untouched.
 */
private fun ButtonColors.buttonColorsOverriding(
    containerColor: Color,
    contentColor: Color,
    secondaryContentColor: Color,
    iconColor: Color,
    disabledContainerColor: Color,
    disabledContentColor: Color,
    disabledSecondaryContentColor: Color,
    disabledIconColor: Color,
): ButtonColors = ButtonColors(
    containerColor = containerColor.takeOrElse { this.containerColor },
    contentColor = contentColor.takeOrElse { this.contentColor },
    secondaryContentColor = secondaryContentColor.takeOrElse { this.secondaryContentColor },
    iconColor = iconColor.takeOrElse { this.iconColor },
    disabledContainerColor = disabledContainerColor.takeOrElse { this.disabledContainerColor },
    disabledContentColor = disabledContentColor.takeOrElse { this.disabledContentColor },
    disabledSecondaryContentColor =
        disabledSecondaryContentColor.takeOrElse { this.disabledSecondaryContentColor },
    disabledIconColor = disabledIconColor.takeOrElse { this.disabledIconColor },
    border = border,
    disabledBorder = disabledBorder,
)

private fun ColorScheme.filledButtonColors(): ButtonColors = ButtonColors(
    containerColor = FilledButtonTokens.ContainerColor.resolve(this),
    contentColor = FilledButtonTokens.LabelColor.resolve(this),
    secondaryContentColor = FilledButtonTokens.SecondaryLabelColor.resolve(this)
        .copy(alpha = FilledButtonTokens.SecondaryLabelOpacity),
    iconColor = FilledButtonTokens.IconColor.resolve(this),
    disabledContainerColor = FilledButtonTokens.DisabledContainerColor.resolve(this)
        .toDisabledColor(FilledButtonTokens.DisabledContainerOpacity),
    disabledContentColor = FilledButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(FilledButtonTokens.DisabledContentOpacity),
    disabledSecondaryContentColor = FilledButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(FilledButtonTokens.DisabledContentOpacity),
    disabledIconColor = FilledButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(FilledButtonTokens.DisabledContentOpacity),
)

private fun ColorScheme.variantButtonColors(): ButtonColors = ButtonColors(
    containerColor = FilledButtonTokens.VariantContainerColor.resolve(this),
    contentColor = FilledButtonTokens.VariantLabelColor.resolve(this),
    secondaryContentColor = FilledButtonTokens.VariantSecondaryLabelColor.resolve(this)
        .copy(alpha = FilledButtonTokens.VariantSecondaryLabelOpacity),
    iconColor = FilledButtonTokens.VariantIconColor.resolve(this),
    disabledContainerColor = FilledButtonTokens.DisabledContainerColor.resolve(this)
        .toDisabledColor(FilledButtonTokens.DisabledContainerOpacity),
    disabledContentColor = FilledButtonTokens.VariantLabelColor.resolve(this)
        .toDisabledColor(FilledButtonTokens.DisabledContentOpacity),
    disabledSecondaryContentColor = FilledButtonTokens.VariantLabelColor.resolve(this)
        .toDisabledColor(FilledButtonTokens.DisabledContentOpacity),
    disabledIconColor = FilledButtonTokens.VariantLabelColor.resolve(this)
        .toDisabledColor(FilledButtonTokens.DisabledContentOpacity),
)

private fun ColorScheme.filledTonalButtonColors(): ButtonColors = ButtonColors(
    containerColor = FilledTonalButtonTokens.ContainerColor.resolve(this),
    contentColor = FilledTonalButtonTokens.LabelColor.resolve(this),
    secondaryContentColor = FilledTonalButtonTokens.SecondaryLabelColor.resolve(this),
    iconColor = FilledTonalButtonTokens.IconColor.resolve(this),
    disabledContainerColor = FilledTonalButtonTokens.DisabledContainerColor.resolve(this)
        .toDisabledColor(FilledTonalButtonTokens.DisabledContainerOpacity),
    disabledContentColor = FilledTonalButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(FilledTonalButtonTokens.DisabledContentOpacity),
    disabledSecondaryContentColor = FilledTonalButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(FilledTonalButtonTokens.DisabledContentOpacity),
    disabledIconColor = FilledTonalButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(FilledTonalButtonTokens.DisabledContentOpacity),
)

private fun ColorScheme.outlinedButtonColors(): ButtonColors = ButtonColors(
    containerColor = Color.Transparent,
    contentColor = OutlinedButtonTokens.LabelColor.resolve(this),
    secondaryContentColor = OutlinedButtonTokens.SecondaryLabelColor.resolve(this),
    iconColor = OutlinedButtonTokens.IconColor.resolve(this),
    disabledContainerColor = Color.Transparent,
    disabledContentColor = OutlinedButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(OutlinedButtonTokens.DisabledContentOpacity),
    disabledSecondaryContentColor = OutlinedButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(OutlinedButtonTokens.DisabledContentOpacity),
    disabledIconColor = OutlinedButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(OutlinedButtonTokens.DisabledContentOpacity),
    border = BorderStroke(
        OutlinedButtonTokens.ContainerBorderWidth,
        OutlinedButtonTokens.ContainerBorderColor.resolve(this),
    ),
    disabledBorder = BorderStroke(
        OutlinedButtonTokens.ContainerBorderWidth,
        OutlinedButtonTokens.DisabledContainerBorderColor.resolve(this)
            .toDisabledColor(OutlinedButtonTokens.DisabledContainerBorderOpacity),
    ),
)

/**
 * Upstream's private `ColorScheme.defaultChildButtonColors` (`material3/Button.kt:2071-2097`), named
 * after this object's factory the way the three above are named after theirs.
 */
private fun ColorScheme.childButtonColors(): ButtonColors = ButtonColors(
    containerColor = Color.Transparent,
    contentColor = ChildButtonTokens.LabelColor.resolve(this),
    secondaryContentColor = ChildButtonTokens.SecondaryLabelColor.resolve(this),
    iconColor = ChildButtonTokens.IconColor.resolve(this),
    disabledContainerColor = Color.Transparent,
    disabledContentColor = ChildButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(ChildButtonTokens.DisabledContentOpacity),
    disabledSecondaryContentColor = ChildButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(ChildButtonTokens.DisabledContentOpacity),
    disabledIconColor = ChildButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(ChildButtonTokens.DisabledContentOpacity),
)
