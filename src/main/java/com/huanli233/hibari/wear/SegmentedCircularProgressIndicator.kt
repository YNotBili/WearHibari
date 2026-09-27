package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.lerp
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import com.huanli233.hibari.wear.view.WearSegmentedCircularProgressView

/**
 * Ported from androidx.wear.compose.material3.SegmentedCircularProgressIndicator, both the
 * determinate `progress` overload and the binary `segmentValue` overload.
 *
 * Differences from upstream, all in the render mechanism rather than the drawing:
 *  - `progress: () -> Float` becomes a plain [Float]: the module's [CircularProgressIndicator] port
 *    already made that swap, and a retune is what re-reads the value here.
 *  - `segmentValue: (segmentIndex: Int) -> Boolean` is kept, but the component evaluates it for every
 *    segment at tune time and hands the view a `List<Boolean>`. Upstream restarts its reveal
 *    animation when the *lambda identity* changes (`LaunchedEffect(segmentValue)`); here it restarts
 *    when the evaluated mask changes, which is what a caller means but not the same trigger.
 *  - `clearAndSetSemantics {}` is dropped: Hibari has no semantics layer.
 *  - Upstream's `Spacer(modifier.fillMaxSize())` has no equivalent constraint system to fill, so an
 *    unsized view measures itself to the screen diameter minus `2 x edgePadding`, which is what the
 *    upstream sample (`fillMaxSize()` plus `CircularProgressIndicatorDefaults.FullScreenPadding`)
 *    renders as. A `Modifier.size(...)` from the caller wins, as upstream.
 *
 * Upstream keeps this component's constants on `CircularProgressIndicatorDefaults`, which is not
 * ported in this module; they live on [SegmentedCircularProgressIndicatorDefaults] instead.
 */

/**
 * Upstream's `ProgressIndicatorColors` as the segmented indicator uses it.
 *
 * The [com.huanli233.hibari.wear.ProgressIndicatorColors] already in this module carries only the four
 * non-overflow entries and is not this port's to widen, and without the overflow colours
 * `allowProgressOverflow` cannot be rendered at all — so the six fields upstream has are repeated
 * here. Every brush upstream uses is a solid colour, hence [Color] rather than `Brush`.
 */
data class SegmentedCircularProgressColors(
    val indicatorColor: Color,
    val trackColor: Color,
    val overflowTrackColor: Color,
    val disabledIndicatorColor: Color,
    val disabledTrackColor: Color,
    val disabledOverflowTrackColor: Color,
) {
    /** `ProgressIndicatorColors.indicatorBrush(enabled)`. */
    fun indicatorColorFor(enabled: Boolean): Color =
        if (enabled) indicatorColor else disabledIndicatorColor

    /** `ProgressIndicatorColors.trackBrush(enabled)`. */
    fun trackColorFor(enabled: Boolean): Color = if (enabled) trackColor else disabledTrackColor

    /**
     * `ProgressIndicatorColors.overflowTrackBrush(enabled, fraction)`. Upstream only blends when both
     * brushes are `SolidColor`, which is the only shape a Hibari [Color] can have.
     */
    fun overflowTrackColorFor(enabled: Boolean, fraction: Float): Color = when {
        !enabled -> disabledOverflowTrackColor
        fraction < 1f -> lerp(indicatorColor, overflowTrackColor, fraction)
        else -> overflowTrackColor
    }
}

/** One immutable frame of the segmented indicator's state; equality drives attribute diffing. */
data class SegmentedCircularProgressSpec(
    val segmentCount: Int,
    val progress: Float,
    /** Non-null selects the binary overload: segment `i` is lit when `segmentMask[i]`. */
    val segmentMask: List<Boolean>?,
    val allowProgressOverflow: Boolean,
    val enabled: Boolean,
    val colors: SegmentedCircularProgressColors,
    val strokeWidth: Dp,
    val gapSize: Dp,
    val startAngle: Float,
    val endAngle: Float,
)

