package com.huanli233.hibari.wear.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import com.huanli233.hibari.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Selection controls ported from `materialcore/SelectionControls.kt` (the drawing behind
 * `material/ToggleControl.kt`'s bare `Checkbox` / `Switch` / `RadioButton`), which is what
 * `SelectionControls.kt` drives these three views.
 *
 * All three are driven by a single `progress` in `0..1`. Upstream animates it with
 * `updateTransition(...).animateFloat(PROGRESS_ANIMATION_SPEC)` where the spec is
 * `tween(QUICK = 250, 0, STANDARD_IN)` (`material/ToggleControl.kt:632`, `material/Animation.kt:24,29`),
 * and the radio's dot radius runs `QUICK` on select but `RAPID = 150` off it (`ToggleControl.kt:188`,
 * `Animation.kt:23`) — so [animateProgressTo] takes the duration per call. What one progress cannot
 * carry is upstream's *second* channel: the radio fades its dot alpha on its own 150 ms spec delayed
 * 75 ms (`ToggleControl:189-190`, core `SelectionControls.kt:345-353`), and every one of these controls
 * tweens its colours on `COLOR_ANIMATION_SPEC` (`:631`) beside the shape. Both jump here.
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

    /** [durationMillis] defaults to v1's `QUICK`; the radio passes `RAPID` when deselecting. */
    fun animateProgressTo(target: Float, durationMillis: Long = QuickMillis) {
        val t = target.coerceIn(0f, 1f)
        if (progress == t) return
        animator?.cancel()
        val from = progress
        animator = ValueAnimator.ofFloat(from, t).apply {
            duration = durationMillis
            // `STANDARD_IN = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)`, `material/Animation.kt:29`.
            interpolator = PathInterpolator(0f, 0f, 0.2f, 1f)
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

    internal companion object {
        /** `QUICK`, `material/Animation.kt:24` — v1's `PROGRESS_ANIMATION_SPEC` duration. */
        const val QuickMillis = 250L

        /** `RAPID`, `material/Animation.kt:23` — how long v1's dot takes to shrink. */
        const val RapidMillis = 150L
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
 * Switch: v1's, not the row's — a 24 x 10 dp track drawn as one round-capped stroke down the middle of a
 * 24 x 24 dp canvas, and a 7 dp thumb whose centre travels 7 to 17 dp with no tick and no growth.
 * `SWITCH_TRACK_LENGTH 24` / `SWITCH_TRACK_HEIGHT 10` / `SWITCH_THUMB_RADIUS 7` / `WIDTH`-`HEIGHT 24`
 * (`material/ToggleControl.kt:627-629`, `:634-635`), `drawTrack` (`materialcore/SelectionControls.kt:593-623`)
 * and `drawThumb` (`material/ToggleControl.kt:398-416`).
 *
 * `material/ToggleControl.kt:123-134` hands v1's core the *same* colour for the track's fill and its
 * stroke, and for the thumb and its icon, and the icon argument is discarded (`:137`, `_`), so this view
 * has exactly two slots and draws neither a border ring nor a tick. [controlColor] is the track.
 */
class WearSwitchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WearSelectionView(context, attrs, defStyleAttr, 24f, 24f) {

    private val track = Path()

    var thumbColor: Color = Color.Unspecified
        set(value) {
            field = value
            invalidate()
        }

    override fun drawControl(canvas: Canvas, density: Float, progress: Float) {
        val trackLength = dp(density, 24f)
        val trackHeight = dp(density, 10f)
        // Upstream's `center.y` is the draw scope's, i.e. half the measured height, not half the 24 dp of
        // the design box — the two differ as soon as a parent measures the view taller.
        val centerY = height / 2f
        val strokeRadius = trackHeight / 2f

        // Upstream strokes a line rather than filling a rect: the stadium is a round-capped stroke of the
        // track's own height, from (trackHeight/2, centre) to (trackLength - trackHeight/2, centre).
        track.reset()
        track.moveTo(strokeRadius, centerY)
        track.lineTo(trackLength - strokeRadius, centerY)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = trackHeight
        paint.color = controlColor.toArgb()
        canvas.drawPath(track, paint)

        // `BlendMode.Src` (material/ToggleControl.kt:414) replaces the pixels under the thumb instead of
        // compositing onto them, which is what keeps a 0.6-alpha unchecked thumb from picking up the
        // track's alpha beneath it.
        val thumbRadius = dp(density, 7f)
        val thumbX = thumbRadius + (trackLength - 2f * thumbRadius) * progress
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        paint.color = thumbColor.toArgb()
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        canvas.drawCircle(thumbX, centerY, thumbRadius, paint)
        paint.xfermode = null
    }
}
