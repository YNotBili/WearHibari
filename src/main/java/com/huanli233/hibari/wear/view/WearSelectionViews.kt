package com.huanli233.hibari.wear.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
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
 * The bare v1 [Checkbox]: an outline square, unchanging, with the two-segment tick drawn on top of it.
 *
 * The box is v1's `drawBox` (`material/ToggleControl.kt:383-397`), which the bare `Checkbox` hands core as
 * `{ _, color, _, _ -> drawBox(color) }` (`:79`) — it takes no `progress`, so upstream has **no filled state at
 * all**: one stroked round rect, `BOX_CORNER 3` / `BOX_STROKE 2` / `BOX_RADIUS 2` / `BOX_SIZE 18` (`:622-625`) on a
 * 24 x 24 canvas (`:634-635`), at full opacity in every state. The fading fill the material3 row draws is not
 * ported here.
 *
 * The tick is core's `animateTick` (`materialcore/SelectionControls.kt:462-477`) with its private `drawTick`
 * (`:547-592`) and `eraseTick` (`:625-662`): two segments from (6.7, 12.3) and (9.3, 16.3) of `TICK_BASE_LENGTH
 * 4.dp` and `TICK_STICK_LENGTH 8.dp`, the whole mark rotating off `TICK_ROTATION 15f` linearly and stroked with
 * `StrokeCap.Butt` under `BlendMode.Hardlight` while disabled. `startXOffset` is 0 either way — it is
 * `width - height` and both are 24.dp (`:106`) — so the tick is drawn where v1 draws it, and `eraseTick`'s dead
 * second leg is ported as written for the same reason recorded on [WearCheckboxButtonView]'s own `eraseTick`
 * (upstream compares an already clamped value there too), though the two forks erase from different points.
 */
class WearCheckboxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WearSelectionView(context, attrs, defStyleAttr, 24f, 24f) {

    private val tickPath = Path()
    private val box = RectF()

    /** Scratch output of [rotate], which runs once per tick vertex. */
    private val rotated = FloatArray(2)

    /** Which branch of `animateTick` is live: upstream branches on `checked`, not on `progress`. */
    private var tickChecked = false

    /** Upstream's `enabled` reaches the tick only through the `blendMode` argument. */
    private var tickEnabled = true

    private var tickColor: Color = Color.Unspecified

    /** Applies one resolved state: [boxColor] paints the outline, [checkmarkColor] the tick. */
    fun setCheckboxState(checked: Boolean, enabled: Boolean, boxColor: Color, checkmarkColor: Color) {
        tickChecked = checked
        tickEnabled = enabled
        tickColor = checkmarkColor
        // `controlColor` is what the base class gates `onDraw` with.
        controlColor = boxColor
        animateProgressTo(if (checked) 1f else 0f)
    }

    override fun drawControl(canvas: Canvas, density: Float, progress: Float) {
        drawBox(canvas, density)
        if (tickChecked) drawTick(canvas, density, progress) else eraseTick(canvas, density, progress)
    }

    /** `DrawScope.drawBox`: the stroked outline, full opacity, whatever `progress` is. */
    private fun drawBox(canvas: Canvas, density: Float) {
        val topCorner = dp(density, BoxCornerDp)
        val strokeWidth = dp(density, BoxStrokeDp)
        val halfStrokeWidth = strokeWidth / 2f
        val radius = dp(density, BoxRadiusDp)
        val size = dp(density, BoxSizeDp)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = controlColor.toArgb()
        box.set(
            topCorner + halfStrokeWidth,
            topCorner + halfStrokeWidth,
            topCorner + halfStrokeWidth + size - strokeWidth,
            topCorner + halfStrokeWidth + size - strokeWidth,
        )
        canvas.drawRoundRect(box, radius - halfStrokeWidth, radius - halfStrokeWidth, paint)
    }

