package com.huanli233.hibari.wear

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.foundation.Row
import com.huanli233.hibari.foundation.RowScope
import com.huanli233.hibari.foundation.attributes.minHeight
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.wear.attributes.clickable
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.tokens.ChildButtonTokens
import com.huanli233.hibari.wear.tokens.OutlinedButtonTokens

/**
 * Ported from the base-level, single-slot Wear Material 3 `ChildButton`
 * (`material3/Button.kt:421-505`), the low-emphasis sibling of [Button] used for optional or
 * supplementary actions. It funnels into upstream's private `SingleSlotButtonImpl`
 * (`Button.kt:2366-2410`) — a centred `Row`, painted, bordered, clickable, given
 * `defaultMinSize(minHeight = ButtonDefaults.Height)` by `buttonSizeModifier` (`Button.kt:2358-2360`)
 * — which is what [ContainerSpec] plus [Modifier.minHeight] are here.
 *
 * Verified against the reference tree, because the shape of this API is easy to guess wrong: there is
 * **no** `ChildButtonColors` and **no** `ChildButtonDefaults` upstream. The colour type is the shared
 * [ButtonColors] and the factory is `ButtonDefaults.childButtonColors()` (`Button.kt:1599-1603`),
 * ported as an extension on the existing `ButtonDefaults` below for the reason given there.
 *
 * # Not ported, and why
 *
 *  - `onLongClick` / `onLongClickLabel` (`:478-479`), `interactionSource` (`:485`) and
 *    `transformation` (`:486`): as in [Button], no long-click channel in [clickable], no indication
 *    system, and no `SurfaceTransformation`. `ContainerDrawable` still swaps the disabled container
 *    and border on drawable state, which is what `transformation` was compensating for visually.
 *  - `semantics { role = Role.Button }` and the `combinedClickable` haptic: no semantics layer here.
 *  - `width(IntrinsicSize.Max)` (`buttonContainerModifier`, `:2437`): the Compose intrinsic pass has
 *    no `LinearLayout` equivalent. The row is `wrap_content`, so it hugs its content as upstream's
 *    does, but a child that asks for all the slack (`Modifier.weight`) will not be shrunk back.
 *
 * @param colors Defaults to `null` and resolves in the body: `ButtonDefaults.childButtonColors()`
 *   reads `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable`
 *   `$default` method that cannot.
 * @param border Upstream draws this [BorderStroke] in both the enabled and the disabled state — its
 *   [ButtonColors] carries no border at all, so callers resolve the state themselves, as
 *   `ButtonDefaults.outlinedButtonBorder(enabled)` does (`:397`). Passing one therefore also replaces
 *   whatever border [colors] carries, which is the same visual result.
 */
@Tunable
fun ChildButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Tunable RowScope.() -> Unit,
) {
    val resolved = colors ?: ButtonDefaults.childButtonColors()
    val contentColor = if (enabled) resolved.contentColor else resolved.disabledContentColor
    val scope = content
    Row(
        modifier = modifier
            // The 52.dp floor upstream's `buttonSizeModifier()` puts on every button
            // (`Button.kt:2359-2360`), which is `ButtonDefaults.Height` (`:1893`,
            // `FilledButtonTokens.ContainerHeight` = 52.dp) and also `ChildButtonTokens.ContainerHeight`.
            .minHeight(ButtonDefaults.Height)
            .container(resolved.containerSpec(shape).withExplicitBorder(border))
            .clickable(enabled, onClick)
            .padding(contentPadding)
            .childButtonCentered(),
    ) {
        // Upstream's `SingleSlotButtonImpl` provides `OutlinedButtonTokens.LabelFont` rather than
        // `ChildButtonTokens.LabelFont` here (`:496`); both are `LabelMedium`, so the style is stated
        // from the token the call site actually reads.
        provideContentColorAndStyle(
            contentColor,
            MaterialTheme.typography.fromToken(OutlinedButtonTokens.LabelFont),
        ) { scope() }
    }
}

