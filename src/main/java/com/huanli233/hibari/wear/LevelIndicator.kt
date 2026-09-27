package com.huanli233.hibari.wear

import androidx.annotation.FloatRange
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.attributes.size
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import com.huanli233.hibari.wear.view.WearLevelIndicatorView
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ported from androidx.wear.compose.material3.LevelIndicator.
 *
 * Creates a level indicator for screens that control a setting such as volume with either the
 * rotating side button or a rotating bezel.
 *
 * The upstream composable delegates to the shared `IndicatorImpl` that `ScrollIndicator` also uses,
 * through a `FractionPositionStateAdapter` that pins `positionFraction` to 1f and leaves
 * `jiggleAmount` at 0f. Only those two facts matter for a level indicator, so the adapter and the
 * `IndicatorState` interface are not ported; the sweep/gap/segment maths they feed lives in
 * [WearLevelIndicatorView] instead. See that class for the dropped scroll-only branches
 * (`DisplayState`'s throttled position, the overscroll shrink, `jiggleAmount`).
 *
 * Differences from upstream:
 *  - `value: () -> Float` becomes `value: Float`, coerced into `0f..1f` here exactly as upstream's
 *    adapter lambda does. Hibari re-tunes on state change, so reading the lambda at tune time and
 *    at draw time are the same thing, and a captured lambda would never compare equal across tunes
 *    which would re-apply the drawing attribute on every pass. `rememberUpdatedState` has no role
 *    once the value is a plain argument.
 *  - Upstream sizes its `Box` with `Modifier.size(size())`; that lands here as
 *    `Modifier.size(DpSize)` writing exact layout params, so the arc geometry is derived from this
 *    view's own edges plus the display diameter. Like upstream, the caller is responsible for
 *    putting those edges where they belong - a full-screen parent with the view pushed to one side
 *    (`Alignment.CenterEnd` upstream, `Modifier.gravity(...)` here).
 *
 * @param colors Defaults to `null` and resolves in the body: [LevelIndicatorDefaults.colors] reads
 *   `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable` `$default`
 *   method that cannot.
 */
@Tunable
fun LevelIndicator(
    value: Float,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: LevelIndicatorColors? = null,
    strokeWidth: Dp = LevelIndicatorDefaults.StrokeWidth,
    @FloatRange(from = 0.0, to = 360.0) sweepAngle: Float = LevelIndicatorDefaults.SweepAngle,
    reverseDirection: Boolean = false,
) {
    val resolved = colors ?: LevelIndicatorDefaults.colors()
    val screenWidthDp = currentContext.resources.configuration.screenWidthDp
    val paddingHorizontal = LevelIndicatorDefaults.edgePadding
    // `screenWidthDp / 2` is an Int division upstream (a raw configuration dp value), so an odd
    // screen width truncates here too; levelIndicatorSizeDp below halves the same number as a Dp.
    val radius = screenWidthDp / 2 - paddingHorizontal.value - strokeWidth.value / 2
    // Calculate indicator height based on a triangle of the top half of the sweep angle
    val indicatorHeight = 2f * sin((0.5f * sweepAngle).levelToRadians()) * radius

    Node(
        modifier = modifier
            .size(
                levelIndicatorSizeDp(
                    screenWidthDp = screenWidthDp,
                    indicatorHeight = indicatorHeight.dp,
                    indicatorWidth = strokeWidth,
                    paddingHorizontal = paddingHorizontal,
                )
            )
            .viewClass(WearLevelIndicatorView::class.java)
            .levelIndicator(
                LevelIndicatorSpec(
                    sizeFraction = value.coerceIn(0f, 1f),
                    indicatorColor = resolved.indicatorColor(enabled),
                    trackColor = resolved.trackColor(enabled),
                    indicatorWidth = strokeWidth,
                    indicatorHeight = indicatorHeight.dp,
                    paddingHorizontal = paddingHorizontal,
                    reverseDirection = reverseDirection,
                )
            )
    )
}

/**
 * Ported from androidx.wear.compose.material3.StepperLevelIndicator: the same [LevelIndicator],
 * with [value] mapped out of [valueRange] first.
 *
 * @param colors `null` resolves [LevelIndicatorDefaults.colors]; see [LevelIndicator] for why.
 */
@Tunable
fun StepperLevelIndicator(
    value: Float,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    colors: LevelIndicatorColors? = null,
    strokeWidth: Dp = LevelIndicatorDefaults.StrokeWidth,
    @FloatRange(from = 0.0, to = 360.0) sweepAngle: Float = LevelIndicatorDefaults.SweepAngle,
    reverseDirection: Boolean = false,
) {
    val resolved = colors ?: LevelIndicatorDefaults.colors()
    LevelIndicator(
        value = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start),
        modifier = modifier,
        enabled = enabled,
        colors = resolved,
        strokeWidth = strokeWidth,
        sweepAngle = sweepAngle,
        reverseDirection = reverseDirection,
    )
}

