package com.huanli233.hibari.wear

import android.view.Gravity
import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.foundation.attributes.size
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.CircleShape
import com.huanli233.hibari.ui.geometry.CornerBasedShape
import com.huanli233.hibari.ui.geometry.RoundedCornerShape
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.wear.attributes.clickable
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.tokens.ShapeTokens
import com.huanli233.hibari.wear.tokens.TextToggleButtonTokens

/**
 * Ported from androidx.wear.compose.material3.TextToggleButton
 * (`material3/TextToggleButton.kt:89-136`), which draws through `materialcore.ToggleButton`
 * (`materialcore/ToggleButton.kt:91-127`) - a centred `Box`, sized, clipped, `toggleable`, bordered
 * and painted with one colour - the same stack [IconToggleButton] sits on, with a text slot instead
 * of an icon one and one difference that matters: it scopes a **text style** as well as a colour
 * (`material3/TextToggleButton.kt:113-119`), so [content] inherits
 * [TextToggleButtonDefaults.textStyle] unless the caller restyles it.
 *
 * # Not ported, and why
 *
 *  - `interactionSource` (`:96`) and `ripple`: Hibari has no interaction or indication system. What
 *    upstream drives *through* it survives: the pressed shape morph rides on the view's real
 *    `state_pressed` inside [ContainerDrawable], so
 *    [TextToggleButtonDefaults.animatedShapes] still rounds down under a finger.
 *  - The **checked** half of `animateToggleButtonShape` (`:101-111`, and
 *    `rememberAnimatedToggleRoundedCornerShape` in
 *    `material3/AnimatedToggleRoundedCornerShape.kt:80-141`) is resolved rather than animated:
 *    [checked] is a tuner argument here, so the resting shape and both colours are picked for it at
 *    the call site and the switch is instant. A drawable-state route for this morph is deliberately
 *    not built: it would need `android.R.attr.state_checked` in the drawable state, and no view in
 *    this module is `Checkable` ([clickable] writes `isEnabled`/`isClickable`/`isFocusable`, and
 *    `state_pressed` comes from the framework's own press handling), so it would never arrive. The
 *    price of answering `checked` in composition is that the corner change is instant where upstream
 *    animates it. The press morph is unaffected because `state_pressed` is real.
 *  - `Modifier.minimumInteractiveComponentSize()` (`:123`): a 48.dp floor under a button pinned to
 *    [TextToggleButtonDefaults.Size] (52.dp), so a no-op - as in [IconButton] and
 *    [IconToggleButton].
 *  - `semantics { role = Role.Checkbox }` (`materialcore/ToggleButton.kt:110`): no semantics layer.
 *  - `COLOR_ANIMATION_SPEC` (`material3/TextToggleButton.kt:605-606`, `tween(MotionTokens.DurationMedium1,
 *    0, EasingStandardDecelerate)`): with the colours resolved above there is no colour animation to
 *    configure. A press still fades on the shared 100/200 ms state layer.
 *  - `Modifier.touchTargetAwareSize`, which the KDoc at `:49-55` recommends for applying [Size],
 *    [LargeSize] and [ExtraLargeSize]: it is a `wear.foundation` modifier and not part of this file.
 *    A caller's own `Modifier.size` does still win, as `toggleButtonSize` promises
 *    (`materialcore/ToggleButton.kt:78-79`); see the chain below for how.
 *
 * @param checked Boolean flag indicating whether this toggle button is currently checked.
 * @param onCheckedChange Callback to be invoked when this toggle button is clicked.
 * @param modifier Modifier to be applied to the toggle button.
 * @param enabled Controls the enabled state of the toggle button. When `false`, this toggle button
 *   will not be clickable.
 * @param colors [TextToggleButtonColors] that will be used to resolve the container and content
 *   color for this toggle button. Defaults to `null` and resolves in the body:
 *   `TextToggleButtonDefaults.colors()` reads `MaterialTheme`, and a `@Tunable` default expression is
 *   hoisted into a non-`@Tunable` `$default` method that cannot.
 * @param shapes Defines the shape for this toggle button. Defaults to a static shape based on
 *   [TextToggleButtonDefaults.shape], but animated versions are available through
 *   [TextToggleButtonDefaults.animatedShapes] and [TextToggleButtonDefaults.variantAnimatedShapes].
 *   Defaults to `null` and resolves in the body for the same reason as [colors].
 * @param border Optional [BorderStroke] for the [TextToggleButton].
 * @param content The text to be drawn inside the toggle button.
 */
