package com.huanli233.hibari.wear.view

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.huanli233.hibari.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** `CHECKBOX_WIDTH` / `CHECKBOX_HEIGHT` of CheckboxButton.kt: the control is a 24.dp square. */
private const val CheckboxButtonCanvasSizeDp = 24f

/**
 * The checkmark control of `CheckboxButton` / `SplitCheckboxButton`, ported from the private
 * `Checkbox` composable and `DrawScope.drawBox` in `material3/CheckboxButton.kt`, plus
 * `DrawScope.animateTick` and its private `drawTick`/`eraseTick` in `material3/AnimateTick.kt`.
 * Every coordinate is the upstream literal.
 *
 * Why this is a [WearSelectionView] subclass rather than [WearCheckboxView]:
 *  - upstream hands the control **two** resolvers, `boxColor` and `checkmarkColor`, and they are
 *    different tokens (`Primary` on `PrimaryContainer`); [WearCheckboxView] draws box and tick from
 *    one inherited `controlColor`.
 *  - the tick has two branches: it is *drawn* forward while `checked` and *erased* backwards from
 *    (16.5, 9.0) while not, which is what makes an un-tap read as an un-tick. [WearCheckboxView]
 *    runs the draw geometry backwards instead.
 *  - a disabled tick is composited in hardlight, per upstream's `blendMode` argument.
 *
 * Colour animation: upstream runs `progress` on `fastEffectsSpec()` and each colour on
 * `slowEffectsSpec()` through `animateSelectionColor`, so the tick fades between
 * `checkedCheckmarkColor` and `Color.Transparent` while it grows. Both animations travel between the
 * same two ends, so the fade is folded into `progress` here; [setCheckboxState] keeps the last
 * colour that had ink in it, otherwise the erase branch would have nothing to draw once the resolved
 * checkmark colour turns transparent.
 *
 * RTL: `isLayoutDirectionRtl()` only moves the tick inside the canvas by
 * `CHECKBOX_WIDTH - CHECKBOX_HEIGHT`, which is 0 because both are 24.dp, so the geometry is the same
 * either way and no direction handling is needed here.
 */
class WearCheckboxButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WearSelectionView(
    context,
    attrs,
    defStyleAttr,
    CheckboxButtonCanvasSizeDp,
    CheckboxButtonCanvasSizeDp,
) {

    private val tickPath = Path()
    private val box = RectF()

    /** Scratch output of [rotate], which runs once per tick vertex. */
    private val rotated = FloatArray(2)

    /** Which branch of `animateTick` is live: upstream branches on `checked`, not on `progress`. */
    private var tickChecked = false

    private var tickColor: Color = Color.Transparent

    /**
     * Applies one resolved state. [boxColor] and [checkmarkColor] are what
     * `CheckboxButtonColors.boxColorFor` / `checkmarkColorFor` return for the current
     * `enabled`/`checked` pair, transparent checkmark included.
     */
    fun setCheckboxState(
        checked: Boolean,
        enabled: Boolean,
        boxColor: Color,
        checkmarkColor: Color,
    ) {
        tickChecked = checked
        // `controlColor` is what the base class gates onDraw with; drawBox strokes it.
        controlColor = boxColor
        if (checkmarkColor.alpha > 0f) tickColor = checkmarkColor
        isEnabled = enabled
        animateProgressTo(if (checked) 1f else 0f)
        invalidate()
    }

    override fun drawControl(canvas: Canvas, density: Float, progress: Float) {
        drawBox(canvas, density, controlColor, progress)
        // The transparent<->checkmark colour animation upstream runs in parallel with progress.
        val ink = tickColor.copy(alpha = tickColor.alpha * progress)
        if (ink.alpha <= 0f) return
        if (tickChecked) {
            drawTick(canvas, density, progress, ink)
        } else {
            eraseTick(canvas, density, progress, ink)
        }
    }

    /**
     * `DrawScope.drawBox`: an outlined square fading out under a filled one fading in, both in the
     * same colour, centred so the 18.dp box sits inside the 24.dp canvas.
     */
    private fun drawBox(canvas: Canvas, density: Float, color: Color, progress: Float) {
        val topCornerPx = dp(density, CheckboxButtonCanvasSizeDp - BoxSizeDp) / 2f
        val strokeWidthPx = dp(density, BoxStrokeDp)
        val halfStrokeWidthPx = strokeWidthPx / 2f
        val radiusPx = dp(density, BoxRadiusDp)
        val checkboxSizePx = dp(density, BoxSizeDp)
        // (CHECKBOX_WIDTH - CHECKBOX_HEIGHT), and 0.dp when RTL: both 24.dp, so 0 in either case.
        val startXOffsetPx = 0f

        paint.style = Paint.Style.STROKE
        // The round cap the tick leaves on the shared paint must not reach the box outline.
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = strokeWidthPx
        paint.color = withAlpha(color, 1f - progress)
        val outlineSize = checkboxSizePx - strokeWidthPx
        box.set(
            topCornerPx + halfStrokeWidthPx + startXOffsetPx,
            topCornerPx + halfStrokeWidthPx,
            topCornerPx + halfStrokeWidthPx + startXOffsetPx + outlineSize,
            topCornerPx + halfStrokeWidthPx + outlineSize,
        )
        canvas.drawRoundRect(box, radiusPx - halfStrokeWidthPx, radiusPx - halfStrokeWidthPx, paint)

        paint.style = Paint.Style.FILL
        paint.color = withAlpha(color, progress)
        box.set(
            topCornerPx + startXOffsetPx,
            topCornerPx,
            topCornerPx + startXOffsetPx + checkboxSizePx,
            topCornerPx + checkboxSizePx,
        )
        canvas.drawRoundRect(box, radiusPx, radiusPx, paint)
    }

    /**
     * `AnimateTick.drawTick`: the 2.5.dp base segment grows first, then the 6.dp stick, and the whole
     * mark rotates from 15 degrees toward 0 with a `progress^3` ease, so the angle - and with it the
     * most visible part of the mark - decays late.
     */
    private fun drawTick(canvas: Canvas, density: Float, tickProgress: Float, color: Color) {
        val tickBaseLength = dp(density, TickBaseComponentDp)
        val tickStickLength = dp(density, TickStickComponentDp)
        val tickProgressPx = tickProgress * (tickBaseLength + tickStickLength)
        val centerXPx = dp(density, TickDesignCenterDp)
        val centerYPx = dp(density, TickDesignCenterDp)

        val normalizedProgress = tickProgress.coerceIn(0f, 1f)
        val rotationEasedProgress = normalizedProgress.pow(3)
        val angleDegrees = TickRotationDegrees * (1f - rotationEasedProgress)
        // `Dp.toRadians()` is Compose's own helper; spelled out the way WearSelectionViews does.
        val angleRadians = Math.toRadians(angleDegrees.toDouble()).toFloat()
        val cosA = cos(angleRadians)
        val sinA = sin(angleRadians)

        val baseStartX = dp(density, TickBaseStartXDp)
        val baseStartY = dp(density, TickBaseStartYDp)
        val tickBaseProgress = min(tickProgressPx, tickBaseLength)

        tickPath.reset()
        rotate(baseStartX, baseStartY, centerXPx, centerYPx, cosA, sinA)
        tickPath.moveTo(rotated[0], rotated[1])
        rotate(
            baseStartX + tickBaseProgress,
            baseStartY + tickBaseProgress,
            centerXPx,
            centerYPx,
            cosA,
            sinA,
        )
        tickPath.lineTo(rotated[0], rotated[1])

        if (tickProgressPx > tickBaseLength) {
            val tickStickProgress = min(tickProgressPx - tickBaseLength, tickStickLength)
            val stickStartX = dp(density, TickStickStartXDp)
            val stickStartY = dp(density, TickStickStartYDp)
            rotate(stickStartX, stickStartY, centerXPx, centerYPx, cosA, sinA)
            tickPath.moveTo(rotated[0], rotated[1])
            rotate(
                stickStartX + tickStickProgress,
                stickStartY - tickStickProgress,
                centerXPx,
                centerYPx,
                cosA,
                sinA,
            )
            tickPath.lineTo(rotated[0], rotated[1])
        }

        strokeTick(canvas, density, color)
    }

    /**
     * `AnimateTick.eraseTick`: unchecked runs the mark back into itself from its tip, without the
     * rotation the draw branch uses.
     */
    private fun eraseTick(canvas: Canvas, density: Float, tickProgress: Float, color: Color) {
        val tickBaseLength = dp(density, TickBaseComponentDp)
        val tickStickLength = dp(density, TickStickComponentDp)
        val tickProgressPx = tickProgress * (tickBaseLength + tickStickLength)
        val stickStartX = dp(density, EraseStickStartXDp)
        val stickStartY = dp(density, EraseStickStartYDp)
        val tickStickProgress = min(tickProgressPx, tickStickLength)

        tickPath.reset()
        tickPath.moveTo(stickStartX, stickStartY)
        tickPath.lineTo(stickStartX - tickStickProgress, stickStartY + tickStickProgress)

        // Upstream compares the already clamped `tickStickProgress` here, so this leg never draws;
        // ported as written rather than repaired to the apparently intended `tickProgressPx` test.
        if (tickStickProgress > tickStickLength) {
            val tickBaseProgress = min(tickProgressPx - tickStickLength, tickBaseLength)
            val baseStartX = dp(density, EraseBaseStartXDp)
            val baseStartY = dp(density, EraseBaseStartYDp)
            tickPath.moveTo(baseStartX, baseStartY)
            tickPath.lineTo(baseStartX - tickBaseProgress, baseStartY - tickBaseProgress)
        }

        strokeTick(canvas, density, color)
    }

    private fun strokeTick(canvas: Canvas, density: Float, color: Color) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(density, TickStrokeWidthDp)
        paint.color = color.toArgb()
        // Upstream: `blendMode = if (enabled) DefaultBlendMode else BlendMode.Hardlight`. Views only
        // gained Paint#setBlendMode in API 29, so a disabled tick stays src-over below that.
        val hardlight = !isEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        if (hardlight) paint.blendMode = BlendMode.HARD_LIGHT
        canvas.drawPath(tickPath, paint)
        if (hardlight) paint.blendMode = BlendMode.SRC_OVER
    }

    /** `Offset.rotate(angleRadians, center)` of AnimateTick, spelled out for a `Path`. */
    private fun rotate(x: Float, y: Float, centerX: Float, centerY: Float, cosA: Float, sinA: Float) {
        val dx = x - centerX
        val dy = y - centerY
        rotated[0] = centerX + dx * cosA - dy * sinA
        rotated[1] = centerY + dx * sinA + dy * cosA
    }

    private fun withAlpha(color: Color, factor: Float): Int =
        color.copy(alpha = (color.alpha * factor).coerceIn(0f, 1f)).toArgb()

    private companion object {
        // CheckboxButton.kt: BOX_SIZE / BOX_STROKE / BOX_RADIUS.
        const val BoxSizeDp = 18f
        const val BoxStrokeDp = 2f
        const val BoxRadiusDp = 2f

        // AnimateTick.kt. The COMPONENT constants are the equal horizontal and vertical projections
        // of the 45-degree segments, whose Euclidean length is sqrt(2) * component.
        const val TickBaseComponentDp = 2.5f
        const val TickStickComponentDp = 6f
        const val TickBaseStartXDp = 7.4f
        const val TickBaseStartYDp = 13.0f
        const val TickStickStartXDp = 10.5f
        const val TickStickStartYDp = 15.1f
        const val TickDesignCenterDp = 12f
        const val TickStrokeWidthDp = 2f
        const val TickRotationDegrees = 15f
        const val EraseStickStartXDp = 16.5f
        const val EraseStickStartYDp = 9.0f
        const val EraseBaseStartXDp = 10.0f
        const val EraseBaseStartYDp = 15.6f
    }
}

