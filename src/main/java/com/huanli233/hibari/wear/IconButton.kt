package com.huanli233.hibari.wear

import android.view.Gravity
import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.foundation.attributes.size
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.attributes.clickable
import com.huanli233.hibari.wear.tokens.FilledIconButtonTokens
import com.huanli233.hibari.wear.tokens.FilledTonalIconButtonTokens
import com.huanli233.hibari.wear.tokens.IconButtonTokens
import com.huanli233.hibari.wear.tokens.OutlinedButtonTokens
import com.huanli233.hibari.wear.tokens.OutlinedIconButtonTokens
import com.huanli233.hibari.wear.tokens.ShapeTokens

/**
 * Ported from androidx.wear.compose.material3.IconButton, FilledIconButton,
 * FilledTonalIconButton and OutlinedIconButton (`material3/IconButton.kt:69-377`), which all funnel
 * into one `IconButtonImpl` (`:378-414`) and then into `RoundButton`
 * (`material3/RoundButton.kt:11-42`) - a `Box` that is clipped, clickable, bordered and painted with
 * one colour. [ContainerSpec] is that same four-layer stack here.
 *
 * # Not ported, and why
 *
 *  - `onLongClick` / `onLongClickLabel` (`:106-107`): upstream reaches them through
 *    `combinedClickable`, and Hibari's [clickable] has no long-click channel.
 *  - `interactionSource` (`:110`): it is the ripple and pressed-state carrier, and this module has
 *    no indication system (see [MaterialTheme]). What upstream drives through it - the pressed shape
 *    morph - is *not* dropped with it: [IconButtonShapes.pressedShape] rides into
 *    [ContainerSpec.pressedShape], which [ContainerDrawable] interpolates on the view's pressed
 *    state, so the squircle-on-press still happens. What is dropped is the press *curve*: upstream
 *    runs the morph on `fastSpatialSpec().faster(200f)` in and `slowSpatialSpec()` out, and this
 *    path uses the one 100/200 ms state fade the colours already use.
 *  - `Modifier.minimumInteractiveComponentSize()` (`:119`): a no-op for all four composables, each
 *    of which pins itself to [IconButtonDefaults.DefaultButtonSize] - 52.dp, above the 48.dp floor
 *    that modifier enforces.
 *  - `semantics { role = Role.Button }` (`RoundButton.kt:29`): no semantics layer.
 *
 * @param shapes Defaults to `null` and resolves in the body: `IconButtonDefaults.shapes()` reads
 *   `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable` `$default`
 *   method that cannot.
 * @param colors Defaults to `null` for the same reason, per the variant's own factory.
 */
@Tunable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shapes: IconButtonShapes? = null,
    colors: IconButtonColors? = null,
    border: BorderStroke? = null,
    content: @Tunable BoxScope.() -> Unit,
) {
    iconButton(
        onClick,
        modifier,
        enabled,
        shapes ?: IconButtonDefaults.shapes(),
        colors ?: IconButtonDefaults.iconButtonColors(),
        border,
        content,
    )
}

/** An [IconButton] with a coloured container, from [FilledIconButtonTokens]. */
@Tunable
fun FilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shapes: IconButtonShapes? = null,
    colors: IconButtonColors? = null,
    border: BorderStroke? = null,
    content: @Tunable BoxScope.() -> Unit,
) {
    iconButton(
        onClick,
        modifier,
        enabled,
        shapes ?: IconButtonDefaults.shapes(),
        colors ?: IconButtonDefaults.filledIconButtonColors(),
        border,
        content,
    )
}

/** An [IconButton] on the tonal surface, from [FilledTonalIconButtonTokens]. */
@Tunable
fun FilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shapes: IconButtonShapes? = null,
    colors: IconButtonColors? = null,
    border: BorderStroke? = null,
    content: @Tunable BoxScope.() -> Unit,
) {
    iconButton(
        onClick,
        modifier,
        enabled,
        shapes ?: IconButtonDefaults.shapes(),
        colors ?: IconButtonDefaults.filledTonalIconButtonColors(),
        border,
        content,
    )
}