@Tunable
fun TextToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: TextToggleButtonColors? = null,
    shapes: TextToggleButtonShapes? = null,
    border: BorderStroke? = null,
    content: @Tunable BoxScope.() -> Unit,
) {
    val resolvedColors = colors ?: TextToggleButtonDefaults.colors()
    val resolvedShapes = shapes ?: TextToggleButtonDefaults.shapes()
    val textStyle = TextToggleButtonDefaults.textStyle
    val scope = content
    Box(
        modifier = Modifier
            // Upstream hands the caller's modifier to `materialcore.ToggleButton` first and applies
            // `toggleButtonSize` after it (`materialcore/ToggleButton.kt:109-111`), and that argument
            // is documented as "the default size ... unless overridden by Modifier.size" (`:78-79`) -
            // so the caller's size wins. `Modifier.size` writes `ViewGroup.LayoutParams` here and the
            // renderer applies a chain outer-to-inner (`Renderer.applyAttributes`), so the default
            // has to go *first* for the same thing to happen: last write, i.e. the caller's own,
            // takes the LayoutParams.
            .size(DpSize(TextToggleButtonDefaults.Size, TextToggleButtonDefaults.Size))
            .then(modifier)
            .container(
                ContainerSpec(
                    shape = if (checked) resolvedShapes.checkedShape else resolvedShapes.uncheckedShape,
                    containerColor = resolvedColors.containerColor(enabled, checked),
                    border = border,
                    pressedShape = textToggleButtonPressedShape(resolvedShapes, checked),
                ),
            )
            .clickable(enabled) { onCheckedChange(!checked) },
        content = {
            // `Box(contentAlignment = Alignment.Center)` (materialcore/ToggleButton.kt:107). A Views
            // `FrameLayout` offers no container-level child gravity - it declares only
            // `setForegroundGravity`, and a child that names no `layout_gravity` falls back to the
            // static `DEFAULT_CHILD_GRAVITY` (start|top) - so the centring has to ride on the slot's
            // own child gravity through a wrap_content box, which is how the rest of this module
            // centres a slot (see [CompactButton]). The cost is the same one upstream's
            // `contentAlignment` does not pay: a slot that sizes itself with `matchParentSize()` now
            // measures against that wrap_content box rather than the button.
            Box(modifier = Modifier.gravity(Gravity.CENTER)) {
                provideContentColorAndStyle(
                    resolvedColors.contentColor(enabled, checked),
                    textStyle,
                ) { scope() }
            }
        },
    )
}

/**
 * The pressed shape for [checked], or `null` when it is the resting shape and has nothing to morph
 * toward - [ContainerDrawable] skips the blend on `null`, and upstream's `animateToggleButtonShape`
 * (`material3/TextToggleButton.kt:101-111`) likewise bails out when the two shapes are the same
 * instance.
 */
private fun textToggleButtonPressedShape(
    shapes: TextToggleButtonShapes,
    checked: Boolean,
): Shape? {
    val resting = if (checked) shapes.checkedShape else shapes.uncheckedShape
    val pressed = if (checked) shapes.checkedPressedShape else shapes.uncheckedPressedShape
    return pressed.takeIf { it != resting }
}

/**
 * Ported from androidx.wear.compose.material3.TextToggleButtonColors
 * (`material3/TextToggleButton.kt:426-548`), the same eight roles [IconToggleButtonColors] carries.
 *
 * Upstream's `containerColor` / `contentColor` (`:488-516`) are `internal @Composable` returning a
 * `State<Color>` from `animateSelectionColor`; here they are `internal` plain functions returning the
 * colour that animation is heading for, which is the same value and the same selection table -
 * disabled beats checked, checked beats unchecked.
 */
