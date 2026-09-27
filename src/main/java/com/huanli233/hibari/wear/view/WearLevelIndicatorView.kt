package com.huanli233.hibari.wear.view

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.huanli233.hibari.animation.CubicBezierEasing
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.util.lerp
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Draws what `DrawScope.drawCurvedIndicator` and `drawCurvedIndicatorSegment` do in
 * androidx.wear.compose.material3's `ScrollIndicator.kt`, for a level indicator: three arcs along
 * the screen-edge circle - the unused sweep before the level, the level itself, the unused sweep
 * after it - each padded by a gap that turns a too-narrow segment into a shrinking, fading dot.
 *
 * The maths is copied from upstream and left in Float degrees of the display circle, so the arc
 * rides on a circle whose diameter is `configuration.screenWidthDp`, exactly like the Compose
 * version's `LocalConfiguration`:
 *  - `gapPadding = pixelsHeightToDegrees(stroke + gapHeight, usableRadius)`, with
 *    `pixelsHeightToDegrees(h, r) = 2 * asin(h / 2 / r)` in degrees, and
 *    `sweepDegrees = pixelsHeightToDegrees(indicatorHeight, usableRadius) + gapPadding`.
 *  - `usableRadius = diameter / 2 - paddingHorizontal` and `arcRadius = usableRadius - stroke / 2`,
 *    the latter equal to half the arc's bounding side.
 *  - top arc = `sweepDegrees * indicatorStart`, mid (level) arc = `sweepDegrees * sizeFraction`,
 *    bottom arc = `sweepDegrees * (1 - sizeFraction - indicatorStart)`, drawn from
 *    `startAngleOffset - sweepDegrees / 2` clockwise, with `startAngleOffset` 0 on the right edge
 *    and 180 on the left one.
 *
 * Differences from upstream, all of them in the plumbing `LevelIndicator` never exercises:
 *  - `IndicatorState` is inlined: `positionFraction` is the constant 1f
 *    (`FractionPositionStateAdapter`), `jiggleAmount` is always 0f, and the reverse-direction
 *    inversion that goes with `rsbSide = false` is kept in full.
 *  - The scroll-only `DisplayState` throttle and the overscroll shrink of `sizeFraction` are
 *    dropped: neither is reachable from a level indicator.
 *  - Compose picks the fill or stroke style per call through `DrawStyle`; a `Paint` carries one
 *    style, so the dot gets its own filled paint (a stroked `drawCircle` would be a ring).
 *  - This view has no intrinsic size: the box upstream forces with `Modifier.size(size())` arrives
 *    as the layout params that `LevelIndicator` writes, and only `onDraw` reads the geometry.
 *  - `Color.Unspecified` has no upstream counterpart and simply skips a segment.
 *
 * The segment maths is a deliberate private duplicate of the routine [WearCircularProgressView]
 * carries, not a shared helper: [WearCircularProgressView] belongs to another port, and the two
 * maths genuinely differ - a progress arc derives its gap from a third of the stroke, an indicator
 * arc from the fixed 3.dp `gapHeight` between neighbours.
 */
class WearLevelIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE
        it.strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.FILL }
    private val rect = RectF()

    private var fractionAnimator: ValueAnimator? = null
    private var drawnFraction = 0f
    private var fractionSeeded = false

    /** `IndicatorState.sizeFraction`: how much of the sweep the indicator colour covers. */
    var sizeFraction: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            animateFractionTo(value)
        }

    /** `IndicatorImpl.color`: the level itself. */
    var indicatorColor: Color = Color.Unspecified
        set(value) {
            field = value
            invalidate()
        }

    /** `IndicatorImpl.background`: the unused part of the sweep. */
    var trackColor: Color = Color.Unspecified
        set(value) {
            field = value
            invalidate()
        }

    /** `IndicatorImpl.indicatorWidth`, which for a level indicator is its stroke width. */
    var indicatorWidth: Dp = 6.dp
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * `IndicatorImpl.indicatorHeight`: the height the [com.huanli233.hibari.wear.LevelIndicator]
     * sweep's chord reaches, derived from `sweepAngle` upstream.
     */
    var indicatorHeight: Dp = 0.dp
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** `IndicatorImpl.paddingHorizontal`: how far the arc sits inside the display circle. */
    var paddingHorizontal: Dp = 2.dp
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** `IndicatorImpl.reverseDirection`, applied on top of the edge the indicator rides on. */
    var reverseDirection: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val screenWidthDp = resources.configuration.screenWidthDp
        val diameterPx = density * screenWidthDp
        val indicatorWidthPx = density * indicatorWidth.value
        val indicatorHeightPx = density * indicatorHeight.value
        val paddingHorizontalPx = density * paddingHorizontal.value
        val gapHeightPx = density * GapHeightDp

        val usableRadius = diameterPx / 2f - paddingHorizontalPx
        val arcRadius = usableRadius - indicatorWidthPx / 2f
        if (usableRadius <= 0f || arcRadius <= 0f) return

        val gapPadding = pixelsHeightToDegrees(indicatorWidthPx + gapHeightPx, usableRadius)
        val sweepDegrees = pixelsHeightToDegrees(indicatorHeightPx, usableRadius) + gapPadding

        // `rsbSide = false` for a level indicator, so in Ltr the level rides on the left edge;
        // `LocalLayoutDirection` is the view's own resolved direction here.
        val indicatorOnTheRight = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val actualReverseDirection =
            if (!indicatorOnTheRight) !reverseDirection else reverseDirection

        // position = 0 is the indicator aligned at the top|start of its area, 1 at the bottom|end.
        val indicatorPosition =
            if (actualReverseDirection) 1f - PositionFraction else PositionFraction
        val indicatorStart = indicatorPosition * (1f - drawnFraction)

        // Size for the arcs and the arcs' top-left position, straight out of drawCurvedIndicator.
        val arcSide = diameterPx - 2f * paddingHorizontalPx - indicatorWidthPx
        val edgeOffset =
            if (indicatorOnTheRight) width - diameterPx + paddingHorizontalPx else paddingHorizontalPx
        val arcTopLeftX = indicatorWidthPx / 2f + edgeOffset
        val arcTopLeftY = (height - diameterPx) / 2f + paddingHorizontalPx + indicatorWidthPx / 2f

        val startAngleOffset = if (indicatorOnTheRight) 0f else 180f

        // Calculate start and sweep angles for top, medium and bottom arcs
        val startTopArc = startAngleOffset - sweepDegrees / 2
        val sweepTopArc = sweepDegrees * indicatorStart
        val startMidArc = startTopArc + sweepTopArc
        val sweepMidArc = sweepDegrees * drawnFraction
        val startBottomArc = startMidArc + sweepMidArc
        val sweepBottomArc = sweepDegrees * (1f - drawnFraction - indicatorStart)

        // Draw top arc (unselected/background)
        drawLevelSegment(
            canvas = canvas,
            startAngle = startTopArc,
            sweep = max(sweepTopArc, 0f),
            color = trackColor,
            radius = arcRadius,
            arcTopLeftX = arcTopLeftX,
            arcTopLeftY = arcTopLeftY,
            arcSide = arcSide,
            indicatorWidthPx = indicatorWidthPx,
            gapSweep = gapPadding,
        )
        // Draw mid arc (selected/thumb)
        drawLevelSegment(
            canvas = canvas,
            startAngle = startMidArc,
            sweep = max(sweepMidArc, 0f),
            color = indicatorColor,
            radius = arcRadius,
            arcTopLeftX = arcTopLeftX,
            arcTopLeftY = arcTopLeftY,
            arcSide = arcSide,
            indicatorWidthPx = indicatorWidthPx,
            gapSweep = gapPadding,
        )
        // Draw bottom arc (unselected/background)
        drawLevelSegment(
            canvas = canvas,
            startAngle = startBottomArc,
            sweep = max(sweepBottomArc, 0f),
            color = trackColor,
            radius = arcRadius,
            arcTopLeftX = arcTopLeftX,
            arcTopLeftY = arcTopLeftY,
            arcSide = arcSide,
            indicatorWidthPx = indicatorWidthPx,
            gapSweep = gapPadding,
        )
    }

    /** One stroked arc, or, when [sweep] is no wider than the [gapSweep] that pads it, a faded dot. */
    private fun drawLevelSegment(
        canvas: Canvas,
        startAngle: Float,
        sweep: Float,
        color: Color,
        radius: Float,
        arcTopLeftX: Float,
        arcTopLeftY: Float,
        arcSide: Float,
        indicatorWidthPx: Float,
        gapSweep: Float,
    ) {
        if (color.isUnspecified) return

        if (sweep <= gapSweep) {
            // Draw a small indicator.
            val angle = (startAngle + sweep / 2f).toLevelRadians()
            val indicatorRadiusFraction = sweep / gapSweep // inverseLerp(0f, gapSweep, sweep)
            val indicatorRadius = lerp(0f, indicatorWidthPx / 2, indicatorRadiusFraction)
            dotPaint.color = color.copy(alpha = color.alpha * indicatorRadiusFraction).toArgb()
            canvas.drawCircle(
                arcTopLeftX + radius + radius * cos(angle),
                arcTopLeftY + radius + radius * sin(angle),
                indicatorRadius,
                dotPaint,
            )
        } else {
            // Draw indicator arc.
            arcPaint.color = color.toArgb()
            arcPaint.strokeWidth = indicatorWidthPx
            rect.set(
                arcTopLeftX,
                arcTopLeftY,
                arcTopLeftX + arcSide,
                arcTopLeftY + arcSide,
            )
            canvas.drawArc(
                rect,
                startAngle + gapSweep / 2,
                max(sweep - gapSweep, 0f),
                false,
                arcPaint,
            )
        }
    }

    /**
     * `ScrollIndicatorDefaults.PositionAnimationSpec` - `tween(500, CubicBezierEasing(0f, 0f, 0f,
     * 1f))` - run through a [ValueAnimator], because upstream animates the same two fractions
     * (`positionFraction` is constant here, so only `sizeFraction` ever moves). The first value
     * lands as-is: upstream's `skipFirstPositionAnimation` snaps before there is anything to
     * animate from.
     */
    private fun animateFractionTo(target: Float) {
        fractionAnimator?.cancel()
        fractionAnimator = null
        if (!fractionSeeded) {
            fractionSeeded = true
            drawnFraction = target
            invalidate()
            return
        }
        if (drawnFraction == target) return
        fractionAnimator = ValueAnimator.ofFloat(drawnFraction, target).apply {
            // setDuration/setInterpolator are called rather than assigned: ValueAnimator's
            // duration setter returns the animator in the current android.jar, which a property
            // assignment cannot rely on.
            setDuration(PositionAnimationMillis)
            setInterpolator(PositionInterpolator)
            addUpdateListener {
                drawnFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** `pixelsHeightToDegrees` from ScrollIndicator.kt. */
    private fun pixelsHeightToDegrees(heightInPixels: Float, radius: Float): Float =
        2 * asin(heightInPixels / 2 / radius).toLevelDegrees()

    /** `Float.toRadians()` from material3's Angular.kt, kept private to this class's maths. */
    private fun Float.toLevelRadians(): Float = this * PI.toFloat() / 180f

    /** `Float.toDegrees()` from material3's Angular.kt, kept private to this class's maths. */
    private fun Float.toLevelDegrees(): Float = this * 180f / PI.toFloat()

    override fun onDetachedFromWindow() {
        fractionAnimator?.cancel()
        fractionAnimator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        /** `ScrollIndicatorDefaults.gapHeight`, the gap padded between two neighbouring arcs. */
        const val GapHeightDp = 3f

        /** `FractionPositionStateAdapter.positionFraction`: the level always starts at one end. */
        const val PositionFraction = 1f

        const val PositionAnimationMillis = 500L

        val PositionEasing = CubicBezierEasing(0f, 0f, 0f, 1f)

        val PositionInterpolator = TimeInterpolator { PositionEasing.transform(it) }
    }
}
