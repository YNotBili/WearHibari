package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.attributes.clickable
import com.huanli233.hibari.wear.tokens.FilledTextButtonTokens
import com.huanli233.hibari.wear.tokens.FilledTonalTextButtonTokens
import com.huanli233.hibari.wear.tokens.OutlinedTextButtonTokens
import com.huanli233.hibari.wear.tokens.ShapeTokens
import com.huanli233.hibari.wear.tokens.TextButtonTokens

/**
 * Ported from androidx.wear.compose.material3.TextButton (`material3/TextButton.kt:69-148`), which
 * is `IconButton`'s sibling on the same private `RoundButton` (`material3/RoundButton.kt:11-42`) - a
 * centred `Box`, clipped, clickable, bordered, painted - with one difference that matters to callers:
 * it sets no size and no content padding of its own. Upstream's KDoc there says the same, and hands
 * out [TextButtonDefaults.SmallButtonSize] / [TextButtonDefaults.DefaultButtonSize] /
 * [TextButtonDefaults.LargeButtonSize] with the matching text style so a caller can size it.
 *
 * # Not ported, and why
 *
 *  - `onLongClick` / `onLongClickLabel` / `interactionSource` / `ripple`: as in
 *    [IconButton], no long-click channel and no indication system. The pressed-shape morph those
 *    layers drive is kept, through [ContainerSpec.pressedShape].
 *  - `Modifier.minimumInteractiveComponentSize()` (`:131`): upstream applies it, and with no size of
 *    its own this button would honour it as a 48.dp floor. Hibari has that helper only as a
 *    pixel-argument extension used by the other rows, so the floor is left to the caller's
 *    [com.huanli233.hibari.foundation.attributes.minHeight] - stated rather than silently missing.
 *
 * @param shapes Defaults to `null` and resolves in the body: `TextButtonDefaults.shapes()` reads
 *   `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable` `$default`
 *   method that cannot.
 * @param colors Defaults to `null` for the same reason.
 */
@Tunable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shapes: TextButtonShapes? = null,
    colors: TextButtonColors? = null,
    border: BorderStroke? = null,
    content: @Tunable BoxScope.() -> Unit,
) {
    val resolvedShapes = shapes ?: TextButtonDefaults.shapes()
    val resolvedColors = colors ?: TextButtonDefaults.textButtonColors()
    val scope = content
    Box(
        modifier = modifier
            .container(
                ContainerSpec(
                    shape = resolvedShapes.shape,
                    containerColor = resolvedColors.containerColor(enabled),
                    border = border,
                    pressedShape = resolvedShapes.pressedShape.takeIf {
                        it != resolvedShapes.shape
                    },
                    disabledContainerColor = resolvedColors.disabledContainerColor,
                ),
            )
            .clickable(enabled, onClick),
    ) {
        provideContentColorAndStyle(
            resolvedColors.contentColor(enabled),
            TextButtonDefaults.defaultButtonTextStyle,
        ) { scope() }
    }
}

/**
 * Ported from androidx.wear.compose.material3.TextButtonColors (`material3/TextButton.kt:635-700`).
 *
 * Upstream animates the enabled/disabled pairs through `animateColorAsState` on the interaction
 * source; here the disabled pair reaches [ContainerDrawable] as a drawable state, so the pixels
 * match and the transition is the shared state fade.
 */
class TextButtonColors(
    val containerColor: Color,
    val contentColor: Color,
    val disabledContainerColor: Color,
    val disabledContentColor: Color,
) {

    /** Each argument runs through `takeOrElse`, so [Color.Unspecified] keeps what is already set. */
    fun copy(
        containerColor: Color = this.containerColor,
        contentColor: Color = this.contentColor,
        disabledContainerColor: Color = this.disabledContainerColor,
        disabledContentColor: Color = this.disabledContentColor,
    ): TextButtonColors = TextButtonColors(
        containerColor = containerColor.takeOrElse { this.containerColor },
        contentColor = contentColor.takeOrElse { this.contentColor },
        disabledContainerColor = disabledContainerColor.takeOrElse { this.disabledContainerColor },
        disabledContentColor = disabledContentColor.takeOrElse { this.disabledContentColor },
    )

    fun containerColor(enabled: Boolean): Color =
        if (enabled) containerColor else disabledContainerColor

    fun contentColor(enabled: Boolean): Color =
        if (enabled) contentColor else disabledContentColor

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is TextButtonColors) return false
        return containerColor == other.containerColor &&
            contentColor == other.contentColor &&
            disabledContainerColor == other.disabledContainerColor &&
            disabledContentColor == other.disabledContentColor
    }

    override fun hashCode(): Int {
        var result = containerColor.hashCode()
        result = 31 * result + contentColor.hashCode()
        result = 31 * result + disabledContainerColor.hashCode()
        result = 31 * result + disabledContentColor.hashCode()
        return result
    }
}