class TextToggleButtonColors(
    val checkedContainerColor: Color,
    val checkedContentColor: Color,
    val uncheckedContainerColor: Color,
    val uncheckedContentColor: Color,
    val disabledCheckedContainerColor: Color,
    val disabledCheckedContentColor: Color,
    val disabledUncheckedContainerColor: Color,
    val disabledUncheckedContentColor: Color,
) {

    /** Upstream's `copy` (`:456-480`) runs each argument through `takeOrElse`, so an [Color.Unspecified] keeps. */
    fun copy(
        checkedContainerColor: Color = this.checkedContainerColor,
        checkedContentColor: Color = this.checkedContentColor,
        uncheckedContainerColor: Color = this.uncheckedContainerColor,
        uncheckedContentColor: Color = this.uncheckedContentColor,
        disabledCheckedContainerColor: Color = this.disabledCheckedContainerColor,
        disabledCheckedContentColor: Color = this.disabledCheckedContentColor,
        disabledUncheckedContainerColor: Color = this.disabledUncheckedContainerColor,
        disabledUncheckedContentColor: Color = this.disabledUncheckedContentColor,
    ): TextToggleButtonColors = TextToggleButtonColors(
        checkedContainerColor = checkedContainerColor.takeOrElse { this.checkedContainerColor },
        checkedContentColor = checkedContentColor.takeOrElse { this.checkedContentColor },
        uncheckedContainerColor =
            uncheckedContainerColor.takeOrElse { this.uncheckedContainerColor },
        uncheckedContentColor = uncheckedContentColor.takeOrElse { this.uncheckedContentColor },
        disabledCheckedContainerColor =
            disabledCheckedContainerColor.takeOrElse { this.disabledCheckedContainerColor },
        disabledCheckedContentColor =
            disabledCheckedContentColor.takeOrElse { this.disabledCheckedContentColor },
        disabledUncheckedContainerColor =
            disabledUncheckedContainerColor.takeOrElse { this.disabledUncheckedContainerColor },
        disabledUncheckedContentColor =
            disabledUncheckedContentColor.takeOrElse { this.disabledUncheckedContentColor },
    )

    internal fun containerColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled -> if (checked) disabledCheckedContainerColor else disabledUncheckedContainerColor
        checked -> checkedContainerColor
        else -> uncheckedContainerColor
    }

    internal fun contentColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled -> if (checked) disabledCheckedContentColor else disabledUncheckedContentColor
        checked -> checkedContentColor
        else -> uncheckedContentColor
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null) return false
        if (this::class != other::class) return false

        other as TextToggleButtonColors

        if (checkedContainerColor != other.checkedContainerColor) return false
        if (checkedContentColor != other.checkedContentColor) return false
        if (uncheckedContainerColor != other.uncheckedContainerColor) return false
        if (uncheckedContentColor != other.uncheckedContentColor) return false
        if (disabledCheckedContainerColor != other.disabledCheckedContainerColor) return false
        if (disabledCheckedContentColor != other.disabledCheckedContentColor) return false
        if (disabledUncheckedContainerColor != other.disabledUncheckedContainerColor) return false
        if (disabledUncheckedContentColor != other.disabledUncheckedContentColor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = checkedContainerColor.hashCode()
        result = 31 * result + checkedContentColor.hashCode()
        result = 31 * result + uncheckedContainerColor.hashCode()
        result = 31 * result + uncheckedContentColor.hashCode()
        result = 31 * result + disabledCheckedContainerColor.hashCode()
        result = 31 * result + disabledCheckedContentColor.hashCode()
        result = 31 * result + disabledUncheckedContainerColor.hashCode()
        result = 31 * result + disabledUncheckedContentColor.hashCode()
        return result
    }
}

