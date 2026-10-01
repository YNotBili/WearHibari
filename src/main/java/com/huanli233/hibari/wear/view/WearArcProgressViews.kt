package com.huanli233.hibari.wear.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.huanli233.hibari.animation.CubicBezierEasing
import com.huanli233.hibari.animation.Easing
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.wear.AngularDirection
import com.huanli233.hibari.wear.ArcProgressIndicatorDefaults
import com.huanli233.hibari.wear.ArcProgressIndicatorSpec
import com.huanli233.hibari.wear.SegmentedCircularProgressSpec
import com.huanli233.hibari.wear.WearInsets
import com.huanli233.hibari.wear.tokens.MotionDurationTokens
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Drawing views for SegmentedCircularProgressIndicator and ArcProgressIndicator, and the shared
 * segment routine every circular indicator in this module draws through.
 *
 * Upstream draws both with `DrawScope` extensions (`drawIndicatorSegment` and `drawIndicatorArc` in
 * `material3/ProgressIndicator.kt:301-348`, `:398-425`); [drawIndicatorSegment] here is that first
 * routine, the arc maths unchanged: `gapSweep = asin((stroke + gap) / (min - stroke))` doubled, and a
 * segment narrower than its own gap degrades into a shrinking, fading dot instead of disappearing. The
 * dot branch fills, so `Paint.Style` is switched per call.
 *
 * It is `internal` and shared, not a per-view copy: [WearCircularProgressView] used to carry its own
 * version of the same maths, minus upstream's `strokePadding` term and with the dot drawn in the
 * stroke style — a ring instead of the filled dot upstream shows for a small progress value. Both
 * views now call this one.
 *
 * Motion, not just rendering, lives here: upstream drives progress with an `Animatable` timed by
 * `MaterialTheme.motionScheme`. These two views cannot read that local themselves — a `Drawable`/`View`
 * is not a tunable body — so their springs are reproduced from the stiffness constants
 * `MotionScheme.standard()` resolves to; see SpringCurve. That is this file's choice, not a limit of the
 * engine: `WearCircularProgressView` takes the real spec objects instead, resolved at tune in
 * `ProgressIndicator.kt` and carried in as attribute data (`CircularProgressMotion`), and runs them on
 * the ported `Animatable`. What the two routes still differ on is the platform animator duration scale
 * ("no animations" in developer options): a ValueAnimator obeys it, while a frame-clock spring does not,
 * because `SuspendAnimation.kt:309-314` takes the scale from a `MotionDurationScale` context element that
 * no Hibari scope installs (see `WearCircularProgressView`'s header).
 */

/**
 * `GapExtraProgress` (`CircularProgressIndicator.kt:611`): the extra progress a full circle is pushed
 * to so the merge animation has something to unwind.
 */
internal const val GapExtraProgress = 0.05f

/**
 * `gapSweep` from `drawCircularProgressIndicator` (`CircularProgressIndicator.kt:277`) and the two
 * indeterminate routines: the angular gap between an indicator's end cap and the track's, for a stroke
 * of [strokePx], a gap of [gapPx] and a drawing area of [minSize].
 *
 * Upstream hands the ratio straight to `asin()`, and outside [-1, 1] that is NaN, which propagates
 * into every angle and draws nothing. The degenerate ratio is clamped instead, so a stroke that fills
 * its own circle still renders; callers guard the impossible case beforehand.
 */
internal fun gapSweepFor(strokePx: Float, gapPx: Float, minSize: Float): Float {
    val ratio = ((strokePx + gapPx) / (minSize - strokePx)).coerceIn(0f, 1f)
    return Math.toDegrees(asin(ratio).toDouble()).toFloat() * 2f
}

/**
 * `DrawScope.drawIndicatorSegment` (`material3/ProgressIndicator.kt:301-348`) on a `Canvas`, sized to
 * its own bounds and painting with a caller-owned [paint] — a draw routine must not allocate one.
 *
 * [strokePadding] is upstream's parameter of the same name (`:307`), used by the segmented indicator
 * to widen the progress stroke by a pixel so it cannot leave a seam over the track arc
 * (`SegmentedCircularProgressIndicator.kt`'s `AntiAliasingStrokePadding`, b/381865505).
 */
internal fun Canvas.drawIndicatorSegment(
    paint: Paint,
    startAngle: Float,
    sweep: Float,
    gapSweep: Float,
    color: Color,
    strokeWidth: Float,
    strokePadding: Float = 0f,
) {
    if (color.isUnspecified || sweep <= 0f) return
    val minSide = min(width, height).toFloat()
    paint.color = color.toArgb()

    if (sweep <= gapSweep) {
        // Too narrow to be an arc: a dot centred on the segment's own angle, shrunk and faded by how
        // far below the gap it fell. Filled, as `drawCircle` is upstream.
        val angle = Math.toRadians((startAngle + sweep / 2f).toDouble()).toFloat()
        val radius = minSide / 2 - strokeWidth / 2
        val circleRadius = ((strokeWidth + strokePadding) / 2) * sweep / gapSweep
        val alphaScale = (circleRadius / strokeWidth * 2f).coerceAtMost(1f)
        val style = paint.style
        paint.style = Paint.Style.FILL
        paint.alpha = (color.alpha * 255f * alphaScale).toInt().coerceIn(0, 255)
        drawCircle(
            radius * cos(angle) + minSide / 2,
            radius * sin(angle) + minSide / 2,
            circleRadius,
            paint,
        )
        paint.style = style
        paint.alpha = (color.alpha * 255f).toInt().coerceIn(0, 255)
    } else {
        // A stroked arc, inset by half the stroke so the round caps stay inside the bounds.
        val diameterOffset = strokeWidth / 2
        val arcDimen = minSide - 2 * diameterOffset
        val left = diameterOffset + (width - minSide) / 2f
        val top = diameterOffset + (height - minSide) / 2f
        paint.strokeWidth = strokeWidth + strokePadding
        drawArc(
            left,
            top,
            left + arcDimen,
            top + arcDimen,
            startAngle + gapSweep / 2,
            sweep - gapSweep,
            false,
            paint,
        )
    }
}

/** `MotionScheme` effects spring stiffnesses; upstream `internal` in MaterialTheme's MotionScheme. */
private const val EffectsSlowStiffness = 260f
private const val EffectsFastStiffness = 1400f

/** Compose's visibility threshold for a `Float` animation, i.e. where a spring is considered done. */
private const val SpringVisibilityThreshold = 0.01f

/**
 * `AntiAliasingStrokePadding` from SegmentedCircularProgressIndicator.kt: widens the progress stroke
 * by 1 px so it cannot leave a seam over the track arc (b/381865505).
 */
private const val AntiAliasingStrokePadding = 1f // 1 px

/** `ArcIndeterminateProgressEasing`. */
private val ArcIndeterminateProgressEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.7f, 1f)