/**
 * Ported from the three-slot Wear Material 3 `ChildButton` (`material3/Button.kt:985-1096`): an
 * optional leading [icon], then a column holding [label] and the optional [secondaryLabel], inside
 * the same stadium container as the single-slot overload above.
 *
 * It differs from upstream's own three-slot `Button` (`Button.kt:586`, same list bar the colours) in
 * its defaults only —
 * [ButtonColors] there resolve to `ButtonDefaults.buttonColors`, here to
 * `ButtonDefaults.childButtonColors()` (a `ButtonDefaults` member, as upstream has it) — and in the
 * style it provides: upstream
 * wraps the whole button in `LocalContentColor` + `LocalTextStyle provides
 * ChildButtonTokens.LabelFont.value` (`:1065-1069`) before handing the slots to `ButtonContent`
 * (`:1087-1094`), which then re-provides its own colour *and* `FilledButtonTokens` style per row.
 * Both layers are kept here, and the slots go to [ButtonContent] — the same public component upstream
 * calls there, carrying its own per-row colour, style and [TextConfiguration].
 *
 * # Choosing between the two overloads
 *
 * Upstream's `content` and `label` are both `@Composable RowScope.() -> Unit` in the last position, so
 * a bare trailing lambda — `ChildButton(onClick = {}) { ... }` — matches both overloads and Kotlin
 * reports an ambiguity. This is upstream's signature pair, kept as is: call the three-slot form with a
 * named `label = { ... }` (as every upstream sample does), or pass the single-slot form's `content`
 * explicitly.
 *
 * @param colors Defaults to `null` and resolves in the body; see [ChildButton].
 * @param border Defaults to `null`; see [ChildButton].
 */
@Tunable
fun ChildButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: (@Tunable RowScope.() -> Unit)? = null,
    icon: (@Tunable BoxScope.() -> Unit)? = null,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    label: @Tunable RowScope.() -> Unit,
) {
    val resolved = colors ?: ButtonDefaults.childButtonColors()
    val contentColor = if (enabled) resolved.contentColor else resolved.disabledContentColor
    provideContentColorAndStyle(
        contentColor,
        MaterialTheme.typography.fromToken(ChildButtonTokens.LabelFont),
    ) {
        // Upstream hands the whole container chain to `ButtonContent` rather than wrapping it in a
        // second row (`:1070-1094`), so the content row is the painted, clickable surface here too.
        ButtonContent(
            modifier = modifier
                .minHeight(ButtonDefaults.Height)
                .container(resolved.containerSpec(shape).withExplicitBorder(border))
                .clickable(enabled, onClick)
                .padding(contentPadding),
            secondaryLabel = secondaryLabel,
            icon = icon,
            enabled = enabled,
            colors = resolved,
            label = label,
        )
    }
}

/**
 * [ButtonColors.containerSpec] with the button's own `border` argument applied. Upstream hands the
 * single `border` parameter to `Modifier.surface` for every state (`:2438`), so it replaces both the
 * enabled and the disabled border here, and is ignored when null — leaving whatever the [ButtonColors]
 * carry standing, which is how Hibari's [OutlinedButton] gets its outline without a parameter.
 */
private fun ContainerSpec.withExplicitBorder(border: BorderStroke?): ContainerSpec =
    if (border == null) this else copy(border = border, disabledBorder = border)

/**
 * `Row(verticalAlignment = Alignment.CenterVertically)` (`:2405`, `:1328`) as a Views attribute:
 * `LinearLayout.setGravity` is the only way to centre the cross axis of every child when those
 * children come from a caller's slot and cannot be given `RowScope.gravity` here.
 */
private fun Modifier.childButtonCentered(): Modifier =
    this.thenViewAttribute<View, Int>(uniqueKey, Gravity.CENTER_VERTICAL) {
        if (this is LinearLayout) gravity = it
    }
