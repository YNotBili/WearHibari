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
import com.huanli233.hibari.ui.graphics.lerp
import kotlin.math.pow

/**
 * Upstream's `COLOR_ANIMATION_SPEC` is `MaterialTheme.motionScheme.slowEffectsSpec()`, i.e.
 * `spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = EffectsSlowStiffness = 260f)`
 * (`material3/MotionScheme.kt`), while the thumb travels on `fastEffectsSpec()`
 * (`stiffness = EffectsFastStiffness = 1400f`).
 *
 * Those stiffnesses are upstream's; the millisecond spans below are **derived**, not copied: a
 * critically damped spring settles to within about a percent of its target after
 * `6.64 / sqrt(stiffness)` seconds, which is ~177 ms for the fast spec and ~412 ms for the slow one.
 * `WearSelectionView` already rounds the fast spec to 200 ms for its geometry ramp, so the slow
 * colour ramp is rounded to 400 ms beside it. Hibari has no spring driver, so both animations are
 * linear ramps over these spans: the durations are in the right ballpark, the curve is not
 * upstream's.
 */
internal const val WearSwitchButtonSlowEffectMillis: Long = 400L

/**
 * The toggle control of [com.huanli233.hibari.wear.SwitchButton] and
 * [com.huanli233.hibari.wear.SplitSwitchButton]: upstream's private `Switch`
 * (`material3/SwitchButton.kt`), whose `drawWithCache` body and `drawThumbAndTick` are copied here
 * line for line.
 *
 * Two things separate it from the bare [WearSwitchView]:
 *
 *  * Upstream runs *two* animations at once — the thumb geometry on the fast spec, every colour
 *    channel on the slow one. [WearSelectionView] only animates geometry, so its four colour
 *    properties snap; [animateSwitchTo] keeps that fast geometry animation and adds the slow colour
 *    tween beside it.
 *  * Upstream draws the 22.dp track inside a 32.dp x 24.dp slot, `wrapContentSize(CenterEnd)`
 *    centring it, so the track sits one dp below the slot's top edge. [WearSwitchView] draws it
 *    flush with the top. This view centres it.
 *
 * The `progress` geometry, the [WearSelectionView.dp] helper and the fast animation come from the
 * shared base class; the drawing itself is duplicated because [WearSwitchView] is `final` (like
 * every class in that file) and takes one colour per channel with no room for the second, slower
 * animation, so the only reuse point is the base class.
 *
 * Upstream's disabled thumb icon is drawn with `BlendMode.Hardlight`; that is not reproduced —
 * [com.huanli233.hibari.ui.graphics.Color] is sRGB-only and `Canvas` has no hard-light `Xfermode`
 * below API 29, so a substitute would be a different pixel, not a missing one.
 */
class WearSwitchButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WearSelectionView(context, attrs, defStyleAttr, SwitchWidthDp, SwitchOuterHeightDp) {

    private val trackRect = RectF()
    private val tickPath = Path()

    /** The four channels as they are on screen, chasing the targets of the last [animateSwitchTo]. */
    private var drawnTrackColor = Color.Unspecified
    private var drawnTrackBorderColor = Color.Unspecified
    private var drawnThumbColor = Color.Unspecified
    private var drawnTickColor = Color.Unspecified
    private var colorTransition: ValueAnimator? = null
    private var animatedOnce = false

    /**
     * Resolve the switch to a state: the thumb travels on the base class's fast animation, the four
     * colours cross-fade on the slow one, exactly as upstream's `updateTransition` and eight
     * `animateColorAsState` calls run side by side.
     */
    fun animateSwitchTo(
        checked: Boolean,
        trackColor: Color,
        trackBorderColor: Color,
        thumbColor: Color,
        thumbIconColor: Color,
    ) {
        // `WearSelectionView.onDraw` bails out while `controlColor` is unspecified; the four
        // channels below are what this view actually paints.
        controlColor = thumbColor
        if (!animatedOnce) {
            // Upstream's transition starts at its target, so the first tune must not slide in.
            animatedOnce = true
            progress = if (checked) 1f else 0f
        } else {
            animateProgressTo(if (checked) 1f else 0f)
        }
        animateColorsTo(trackColor, trackBorderColor, thumbColor, thumbIconColor)
    }

