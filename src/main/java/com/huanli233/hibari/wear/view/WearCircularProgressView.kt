package com.huanli233.hibari.wear.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.huanli233.hibari.animation.Animatable
import com.huanli233.hibari.animation.AnimationVector1D
import com.huanli233.hibari.animation.AnimationSpec
import com.huanli233.hibari.animation.CubicBezierEasing
import com.huanli233.hibari.animation.Easing
import com.huanli233.hibari.animation.LinearEasing
import com.huanli233.hibari.animation.keyframes
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.lerp
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.unit.isUnspecified
import com.huanli233.hibari.wear.CircularProgressIndicatorDefaults
import com.huanli233.hibari.wear.CircularProgressMotion
import com.huanli233.hibari.wear.tokens.MotionDurationTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.min

/**
 * Ported from `DrawScope.drawCircularProgressIndicator` and `DrawScope.drawIndicatorSegment` in
 * androidx.wear.compose.material3. The segment routine is the module's single shared one,
 * [drawIndicatorSegment], which is where upstream's `strokePadding` term and the filled-dot branch
 * live; the gap sweep comes from [gapSweepFor].
 *
 * Deliberate differences:
 *  - `progress` is a property, not `() -> Float`, and the emission that springs it is the attribute
 *    application rather than upstream's `snapshotFlow(updatedProgress).collectLatest {}`
 *    (`CircularProgressIndicator.kt:405-415`, `:461-494`): a different value is one emission, the
 *    emission's job is cancelled by the next one, and re-applying the same value is not an emission at
 *    all. One `Animatable` per animated quantity is kept across emissions the way upstream's
 *    `remember { Animatable(…) }` is, so an interrupted transition restarts from the value it had
 *    already reached rather than jumping to the target. What it does with *velocity* is `Animatable`'s
 *    own rule: a new `animateTo` continues from the current value and velocity
 *    (`hibari-animation/Animatable.kt:176-179`, `207-222`), while an animation whose caller's job was
 *    cancelled first resets velocity in its catch (`:304-308`, `:334-341`) - and upstream interrupts it
 *    the same way, through `collectLatest`, so whatever that yields is shared rather than reproduced.
 *    The specs are `slowEffectsSpec` for the arc and `fastEffectsSpec` for the overflow colour
 *    (`ProgressIndicator.kt:491-492`, `:495-496`) - `spring(DampingRatioNoBouncy, 260f)` and
 *    `spring(DampingRatioNoBouncy, 1400f)` (`MotionScheme.kt:148-152`, `:218-222`, `:261-265`), the
 *    *effects* pair rather than `defaultSpatialSpec`, and the same for both built schemes;
 *    [CircularProgressMotion] carries them in from this module's `MotionScheme.kt`, which holds the same
 *    numbers.
 *    Run on `withFrameNanos` inside this view's own scope, the spring is **not** scaled by the platform
 *    animator duration scale: `SuspendAnimation.kt:309-314` takes that scale from a `MotionDurationScale`
 *    context element, and nothing in this repo installs one for any scope. Whether upstream's spring is
 *    scaled turns on the provider that would put `Settings.Global.ANIMATOR_DURATION_SCALE` into the
 *    composition context, which lives in compose-runtime and not in the reference tree, so the
 *    difference is named and not closed on an unverified claim: if upstream does read it, then with the
 *    animator scale at 0 its determinate transition ends on the first frame while this one still plays
 *    the spring. The indeterminate cycle below is a [ValueAnimator] and does obey the platform scale.
 *  - `allowProgressOverflow` swaps the animation space, because upstream's `if (allowProgressOverflow)`
 *    (`:128-150`) swaps whole composables whose `remember { Animatable(…) }`s do not survive that: the
 *    flag's setter re-snaps [animatedProgress] (see [allowProgressOverflow]).
 *  - the indeterminate motion is upstream's three `keyframes` timelines (`:572-608`), evaluated from
 *    the elapsed milliseconds of one repeating [ValueAnimator] instead of three
 *    `rememberInfiniteTransition`s. Upstream hard-codes those specs, so nothing here reads a local;
 *    because they run on ValueAnimator they do obey the platform animator duration scale, which the
 *    frame-clock spring above does not for the reason given one bullet back.
 *  - an unsized indicator has no `fillMaxSize` constraints to take, so it measures itself - to
 *    [CircularProgressIndicatorDefaults.IndeterminateCircularIndicatorDiameter] while indeterminate
 *    (upstream's own `Modifier.size`, `:203`, which wins over the caller's) and to
 *    [DeterminateDiameterDp] otherwise. A caller's explicit size still wins here, because a view cannot
 *    overrule the MeasureSpec it is handed.
 */
class WearCircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private var cycleAnimator: ValueAnimator? = null
    private var globalRotation = 0f
    private var additionalRotation = 0f
    private var arcProgress = 0f

    /**
     * The two quantities upstream holds in `Animatable`s: the progress in animation space (see
     * [animationTargetFor]) and the indicator-to-overflow colour fraction, which upstream starts at 1
     * when the value it is created with is already past a full circle (`:459`).
     */
    private var progressAnimatable: Animatable<Float, AnimationVector1D> = Animatable(0f)
    private var overflowColorAnimatable: Animatable<Float, AnimationVector1D> = Animatable(0f)
    private var animationScope = progressAnimationScope()
    private var emissionJob: Job? = null
    private var motion: CircularProgressMotion? = null

    /** The value [onDraw] paints: the spring's current value, or the snapped target. */
    private var animatedProgress = 0f
    private var overflowColorFraction = 0f

    /**
     * Upstream's `lastProgress` (`:457`), the reference point of the more-than-a-circle test that picks
     * between the spring and [createOverflowProgressAnimationSpec]. Updated at the tail of an emission,
     * so an interrupted one leaves it behind, as upstream's does.
     */
    private var lastProgress = 0f

    /** The first applied value is snapped to, the way upstream swallows its first emission (`:411-413`). */
    private var progressInitialized = false

    /**
     * The requested progress, in the same space upstream's `progress()` lambda returns: raw, including
     * values past 1 when [allowProgressOverflow]. The drawn value is [animatedProgress], which the
     * spring moves towards it.
     */
    var progress: Float = 0f
        set(value) {
            val changed = field != value
            field = value
            if (!progressInitialized) {
                progressInitialized = true
                lastProgress = value
                snapProgressTo(animationTargetFor(value))
                // Upstream seeds the colour fraction off the raw value (`:459`): past 1 it starts
                // already blended in. The overflow flag only decides whether the fraction is ever read,
                // so it is not consulted here - and at creation it has not even been applied yet.
                snapOverflowColorTo(if (value > 1f) 1f else 0f)
                invalidate()
                return
            }
            // `changed` is the `snapshotFlow` filter: a retune that re-applies the same progress must
            // not restart the spring. The indeterminate path paints none of this.
            if (changed && !indeterminate) emitProgress(value)
        }

    /**
     * The specs and the blend flag upstream resolves off `MaterialTheme.motionScheme` for this
     * indicator; applied after [progress], so a value arrives here before anything has sprung on it.
     */
    internal fun applyProgressMotion(newMotion: CircularProgressMotion) {
        motion = newMotion
    }

    var indicatorColor: Color = Color.Unspecified
        set(value) {
            field = value
            invalidate()
        }

    var trackColor: Color = Color.Unspecified
        set(value) {
            field = value
            invalidate()
        }

    /**
     * `ProgressIndicatorColors.overflowTrackBrush` for this frame, read only past 1.0. At a fraction
     * below 1 and while [CircularProgressMotion.blendOverflowColor] this is the blend upstream takes
     * from [indicatorColor] to here (`ProgressIndicator.kt:252-261`).
     */
    var overflowTrackColor: Color = Color.Unspecified
        set(value) {
            field = value
            invalidate()
        }

    var strokeWidth: Dp = 12.dp
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * The gap between an indicator end cap and the track, as
     * `CircularProgressIndicator`'s `gapSize` parameter. [Dp.Unspecified] means "whatever
     * [CircularProgressIndicatorDefaults.calculateRecommendedGapSize] gives for the current stroke",
     * which is upstream's own default and stays recomputed here because a stroke change has to
     * re-derive it.
     */
    var gapSize: Dp = Dp.Unspecified
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var startAngle: Float = CircularProgressIndicatorDefaults.StartAngle
        set(value) {
            field = value
            invalidate()
        }

    var endAngle: Float = CircularProgressIndicatorDefaults.StartAngle
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Upstream's `allowProgressOverflow`: past 1.0 the value wraps - 1.2 reads as 20% - and the rest of
     * the sweep is drawn in [overflowTrackColor] instead of [trackColor]. Off, every value is coerced
     * into 0..1.
     *
     * Flipping it re-snaps the animated value, because upstream's `if (allowProgressOverflow)`
     * (`CircularProgressIndicator.kt:128-150`) leaves one implementation composable and enters the other,
     * and the `remember { Animatable(…) }`s of the branch that left composition do not carry over: the
     * new one starts at the current progress and swallows its first emission.
     */
    var allowProgressOverflow: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (progressInitialized && !indeterminate) {
                snapProgressTo(animationTargetFor(progress))
                snapOverflowColorTo(if (progress > 1f) 1f else 0f)
            }
            invalidate()
        }

    var indeterminate: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                emissionJob?.cancel()
                startCycle()
            } else {
                stopCycle()
            }
            requestLayout()
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredDp =
            if (indeterminate) {
                CircularProgressIndicatorDefaults.IndeterminateCircularIndicatorDiameter.value
            } else {
                DeterminateDiameterDp
            }
        val desired = (desiredDp * resources.displayMetrics.density).toInt()
        val size = resolveSize(desired, widthMeasureSpec)
        setMeasuredDimension(size, resolveSize(desired, heightMeasureSpec))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // This module's lists recycle views, so a detach cancels the cycle and a re-attach has to
        // start it again; the property setter alone only ran once.
        if (indeterminate) startCycle()
        // A `SupervisorJob` does not come back once cancelled, so a re-attached view gets a fresh scope
        // - and a progress that arrived while detached could not spring on the cancelled one, so its
        // value is caught up here instead of being left on the previous item's sweep.
        if (!animationScope.isActive) {
            animationScope = progressAnimationScope()
            if (progressInitialized && !indeterminate) {
                snapProgressTo(animationTargetFor(progress))
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val strokePx = strokeWidth.value * density
        if (strokePx <= 0f) return
        val minSide = min(width, height).toFloat()
        if (minSide <= strokePx * 2f) return

        val gapDp = if (gapSize.isUnspecified) {
            CircularProgressIndicatorDefaults.calculateRecommendedGapSize(strokeWidth)
        } else {
            gapSize
        }

        if (indeterminate) {
            drawIndeterminate(canvas, strokePx, gapDp.value * density, minSide)
            return
        }

        val gapSweep = gapSweepFor(strokePx, gapDp.value * density, minSide)

        val fullSweep = 360f - ((startAngle - endAngle) % 360 + 360) % 360
        // Upstream hands the draw routine nothing but the animated value
        // (`drawCircularProgressIndicator(progress = animatedProgress.value, …)`, `:424`, `:504`); it is
        // already in animation space, because the animation was started on it there.
        val effective = animatedProgress
        val wrapped = wrapProgress(effective, allowProgressOverflow)
        val progressSweep = fullSweep * wrapped

        canvas.drawIndicatorSegment(
            paint = arcPaint,
            startAngle = startAngle + progressSweep,
            sweep = fullSweep - progressSweep,
            gapSweep = gapSweep,
            color =
                if (allowProgressOverflow && effective > 1f) overflowTrackColorForFrame() else trackColor,
            strokeWidth = strokePx,
        )

        if (!allowProgressOverflow && wrapped == 1f && startAngle == endAngle) {
            // Closing the loop: upstream hands this branch the gap *fraction* in degrees, so a settled
            // full circle lands on 0 and the two ends merge into one continuous arc.
            val gapFraction = abs(1f + GapExtraProgress - effective) / GapExtraProgress
            canvas.drawIndicatorSegment(
                paint = arcPaint,
                startAngle = startAngle,
                sweep = progressSweep,
                gapSweep = gapFraction,
                color = indicatorColor,
                strokeWidth = strokePx,
            )
        } else {
            canvas.drawIndicatorSegment(
                paint = arcPaint,
                startAngle = startAngle,
                sweep = progressSweep,
                gapSweep = gapSweep,
                color = indicatorColor,
                strokeWidth = strokePx,
            )
        }
    }

    /** The indeterminate `Canvas` block (`CircularProgressIndicator.kt:202-226`). */
    private fun drawIndeterminate(canvas: Canvas, strokePx: Float, gapPx: Float, minSide: Float) {
        // `:206-208`: the gap is measured between the end caps, so the stroke is added to it, and the
        // angle it subtends is that length over the circumference. Upstream takes the ratio in dp and
        // pixels for the diameter; the density cancels, so both are pixels here.
        val gapSizeSweep = ((gapPx + strokePx) / (PI.toFloat() * minSide)) * 360f
        val sweep = arcProgress * 360f

        canvas.save()
        canvas.rotate(
            CircularRotationStartAngle + globalRotation + additionalRotation,
            width / 2f,
            height / 2f,
        )
        canvas.drawIndicatorSegment(
            paint = arcPaint,
            startAngle = sweep,
            sweep = 360f - sweep,
            gapSweep = min(sweep, gapSizeSweep),
            color = trackColor,
            strokeWidth = strokePx,
        )
        canvas.drawIndicatorSegment(
            paint = arcPaint,
            startAngle = 0f,
            sweep = sweep,
            gapSweep = gapSizeSweep,
            color = indicatorColor,
            strokeWidth = strokePx,
        )
        canvas.restore()
    }

    /**
     * `coercedProgressWithGap` (`CircularProgressIndicator.kt:556-564`): a full circle on the
     * single-angle path is pushed past 1 by [GapExtraProgress] so the merge branch has a range to
     * unwind through. Upstream applies it to the *animation target* (`:402`, `:408`), not in the draw
     * routine, and so does this view: the extra is what the spring travels through on its way to a
     * merged ring.
     */
    private fun coercedProgressWithGap(progress: Float, isFullCircle: Boolean): Float {
        val coercedProgress = progress.coerceIn(0f, 1f)
        return if (isFullCircle && coercedProgress == 1f) {
            1f + GapExtraProgress
        } else {
            coercedProgress
        }
    }

    /**
     * The space the animation runs in: the raw value when overflow is allowed, because upstream's
     * overflow `Animatable(initialProgress)` is seeded with it and wraps at draw time (`:458`, `:504`);
     * otherwise the 0..1 coercion plus the full-circle extra (`:402`, `:408`).
     */
    private fun animationTargetFor(value: Float): Float =
        if (allowProgressOverflow) {
            value
        } else {
            coercedProgressWithGap(value, startAngle == endAngle)
        }

    /** The overflow track colour for this frame: upstream's `overflowTrackBrush(enabled, fraction)`. */
    private fun overflowTrackColorForFrame(): Color {
        val fraction = overflowColorFraction
        return if (motion?.blendOverflowColor == true && fraction < 1f) {
            lerp(indicatorColor, overflowTrackColor, fraction)
        } else {
            overflowTrackColor
        }
    }

    private fun snapProgressTo(target: Float) {
        emissionJob?.cancel()
        // A fresh `Animatable` rather than upstream's `snapTo`, which is a `suspend` call: this is the
        // same state a branch's first composition starts in, value set and velocity zero.
        progressAnimatable = Animatable(target)
        animatedProgress = target
    }

    private fun snapOverflowColorTo(target: Float) {
        overflowColorAnimatable = Animatable(target)
        overflowColorFraction = target
    }

    /**
     * One emission of `snapshotFlow(updatedProgress).collectLatest { … }` (`:461-494` with overflow,
     * `:405-415` without). The previous emission's job is cancelled first, which is `collectLatest`'s
     * own cancellation. The colour transition is *not* parented the same way, though: upstream's inner
     * `launch { … }` (`:476`, `:482`) resolves against the `LaunchedEffect` scope - `collectLatest`'s
     * action lambda is not a `CoroutineScope` - so those colour jobs are siblings of the emission being
     * replaced and survive its cancellation, while here the colour job is a child of [emissionJob] and
     * dies with it. The visible consequence is narrow: on an interruption, upstream's orphaned colour job
     * keeps blending toward whatever fraction it was aiming at until the next `animateTo` takes over,
     * while this view's fraction stands still between the two emissions. In the common case the next
     * emission's arc reaches `equalsWithTolerance(1f)` within a frame or two and its `animateTo` on the
     * same [overflowColorAnimatable] cancels and replaces the in-flight one, which is what both routes
     * end up running.
     */
    private fun emitProgress(newProgress: Float) {
        val specs = motion
        if (specs == null) {
            // Nothing has told this view what to spring on yet, so the value lands as it did before
            // this animation existed at all.
            snapProgressTo(animationTargetFor(newProgress))
            invalidate()
            return
        }
        emissionJob?.cancel()
        if (!animationScope.isActive) return
        emissionJob = animationScope.launch {
            if (allowProgressOverflow) {
                // `:465-467`: a jump of more than a whole circle gets the three-phase keyframes, and
                // the test is against `lastProgress`, which an interrupted emission never advanced.
                val spec: AnimationSpec<Float> =
                    if ((newProgress - lastProgress).absoluteValue <= 1f) {
                        specs.progressSpec
                    } else {
                        createOverflowProgressAnimationSpec(newProgress, lastProgress)
                    }
                if (newProgress > 1f) {
                    progressAnimatable.animateTo(newProgress, spec) {
                        val frame = value
                        animatedProgress = frame
                        // `:475-477` launches the colour `animateTo` on *every* frame the value sits
                        // inside `equalsWithTolerance(1f)`, so each of those frames restarts that spring
                        // from where it stands; the blend only gets traction once the arc leaves the
                        // tolerance band. Reproduced rather than tidied into a start-once flag.
                        if (frame.equalsWithTolerance(1f)) {
                            launch {
                                overflowColorAnimatable.animateTo(1f, specs.overflowColorSpec) {
                                    overflowColorFraction = value
                                    invalidate()
                                }
                            }
                        }
                        invalidate()
                    }
                    lastProgress = newProgress
                } else {
                    // `:482-489`: the arc shrinks and the overflow colour unwinds concurrently.
                    launch {
                        awaitAll(
                            async {
                                progressAnimatable.animateTo(newProgress, spec) {
                                    animatedProgress = value
                                    invalidate()
                                }
                            },
                            async {
                                overflowColorAnimatable.animateTo(0f, specs.overflowColorSpec) {
                                    overflowColorFraction = value
                                    invalidate()
                                }
                            },
                        )
                    }
                    lastProgress = newProgress
                }
            } else {
                progressAnimatable.animateTo(animationTargetFor(newProgress), specs.progressSpec) {
                    animatedProgress = value
                    invalidate()
                }
            }
        }
    }

    private fun startCycle() {
        if (cycleAnimator != null) return
        cycleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = IndeterminateCycleDuration.toLong()
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val elapsed = it.animatedFraction * IndeterminateCycleDuration
                globalRotation = GlobalRotationAt.valueAt(elapsed)
                additionalRotation = AdditionalRotationAt.valueAt(elapsed)
                arcProgress = ArcProgressAt.valueAt(elapsed)
                invalidate()
            }
            start()
        }
    }

    private fun stopCycle() {
        cycleAnimator?.cancel()
        cycleAnimator = null
    }

    override fun onDetachedFromWindow() {
        stopCycle()
        emissionJob?.cancel()
        animationScope.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        /**
         * The diameter an unsized *determinate* indicator takes. Upstream has no number here - it
         * fills the constraints its caller gives it (`Spacer(modifier.fillMaxSize())`,
         * `CircularProgressIndicator.kt:417-420`) - and a `wrap_content` View has nothing to fill, so
         * this is the port's own stand-in. The indeterminate case does not use it: upstream sizes that
         * one itself, and [CircularProgressIndicatorDefaults.IndeterminateCircularIndicatorDiameter]
         * is its answer.
         */
        const val DeterminateDiameterDp = 56f
    }
}

