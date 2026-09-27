package com.huanli233.hibari.wear.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Ported from `DrawScope.drawCircularProgressIndicator` and `DrawScope.drawIndicatorSegment` in
 * androidx.wear.compose.material3. The arc maths comes over unchanged:
 * `gapSweep = asin((stroke+gap)/(min-stroke))` doubled, and a segment narrower than its own gap
 * degrades into a dot that shrinks and fades instead of vanishing.
 *
 * Deliberate differences:
 *  - `progress` is a property, not `() -> Float`; the retune drives invalidation.
 *  - indeterminate mode is one rotating arc. Upstream chains three infinite transitions
 *    (1440 deg over 5 s, a 360 deg settle, then a variable sweep) timed by `MotionScheme`, which
 *    is not ported, so the motion reads differently even though the stroke does not.
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
    private val rect = RectF()
    private var rotationAnimator: ValueAnimator? = null
    private var rotationDegrees = 0f

    var progress: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
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

    var startAngle: Float = StartAngle
        set(value) {
            field = value
            invalidate()
        }

    var endAngle: Float = StartAngle
        set(value) {
            field = value
            invalidate()
        }

    var indeterminate: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startRotation() else stopRotation()
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (56 * resources.displayMetrics.density).toInt()
        val size = resolveSize(desired, widthMeasureSpec)
        setMeasuredDimension(size, resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val strokePx = strokeWidth.value * density
        if (strokePx <= 0f) return
        // Upstream defaults the gap to a third of the stroke when the caller leaves it unset.
        val gapPx = strokePx / 3f
        val minSide = min(width, height).toFloat()
        if (minSide <= strokePx * 2f) return

        val ratio = ((strokePx + gapPx) / (minSide - strokePx)).coerceIn(0f, 1f)
        val gapSweep = Math.toDegrees(asin(ratio).toDouble()).toFloat() * 2f

        if (indeterminate) {
            drawSegment(canvas, rotationDegrees, IndeterminateSweep, 0f, strokePx, indicatorColor)
            return
        }

        val fullSweep = 360f - ((startAngle - endAngle) % 360 + 360) % 360
        val wrapped = progress.coerceIn(0f, 1f)
        val progressSweep = fullSweep * wrapped

        drawSegment(canvas, startAngle + progressSweep, fullSweep - progressSweep, gapSweep, strokePx, trackColor)

        if (wrapped == 1f && startAngle == endAngle) {
            // Closing the loop: the two gap ends merge as the gap fraction shrinks to zero.
            val gapFraction = kotlin.math.abs(1f + GapExtraProgress - progress) / GapExtraProgress
            drawSegment(canvas, startAngle, progressSweep, gapFraction * gapSweep, strokePx, indicatorColor)
        } else {
            drawSegment(canvas, startAngle, progressSweep, gapSweep, strokePx, indicatorColor)
        }
    }

    /** One stroked arc, or, when [sweep] is narrower than [gapSweep], a shrinking faded dot. */
    private fun drawSegment(
        canvas: Canvas,
        startAngle: Float,
        sweep: Float,
        gapSweep: Float,
        strokePx: Float,
        color: Color,
    ) {
        if (color.isUnspecified || sweep <= 0f) return
        arcPaint.color = color.toArgb()
        arcPaint.strokeWidth = strokePx

        val minSide = min(width, height).toFloat()
        val offset = strokePx / 2

        if (sweep <= gapSweep) {
            val angle = Math.toRadians((startAngle + sweep / 2f).toDouble()).toFloat()
            val radius = minSide / 2 - offset
            val dotRadius = (strokePx / 2) * (sweep / gapSweep)
            val alphaScale = ((dotRadius / strokePx) * 2f).coerceAtMost(1f)
            arcPaint.alpha = (color.alpha * 255f * alphaScale).toInt().coerceIn(0, 255)
            canvas.drawCircle(
                radius * cos(angle) + minSide / 2,
                radius * sin(angle) + minSide / 2,
                dotRadius,
                arcPaint,
            )
            arcPaint.alpha = (color.alpha * 255f).toInt().coerceIn(0, 255)
        } else {
            val arcDimen = minSide - 2 * offset
            rect.set(
                offset + (width - minSide) / 2f,
                offset + (height - minSide) / 2f,
                offset + (width - minSide) / 2f + arcDimen,
                offset + (height - minSide) / 2f + arcDimen,
            )
            canvas.drawArc(rect, startAngle + gapSweep / 2, sweep - gapSweep, false, arcPaint)
        }
    }

    private fun startRotation() {
        if (rotationAnimator != null) return
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotationDegrees = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopRotation() {
        rotationAnimator?.cancel()
        rotationAnimator = null
    }

    override fun onDetachedFromWindow() {
        stopRotation()
        super.onDetachedFromWindow()
    }

    companion object {
        const val StartAngle = 270f
        const val GapExtraProgress = 0.05f
        const val IndeterminateSweep = 90f
    }
}
