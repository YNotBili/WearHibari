package com.huanli233.hibari.wear.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.huanli233.hibari.animation.Animatable
import com.huanli233.hibari.animation.AnimationVector1D
import com.huanli233.hibari.animation.FiniteAnimationSpec
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.wear.LinearProgressIndicatorDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Ported from `LinearProgressIndicatorContent` in androidx.wear.compose.material3
 * (`LinearProgressIndicator.kt:125-187`): a round-capped track, an indicator line inset by half the
 * stroke at both ends so the caps stay inside the view, and the dot at the end of the range that
 * shrinks and fades as the line reaches it.
 *
 * The three numbers upstream keeps on `LinearProgressIndicatorDefaults` -
 * [LinearProgressIndicatorDefaults.DotRadius], [LinearProgressIndicatorDefaults.DotMargin] and
 * [LinearProgressIndicatorDefaults.OuterHorizontalMargin] - are read from there rather than restated.
 * The `.padding(OuterHorizontalMargin)` that shrinks upstream's canvas is folded into the drawing
 * bounds (`translate` plus a narrowed width) because a `View` has no padding of its own to give a
 * canvas.
 *
 * [flipHorizontal] is upstream's `.scale(scaleX = -1f …)` for a right-to-left layout (`:143`), so the
 * fill grows from the trailing edge and the dot sits at the leading one.
 *
 * Progress changes are sprung, as upstream springs them (`:89-98`): the drawn value is the current
 * value of an `Animatable` moved by `animateTo` on `linearProgressAnimationSpec`
 * (`:254-255`, `MaterialTheme.motionScheme.defaultEffectsSpec()`), which the component resolves at tune
 * and hands over through [applyProgressSpec]. Upstream's `LaunchedEffect`/`snapshotFlow`/`collectLatest`
 * triple becomes one emission per attribute application that carries a different value, each emission
 * cancelling the last, and the first application is snapped to - which is what upstream's
 * `Animatable(updatedProgress().coerceIn(0f, 1f))` seed already is for its first, no-op emission.
 *
 * This bar clamps nothing at draw time, and neither does upstream: the coercion is applied to the
 * target (`:91`, `:95`) and [onDraw] paints the animated value raw, exactly as upstream paints
 * `animatedProgress::value` (`:101`, `:145`), so a frame mid-transition sits wherever the spring has got
 * to between the old and the new target. Whether it can also cross the new target is `Animatable`'s own
 * velocity rule (`hibari-animation/Animatable.kt:176-179`, `207-222`, and the reset at `304-308`,
 * `334-341`), which this bar neither adds to nor takes away.
 */
class WearLinearProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var progressAnimatable: Animatable<Float, AnimationVector1D> = Animatable(0f)
    private var animationScope = linearProgressAnimationScope()
    private var emissionJob: Job? = null
    private var progressSpec: FiniteAnimationSpec<Float>? = null
    private var animatedProgress = 0f
    private var progressInitialized = false

    /**
     * The requested progress, coerced the way upstream coerces it before animating; the drawn value is
     * [animatedProgress].
     */
    var progress: Float = 0f
        set(value) {
            val changed = field != value
            field = value
            val target = value.coerceIn(0f, 1f)
            if (!progressInitialized) {
                progressInitialized = true
                snapProgressTo(target)
                invalidate()
                return
            }
            if (!changed) return
            val spec = progressSpec
            if (spec == null) {
                // Nothing has named a spec for this bar yet, so there is nothing to spring on.
                snapProgressTo(target)
                invalidate()
                return
            }
            emissionJob?.cancel()
            if (!animationScope.isActive) return
            emissionJob = animationScope.launch {
                progressAnimatable.animateTo(target, spec) {
                    // `this.value`, not `value`: a property setter's parameter is named `value` too, and
                    // the lexical name would win over the `Animatable` receiver's member.
                    animatedProgress = this.value
                    invalidate()
                }
            }
        }

    /** `linearProgressAnimationSpec` (`LinearProgressIndicator.kt:254-255`) for this bar. */
    fun applyProgressSpec(spec: FiniteAnimationSpec<Float>) {
        progressSpec = spec
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

    var strokeWidth: Dp = 12.dp
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * Mirror the bar on the X axis. Supplied by the component rather than read from
     * [View.getLayoutDirection]: a subtree that overrides its direction through
     * `LocalLayoutDirection` does not change this view's resolved direction, so reading the latter
     * silently misses the case upstream exists for.
     */
    var flipHorizontal: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val desiredHeight = (strokeWidth.value * density).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A detached view cancelled its scope and a `SupervisorJob` does not come back; a progress
        // applied while detached therefore could not spring, so the value is caught up here rather than
        // left on whatever the recycled view last drew.
        if (!animationScope.isActive) {
            animationScope = linearProgressAnimationScope()
            if (progressInitialized) {
                snapProgressTo(progress.coerceIn(0f, 1f))
                invalidate()
            }
        }
    }

    override fun onDetachedFromWindow() {
        emissionJob?.cancel()
        animationScope.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val strokePx = strokeWidth.value * density
        if (strokePx <= 0f || width <= 0) return

        // Upstream's `.padding(OuterHorizontalMargin)` shrinks the canvas rather than insetting the
        // drawing, so every coordinate below is measured inside that narrowed area.
        val marginPx = LinearProgressIndicatorDefaults.OuterHorizontalMargin.value * density
        val areaWidth = width - 2f * marginPx
        if (areaWidth <= 0f) return

        canvas.save()
        canvas.translate(marginPx, 0f)
        if (flipHorizontal) {
            canvas.scale(-1f, 1f, areaWidth / 2f, 0f) // Flip X axis for RTL layouts, as upstream does.
        }

        val y = height / 2f
        val capOffset = strokePx / 2f
        // `:145` reads the animated value straight (`progress()` is `animatedProgress::value`, `:101`).
        val progressPx = animatedProgress * (areaWidth - strokePx)

        drawLine(canvas, capOffset, areaWidth - capOffset, y, trackColor, strokePx)

        // Upstream draws the indicator only once there is something to show.
        if (progressPx > 0f) {
            drawLine(canvas, capOffset, capOffset + progressPx, y, indicatorColor, strokePx)
        }

        val dotRadius = LinearProgressIndicatorDefaults.DotRadius.value * density
        val dotMargin = LinearProgressIndicatorDefaults.DotMargin.value * density
        val dotCenterX = areaWidth - dotRadius - dotMargin
        val distanceFromProgressToDot = dotCenterX - dotRadius - progressPx - capOffset * 2f

        // The dot disappears when the progress line would touch it, and shrinks - then fades - as it
        // closes on the margin.
        if (distanceFromProgressToDot > 0f) {
            val scaleFraction = (distanceFromProgressToDot / dotMargin).coerceAtMost(1f)
            drawDot(canvas, indicatorColor, dotRadius * scaleFraction, dotCenterX, y, scaleFraction)
        }
        canvas.restore()
    }

    /**
     * Upstream's `Animatable(updatedProgress().coerceIn(0f, 1f))` seed (`:91`): the value lands with no
     * spring under it and velocity at zero. Replacing the `Animatable` is how that state is reached
     * outside a coroutine, where upstream's `snapTo` - a `suspend` call - is not available.
     */
    private fun snapProgressTo(target: Float) {
        emissionJob?.cancel()
        progressAnimatable = Animatable(target)
        animatedProgress = target
    }

    private fun drawLine(
        canvas: Canvas,
        start: Float,
        end: Float,
        y: Float,
        color: Color,
        strokePx: Float,
    ) {
        if (color.isUnspecified || end <= start) return
        paint.color = color.toArgb()
        paint.strokeWidth = strokePx
        canvas.drawLine(start, y, end, y, paint)
    }

    /** `drawLinearIndicatorDot` (`:239-251`): a filled circle, faded by the same fraction it shrank by. */
    private fun drawDot(
        canvas: Canvas,
        color: Color,
        radius: Float,
        centerX: Float,
        centerY: Float,
        alphaFraction: Float,
    ) {
        if (color.isUnspecified || radius <= 0f) return
        paint.color = color.toArgb()
        paint.alpha = (color.alpha * 255f * alphaFraction.coerceAtMost(1f)).toInt().coerceIn(0, 255)
        val style = paint.style
        paint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.style = style
    }

    override fun getSuggestedMinimumWidth(): Int =
        min(64, resources.displayMetrics.widthPixels / 4)
}

/**
 * The spring runs on the main thread's frame clock, the same route `view/WearPickerViews.kt` takes for
 * its shim fade; `immediate` so a resumption already on the main thread does not wait for another turn.
 */
private fun linearProgressAnimationScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