/** `CircularProgressEasing`, i.e. `MotionTokens.EasingStandard` (`CircularProgressIndicator.kt:567`). */
private val CircularProgressEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

/** `CircularGlobalRotationDegreesTarget` (`:568`). */
private const val CircularGlobalRotationDegreesTarget = 1440f

/** `CircularRotationStartAngle` (`:569`). */
private const val CircularRotationStartAngle = 270f

/** The one cycle all three indeterminate timelines share: `durationMillis = 5000` (`:576`, `:589`, `:602`). */
private const val IndeterminateCycleDuration = 5000

/**
 * `circularIndeterminateGlobalRotationAnimationSpec` (`:572-579`): 1440 degrees - four turns - spread
 * linearly over the cycle.
 */
private val GlobalRotationAt = CircularKeyframes(
    initial = 0f,
    stops = arrayOf(CircularKeyframe(5000, CircularGlobalRotationDegreesTarget, LinearEasing)),
)

/**
 * `circularIndeterminateRotationAnimationSpec` (`:585-595`): a turn in 1250 ms, a 1250 ms hold, another
 * turn, a hold - the settle-and-leap of the spinner.
 */
private val AdditionalRotationAt = CircularKeyframes(
    initial = 0f,
    stops = arrayOf(
        CircularKeyframe(1250, 0f, CircularProgressEasing),
        CircularKeyframe(2500, 360f, CircularProgressEasing),
        CircularKeyframe(3750, 360f, CircularProgressEasing),
        CircularKeyframe(5000, 720f, CircularProgressEasing),
    ),
)

