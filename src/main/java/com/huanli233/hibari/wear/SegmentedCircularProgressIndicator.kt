package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.view.WearSegmentedCircularProgressView

/**
 * Ported from androidx.wear.compose.material3.SegmentedCircularProgressIndicator, both the
 * determinate `progress` overload (`SegmentedCircularProgressIndicator.kt:93-104`) and the binary
 * `segmentValue` overload (`:280-290`), with upstream's parameter order.
 *
 * Every default this component has upstream comes from the objects it actually reads -
 * [CircularProgressIndicatorDefaults.StartAngle] (`:98-99`, `:284-285`), its
 * `largeStrokeWidth` (`:101`, `:287`) and `calculateRecommendedGapSize` (`:102`, `:288`), plus
 * [ProgressIndicatorDefaults.colors] (`:100`, `:286`). There is no `SegmentedCircularProgressIndicatorDefaults`
 * upstream and none here either, and the colours are the shared [ProgressIndicatorColors] with its
 * overflow entries, which is what `SegmentedCircularProgressIndicatorImpl` reaches for when it paints
 * the wrapped-around track.
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
 * @param segmentCount number of equal segments; upstream declares this `@IntRange(from = 1)`, and
 *   values below 1 are coerced here rather than dividing by zero.
 * @param progress 0..1 completion; above 1 it wraps when [allowProgressOverflow], else it is coerced.
 *   Progress changes are animated.
 * @param allowProgressOverflow values larger than 1 wrap around and paint the remaining segments with
 *   [ProgressIndicatorColors.overflowTrackColor].
 * @param startAngle arc start in degrees, clockwise from 3 o'clock; 270 is the top of the screen.
 * @param endAngle arc end in degrees, clockwise from 3 o'clock; defaults to [startAngle].
 * @param colors null resolves [ProgressIndicatorDefaults.colors]. A nullable parameter rather than
 *   `= ProgressIndicatorDefaults.colors()` because that factory reads `MaterialTheme`, and a @Tunable
 *   call in a default expression is hoisted out of the tunable scope. [strokeWidth] and [gapSize] are
 *   nullable for the same reason - upstream's defaults there read the screen size and each other.
 * @param strokeWidth null resolves [CircularProgressIndicatorDefaults.largeStrokeWidth].
 * @param gapSize size of the gap between segments; null resolves
 *   [CircularProgressIndicatorDefaults.calculateRecommendedGapSize] from the stroke width.
 */
@Tunable
fun SegmentedCircularProgressIndicator(
    segmentCount: Int,
    progress: Float,
    modifier: Modifier = Modifier,
    allowProgressOverflow: Boolean = false,
    startAngle: Float = CircularProgressIndicatorDefaults.StartAngle,
    endAngle: Float = startAngle,
    colors: ProgressIndicatorColors? = null,
    strokeWidth: Dp? = null,
    gapSize: Dp? = null,
    enabled: Boolean = true,
) {
    val stroke = strokeWidth ?: CircularProgressIndicatorDefaults.largeStrokeWidth
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
                    colors = colors ?: ProgressIndicatorDefaults.colors(),
                    strokeWidth = stroke,
                    gapSize = gapSize
                        ?: CircularProgressIndicatorDefaults.calculateRecommendedGapSize(stroke),
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
 * progress overflow: upstream lights whole segments (`:280-290`), so there is no partial sweep to
 * colour.
 *
 * @param segmentValue returns whether segment `segmentIndex` is drawn in the indicator colour; called
 *   for every index in `0 until segmentCount` at tune time.
 */
@Tunable
fun SegmentedCircularProgressIndicator(
    segmentCount: Int,
    segmentValue: (segmentIndex: Int) -> Boolean,
    modifier: Modifier = Modifier,
    startAngle: Float = CircularProgressIndicatorDefaults.StartAngle,
    endAngle: Float = startAngle,
    colors: ProgressIndicatorColors? = null,
    strokeWidth: Dp? = null,
    gapSize: Dp? = null,
    enabled: Boolean = true,
) {
    val count = segmentCount.coerceAtLeast(1)
    val stroke = strokeWidth ?: CircularProgressIndicatorDefaults.largeStrokeWidth
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
                    colors = colors ?: ProgressIndicatorDefaults.colors(),
                    strokeWidth = stroke,
                    gapSize = gapSize
                        ?: CircularProgressIndicatorDefaults.calculateRecommendedGapSize(stroke),
                    startAngle = startAngle,
                    endAngle = endAngle,
                )
            )
    )
}

/** One immutable frame of the segmented indicator's state; equality drives attribute diffing. */
data class SegmentedCircularProgressSpec(
    val segmentCount: Int,
    val progress: Float,
    /** Non-null selects the binary overload: segment `i` is lit when `segmentMask[i]`. */
    val segmentMask: List<Boolean>?,
    val allowProgressOverflow: Boolean,
    val enabled: Boolean,
    val colors: ProgressIndicatorColors,
    val strokeWidth: Dp,
    val gapSize: Dp,
    val startAngle: Float,
    val endAngle: Float,
)

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
