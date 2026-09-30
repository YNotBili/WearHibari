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
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.wear.attributes.clickable
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.tokens.IconToggleButtonTokens
import com.huanli233.hibari.wear.tokens.ShapeTokens

/**
 * Ported from androidx.wear.compose.material3.IconToggleButton
 * (`material3/IconToggleButton.kt:85-128`), which delegates its drawing to
 * `materialcore.ToggleButton` (`materialcore/ToggleButton.kt:89-127`) - a centred `Box`, sized,
 * clipped, `toggleable`, bordered and painted with one colour. [ContainerSpec] is that stack here,
 * and [IconButton] is its non-toggle sibling.
 *
 * Unlike the labelled rows ([SwitchButton] / [CheckboxButton] / [RadioButton]), which take a
 * `toggleControl` slot, this is the round variant: one slot, no content padding, and the whole
 * container is the tap target.
 *
 * # Not ported, and why
 *
 *  - `interactionSource` (`:92`) and `ripple`: Hibari has no interaction or indication system (see
 *    [MaterialTheme]). What upstream drives *through* it survives: the pressed shape morph rides on
 *    the view's real `state_pressed` inside [ContainerDrawable], so
 *    [IconToggleButtonDefaults.animatedShapes] still rounds down under a finger.
 *  - The **checked** half of `animateToggleButtonShape` (`:97-107`, and
 *    `rememberAnimatedToggleRoundedCornerShape` in
 *    `material3/AnimatedToggleRoundedCornerShape.kt:80-141`) is resolved rather than animated:
 *    [checked] is a tuner argument here, so `shape`/`pressedShape` and the container/content colours
 *    are picked for it at the call site and the switch is instant. A drawable-state route for this
 *    morph is deliberately not built: it would need `android.R.attr.state_checked` in the drawable
 *    state, and no view in this module is `Checkable` (`Modifier.clickable` writes
 *    `state_pressed`/`state_enabled` only), so it would never arrive. The price of answering
 *    `checked` in composition is that the corner change is instant where upstream animates it. The
 *    press morph is unaffected because `state_pressed` is real.
 *  - `Modifier.minimumInteractiveComponentSize()` (`:115`): a 48.dp floor under a button pinned to
 *    [IconToggleButtonDefaults.Size] (52.dp), so a no-op - as in [IconButton].
 *  - `semantics { role = Role.Checkbox }` (`materialcore/ToggleButton.kt:110`): no semantics layer.
 *  - `COLOR_ANIMATION_SPEC` (`:632-633`, `tween(MotionTokens.DurationMedium1, 0,
 *    EasingStandardDecelerate)`): with the colours resolved above there is no colour animation to
 *    configure. A press still fades on the shared 100/200 ms state layer.
 *
 * @param shapes Defaults to `null` and resolves in the body: `IconToggleButtonDefaults.shapes()`
 *   reads `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable`
 *   `$default` method that cannot.
 * @param colors Defaults to `null` for the same reason.
 */
@Tunable
fun IconToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconToggleButtonColors? = null,
    shapes: IconToggleButtonShapes? = null,
    border: BorderStroke? = null,
    content: @Tunable BoxScope.() -> Unit,
) {
    val resolvedColors = colors ?: IconToggleButtonDefaults.colors()
    val resolvedShapes = shapes ?: IconToggleButtonDefaults.shapes()
    val scope = content
    Box(
        modifier = Modifier
            // Upstream hands its `toggleButtonSize` to `materialcore.ToggleButton`
            // (material3/IconToggleButton.kt:112-121, materialcore/ToggleButton.kt:111), which applies
            // it *inside* the caller's chain - and there `Modifier.size` is coerced by the caller's
            // outer constraints, so a caller's own `Modifier.size` wins: the argument is documented as
            // "the default size ... unless overridden by Modifier.size" (`:78-79`). Here a size
            // attribute writes into `ViewGroup.LayoutParams` and the renderer applies a flattened
            // chain outer-to-inner (`Modifier.flattenToList()` → `Renderer.applyAttributes` →
            // `LayoutAttribute.applyTo`/`updateLayoutParams`), so the last write wins - which means the
            // default has to go first for the caller's size to keep winning.
            .size(DpSize(IconToggleButtonDefaults.Size, IconToggleButtonDefaults.Size))
            .then(modifier)
            .container(
                ContainerSpec(
                    shape = if (checked) resolvedShapes.checkedShape else resolvedShapes.uncheckedShape,
                    containerColor = resolvedColors.containerColor(enabled, checked),
                    border = border,
                    pressedShape = iconToggleButtonPressedShape(resolvedShapes, checked),
                ),
            )
            .clickable(enabled) { onCheckedChange(!checked) },
        content = {
            // `Box(contentAlignment = Alignment.Center)` (materialcore/ToggleButton.kt:107). A Views
            // `FrameLayout` has no container-level gravity at all - `javap` on android-37 lists only
            // `setForegroundGravity` - so a child that names no `layout_gravity` is left at start|top;
            // the centring therefore rides on a wrap_content box's own child gravity, as it does
            // elsewhere in this module (see [TextToggleButton]).
            Box(modifier = Modifier.gravity(Gravity.CENTER)) {
                provideContentColor(resolvedColors.contentColor(enabled, checked)) { scope() }
            }
        },
    )
}