/**
 * `circularIndeterminateProgressAnimationSpec` (`:598-608`): the arc grows to 80% of the circle, falls
 * back to 20%, grows to 80% again and drains to nothing.
 */
private val ArcProgressAt = CircularKeyframes(
    initial = 0f,
    stops = arrayOf(
        CircularKeyframe(1250, 0.8f, CircularProgressEasing),
        CircularKeyframe(2500, 0.2f, CircularProgressEasing),
        CircularKeyframe(3750, 0.8f, CircularProgressEasing),
        CircularKeyframe(5000, 0.0f, CircularProgressEasing),
    ),
)

private class CircularKeyframe(val timeMillis: Int, val value: Float, val easing: Easing)

/**
 * `createOverflowProgressAnimationSpec` (`material3/ProgressIndicator.kt:443-473`) - the spec upstream
 * hands a determinate circular progress change that moves by more than a whole circle: an accelerating
 * intro, a middle phase at a fixed speed, then a decelerating outro. The intro and outro cover a fixed
 * share of the distance each, the two [IntroCubicBezierCurveAreaFactor] /
 * [OutroCubicBezierCurveAreaFactor] areas under their Bézier curves; the middle covers the rest at
 * [OverflowProgressMiddlePhaseSpeed], so its duration is what's left.
 *
 * Hibari's `hibari-animation` is a port of compose-animation-core, so `keyframes` and its `at`/`using`
 * DSL exist and this is upstream's spec object rather than an approximation of it.
 */
