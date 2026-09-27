package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.Row
import com.huanli233.hibari.foundation.RowScope
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.view.EdgeButtonGeometry
import com.huanli233.hibari.wear.view.WearEdgeButtonView

/**
 * Ported from androidx.wear.compose.material3.EdgeButton (`material3/EdgeButton.kt:69-221`).
 *
 * The whole of upstream's geometry - the two arcs plus ellipse segment, the content window it
 * leaves, and the two height fades - is in [WearEdgeButtonView], whose KDoc lists the deviations
 * that come from moving off Compose's modifier layers. Two more belong to this call site:
 *
 *  - **`interactionSource` is not a parameter.** Upstream threads it through to the ripple
 *    (`:184-189`); with no indication system there is nothing for it to drive, and a parameter that
 *    changes nothing would be a worse promise than its absence.
 *  - **`TextConfiguration(TextAlign.Center, TextOverflow.Ellipsis, maxLines = buttonSize.maxLines())`
 *    is not provided** (`:208-215`). Hibari has no text-configuration local that [Text] reads - the
 *    same gap [ListHeader] and the button rows document - so the centring and the line budget are
 *    left to the caller's own [Text] rather than silently dropped in a way that looks like a port.
 *
 * @param colors Defaults to `null` and resolves in the body: `ButtonDefaults.buttonColors()` reads
 *   `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable` `$default`
 *   method that cannot.
 * @param buttonSize Which of the four heights to use; [EdgeButtonSize.Small] is upstream's default.
 */
@Tunable
fun EdgeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: EdgeButtonSize = EdgeButtonSize.Small,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    border: BorderStroke? = null,
    content: @Tunable RowScope.() -> Unit,
) {
    val resolved = colors ?: ButtonDefaults.buttonColors()
    // Upstream's `colors.containerColor(enabled = enabled)` and `colors.contentColor(enabled)` are
    // both a plain pick between the enabled and disabled field (`ButtonColors` in the same module).
    val containerColor =
        if (enabled) resolved.containerColor else resolved.disabledContainerColor
    val contentColor = if (enabled) resolved.contentColor else resolved.disabledContentColor
    val scope = content
    Node(
        modifier = modifier
            .viewClass(WearEdgeButtonView::class.java)
            .edgeButtonGeometry(
                EdgeButtonGeometry(
                    maximumHeight = buttonSize.maximumHeight,
                    containerColor = containerColor,
                    border = border,
                    verticalPadding = EdgeButtonVerticalPadding,
                    contentPaddingTop = EdgeButtonContentPaddingTop,
                    contentPaddingBottom = EdgeButtonContentPaddingBottom,
                ),
            ),
    ) {
        provideContentColorAndStyle(contentColor, MaterialTheme.typography.labelMedium) {
            Row { scope() }
        }
    }
}

/**
 * `EdgeButtonVerticalPadding` (`material3/EdgeButton.kt:497`): the gap above and below the button,
 * which is also how far inside the screen circle the shape is measured from.
 */
internal val EdgeButtonVerticalPadding: Dp = 3.dp

/**
 * The content insets of [EdgeButton] (`material3/EdgeButton.kt:verticalContentPadding`, inside
 * `EdgeButtonSize`): `6.dp` above, `8.dp` below. Upstream's comment there says the bottom is
 * deliberately the larger of the two because the shape is not symmetrical.
 */
private val EdgeButtonContentPaddingTop: Dp = 6.dp
private val EdgeButtonContentPaddingBottom: Dp = 8.dp

/**
 * Ported from androidx.wear.compose.material3.EdgeButtonSize (`material3/EdgeButton.kt:222-253`).
 *
 * Upstream's `maximumHeight` is `internal`, as it is here, and its two other internal helpers -
 * `maximumHeightPlusPadding()` (only ever feeding `maxIntrinsicHeight`, which has no Views
 * counterpart) and `maxLines()` (only ever feeding the `TextConfiguration` this module cannot
 * provide) - are not carried over rather than carried over unused.
 */
@JvmInline
value class EdgeButtonSize internal constructor(internal val maximumHeight: Dp) {

    companion object {
        /** The size to be applied for an extra small edge button: 46 dp. */
        val ExtraSmall: EdgeButtonSize = EdgeButtonSize(46.dp)

        /** The size to be applied for a small edge button, upstream's default: 56 dp. */
        val Small: EdgeButtonSize = EdgeButtonSize(56.dp)

        /** The size to be applied for a medium edge button: 70 dp. */
        val Medium: EdgeButtonSize = EdgeButtonSize(70.dp)

        /** The size to be applied for a large edge button: 96 dp. */
        val Large: EdgeButtonSize = EdgeButtonSize(96.dp)
    }
}

/**
 * Ported from androidx.wear.compose.material3.EdgeButtonDefaults (`material3/EdgeButton.kt:254-277`).
 */
object EdgeButtonDefaults {
    /** Recommended icon size with [EdgeButtonSize.ExtraSmall]. */
    val ExtraSmallIconSize: Dp = 24.dp

    /** Recommended icon size with [EdgeButtonSize.Small]. */
    val SmallIconSize: Dp = 32.dp

    /** Recommended icon size with [EdgeButtonSize.Medium]. */
    val MediumIconSize: Dp = 32.dp

    /** Recommended icon size with [EdgeButtonSize.Large]. */
    val LargeIconSize: Dp = 36.dp

    /**
     * Recommended icon size for a given [EdgeButtonSize]. Upstream's `else -> MediumIconSize` arm is
     * kept: the four sizes are `internal` values of a value class, so an instance built from another
     * height still has to answer.
     */
    fun iconSizeFor(edgeButtonSize: EdgeButtonSize): Dp = when (edgeButtonSize) {
        EdgeButtonSize.ExtraSmall -> ExtraSmallIconSize
        EdgeButtonSize.Small -> SmallIconSize
        EdgeButtonSize.Medium -> MediumIconSize
        EdgeButtonSize.Large -> LargeIconSize
        else -> MediumIconSize
    }
}

/**
 * Hands [EdgeButtonGeometry] to the container. The spec is an immutable data class, so a retune that
 * changes nothing compares equal and leaves the drawn path alone.
 */
private fun Modifier.edgeButtonGeometry(geometry: EdgeButtonGeometry): Modifier =
    this.thenViewAttribute<WearEdgeButtonView, EdgeButtonGeometry>(uniqueKey, geometry) {
        this.geometry = it
    }
