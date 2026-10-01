package com.huanli233.hibari.wear

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.foundation.Row
import com.huanli233.hibari.foundation.RowScope
import com.huanli233.hibari.foundation.Spacer
import com.huanli233.hibari.foundation.attributes.height
import com.huanli233.hibari.foundation.attributes.matchParentHeight
import com.huanli233.hibari.foundation.attributes.matchParentSize
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.foundation.attributes.size
import com.huanli233.hibari.foundation.attributes.width
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.text.TextAlign
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.wear.attributes.clickable
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.tokens.CompactButtonTokens
import com.huanli233.hibari.wear.tokens.ShapeTokens

/**
 * Ported from androidx.wear.compose.material3.CompactButton (`material3/Button.kt:1098-1257`): the
 * one-line button that sits in `ButtonGroup`/`TransformingLazyColumn` rows, with an optional [icon]
 * and an optional [label].
 *
 * Upstream's chain is `height(CompactButtonDefaults.Height)` → `padding(TapTargetPadding)` →
 * (`width(IconOnlyWidth)` in the icon-only branch, `Button.kt:1232`) →
 * `buttonContainerModifier` = `width(IntrinsicSize.Max)` + `surface` + `combinedClickable` +
 * `padding(contentPadding)` (`:1210-1248`, `:2412-2449`) → [CompactButtonContent]. Views cannot put
 * a background inside a padding, so that chain becomes two views: an outer [Box] carrying the fixed
 * 48.dp height and the tap-target padding, and the content root [CompactButtonContent] builds, which
 * carries the container, the click and the content padding. The painted stadium is
 * therefore 32.dp tall, as upstream's is, and the 8.dp band above and below is *not* tappable — the
 * same asymmetry upstream ships, where the KDoc at `:1103-1106` promises a 48.dp tappable area but
 * places `combinedClickable` inside the padding.
 *
 * # Not ported, and why
 *
 *  - `onLongClick` / `onLongClickLabel` (`:1193-1194`), `interactionSource` (`:1201`),
 *    `transformation` (`:1202`): no long-click channel in [clickable], no indication system, no
 *    `SurfaceTransformation`.
 *  - `width(IntrinsicSize.Max)` (`:2437`): no `LinearLayout` equivalent; the row is `wrap_content`.
 *  - The `semantics` role and the ripple.
 *  - Compose's constraint pass: `Modifier.height(48.dp)` is a *fixed* height, so upstream measures the
 *    label inside the 32.dp that is left and the `Text` has to fit (with `maxLines = 1` and an
 *    ellipsis). The port writes exact `LayoutParams`, and a `LinearLayout` does not clip its children,
 *    so a label that is taller than 32.dp overflows the stadium instead of being trimmed.
 *  - The deprecated `ButtonDefaults` aliases, on purpose: `ButtonDefaults.compactButtonShape`
 *    (`:1412-1418`) and the `ExtraSmallIconSize` / `SmallIconSize` / `CompactButtonHorizontalPadding`
 *    / `CompactButtonVerticalPadding` / `CompactButtonContentPadding` / `CompactButtonHeight` /
 *    `CompactButtonTapTargetPadding` group (`:1866-1944`) are each
 *    `@Deprecated("Use CompactButtonDefaults.x instead")`, and the live replacements are all here.
 *    Hibari's [ButtonDefaults] already keeps three of those names undeprecated, and this file reads
 *    its paddings from them rather than restating the numbers.
 *
 * @param colors Defaults to `null` and resolves in the body to [ButtonDefaults.buttonColors] — the
 *   high-emphasis set, as upstream's default is (`:1198`) — because a `@Tunable` default expression
 *   is hoisted into a non-`@Tunable` `$default` method that cannot read `MaterialTheme`.
 * @param border A [BorderStroke] here is drawn in both the enabled and the disabled state, which is
 *   upstream's behaviour: its [ButtonColors] carries no border, so the caller resolves the state
 *   (`:397`). It therefore replaces the border [colors] carries rather than blending with it.
 * @param label Upstream's default is `null` (`:1203`) and `null` selects the icon-only branch.
 */