/**
 * An [IconButton] with a transparent container and a visible outline.
 *
 * Upstream's default for [border] is `ButtonDefaults.outlinedButtonBorder(enabled)`
 * (`material3/IconButton.kt:352`), which resolves through `MaterialTheme` - so it is `null` and
 * resolved in the body here like the other defaults.
 */
@Tunable
fun OutlinedIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shapes: IconButtonShapes? = null,
    colors: IconButtonColors? = null,
    border: BorderStroke? = null,
    content: @Tunable BoxScope.() -> Unit,
) {
    iconButton(
        onClick,
        modifier,
        enabled,
        shapes ?: IconButtonDefaults.shapes(),
        colors ?: IconButtonDefaults.outlinedIconButtonColors(),
        border ?: IconButtonDefaults.outlinedButtonBorder(enabled),
        content,
    )
}

@Tunable
private fun iconButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    shapes: IconButtonShapes,
    colors: IconButtonColors,
    border: BorderStroke?,
    content: @Tunable BoxScope.() -> Unit,
) {
    val scope = content
    Box(
        modifier = Modifier
            // Upstream appends its own size to the caller's chain
            // (`material3/IconButton.kt:120-122`: `modifier.minimumInteractiveComponentSize()
            // .size(IconButtonDefaults.DefaultButtonSize)`), and in Compose that inner `size` is
            // coerced by whatever the caller already fixed, so a caller's `Modifier.size` outside
            // wins. Here a size attribute writes straight into `ViewGroup.LayoutParams`, and the
            // renderer applies a flattened chain outer-to-inner
            // (`Modifier.flattenToList()` → `Renderer.applyAttributes` →
            // `LayoutAttribute.applyTo`/`updateLayoutParams`), so the *last* write wins: to get the
            // same precedence the default has to sit first and the caller's chain after it.
            .size(DpSize(IconButtonDefaults.DefaultButtonSize, IconButtonDefaults.DefaultButtonSize))
            .then(modifier)
            .container(
                ContainerSpec(
                    shape = shapes.shape,
                    containerColor = colors.containerColor(enabled),
                    border = border,
                    pressedShape = shapes.pressedShape.takeIf { it != shapes.shape },
                    disabledContainerColor = colors.disabledContainerColor,
                ),
            )
            .clickable(enabled, onClick),
        content = {
            // `Box(contentAlignment = Alignment.Center)` (`material3/RoundButton.kt:67`). A Views
            // `FrameLayout` has no container-level gravity at all - `javap` on android-37 lists only
            // `setForegroundGravity` - so a child that names no `layout_gravity` is left at
            // start|top; the centring therefore rides on a wrap_content box's own child gravity, as
            // it does elsewhere in this module (see [TextToggleButton]).
            Box(modifier = Modifier.gravity(Gravity.CENTER)) {
                provideContentColor(colors.contentColor(enabled)) { scope() }
            }
        },
    )
}

/**
 * Ported from androidx.wear.compose.material3.IconButtonColors (`material3/IconButton.kt:781-840`).
 *
 * Upstream animates between the two pairs through `animateColorAsState` on `interactionSource`; the
 * disabled pair reaches [ContainerDrawable] as a drawable state instead, so the pixels match and the
 * transition is the shared 100/200 ms state fade.
 */
