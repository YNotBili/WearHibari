package com.huanli233.hibari.wear.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet

/**
 * The radio selection control the Wear Material 3 `RadioButton` / `SplitRadioButton` rows put at
 * their end, ported from `materialcore.SelectionControls.RadioButton`: a ring of constant radius
 * (`RADIO_CIRCLE_RADIUS 9`) stroked at `RADIO_CIRCLE_STROKE 2`, with a centre dot whose radius is
 * `progress * RADIO_DOT_RADIUS 5`. Ring and dot share one colour, because material3's `RadioControl`
 * passes the same resolver to `ringColor` and `dotColor`.
 *
 * [WearRadioView] already draws this geometry for the bare control, and upstream pins the control's
 * own draw scope to `CONTROL_WIDTH x CONTROL_HEIGHT` (24 x 24) and centres on it, so its hard-coded
 * (12, 12) dp is the same point as this view's measured centre: this class is a duplicate of
 * [WearRadioView], not a necessary variant. It exists only because that view belongs to
 * `SelectionControls.kt`, which this port may not edit; folding the two together is the
 * orchestrator's call.
 *
 * Deviation, shared with [WearRadioView]: upstream runs the dot radius and - only while unselected,
 * materialcore nulls the alpha animation once checked - the dot alpha as two float animations, but
 * `material3`'s `RadioControl` hands both of them the same `PROGRESS_ANIMATION_SPEC` and both map
 * `Unchecked -> 0f / Checked -> 1f`, so the two floats are always equal and one
 * [WearSelectionView] `progress` carries them both, including the `progress * dotColor.alpha` the
 * dot is drawn with. What is not reproduced is the curve: the base class ramps linearly over a fixed
 * span where upstream springs.
 */
class WearRadioButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WearSelectionView(context, attrs, defStyleAttr, RadioControlSizeDp, RadioControlSizeDp) {

    override fun drawControl(canvas: Canvas, density: Float, progress: Float) {
        val cx = width / 2f
        val cy = height / 2f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(density, RadioRingStrokeDp)
        paint.color = controlColor.toArgb()
        canvas.drawCircle(cx, cy, dp(density, RadioRingRadiusDp), paint)

        val dotRadius = dp(density, RadioDotRadiusDp) * progress
        if (dotRadius > 0f) {
            paint.style = Paint.Style.FILL
            // `radius = dotRadiusProgress * RADIO_DOT_RADIUS` and
            // `alpha = dotAlphaProgress * radioDotColor.alpha`, on one and the same float.
            paint.color = controlColor.copy(alpha = controlColor.alpha * progress).toArgb()
            canvas.drawCircle(cx, cy, dotRadius, paint)
        }
    }
}

private const val RadioControlSizeDp = 24f
private const val RadioRingRadiusDp = 9f
private const val RadioRingStrokeDp = 2f
private const val RadioDotRadiusDp = 5f