@Tunable
fun CompactButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Tunable BoxScope.() -> Unit)? = null,
    enabled: Boolean = true,
    shape: Shape = CompactButtonDefaults.shape,
    colors: ButtonColors? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = CompactButtonDefaults.ContentPadding,
    label: (@Tunable RowScope.() -> Unit)? = null,
) {
    val resolved = colors ?: ButtonDefaults.buttonColors()
    val contentColor = if (enabled) resolved.contentColor else resolved.disabledContentColor
    val iconSlot = icon
    val labelSlot = label
    val spec = resolved.containerSpec(shape)
    provideContentColorAndStyle(
        contentColor,
        MaterialTheme.typography.fromToken(CompactButtonTokens.LabelFont),
    ) {
        val tapTarget = modifier
            .height(CompactButtonDefaults.Height)
            .padding(CompactButtonDefaults.TapTargetPadding)
        if (labelSlot != null) {
            Box(modifier = tapTarget) {
                CompactButtonContent(
                    modifier = Modifier
                        .matchParentHeight()
                        .container(spec.withCompactBorder(border))
                        .clickable(enabled, onClick)
                        .padding(contentPadding),
                    icon = iconSlot,
                    enabled = enabled,
                    colors = resolved,
                    label = labelSlot,
                )
            }
        } else {
            Box(
                modifier = tapTarget.width(CompactButtonDefaults.IconOnlyWidth),
            ) {
                CompactButtonContent(
                    modifier = Modifier
                        .matchParentSize()
                        .container(spec.withCompactBorder(border))
                        .clickable(enabled, onClick)
                        .padding(contentPadding),
                    icon = iconSlot,
                    enabled = enabled,
                    colors = resolved,
                    label = null,
                )
            }
        }
    }
}

/**
 * Ported from androidx.wear.compose.material3.CompactButtonContent (`material3/Button.kt:1346-1404`),
 * the two-slot layout [CompactButton] hands its container modifier to, and public upstream so that a
 * caller building a custom button container can reuse the icon/label arrangement.
 *
 * The label branch is upstream's `Row(CenterVertically) { icon Box; Spacer(IconSpacing); Row(label) }`
 * (`:1373-1396`). The icon-only branch is upstream's
 * `Box(fillMaxSize().wrapContentSize(align = Center)) { icon() }` (`:1397-1402`): `fillMaxSize` has no
 * standalone meaning in a `wrap_content` `LinearLayout`, so the fill is expressed by the caller's
 * [matchParentSize] / [matchParentHeight] on [modifier] (as [CompactButton] does) and the centring by
 * an inner box carrying `BoxScope.gravity(Gravity.CENTER)`. Called directly with no fill in the
 * incoming [modifier], the icon-only branch collapses to the icon's own size.
 *
 * Upstream's own KDoc here (`:1349-1353`) recommends it for "the [CompactButton] overload that takes
 * a generic `content`" — checked against the reference tree, that overload does not exist: `Button.kt`
 * declares exactly one `CompactButton` (`:1189-1257`), so the sentence is stale documentation rather
 * than a port gap.
 *
 * @param colors Defaults to `null` and resolves to [ButtonDefaults.buttonColors] in the body, as
 *   upstream's default does (`:1370`); see [CompactButton] for why the default is `null`.
 */
@Tunable
fun CompactButtonContent(
    modifier: Modifier = Modifier,
    icon: (@Tunable BoxScope.() -> Unit)? = null,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    label: (@Tunable RowScope.() -> Unit)? = null,
) {
    val resolved = colors ?: ButtonDefaults.buttonColors()
    val typography = MaterialTheme.typography
    val iconColor = if (enabled) resolved.iconColor else resolved.disabledIconColor
    val labelColor = if (enabled) resolved.contentColor else resolved.disabledContentColor
    val iconSlot = icon
    val labelSlot = label
    if (labelSlot != null) {
        Row(modifier = modifier.compactButtonCentered()) {
            if (iconSlot != null) {
                Box(modifier = Modifier.gravity(Gravity.CENTER_VERTICAL)) {
                    provideContentColor(iconColor) { iconSlot() }
                }
                Spacer(
                    modifier = Modifier.size(
                        DpSize(ButtonDefaults.IconSpacing, ButtonDefaults.IconSpacing)
                    )
                )
            }
            Row {
                provideContentColorAndStyle(
                    labelColor,
                    typography.fromToken(CompactButtonTokens.LabelFont),
                    // Upstream's one-line budget for this label: start-aligned beside an icon, centred
                    // when there is none (`material3/Button.kt:1387-1392`).
                    TextConfiguration(
                        if (iconSlot != null) TextAlign.Start else TextAlign.Center,
                        TextOverflow.Ellipsis,
                        maxLines = 1,
                    ),
                ) { labelSlot() }
            }
        }
    } else {
        Box(modifier = modifier) {
            if (iconSlot != null) {
                Box(modifier = Modifier.gravity(Gravity.CENTER)) {
                    provideContentColor(iconColor) { iconSlot() }
                }
            }
        }
    }
}

