package com.huanli233.hibari.wear.view

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import com.google.android.material.textview.MaterialTextView
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.text.createTypeface
import com.huanli233.hibari.ui.text.fontVariationSettings
import com.huanli233.hibari.ui.text.toAndroidStyle

/**
 * Everything [WearTimePickerOptionView] has to be told about a glyph run, resolved outside the view
 * so the attribute can be compared: `Attribute` equality drives the diff, and a spec rebuilt with
 * the same numbers must not relayout a column.
 *
 * [baselineOffsetPx] is upstream's `optionBaseline` (`material3/TimePicker.kt:946-1144`): the number
 * of pixels below the top of the option box at which the first text baseline has to sit. The label
 * leaves it at [NoBaselineShift], because upstream does not baseline-place the label.
 */
internal data class TimePickerTextSpec(
    val style: TextStyle,
    val colorArgb: Int,
    val baselineOffsetPx: Int = NoBaselineShift,
    val maxLines: Int = 1,
) {
    internal companion object {
        const val NoBaselineShift = -1
    }
}

/**
 * The one `TextView` [com.huanli233.hibari.wear.TimePicker] draws with, for a column option, a
 * separator or the heading. It exists for two things a themed `TextView` will not do:
 *
 *  - **Font-scale-independent type.** Upstream wraps the whole heading-plus-columns block in
 *    `FontScaleIndependent` (`material3/FontScaleIndependent.kt:25-32`), i.e.
 *    `LocalDensity provides Density(density, fontScale = 1f)`. A `TextView` sizes `sp` through
 *    `scaledDensity`, so the only way to get the same answer is to hand it a pixel size computed from
 *    density alone — which is what [timePickerTextSpec] does. That is why this is a view rather than
 *    [com.huanli233.hibari.wear.attributes.textStyle], which correctly honours the wearer's font
 *    scale everywhere else in the module.
 *
 *    Consolidation candidate: `FontScaleIndependent.kt` landed in parallel with this view and now
 *    exposes `Modifier.fontScaleIndependentTextStyle(style, spToPx)` for the same job. The type half
 *    of [timePickerTextSpec] is that attribute's code, kept inside the view because this class owns
 *    the baseline placement too and because depending on a file that was being written while this one
 *    was compiled is not worth the coupling; one of the two should go in the integration pass.
 *  - **Baseline placement.** `pickerTextOption` (`material3/Picker.kt:659-698`) and `Separator`
 *    (`material3/TimePicker.kt:1146-1182`) both end in
 *    `layout { placeRelative(y = optionBaseline - baseline) }`, which lines the digits of every
 *    column — and the colon between them — up on one baseline inside a box whose height is the
 *    coerced `optionHeight`. [onMeasure] reaches the same place by top padding, because
 *    `baseline` moves exactly with `paddingTop`: one pass lands on the target.
 *
 * Deviation, and it is only reachable when the measured text is taller than the height cap (upstream's
 * own comment calls that "the edge case where the measured text is TALLER than the maximum allowed
 * component height", `material3/TimePicker.kt:1116-1129`): upstream then shifts the glyph run *up*
 * inside the clipped box, which a fixed-height `TextView` cannot do without drawing outside itself,
 * where the row would clip it. There the padding is clamped at zero, so the text keeps its natural
 * top and the row shows it as high as it can.
 */
class WearTimePickerOptionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : MaterialTextView(context, attrs, defStyleAttr) {

    internal var timePickerTextSpec: TimePickerTextSpec? = null
        set(value) {
            if (field == value) return
            field = value
            applyTimePickerTextSpec(value)
        }

    private fun applyTimePickerTextSpec(spec: TimePickerTextSpec?) {
        val resolved = spec ?: return
        val style = resolved.style
        if (style.fontSize.isSp) {
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                style.fontSize.value * resources.displayMetrics.density,
            )
        }
        val tracking = style.letterSpacing
        if (tracking.isSp && style.fontSize.isSp && style.fontSize.value > 0f) {
            // `TextView.letterSpacing` is a multiple of the glyph advance, so the sp tracking from
            // the type scale has to be divided back out to em — the same conversion
            // `Modifier.textStyle` does, minus the scale it cannot skip.
            letterSpacing = tracking.value / style.fontSize.value
        }
        var face: Typeface? = style.fontFamily.typeface(style.fontWeight.toAndroidStyle())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            face = style.fontWeight.createTypeface(face)
        }
        face?.let { typeface = it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            style.fontWeight.fontVariationSettings(style.widthAxis)?.let { fontVariationSettings = it }
            style.fontFeatureSettings?.let { fontFeatureSettings = it }
        }
        setTextColor(resolved.colorArgb)
        // Upstream's option pair is `maxLines = 1, softWrap = false`, and `softWrap = false` is a
        // horizontal-scrolling TextView: without it a run wider than its column would wrap and the
        // second line would simply be gone. The label keeps wrapping, as upstream's `FadeLabel` does.
        setHorizontallyScrolling(resolved.maxLines == 1)
        maxLines = resolved.maxLines
        includeFontPadding = true
        // `style.lineHeight` is deliberately not applied: every run this view draws is a single line
        // inside a box whose height is already fixed upstream, and the option and the separator are
        // positioned by [baselineOffsetPx] rather than by the line box.
        // Upstream centres the run horizontally with `placeRelative(x = (max - width) / 2)` and puts
        // it vertically by baseline alone, so the box is measured from its own top.
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val target = timePickerTextSpec?.baselineOffsetPx ?: return
        if (target < 0) return
        val current = baseline
        if (current <= 0) return
        val wanted = (paddingTop + target - current).coerceAtLeast(0)
        if (wanted != paddingTop) {
            setPadding(paddingLeft, wanted, paddingRight, paddingBottom)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
