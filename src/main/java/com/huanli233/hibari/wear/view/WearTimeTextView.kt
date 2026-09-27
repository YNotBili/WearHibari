package com.huanli233.hibari.wear.view

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.text.format.DateFormat
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Curved text that keeps itself current with the wall clock.
 *
 * Mirrors `TimeTextDefaults.rememberTimeSource(timeFormat())` in
 * androidx.wear.compose.material3.TimeText: it listens for `ACTION_TIME_TICK`,
 * `ACTION_TIME_CHANGED` and `ACTION_TIMEZONE_CHANGED` and re-formats, rather than polling. A
 * receiver-based clock also survives a backgrounded composition, which a `delay` loop would not.
 */
class WearTimeTextView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WearCurvedTextView(context, attrs, defStyleAttr) {

    private var receiver: BroadcastReceiver? = null

    /** Set while 12-hour formatting is active, matching upstream's stripping of the am/pm marker. */
    private var pattern: String = "HH:mm"

    var is24HourFormat: Boolean = DateFormat.is24HourFormat(context)
        set(value) {
            field = value
            pattern = buildPattern(value)
            updateText()
        }

    private fun buildPattern(is24Hour: Boolean): String {
        val skeleton = if (is24Hour) "HH:mm" else "h:mm"
        return DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
            .replace("a", "")
            .trim()
    }

    private fun updateText() {
        text = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
    }

    /**
     * Upstream `TimeText` is its own `CurvedLayout` (material3/TimeText.kt:115-117), and a
     * `CurvedLayout` measures to `diameter = min(maxWidth, maxHeight)` then lays out
     * `layout(diameter, diameter)` (foundation/CurvedLayout.kt:122-127, :166). The inherited
     * `resolveSize` measure instead lets an `EXACTLY` height spec stretch the box, which puts the
     * anchor-270 baseline in the middle of a tall non-square screen rather than along its top; on a
     * square watch the two agree, which is why this only shows up off-device.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val height = View.MeasureSpec.getSize(heightMeasureSpec)
        val diameter = min(width, height).let { if (it > 0) it else max(width, height) }
        setMeasuredDimension(diameter, diameter)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pattern = buildPattern(is24HourFormat)
        updateText()
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateText()
            }
        }.also {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(it, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(it, filter)
            }
        }
    }

    override fun onDetachedFromWindow() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        super.onDetachedFromWindow()
    }
}
