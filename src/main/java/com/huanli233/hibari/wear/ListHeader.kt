package com.huanli233.hibari.wear

import android.view.Gravity
import android.widget.LinearLayout
import com.huanli233.hibari.foundation.Row
import com.huanli233.hibari.foundation.RowScope
import com.huanli233.hibari.foundation.attributes.matchParentWidth
import com.huanli233.hibari.foundation.attributes.minHeight
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.RectangleShape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import com.huanli233.hibari.wear.tokens.ListHeaderTokens
import com.huanli233.hibari.wear.tokens.ListSubHeaderTokens
import com.huanli233.hibari.wear.tokens.TypographyKeyTokens

/**
 * Ported from androidx.wear.compose.material3.ListHeader / ListSubHeader.
 *
 * Upstream animates the header's padding away while the list scrolls beneath it (`ScrollAway`);
 * that depends on `ScrollInfoProvider`, so these headers keep a fixed padding.
 *
 * Both headers hand their children three things through locals (material3/ListHeader.kt:84-92 and
 * :141-149): `LocalContentColor`, `LocalTextStyle` from the role's own token, and
 * `LocalTextConfiguration(textAlign, Ellipsis, maxLines = 3)`. The first two are provided here
 * through [provideContentColorAndStyle]; **the third is a gap** — Hibari has no text-configuration
 * local, so a header's child `Text` keeps its own alignment, line count and overflow instead of
 * inheriting `Center`/`Start` + ellipsis + 3 lines.
 *
 * The two roles also differ in ways the delegation this file used to have between them hid: the
 * header is `Arrangement.Center` over a `wrapContentSize()` box (:76-80), the sub-header is
 * `Arrangement.Start` over a `fillMaxWidth()` one (:129-135).
 *
 * @param contentColor Defaults to null and resolves in the body: the real default reads
 *   `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable` `$default`
 *   method that cannot reach it.
 */
@Tunable
fun ListHeader(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent,
    contentColor: Color? = null,
    contentPadding: PaddingValues = ListHeaderDefaults.ContentPadding,
    content: @Tunable RowScope.() -> Unit,
) {
    val resolvedColor = contentColor ?: ListHeaderDefaults.contentColor()
    val style = ListHeaderDefaults.contentTypography()
    val scope = content
    Row(
        modifier = modifier
            .rowGravity(Gravity.CENTER_HORIZONTAL)
            .container(ContainerSpec(shape = RectangleShape, containerColor = backgroundColor))
            .padding(contentPadding)
            .minHeight(ListHeaderDefaults.Height),
    ) {
        provideContentColorAndStyle(resolvedColor, style) { scope() }
    }
}

@Tunable
fun ListSubHeader(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent,
    contentColor: Color? = null,
    contentPadding: PaddingValues = ListSubHeaderDefaults.ContentPadding,
    icon: (@Tunable RowScope.() -> Unit)? = null,
    label: @Tunable RowScope.() -> Unit,
) {
    val resolvedColor = contentColor ?: ListSubHeaderDefaults.contentColor()
    val style = ListSubHeaderDefaults.contentTypography()
    val labelSlot = label
    val iconSlot = icon
    Row(
        modifier = modifier
            .matchParentWidth()
            .container(ContainerSpec(shape = RectangleShape, containerColor = backgroundColor))
            .padding(contentPadding)
            .minHeight(ListSubHeaderDefaults.Height),
    ) {
        provideContentColorAndStyle(resolvedColor, style) {
            iconSlot?.invoke(this)
            labelSlot()
        }
    }
}

/**
 * The Views stand-in for `Row(horizontalArrangement = ...)`: Hibari's `RowScope` only carries
 * per-child gravity and weight, so the container's own alignment has to be written on the
 * `LinearLayout` this `Row` renders to.
 */
private fun Modifier.rowGravity(gravity: Int): Modifier =
    this.thenViewAttribute<LinearLayout, Int>(uniqueKey, gravity) { this.gravity = gravity }

object ListHeaderDefaults {
    val Height: Dp = ListHeaderTokens.Height

    val HorizontalPadding: Dp = 14.dp
    val VerticalPadding: Dp = 6.dp

    val ContentPadding: PaddingValues = PaddingValues(
        start = HorizontalPadding,
        top = VerticalPadding,
        end = HorizontalPadding,
        bottom = VerticalPadding,
    )

    @Tunable
    fun contentColor(): Color = ListHeaderTokens.ContentColor.resolve(MaterialTheme.colorScheme)

    @Tunable
    fun contentTypography(): com.huanli233.hibari.ui.text.TextStyle =
        MaterialTheme.typography.fromToken(ListHeaderTokens.ContentTypography)
}

object ListSubHeaderDefaults {
    val Height: Dp = ListSubHeaderTokens.Height

    val HorizontalPadding: Dp = 14.dp
    val VerticalPadding: Dp = 2.dp

    val ContentPadding: PaddingValues = PaddingValues(
        start = HorizontalPadding,
        top = VerticalPadding,
        end = HorizontalPadding,
        bottom = VerticalPadding,
    )

    @Tunable
    fun contentColor(): Color = ListSubHeaderTokens.ContentColor.resolve(MaterialTheme.colorScheme)

    @Tunable
    fun contentTypography(): com.huanli233.hibari.ui.text.TextStyle =
        MaterialTheme.typography.fromToken(ListSubHeaderTokens.ContentTypography)
}
