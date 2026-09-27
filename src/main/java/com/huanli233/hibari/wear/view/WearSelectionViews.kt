package com.huanli233.hibari.wear.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.huanli233.hibari.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Selection controls ported from `materialcore/SelectionControls.kt`, `material3/AnimateTick.kt`
 * and the private draw functions of CheckboxButton / RadioButton / SwitchButton.
 *
 * All three are driven by a single `progress` in `0..1` that upstream animates with
 * `updateTransition(...).animateFloat(fastEffectsSpec())`. Here [animateProgressTo] runs a
 * [ValueAnimator] over the same span, so the geometry and the timing match; what is lost is
 * upstream's *separate* slow animation of the colour channels against the fast one of the shape.
 */
abstract class WearSelectionView(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int,
    protected val designWidthDp: Float,
    protected val designHeightDp: Float,
) : View(context, attrs, defStyleAttr) {

    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null

    /** 0 = unchecked, 1 = checked. Intermediate only while animating. */
    var progress: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (field != v) {
                field = v
                invalidate()
            }
        }

    var controlColor: Color = Color.Unspecified
        set(value) {
            field = value
            invalidate()
        }

    /** Called with the resolved colour already; alpha modulation is the subclass's job. */
    abstract fun drawControl(canvas: Canvas, density: Float, progress: Float)

    fun animateProgressTo(target: Float) {
        val t = target.coerceIn(0f, 1f)
        if (progress == t) return
        animator?.cancel()
        val from = progress
        animator = ValueAnimator.ofFloat(from, t).apply {
            duration = FastEffectMillis
            addUpdateListener { progress = it.animatedValue as Float }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val w = (designWidthDp * density).toInt()
        val h = (designHeightDp * density).toInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (controlColor.isUnspecified) return
        drawControl(canvas, resources.displayMetrics.density, progress)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    protected fun dp(density: Float, value: Float): Float = value * density

    private companion object {
        const val FastEffectMillis = 200L
    }
}

/**
 * Checkbox: an outline square that fades out as a filled square fades in, with the two-segment
 * tick drawn on top. `CHECKBOX_WIDTH/HEIGHT 24`, `BOX_SIZE 18`, `BOX_STROKE 2`, `BOX_RADIUS 2`.
 */
class WearCheckboxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WearSelectionView(context, attrs, defStyleAttr, 24f, 24f) {

    private val tickPath = Path()
    private val box = RectF()

    /** The tick's travelling pen position, in dp along the design box. */
    private val tickTotalDp = 2.5f + 6.0f

    override fun drawControl(canvas: Canvas, density: Float, progress: Float) {
        val top = dp(density, (24f - 18f) / 2f)
        val halfStroke = dp(density, 1f)
        val size = dp(density, 18f)
        val stroke = dp(density, 2f)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = withAlpha(controlColor, 1f - progress)
        box.set(top + halfStroke, top + halfStroke, top + halfStroke + size - stroke, top + halfStroke + size - stroke)
        canvas.drawRoundRect(box, dp(density, 1f), dp(density, 1f), paint)

        paint.style = Paint.Style.FILL
        paint.color = withAlpha(controlColor, progress)
        box.set(top, top, top + size, top + size)
        canvas.drawRoundRect(box, dp(density, 2f), dp(density, 2f), paint)

        drawTick(canvas, density, progress)
    }

    /**
     * The Material3 fork of `AnimateTick`. Two segments grow in sequence, and the whole mark rotates
     * from 15 deg toward 0 with a cubic ease (`1 - p^3`) rather than linearly.
     */
    private fun drawTick(canvas: Canvas, density: Float, progress: Float) {
        val travel = dp(density, min(progress * tickTotalDp, tickTotalDp))
        if (travel <= 0f) return

        val baseStartX = dp(density, 7.4f)
        val baseStartY = dp(density, 13.0f)
        val stickStartX = dp(density, 10.5f)
        val stickStartY = dp(density, 15.1f)
        val centerX = dp(density, 12f)
        val centerY = dp(density, 12f)
        val angleDegrees = 15f * (1f - progress * progress * progress)
        val radians = Math.toRadians(angleDegrees.toDouble()).toFloat()
        val cosA = cos(radians)
        val sinA = sin(radians)

        fun rotate(x: Float, y: Float): FloatArray {
            val dx = x - centerX
            val dy = y - centerY
            return floatArrayOf(centerX + dx * cosA - dy * sinA, centerY + dx * sinA + dy * cosA)
        }

        tickPath.reset()
        var p = rotate(baseStartX, baseStartY)
        tickPath.moveTo(p[0], p[1])
        val seg1 = dp(density, 2.5f).let { if (travel > it) it else travel }
        p = rotate(baseStartX + seg1, baseStartY + seg1)
        tickPath.lineTo(p[0], p[1])

        if (travel > dp(density, 2.5f)) {
            val seg2 = dp(density, 6f).let {
                val raw = travel - dp(density, 2.5f)
                if (raw > it) it else raw
            }
            p = rotate(stickStartX, stickStartY)
            tickPath.moveTo(p[0], p[1])
            p = rotate(stickStartX + seg2, stickStartY - seg2)
            tickPath.lineTo(p[0], p[1])
        }

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(density, 2f)
        paint.color = controlColor.toArgb()
        canvas.drawPath(tickPath, paint)
    }

    private fun withAlpha(color: Color, factor: Float): Int {
        val a = (color.alpha * factor).coerceIn(0f, 1f)
        return color.copy(alpha = a).toArgb()
    }
}

/** Radio: a constant-weight ring with a centre dot whose radius tracks progress. */
class WearRadioView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WearSelectionView(context, attrs, defStyleAttr, 24f, 24f) {

    override fun drawControl(canvas: Canvas, density: Float, progress: Float) {
        val cx = dp(density, 12f)
        val cy = dp(density, 12f)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(density, 2f)
        paint.color = controlColor.toArgb()
        canvas.drawCircle(cx, cy, dp(density, 9f), paint)

        val dotRadius = dp(density, 5f) * progress
        if (dotRadius > 0f) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, dotRadius, paint)
        }
    }
}

