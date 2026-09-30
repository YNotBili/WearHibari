package com.huanli233.hibari.wear.attributes

import android.os.Build
import android.widget.TextView
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.thenUnitViewAttribute
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.text.createTypeface
import com.huanli233.hibari.ui.text.fontVariationSettings
import com.huanli233.hibari.ui.text.toAndroidStyle
import com.huanli233.hibari.ui.unit.TextUnit
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.wear.ContainerDrawable
import com.huanli233.hibari.wear.ContainerSpec

/**
 * Paint [spec] as the view background, reusing the existing [ContainerDrawable] when a view is
 * retuned with a changed spec so the background callback and padding survive.
 */
fun Modifier.container(spec: ContainerSpec): Modifier =
    this.thenViewAttribute<android.view.View, ContainerSpec>(uniqueKey, spec) {
        val density = resources.displayMetrics.density
        // The spec is immutable, and a change to it may swap the pressed/disabled variants too, so
        // rebuild instead of mutate. Comparing against the live spec keeps a no-change retune free.
        if ((background as? ContainerDrawable)?.spec != it) {
            // The outgoing drawable may be mid-fade; its ValueAnimator only stops on its own, and a
            // pressed-then-rethemined container would otherwise keep invalidating a drawable that is
            // no longer anyone's background.
            (background as? ContainerDrawable)?.discard()
            background = if (it.isEmpty) null else ContainerDrawable(it, density)
        }
    }

/**
 * The Views equivalent of Compose's `minimumInteractiveComponentSize`: `wrap_content` children
 * honour [sizePx] through `minimumWidth`/`minimumHeight` instead of measure constraints.
 */
fun Modifier.minimumInteractiveComponentSize(sizePx: Int): Modifier =
    this.thenUnitViewAttribute<android.view.View>(uniqueKey) {
        minimumWidth = sizePx
        minimumHeight = sizePx
    }

/**
 * Apply a [TextStyle] to a `TextView`.
 *
 * `TextView.letterSpacing` is a multiple of the glyph advance, not an absolute length, so the sp
 * tracking from the type scale is divided by the font size to recover em. Line height is expressed
 * as uniform line spacing because `TextView` has no absolute line-height API before API 28.
 */
fun Modifier.textStyle(style: TextStyle): Modifier =
    this.thenViewAttribute<TextView, TextStyle>(uniqueKey, style) {
        val sizeSp = if (it.fontSize.isSp) it.fontSize.value else null
        sizeSp?.let { sp -> textSize = sp }

        val tracking = it.letterSpacing
        if (tracking != TextUnit.Unspecified && sizeSp != null && sizeSp > 0f) {
            letterSpacing = tracking.value / sizeSp
        }

        if (it.lineHeight.isSp && sizeSp != null && sizeSp > 0f) {
            // scaledDensity is deprecated; this is exactly what it computed, spelled out.
            val spToPx = resources.displayMetrics.density * resources.configuration.fontScale
            val extra = (it.lineHeight.value - sizeSp) * spToPx
            setLineSpacing(extra.coerceAtLeast(0f), 1f)
        }

        it.fontFamily.typeface(it.fontWeight.toAndroidStyle())?.let { tf ->
            typeface = tf
        }
        if (Build.VERSION.SDK_INT >= 28) {
            typeface = it.fontWeight.createTypeface(typeface)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            fontVariationSettings = it.fontWeight.fontVariationSettings(it.widthAxis)
            it.fontFeatureSettings?.let { features -> fontFeatureSettings = features }
        }
    }

fun Modifier.textColor(color: Color): Modifier =
    this.thenViewAttribute<TextView, Color>(uniqueKey, color) { setTextColor(it.toArgb()) }

/** Convenience for the many components whose content colour is only applied when specified. */
fun Modifier.textColorIfSpecified(color: Color): Modifier =
    if (color.isSpecified) textColor(color) else this
