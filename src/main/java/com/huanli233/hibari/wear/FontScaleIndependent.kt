package com.huanli233.hibari.wear

import android.os.Build
import android.util.TypedValue
import android.widget.TextView
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.TunationLocalProvider
import com.huanli233.hibari.runtime.locals.LocalDensity
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.text.createTypeface
import com.huanli233.hibari.ui.text.fontVariationSettings
import com.huanli233.hibari.ui.text.toAndroidStyle
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Density
import com.huanli233.hibari.ui.unit.sp
import com.huanli233.hibari.ui.uniqueKey

/**
 * Ported from `androidx.wear.compose.material3.FontScaleIndependent`
 * (`material3/FontScaleIndependent.kt:24-31`) — the whole of it is
 * `LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f)` around [content], and
 * that line is reproduced literally: Hibari carries the same [Density] factory, the same
 * `TextUnit.toPx()` member extension and the same non-linear font-scaling tables
 * (`hibari-ui/.../unit/fontscaling/FontScaling.kt:43-51`), and `HibariView.runTunable` seeds
 * `LocalDensity` (`hibari-runtime/.../HibariView.kt:50`) the way composition seeds `LocalDensity` in a
 * Compose app.
 *
 * Two upstream callers and what they get from it: `material3/TimePicker.kt:292` and
 * `material3/DatePicker.kt:303` wrap their whole picker body, so every `sp` inside resolves at
 * `fontScale = 1` and a picker column keeps a fixed physical size under a 2x font scale
 * (`material3/TimePicker.kt:983` says so out loud: "We DO NOT need to key on `density.fontScale`
 * because the `FontScaleIndependent`…").
 *
 * Deviations, both declared rather than chosen: upstream is `internal`, public here because the
 * components that need it are separate files of this module written independently of it, and its
 * content slot is `@Tunable`, which is how every content slot in this module is declared.
 *
 * What this wrapper does and does not neutralise on Views — read before assuming a subtree is covered:
 *  - Reached: anything that converts an `sp` size through [currentSpToPx], i.e. through
 *    [LocalDensity]. That is the whole of upstream's mechanism, and it is the only one available here.
 *  - Already covered by construction: this module's self-drawing text surfaces
 *    (`view/WearPickerViews.kt`, `view/WearCurvedTextView.kt:315`, `AnimatedText.kt:406-411`) measure
 *    with `value * density` and never multiply by `Configuration.fontScale`, so they already behave as
 *    if wrapped — which is what `TimePicker.kt:113-117` documents for the picker's own paint.
 *  - Not reached: `Modifier.textStyle` (`attributes/ContainerAttributes.kt:49-76`) and
 *    `Modifier.textSize` (`hibari-foundation/.../attributes/TextViewAttributes.kt:26-35`) hand a *sp
 *    float* to `TextView`, which resolves it through `DisplayMetrics.scaledDensity` on the shared
 *    `Resources` object. A modifier cannot see that multiplication, and neither the view's `Resources`
 *    nor its `DisplayMetrics` may be rewritten from here: `HibariView`'s resources are the host
 *    activity's, so a `scaledDensity` override would scale every `TextView` in the app, would be
 *    reverted by the next `updateConfiguration`, and would fight the density the tuner already
 *    publishes. Those two attributes are existing files this port may not edit, so wrapped `Text(...)`
 *    content stays font-scaled — [Modifier.fontScaleIndependentTextStyle] is the drop-in that is not,
 *    and the clean fix is for `textStyle`/`textSize` to resolve through [LocalDensity], which is a
 *    change to two files this port may not touch.
 *  - Not reached, in all cases: a size that comes from the view's own style or theme
 *    (`android:textSize`, `Modifier.textAppearance`), the framework's minimum-font-scale clamping, and
 *    any dimension the platform scales internally on its own.
 */
@Tunable
fun FontScaleIndependent(content: @Tunable () -> Unit) {
    val density = LocalDensity.current
    TunationLocalProvider(
        LocalDensity provides Density(density.density, fontScale = 1f),
        content = content,
    )
}