/**
 * Material Design segmented circular progress indicator: a [CircularProgressIndicator] divided into
 * [segmentCount] equal segments, with the progress spread across all of them.
 *
 * @param segmentCount number of equal segments; upstream declares this `@IntRange(from = 1)`, and
 *   values below 1 are coerced here rather than dividing by zero.
 * @param progress 0..1 completion; above 1 it wraps when [allowProgressOverflow], else it is coerced.
 *   Progress changes are animated.
 * @param allowProgressOverflow values larger than 1 wrap around and paint the remaining segments with
 *   [SegmentedCircularProgressColors.overflowTrackColor].
 * @param startAngle arc start in degrees, clockwise from 3 o'clock; 270 is the top of the screen.
 * @param endAngle arc end in degrees, clockwise from 3 o'clock; defaults to [startAngle].
 * @param colors null resolves [SegmentedCircularProgressIndicatorDefaults.colors]. A nullable
 *   parameter rather than a `= Defaults.colors()` default because a @Tunable call in a default
 *   expression is hoisted out of the tunable scope.
 * @param strokeWidth null resolves [SegmentedCircularProgressIndicatorDefaults.largeStrokeWidth].
 * @param gapSize size of the gap between segments; null resolves
 *   [SegmentedCircularProgressIndicatorDefaults.calculateRecommendedGapSize] from the stroke width.
 */
@Tunable
fun SegmentedCircularProgressIndicator(
    segmentCount: Int,
    progress: Float,
    modifier: Modifier = Modifier,
    allowProgressOverflow: Boolean = false,
    startAngle: Float = SegmentedCircularProgressIndicatorDefaults.StartAngle,
    endAngle: Float = startAngle,
    colors: SegmentedCircularProgressColors? = null,
    strokeWidth: Dp? = null,
    gapSize: Dp? = null,
    enabled: Boolean = true,
) {
    val stroke = strokeWidth ?: SegmentedCircularProgressIndicatorDefaults.largeStrokeWidth()
    Node(
        modifier = modifier
            .viewClass(WearSegmentedCircularProgressView::class.java)
            .segmentedCircularProgress(
                SegmentedCircularProgressSpec(
                    segmentCount = segmentCount.coerceAtLeast(1),
                    progress = progress,
                    segmentMask = null,
                    allowProgressOverflow = allowProgressOverflow,
                    enabled = enabled,
                    colors = colors ?: SegmentedCircularProgressIndicatorDefaults.colors(),
                    strokeWidth = stroke,
                    gapSize = gapSize
                        ?: SegmentedCircularProgressIndicatorDefaults.calculateRecommendedGapSize(stroke),
                    startAngle = startAngle,
                    endAngle = endAngle,
                )
            )
    )
}

/**
 * Material Design segmented circular progress indicator with each segment individually marked as
 * completed, such as activity for intervals within a longer period.
 *
 * Every parameter but [segmentValue] is as in the determinate overload, except that this one has no
 * progress overflow: upstream lights whole segments, so there is no partial sweep to colour.
 *
 * @param segmentValue returns whether segment `segmentIndex` is drawn in the indicator colour; called
 *   for every index in `0 until segmentCount` at tune time.
 */
@Tunable
fun SegmentedCircularProgressIndicator(
    segmentCount: Int,
    segmentValue: (segmentIndex: Int) -> Boolean,
    modifier: Modifier = Modifier,
    startAngle: Float = SegmentedCircularProgressIndicatorDefaults.StartAngle,
    endAngle: Float = startAngle,
    colors: SegmentedCircularProgressColors? = null,
    strokeWidth: Dp? = null,
    gapSize: Dp? = null,
    enabled: Boolean = true,
) {
    val count = segmentCount.coerceAtLeast(1)
    val stroke = strokeWidth ?: SegmentedCircularProgressIndicatorDefaults.largeStrokeWidth()
    val mask = List(count) { segmentValue(it) }
    Node(
        modifier = modifier
            .viewClass(WearSegmentedCircularProgressView::class.java)
            .segmentedCircularProgress(
                SegmentedCircularProgressSpec(
                    segmentCount = count,
                    // Unused on the binary path; the view animates its own reveal fraction instead.
                    progress = 0f,
                    segmentMask = mask,
                    allowProgressOverflow = false,
                    enabled = enabled,
                    colors = colors ?: SegmentedCircularProgressIndicatorDefaults.colors(),
                    strokeWidth = stroke,
                    gapSize = gapSize
                        ?: SegmentedCircularProgressIndicatorDefaults.calculateRecommendedGapSize(stroke),
                    startAngle = startAngle,
                    endAngle = endAngle,
                )
            )
    )
}