/**
 * Ported from androidx.wear.compose.material3.TextToggleButtonShapes
 * (`material3/TextToggleButton.kt:564-603`): the four corners a toggle button can hold - checked and
 * unchecked, each with its own pressed morph.
 *
 * Upstream's `animateToggleButtonShape` can only animate when all four are `RoundedCornerShape`s (or
 * when checked and unchecked are the same object), and [ContainerDrawable] interpolates the same
 * three of them that the drawable state can reach here: resting, checked, pressed.
 */
class TextToggleButtonShapes(
    val uncheckedShape: Shape,
    val checkedShape: Shape = uncheckedShape,
    val uncheckedPressedShape: Shape = uncheckedShape,
    val checkedPressedShape: Shape = uncheckedPressedShape,
) {

    /** Upstream's `copy` (`:570-581`) takes nullable arguments so any corner can be left alone. */
    fun copy(
        uncheckedShape: Shape? = this.uncheckedShape,
        checkedShape: Shape? = this.checkedShape,
        uncheckedPressedShape: Shape? = this.uncheckedPressedShape,
        checkedPressedShape: Shape? = this.checkedPressedShape,
    ): TextToggleButtonShapes = TextToggleButtonShapes(
        uncheckedShape = uncheckedShape ?: this.uncheckedShape,
        checkedShape = checkedShape ?: this.checkedShape,
        uncheckedPressedShape = uncheckedPressedShape ?: this.uncheckedPressedShape,
        checkedPressedShape = checkedPressedShape ?: this.checkedPressedShape,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is TextToggleButtonShapes) return false

        if (uncheckedShape != other.uncheckedShape) return false
        if (checkedShape != other.checkedShape) return false
        if (uncheckedPressedShape != other.uncheckedPressedShape) return false
        if (checkedPressedShape != other.checkedPressedShape) return false

        return true
    }

    override fun hashCode(): Int {
        var result = uncheckedShape.hashCode()
        result = 31 * result + checkedShape.hashCode()
        result = 31 * result + uncheckedPressedShape.hashCode()
        result = 31 * result + checkedPressedShape.hashCode()
        return result
    }
}

/**
 * Ported from androidx.wear.compose.material3.TextToggleButtonDefaults
 * (`material3/TextToggleButton.kt:138-407`), including the three cached shape sets at `:325-360` and
 * the private `ColorScheme.defaultTextToggleButtonColors` at `:362-399`.
 *
 * Upstream types `shape` as `RoundedCornerShape` and `pressedShape` / `checkedShape` as
 * `CornerBasedShape`, and the corresponding `animatedShapes` / `variantAnimatedShapes` parameters are
 * `CornerBasedShape?`; both name Compose shapes with no Hibari counterpart for the percent form, so
 * the roles here are plain [Shape]s - `ShapeTokens.CornerFull` is [CircleShape] in this module and
 * `MaterialTheme.shapes.*` hands out [CornerBasedShape]s.
 *
 * Upstream's `@Composable` value getters (`:142-151`, `:172-181`) are `@Tunable` getters here; the
 * shape factories keep upstream's parameterless / parameterised overload pairs, and `null` means
 * "keep the default" exactly as it does there.
 */
object TextToggleButtonDefaults {

    /** `ShapeTokens.CornerFull` (`:142-143`), and deliberately not read through `MaterialTheme.shapes`. */
    val shape: Shape = ShapeTokens.CornerFull

    /** `MaterialTheme.shapes.small` (`:146-147`), the corner a press rounds down to. */
    val pressedShape: Shape
        @Tunable get() = MaterialTheme.shapes.small

    /** `MaterialTheme.shapes.medium` (`:150-151`), the corner a checked button grows to. */
    val checkedShape: Shape
        @Tunable get() = MaterialTheme.shapes.medium

    /** `TextToggleButtonTokens.ContainerDefaultSize`, the size [TextToggleButton] pins itself to. */
    val Size: Dp = TextToggleButtonTokens.ContainerDefaultSize

    /** `TextToggleButtonTokens.ContainerLargeSize`. */
    val LargeSize: Dp = TextToggleButtonTokens.ContainerLargeSize

    /** `TextToggleButtonTokens.ContainerExtraLargeSize`. */
    val ExtraLargeSize: Dp = TextToggleButtonTokens.ContainerExtraLargeSize