    /** `drawTick`: base segment first, then the stick, both under one linear rotation about (12, 12). */
    private fun drawTick(canvas: Canvas, density: Float, tickProgress: Float) {
        val tickBaseLength = dp(density, TickBaseLengthDp)
        val tickStickLength = dp(density, TickStickLengthDp)
        val tickTotalLength = tickBaseLength + tickStickLength
        val tickProgressPx = tickProgress * tickTotalLength
        val centerX = dp(density, DesignCenterDp)
        val centerY = dp(density, DesignCenterDp)
        val angleDegrees = TickRotationDegrees - TickRotationDegrees / tickTotalLength * tickProgressPx
        val radians = Math.toRadians(angleDegrees.toDouble()).toFloat()
        val cosA = cos(radians)
        val sinA = sin(radians)

        val baseStartX = dp(density, TickBaseStartXDp)
        val baseStartY = dp(density, TickBaseStartYDp)
        val tickBaseProgress = min(tickProgressPx, tickBaseLength)

        tickPath.reset()
        rotate(baseStartX, baseStartY, centerX, centerY, cosA, sinA)
        tickPath.moveTo(rotated[0], rotated[1])
        rotate(
            baseStartX + tickBaseProgress,
            baseStartY + tickBaseProgress,
            centerX,
            centerY,
            cosA,
            sinA,
        )
        tickPath.lineTo(rotated[0], rotated[1])

        if (tickProgressPx > tickBaseLength) {
            val tickStickProgress = min(tickProgressPx - tickBaseLength, tickStickLength)
            val stickStartX = dp(density, TickStickStartXDp)
            val stickStartY = dp(density, TickStickStartYDp)
            rotate(stickStartX, stickStartY, centerX, centerY, cosA, sinA)
            tickPath.moveTo(rotated[0], rotated[1])
            rotate(
                stickStartX + tickStickProgress,
                stickStartY - tickStickProgress,
                centerX,
                centerY,
                cosA,
                sinA,
            )
            tickPath.lineTo(rotated[0], rotated[1])
        }

        strokeTick(canvas, density)
    }

    /** `eraseTick`: unchecked runs the mark back into itself from its tip, without the rotation. */
    private fun eraseTick(canvas: Canvas, density: Float, tickProgress: Float) {
        val tickBaseLength = dp(density, TickBaseLengthDp)
        val tickStickLength = dp(density, TickStickLengthDp)
        val tickTotalLength = tickBaseLength + tickStickLength
        val tickProgressPx = tickProgress * tickTotalLength
        val stickStartX = dp(density, EraseStickStartXDp)
        val stickStartY = dp(density, EraseStickStartYDp)
        val tickStickProgress = min(tickProgressPx, tickStickLength)

        tickPath.reset()
        tickPath.moveTo(stickStartX, stickStartY)
        tickPath.lineTo(stickStartX - tickStickProgress, stickStartY + tickStickProgress)

        // Upstream compares the already clamped `tickStickProgress` here, so this leg never draws; ported as
        // written rather than repaired to the apparently intended `tickProgressPx` test.
        if (tickStickProgress > tickStickLength) {
            val tickBaseProgress = min(tickProgressPx - tickStickLength, tickBaseLength)
            val baseStartX = dp(density, EraseBaseStartXDp)
            val baseStartY = dp(density, EraseBaseStartYDp)
            tickPath.moveTo(baseStartX, baseStartY)
            tickPath.lineTo(baseStartX - tickBaseProgress, baseStartY - tickBaseProgress)
        }

        strokeTick(canvas, density)
    }

    private fun strokeTick(canvas: Canvas, density: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = dp(density, TickStrokeWidthDp)
        paint.color = tickColor.toArgb()
        // Upstream: `blendMode = if (enabled) DefaultBlendMode else BlendMode.Hardlight`. Views only
        // gained Paint#setBlendMode in API 29, so a disabled tick stays src-over below that.
        val hardlight = !tickEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        if (hardlight) paint.blendMode = BlendMode.HARD_LIGHT
        canvas.drawPath(tickPath, paint)
        if (hardlight) paint.blendMode = BlendMode.SRC_OVER
    }