/**
 * [fontSizeSp] in px under the ambient [LocalDensity] — upstream's `fontSize.toPx()` inside a
 * `Density(fontScale = 1f)`, which is exactly what `FontScaleIndependent` arranges
 * (`material3/FontScaleIndependent.kt:26-30`). Outside the wrapper it returns the scaled size, so a
 * component can use it unconditionally.
 *
 * Not an upstream member: `FontScaleIndependent` has no API beyond the wrapper, but on Views the
 * wrapper only has an effect on the conversions that go through it, so this is the read side it needs.
 * The argument is an `sp` float and only `sp` converts, exactly as upstream's `TextUnit.toPx()` throws
 * for `em` (`Density.kt:47-50`); an em-relative size has to be resolved to sp by the caller first.
 */
@Tunable
fun currentSpToPx(fontSizeSp: Float): Float =
    with(LocalDensity.current) { fontSizeSp.sp.toPx() }

/**
 * `Modifier.textStyle(style)` (`attributes/ContainerAttributes.kt:49-76`) with the size resolved
 * through [currentSpToPx] instead of `TextView`'s own `scaledDensity`, which is the only way a
 * `TextView` in a [FontScaleIndependent] subtree gets the treatment upstream gives it for free.
 *
 * It is a complete replacement, not a post-pass over the two attributes: an attribute only re-applies
 * when its own value changes (`HibariDiffCallback` compares per key), so a "divide what the previous
 * attribute just wrote by the font scale" version would keep a stale size whenever the style changed
 * without the scale changing. [spToPx] is therefore part of the compared value, and is passed in
 * rather than read from `resources`, so the apply side needs no tuner: resolve it in the `@Tunable`
 * body — inside [FontScaleIndependent] that is `currentSpToPx(1f)`, i.e. plain density.
 *
 * The letterSpacing, typeface, variation-axis and feature-settings half is `ContainerAttributes`'s own
 * code, copied so the two variants agree; only the two `sp` resolutions differ (line spacing was
 * already the odd one out there, computing `density * fontScale` by hand at `:60-63`).
 *
 * @param style Applied verbatim except for how its `fontSize`/`lineHeight` reach px.
 * @param spToPx The px-per-sp factor to use, px included. Pass `currentSpToPx(1f)`.
 */
fun Modifier.fontScaleIndependentTextStyle(style: TextStyle, spToPx: Float): Modifier =
    this.thenViewAttribute<TextView, FontScaleIndependentText>(
        uniqueKey,
        FontScaleIndependentText(style, spToPx),
    ) {
        val sizeSp = if (it.style.fontSize.isSp) it.style.fontSize.value else null
        sizeSp?.let { sp -> setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * it.spToPx) }

        val tracking = it.style.letterSpacing
        if (tracking.isSp && sizeSp != null && sizeSp > 0f) {
            // `TextView.letterSpacing` is a multiple of the glyph advance, so the sp tracking has to
            // go back to em — and em is scale-free on both sides, which is why this line is unchanged.
            letterSpacing = tracking.value / sizeSp
        }

        if (it.style.lineHeight.isSp && sizeSp != null && sizeSp > 0f) {
            val extra = (it.style.lineHeight.value - sizeSp) * it.spToPx
            setLineSpacing(extra.coerceAtLeast(0f), 1f)
        }

        it.style.fontFamily.typeface(it.style.fontWeight.toAndroidStyle())?.let { tf -> typeface = tf }
        if (Build.VERSION.SDK_INT >= 28) {
            typeface = it.style.fontWeight.createTypeface(typeface)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            fontVariationSettings = it.style.fontWeight.fontVariationSettings(it.style.widthAxis)
            it.style.fontFeatureSettings?.let { features -> fontFeatureSettings = features }
        }
    }

/** The compared value behind [Modifier.fontScaleIndependentTextStyle]. */
private data class FontScaleIndependentText(val style: TextStyle, val spToPx: Float)