/** `MotionTokens.EasingStandardAccelerate`, used by the overflow progress spec's intro phase. */
private val OverflowIntroEasing: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

/** `MotionTokens.EasingStandardDecelerate`, used by the overflow progress spec's outro phase. */
private val OverflowOutroEasing: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)

private val SlowEffectsSpring = SpringCurve(EffectsSlowStiffness)
private val FastEffectsSpring = SpringCurve(EffectsFastStiffness)

/** Maps an animation fraction onto a value in `0..1` over [durationMillis]. */
private interface ProgressCurve {
    val durationMillis: Long

    fun valueAt(fraction: Float): Float
}

/**
 * `spring(dampingRatio = DampingRatioNoBouncy, stiffness = …)`, solved in closed form: with unit
 * mass and ζ = 1 a spring released from rest follows `1 - (1 + ωt)e^{-ωt}`, which is exactly what
 * Compose integrates numerically. The window is cut at the point where the spring enters Compose's
 * 0.01f visibility threshold, and the curve is renormalised so the last frame lands on the target.
 *
 * What is lost: Compose's `Animatable` carries the running velocity into the next `animateTo`, so an
 * interrupted progress animation keeps its momentum. Each retune here restarts the spring from rest,
 * which shortens and softens interrupted transitions.
 */
private class SpringCurve(stiffness: Float) : ProgressCurve {

    private val omega = sqrt(stiffness)
    private val settleRadians = settleAt(omega)
    private val settleValue = positionAt(settleRadians)

    override val durationMillis: Long =
        (settleRadians / omega * 1000f).toLong().coerceAtLeast(1L)