/** Contains default values for [SegmentedCircularProgressIndicator]. */
object SegmentedCircularProgressIndicatorDefaults {
    /** Upstream's `CircularProgressIndicatorDefaults.StartAngle`: the top of the screen. */
    const val StartAngle: Float = 270f

    /** Upstream's `CircularProgressIndicatorDefaults.largeStrokeWidth` picks by screen size. */
    val LargeStrokeWidth: Dp = 12.dp

    val SmallStrokeWidth: Dp = 8.dp

    /** `ProgressIndicatorDefaults.OverflowTrackColorAlpha`. */
    private const val OverflowTrackColorAlpha = 0.6f

    /** `CircularProgressIndicatorDefaults.largeStrokeWidth`: 8.dp under 225.dp of screen width. */
    @Tunable
    fun largeStrokeWidth(): Dp =
        if (WearScreen.isSmallScreen(currentContext)) SmallStrokeWidth else LargeStrokeWidth

    /** `CircularProgressIndicatorDefaults.calculateRecommendedGapSize`. */
    fun calculateRecommendedGapSize(strokeWidth: Dp): Dp = strokeWidth / 3f

    @Tunable
    fun colors(): SegmentedCircularProgressColors {
        val scheme = MaterialTheme.colorScheme
        val primary = ColorSchemeKeyTokens.Primary.resolve(scheme)
        val overflow = primary.copy(alpha = OverflowTrackColorAlpha)
        val onSurface = ColorSchemeKeyTokens.OnSurface.resolve(scheme)
        return SegmentedCircularProgressColors(
            indicatorColor = primary,
            trackColor = ColorSchemeKeyTokens.SurfaceContainer.resolve(scheme),
            overflowTrackColor = overflow,
            disabledIndicatorColor = onSurface.toDisabledColor(ColorScheme.DisabledContentAlpha),
            disabledTrackColor = onSurface.toDisabledColor(ColorScheme.DisabledContainerAlpha),
            disabledOverflowTrackColor =
                overflow.toDisabledColor(ColorScheme.DisabledContainerAlpha),
        )
    }

    /** Unspecified entries fall back to [colors], as upstream's `copy(indicatorColor = …)` does. */
    @Tunable
    fun colors(
        indicatorColor: Color = Color.Unspecified,
        trackColor: Color = Color.Unspecified,
        overflowTrackColor: Color = Color.Unspecified,
        disabledIndicatorColor: Color = Color.Unspecified,
        disabledTrackColor: Color = Color.Unspecified,
        disabledOverflowTrackColor: Color = Color.Unspecified,
    ): SegmentedCircularProgressColors {
        val defaults = colors()
        return defaults.copy(
            indicatorColor = indicatorColor.takeOrElse { defaults.indicatorColor },
            trackColor = trackColor.takeOrElse { defaults.trackColor },
            overflowTrackColor = overflowTrackColor.takeOrElse { defaults.overflowTrackColor },
            disabledIndicatorColor =
                disabledIndicatorColor.takeOrElse { defaults.disabledIndicatorColor },
            disabledTrackColor = disabledTrackColor.takeOrElse { defaults.disabledTrackColor },
            disabledOverflowTrackColor =
                disabledOverflowTrackColor.takeOrElse { defaults.disabledOverflowTrackColor },
        )
    }
}

/**
 * The whole state goes on as one attribute so a retune that changes nothing but the animation target
 * still diffs as a single value; [WearSegmentedCircularProgressView.updateSpec] decides which of them
 * has to be animated.
 */
private fun Modifier.segmentedCircularProgress(
    spec: SegmentedCircularProgressSpec,
): Modifier = this.thenViewAttribute<WearSegmentedCircularProgressView, SegmentedCircularProgressSpec>(
    uniqueKey,
    spec,
) {
    updateSpec(it)
}
