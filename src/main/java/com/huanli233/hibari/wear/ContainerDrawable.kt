package com.huanli233.hibari.wear

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.huanli233.hibari.ui.geometry.CornerBasedShape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.lerp
import com.huanli233.hibari.wear.tokens.MotionDurationTokens

/**
 * Renders a [ContainerSpec] as a view background: a shape-rounded fill plus an optional inset
 * stroke. This is what Compose's `Modifier.surface()` reduces to on its non-transformation path
 * (`border(shape).clip(shape).paintBackground(ColorPainter(containerColor))`).
 *
 * [disabled] outranks [pressed], so a disabled container stays disabled while being pressed.
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
    private val pressedRadii = FloatArray(8)
    private val rect = RectF()
    private val fillPath = Path()
    private val strokePath = Path()
    private var transition: ValueAnimator? = null

    /** 0 draws [ContainerSpec.shape], 1 draws [ContainerSpec.pressedShape], in between it lerps. */
    private var pressedBlend = 0f

    private var pressed = false
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
        resolveRadii(w, h)

        rect.set(0f, 0f, w, h)
        // Canvas has no drawRoundRect taking per-corner radii, and CornerBasedShape yields four
        // distinct corners, so the outline has to be a Path.
        fillPath.reset()
        fillPath.addRoundRect(rect, outerRadii, Path.Direction.CW)

        canvas.translate(b.left.toFloat(), b.top.toFloat())
        fillPaint.color = withPaintAlpha(drawnColor).toArgb()
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
        strokePaint.color = withPaintAlpha(stroke.color).toArgb()
        strokePaint.strokeWidth = strokeWidthPx
        canvas.drawPath(strokePath, strokePaint)
    }

    /** [paintAlpha] folded into a colour, the only place a caller's alpha can survive a redraw. */
    private fun withPaintAlpha(color: Color): Color =
        if (paintAlpha == 255) color else color.copy(alpha = color.alpha * (paintAlpha / 255f))

    private fun resolveContainerColor(): Color = when {
        !enabled && spec.disabledContainerColor != null -> spec.disabledContainerColor!!
        pressed && spec.pressedContainerColor != null -> spec.pressedContainerColor!!
        else -> spec.containerColor
    }

    private fun resolveBorder(): BorderStroke? = when {
        !enabled && spec.disabledBorder != null -> spec.disabledBorder
        pressed && spec.pressedBorder != null -> spec.pressedBorder
        else -> spec.border
    }

    /** Must be true or [onStateChange] is never called and the state variants stay unreachable. */
    override fun isStateful(): Boolean = true

    /**
     * Upstream's own gate, moved to where the blend is decided
     * (`material3/RoundButton.kt:146-160`): `animateButtonShape` morphs only when *both* the resting
     * and the pressed shape are `CornerBasedShape`s, and otherwise falls back to the static shape.
     * [com.huanli233.hibari.wear.tokens.ShapeTokens.CornerFull] is
     * [com.huanli233.hibari.ui.geometry.CircleShape], which is not one, so
     * a default `IconButton` does not change shape under a finger upstream and must not here either.
     * A null [ContainerSpec.pressedShape] answers false, as before.
     */
    private val morphAllowed: Boolean
        get() = spec.shape is CornerBasedShape && spec.pressedShape is CornerBasedShape

    /** False until the first drawable state has been applied, which is the view's attach. */
    private var settled = false

    override fun onStateChange(states: IntArray): Boolean {
        val nowPressed = states.any { it == android.R.attr.state_pressed }
        val nowEnabled = states.any { it == android.R.attr.state_enabled }
        if (nowPressed == pressed && nowEnabled == enabled) return false
        val wasPressed = pressed
        pressed = nowPressed
        enabled = nowEnabled
        if (!settled) {
            // The first state this drawable sees is the state the view was attached in, not a change
            // the wearer caused: a disabled button would otherwise fade in from its enabled colour
            // over the release clock. Upstream has nothing to fade here — its container colour only
            // animates on an `enabled` transition it was already composited with.
            settled = true
            drawnColor = resolveContainerColor()
            drawnBorder = resolveBorder()
            cancelTransition()
            return true
        }
        animateTo(if (nowPressed && !wasPressed) FadeInMillis else FadeOutMillis)
        return true
    }

    /**
     * This port's stand-in for upstream's press feedback, on its own clock: 100 ms in
     * (`MotionDurationTokens.DurationShort2`), 200 ms out (`DurationShort4`).
     *
     * Not upstream's mechanism, in two ways. Upstream's press feedback is a ripple state layer that
     * tints the *content* colour by `pressedAlpha = 0.10f` (`material3/Ripple.kt:297-303`, provided
     * through `LocalIndication`), which this module has not ported; and the only container-colour
     * animation upstream has is a `tween(DurationMedium1 = 250 ms, EasingStandardDecelerate)` keyed on
     * `enabled` (`material3/IconButton.kt:966-967`), not on press. Tinting the container instead of
     * the content is a deliberate substitution, recorded here rather than dressed up as a port.
     *
     * Interpolation runs in linear light, which is what [com.huanli233.hibari.ui.graphics.lerp] does
     * (`hibari-ui/.../graphics/Color.kt:126-140`).
     */
    private fun animateTo(durationMillis: Long) {
        val fromColor = drawnColor
        val fromBorder = drawnBorder
        val toColor = resolveContainerColor()
        val toBorder = resolveBorder()
        // The shape rides on the same clock as the colour. Upstream runs it on
        // `fastSpatialSpec().faster(200f)` when pressing and `slowSpatialSpec()` when releasing
        // (`material3/MotionScheme.kt:124-133` + `AnimationSpecUtils.kt:71-77`, i.e. springs at
        // stiffness 12600 and 260), and its corner interpolation is a `RoundedPolygon` morph for a
        // `CornerBasedShape` pair (`material3/AnimatedCornerShape.kt:309-355`), a per-corner lerp
        // only for two `RoundedCornerShape`s (`:95-103`). This lerps eight radii in both cases, which
        // is upstream's second path generalised — close for the pill-and-round pairs these buttons
        // use, and one clock for colour and shape because both hang off the same view state.
        val toPressed = if (pressed && morphAllowed) 1f else 0f
        val fromPressed = pressedBlend
        if (fromColor == toColor && fromBorder?.color == toBorder?.color &&
            pressedBlend == toPressed
        ) {
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
                pressedBlend = fromPressed + (toPressed - fromPressed) * f
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

    /**
     * [ContainerSpec.shape]'s radii, already moved toward [ContainerSpec.pressedShape] by
     * [pressedBlend]. Lerping the eight floats is what upstream's `animateShape` does to a
     * `CornerBasedShape` pair, and it holds across mixed inputs because both shape kinds reduce to
     * the same eight values.
     */
    private fun resolveRadii(w: Float, h: Float) {
        spec.shape.toRadii(w, h, density, outerRadii)
        spec.pressedShape?.let { shape ->
            if (pressedBlend > 0f) {
                shape.toRadii(w, h, density, pressedRadii)
                blendRadiiToward(pressedBlend)
            }
        }
    }

    private fun blendRadiiToward(fraction: Float) {
        for (i in outerRadii.indices) {
            outerRadii[i] += (pressedRadii[i] - outerRadii[i]) * fraction
        }
    }

    private fun cancelTransition() {
        transition?.cancel()
        transition = null
    }

    /**
     * Stop the running transition because this drawable is being replaced ([ContainerSpec] changed,
     * or the container went empty). The animator only ends on its own, and an orphaned one keeps
     * invalidating a background nobody holds any more — visible as a container that finished fading
     * to a colour it no longer uses.
     */
    internal fun discard() {
        cancelTransition()
    }

    override fun setAlpha(alpha: Int) {
        paintAlpha = alpha
        invalidateSelf()
    }

    /**
     * The alpha the caller asked for. [draw] writes `paint.color` from [drawnColor] every frame, so
     * setting `paint.alpha` there would be undone on the next one; the value is folded into the
     * colours instead, which is also what a `Drawable` alpha is documented to mean.
     */
    private var paintAlpha: Int = 255

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