/**
 * Ported from androidx.wear.compose.material3.TextButtonShapes (`material3/TextButton.kt:705-740`):
 * the shape pair morphed between while pressed, [pressedShape] defaulting to [shape].
 */
class TextButtonShapes(
    val shape: Shape,
    val pressedShape: Shape = shape,
) {

    fun copy(
        shape: Shape? = this.shape,
        pressedShape: Shape? = this.pressedShape,
    ): TextButtonShapes = TextButtonShapes(
        shape = shape ?: this.shape,
        pressedShape = pressedShape ?: this.pressedShape,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is TextButtonShapes) return false
        return shape == other.shape && pressedShape == other.pressedShape
    }

    override fun hashCode(): Int {
        var result = shape.hashCode()
        result = 31 * result + pressedShape.hashCode()
        return result
    }
}

/**
 * Ported from androidx.wear.compose.material3.TextButtonDefaults (`material3/TextButton.kt:150-620`).
 */
object TextButtonDefaults {

    /** `ShapeTokens.CornerFull` (`:152-153`), a pill at this button's aspect, and not theme-read. */
    val shape: Shape = ShapeTokens.CornerFull

    /** `MaterialTheme.shapes.small` (`:156-157`), the corner it rounds down to while pressed. */
    val pressedShape: Shape
        @Tunable get() = MaterialTheme.shapes.small

    /** `screenHeightFraction(SMALL_VERTICAL_CONTENT_PADDING_FRACTION)` (`:168-169`, `Padding.kt:73`),
     * as in [IconButtonDefaults]. */
    val minimumVerticalListContentPadding: Dp
        @Tunable get() = screenHeightFraction(SMALL_VERTICAL_CONTENT_PADDING_FRACTION)

    @Tunable
    fun shapes(): TextButtonShapes = TextButtonShapes(shape = shape)

    @Tunable
    fun shapes(shape: Shape?): TextButtonShapes =
        TextButtonShapes(shape = shape ?: this.shape)

    @Tunable
    fun animatedShapes(): TextButtonShapes =
        TextButtonShapes(shape = shape, pressedShape = pressedShape)

    @Tunable
    fun animatedShapes(shape: Shape? = null, pressedShape: Shape? = null): TextButtonShapes =
        TextButtonShapes(
            shape = shape ?: this.shape,
            pressedShape = pressedShape ?: this.pressedShape,
        )

    /** The plain, transparent-container set ([TextButton]). */
    @Tunable
    fun textButtonColors(): TextButtonColors =
        MaterialTheme.colorScheme.defaultTextButtonColors()

    @Tunable
    fun textButtonColors(
        contentColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
    ): TextButtonColors =
        MaterialTheme.colorScheme.defaultTextButtonColors().copy(
            containerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = disabledContentColor,
        )

    /** The set for a coloured container. */
    @Tunable
    fun filledTextButtonColors(): TextButtonColors =
        MaterialTheme.colorScheme.defaultFilledTextButtonColors()