/**
 * Ported from androidx.wear.compose.material3.StepperLevelIndicator for an [IntProgression]. The
 * progression is only used for its ends, so a `step` larger than 1 does not quantise the arc; that
 * is upstream's behaviour too.
 *
 * @param colors `null` resolves [LevelIndicatorDefaults.colors]; see [LevelIndicator] for why.
 */
@Tunable
fun StepperLevelIndicator(
    value: Int,
    valueProgression: IntProgression,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: LevelIndicatorColors? = null,
    strokeWidth: Dp = LevelIndicatorDefaults.StrokeWidth,
    @FloatRange(from = 0.0, to = 360.0) sweepAngle: Float = LevelIndicatorDefaults.SweepAngle,
    reverseDirection: Boolean = false,
) {
    val resolved = colors ?: LevelIndicatorDefaults.colors()
    LevelIndicator(
        value = (value - valueProgression.first) /
            (valueProgression.last - valueProgression.first).toFloat(),
        modifier = modifier,
        enabled = enabled,
        colors = resolved,
        strokeWidth = strokeWidth,
        sweepAngle = sweepAngle,
        reverseDirection = reverseDirection,
    )
}

/** Contains the default values used for [LevelIndicator]. */
object LevelIndicatorDefaults {
    /**
     * The sweep angle for the curved [LevelIndicator], measured up to the centers of the stroke
     * caps. The default value of 72 degrees equates to 20% of the circumference, i.e. 360/5.
     */
    const val SweepAngle: Float = 72f

    /** The default stroke width for the indicator and track strokes */
    val StrokeWidth: Dp = 6.dp

    /**
     * `LevelIndicatorDefaults.edgePadding`, i.e. `PaddingDefaults.edgePadding`, which material3
     * keeps as a flat 2.dp (material3/Padding.kt:63). It is unconditional upstream, so no screen
     * size or shape changes it.
     */
    internal val edgePadding: Dp = 2.dp

    /**
     * Creates a [LevelIndicatorColors] that represents the default colors used in a
     * [LevelIndicator].
     */
    @Tunable
    fun colors(): LevelIndicatorColors = MaterialTheme.colorScheme.defaultLevelIndicatorColors

    /**
     * Creates a [LevelIndicatorColors] with modified colors used in [LevelIndicator]. Unspecified
     * arguments keep the theme default, which is what upstream's `copy` does.
     */
    @Tunable
    fun colors(
        indicatorColor: Color = Color.Unspecified,
        trackColor: Color = Color.Unspecified,
        disabledIndicatorColor: Color = Color.Unspecified,
        disabledTrackColor: Color = Color.Unspecified,
    ): LevelIndicatorColors = MaterialTheme.colorScheme.defaultLevelIndicatorColors.copy(
        indicatorColor = indicatorColor,
        trackColor = trackColor,
        disabledIndicatorColor = disabledIndicatorColor,
        disabledTrackColor = disabledTrackColor,
    )
}

/** `LevelIndicatorDefaults.defaultLevelIndicatorColors`, without the per-composition cache slot. */
internal val ColorScheme.defaultLevelIndicatorColors: LevelIndicatorColors
    @Tunable get() = LevelIndicatorColors(
        indicatorColor = ColorSchemeKeyTokens.SecondaryDim.resolve(this),
        trackColor = ColorSchemeKeyTokens.SurfaceContainer.resolve(this),
        disabledIndicatorColor = ColorSchemeKeyTokens.OnSurface.resolve(this)
            .toDisabledColor(ColorScheme.DisabledContentAlpha),
        disabledTrackColor = ColorSchemeKeyTokens.OnSurface.resolve(this)
            .toDisabledColor(ColorScheme.DisabledContainerAlpha),
    )

/**
 * Ported from androidx.wear.compose.material3.LevelIndicatorColors.
 *
 * A class rather than a data class because upstream's `copy` treats [Color.Unspecified] as "keep
 * the current value"; the generated `copy` would not. `equals`/`hashCode` are ported verbatim, and
 * they are what let the resolved colours sit inside [LevelIndicatorSpec] unchanged across a retune.
 */