    override fun valueAt(fraction: Float): Float =
        (positionAt(fraction * settleRadians) / settleValue).coerceIn(0f, 1f)

    private fun positionAt(radians: Float): Float = 1f - (1f + radians) * exp(-radians)

    private fun settleAt(omega: Float): Float {
        // (1 + u) e^-u falls monotonically past the threshold; bisect for the crossing.
        var low = 0f
        var high = 30f
        repeat(24) {
            val mid = (low + high) / 2f
            if (1f - positionAt(mid) > SpringVisibilityThreshold) low = mid else high = mid
        }
        return high
    }
}

/**
 * `createOverflowProgressAnimationSpec`: an accelerating intro, a variable-length stretch at
 * [OverflowPeakSpeed] of the progress circle per second, then a decelerating outro. The intro/outro
 * cover a fixed share of the distance — the area under their Bézier curves, hence the two factors.
 */
private class OverflowCurve(
    private val from: Float,
    to: Float,
) : ProgressCurve {

    private val direction = if (to - from > 0f) 1f else -1f
    private val introDistance = direction * IntroCurveAreaFactor * OverflowPeakSpeed * (IntroMillis / 1000f)
    private val outroDistance = direction * OutroCurveAreaFactor * OverflowPeakSpeed * (OutroMillis / 1000f)
    private val midDistance = (to - from) - introDistance - outroDistance
    private val midMillis = (midDistance.absoluteValue / OverflowPeakSpeed * 1000f).toInt().coerceAtLeast(0)

    override val durationMillis: Long = (IntroMillis + OutroMillis + midMillis).toLong()

    override fun valueAt(fraction: Float): Float {
        val elapsed = fraction * durationMillis
        return when {
            elapsed <= IntroMillis ->
                from + introDistance *
                    OverflowIntroEasing.transform((elapsed / IntroMillis).coerceIn(0f, 1f))
            elapsed <= IntroMillis + midMillis ->
                from + introDistance + midDistance * ((elapsed - IntroMillis) / midMillis)
            else ->
                from + introDistance + midDistance + outroDistance *
                    OverflowOutroEasing.transform(
                        ((elapsed - IntroMillis - midMillis) / OutroMillis).coerceIn(0f, 1f)
                    )
        }
    }

    private companion object {
        const val IntroMillis = MotionDurationTokens.DurationMedium1 // 250ms, DurationMedium1
        const val OutroMillis = MotionDurationTokens.DurationMedium4 // 400ms, DurationMedium4
        const val OverflowPeakSpeed = 2f // progress circle rotations per second
        const val IntroCurveAreaFactor = 0.41f
        const val OutroCurveAreaFactor = 0.2f
    }
}

/**
 * `wrapProgress` (`material3/ProgressIndicator.kt:361-370`): 1.2 wraps to 0.2 once overflow is
 * allowed, whole values above 1 read as 1.0. Internal rather than private because
 * [com.huanli233.hibari.wear.drawCircularProgressIndicator] runs the same rule.
 */
internal fun wrapProgress(progress: Float, allowProgressOverflow: Boolean): Float {
    if (!allowProgressOverflow) return progress.coerceIn(0f, 1f)
    if (progress <= 0.0f) return 0.0f
    if (progress <= 1.0f) return progress

    val fraction = progress % 1.0f
    // Round to 5 decimals to avoid floating point errors.
    val roundedFraction = round(fraction * 100000f) / 100000f
    return if (roundedFraction == 0.0f) 1.0f else roundedFraction
}

private fun Float.isFullInt(): Boolean = round(this) == this

private fun Float.equalsWithTolerance(number: Float, tolerance: Float = 0.1f): Boolean =
    (this - number).absoluteValue < tolerance

/**
 * Segmented circular indicator, determinate (`progress`) or binary (`segmentMask`).
 *
 * Upstream draws this in a `drawWithCache` block; here the same loop runs in [onDraw] and the two
 * animated quantities upstream holds in `Animatable`s — the progress and the overflow-colour
 * fraction — are held as view state driven by [ValueAnimator].
 */
class WearSegmentedCircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var currentSpec: SegmentedCircularProgressSpec? = null
    private var initialized = false

    private var animatedProgress = 0f
    private var progressTarget = 0f
    private var lastProgress = 0f
    private var overflowColorFraction = 0f
    private var revealFraction = 1f
    private var lastMask: List<Boolean>? = null

    private var progressAnimator: ValueAnimator? = null
    private var overflowColorAnimator: ValueAnimator? = null
    private var revealAnimator: ValueAnimator? = null

    /**
     * The single entry point the modifier chain calls. Geometry and colours take effect
     * immediately; a changed `progress` (determinate) or a changed `segmentMask` (binary) restarts
     * the animation upstream would restart.
     */
    fun updateSpec(newSpec: SegmentedCircularProgressSpec) {
        currentSpec = newSpec
        if (newSpec.segmentMask != null) updateBinary(newSpec) else updateDeterminate(newSpec)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        // Upstream fills its parent and tells the caller to apply
        // `CircularProgressIndicatorDefaults.FullScreenPadding`, which is `PaddingDefaults.edgePadding`
        // — a flat 2.dp. With no parent constraints to fill, the same look is the screen diameter
        // inset by that padding on both sides.
        val insetPx = WearInsets.EdgePaddingDp * density
        val configuration = resources.configuration
        val desired =
            (min(configuration.screenWidthDp, configuration.screenHeightDp) * density - 2f * insetPx)
                .toInt()
                .coerceAtLeast(0)
        setMeasuredDimension(resolveSize(desired, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val spec = currentSpec ?: return
        val strokePx = spec.strokeWidth.value * resources.displayMetrics.density
        val minSide = min(width, height).toFloat()
        if (strokePx <= 0f || minSide <= strokePx * 2f) return

        val gapPx = spec.gapSize.value * resources.displayMetrics.density
        val gapSweep = gapSweepFor(strokePx, gapPx, minSide)

        val segmentCount = spec.segmentCount
        val fullSweep = 360f - ((spec.startAngle - spec.endAngle) % 360 + 360) % 360
        val segmentSweep = fullSweep / segmentCount
        val mask = spec.segmentMask
        if (mask == null) {
            drawDeterminate(canvas, spec, gapSweep, segmentCount, fullSweep, segmentSweep, strokePx)
        } else {
            drawBinary(canvas, spec, mask, gapSweep, segmentCount, fullSweep, segmentSweep, strokePx)
        }
    }

    private fun drawDeterminate(
        canvas: Canvas,
        spec: SegmentedCircularProgressSpec,
        gapSweep: Float,
        segmentCount: Int,
        fullSweep: Float,
        segmentSweep: Float,
        strokePx: Float,
    ) {
        val wrappedProgress = wrapProgress(animatedProgress, spec.allowProgressOverflow)
        val progressInSegments = segmentCount * wrappedProgress
        val targetProgress = segmentCount * wrapProgress(progressTarget, spec.allowProgressOverflow)
        val hasOverflow = spec.allowProgressOverflow && animatedProgress > 1.0f
        val trackColor =
            if (hasOverflow) spec.colors.overflowTrackColorFor(spec.enabled, overflowColorFraction)
            else spec.colors.trackColorFor(spec.enabled)

        for (segment in 0 until segmentCount) {
            val segmentStartAngle = spec.startAngle + fullSweep * segment / segmentCount
            if (segment >= floor(progressInSegments)) {
                drawSegment(canvas, segmentStartAngle, segmentSweep, gapSweep, trackColor, strokePx, 0f)
            }

            if (segment < progressInSegments) {
                var progressSweep = segmentSweep * (progressInSegments - segment).coerceAtMost(1f)

                // A progress sweep smaller than the gap sweep renders as a dot, and a dot must never
                // be the resting state of a segment: only mid-transition frames may show one.
                val isValidTarget = targetProgress < segment ||
                    targetProgress > segment + 1 ||
                    targetProgress.isFullInt() ||
                    floor(animatedProgress) != floor(progressTarget) ||
                    segmentSweep * (targetProgress - segment) > gapSweep

                if (progressSweep != 0f && !isValidTarget) {
                    progressSweep = progressSweep.coerceIn(gapSweep, segmentSweep)
                }

                drawSegment(
                    canvas,
                    segmentStartAngle,
                    progressSweep,
                    gapSweep,
                    spec.colors.indicatorColorFor(spec.enabled),
                    strokePx,
                    AntiAliasingStrokePadding,
                )
            }
        }
    }

    private fun drawBinary(
        canvas: Canvas,
        spec: SegmentedCircularProgressSpec,
        mask: List<Boolean>,
        gapSweep: Float,
        segmentCount: Int,
        fullSweep: Float,
        segmentSweep: Float,
        strokePx: Float,
    ) {
        val progressInSegments = segmentCount * revealFraction
        for (segment in 0 until segmentCount) {
            val segmentStartAngle = spec.startAngle + fullSweep * segment / segmentCount

            drawSegment(
                canvas,
                segmentStartAngle,
                segmentSweep,
                gapSweep,
                spec.colors.trackColorFor(spec.enabled),
                strokePx,
                0f,
            )

            if (segment < progressInSegments && mask.getOrElse(segment) { false }) {
                var progressSweep = segmentSweep * (progressInSegments - segment).coerceAtMost(1f)

                // Coerce progress sweep to the minimum of gap sweep.
                if (progressSweep != 0f) progressSweep = progressSweep.coerceIn(gapSweep, segmentSweep)

                drawSegment(
                    canvas,
                    segmentStartAngle,
                    progressSweep,
                    gapSweep,
                    spec.colors.indicatorColorFor(spec.enabled),
                    strokePx,
                    AntiAliasingStrokePadding,
                )
            }
        }
    }

    /** [drawIndicatorSegment], with this view's paint and bounds. See [WearCircularProgressView]. */
    private fun drawSegment(
        canvas: Canvas,
        startAngle: Float,
        sweep: Float,
        gapSweep: Float,
        color: Color,
        strokePx: Float,
        strokePadding: Float,
    ) {
        canvas.drawIndicatorSegment(arcPaint, startAngle, sweep, gapSweep, color, strokePx, strokePadding)
    }

    private fun updateDeterminate(newSpec: SegmentedCircularProgressSpec) {
        if (!initialized) {
            // Upstream's LaunchedEffect swallows the first emission: the initial value is snapped to.
            initialized = true
            lastProgress = newSpec.progress
            animatedProgress = coercedForAnimation(newSpec.progress, newSpec.allowProgressOverflow)
            progressTarget = animatedProgress
            overflowColorFraction =
                if (newSpec.allowProgressOverflow && newSpec.progress > 1f) 1f else 0f
            return
        }

        val target = coercedForAnimation(newSpec.progress, newSpec.allowProgressOverflow)
        // A jump of more than a whole circle gets the three-phase spec, anything shorter the spring.
        val curve: ProgressCurve =
            if ((target - lastProgress).absoluteValue > 1f) OverflowCurve(animatedProgress, target)
            else SlowEffectsSpring
        lastProgress = newSpec.progress
        progressTarget = target

        when {
            newSpec.allowProgressOverflow && target > 1f -> runProgress(curve, target, fadeOverflowIn = true)
            newSpec.allowProgressOverflow -> {
                // Reverse the overflow colour while the arc shrinks, both in parallel.
                runProgress(curve, target, fadeOverflowIn = false)
                runOverflowColor(0f)
            }

            else -> runProgress(curve, target, fadeOverflowIn = false)
        }
    }

    private fun updateBinary(newSpec: SegmentedCircularProgressSpec) {
        val mask = newSpec.segmentMask ?: return
        if (!initialized) {
            initialized = true
            lastMask = mask
            revealFraction = 1f
            return
        }
        if (mask == lastMask) return
        lastMask = mask

        // Upstream animates the reveal from scratch every time `segmentValue` changes.
        revealAnimator?.cancel()
        revealFraction = 0f
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FastEffectsSpring.durationMillis
            interpolator = LinearInterpolator()
            addUpdateListener {
                revealFraction = FastEffectsSpring.valueAt(it.animatedFraction)
                invalidate()
            }
            start()
        }
    }

    private fun runProgress(curve: ProgressCurve, target: Float, fadeOverflowIn: Boolean) {
        progressAnimator?.cancel()
        val from = animatedProgress
        if (from == target || curve.durationMillis <= 1L) {
            animatedProgress = target
            return
        }
        var overflowStarted = false
        progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = curve.durationMillis
            interpolator = LinearInterpolator()
            addUpdateListener {
                animatedProgress = from + (target - from) * curve.valueAt(it.animatedFraction)
                // Start the overflow colour transition as the arc crosses a full circle.
                if (fadeOverflowIn && !overflowStarted && animatedProgress.equalsWithTolerance(1f)) {
                    overflowStarted = true
                    runOverflowColor(1f)
                }
                invalidate()
            }
            start()
        }
    }

    private fun runOverflowColor(target: Float) {
        overflowColorAnimator?.cancel()
        val from = overflowColorFraction
        if (from == target) return
        overflowColorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FastEffectsSpring.durationMillis
            interpolator = LinearInterpolator()
            addUpdateListener {
                overflowColorFraction =
                    from + (target - from) * FastEffectsSpring.valueAt(it.animatedFraction)
                invalidate()
            }
            start()
        }
    }

    private fun coercedForAnimation(progress: Float, allowProgressOverflow: Boolean): Float =
        if (allowProgressOverflow) progress else progress.coerceIn(0f, 1f)

    override fun onDetachedFromWindow() {
        progressAnimator?.cancel()
        overflowColorAnimator?.cancel()
        revealAnimator?.cancel()
        progressAnimator = null
        overflowColorAnimator = null
        revealAnimator = null
        super.onDetachedFromWindow()
    }
}