/**
 * The pressed shape for [checked], or `null` when it is the resting shape and has nothing to morph
 * toward - [ContainerDrawable] skips the blend on `null`, and upstream's
 * `animateButtonShape` (`material3/RoundButton.kt:147`) likewise bails out when the two shapes are
 * the same instance.
 */
private fun iconToggleButtonPressedShape(
    shapes: IconToggleButtonShapes,
    checked: Boolean,
): Shape? {
    val resting = if (checked) shapes.checkedShape else shapes.uncheckedShape
    val pressed = if (checked) shapes.checkedPressedShape else shapes.uncheckedPressedShape
    return pressed.takeIf { it != resting }
}

/**
 * Ported from androidx.wear.compose.material3.IconToggleButtonColors
 * (`material3/IconToggleButton.kt:453-575`).
 *
 * Upstream's `containerColor` / `contentColor` (`:515-543`) are `internal @Composable` returning a
 * `State<Color>` from `animateSelectionColor`; here they are `internal` plain functions returning the
 * colour that animation is heading for, which is the same value and the same selection table -
 * disabled beats checked, checked beats unchecked.
 */
class IconToggleButtonColors(
    val checkedContainerColor: Color,
    val checkedContentColor: Color,
    val uncheckedContainerColor: Color,
    val uncheckedContentColor: Color,
    val disabledCheckedContainerColor: Color,
    val disabledCheckedContentColor: Color,
    val disabledUncheckedContainerColor: Color,
    val disabledUncheckedContentColor: Color,
) {

    /** Upstream's `copy` (`:483-507`) runs each argument through `takeOrElse`, so an [Color.Unspecified] keeps. */
    fun copy(
        checkedContainerColor: Color = this.checkedContainerColor,
        checkedContentColor: Color = this.checkedContentColor,
        uncheckedContainerColor: Color = this.uncheckedContainerColor,
        uncheckedContentColor: Color = this.uncheckedContentColor,
        disabledCheckedContainerColor: Color = this.disabledCheckedContainerColor,
        disabledCheckedContentColor: Color = this.disabledCheckedContentColor,
        disabledUncheckedContainerColor: Color = this.disabledUncheckedContainerColor,
        disabledUncheckedContentColor: Color = this.disabledUncheckedContentColor,
    ): IconToggleButtonColors = IconToggleButtonColors(
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

        other as IconToggleButtonColors

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
 * Ported from androidx.wear.compose.material3.IconToggleButtonShapes
 * (`material3/IconToggleButton.kt:591-630`): the four corners a toggle button can hold - checked
 * and unchecked, each with its own pressed morph.
 *
 * Upstream's `animateToggleButtonShape` (`material3/RoundButton.kt:164-208`) can only animate when
 * all four are `RoundedCornerShape`s (or when checked and unchecked are the same object), and
 * [ContainerDrawable] interpolates the same three of them that the drawable state can reach here:
 * resting, checked, pressed.
 */
class IconToggleButtonShapes(
    val uncheckedShape: Shape,
    val checkedShape: Shape = uncheckedShape,
    val uncheckedPressedShape: Shape = uncheckedShape,
    val checkedPressedShape: Shape = uncheckedPressedShape,
) {

    /** Upstream's `copy` (`:597-608`) takes nullable arguments so any corner can be left alone. */
    fun copy(
        uncheckedShape: Shape? = this.uncheckedShape,
        checkedShape: Shape? = this.checkedShape,
        uncheckedPressedShape: Shape? = this.uncheckedPressedShape,
        checkedPressedShape: Shape? = this.checkedPressedShape,
    ): IconToggleButtonShapes = IconToggleButtonShapes(
        uncheckedShape = uncheckedShape ?: this.uncheckedShape,
        checkedShape = checkedShape ?: this.checkedShape,
        uncheckedPressedShape = uncheckedPressedShape ?: this.uncheckedPressedShape,
        checkedPressedShape = checkedPressedShape ?: this.checkedPressedShape,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is IconToggleButtonShapes) return false

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
 * Ported from androidx.wear.compose.material3.IconToggleButtonDefaults
 * (`material3/IconToggleButton.kt:130-434`), including the three cached shape sets at `:352-387` and
 * the private `ColorScheme.defaultIconToggleButtonColors` at `:389-426`.
 *
 * Upstream's `shape` is typed `RoundedCornerShape` and its `pressedShape` / `checkedShape`
 * `CornerBasedShape`; both name Compose shapes with no Hibari counterpart for the percent form, so
 * the roles here are plain [Shape]s - `ShapeTokens.CornerFull` is [CircleShape] in this module and
 * `MaterialTheme.shapes.*` hands out [CornerBasedShape]s.
 */
object IconToggleButtonDefaults {

    /** `ShapeTokens.CornerFull` (`:134-135`), and deliberately not read through `MaterialTheme.shapes`. */
    val shape: Shape = ShapeTokens.CornerFull

    /** `MaterialTheme.shapes.small` (`:138-139`), the corner a press rounds down to. */
    val pressedShape: Shape
        @Tunable get() = MaterialTheme.shapes.small

    /** `MaterialTheme.shapes.medium` (`:142-143`), the corner a checked button grows to. */
    val checkedShape: Shape
        @Tunable get() = MaterialTheme.shapes.medium

    /** `IconToggleButtonTokens.IconSmallSize`. */
    val SmallIconSize: Dp = IconToggleButtonTokens.IconSmallSize

    /** `IconToggleButtonTokens.IconDefaultSize`. */
    val DefaultIconSize: Dp = IconToggleButtonTokens.IconDefaultSize

    /** `IconToggleButtonTokens.IconLargeSize`. */
    val LargeIconSize: Dp = IconToggleButtonTokens.IconLargeSize

    /** `IconToggleButtonTokens.IconExtraLargeSize`. */
    val ExtraLargeIconSize: Dp = IconToggleButtonTokens.IconExtraLargeSize

    /** `IconToggleButtonTokens.ContainerSmallSize`, 48.dp: apply it with `Modifier.size`. */
    val SmallSize: Dp = IconToggleButtonTokens.ContainerSmallSize

    /** `IconToggleButtonTokens.ContainerDefaultSize`, the size [IconToggleButton] pins itself to. */
    val Size: Dp = IconToggleButtonTokens.ContainerDefaultSize

    /** `IconToggleButtonTokens.ContainerLargeSize`. */
    val LargeSize: Dp = IconToggleButtonTokens.ContainerLargeSize

    /** `IconToggleButtonTokens.ContainerExtraLargeSize`. */
    val ExtraLargeSize: Dp = IconToggleButtonTokens.ContainerExtraLargeSize

    /**
     * Recommended icon size for a button of [buttonSize] (`:203-208`): at least
     * [LargeIconSize] from [LargeSize] up, at least [SmallIconSize] below, and in both cases never
     * less than half the button.
     */
    fun iconSizeFor(buttonSize: Dp): Dp {
        val half = Dp(buttonSize.value / 2f)
        val floor = if (buttonSize >= LargeSize) LargeIconSize else SmallIconSize
        return if (floor > half) floor else half
    }

    /** The static shapes (`:212`): one corner for every state, so nothing morphs. */
    @Tunable
    fun shapes(): IconToggleButtonShapes = IconToggleButtonShapes(uncheckedShape = shape)

    /** [shapes] with a caller's own resting corner (`:219-221`). */
    @Tunable
    fun shapes(shape: Shape?): IconToggleButtonShapes =
        IconToggleButtonShapes(uncheckedShape = shape ?: this.shape)

    /** The shapes that morph to [pressedShape] while pressed (`:232-233`). */
    @Tunable
    fun animatedShapes(): IconToggleButtonShapes = IconToggleButtonShapes(
        uncheckedShape = shape,
        uncheckedPressedShape = pressedShape,
    )

    /** [animatedShapes] with either end overridden; `null` keeps the default, as upstream does (`:248-255`). */
    @Tunable
    fun animatedShapes(
        shape: Shape? = null,
        pressedShape: Shape? = null,
    ): IconToggleButtonShapes = IconToggleButtonShapes(
        uncheckedShape = shape ?: this.shape,
        uncheckedPressedShape = pressedShape ?: this.pressedShape,
    )

    /** The checked/unchecked/pressed trio of `:266-267`, pressed corners at [PressedShapeCornerSizeFraction]. */
    @Tunable
    fun variantAnimatedShapes(): IconToggleButtonShapes = variantShapePair(shape, checkedShape)

    /**
     * [variantAnimatedShapes] with either resting corner overridden (`:282-293`); each pressed
     * corner is derived from its own resting one by [PressedShapeCornerSizeFraction].
     */
    @Tunable
    fun variantAnimatedShapes(
        uncheckedShape: Shape? = null,
        checkedShape: Shape? = null,
    ): IconToggleButtonShapes {
        val unchecked = uncheckedShape ?: shape
        val checked = checkedShape ?: this.checkedShape
        return IconToggleButtonShapes(
            uncheckedShape = unchecked,
            checkedShape = checked,
            uncheckedPressedShape =
                iconToggleButtonFractionalShape(unchecked, PressedShapeCornerSizeFraction),
            checkedPressedShape =
                iconToggleButtonFractionalShape(checked, PressedShapeCornerSizeFraction),
        )
    }

    /** `IconToggleButtonDefaults.colors()` (`:303-304`). */
    @Tunable
    fun colors(): IconToggleButtonColors =
        MaterialTheme.colorScheme.defaultIconToggleButtonColors()

    /** Every role of [colors] overridable, [Color.Unspecified] keeping the default (`:331-350`). */
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
    ): IconToggleButtonColors = MaterialTheme.colorScheme.defaultIconToggleButtonColors().copy(
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
     * Upstream's `PressedShapeCornerSizeFraction` (`:433`), the corner fraction
     * [variantAnimatedShapes] presses to.
     */
    private const val PressedShapeCornerSizeFraction: Float = 0.66f

    /**
     * The default four corners, spelled out the way `defaultVariantAnimatedShapes` caches them
     * (`:372-387`).
     */
    private fun variantShapePair(unchecked: Shape, checked: Shape): IconToggleButtonShapes =
        IconToggleButtonShapes(
            uncheckedShape = unchecked,
            checkedShape = checked,
            uncheckedPressedShape =
                iconToggleButtonFractionalShape(unchecked, PressedShapeCornerSizeFraction),
            checkedPressedShape =
                iconToggleButtonFractionalShape(checked, PressedShapeCornerSizeFraction),
        )
}

/**
 * Upstream's `CornerBasedShape.fractionalRoundedCornerShape(fraction)`
 * (`material3/AnimatedCornerShape.kt:317-323`), which multiplies every corner of the receiver and
 * is applied to both resting corners by [IconToggleButtonDefaults.variantAnimatedShapes].
 *
 * `ShapeTokens.CornerFull` is a percentage corner upstream can scale and a [CircleShape] here
 * cannot, because this module's shapes carry [Dp]s; for that one case the fraction is applied to
 * [IconToggleButtonDefaults.Size], which is what `CornerSize(50% × fraction)` resolves to on the
 * square button these shapes are used on.
 */
private fun iconToggleButtonFractionalShape(shape: Shape, fraction: Float): Shape = when (shape) {
    is CornerBasedShape -> CornerBasedShape(
        topStart = Dp(shape.topStart.value * fraction),
        topEnd = Dp(shape.topEnd.value * fraction),
        bottomEnd = Dp(shape.bottomEnd.value * fraction),
        bottomStart = Dp(shape.bottomStart.value * fraction),
    )
    CircleShape ->
        RoundedCornerShape(Dp(IconToggleButtonDefaults.Size.value * fraction / 2f))
    else -> shape
}

/**
 * The token-backed colour set, named after upstream's private
 * `ColorScheme.defaultIconToggleButtonColors` (`material3/IconToggleButton.kt:389-426`) and
 * file-private here exactly as it is there.
 */
private fun ColorScheme.defaultIconToggleButtonColors(): IconToggleButtonColors =
    IconToggleButtonColors(
        checkedContainerColor = IconToggleButtonTokens.CheckedContainerColor.resolve(this),
        checkedContentColor = IconToggleButtonTokens.CheckedContentColor.resolve(this),
        uncheckedContainerColor = IconToggleButtonTokens.UncheckedContainerColor.resolve(this),
        uncheckedContentColor = IconToggleButtonTokens.UncheckedContentColor.resolve(this),
        disabledCheckedContainerColor =
            IconToggleButtonTokens.DisabledCheckedContainerColor.resolve(this)
                .toDisabledColor(IconToggleButtonTokens.DisabledCheckedContainerOpacity),
        disabledCheckedContentColor =
            IconToggleButtonTokens.DisabledCheckedContentColor.resolve(this)
                .toDisabledColor(IconToggleButtonTokens.DisabledCheckedContentOpacity),
        disabledUncheckedContainerColor =
            IconToggleButtonTokens.DisabledUncheckedContainerColor.resolve(this)
                .toDisabledColor(IconToggleButtonTokens.DisabledUncheckedContainerOpacity),
        disabledUncheckedContentColor =
            IconToggleButtonTokens.DisabledUncheckedContentColor.resolve(this)
                .toDisabledColor(IconToggleButtonTokens.DisabledUncheckedContentOpacity),
    )