class IconButtonColors(
    val containerColor: Color,
    val contentColor: Color,
    val disabledContainerColor: Color,
    val disabledContentColor: Color,
) {

    /** Upstream's `copy` runs each argument through `takeOrElse`, so an [Color.Unspecified] keeps. */
    fun copy(
        containerColor: Color = this.containerColor,
        contentColor: Color = this.contentColor,
        disabledContainerColor: Color = this.disabledContainerColor,
        disabledContentColor: Color = this.disabledContentColor,
    ): IconButtonColors = IconButtonColors(
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
        if (other == null || other !is IconButtonColors) return false
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

    override fun toString(): String =
        "IconButtonColors(containerColor=$containerColor, contentColor=$contentColor, " +
            "disabledContainerColor=$disabledContainerColor, " +
            "disabledContentColor=$disabledContentColor)"
}

/**
 * Ported from androidx.wear.compose.material3.IconButtonShapes (`material3/IconButton.kt:845-880`):
 * the shape pair a button morphs between while pressed, [pressedShape] defaulting to [shape] so a
 * plain [IconButton] never morphs.
 */
class IconButtonShapes(
    val shape: Shape,
    val pressedShape: Shape = shape,
) {

    /** Upstream's `copy` takes nullable arguments so either corner can be left alone. */
    fun copy(
        shape: Shape? = this.shape,
        pressedShape: Shape? = this.pressedShape,
    ): IconButtonShapes = IconButtonShapes(
        shape = shape ?: this.shape,
        pressedShape = pressedShape ?: this.pressedShape,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is IconButtonShapes) return false
        return shape == other.shape && pressedShape == other.pressedShape
    }

    override fun hashCode(): Int {
        var result = shape.hashCode()
        result = 31 * result + pressedShape.hashCode()
        return result
    }
}

/**
 * Ported from androidx.wear.compose.material3.IconButtonDefaults (`material3/IconButton.kt:416-744`).
 */
object IconButtonDefaults {

    /**
     * Upstream's `ShapeTokens.CornerFull` (`:418-419`) - a full circle, and deliberately not a value
     * read through `MaterialTheme.shapes`.
     */
    val shape: Shape = ShapeTokens.CornerFull

    /** `MaterialTheme.shapes.small` (`:422-423`), the corner an icon button rounds *down* to. */
    val pressedShape: Shape
        @Tunable get() = MaterialTheme.shapes.small

    /** Recommended alpha for disabled image content: upstream's `DisabledContentAlpha`. */
    val DisabledImageOpacity: Float = ColorScheme.DisabledContentAlpha

    /**
     * `screenHeightFraction(0.13f)` (`material3/IconButton.kt:434-435`, `Padding.kt:73`): the
     * content padding a list item should ask for when this button sits at the top or bottom edge of
     * a [com.huanli233.hibari.wear.lazy.TransformingLazyColumn]. Upstream reads the screen height
     * from a density scope; the same number is in the configuration here.
     */
    val minimumVerticalListContentPadding: Dp
        @Tunable get() = screenHeightFraction(SMALL_VERTICAL_CONTENT_PADDING_FRACTION)

    /** The static shapes: [shape] on both ends, so nothing morphs. */
    @Tunable
    fun shapes(): IconButtonShapes = IconButtonShapes(shape = shape)

    /** [shapes] with a caller's own outline. */
    @Tunable
    fun shapes(shape: Shape?): IconButtonShapes =
        IconButtonShapes(shape = shape ?: this.shape)

    /** The shapes that morph to [pressedShape] while pressed. */
    @Tunable
    fun animatedShapes(): IconButtonShapes =
        IconButtonShapes(shape = shape, pressedShape = pressedShape)

    /** [animatedShapes] with either end overridden; `null` keeps the default, as upstream does. */
    @Tunable
    fun animatedShapes(shape: Shape? = null, pressedShape: Shape? = null): IconButtonShapes =
        IconButtonShapes(
            shape = shape ?: this.shape,
            pressedShape = pressedShape ?: this.pressedShape,
        )

    /**
     * Recommended icon size for a button of [buttonSize] (`material3/IconButton.kt:499-506`): the
     * large icon from [LargeButtonSize] up, otherwise half the button, never below [SmallIconSize].
     */
    fun iconSizeFor(buttonSize: Dp): Dp =
        if (buttonSize >= LargeButtonSize) {
            LargeIconSize
        } else {
            val half = Dp(buttonSize.value / 2f)
            if (half > SmallIconSize) half else SmallIconSize
        }

    /** `ButtonDefaults.outlinedButtonBorder(enabled)` (`material3/Button.kt:1766-1774`). */
    @Tunable
    fun outlinedButtonBorder(enabled: Boolean): BorderStroke = BorderStroke(
        OutlinedButtonTokens.ContainerBorderWidth,
        if (enabled) {
            OutlinedButtonTokens.ContainerBorderColor.resolve(MaterialTheme.colorScheme)
        } else {
            OutlinedButtonTokens.DisabledContainerBorderColor
                .resolve(MaterialTheme.colorScheme)
                .toDisabledColor(OutlinedButtonTokens.DisabledContainerBorderOpacity)
        },
    )

