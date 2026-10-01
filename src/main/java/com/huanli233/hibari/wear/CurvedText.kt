package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.TextUnit
import com.huanli233.hibari.ui.unit.toPx
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
    /** `TimeTextDefaults.MaxSweepAngle` (`material3/TimeText.kt:148`). */
    const val MaxSweepAngle = 70f

    /**
     * `TimeTextDefaults.Padding` (`material3/TimeText.kt:134`, private there): `PaddingDefaults.edgePadding`,
     * a flat 2.dp (`material3/Padding.kt:63`).
     */
    private val Padding: Dp = PaddingDefaults.edgePadding

    /**
     * `TimeTextDefaults.ContentPadding` (`material3/TimeText.kt:151`): `PaddingValues(top = Padding)`.
     *
     * Upstream turns it into arc padding with `calculateOuterPadding() = calculateTopPadding()`
     * (`material3/TimeText.kt:281-288`), i.e. the *top* value is the radial offset from the screen
     * edge, and [WearCurvedTextView.outerPaddingPx] is exactly that offset — so [TimeText] feeds one
     * into the other rather than inventing a default.
     */
    val ContentPadding: PaddingValues = PaddingValues(top = Padding)

    /**
     * `TimeTextDefaults.backgroundColor()` (`material3/TimeText.kt:203`), which is itself
     * `CurvedTextDefaults.backgroundColor()` (`material3/CurvedText.kt:169-171`): the screen background
     * at 85% alpha.
     */
    @Tunable
    fun backgroundColor(): Color =
        MaterialTheme.colorScheme.background.let { it.copy(alpha = it.alpha * 0.85f) }
}

/**
 * Ported from androidx.wear.compose.material3.TimeText (`material3/TimeText.kt:106-129`).
 *
 * Upstream is `CurvedLayout { curvedRow(curvedModifier.sizeIn(maxSweepDegrees = maxSweepAngle)
 * .padding(contentPadding.toArcPadding()).background(backgroundColor, StrokeCap.Round),
 * radialAlignment = Center) { content(timeSource.currentTime()) } }`. This port renders that same
 * tree as one [WearTimeTextView] — a curved label that paints its own arc band — because the
 * container path cannot do two of those four jobs:
 *  - `curvedModifier` (`:108`, applied to the arc at `:120`): the `CurvedModifier.background` family
 *    is not ported, because a node with a measure policy is handed `Renderer`'s internal
 *    `LayoutNodeHost`, which paints nothing and cannot be subclassed from here
 *    (`Renderer.kt:98-103`, recorded at `CurvedLayout.kt:64-69`). A band behind curved children
 *    therefore needs either that host or this view.
 *  - `content: CurvedScope.(String) -> Unit` (`:113`, default `timeTextCurvedText(time)`): the same
 *    blocker, plus the layout's own [modifier] never reaching a measure-policy node's `LayoutParams`
 *    (`CurvedLayout.kt:70-78`). Taking the slot without a container that can stroke the background
 *    would drop the band on the default path, so the slot is not exposed and the clock text upstream
 *    draws there is what [WearTimeTextView] draws here. The two scope functions upstream's content
 *    lambdas are built from, `CurvedScope.timeTextCurvedText` (`:212-217`) and
 *    `CurvedScope.timeTextSeparator` (`:225-235`), are *not* blocked in the same way — in isolation
 *    each needs only `CurvedLayoutScope.curvedText` (`CurvedLayout.kt:362`) or
 *    `Modifier.curvedPadding` (`attributes/CurvedAttributes.kt:121-136`, whose `ArcPaddingValues`
 *    parameter is ported), and both of those exist — but with no slot to hand them to they would be
 *    public API nothing can call, so they are left for the pass that lands `content`.
 *    Their `clearAndSetSemantics {}` (`:214`, `:233`) has no counterpart either way.
 *  - `curvedModifier`'s `sizeIn(maxSweepDegrees = maxSweepAngle)` (`:121`) is not a loss: that is
 *    precisely what [maxSweepAngle] below feeds into `CurvedTextSpec.maxSweepDegrees`.
 *
 * [contentColor] stands in for the style upstream passes to `timeTextCurvedText` — with no `content`
 * slot there is no other route to the glyph colour — and defaults to `timeTextStyle().color`, which
 * *is* upstream's expression (`MaterialTheme.colorScheme.onBackground.setLuminance(80f)`,
 * `material3/TimeText.kt:176`, reached through the ported [timeTextStyle] at `:173-181`).
 *
 * @param backgroundColor null resolves [TimeTextDefaults.backgroundColor], and `timeSource` null
 *   resolves `rememberTimeSource(timeFormat())`, both because a `@Tunable` default expression is
 *   hoisted into a non-`@Tunable` `$default` method that cannot read the theme or `remember`.
 * @param timeSource upstream's parameter of the same name (`:111`), whose default
 *   `TimeTextDefaults.rememberTimeSource(timeFormat())` is spelled here as [rememberTimeSource] and
 *   [timeFormat] because this module's `TimeTextDefaults` lives in another file. When null, the
 *   system-clock source ticks through the `ACTION_TIME_TICK` receiver `remember`ed by [currentTime],
 *   and because the tune body reads the resulting state every minute, `timeFormat()` is re-derived
 *   then too — which is how a 12/24-hour setting change reaches the label, exactly as a recomposition
 *   is how it reaches upstream's.
 * @param contentPadding null resolves [TimeTextDefaults.ContentPadding]; only its top (outer) value
 *   is honoured, as upstream's `toArcPadding()` uses `calculateTopPadding()` for `outer` and
 *   `calculateBottomPadding()` for `inner` (`material3/TimeText.kt:282-287`), and upstream's own
 *   default sets nothing but the top.
 */
@Tunable
fun TimeText(
    modifier: Modifier = Modifier,
    maxSweepAngle: Float = TimeTextDefaults.MaxSweepAngle,
    backgroundColor: Color? = null,
    timeSource: TimeSource? = null,
    contentPadding: PaddingValues? = null,
    contentColor: Color? = null,
) {
    val arcColor = backgroundColor ?: TimeTextDefaults.backgroundColor()
    val textStyle = timeTextStyle()
    val resolvedColor = contentColor ?: textStyle.color
    val source = timeSource ?: rememberTimeSource(timeFormat())
    val currentTime = source.currentTime()
    val outerPadding = (contentPadding ?: TimeTextDefaults.ContentPadding).calculateTopPadding()
    val arcMedium = MaterialTheme.typography.arcMedium
    val clockStyle = if (textStyle.fontSize != TextUnit.Unspecified) {
        arcMedium.copy(fontSize = textStyle.fontSize)
    } else {
        arcMedium
    }
    Node(
        modifier = modifier
            .viewClass(WearTimeTextView::class.java)
            .curvedText(
                CurvedTextSpec(
                    text = currentTime,
                    style = clockStyle,
                    color = resolvedColor,
                    background = arcColor,
                    maxSweepDegrees = maxSweepAngle,
                    outerPaddingPx = outerPadding.toPx(currentContext).toFloat(),
                )
            ),
    )
}