    /** `Offset.rotate(angleRadians, center)`, spelled out for a `Path`. */
    private fun rotate(x: Float, y: Float, centerX: Float, centerY: Float, cosA: Float, sinA: Float) {
        val dx = x - centerX
        val dy = y - centerY
        rotated[0] = centerX + dx * cosA - dy * sinA
        rotated[1] = centerY + dx * sinA + dy * cosA
    }

    private companion object {
        // material/ToggleControl.kt:622-625.
        const val BoxCornerDp = 3f
        const val BoxStrokeDp = 2f
        const val BoxRadiusDp = 2f
        const val BoxSizeDp = 18f

        // materialcore/SelectionControls.kt:690-692, with the design centre `drawTick` rotates about.
        const val TickBaseLengthDp = 4f
        const val TickStickLengthDp = 8f
        const val TickRotationDegrees = 15f
        const val TickBaseStartXDp = 6.7f
        const val TickBaseStartYDp = 12.3f
        const val TickStickStartXDp = 9.3f
        const val TickStickStartYDp = 16.3f
        const val EraseStickStartXDp = 17.3f
        const val EraseStickStartYDp = 8.3f
        const val EraseBaseStartXDp = 10.7f
        const val EraseBaseStartYDp = 16.3f
        const val TickStrokeWidthDp = 2f
        const val DesignCenterDp = 12f
    }
}

/**
 * The bare v1 [RadioButton]: a ring of constant weight with a centre dot whose radius tracks progress.
 *
 * Ring and dot are v1's two independent roles (`material/ToggleControl.kt:243`), so they take separate
 * colours: `RADIO_CIRCLE_RADIUS 9` stroked at `RADIO_CIRCLE_STROKE 2`, dot at `progress * RADIO_DOT_RADIUS 5`
 * (`materialcore/SelectionControls.kt:378-397`, constants `:696-698`). The centre is the draw scope's, not a
 * hard-coded 12.dp (`:373-377`), and the control is centred within its 24 x 24 canvas (`:512-521`).
 *
 * What upstream's `dotAlphaProgress` does — fade the dot's own alpha over `RAPID` after a `FLASH` delay, and
 * only while going to unchecked, because core nulls the animation once checked (`:341-353`) — is not ported; see
 * the class header. At rest both branches are the same, `alpha = dotColor.alpha`.
 */
class WearRadioView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WearSelectionView(context, attrs, defStyleAttr, 24f, 24f) {

    private var dotColor: Color = Color.Unspecified
        set(value) {
            field = value
            invalidate()
        }

    /** Applies one resolved state; [ring] and [dot] are v1's `ringColor` / `dotColor` roles. */
    fun setRadioState(selected: Boolean, ring: Color, dot: Color) {
        controlColor = ring
        dotColor = dot
        animateProgressTo(if (selected) 1f else 0f)
    }

    override fun drawControl(canvas: Canvas, density: Float, progress: Float) {
        val cx = width / 2f
        val cy = height / 2f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(density, RadioCircleStrokeDp)
        paint.color = controlColor.toArgb()
        canvas.drawCircle(cx, cy, dp(density, RadioCircleRadiusDp), paint)

        val dotRadius = dp(density, RadioDotRadiusDp) * progress
        if (dotRadius > 0f) {
            paint.style = Paint.Style.FILL
            paint.color = dotColor.toArgb()
            canvas.drawCircle(cx, cy, dotRadius, paint)
        }
    }

    private companion object {
        // materialcore/SelectionControls.kt:696-698.
        const val RadioCircleRadiusDp = 9f
        const val RadioCircleStrokeDp = 2f
        const val RadioDotRadiusDp = 5f
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
