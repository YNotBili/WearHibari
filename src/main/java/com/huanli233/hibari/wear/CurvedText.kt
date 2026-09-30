package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.unit.TextUnit
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.wear.view.CurvedAnchor
import com.huanli233.hibari.wear.view.CurvedTextOverflow
import com.huanli233.hibari.wear.view.WearCurvedTextView
import com.huanli233.hibari.wear.view.WearTimeTextView
import com.huanli233.hibari.wear.view.WarpOffset

/**
 * The props [WearCurvedTextView] needs, as a comparable value so a retune that changes nothing
 * leaves the view alone.
 */
data class CurvedTextSpec(
    val text: CharSequence,
    val style: TextStyle = TextStyle(),
    val color: Color = Color.Unspecified,
    val background: Color = Color.Transparent,
    val anchorDegrees: Float = WearCurvedTextView.TopAnchor,
    val anchorType: CurvedAnchor = CurvedAnchor.Center,
    val clockwise: Boolean = true,
    val maxSweepDegrees: Float = WearCurvedTextView.DefaultMaxSweepDegrees,
    val warpOffset: WarpOffset = WarpOffset.HalfOpticalHeight,
    val letterSpacing: TextUnit = TextUnit.Unspecified,
    val letterSpacingCounterClockwise: TextUnit = TextUnit.Unspecified,
    val overflow: CurvedTextOverflow = CurvedTextOverflow.Clip,
    val outerPaddingPx: Float = 0f,
)

fun Modifier.curvedText(spec: CurvedTextSpec): Modifier =
    this.thenViewAttribute<WearCurvedTextView, CurvedTextSpec>(uniqueKey, spec) {
        text = it.text
        style = it.style
        textColor = it.color
        backgroundColor = it.background
        anchorDegrees = it.anchorDegrees
        anchorType = it.anchorType
        clockwise = it.clockwise
        maxSweepDegrees = it.maxSweepDegrees
        warpOffset = it.warpOffset
        letterSpacing = it.letterSpacing
        letterSpacingCounterClockwise = it.letterSpacingCounterClockwise
        overflow = it.overflow
        outerPaddingPx = it.outerPaddingPx
    }

/**
 * Ported from androidx.wear.compose.material3.CurvedText / `basicCurvedText`.
 *
 * `CurvedModifier`'s angular padding and `CurvedScope.weight` are not parameters here: they arrive as
 * `Modifier.curvedPadding` / `Modifier.curvedWeight` on [modifier], which is handed straight to the
 * leaf `Node`, so inside a curved container this label reaches its [CurvedPaddingWrapper] and
 * [CurvedParentDataWrapper] like any other leaf. Outside one, nothing consumes them.
 */
@Tunable
fun CurvedText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    background: Color = Color.Transparent,
    fontSize: TextUnit = TextUnit.Unspecified,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    letterSpacingCounterClockwise: TextUnit = TextUnit.Unspecified,
    style: TextStyle = TextStyle(),
    clockwise: Boolean = true,
    maxSweepAngle: Float = WearCurvedTextView.DefaultMaxSweepDegrees,
    overflow: CurvedTextOverflow = CurvedTextOverflow.Clip,
) {
    val resolved = contentColorFor(Color.Transparent, color)
    Node(
        modifier = modifier
            .viewClass(WearCurvedTextView::class.java)
            .curvedText(
                CurvedTextSpec(
                    text = text,
                    // Tracking rides on CurvedTextSpec, not the style: the view picks clockwise vs
                    // counter-clockwise there, which a merged TextStyle could not express.
                    style = style.copy(
                        fontSize = if (fontSize != TextUnit.Unspecified) fontSize else style.fontSize,
                    ),
                    color = resolved,
                    background = background,
                    clockwise = clockwise,
                    maxSweepDegrees = maxSweepAngle,
                    letterSpacing = letterSpacing,
                    letterSpacingCounterClockwise = letterSpacingCounterClockwise,
                    overflow = overflow,
                )
            )
    )
}

object TimeTextDefaults {
    const val MaxSweepAngle = 70f

    /** `TimeTextDefaults.backgroundColor()`: the screen background at 85% alpha. */
    @Tunable
    fun backgroundColor(): Color =
        MaterialTheme.colorScheme.background.let { it.copy(alpha = it.alpha * 0.85f) }

    /**
     * `TimeTextDefaults.contentColor()` (`material3/TimeText.kt:176`): `onBackground` lifted to
     * luminance 80.
     *
     * [setLuminanceTone] and not `com.huanli233.hibari.ui.graphics.setLuminance`, even though that one
     * is the public of the two: upstream's `setLuminance` (material3/DynamicColorScheme.kt:116-125)
     * takes a 0..100 Oklab/Cam tone, which is what [setLuminanceTone] reproduces verbatim, while
     * hibari-ui's takes a 0..1 relative luminance and `coerceIn`s its argument — so `80f` through
     * *that* one clamps to 1f and answers pure white.
     */
    @Tunable
    fun contentColor(): Color = MaterialTheme.colorScheme.onBackground.setLuminanceTone(80f)
}

/**
 * Ported from androidx.wear.compose.material3.TimeText.
 *
 * The clock itself is driven by a `BroadcastReceiver` on `ACTION_TIME_TICK` inside
 * [WearTimeTextView], which is what upstream's `rememberTimeSource` does too.
 *
 * Two upstream slots are absent, for their own reasons rather than [CurvedText]'s:
 *  - `curvedModifier` (material3/TimeText.kt:95, declared :108, applied to the arc at :120). Upstream
 *    keeps the arc-restricting modifier separate from the layout one; there is only [modifier] here,
 *    and `Modifier.curvedPadding` / `Modifier.curvedWeight` can ride on it, so what is missing is the
 *    split, not the capability.
 *  - the separator slot. material3 has `CurvedScope.timeTextSeparator` (`material3/TimeText.kt:220-235`,
 *    a slot-composable with `curvedTextStyle` and `contentArcPadding` parameters) and the v1 line has
 *    `TimeTextDefaults.TextSeparator` / `textCurvedSeparator` (`material/TimeText.kt:57-58`, `:71-73`);
 *    neither ships a `TimeTextScope` with `separatorText`/`separatorIcon` children. This port has no
 *    `TimeTextScope` at all, so [TextSeparator] follows v1's naming and only covers the single-label
 *    form.
 *
 * @param backgroundColor Defaults to `null` and resolves in the body:
 *   [TimeTextDefaults.backgroundColor] reads `MaterialTheme`, and a `@Tunable` default expression is
 *   hoisted into a non-`@Tunable` `$default` method that cannot.
 * @param contentColor Defaults to `null` for the same reason; resolves
 *   [TimeTextDefaults.contentColor].
 */
@Tunable
fun TimeText(
    modifier: Modifier = Modifier,
    maxSweepAngle: Float = TimeTextDefaults.MaxSweepAngle,
    backgroundColor: Color? = null,
    contentColor: Color? = null,
) {
    val resolvedBackground = backgroundColor ?: TimeTextDefaults.backgroundColor()
    val resolvedContent = contentColor ?: TimeTextDefaults.contentColor()
    val arcStyle = MaterialTheme.typography.arcMedium
    Node(
        modifier = modifier
            .viewClass(WearTimeTextView::class.java)
            .thenViewAttribute<WearTimeTextView, Unit>(uniqueKey, Unit) {
                this.textColor = resolvedContent
                this.backgroundColor = resolvedBackground
                this.maxSweepDegrees = maxSweepAngle
                this.anchorType = CurvedAnchor.Center
                this.style = arcStyle
            }
    )
}