class LevelIndicatorColors(
    val indicatorColor: Color,
    val trackColor: Color,
    val disabledIndicatorColor: Color,
    val disabledTrackColor: Color,
) {
    /**
     * Returns a copy of this LevelIndicatorColors optionally overriding some of the values.
     */
    fun copy(
        indicatorColor: Color = this.indicatorColor,
        trackColor: Color = this.trackColor,
        disabledIndicatorColor: Color = this.disabledIndicatorColor,
        disabledTrackColor: Color = this.disabledTrackColor,
    ): LevelIndicatorColors = LevelIndicatorColors(
        indicatorColor = indicatorColor.takeOrElse { this.indicatorColor },
        trackColor = trackColor.takeOrElse { this.trackColor },
        disabledIndicatorColor = disabledIndicatorColor.takeOrElse { this.disabledIndicatorColor },
        disabledTrackColor = disabledTrackColor.takeOrElse { this.disabledTrackColor },
    )

    /** Represents the indicator color, depending on [enabled]. */
    internal fun indicatorColor(enabled: Boolean): Color =
        if (enabled) indicatorColor else disabledIndicatorColor

    /** Represents the track color, depending on [enabled]. */
    internal fun trackColor(enabled: Boolean): Color =
        if (enabled) trackColor else disabledTrackColor

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is LevelIndicatorColors) return false

        if (indicatorColor != other.indicatorColor) return false
        if (trackColor != other.trackColor) return false
        if (disabledIndicatorColor != other.disabledIndicatorColor) return false
        if (disabledTrackColor != other.disabledTrackColor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = indicatorColor.hashCode()
        result = 31 * result + trackColor.hashCode()
        result = 31 * result + disabledIndicatorColor.hashCode()
        result = 31 * result + disabledTrackColor.hashCode()
        return result
    }
}

/**
 * What [WearLevelIndicatorView] needs, as one comparable value so a retune that changes nothing
 * leaves the view alone. [indicatorWidth] is upstream's `indicatorWidth`, which for a level
 * indicator is the caller's `strokeWidth`.
 */
data class LevelIndicatorSpec(
    val sizeFraction: Float,
    val indicatorColor: Color,
    val trackColor: Color,
    val indicatorWidth: Dp,
    val indicatorHeight: Dp,
    val paddingHorizontal: Dp,
    val reverseDirection: Boolean,
)

private fun Modifier.levelIndicator(spec: LevelIndicatorSpec): Modifier =
    this.thenViewAttribute<WearLevelIndicatorView, LevelIndicatorSpec>(uniqueKey, spec) {
        sizeFraction = it.sizeFraction
        indicatorColor = it.indicatorColor
        trackColor = it.trackColor
        indicatorWidth = it.indicatorWidth
        indicatorHeight = it.indicatorHeight
        paddingHorizontal = it.paddingHorizontal
        reverseDirection = it.reverseDirection
    }

/**
 * `IndicatorImpl`'s `size` lambda. The box is only as wide as the chord of the arc it draws, and as
 * tall as one stroke plus the indicator.
 */
private fun levelIndicatorSizeDp(
    screenWidthDp: Int,
    indicatorHeight: Dp,
    indicatorWidth: Dp,
    paddingHorizontal: Dp,
): DpSize {
    // radius is the distance from the center of the container to the arc we draw the indicator on
    // (the center of the arc, which is indicatorWidth wide). Here `screenWidthDp` reaches the maths
    // as a Dp, so unlike LevelIndicator's own radius this halves in Float.
    val radius = screenWidthDp.dp.value / 2 - paddingHorizontal.value - indicatorWidth.value / 2
    val width =
        // The sqrt is the size of the projection on the x axis of line between center of
        // the container and the point where we start the arc.
        // The coerceAtLeast is needed while initializing since containerSize.width is 0
        radius - sqrt((levelSqr(radius) - levelSqr(indicatorHeight.value / 2)).coerceAtLeast(0f)) +
            paddingHorizontal.value +
            indicatorWidth.value

    val height = indicatorHeight.value + indicatorWidth.value

    return DpSize(width.dp, height.dp)
}

/** `sqr` from androidx.wear.compose.materialcore. */
private fun levelSqr(x: Float): Float = x * x

/** `Float.toRadians()` from androidx.wear.compose.material3.Angular.kt. */
private fun Float.levelToRadians(): Float = this * PI.toFloat() / 180f