/**
 * Switch: 32x22 dp track with a 2 dp border inset by 1 dp, and a thumb whose radius grows 6 to 9 dp
 * while its centre travels 11 to 21 dp. The tick inside the thumb is the unrotated M3 tick, scaled
 * about (12, 12) with the same cubic ease.
 */
class WearSwitchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WearSelectionView(context, attrs, defStyleAttr, 32f, 24f) {

    private val track = RectF()
    private val thumbPath = Path()

    var trackColor: Color = Color.Unspecified
        set(value) {
            field = value
            invalidate()
        }

    var trackBorderColor: Color = Color.Unspecified
        set(value) {
            field = value
            invalidate()
        }

    var thumbColor: Color = Color.Unspecified
        set(value) {
            field = value
            invalidate()
        }

    var tickColor: Color = Color.Unspecified
        set(value) {
            field = value
            invalidate()
        }

    override fun drawControl(canvas: Canvas, density: Float, progress: Float) {
        val width = dp(density, 32f)
        val height = dp(density, 22f)
        val centerY = height / 2f
        val radius = height / 2f

        paint.style = Paint.Style.FILL
        paint.color = if (trackColor.isSpecified) trackColor.toArgb() else controlColor.toArgb()
        track.set(0f, 0f, width, height)
        canvas.drawRoundRect(track, radius, radius, paint)

        val border = if (trackBorderColor.isSpecified) trackBorderColor else controlColor
        if (border != trackColor) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(density, 2f)
            paint.color = border.toArgb()
            track.set(dp(density, 1f), dp(density, 1f), width - dp(density, 1f), height - dp(density, 1f))
            val inner = (height - dp(density, 2f)) / 2f
            canvas.drawRoundRect(track, inner, inner, paint)
        }

        // Upstream evaluates the radius first, then lerps x with that radius already in the terms.
        val thumbRadius = lerp(dp(density, 6f), dp(density, 9f), progress)
        val thumbX = lerp(
            thumbRadius + dp(density, 5f),
            width - thumbRadius - dp(density, 2f),
            progress,
        )

        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        paint.color = (if (thumbColor.isSpecified) thumbColor else controlColor).toArgb()
        canvas.drawCircle(thumbX, centerY, thumbRadius, paint)

        drawTick(canvas, density, progress, thumbX, centerY)
    }

    private fun drawTick(
        canvas: Canvas,
        density: Float,
        progress: Float,
        thumbX: Float,
        thumbY: Float,
    ) {
        val scale = 1f - (1f - progress) * (1f - progress) * (1f - progress)
        if (scale <= 0f) return
        val color = if (tickColor.isSpecified) tickColor else controlColor
        val pivotX = dp(density, 12f)
        val pivotY = dp(density, 12f)

        thumbPath.reset()
        thumbPath.moveTo(dp(density, 7.4f), dp(density, 13.0f))
        thumbPath.lineTo(dp(density, 9.9f), dp(density, 15.5f))
        thumbPath.moveTo(dp(density, 10.5f), dp(density, 15.1f))
        thumbPath.lineTo(dp(density, 16.5f), dp(density, 9.1f))

        val save = canvas.save()
        canvas.translate(thumbX - pivotX, thumbY - pivotY)
        canvas.scale(scale, scale, pivotX, pivotY)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(density, 2f)
        paint.color = color.toArgb()
        canvas.drawPath(thumbPath, paint)
        canvas.restoreToCount(save)
    }

    private fun lerp(from: Float, to: Float, fraction: Float): Float =
        from + (to - from) * fraction
}
