package com.huanli233.hibari.wear

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.lerp
import com.huanli233.hibari.wear.tokens.MotionDurationTokens

/**
 * Renders a [ContainerSpec] as a view background: a shape-rounded fill plus an optional inset
 * stroke. This is what Compose's `Modifier.surface()` reduces to on its non-transformation path
 * (`border(shape).clip(shape).paintBackground(ColorPainter(containerColor))`).
 *
 * [checked] outranks [pressed] so a held-down toggle still reads as selected.
 */
class ContainerDrawable(
    val spec: ContainerSpec,
    /** Set by [com.huanli233.hibari.wear.attributes.container] from the host view at apply time. */
    var density: Float = 1f,
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val outerRadii = FloatArray(8)
    private val innerRadii = FloatArray(8)
    private val rect = RectF()
    private val fillPath = Path()
    private val strokePath = Path()
    private var transition: ValueAnimator? = null

    private var pressed = false
    private var checked = false
    private var enabled = true

    /** What is on screen right now; it chases [resolveContainerColor] during a state transition. */
    private var drawnColor: Color
    private var drawnBorder: BorderStroke?

    init {
        drawnColor = spec.containerColor
        drawnBorder = spec.border
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return

        val w = b.width().toFloat()
        val h = b.height().toFloat()
        spec.shape.toRadii(w, h, density, outerRadii)

        rect.set(0f, 0f, w, h)
        // Canvas has no drawRoundRect taking per-corner radii, and CornerBasedShape yields four
        // distinct corners, so the outline has to be a Path.
        fillPath.reset()
        fillPath.addRoundRect(rect, outerRadii, Path.Direction.CW)

        canvas.translate(b.left.toFloat(), b.top.toFloat())
        fillPaint.color = drawnColor.toArgb()
        canvas.drawPath(fillPath, fillPaint)

        val stroke = drawnBorder ?: return
        val strokeWidthPx = stroke.width.value * density
        if (strokeWidthPx <= 0f) return
        // Compose strokes centred on an outline inset by half the stroke width, which keeps the
        // border inside the bounds and lets its outer edge cover the fill's antialiased rim.
        val inset = strokeWidthPx / 2f
        for (i in 0 until 8) innerRadii[i] = (outerRadii[i] - inset).coerceAtLeast(0f)
        rect.set(inset, inset, w - inset, h - inset)
        strokePath.reset()
        strokePath.addRoundRect(rect, innerRadii, Path.Direction.CW)
        strokePaint.color = stroke.color.toArgb()
        strokePaint.strokeWidth = strokeWidthPx
        canvas.drawPath(strokePath, strokePaint)
    }

    private fun resolveContainerColor(): Color = when {
        !enabled && spec.disabledContainerColor != null -> spec.disabledContainerColor!!
        checked && spec.checkedContainerColor != null -> spec.checkedContainerColor!!
        pressed && spec.pressedContainerColor != null -> spec.pressedContainerColor!!
        else -> spec.containerColor
    }

    private fun resolveBorder(): BorderStroke? = when {
        !enabled && spec.disabledBorder != null -> spec.disabledBorder
        checked && spec.checkedBorder != null -> spec.checkedBorder
        pressed && spec.pressedBorder != null -> spec.pressedBorder
        else -> spec.border
    }

    /** Must be true or [onStateChange] is never called and the state variants stay unreachable. */
    override fun isStateful(): Boolean = true

    override fun onStateChange(states: IntArray): Boolean {
        val nowPressed = states.any { it == android.R.attr.state_pressed }
        val nowChecked = states.any { it == android.R.attr.state_checked }
        val nowEnabled = states.any { it == android.R.attr.state_enabled }
        if (nowPressed == pressed && nowChecked == checked && nowEnabled == enabled) return false
        val wasPressed = pressed
        pressed = nowPressed
        checked = nowChecked
        enabled = nowEnabled
        animateTo(if (nowPressed && !wasPressed) FadeInMillis else FadeOutMillis)
        return true
    }

    /**
     * M3's state-layer fade: 100 ms on press, 200 ms on release. Interpolation runs in linear
     * light, which is what upstream's `SrgbToLinearTwoWayConverter` animates through.
     */
    private fun animateTo(durationMillis: Long) {
        val fromColor = drawnColor
        val fromBorder = drawnBorder
        val toColor = resolveContainerColor()
        val toBorder = resolveBorder()
        if (fromColor == toColor && fromBorder?.color == toBorder?.color) {
            drawnColor = toColor
            drawnBorder = toBorder
            cancelTransition()
            invalidateSelf()
            return
        }
        cancelTransition()
        transition = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = durationMillis
            addUpdateListener {
                val f = it.animatedValue as Float
                drawnColor = lerp(fromColor, toColor, f)
                val fromStrokeColor = fromBorder?.color
                val toStrokeColor = toBorder?.color
                drawnBorder = when {
                    fromStrokeColor != null && toStrokeColor != null ->
                        BorderStroke(toBorder!!.width, lerp(fromStrokeColor, toStrokeColor, f))
                    f >= 1f -> toBorder
                    else -> fromBorder
                }
                invalidateSelf()
            }
            start()
        }
    }

    private fun cancelTransition() {
        transition?.cancel()
        transition = null
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION") // Required below API 33, ignored by Canvas above it.
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        val FadeInMillis = MotionDurationTokens.DurationShort2.toLong()
        val FadeOutMillis = MotionDurationTokens.DurationShort4.toLong()
    }
}