    /** `TextToggleButtonTokens.ContentDefaultFont` (`:172-173`), which [TextToggleButton] scopes. */
    val textStyle: TextStyle
        @Tunable get() =
            MaterialTheme.typography.fromToken(TextToggleButtonTokens.ContentDefaultFont)

    /** `TextToggleButtonTokens.ContentLargeFont` (`:176-177`), for a [LargeSize] button. */
    val largeTextStyle: TextStyle
        @Tunable get() =
            MaterialTheme.typography.fromToken(TextToggleButtonTokens.ContentLargeFont)

    /** `TextToggleButtonTokens.ContentExtraLargeFont` (`:180-181`), for an [ExtraLargeSize] button. */
    val extraLargeTextStyle: TextStyle
        @Tunable get() =
            MaterialTheme.typography.fromToken(TextToggleButtonTokens.ContentExtraLargeFont)

    /** The static shapes (`:186-187`): one corner for every state, so nothing morphs. */
    @Tunable
    fun shapes(): TextToggleButtonShapes = TextToggleButtonShapes(uncheckedShape = shape)

    /** [shapes] with a caller's own resting corner (`:194-196`). */
    @Tunable
    fun shapes(shape: Shape): TextToggleButtonShapes =
        shapes().copy(uncheckedShape = shape)

    /** The shapes that morph to [pressedShape] while pressed (`:206-208`). */
    @Tunable
    fun animatedShapes(): TextToggleButtonShapes = TextToggleButtonShapes(
        uncheckedShape = shape,
        uncheckedPressedShape = pressedShape,
    )

    /**
     * [animatedShapes] with either end overridden (`:222-230`); `null` keeps the default, as upstream
     * does.
     */
    @Tunable
    fun animatedShapes(
        shape: Shape? = null,
        pressedShape: Shape? = null,
    ): TextToggleButtonShapes = animatedShapes().copy(
        uncheckedShape = shape,
        uncheckedPressedShape = pressedShape,
    )

    /**
     * The checked/unchecked/pressed trio of `:240-242`, spelled out the way
     * `defaultTextToggleButtonVariantAnimatedShapes` builds it (`:345-360`): each pressed corner is
     * derived from its own resting corner by [PressedShapeCornerSizeFraction].
     */
    @Tunable
    fun variantAnimatedShapes(): TextToggleButtonShapes = TextToggleButtonShapes(
        uncheckedShape = shape,
        checkedShape = checkedShape,
        uncheckedPressedShape =
            shape.textToggleButtonFractionalShape(PressedShapeCornerSizeFraction),
        checkedPressedShape =
            checkedShape.textToggleButtonFractionalShape(PressedShapeCornerSizeFraction),
    )

    /**
     * [variantAnimatedShapes] with either resting corner overridden (`:256-268`). Upstream derives a
     * pressed corner only from a corner the caller actually passed - `uncheckedShape?.fractional...`
     * - so a `null` here leaves that state's pressed corner at the default's, and only the resting
     * one moves.
     */
    @Tunable
    fun variantAnimatedShapes(
        uncheckedShape: Shape? = null,
        checkedShape: Shape? = null,
    ): TextToggleButtonShapes = variantAnimatedShapes().copy(
        uncheckedShape = uncheckedShape,
        checkedShape = checkedShape,
        uncheckedPressedShape = uncheckedShape?.textToggleButtonFractionalShape(
            PressedShapeCornerSizeFraction
        ),
        checkedPressedShape = checkedShape?.textToggleButtonFractionalShape(
            PressedShapeCornerSizeFraction
        ),
    )

    /** `TextToggleButtonDefaults.colors()` (`:276-278`). */
    @Tunable
    fun colors(): TextToggleButtonColors =
        MaterialTheme.colorScheme.defaultTextToggleButtonColors()

