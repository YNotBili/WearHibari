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
 * @param style Defaults to null rather than `currentTextStyle()` because a `@Tunable` default
 *   expression is hoisted into a non-`@Tunable` `$default` method, which cannot read
 *   `LocalTextStyle`; the resolution happens in the body.
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
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle? = null,
) {
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
            .run { if (textAlign != null) textAlignAttr(textAlign) else this }
            .minLines(minLines)
            .maxLines(maxLines)
            .run { if (overflow == TextOverflow.Ellipsis) ellipsis(TextTruncateAt.END) else this }
    )
}

enum class TextOverflow { Clip, Ellipsis, Visible }

private fun TextUnit.takeIfSpecified(): TextUnit? =
    if (this != TextUnit.Unspecified) this else null

/** Re-export so callers do not need a second import for the weight literals. */
typealias FontWeight = com.huanli233.hibari.ui.text.FontWeight