    @Tunable
    fun iconButtonColors(): IconButtonColors =
        MaterialTheme.colorScheme.defaultIconButtonColors()

    @Tunable
    fun filledIconButtonColors(): IconButtonColors =
        MaterialTheme.colorScheme.defaultFilledIconButtonColors()

    @Tunable
    fun filledTonalIconButtonColors(): IconButtonColors =
        MaterialTheme.colorScheme.defaultFilledTonalIconButtonColors()

    @Tunable
    fun outlinedIconButtonColors(): IconButtonColors =
        MaterialTheme.colorScheme.defaultOutlinedIconButtonColors()

    /** `IconButtonTokens.IconSmallSize`. */
    val SmallIconSize: Dp = IconButtonTokens.IconSmallSize

    /** `IconButtonTokens.IconDefaultSize`. */
    val DefaultIconSize: Dp = IconButtonTokens.IconDefaultSize

    /** `IconButtonTokens.IconLargeSize`. */
    val LargeIconSize: Dp = IconButtonTokens.IconLargeSize

    /** `IconButtonTokens.ContainerExtraSmallSize`. */
    val ExtraSmallButtonSize: Dp = IconButtonTokens.ContainerExtraSmallSize

    /** `IconButtonTokens.ContainerSmallSize`. */
    val SmallButtonSize: Dp = IconButtonTokens.ContainerSmallSize

    /** `IconButtonTokens.ContainerDefaultSize`, the size all four composables pin themselves to. */
    val DefaultButtonSize: Dp = IconButtonTokens.ContainerDefaultSize

    /** `IconButtonTokens.ContainerLargeSize`. */
    val LargeButtonSize: Dp = IconButtonTokens.ContainerLargeSize
}

/**
 * The four token-backed colour sets, each named after upstream's private
 * `ColorScheme.default*IconButtonColors` extension (`material3/IconButton.kt:700-880`). They are
 * file-private here exactly as they are library-private there.
 */
private fun ColorScheme.defaultIconButtonColors(): IconButtonColors = IconButtonColors(
    containerColor = Color.Transparent,
    contentColor = IconButtonTokens.ContentColor.resolve(this),
    disabledContainerColor = Color.Transparent,
    disabledContentColor = IconButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(IconButtonTokens.DisabledContentOpacity),
)

private fun ColorScheme.defaultFilledIconButtonColors(): IconButtonColors = IconButtonColors(
    containerColor = FilledIconButtonTokens.ContainerColor.resolve(this),
    contentColor = FilledIconButtonTokens.ContentColor.resolve(this),
    disabledContainerColor = FilledIconButtonTokens.DisabledContainerColor.resolve(this)
        .toDisabledColor(FilledIconButtonTokens.DisabledContainerOpacity),
    disabledContentColor = FilledIconButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(FilledIconButtonTokens.DisabledContentOpacity),
)

private fun ColorScheme.defaultFilledTonalIconButtonColors(): IconButtonColors = IconButtonColors(
    containerColor = FilledTonalIconButtonTokens.ContainerColor.resolve(this),
    contentColor = FilledTonalIconButtonTokens.ContentColor.resolve(this),
    disabledContainerColor = FilledTonalIconButtonTokens.DisabledContainerColor.resolve(this)
        .toDisabledColor(FilledTonalIconButtonTokens.DisabledContainerOpacity),
    disabledContentColor = FilledTonalIconButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(FilledTonalIconButtonTokens.DisabledContentOpacity),
)

private fun ColorScheme.defaultOutlinedIconButtonColors(): IconButtonColors = IconButtonColors(
    containerColor = Color.Transparent,
    contentColor = OutlinedIconButtonTokens.ContentColor.resolve(this),
    disabledContainerColor = Color.Transparent,
    disabledContentColor = OutlinedIconButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(OutlinedIconButtonTokens.DisabledContentOpacity),
)