    /** Every role of [colors] overridable, [Color.Unspecified] keeping the default (`:303-323`). */
    @Tunable
    fun colors(
        checkedContainerColor: Color = Color.Unspecified,
        checkedContentColor: Color = Color.Unspecified,
        uncheckedContainerColor: Color = Color.Unspecified,
        uncheckedContentColor: Color = Color.Unspecified,
        disabledCheckedContainerColor: Color = Color.Unspecified,
        disabledCheckedContentColor: Color = Color.Unspecified,
        disabledUncheckedContainerColor: Color = Color.Unspecified,
        disabledUncheckedContentColor: Color = Color.Unspecified,
    ): TextToggleButtonColors = MaterialTheme.colorScheme.defaultTextToggleButtonColors().copy(
        checkedContainerColor = checkedContainerColor,
        checkedContentColor = checkedContentColor,
        uncheckedContainerColor = uncheckedContainerColor,
        uncheckedContentColor = uncheckedContentColor,
        disabledCheckedContainerColor = disabledCheckedContainerColor,
        disabledCheckedContentColor = disabledCheckedContentColor,
        disabledUncheckedContainerColor = disabledUncheckedContainerColor,
        disabledUncheckedContentColor = disabledUncheckedContentColor,
    )

    /**
     * Upstream's `PressedShapeCornerSizeFraction` (`:406`), the corner fraction
     * [variantAnimatedShapes] presses to.
     */
    private const val PressedShapeCornerSizeFraction: Float = 0.66f
}

/**
 * Upstream's `CornerBasedShape.fractionalRoundedCornerShape(fraction)`
 * (`material3/AnimatedCornerShape.kt:317-323`), which multiplies every corner of the receiver and is
 * applied to both resting corners by [TextToggleButtonDefaults.variantAnimatedShapes].
 *
 * `ShapeTokens.CornerFull` is a percentage corner upstream can scale and a [CircleShape] here cannot,
 * because this module's shapes carry [Dp]s; for that one case the fraction is applied to
 * [TextToggleButtonDefaults.Size], which is what `CornerSize(50% × fraction)` resolves to on the
 * square button these shapes are used on.
 */
private fun Shape.textToggleButtonFractionalShape(fraction: Float): Shape = when (this) {
    is CornerBasedShape -> CornerBasedShape(
        topStart = Dp(topStart.value * fraction),
        topEnd = Dp(topEnd.value * fraction),
        bottomEnd = Dp(bottomEnd.value * fraction),
        bottomStart = Dp(bottomStart.value * fraction),
    )
    CircleShape -> RoundedCornerShape(Dp(TextToggleButtonDefaults.Size.value * fraction / 2f))
    else -> this
}

/**
 * The token-backed colour set, named after upstream's private
 * `ColorScheme.defaultTextToggleButtonColors` (`material3/TextToggleButton.kt:362-399`) and
 * file-private here exactly as it is there.
 */
private fun ColorScheme.defaultTextToggleButtonColors(): TextToggleButtonColors =
    TextToggleButtonColors(
        checkedContainerColor = TextToggleButtonTokens.CheckedContainerColor.resolve(this),
        checkedContentColor = TextToggleButtonTokens.CheckedContentColor.resolve(this),
        uncheckedContainerColor = TextToggleButtonTokens.UncheckedContainerColor.resolve(this),
        uncheckedContentColor = TextToggleButtonTokens.UncheckedContentColor.resolve(this),
        disabledCheckedContainerColor =
            TextToggleButtonTokens.DisabledCheckedContainerColor.resolve(this)
                .toDisabledColor(TextToggleButtonTokens.DisabledCheckedContainerOpacity),
        disabledCheckedContentColor =
            TextToggleButtonTokens.DisabledCheckedContentColor.resolve(this)
                .toDisabledColor(TextToggleButtonTokens.DisabledCheckedContentOpacity),
        disabledUncheckedContainerColor =
            TextToggleButtonTokens.DisabledUncheckedContainerColor.resolve(this)
                .toDisabledColor(TextToggleButtonTokens.DisabledUncheckedContainerOpacity),
        disabledUncheckedContentColor =
            TextToggleButtonTokens.DisabledUncheckedContentColor.resolve(this)
                .toDisabledColor(TextToggleButtonTokens.DisabledUncheckedContentOpacity),
    )
