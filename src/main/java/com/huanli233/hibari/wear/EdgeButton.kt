package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.Row
import com.huanli233.hibari.foundation.RowScope
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.text.TextAlign
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.attributes.clickable
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
 *  - **The label's [TextConfiguration] is scoped, as upstream scopes it** (`:273-284`):
 *    `TextConfiguration(TextAlign.Center, TextOverflow.Ellipsis, maxLines = buttonSize.maxLines())`
 *    goes down through [LocalTextConfiguration], so a caller's bare [Text] is centred and line-budgeted
 *    by the button instead of having to spell both out.
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
            )
            // Upstream's chain ends in `clickable(...)` (`material3/EdgeButton.kt:183-189`); without
            // it this node is neither clickable nor focusable-by-press, so a tap - and a D-pad centre
            // press on the focused button - would do nothing at all.
            .clickable(enabled, onClick),
    ) {
        provideContentColorAndStyle(
            contentColor,
            MaterialTheme.typography.labelMedium,
            TextConfiguration(
                TextAlign.Center,
                TextOverflow.Ellipsis,
                maxLines = buttonSize.maxLines(),
            ),
        ) {
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
 * Upstream's `maximumHeight` is `internal`, as it is here, and so is `maxLines()`, which [EdgeButton]
 * feeds into the [TextConfiguration] it scopes around its content. The one helper not carried over is
 * `maximumHeightPlusPadding()`: it only ever feeds `maxIntrinsicHeight`, which has no Views
 * counterpart, so carrying it would mean carrying an unused member.
 */
@JvmInline
value class EdgeButtonSize internal constructor(internal val maximumHeight: Dp) {

    /**
     * The line budget a label gets at this size (`material3/EdgeButton.kt:305-312`).
     *
     * Upstream's `ExtraSmall`/`Small`/`Medium` arms with `else -> 3` for `Large` are copied as written,
     * `else` arm included: the four sizes are values of a class with an `internal` constructor, so an
     * instance of another height still has to answer.
     */
    internal fun maxLines(): Int =
        when (this) {
            ExtraSmall -> 1
            Small -> 2
            Medium -> 2
            // Large
            else -> 3
        }

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