/**
 * The row both checkbox buttons are built on, standing in for
 * `Row(Modifier.width(IntrinsicSize.Max).defaultMinSize(minHeight = 52.dp))` with a
 * `Column(Modifier.weight(1f))` in the middle - and, for the split variant, two `fillMaxHeight()`
 * sections inside `height(IntrinsicSize.Min)`.
 *
 * `LinearLayout` gets neither of those right, so they live here:
 *  - **shrink to fit.** A `wrap_content` row never hands its deficit to a weighted child, so a long
 *    label pushes the row past its parent instead of wrapping. The weighted child is measured again
 *    against the room that is actually left, which is what `IntrinsicSize.Max` + `weight(1f)` mean.
 *  - **`fillMaxHeight`.** A `wrap_content` row cannot pass a usable height down either: measured
 *    against `AT_MOST`, a `match_parent` child would inflate the row to the full available height.
 *    Such children are measured on their own content first and stretched to the row's height
 *    afterwards, which is `IntrinsicSize.Min` + `fillMaxHeight()`.
 *
 * [View.setMinimumHeight] is honoured in the measure pass as well, because `LinearLayout`'s
 * `wrap_content` result ignores it and `MIN_HEIGHT` would otherwise be dropped.
 */
class WearCheckboxButtonRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        orientation = LinearLayout.HORIZONTAL
        // Compose's Row has no baseline pass; baseline alignment would slide the 24.dp icon and the
        // 24.dp control down to the label's baseline instead of centring them.
        setBaselineAligned(false)
    }

    override fun measureChildWithMargins(
        child: View,
        parentWidthMeasureSpec: Int,
        widthUsed: Int,
        parentHeightMeasureSpec: Int,
        heightUsed: Int,
    ) {
        val lp = child.layoutParams
        val stretchy = lp != null && lp.height == ViewGroup.LayoutParams.MATCH_PARENT
        if (stretchy && View.MeasureSpec.getMode(parentHeightMeasureSpec) == View.MeasureSpec.AT_MOST) {
            super.measureChildWithMargins(
                child,
                parentWidthMeasureSpec,
                widthUsed,
                View.MeasureSpec.UNSPECIFIED,
                0,
            )
        } else {
            super.measureChildWithMargins(
                child,
                parentWidthMeasureSpec,
                widthUsed,
                parentHeightMeasureSpec,
                heightUsed,
            )
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val horizontalPadding = paddingLeft + paddingRight
        var width = measuredWidth
        var height = measuredHeight

        val heightMode = View.MeasureSpec.getMode(heightMeasureSpec)
        if (minimumHeight > height && heightMode != View.MeasureSpec.EXACTLY) {
            height = if (heightMode == View.MeasureSpec.AT_MOST) {
                min(minimumHeight, View.MeasureSpec.getSize(heightMeasureSpec))
            } else {
                minimumHeight
            }
        }

        val available = View.MeasureSpec.getSize(widthMeasureSpec) - horizontalPadding
        val children = width - horizontalPadding
        if (View.MeasureSpec.getMode(widthMeasureSpec) != View.MeasureSpec.UNSPECIFIED &&
            available > 0 && children > available
        ) {
            val target = weightedChild()
            if (target != null) {
                val lp = target.layoutParams as LinearLayout.LayoutParams
                val occupied = target.measuredWidth + lp.leftMargin + lp.rightMargin
                val cap = available - (children - occupied)
                if (cap > 0) {
                    measureChildWithMargins(
                        target,
                        View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.EXACTLY),
                        0,
                        heightMeasureSpec,
                        0,
                    )
                    val newOccupied = target.measuredWidth + lp.leftMargin + lp.rightMargin
                    width = horizontalPadding + (children - occupied) + newOccupied
                    height = max(height, target.measuredHeight + paddingTop + paddingBottom)
                }
            }
        }

        setMeasuredDimension(resolveSize(width, widthMeasureSpec), height)

        val contentHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            if (lp.height == ViewGroup.LayoutParams.MATCH_PARENT && child.measuredHeight != contentHeight) {
                measureChildWithMargins(
                    child,
                    widthMeasureSpec,
                    0,
                    View.MeasureSpec.makeMeasureSpec(contentHeight, View.MeasureSpec.EXACTLY),
                    0,
                )
            }
        }
    }

    /** The child upstream gave `Modifier.weight(1f)`: the labels column, or the label section. */
    private fun weightedChild(): View? {
        var best: View? = null
        var bestWeight = 0f
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            if (lp.weight > bestWeight) {
                bestWeight = lp.weight
                best = child
            }
        }
        return best
    }
}
