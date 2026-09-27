package com.huanli233.hibari.wear.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import kotlin.math.min

/**
 * Ported from `drawLinearIndicator` in androidx.wear.compose.material3.LinearProgressIndicator: a
 * round-capped track, an indicator line inset by half the stroke at both ends so the caps stay
 * inside the view. The leading dot upstream draws while progress nears the end is not ported: it
 * depends on `animateFloat` against `MotionScheme`, which has no Hibari equivalent yet.
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val desiredHeight = (strokeWidth.value * density).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val strokePx = strokeWidth.value * resources.displayMetrics.density
        if (strokePx <= 0f || width <= 0) return
        val capOffset = strokePx / 2
        val y = height / 2f

        paint.strokeWidth = strokePx
        drawLine(canvas, capOffset, width - capOffset, y, trackColor, strokePx)

        // Upstream measures progress against the stroke-shrunken width, matching the track's ends.
        val usable = width - strokePx
        if (usable <= 0f) return
        val progressPx = progress.coerceIn(0f, 1f) * usable
        if (progressPx <= 0f) return
        drawLine(canvas, capOffset, capOffset + progressPx, y, indicatorColor, strokePx)
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
        canvas.drawLine(start, y, end, y, paint)
    }

    override fun getSuggestedMinimumWidth(): Int =
        min(64, resources.displayMetrics.widthPixels / 4)
}
