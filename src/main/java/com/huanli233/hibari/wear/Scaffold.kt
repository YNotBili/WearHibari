package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.foundation.attributes.matchParentSize
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.RectangleShape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.thenLayoutAttribute
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.view.WearInsetLayoutView
import com.huanli233.hibari.wear.view.WearSwipeToDismissView

/**
 * Ported from androidx.wear.compose.material3.{Scaffold, AppScaffold, ScreenScaffold}.
 *
 * Upstream's whole scaffold family is a padding computation around `BoxWithConstraints` plus a
 * `ScrollInfoProvider` that drives the scroll indicator and `ScrollAway`. Hibari has no scroll
 * info plumbing yet, so [ScreenScaffold] hands out the content padding and nothing consumes a
 * scroll state: the `scrollIndicator` and `overscrollEffect` slots are absent, and the
 * `edgeButton` overload is not ported because EdgeButton needs the screen-curvature path solve.
 */

/**
 * Which edges of a [WearInsetLayoutView] child get pushed inside the inset box.
 *
 * The values are [WearBoxedEdges]' — the same bit pattern `androidx.wear.widget.BoxInsetLayout`
 * shipped — so this is a re-point at the ported maths, not a change of meaning, and no call site
 * changes.
 *
 * **Not mounted by [AppScaffold].** Upstream `AppScaffold` is a plain
 * `Box(Modifier.fillMaxSize().background(containerColor))` with no insets at all
 * (material3/AppScaffold.kt:71-82), so the faithful default is the plain box; the per-edge opt-in
 * below only does something under a `Modifier.viewClass(WearInsetLayoutView::class.java)` container,
 * which a caller mounts deliberately.
 */
object BoxedEdges {
    const val NONE = WearBoxedEdges.NONE
    const val ALL = WearBoxedEdges.ALL
    const val TOP = WearBoxedEdges.TOP
    const val BOTTOM = WearBoxedEdges.BOTTOM
    const val LEFT = WearBoxedEdges.LEFT
    const val RIGHT = WearBoxedEdges.RIGHT
}

/**
 * Opt a child of a [WearInsetLayoutView] into its inset box. The container reads this off its own
 * LayoutParams, so it has to be a layout attribute rather than a view property.
 */
fun Modifier.boxedEdges(edges: Int): Modifier =
    this.thenLayoutAttribute<WearInsetLayoutView.LayoutParams, Int>(uniqueKey, edges) { value, _ ->
        boxedEdges = value
    }

@Tunable
fun Scaffold(
    modifier: Modifier = Modifier,
    content: @Tunable BoxScope.() -> Unit,
) {
    Box(modifier = modifier, content = content)
}

/**
 * The app root: paints `containerColor` behind everything and hands `contentColor` down as the
 * ambient content colour, which is what a bare [Text] inside [ScreenScaffold] resolves against.
 *
 * Upstream `AppScaffold` (material3/AppScaffold.kt:54-82) does four things; two are ported and two
 * are not:
 *  - `LocalContentColor provides contentColor` with `contentColor = contentColorFor(containerColor)`
 *    (:59, :69) and `Modifier.fillMaxSize().background(containerColor)` (:71) — **ported**, and the
 *    reason this container is the one that owns the colour: [ScreenScaffold] upstream provides no
 *    colour at all, so a `Text` that is not inside a `Card`/`Button`/`ListHeader` and not under an
 *    `AppScaffold` falls back to Compose's `LocalContentColor` seed, which is `Color.White`
 *    (material3/ContentColor.kt:34). This port seeds it with `Color.Unspecified` instead — see [Text] —
 *    so without this layer a bare `Text` keeps the raw `TextView` theme colour, which is not
 *    `contentColorFor(background)` and is not stable across themes.
 *  - `timeText = { TimeText() }` promoted to a slot, driven by `LocalScaffoldState`'s
 *    `screenContent.timeText` and scrolled away by `ScrollAway`; and `AnimationCoordinator.Looper()` —
 *    **not ported**, same reason as in [ScreenScaffold]: there is no `ScrollInfoProvider` or
 *    scaffold-state plumbing here, so a `TimeText` stays an explicit child of the content.
 *
 * @param containerColor null resolves to `MaterialTheme.colorScheme.background` in the body, because
 *   a `@Tunable` default expression is hoisted into a non-`@Tunable` `$default` that cannot read the
 *   theme. Same for [contentColor], which resolves through `contentColorFor` instead.
 */
