package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.foundation.attributes.size
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.ui.unit.dp

/**
 * Ported from androidx.wear.compose.material3.InteractiveComponentSize (`InteractiveComponentSize.kt`,
 * 99 lines) and androidx.wear.compose.material3.TouchTargetAwareSize (`TouchTargetAwareSize.kt`,
 * 31 lines): the 48.dp × 48.dp minimum touch area and the one modifier that trades a component's
 * *visual* size against it.
 *
 * # What is deliberately not re-provided here
 *
 * Upstream's public entry point is `Modifier.minimumInteractiveComponentSize()`
 * (`InteractiveComponentSize.kt:43-45`), a no-argument modifier whose node
 * (`:67-97`) measures the child and then reports `maxOf(placeable.width, 48.dp.roundToPx())` in both
 * axes. This module already owns that rule: `Modifier.minimumInteractiveComponentSize(sizePx: Int)`
 * in `attributes/ContainerAttributes.kt:33-40` writes the same floor through
 * `minimumWidth`/`minimumHeight`, which is the `wrap_content` equivalent of Compose's
 * max-of-measured-size. So this file does **not** declare a second modifier of that name — a caller
 * in a `@Tunable` body reaches upstream's form with
 * `minimumInteractiveComponentSize(MinimumInteractiveComponentSize.toPx(currentContext))`, and
 * `ButtonGroup.kt:254-256`'s `MinWidth` plus `CheckboxButton.kt:70`'s `SplitCheckboxButtonMinWidth`
 * are both this file's [MinimumInteractiveComponentSize] restated as 48.dp (the integrator should
 * point them here).
 *
 * Two real differences against upstream's node, neither of which the existing helper hides:
 *  - Upstream centres the measured content inside the reserved box
 *    (`placeable.place(centerX, centerY)`, `InteractiveComponentSize.kt:91-95`).
 *    `minimumWidth`/`minimumHeight` only grow the box, so a caller that needs the content centred
 *    inside it wraps it in a `Box` with `BoxScope.gravity(Gravity.CENTER)`.
 *  - Upstream skips enforcement while the node is detached (`:74`, `:79-89`); a Views floor is always
 *    on, because `View.getSuggestedMinimumHeight` applies whenever the measure spec is not
 *    `EXACTLY`, which is the same condition in a form that needs no attach state.
 */
val MinimumInteractiveComponentSize: Dp = 48.dp

/**
 * Ported from androidx.wear.compose.material3.touchTargetAwareSize (`TouchTargetAwareSize.kt:27-31`),
 * the modifier upstream's `IconButton` and `TextButton` rows use to pin their *drawing* to a token
 * size while still reserving a 48.dp touch target around it:
 * `padding(max(0.dp, (48.dp - size) / 2)).size(size)`.
 *
 * The Views spelling is one view, not two: `padding` becomes the view's own padding and `size` its
 * exact `LayoutParams`, so the layout box is `size + 2 * inset` — which is precisely what Compose
 * measures for that chain — while the content region stays `size`.
 *
 *  - Above 48.dp the inset is 0 and the box is the requested size, as upstream's `max` gives.
 *  - Upstream's `.size(size)` is a constraint that Compose clamps to the incoming maximum; exact
 *    `LayoutParams` do not clamp, so asking for more than the parent offers overflows here where
 *    Compose would shrink.
 *
 * @param size The visible size of the content, square, in both axes — upstream takes one `Dp` for
 *   exactly that reason (`TouchTargetAwareSize.kt:28`).
 */
fun Modifier.touchTargetAwareSize(size: Dp): Modifier {
    val inset = ((MinimumInteractiveComponentSize - size) * 0.5f).coerceAtLeast(0.dp)
    val box = size + inset + inset
    return this.padding(inset).size(DpSize(box, box))
}
