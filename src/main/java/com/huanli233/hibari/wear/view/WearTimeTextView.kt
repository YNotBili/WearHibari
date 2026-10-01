package com.huanli233.hibari.wear.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * The curved label [com.huanli233.hibari.wear.TimeText] draws: [WearCurvedTextView] plus the square
 * viewport upstream's `CurvedLayout` gives it, and nothing else.
 *
 * It used to also be the clock — its own `ACTION_TIME_TICK`/`ACTION_TIME_CHANGED`/
 * `ACTION_TIMEZONE_CHANGED` receiver, its own `DateFormat.getBestDateTimePattern` call, and its own
 * `SimpleDateFormat`. That is removed here, for three reasons this file can no longer argue against:
 *  - it made upstream's `timeSource` parameter (`material3/TimeText.kt:111`) impossible to honour,
 *    because the text was produced inside the view rather than by the injected source;
 *  - `is24HourFormat` was sampled once, as the default of a property, so a 12/24-hour setting change
 *    never reached the label — while upstream re-derives `timeFormat()` on every recomposition
 *    (`material3/TimeText.kt:157-163`, reached as the default of the same parameter);
 *  - it formatted with `java.text.SimpleDateFormat`, where upstream formats the same pattern with
 *    `android.text.format.DateFormat.format` (`material3/TimeText.kt:327-330`).
 *
 * Both jobs now run through the single mechanism upstream uses: [rememberTimeSource] holds a
 * [DefaultTimeSource] whose [currentTime] state the receiver of
 * [com.huanli233.hibari.wear.TimeText]'s tune body writes each tick, and `formatTime` in
 * `TimeText.kt` is the verbatim port of the call above.
 *
 * The measure override is the one thing left, and it is why this subclass exists at all: upstream's
 * `TimeText` is its own `CurvedLayout` (`material3/TimeText.kt:117`), whose measure policy takes
 * `diameter = min(maxWidth, maxHeight)` and then places `layout(diameter, diameter)`
 * (`foundation/CurvedLayout.kt:125-129`, `:164`). The inherited `resolveSize` measure instead lets an
 * `EXACTLY` height spec stretch the box, which puts the anchor-270 baseline in the middle of a tall
 * non-square screen rather than along its top; on a square watch the two agree, which is why this only
 * shows up off-device.
 */
class WearTimeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WearCurvedTextView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val height = View.MeasureSpec.getSize(heightMeasureSpec)
        val diameter = min(width, height).let { if (it > 0) it else max(width, height) }
        setMeasuredDimension(diameter, diameter)
    }
}