    @Tunable
    fun filledTextButtonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
    ): TextButtonColors =
        MaterialTheme.colorScheme.defaultFilledTextButtonColors().copy(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = disabledContainerColor,
            disabledContentColor = disabledContentColor,
        )

    /** The filled set on the variant container (`VariantContainerColor`). */
    @Tunable
    fun filledVariantTextButtonColors(): TextButtonColors =
        MaterialTheme.colorScheme.defaultFilledVariantTextButtonColors()

    @Tunable
    fun filledVariantTextButtonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
    ): TextButtonColors =
        MaterialTheme.colorScheme.defaultFilledVariantTextButtonColors().copy(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = disabledContainerColor,
            disabledContentColor = disabledContentColor,
        )

    /** The set on the tonal container. */
    @Tunable
    fun filledTonalTextButtonColors(): TextButtonColors =
        MaterialTheme.colorScheme.defaultFilledTonalTextButtonColors()

    @Tunable
    fun filledTonalTextButtonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
    ): TextButtonColors =
        MaterialTheme.colorScheme.defaultFilledTonalTextButtonColors().copy(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = disabledContainerColor,
            disabledContentColor = disabledContentColor,
        )

    /** The outlined set: transparent container, meant to be paired with an
     *  [IconButtonDefaults.outlinedButtonBorder]. */
    @Tunable
    fun outlinedTextButtonColors(): TextButtonColors =
        MaterialTheme.colorScheme.defaultOutlinedTextButtonColors()

    @Tunable
    fun outlinedTextButtonColors(
        contentColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
    ): TextButtonColors =
        MaterialTheme.colorScheme.defaultOutlinedTextButtonColors().copy(
            containerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = disabledContentColor,
        )

    /** `TextButtonTokens.ContainerSmallSize`. */
    val SmallButtonSize: Dp = TextButtonTokens.ContainerSmallSize

    /** `TextButtonTokens.ContainerDefaultSize`. */
    val DefaultButtonSize: Dp = TextButtonTokens.ContainerDefaultSize

    /** `TextButtonTokens.ContainerLargeSize`. */
    val LargeButtonSize: Dp = TextButtonTokens.ContainerLargeSize

    /** `MaterialTheme.typography.labelMedium` (`:434-435`), the style for a [SmallButtonSize]. */
    val smallButtonTextStyle: TextStyle
        @Tunable get() = MaterialTheme.typography.labelMedium

    /**
     * `MaterialTheme.typography.labelMedium` (`:438-439`), the style for a [DefaultButtonSize] - and
     * the same role [TextButtonTokens.ContentFont] names, which is the style [TextButton] provides
     * around its content (`:137`, upstream reads the token directly).
     */
    val defaultButtonTextStyle: TextStyle
        @Tunable get() = MaterialTheme.typography.labelMedium

    /** `MaterialTheme.typography.labelLarge` (`:442-443`), the style for a [LargeButtonSize]. */
    val largeButtonTextStyle: TextStyle
        @Tunable get() = MaterialTheme.typography.labelLarge
}

/**
 * The token-backed sets, named after upstream's private `ColorScheme.default*TextButtonColors`
 * extensions (`material3/TextButton.kt:745-880`) and private here for the same reason.
 */
private fun ColorScheme.defaultTextButtonColors(): TextButtonColors = TextButtonColors(
    containerColor = Color.Transparent,
    contentColor = TextButtonTokens.ContentColor.resolve(this),
    disabledContainerColor = Color.Transparent,
    disabledContentColor = TextButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(TextButtonTokens.DisabledContentOpacity),
)

private fun ColorScheme.defaultFilledTextButtonColors(): TextButtonColors = TextButtonColors(
    containerColor = FilledTextButtonTokens.ContainerColor.resolve(this),
    contentColor = FilledTextButtonTokens.ContentColor.resolve(this),
    disabledContainerColor = FilledTextButtonTokens.DisabledContainerColor.resolve(this)
        .toDisabledColor(FilledTextButtonTokens.DisabledContainerOpacity),
    disabledContentColor = FilledTextButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(FilledTextButtonTokens.DisabledContentOpacity),
)

private fun ColorScheme.defaultFilledVariantTextButtonColors(): TextButtonColors = TextButtonColors(
    containerColor = FilledTextButtonTokens.VariantContainerColor.resolve(this),
    contentColor = FilledTextButtonTokens.VariantContentColor.resolve(this),
    disabledContainerColor = FilledTextButtonTokens.DisabledContainerColor.resolve(this)
        .toDisabledColor(FilledTextButtonTokens.DisabledContainerOpacity),
    disabledContentColor = FilledTextButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(FilledTextButtonTokens.DisabledContentOpacity),
)

private fun ColorScheme.defaultFilledTonalTextButtonColors(): TextButtonColors = TextButtonColors(
    containerColor = FilledTonalTextButtonTokens.ContainerColor.resolve(this),
    contentColor = FilledTonalTextButtonTokens.ContentColor.resolve(this),
    disabledContainerColor = FilledTonalTextButtonTokens.DisabledContainerColor.resolve(this)
        .toDisabledColor(FilledTonalTextButtonTokens.DisabledContainerOpacity),
    disabledContentColor = FilledTonalTextButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(FilledTonalTextButtonTokens.DisabledContentOpacity),
)

private fun ColorScheme.defaultOutlinedTextButtonColors(): TextButtonColors = TextButtonColors(
    containerColor = Color.Transparent,
    contentColor = OutlinedTextButtonTokens.ContentColor.resolve(this),
    disabledContainerColor = Color.Transparent,
    disabledContentColor = OutlinedTextButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(OutlinedTextButtonTokens.DisabledContentOpacity),
)