private fun createOverflowProgressAnimationSpec(
    newProgress: Float,
    oldProgress: Float,
): AnimationSpec<Float> {
    val progressDiff = newProgress - oldProgress
    val peakSpeed = OverflowProgressMiddlePhaseSpeed

    // Calculate intro and outro progress distance from the area under the CubicBezier curve.
    val introProgressDistance =
        IntroCubicBezierCurveAreaFactor * peakSpeed * (OverflowProgressIntroPhaseDuration / 1000f)
    val outroProgressDistance =
        OutroCubicBezierCurveAreaFactor * peakSpeed * (OverflowProgressOutroPhaseDuration / 1000f)
    val introProgress = if (progressDiff > 0) introProgressDistance else -introProgressDistance
    val outroProgress = if (progressDiff > 0) outroProgressDistance else -outroProgressDistance
    val midProgress = progressDiff - introProgress - outroProgress
    // Calculate the duration of the middle phase by dividing distance(progress) with speed.
    val midDuration = (midProgress.absoluteValue / peakSpeed * 1000).toInt()

    // Upstream writes these three sums inline; named here so each keyframe stays on one line, which
    // Kotlin needs of an infix `at`/`using` pair.
    val introEnd = OverflowProgressIntroPhaseDuration
    val introValue = oldProgress + introProgress
    val outroStart = introEnd + midDuration
    val outroValue = introValue + midProgress

    return keyframes<Float> {
        durationMillis =
            OverflowProgressIntroPhaseDuration + OverflowProgressOutroPhaseDuration + midDuration
        // Intro phase
        oldProgress at 0 using OverflowProgressIntroPhaseEasing
        // Middle phase
        introValue at introEnd using LinearEasing
        // Outro phase
        outroValue at outroStart using OverflowProgressOutroPhaseEasing
    }
}

