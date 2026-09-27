package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Row
import com.huanli233.hibari.foundation.RowScope
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.wear.attributes.clickable
import com.huanli233.hibari.wear.attributes.container
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
        modifier = modifier
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

    val ContainerHeight: Dp = FilledButtonTokens.ContainerHeight

    @Tunable
    fun buttonColors(): ButtonColors = MaterialTheme.colorScheme.filledButtonColors()

    @Tunable
    fun filledTonalButtonColors(): ButtonColors = MaterialTheme.colorScheme.variantButtonColors()

    @Tunable
    fun outlinedButtonColors(): ButtonColors = MaterialTheme.colorScheme.outlinedButtonColors()
}

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
