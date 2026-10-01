package com.huanli233.hibari.wear

import com.google.android.material.textview.MaterialTextView
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.text.TextAlign
import com.huanli233.hibari.ui.text.TextTruncateAt
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.unit.TextUnit
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.foundation.attributes.ellipsis
import com.huanli233.hibari.foundation.attributes.maxLines
import com.huanli233.hibari.foundation.attributes.minLines
import com.huanli233.hibari.foundation.attributes.text
import com.huanli233.hibari.foundation.attributes.textAlign as textAlignAttr
import com.huanli233.hibari.wear.attributes.textColorIfSpecified
import com.huanli233.hibari.wear.attributes.textStyle

/**
 * Ported from androidx.wear.compose.material3.Text.
 *
 * `onTextLayout` is dropped: it hands back a Compose `TextLayoutResult`, which has no Views
 * analogue. `softWrap` maps to a bounded line count rather than a wrap strategy.
 *
 * Upstream resolves the colour as `color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }`
 * (material3/Text.kt:207). The middle term is gone here because Hibari's [TextStyle] carries no
 * colour at all — a `TextView`'s colour is a view property, not a span — so it collapses to
 * `color` then `LocalContentColor`. If both are unspecified no colour is applied and the `TextView`
 * keeps its XML theme default, which is a deviation rather than upstream's behaviour: upstream's
 * `LocalContentColor` is seeded with `Color.White` (material3/ContentColor.kt:34), while
 * [LocalContentColor] here is seeded with `Color.Unspecified` because a `TextView` already carries a
 * colour before anything is provided, and claiming white would fight it. The containers that must not
 * rely on the seed — [Card], [ListHeader], the scaffold family — provide the colour explicitly
 * instead of leaving this component to guess at it.
 *
 * [textAlign], [overflow] and [maxLines] fall back to [LocalTextConfiguration] when the caller
 * leaves them unset, as upstream's defaults do
 * (`material3/Text.kt:103`/`:105`/`:107` — `LocalTextConfiguration.current.textAlign`,
 * `.overflow`, `.maxLines`). They are spelled as nullable sentinels rather than as that expression
 * directly because a `@Tunable` default-parameter expression is hoisted into the non-`@Tunable`
 * `$default` method, which has no tuner to read a `TunationLocal` with; the fallback is applied in
 * the body instead. The unset default the local seeds with is `TextConfigurationDefaults` — `null`
 * alignment, `Clip`, `Int.MAX_VALUE` lines — i.e. the values this signature used to hard-code, so a
 * call outside any provider behaves exactly as before.
 *
 * @param style Defaults to null rather than `currentTextStyle()` because a `@Tunable` default
 *   expression is hoisted into a non-`@Tunable` `$default` method, which cannot read
 *   `LocalTextStyle`; the resolution happens in the body.
 * @param overflow Only [TextOverflow.Ellipsis] needs its own wiring — `Modifier.ellipsis`
 *   (`foundation/TextViewAttributes.kt:89-102`) sets `TextView.ellipsize` to `TruncateAt.END`.
 *   [TextOverflow.Clip] is what a `TextView` with no `ellipsize` already does at the [maxLines]
 *   bound, so it asks for nothing. [TextOverflow.Visible] is the one the engine cannot follow: a
 *   `TextView` never paints outside the bounds it was measured to, so it renders exactly as
 *   [TextOverflow.Clip] — upstream asks for it at `material3/Picker.kt:686` and
 *   `material3/FadingExpandingLabel.kt:189`.
 */
@Tunable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int? = null,
    minLines: Int = 1,
    overflow: TextOverflow? = null,
    style: TextStyle? = null,
) {
    // One read per parameter the caller left unset, as upstream's default expressions are
    // (`material3/Text.kt:103`/`:105`/`:107`): a read subscribes this body to the local, so a `Text`
    // handed all three never subscribes to it.
    val resolvedTextAlign = textAlign ?: LocalTextConfiguration.current.textAlign
    val resolvedMaxLines = maxLines ?: LocalTextConfiguration.current.maxLines
    val resolvedOverflow = overflow ?: LocalTextConfiguration.current.overflow
    val ambient = LocalContentColor.current
    val resolvedColor = color.takeOrElse { ambient }
    val base = style ?: currentTextStyle()
    val merged = base.copy(
        fontSize = fontSize.takeIfSpecified() ?: base.fontSize,
        letterSpacing = letterSpacing.takeIfSpecified() ?: base.letterSpacing,
        lineHeight = lineHeight.takeIfSpecified() ?: base.lineHeight,
        fontWeight = fontWeight ?: base.fontWeight,
    )

    Node(
        modifier = modifier
            .viewClass(MaterialTextView::class.java)
            .text(text)
            .textStyle(merged)
            .textColorIfSpecified(resolvedColor)
            .run { if (resolvedTextAlign != null) textAlignAttr(resolvedTextAlign) else this }
            .minLines(minLines)
            .maxLines(resolvedMaxLines)
            .run { if (resolvedOverflow == TextOverflow.Ellipsis) ellipsis(TextTruncateAt.END) else this }
    )
}

enum class TextOverflow { Clip, Ellipsis, Visible }

private fun TextUnit.takeIfSpecified(): TextUnit? =
    if (this != TextUnit.Unspecified) this else null

/** Re-export so callers do not need a second import for the weight literals. */
typealias FontWeight = com.huanli233.hibari.ui.text.FontWeight