/** `MotionTokens.DurationMedium1` (`material3/tokens/MotionTokens.kt:38`), 250 ms. */
private val OverflowProgressIntroPhaseDuration = MotionDurationTokens.DurationMedium1

/** `MotionTokens.EasingStandardAccelerate` (`:60`). */
private val OverflowProgressIntroPhaseEasing: Easing = CubicBezierEasing(0.3f, 0.0f, 1.0f, 1.0f)

/** `MotionTokens.DurationMedium4` (`:41`), 400 ms. */
private val OverflowProgressOutroPhaseDuration = MotionDurationTokens.DurationMedium4

/** `MotionTokens.EasingStandardDecelerate` (`:61`). */
private val OverflowProgressOutroPhaseEasing: Easing = CubicBezierEasing(0.0f, 0.0f, 0.0f, 1.0f)

/** `OverflowProgressMiddlePhaseSpeed` (`material3/ProgressIndicator.kt:480`). */
private val OverflowProgressMiddlePhaseSpeed = 2f // Full progress circle rotations per second

/** `IntroCubicBezierCurveAreaFactor` (`:486`), the area under `EasingStandardAccelerate`. */
private const val IntroCubicBezierCurveAreaFactor = 0.41f

/** `OutroCubicBezierCurveAreaFactor` (`:488`), the area under `EasingStandardDecelerate`. */
private const val OutroCubicBezierCurveAreaFactor = 0.2f