/** Contains the default values used by [CompactButton] — `material3/Button.kt:2164-2229`. */
object CompactButtonDefaults {

    /**
     * `ShapeTokens.CornerMedium` (`:2166-2168`), the same 18.dp `RoundedCornerShape`
     * `CompactButtonTokens.ContainerShape` names. Upstream's `shape` is a `@Composable get()`; there
     * is no theme read in it, so this is a plain value like `ButtonDefaults.shape`.
     */
    val shape: Shape = ShapeTokens.CornerMedium

    /**
     * `CompactButtonDefaults.HorizontalPadding` (`:2170-2171`, 12.dp). The same number
     * [ButtonDefaults.CompactButtonHorizontalPadding] already carries, so it is read from there
     * rather than restated.
     */
    val HorizontalPadding: Dp = ButtonDefaults.CompactButtonHorizontalPadding

    /** `CompactButtonDefaults.VerticalPadding` (`:2173-2174`, 0.dp), as above. */
    val VerticalPadding: Dp = ButtonDefaults.CompactButtonVerticalPadding

    /**
     * `CompactButtonDefaults.ContentPadding` (`:2176-2183`), assembled from the two paddings above in
     * upstream's start/top/end/bottom shape; [ButtonDefaults.CompactContentPadding] is the same
     * `PaddingValues` built from the same two numbers.
     */
    val ContentPadding: PaddingValues = ButtonDefaults.CompactContentPadding

    /**
     * `CompactButtonDefaults.minimumVerticalListContentPadding` (`:2199-2200`):
     * `screenHeightFraction(SMALL_VERTICAL_CONTENT_PADDING_FRACTION)` (`Padding.kt:67,73`, fraction
     * `Padding.kt:73`), for the edge item of a `TransformingLazyColumn` — the same reading as
     * [IconButtonDefaults.minimumVerticalListContentPadding], which still inlines the fraction.
     */
    val minimumVerticalListContentPadding: Dp
        @Tunable get() = screenHeightFraction(SMALL_VERTICAL_CONTENT_PADDING_FRACTION)

    /**
     * `CompactButtonDefaults.Height` (`:2210`), `CompactButtonTokens.ContainerHeight`: the 48.dp the
     * whole button occupies, 32.dp of visible stadium plus [TapTargetPadding]'s two 8.dp bands.
     */
    val Height: Dp = CompactButtonTokens.ContainerHeight

    /**
     * `CompactButtonDefaults.TapTargetPadding` (`:2212-2216`): the 8.dp above and below that keeps
     * the stadium 32.dp tall inside the 48.dp row.
     */
    val TapTargetPadding: PaddingValues = PaddingValues(top = 8.dp, bottom = 8.dp)

    /**
     * `CompactButtonDefaults.IconOnlyWidth` (`:2218-2222`), `CompactButtonTokens.IconOnlyWidth`, and
     * internal upstream too, so internal here: it is a layout branch constant, not a caller knob —
     * upstream's own advice is to override it with `Modifier.width`.
     */
    internal val IconOnlyWidth: Dp = CompactButtonTokens.IconOnlyWidth

    /**
     * `CompactButtonDefaults.ExtraSmallIconSize` (`:2224-2225`), `CompactButtonTokens.IconSize`: the
     * icon size for the icon-plus-label arrangement.
     */
    val ExtraSmallIconSize: Dp = CompactButtonTokens.IconSize

    /**
     * `CompactButtonDefaults.SmallIconSize` (`:2227-2228`), `CompactButtonTokens.IconOnlyIconSize`:
     * the icon size for the icon-only arrangement.
     */
    val SmallIconSize: Dp = CompactButtonTokens.IconOnlyIconSize
}

/**
 * [ButtonColors.containerSpec] with the button's own `border` argument applied to both states, as
 * `Modifier.surface` does upstream (`material3/Button.kt:2438`). Duplicate of the private helper in
 * `ChildButton.kt`: private helpers do not cross files, and this one may not add to `Button.kt`.
 */
private fun ContainerSpec.withCompactBorder(border: BorderStroke?): ContainerSpec =
    if (border == null) this else copy(border = border, disabledBorder = border)

/**
 * `Row(verticalAlignment = Alignment.CenterVertically)` (`material3/Button.kt:1374`) as a Views
 * attribute: `LinearLayout.setGravity` centres every child on the cross axis, including the label
 * slots this file cannot reach through `RowScope.gravity`.
 */
private fun Modifier.compactButtonCentered(): Modifier =
    this.thenViewAttribute<View, Int>(uniqueKey, Gravity.CENTER_VERTICAL) {
        if (this is LinearLayout) gravity = it
    }