    private fun animateColorsTo(
        trackColor: Color,
        trackBorderColor: Color,
        thumbColor: Color,
        thumbIconColor: Color,
    ) {
        val fromTrack = drawnTrackColor
        val fromBorder = drawnTrackBorderColor
        val fromThumb = drawnThumbColor
        val fromTick = drawnTickColor
        if (fromTrack == trackColor && fromBorder == trackBorderColor &&
            fromThumb == thumbColor && fromTick == thumbIconColor
        ) {
            return
        }
        colorTransition?.cancel()
        // `animateColorAsState` only animates away from a value it already holds, and a view that
        // has never been laid out has nothing to cross-fade from.
        if (fromTrack.isUnspecified || !isLaidOut) {
            drawnTrackColor = trackColor
            drawnTrackBorderColor = trackBorderColor
            drawnThumbColor = thumbColor
            drawnTickColor = thumbIconColor
            invalidate()
            return
        }
        colorTransition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = WearSwitchButtonSlowEffectMillis
            addUpdateListener {
                val fraction = it.animatedValue as Float
                drawnTrackColor = lerp(fromTrack, trackColor, fraction)
                drawnTrackBorderColor = lerp(fromBorder, trackBorderColor, fraction)
                drawnThumbColor = lerp(fromThumb, thumbColor, fraction)
                drawnTickColor = lerp(fromTick, thumbIconColor, fraction)
                invalidate()
            }
            start()
        }
    }

    override fun drawControl(canvas: Canvas, density: Float, progress: Float) {
        val width = dp(density, SwitchWidthDp)
        val trackHeight = dp(density, SwitchInnerHeightDp)
        val trackTop = (measuredHeight - trackHeight) / 2f
        val centerY = trackTop + trackHeight / 2f

        // Track background.
        paint.style = Paint.Style.FILL
        paint.color = drawnTrackColor.toArgb()
        trackRect.set(0f, trackTop, width, trackTop + trackHeight)
        canvas.drawRoundRect(trackRect, trackHeight / 2f, trackHeight / 2f, paint)

        // Track border: upstream skips it whenever the two resolve to the same colour, and insets
        // the outline by half the stroke to copy `Modifier.border`'s behaviour.
        val borderColor =
            if (drawnTrackColor == drawnTrackBorderColor) Color.Transparent else drawnTrackBorderColor
        if (borderColor.alpha > 0f) {
            val strokeWidth = dp(density, SwitchTrackWidthDp)
            val inset = strokeWidth / 2f
            val innerRadius = (trackHeight - strokeWidth) / 2f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = borderColor.toArgb()
            trackRect.set(inset, trackTop + inset, width - inset, trackTop + trackHeight - inset)
            canvas.drawRoundRect(trackRect, innerRadius, innerRadius, paint)
        }

        // Thumb, then tick on top of it. Upstream evaluates the radius first and lerps the x
        // position with that radius already folded into both ends of the travel.
        val uncheckedPadding = trackHeight / 2f - dp(density, ThumbRadiusUncheckedDp)
        val checkedPadding = trackHeight / 2f - dp(density, ThumbRadiusCheckedDp)
        val thumbRadius = lerpF(
            dp(density, ThumbRadiusUncheckedDp),
            dp(density, ThumbRadiusCheckedDp),
            progress,
        )
        val thumbX = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            lerpF(width - thumbRadius - uncheckedPadding, thumbRadius + checkedPadding, progress)
        } else {
            lerpF(thumbRadius + uncheckedPadding, width - thumbRadius - checkedPadding, progress)
        }

        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        paint.color = drawnThumbColor.toArgb()
        canvas.drawCircle(thumbX, centerY, thumbRadius, paint)

        drawScalingTick(canvas, density, progress, thumbX, centerY)
    }

    /**
     * `AnimateTick.drawScalingTick` over `createFullTickPath`: the mark is built once at the 24.dp
     * design size and scaled about its centre `(12, 12)` with a cubic ease-out, after translating
     * that design centre onto the thumb.
     */
    private fun drawScalingTick(
        canvas: Canvas,
        density: Float,
        progress: Float,
        thumbX: Float,
        thumbY: Float,
    ) {
        if (drawnTickColor.alpha <= 0f) return
        val eased = 1f - (1f - progress).pow(3)
        if (eased <= 0f) return

        val pivot = dp(density, TickDesignCenterDp)
        val baseStartX = dp(density, TickBaseStartXDp)
        val baseStartY = dp(density, TickBaseStartYDp)
        val baseRun = dp(density, TickBaseComponentDp)
        val stickStartX = dp(density, TickStickStartXDp)
        val stickStartY = dp(density, TickStickStartYDp)
        val stickRun = dp(density, TickStickComponentDp)

        tickPath.reset()
        tickPath.moveTo(baseStartX, baseStartY)
        tickPath.lineTo(baseStartX + baseRun, baseStartY + baseRun)
        tickPath.moveTo(stickStartX, stickStartY)
        tickPath.lineTo(stickStartX + stickRun, stickStartY - stickRun)

        val save = canvas.save()
        canvas.translate(thumbX - pivot, thumbY - pivot)
        canvas.scale(eased, eased, pivot, pivot)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(density, TickStrokeWidthDp)
        paint.color = drawnTickColor.toArgb()
        canvas.drawPath(tickPath, paint)
        canvas.restoreToCount(save)
    }

    private fun lerpF(from: Float, to: Float, fraction: Float): Float =
        from + (to - from) * fraction
}

// `material3/SwitchButton.kt`'s file-private geometry, in dp.
private const val SwitchWidthDp = 32f
private const val SwitchOuterHeightDp = 24f
private const val SwitchInnerHeightDp = 22f
private const val SwitchTrackWidthDp = 2f
private const val ThumbRadiusUncheckedDp = 6f
private const val ThumbRadiusCheckedDp = 9f

// `AnimateTick.kt`: the two 45-degree segments of the tick, and the 24.dp box they are drawn in.
private const val TickBaseStartXDp = 7.4f
private const val TickBaseStartYDp = 13.0f
private const val TickBaseComponentDp = 2.5f
private const val TickStickStartXDp = 10.5f
private const val TickStickStartYDp = 15.1f
private const val TickStickComponentDp = 6f
private const val TickDesignCenterDp = 12f
private const val TickStrokeWidthDp = 2f