/** `Float.equalsWithTolerance` (`material3/ProgressIndicator.kt:429-430`), at upstream's 0.1f default. */
private fun Float.equalsWithTolerance(number: Float, tolerance: Float = 0.1f): Boolean =
    (this - number).absoluteValue < tolerance

/**
 * The progress and colour springs run on the main thread's frame clock, the same route
 * `view/WearPickerViews.kt` takes for its shim fade; `immediate` so a resumption already on the main
 * thread does not wait for another turn. `withFrameNanos` inside `Animatable.animateTo` falls back to
 * the Choreographer-backed `DefaultMonotonicFrameClock`, so no host scope has to install a clock.
 */
private fun progressAnimationScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * One `keyframes { durationMillis = 5000; value at time using easing }` table, as Compose reads it:
 * the value starts at [initial] at 0 ms, every stop interpolates from the one before it with that
 * stop's own easing, and the last stop holds for what is left of the cycle.
 */
private class CircularKeyframes(
    private val initial: Float,
    private val stops: Array<CircularKeyframe>,
) {

    fun valueAt(elapsedMillis: Float): Float {
        var fromValue = initial
        var fromTime = 0
        for (stop in stops) {
            if (elapsedMillis < stop.timeMillis) {
                val span = stop.timeMillis - fromTime
                if (span <= 0) return stop.value
                return fromValue +
                    (stop.value - fromValue) *
                    stop.easing.transform((elapsedMillis - fromTime) / span)
            }
            fromValue = stop.value
            fromTime = stop.timeMillis
        }
        return fromValue
    }
}