@Tunable
fun AppScaffold(
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    contentColor: Color? = null,
    content: @Tunable BoxScope.() -> Unit,
) {
    val container = containerColor ?: MaterialTheme.colorScheme.background
    val resolvedContent = contentColor ?: MaterialTheme.colorScheme.contentColorFor(container)
    val scope = content
    Box(
        // `matchParentSize()` is this port's spelling of upstream's `Modifier.fillMaxSize()`
        // (material3/AppScaffold.kt:71). It has to be explicit: `Renderer` hands a child WRAP_CONTENT
        // params when the parent has its own `$LayoutParams` class, and a wrap_content scroll container
        // does not measure.
        modifier = modifier
            .matchParentSize()
            .container(ContainerSpec(shape = RectangleShape, containerColor = container)),
    ) {
        provideContentColor(resolvedContent) { scope() }
    }
}

/**
 * The screen root: swipe-from-left to dismiss, content inset by [contentPadding].
 *
 * `ScreenScaffoldDefaults.contentPadding()` upstream is `PaddingDefaults.verticalContentPadding()`
 * (10% of screen height); that needs the density at compose time, so it is computed here per call.
 *
 * **The gesture here is not a port of a scaffold feature.** Upstream `material3.ScreenScaffold` has
 * no swipe parameters at all — dismissal lives in the separate `SwipeToDismissBox` /
 * `BasicSwipeToDismissBox` that `SwipeDismissableNavHost` wraps around each screen, and every
 * "swipe" hit in that file is a doc comment pointing at the nav host. [swipeToDismissEnabled] and
 * [onDismiss] stay because this module replaced the platform `SwipeDismissFrameLayout`, which hung
 * off the screen root; the state machine and the view they drive
 * ([com.huanli233.hibari.wear.view.WearSwipeToDismissView]) are the ported ones.
 *
 * The two scrims are both `colorScheme.background`, which is what the material3 wrapper passes
 * (material3/SwipeToDismissBox.kt:70-71); a `ViewGroup` cannot read
 * `LocalSwipeToDismiss{Content,Background}ScrimColor`, so they are applied as view attributes.
 */
@Tunable
fun ScreenScaffold(
    modifier: Modifier = Modifier,
    /** Defaults to the 10%-of-height screen padding; null is resolved in the body because a
     *  @Tunable default expression is hoisted into a non-@Tunable `$default` method. */
    contentPadding: PaddingValues? = null,
    swipeToDismissEnabled: Boolean = true,
    onDismiss: (() -> Unit)? = null,
    content: @Tunable BoxScope.(PaddingValues) -> Unit,
) {
    val resolvedPadding = contentPadding ?: ScreenScaffoldDefaults.contentPadding()
    val scope = content
    val dismissState = rememberSwipeToDismissBoxState()
    val scrimColor = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .matchParentSize()
            .viewClass(WearSwipeToDismissView::class.java)
            .thenViewAttribute<WearSwipeToDismissView, SwipeToDismissBoxState>(uniqueKey, dismissState) {
                state = it
            }
            .thenViewAttribute<WearSwipeToDismissView, Boolean>(uniqueKey, swipeToDismissEnabled) {
                isSwipeEnabled = it
            }
            .thenViewAttribute<WearSwipeToDismissView, Color>(uniqueKey, scrimColor) {
                backgroundScrimColor = it
            }
            .thenViewAttribute<WearSwipeToDismissView, Color>(uniqueKey, scrimColor) {
                contentScrimColor = it
            }
            .thenViewAttribute<WearSwipeToDismissView, (() -> Unit)?>(uniqueKey, onDismiss) {
                // A plain property write, not an add-listener: `onDismiss` is a lambda and never
                // compares equal, so this attribute re-applies on every retune.
                onDismissed = it
            },
    ) {
        scope(resolvedPadding)
    }
}

object ScreenScaffoldDefaults {
    /**
     * `ScreenScaffoldDefaults.contentPadding` — material3/ScreenScaffold.kt:786-792:
     * `PaddingValues(horizontal = horizontalContentPadding(), vertical = verticalContentPadding())`.
     *
     * The horizontal edge was missing from this port until now; upstream has always had it.
     */
    @Tunable
    fun contentPadding(): PaddingValues {
        val configuration = currentContext.resources.configuration
        return WearInsets.contentPaddingValues(
            screenWidthDp = configuration.screenWidthDp,
            screenHeightDp = configuration.screenHeightDp,
        )
    }
}
