package com.huanli233.hibari.wear.attributes

import android.os.Build
import android.widget.TextView
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
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
 *
 * The floor rides as the attribute's *value*, not in the applier's closure: a unit attribute compares
 * equal on every retune (`ViewAttribute.equals` looks at key, value and `reuseSupported`, never the
 * applier), so a captured `sizePx` would be applied once at view creation and then silently ignore a
 * new density.
 */
fun Modifier.minimumInteractiveComponentSize(sizePx: Int): Modifier =
    this.thenViewAttribute<android.view.View, Int>(uniqueKey, sizePx) {
        minimumWidth = it
        minimumHeight = it
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

/**
 * Convenience for the many components whose content colour is only applied when specified.
 *
 * Deliberately left conditional, unlike the always-present sentinel `Modifier.image` now uses: a
 * `TextView` has no way back from a written colour. `setTextColor` latches the colour as explicitly
 * set, so it survives every later appearance resolution and only a rebuilt view can be colourless
 * again. What a rebuilt one shows is not a constant this module can name either — `Renderer.render`
 * builds a `Text` view through its `(Context)` constructor whenever the chain carries no attrs
 * (`hibari-runtime/.../Renderer.kt:125-126`, `:189-194`), which is `TextView(context, null,
 * textViewStyle)`, so the colour comes from the theme's `textViewStyle`. Always emitting and writing
 * nothing for the unset sentinel would therefore keep the previous tune's colour standing where the
 * rebuild shows the theme's, and always emitting the theme's colour would overwrite it on every
 * `Text` outside a provider. [com.huanli233.hibari.wear.LocalContentColor]'s seed (`Theme.kt:23-29`)
 * documents the same trade. The cost of a colour turning off is one leaf `TextView` recreation, with
 * no subtree below it.
 */
fun Modifier.textColorIfSpecified(color: Color): Modifier =
    if (color.isSpecified) textColor(color) else this
