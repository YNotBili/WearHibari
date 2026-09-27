package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.unit.times
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.view.WearArcProgressIndicatorView

/**
 * Ported from androidx.wear.compose.material3.ArcProgressIndicator — the indeterminate arc that
 * sweeps between two angles instead of around a whole circle, plus `ArcProgressIndicatorDefaults` and
 * `AngularDirection`.
 *
 * On the geometry call: this port copies upstream's maths rather than wrapping
 * `androidx.wear.widget.ArcLayout`. Upstream draws the arc itself in a `Canvas` with three
 * `drawIndicatorArc` calls, and the parts that make it look like Wear — the round caps, the
 * `(gapSize + strokeWidth)` gap measured between end caps, and a sweep narrower than the gap decaying
 * into a shrinking dot — have no ArcLayout equivalent, while its child-on-arc placement, `Arc`
 * gravity and `LayoutDirection` handling are things this component never asks for. See
 * [WearArcProgressIndicatorView] for the copied routine.
 *
 * The arc *modifiers* named in the porting brief (`Modifier.arcOffset`, `arcPadding`,
 * `arcPlacementClockwise`, `ArcPadding`) are not in the reference tree at all: `androidx.wear.compose`
 * 1.7.0 exposes no such API in source or in the resolved `compose-foundation` artifact, and the
 * `ArcPaddingValues` that does exist upstream (`foundation/CurvedPadding.kt`) belongs to `CurvedLayout`
 * and is only used by `TimeText`. Rather than invent signatures, placement stays exactly where
 * upstream puts it: the caller sizes and aligns the indicator, and
 * [ArcProgressIndicatorDefaults.recommendedIndeterminateDiameter] is the size that makes the arc sit
 * concentric with the screen.
 *
 * Otherwise the deltas are: `LocalDensity` and the `LocalConfiguration` screen height are read off the
 * view/context, and `colors` is the four-field [ProgressIndicatorColors] this module already has —
 * upstream's arc reads only `indicatorBrush` and `trackBrush` from it, never the disabled or overflow
 * entries, so nothing is lost. As in upstream this indicator has no `enabled` parameter, and being
 * indeterminate it has no `progress`.
 */

/** Class to define angular direction - Clockwise and Counter Clockwise. */
@JvmInline
value class AngularDirection internal constructor(internal val type: Int) {
    companion object {
        /** Clockwise is the standard direction for an analog clock. */
        val Clockwise: AngularDirection = AngularDirection(0)

        /** CounterClockwise is the opposite direction to Clockwise */
        val CounterClockwise: AngularDirection = AngularDirection(1)
    }
}

/** The arc indicator's immutable drawing state; equality drives attribute diffing. */
data class ArcProgressIndicatorSpec(
    val startAngle: Float,
    val endAngle: Float,
    val angularDirection: AngularDirection,
    val colors: ProgressIndicatorColors,
    val strokeWidth: Dp,
    val gapSize: Dp,
)

/**
 * Indeterminate Material Design arc progress indicator: it expresses an unspecified wait time and
 * animates indefinitely, as a variation on the circular spinner with both arc ends placeable.
 *
 * @param modifier applied to the indicator; upstream notes this is a `Canvas` whose *width* is the
 *   diameter of the circle the arc runs along, so a size modifier is what positions the arc.
 * @param startAngle the start angle of this progress indicator arc, in degrees, measured clockwise
 *   from the three o'clock position. Recommended value is
 *   [ArcProgressIndicatorDefaults.IndeterminateStartAngle].
 * @param endAngle the end angle of this progress indicator arc, in degrees, measured clockwise from
 *   the three o'clock position. Recommended value is
 *   [ArcProgressIndicatorDefaults.IndeterminateEndAngle].
 * @param angularDirection determines whether the animation runs clockwise or counter-clockwise.
 * @param colors used to resolve the indicator and track colour; null resolves
 *   [ProgressIndicatorDefaults.colors]. Nullable because a @Tunable call in a default expression is
 *   hoisted out of the tunable scope.
 * @param strokeWidth the stroke width; null resolves
 *   [ArcProgressIndicatorDefaults.IndeterminateStrokeWidth].
 * @param gapSize the size of the gap between the ends of the progress indicator and the track, not
 *   counting the stroke end caps; null resolves
 *   [ArcProgressIndicatorDefaults.calculateRecommendedGapSize] from the stroke width.
 */
@Tunable
fun ArcProgressIndicator(
    modifier: Modifier = Modifier,
    startAngle: Float = ArcProgressIndicatorDefaults.IndeterminateStartAngle,
    endAngle: Float = ArcProgressIndicatorDefaults.IndeterminateEndAngle,
    angularDirection: AngularDirection = AngularDirection.CounterClockwise,
    colors: ProgressIndicatorColors? = null,
    strokeWidth: Dp? = null,
    gapSize: Dp? = null,
) {
    val stroke = strokeWidth ?: ArcProgressIndicatorDefaults.IndeterminateStrokeWidth
    Node(
        modifier = modifier
            .viewClass(WearArcProgressIndicatorView::class.java)
            .arcProgressIndicator(
                ArcProgressIndicatorSpec(
                    startAngle = startAngle,
                    endAngle = endAngle,
                    angularDirection = angularDirection,
                    colors = colors ?: ProgressIndicatorDefaults.colors(),
                    strokeWidth = stroke,
                    gapSize = gapSize
                        ?: ArcProgressIndicatorDefaults.calculateRecommendedGapSize(stroke),
                )
            )
    )
}

/** Contains default values for [ArcProgressIndicator]. */
object ArcProgressIndicatorDefaults {
    /** The default start angle in degrees for an indeterminate arc progress indicator. */
    const val IndeterminateStartAngle: Float = 65f

    /** The default end angle in degrees for an indeterminate arc progress indicator. */
    const val IndeterminateEndAngle: Float = 115f

    /** Stroke width of the indeterminate arc progress indicator. */
    val IndeterminateStrokeWidth: Dp = 6.dp

    /**
     * Upstream's private `IndeterminateArcDiameterPercentage`: the indicator takes 81.24% of the
     * screen height, because the padding it wants below itself at the bottom of the screen is 9.38%
     * (`100 - 2 x 9.38`). Public here only because an unsized view needs it to measure itself.
     */
    const val IndeterminateArcDiameterPercentage: Float = 0.8124f

    /**
     * The recommended diameter of the indeterminate arc progress indicator, which leaves room for
     * additional content such as a message above the indicator.
     *
     * Upstream reaches this through `screenHeightDp()` on a recomposition-sensitive `LocalConfiguration`;
     * here it reads the configuration of [currentContext], so a retune is what re-evaluates it.
     */
    @Tunable
    fun recommendedIndeterminateDiameter(): Dp =
        IndeterminateArcDiameterPercentage * currentContext.resources.configuration.screenHeightDp.dp

    /**
     * Returns recommended size of the gap based on `strokeWidth`.
     *
     * The absolute value can be customized with the `gapSize` parameter.
     */
    fun calculateRecommendedGapSize(strokeWidth: Dp): Dp = strokeWidth / 3f
}

private fun Modifier.arcProgressIndicator(spec: ArcProgressIndicatorSpec): Modifier =
    this.thenViewAttribute<WearArcProgressIndicatorView, ArcProgressIndicatorSpec>(uniqueKey, spec) {
        updateSpec(it)
    }