/**
 * Indeterminate arc progress indicator: a track that drains ahead of the arc, the arc itself, and a
 * track that fills behind it, all on the circle inscribed in this view's width.
 *
 * The `head`/`tail` keyframes upstream runs through `rememberInfiniteTransition` are evaluated from
 * the elapsed milliseconds of one [ValueAnimator] cycle, which is both the same 2 s timeline and the
 * same `keyframes` semantics: ramp with `ArcIndeterminateProgressEasing`, then hold until the cycle
 * restarts.
 */
class WearArcProgressIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()

    private var currentSpec: ArcProgressIndicatorSpec? = null
    private var cycleAnimator: ValueAnimator? = null
    private var head = 0f
    private var tail = 0f

    fun updateSpec(newSpec: ArcProgressIndicatorSpec) {
        currentSpec = newSpec
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startCycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val desired = (
            ArcProgressIndicatorDefaults.IndeterminateArcDiameterPercentage *
                resources.configuration.screenHeightDp * density
            )
            .toInt()
        setMeasuredDimension(resolveSize(desired, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val spec = currentSpec ?: return
        val density = resources.displayMetrics.density
        val strokePx = spec.strokeWidth.value * density
        val widthPx = width.toFloat()
        if (strokePx <= 0f || widthPx <= 0f) return

        val counterClockwise = spec.angularDirection == AngularDirection.CounterClockwise
        // Upstream adds the stroke to the gap because the gap is measured between the end caps, then
        // divides by the canvas width alone: the arc circle is the canvas width, not its minimum side.
        val adjustedGapPx = (spec.gapSize.value + spec.strokeWidth.value) * density
        // Upstream leaves the ratio unchecked for asin(); above 1 that is NaN and nothing draws, so
        // the degenerate case is clamped instead.
        val ratio = (adjustedGapPx / widthPx).coerceIn(0f, 1f)
        val gapSweep = asin(ratio) * 360f / PI.toFloat()

        val fullSweep = ((spec.endAngle - spec.startAngle) % 360 + 360) % 360

        val beforeTrackSweep = (1 - head) * fullSweep
        if (beforeTrackSweep > 0) {
            drawIndicatorArc(
                canvas,
                startAngle = if (counterClockwise) spec.startAngle else spec.endAngle,
                sweep = if (counterClockwise) beforeTrackSweep else -beforeTrackSweep,
                color = spec.colors.trackColor,
                strokePx = strokePx,
                gapSweep = gapSweep,
            )
        }

        val arcStart =
            if (counterClockwise) spec.endAngle - tail * fullSweep else spec.startAngle + tail * fullSweep
        val arcSweep =
            if (counterClockwise) (tail - head) * fullSweep else (head - tail) * fullSweep
        drawIndicatorArc(
            canvas,
            startAngle = arcStart,
            sweep = arcSweep,
            color = spec.colors.indicatorColor,
            strokePx = strokePx,
            gapSweep = gapSweep,
        )

        val afterTrackSweep = tail * fullSweep
        if (afterTrackSweep > 0) {
            drawIndicatorArc(
                canvas,
                startAngle = if (counterClockwise) spec.endAngle else spec.startAngle,
                sweep = if (counterClockwise) -afterTrackSweep else afterTrackSweep,
                color = spec.colors.trackColor,
                strokePx = strokePx,
                gapSweep = gapSweep,
            )
        }
    }

    /** `DrawScope.drawIndicatorArc`, including its `size.width`-only dot placement. */
    private fun drawIndicatorArc(
        canvas: Canvas,
        startAngle: Float,
        sweep: Float,
        color: Color,
        strokePx: Float,
        gapSweep: Float,
    ) {
        if (color.isUnspecified) return
        arcPaint.color = color.toArgb()
        arcPaint.strokeWidth = strokePx

        val widthPx = width.toFloat()
        if (sweep.absoluteValue < gapSweep) {
            // Draw a small circle indicator. Upstream centres it on size.width for both axes, so on a
            // non-square canvas the dot sits off the arc; kept as is.
            val angle = Math.toRadians((startAngle + sweep / 2f).toDouble()).toFloat()
            val radius = widthPx / 2 - strokePx / 2
            val circleRadius = (strokePx / 2) * sweep.absoluteValue / gapSweep
            val alpha = (circleRadius / strokePx * 2f).coerceAtMost(1f)
            arcPaint.style = Paint.Style.FILL
            arcPaint.alpha = (color.alpha * 255f * alpha).toInt().coerceIn(0, 255)
            canvas.drawCircle(
                radius * cos(angle) + widthPx / 2,
                radius * sin(angle) + widthPx / 2,
                circleRadius,
                arcPaint,
            )
            arcPaint.style = Paint.Style.STROKE
            arcPaint.alpha = (color.alpha * 255f).toInt().coerceIn(0, 255)
        } else {
            drawCircularIndicator(
                canvas,
                startAngle = if (sweep > 0) startAngle + gapSweep / 2 else startAngle - gapSweep / 2,
                sweep = if (sweep > 0) sweep - gapSweep else sweep + gapSweep,
            )
        }
    }

    /** `DrawScope.drawCircularIndicator`: the arc rect is inset by half the stroke, on min side. */
    private fun drawCircularIndicator(canvas: Canvas, startAngle: Float, sweep: Float) {
        val diameter = min(width, height).toFloat()
        val diameterOffset = arcPaint.strokeWidth / 2
        val arcDimen = diameter - 2 * diameterOffset
        oval.set(
            diameterOffset + (width - diameter) / 2f,
            diameterOffset + (height - diameter) / 2f,
            diameterOffset + (width - diameter) / 2f + arcDimen,
            diameterOffset + (height - diameter) / 2f + arcDimen,
        )
        canvas.drawArc(oval, startAngle, sweep, false, arcPaint)
    }

    private fun startCycle() {
        if (cycleAnimator != null) return
        cycleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TotalArcAnimationDuration.toLong()
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val elapsed = it.animatedFraction * TotalArcAnimationDuration
                head = keyframeValue(elapsed, HeadDelay, HeadDuration)
                tail = keyframeValue(elapsed, TailDelay, TailDuration)
                invalidate()
            }
            start()
        }
    }

    /**
     * `keyframes { durationMillis = TotalArcAnimationDuration; 0f at delay using easing;
     * 1f at duration + delay }`: ramp over the keyframe span, hold either side of it.
     */
    private fun keyframeValue(elapsedMillis: Float, delayMillis: Int, durationMillis: Int): Float =
        when {
            elapsedMillis <= delayMillis -> 0f
            elapsedMillis >= delayMillis + durationMillis -> 1f
            else -> ArcIndeterminateProgressEasing.transform(
                (elapsedMillis - delayMillis) / durationMillis
            )
        }

    override fun onDetachedFromWindow() {
        cycleAnimator?.cancel()
        cycleAnimator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        /** Total duration for one arc cycle: `extra-long4 * 2`. */
        const val TotalArcAnimationDuration = 2000

        const val HeadDuration = MotionDurationTokens.DurationLong4 // long4
        const val TailDuration = MotionDurationTokens.DurationLong4 // long4
        const val HeadDelay = 0
        const val TailDelay = MotionDurationTokens.DurationMedium2 // medium2
    }
}
