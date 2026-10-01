package com.huanli233.hibari.wear

import android.view.Gravity
import com.huanli233.hibari.animation.AnimationSpec
import com.huanli233.hibari.animation.Spring
import com.huanli233.hibari.animation.spring
import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.foundation.BoxScopeInstance
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.attributes.matchParentSize
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.RectangleShape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.thenLayoutAttribute
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.unit.toPx
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.view.ScreenScaffoldEdgeButtonMotion
import com.huanli233.hibari.wear.view.ScreenScaffoldIndicatorFade
import com.huanli233.hibari.wear.view.WearInsetLayoutView
import com.huanli233.hibari.wear.view.WearScreenScaffoldEdgeButtonBandView
import com.huanli233.hibari.wear.view.WearScreenScaffoldIndicatorHostView
import com.huanli233.hibari.wear.view.WearSwipeToDismissView

/**
 * Ported from androidx.wear.compose.material3.{AppScaffold, ScreenScaffold}.
 *
 * **No `Scaffold` composable is published here, and that is not an oversight.** Upstream
 * `material3/Scaffold.kt` declares no public composable at all: every member of that file is
 * `internal` — `ScaffoldState` (`:51`), `ScreenContent` (`:69`), `AnimatedIndicator` (`:153`),
 * `LocalScaffoldState` (`:190`), `AnimationCoordinator` (`:194`), `INDICATOR_FADE_OUT_ANIMATION`
 * (`:231`) — and its job is the *app-level* registry that layers one `TimeText` over every screen.
 * The public `Scaffold` is a Wear **Material 2** component (`material/Scaffold.kt:57-72`): a `Box`
 * plus four optional full-screen slots, `vignette` / `positionIndicator` / `pageIndicator` /
 * `timeText` (`:59-62`), each of them simply invoked after the content (`:65-71`). This module ports
 * material3, and the bare `Scaffold(modifier, content)` that used to sit in this file was neither
 * the M3 one (there is none) nor the M2 one (none of the four slots), so it is gone rather than kept
 * under a name that promises an API it does not have. A screen root is [AppScaffold] over
 * [ScreenScaffold], which is upstream's own pairing (`material3/AppScaffold.kt:33-34`,
 * `material3/ScreenScaffold.kt:80-85`).
 *
 * ## What the scaffold family does, and what this port leaves out
 *
 * Upstream's `ScreenScaffold` is nine public overloads (`material3/ScreenScaffold.kt:124, :181, :246,
 * :306, :368, :425, :483, :549, :726`), all of them funneling into the last one, which does four
 * things: register the screen in `LocalScaffoldState.current.screenContent` (`:735-753`), wrap the
 * content in the overscroll factory (`:755`), emit two boxes (`:756-757`) and — when a
 * `scrollInfoProvider` was given — show the `scrollIndicator` through `AnimatedIndicator`
 * (`:759-768`). Of those four, this port ships the two boxes, the bottom inset in this file's
 * [ScreenScaffold] edge-button overload, and the `AnimatedIndicator` half with one of its two terms
 * substituted for a provider read. Four entries follow, each with its real reason:
 *  - **The app-level registry is not ported.** `LocalScaffoldState` / `ScaffoldState` /
 *    `ScreenContent` (`material3/Scaffold.kt:51-150`) plus the `timeText` slot they feed
 *    (`ScreenScaffold.kt:730`) and the `AnimationCoordinator.Looper()` that `AppScaffold` runs to
 *    drive them (`AppScaffold.kt:62`, `Scaffold.kt:210-225`) are a per-app singleton keyed on
 *    `remember { Any() }` with `DisposableEffect`/`LaunchedEffect` lifecycle writes
 *    (`ScreenScaffold.kt:736-753`) and a `LocalScreenIsActive` gate (`:746-748`). Hibari has no
 *    screen-active local — `WearPagerView.kt:104`, `WearSwipeToDismissView.kt:82` and `Pager.kt:573`
 *    all record the same absence — so a registry could be built but could not decide *which* screen
 *    owns the time text. [AppScaffold] emits its own `timeText` slot for that reason (see its KDoc),
 *    and a [TimeText] remains placeable as an explicit child of the content — which is still the only
 *    way to scroll one away from a [ScreenStage] of the caller's own.
 *  - **The scroll indicator slot is wired to the provider; its default is not upstream's and its
 *    visibility gate is a named substitution.** The plumbing it hangs on **does** exist here:
 *    [ScrollInfoProvider] is ported (`ScrollAway.kt:76-103`, all five values including the
 *    `lastItemOffset` the edge-button reveal reads), [scrollAway] consumes it
 *    (`ScrollAway.kt:142-194`), [ScreenStage] is ported (`ScrollAway.kt:33-53`) and [ScrollIndicator]
 *    is public (`ScrollIndicator.kt:189`, `:245`). Two pieces are missing, and both are the app-level
 *    kind: upstream's default slot is `{ ScrollIndicator(scrollState) }` (`ScreenScaffold.kt:130`,
 *    `:186`), while this module's [ScrollIndicator] needs the `layoutInfo` numbers
 *    `ScalingLazyListState` does not publish and so takes `itemCount` explicitly
 *    (`ScrollIndicator.kt:236-249`); and `AnimatedIndicator`'s `isVisible` is
 *    `screenStage != Idle && provider.isScrollable` (`ScreenScaffold.kt:761-764`), whose left term is
 *    the registry's own `MutableState` (`Scaffold.kt:111`, written at `:113-127`) and is therefore not
 *    reachable without it. What is wired instead (2026-10-01, after the audit): both [ScreenScaffold]
 *    overloads take `scrollInfoProvider` and `scrollIndicator`, the visible rule is the provider's own
 *    conjunction `isScrollable && isScrollInProgress` — one read per tune, the same consumption
 *    `scrollAway` is built on (`ScrollAway.kt:147-156`), never a per-frame state bridge back into
 *    tune, since state invalidation here is whole-Tunation — and the spring fade runs on the box's own
 *    clock, in `view/WearScreenScaffoldIndicatorHostView`, the way [WearPagerIndicatorSlotView] runs
 *    [PagerScaffoldDefaults.FadeOutAnimationSpec] for [HorizontalPagerScaffold]. Dropping
 *    `screenStage` costs three observable things — no show-at-entry, no 2 s idle hold, tune cadence
 *    instead of snapshot cadence — and they are enumerated at the site, in
 *    [screenScaffoldSwipeHost], rather than summarised here.
 *  - **The `overscrollEffect` / `LocalOverscrollFactory` chain is not ported**
 *    (`ScreenScaffold.kt:732`, `:755`, `:802-822`, `:891-893`): `OverscrollEffect`,
 *    `OffsetOverscrollFactory` (`:812`) and `Modifier.overscroll` are compose-foundation types with no
 *    source in the reference tree and no Hibari counterpart.
 *  - **The edge-button reveal is wired where a provider is given, and the earlier reason for not
 *    wiring it was false.** This file claimed the `dynamicHeight` clamp "needs a measure-time height
 *    clamp, which a Views node does not get" — refuted on this module's own code:
 *    `view/WearEdgeButtonView.kt:157-195` is a measure pass that clamps its own height. The real
 *    shape (2026-10-01): [ScreenScaffold]'s edge-button overload reads `lastItemOffset` and
 *    `isScrollInProgress` once per tune — reachable from the recycler the caller's list already hands
 *    its state (`lazy/WearLazyColumn.kt:39`, `:51-60`), consumed the way [scrollAway] consumes a
 *    provider — folds them into one immutable attribute value, and
 *    `view/WearScreenScaffoldEdgeButtonBandView` runs the snap / 16.dp-threshold / spring rules of
 *    `ScreenScaffold.kt:647-679` on the view's own clock from it. A per-frame tune read or a state
 *    bridge back into tune is forbidden: state invalidation in this engine is whole-Tunation, and a
 *    frame-granular bridged value would retune the whole subtree every frame of a reveal. What that
 *    costs — the band follows the list at tune cadence, where upstream re-reads the target inside its
 *    own measure pass (`:601-602`, `:864`) — is stated in the band view's KDoc, not papered over. The
 *    provider-less overload — and `AlertDialogContent`'s — keeps the static full reveal, whose bottom
 *    inset is upstream's own value with the written-down intrinsic of the default button size.
 * ## Which of the nine upstream overloads can exist here
 *
 * Our two public [ScreenScaffold] signatures are upstream's `:549` (edge button + required provider)
 * and `:726` (provider nullable, no slot). The other seven (`:124`, `:181`, `:246`, `:306`, `:368`,
 * `:425`, `:483`) are the same body twice over: each forwards with
 * `scrollInfoProvider = remember(scrollState) { ScrollInfoProvider(scrollState) }`
 * (`foundation/ScrollInfoProvider.kt:81`, `:90`, `:99`, `:110`) and a non-null
 * `scrollIndicator = { ScrollIndicator(scrollState) }` default (`ScreenScaffold.kt:130`, `:186`,
 * `:252`, `:311`, `:374`, `:430`, `:488`). All nine parameter lists were opened: none of them takes a
 * `scaffoldState`, a `pagerState` or a `page` — those belong to the pager scaffold, not to a screen
 * scaffold. So the seven differ from what is shipped only in (a) which concrete
 * scroll state names the parameter and (b) the indicator default, and both are blocked for reasons of
 * different kinds:
 *  - **`:246`/`:306`, `:368`/`:425` and `:483`: the parameter type does not exist in this repository.**
 *    A search of every module finds `TransformingLazyColumnState`, `LazyListState` and `ScrollState`
 *    only inside prose. Hibari declares exactly one scroll state (`ScalingLazyListState`,
 *    `lazy/WearLazyColumn.kt:43-94`), `TransformingLazyColumn` takes `state: ScalingLazyListState?`
 *    (`lazy/WearLazyColumn.kt:152-154`), and `hibari-recyclerview`'s [com.huanli233.hibari.recyclerview.LazyColumn]
 *    takes no state object at all (`LazyList.kt:155-160`). Two consequences: those five signatures
 *    have nothing to name, and even after the next bullet is fixed upstream's ScalingLazy/Transforming
 *    pairs (`:124`/`:246`, `:181`/`:306`) cannot become four Kotlin overloads here — one state type
 *    means one signature, and the difference between them has to be written down rather than
 *    re-declared.
 *  - **`:124`/`:181`: same body, but there is nothing to forward to and no number to fill the default
 *    with.** The adapter they call (`foundation/ScrollInfoProvider.kt:128-189`) answers all five
 *    members from data `ScalingLazyListState` does not carry: `layoutInfo.totalItemsCount` and
 *    `visibleItemsInfo` with each item's anchor-typed `startOffset`, `offset` and `size` (`:132`,
 *    `:136`, `:144-153`, `:159-177`), `config.value.viewportHeightPx` (`:158`) and
 *    `config.value.reverseLayout` (`:160`). Our state publishes `isScrollInProgress`,
 *    `canScrollForward`/`canScrollBackward`, `centerItemIndex` and `centerItemScrollOffset`
 *    (`lazy/WearLazyColumn.kt:51-77`) and nothing else. Worse, the two numbers upstream's
 *    `lastItemOffset` turns on are call-site parameters of the *list*, not properties of the state —
 *    `centerVertically` (`lazy/WearLazyColumn.kt:118`, forced to `false` for `TransformingLazyColumn`
 *    at `:166`) and `reverseLayout` (`:157`) — which is exactly why upstream needs two different
 *    formulas for the two list kinds (`:156-178` centre-aware, `:311-361` plain) while this port has
 *    collapsed those kinds onto one state. A scaffold-side provider would have to guess which list it
 *    is attached to and fabricate three of the five members, so it is not this file's to write: the
 *    honest shape is a layout-info reader in `lazy/`, and until then the caller builds the provider
 *    (the boundary [ScrollInfoProvider] itself declares, `ScrollAway.kt:62-75`). The indicator
 *    default has the same blocker one level down: the slot *expression* is writable here (an
 *    `@Tunable` lambda in a parameter default is legal — see [AppScaffold]'s `timeText`), but
 *    [ScrollIndicator] takes `itemCount` as a parameter precisely because `totalItemsCount` is not
 *    answerable from the state (`ScrollIndicator.kt:236-249`), so no overload could fill upstream's
 *    default without the caller supplying the very number the state was supposed to carry.
 *
 * So a caller porting `ScreenScaffold(scrollState = state, edgeButton = { … }) { padding -> … }`
 * writes `ScreenScaffold(edgeButton = { … }, scrollInfoProvider = <the provider built from that list>,
 * scrollIndicator = <a [ScrollIndicator] slot>) { padding -> … }`: same layout, same reveal, numbers
 * supplied by whoever owns the list.
 *
 * **The swipe host here is not a port of a scaffold feature** either; see [ScreenScaffold].
 */

/**
 * Opt a child of a [WearInsetLayoutView] into its inset box. The container reads this off its own
 * LayoutParams, so it has to be a layout attribute rather than a view property.
 *
 * **Not a Wear Compose API.** Wear Compose has no insets container and no per-edge flags — the
 * premise was checked against the whole reference tree and refuted at `Insets.kt:49-64`, which is
 * also where the percentage padding that replaces it is pinned. This is the helper that reaches
 * [WearInsetLayoutView.LayoutParams.boxedEdges], and the flag values are [WearBoxedEdges]' — the bit
 * pattern `androidx.wear.widget.BoxInsetLayout` shipped, which is the widget
 * [WearInsetLayoutView] replaces. The alias `object BoxedEdges` that used to sit beside it duplicated
 * [WearBoxedEdges] under a name no upstream file has and had no reader at all, so it is gone; name
 * the flags through [WearBoxedEdges].
 */
fun Modifier.boxedEdges(edges: Int): Modifier =
    this.thenLayoutAttribute<WearInsetLayoutView.LayoutParams, Int>(uniqueKey, edges) { value, _ ->
        boxedEdges = value
    }

/**
 * The app root: paints `containerColor` behind everything and hands `contentColor` down as the
 * ambient content colour, which is what a bare [Text] inside [ScreenScaffold] resolves against.
 *
 * Upstream `AppScaffold` (material3/AppScaffold.kt:54-82) does four things; the three that need no
 * registry are ported and the fourth is not:
 *  - `LocalContentColor provides contentColor` with `contentColor = contentColorFor(containerColor)`
 *    (`:58`, `:69`) and `Modifier.fillMaxSize().background(containerColor)` (`:71`) — **ported**, and
 *    the reason this container is the one that owns the colour: [ScreenScaffold] upstream provides no
 *    colour at all, so a `Text` that is not inside a `Card`/`Button`/`ListHeader` and not under an
 *    `AppScaffold` falls back to Compose's `LocalContentColor` seed, which is `Color.White`
 *    (material3/ContentColor.kt:34). This port seeds it with `Color.Unspecified` instead — see [Text] —
 *    so without this layer a bare `Text` keeps the raw `TextView` theme colour, which is not
 *    `contentColorFor(background)` and is not stable across themes.
 *  - the two boxes — **ported as two boxes**, because which one carries `modifier` is observable:
 *    upstream's outer `Box(Modifier.fillMaxSize().background(containerColor))` (`:71`) takes **no**
 *    caller modifier, and the caller's `modifier` goes on the inner one (`:73-74`). One box would put
 *    an inset modifier on the painted background, so `AppScaffold(Modifier.padding(8.dp))` would move
 *    the app's background colour in from the screen edge instead of inseting only the content.
 *  - `timeText` (`:56`) — **the parameter, its `{ TimeText() }` default and its emission are ported;
 *    its wrapper is not**, and that difference is visible. Upstream stores the slot in `ScaffoldState`
 *    (`:64-65`) and emits `scaffoldState.screenContent.timeText()` after the content (`:80`), which is
 *    the *screen's* slot when a screen registered one and the app's otherwise
 *    (`Scaffold.kt:71-82`, `:129-141`), wrapped in `Box(Modifier.fillMaxSize().scrollAway(provider) {
 *    screenStage })` (`:74-81`) so it lifts off the top as the list scrolls. The registry half is the
 *    file header's gap, so what ships here is the app-level value, emitted at upstream's position and
 *    with upstream's default, but unwrapped: the clock stays put while the list moves under it, and a
 *    [ScreenScaffold] `timeText` argument (`:554`, `:730`) has no registry to be promoted through, so
 *    it stays absent from this port's signature. A caller who wants the scroll-away behaviour passes an
 *    empty slot here — upstream's `currentContent()` would then take the app's empty value too, while
 *    ours has no per-screen override to fall back on — and puts a [TimeText] in the content under
 *    [scrollAway] with a [ScreenStage] of their own.
 *  - `AnimationCoordinator.Looper()` (`:62`) and the `graphicsLayer { scaleX = scaleY =
 *    scaffoldState.parentScale }` on the inner box (`:74-77`) — **not ported**: `parentScale`
 *    (`Scaffold.kt:54-58`) is registry state, and its only writer anywhere in the reference tree is
 *    `material3/Dialog.kt:114`, `:126` (with the reset at `:207`), i.e. the shrinking-app-behind-a-dialog
 *    effect this module's [Dialog] declares as its own gap. Without a writer the layer would be a
 *    permanent scale of 1.
 *
 * @param timeText the app-level clock slot, upstream's `:56` including its `{ TimeText() }` default —
 *   a parameter default is the position this engine does build an `@Tunable` slot lambda in, the same
 *   shape as [HorizontalPagerScaffold]'s `pageIndicator` (`PagerScaffold.kt:92`) and [Slider]'s
 *   `decreaseIcon` (`Slider.kt:141`). Emitted after [content] inside the content box, which is
 *   upstream's order (`:79-80`), so it draws over the screens the way `screenContent.timeText()` does.
 * @param containerColor null resolves to `MaterialTheme.colorScheme.background` in the body, because
 *   a `@Tunable` default expression is hoisted into a non-`@Tunable` `$default` that cannot read the
 *   theme. Same for [contentColor], which resolves through `contentColorFor` instead.
 */
@Tunable
fun AppScaffold(
    modifier: Modifier = Modifier,
    timeText: @Tunable () -> Unit = { TimeText() },
    containerColor: Color? = null,
    contentColor: Color? = null,
    content: @Tunable BoxScope.() -> Unit,
) {
    val container = containerColor ?: MaterialTheme.colorScheme.background
    val resolvedContent = contentColor ?: MaterialTheme.colorScheme.contentColorFor(container)
    val scope = content
    val clock = timeText
    Box(
        // `matchParentSize()` is this port's spelling of upstream's `Modifier.fillMaxSize()`
        // (material3/AppScaffold.kt:71). It has to be explicit: `Renderer` hands a child WRAP_CONTENT
        // params when the parent has its own `$LayoutParams` class, and a wrap_content scroll container
        // does not measure.
        modifier = Modifier
            .matchParentSize()
            .container(ContainerSpec(shape = RectangleShape, containerColor = container)),
    ) {
        // `:73-78` — the caller's modifier lands on the content layer, not on the painted background.
        Box(modifier = modifier.matchParentSize()) {
            provideContentColor(resolvedContent) {
                scope()
                // `:80`, without `:74-81`'s scroll-away wrapper; see the KDoc.
                clock()
            }
        }
    }
}

/**
 * The screen root: swipe-from-left to dismiss, content inset by [contentPadding].
 *
 * This is the port of upstream's bare `ScreenScaffold(modifier, scrollInfoProvider, contentPadding,
 * timeText, scrollIndicator, overscrollEffect, content)` (`material3/ScreenScaffold.kt:726-771`),
 * with the provider and indicator slots in and `timeText` and `overscrollEffect` absent for the two
 * reasons the file header gives, plus the two arguments upstream does not have. The `edgeButton`
 * sibling is [ScreenScaffold]'s other overload (`:549-681`).
 *
 * `ScreenScaffoldDefaults.contentPadding()` upstream is `PaddingValues(horizontal =
 * horizontalContentPadding(), vertical = verticalContentPadding())` (`:786-792`), i.e. 5.2% of the
 * width and 10% of the height; that needs the density at compose time, so it is computed here per
 * call.
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
 *
 * @param scrollInfoProvider the provider the indicator fade reads, upstream's `:728`; the only two
 *   values read are `isScrollable` and `isScrollInProgress` (`isVisible` at `:761-764`), each read
 *   once per tune — the same consumption pattern [scrollAway] publishes (`ScrollAway.kt:142-194`),
 *   never a per-frame state write bridged back into this tune, which would retune the whole body.
 * @param scrollIndicator the slot for a [ScrollIndicator] or a [HorizontalPageIndicator], upstream's
 *   `:731`; it defaults to null, because upstream's default `{ ScrollIndicator(scrollState) }` (`:130`,
 *   `:186`) reads the `layoutInfo` numbers no [ScrollInfoProvider] carries and [ScrollIndicator] needs
 *   `itemCount` explicitly for them (`ScrollIndicator.kt:236-249`). Given a [scrollInfoProvider] the
 *   slot is wrapped and faded by `view/WearScreenScaffoldIndicatorHostView`, on the visibility gate
 *   written out in [screenScaffoldSwipeHost]; without one it is emitted raw, as upstream's `:768` does.
 *   The slot runs in a `BoxScope` as upstream's does, but the wrapper resolves `Alignment.CenterEnd`
 *   itself — the same reason [WearPagerIndicatorSlotView] is handed no alignable `Box` — so an `align`
 *   written on the slot is not what places it. No scroll indicator is displayed if null is passed, as
 *   upstream's doc states (`:715-716`).
 * @param contentPadding nullable, and resolved in the body: upstream's default (`:729`) reads
 *   `ScreenScaffoldDefaults.contentPadding`, which is a `@Composable get()` (`:786-792`), and a
 *   `@Tunable` default expression is hoisted into a non-`@Tunable` `$default` that can resolve
 *   neither the theme nor the screen size.
 * @param content the body of the screen; it receives the resolved padding and is expected to consume
 *   it, exactly as upstream's `content(contentPadding)` does (`:757`).
 */
@Tunable
fun ScreenScaffold(
    modifier: Modifier = Modifier,
    scrollInfoProvider: ScrollInfoProvider? = null,
    contentPadding: PaddingValues? = null,
    scrollIndicator: (@Tunable BoxScope.() -> Unit)? = null,
    swipeToDismissEnabled: Boolean = true,
    onDismiss: (() -> Unit)? = null,
    content: @Tunable BoxScope.(PaddingValues) -> Unit,
) {
    val resolvedPadding = contentPadding ?: ScreenScaffoldDefaults.contentPadding()
    screenScaffoldSwipeHost(
        modifier = modifier,
        scrollInfoProvider = scrollInfoProvider,
        contentPadding = resolvedPadding,
        scrollIndicator = scrollIndicator,
        swipeToDismissEnabled = swipeToDismissEnabled,
        onDismiss = onDismiss,
        content = content,
    )
}

/**
 * The `edgeButton` overload of [ScreenScaffold] (`material3/ScreenScaffold.kt:549-681`): the same
 * screen with a slot pinned to the bottom edge, whose own padding is *replaced* rather than added
 * to, so the content stops exactly [edgeButtonSpacing] above it.
 *
 * Upstream's parameter list is `scrollInfoProvider` first (`:550-558`), and so is the reveal: both
 * halves of what it reads off the provider drive it — `lastItemOffset` (`:580-582`) sets the
 * wrapper's live height and `isScrollInProgress` (`:601`, `:651`) chooses between the snapped and the
 * animated height — and the wrapper's height is applied by `dynamicHeight` (`:594-606`, `:797-884`).
 * The Views equivalent is [WearScreenScaffoldEdgeButtonBandView]: given a
 * [scrollInfoProvider], this overload reads the two values once per tune — the same consumption
 * pattern [scrollAway] documents (`ScrollAway.kt:132-141`) — folds them with upstream's 16.dp
 * threshold (`:985`) and `StiffnessMediumLow` spring (`:986-987`) into one immutable
 * [ScreenScaffoldEdgeButtonMotion], and the band runs the pair rules of `:647-679` on its own clock —
 * threshold, snap-versus-spring and the velocity `Animatable.animateTo` carries into a spring that
 * replaces one already in flight, all of it inside
 * [WearScreenScaffoldEdgeButtonBandView][com.huanli233.hibari.wear.view.WearScreenScaffoldEdgeButtonBandView],
 * whose KDoc states the two things that follows: a target that changes between tunes cannot move the
 * band until the next tune, while upstream re-reads it inside the measure pass (`:601-602`, `:864`).
 * A per-frame tune read or a state bridge back into the tune is forbidden because state invalidation
 * in this engine is whole-Tunation. Nor does anything come back the other way: upstream's
 * `dynamicHeight` reports the slot's measured intrinsic height into `intrinsicButtonHeight`
 * (`:595-599`) and spends it on the content's bottom padding (`:618`), and closing that loop here
 * would cost one state write per layout pass, so the inset keeps the written-down default-size
 * intrinsic [ScreenScaffoldEdgeButtonHeight] whatever the band is doing.
 * Without a provider nothing supplies the target, so the slot stands fully revealed at its natural
 * height — the two-box placement (full-size outer box, gravity-placed inner one, because the swipe
 * host ignores a direct child's gravity) that [AlertDialogContent]'s provider-less `edgeButton` body
 * keeps permanently.
 *
 * What **is** ported is upstream's arithmetic and its geometry:
 *  - `effectiveEdgeButtonSpacing = (edgeButtonSpacing - EdgeButtonMinSpacing).coerceAtLeast(0.dp)`
 *    (`:561-562`), verbatim.
 *  - the content's bottom padding becomes `intrinsicButtonHeight + effectiveEdgeButtonSpacing`, the
 *    other edges delegating through `ReplacePaddingValues` (`:610-624`, `:886-889`) — so a caller who
 *    consumes the [PaddingValues] it is handed gets upstream's gap, which is also how upstream keeps
 *    the last list item clear of the button.
 *  - the slot's wrapper is aligned to `BottomCenter` (`:590-594`).
 *
 * `timeText` (`:554`) and `overscrollEffect` (`:557`) are absent for the two reasons the file header
 * gives. `scrollIndicator` (`:555`) is taken and forwarded, with upstream's own `null` default
 * there, and placed and faded by the shared host exactly as in the padding-only overload
 * (`:759-768`).
 *
 * @param edgeButton slot for an [EdgeButton] that hugs the bottom edge, drawn over the content the
 *   way upstream places the button slot after the main measurables (`:635-638`).
 * @param scrollInfoProvider the reveal's input, upstream's required first argument (`:550`); null —
 *   only upstream always requires it — and the slot stays fully revealed, because this module's
 *   [ScreenScaffold] does not have a non-null default for it. Reads exactly
 *   `lastItemOffset` and `isScrollInProgress`, once per tune; see the paragraph above.
 * @param scrollIndicator forwarded to the shared host unchanged, including its `null` default, which
 *   is upstream's here too (`:555`).
 * @param contentPadding nullable, resolved in the body as in [ScreenScaffold]; its bottom is ignored
 *   and replaced by the button inset, as upstream's doc states (`:527-531`).
 * @param edgeButtonSpacing the space between the button and the list content, upstream's default
 *   being [ScreenScaffoldDefaults.EdgeButtonSpacing] (`:556`). Anything below
 *   [ScreenScaffoldDefaults.EdgeButtonMinSpacing] adds nothing, because that is the padding the button
 *   already carries above and below itself — upstream floors the difference at zero rather than
 *   letting a small request turn into negative padding (`:561-562`).
 * @param content the body of the screen, handed the padding with its bottom replaced; see above.
 */
@Tunable
fun ScreenScaffold(
    edgeButton: @Tunable BoxScope.() -> Unit,
    scrollInfoProvider: ScrollInfoProvider? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
    scrollIndicator: (@Tunable BoxScope.() -> Unit)? = null,
    edgeButtonSpacing: Dp = ScreenScaffoldDefaults.EdgeButtonSpacing,
    swipeToDismissEnabled: Boolean = true,
    onDismiss: (() -> Unit)? = null,
    content: @Tunable BoxScope.(PaddingValues) -> Unit,
) {
    val resolvedPadding = contentPadding ?: ScreenScaffoldDefaults.contentPadding()
    // `ScreenScaffold.kt:561-562`: the gap the scaffold keeps beyond the button's own padding.
    val effectiveSpacing =
        (edgeButtonSpacing - ScreenScaffoldDefaults.EdgeButtonMinSpacing).coerceAtLeast(0.dp)
    val buttonPadding = ScreenScaffoldEdgePaddingValues(
        resolvedPadding,
        ScreenScaffoldEdgeButtonHeight + effectiveSpacing,
    )
    val slot = edgeButton
    val body = content
    // `:563-566` and `:577-583`: the correction, the threshold and the target, computed from one
    // provider read per tune — the consumption pattern [scrollAway] documents
    // (`ScrollAway.kt:132-141`). The provider itself is deliberately not handed to the band: a
    // number, a flag, the threshold and a spec that never changes are the whole input, and the band
    // needs no subscription to keep moving. Both dp values go through `Dp.toPx(context)`, which
    // rounds, where upstream's `Density.toPx()` (`:564`, `:566`) keeps a float; the band rounds the
    // target the same way upstream's `dynamicHeight` does (`:864`), so the difference is the half px
    // the correction and the threshold each lose here.
    val provider = scrollInfoProvider
    val motion = provider?.let {
        val context = currentContext
        ScreenScaffoldEdgeButtonMotion(
            targetHeightPx = (it.lastItemOffset - effectiveSpacing.toPx(context)).coerceAtLeast(0f),
            isScrollInProgress = it.isScrollInProgress,
            thresholdPx = EDGE_BUTTON_HEIGHT_ANIMATION_THRESHOLD.toPx(context).toFloat(),
            animationSpec = DEFAULT_EDGE_BUTTON_ANIMATION_SPEC,
        )
    }
    screenScaffoldSwipeHost(
        modifier = modifier,
        scrollInfoProvider = provider,
        contentPadding = buttonPadding,
        scrollIndicator = scrollIndicator,
        swipeToDismissEnabled = swipeToDismissEnabled,
        onDismiss = onDismiss,
    ) { paddingValues ->
        body(paddingValues)
        if (motion != null) {
            // One full-size band is upstream's `align(BottomCenter)` wrapper and the `dynamicHeight`
            // box inside it (`:590-607`) at once, and `:635-638` is what places it after the content,
            // so the button draws over it.
            Node(
                modifier = Modifier
                    .matchParentSize()
                    .viewClass(WearScreenScaffoldEdgeButtonBandView::class.java)
                    .thenViewAttribute<WearScreenScaffoldEdgeButtonBandView, ScreenScaffoldEdgeButtonMotion?>(
                        uniqueKey,
                        motion,
                    ) { this.motion = it },
                content = { with(BoxScopeInstance) { slot() } },
            )
        } else {
            // The provider-less, always-revealed shape. Two boxes rather than one, and the reason is
            // the host: every direct child of [WearSwipeToDismissView] is measured to the whole
            // content box (`view/WearSwipeToDismissView.kt:383-391`) and laid out at its top-left
            // (`:394-401`), so a gravity written on a direct child is read into params that nothing
            // consults. The outer box is that full-size frame; the inner one is a child of a
            // `FrameLayout`, where `BoxScope.gravity` does place the child — the same pair
            // [AlertDialogContent]'s `edgeButton` body relies on.
            Box(modifier = Modifier.matchParentSize()) {
                Box(modifier = Modifier.gravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)) {
                    slot()
                }
            }
        }
    }
}

/**
 * The host both [ScreenScaffold] overloads share: upstream's `Box` pair (`:756-757`) with the
 * [WearSwipeToDismissView] attributes this module adds on top of it, so the swipe chain exists once
 * instead of twice, plus the `scrollInfoProvider?.let { AnimatedIndicator(...) } ?:
 * scrollIndicator?.let { it() }` branch of `:759-768`. [content] runs in the host's `BoxScope` and
 * receives [contentPadding] — the edge-button overload arrives with the already-replaced values.
 */
@Tunable
private fun screenScaffoldSwipeHost(
    modifier: Modifier,
    scrollInfoProvider: ScrollInfoProvider?,
    contentPadding: PaddingValues,
    scrollIndicator: (@Tunable BoxScope.() -> Unit)?,
    swipeToDismissEnabled: Boolean,
    onDismiss: (() -> Unit)?,
    content: @Tunable BoxScope.(PaddingValues) -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val scrimColor = MaterialTheme.colorScheme.background
    val indicator = scrollIndicator
    val provider = scrollInfoProvider
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
        content(contentPadding)
        if (indicator != null) {
            if (provider != null) {
                // Upstream's call is `AnimatedIndicator(isVisible = { screenStage != ScreenStage.Idle
                // && provider.isScrollable }, modifier = Modifier.align(Alignment.CenterEnd),
                // content = scrollIndicator)` — `ScreenScaffold.kt:759-767`, the two terms at `:762-763`.
                // The `screenStage` term is verified as upstream's gate, and it is the registry's: the
                // `MutableState` is `ScreenContent.screenStage` (`Scaffold.kt:111`), written only by
                // `UpdateIdlingDetectorIfNeeded` (`:113-127`), which is reached through
                // `LocalScaffoldState.current` (`ScreenScaffold.kt:735`, `:744`) — the per-app registry
                // this file's header accounts for as un-ported. From those same lines the stage is:
                // `New` at first composition and again whenever the provider identity changes
                // (`Scaffold.kt:111`, `:116`), `Scrolling` while `provider.isScrollInProgress`
                // (`:117-118`), and `Idle` 2 s after an evaluation that found no scroll in progress
                // (the `delay(IDLE_DELAY)` at `:120-125`, `IDLE_DELAY` at `:192`).
                //
                // Replacing that term with `isScrollInProgress` — the conjunction shipped here is
                // `isScrollable && isScrollInProgress` — differs from upstream in three observable
                // ways, and every one of them shows the indicator LESS:
                //  - **no show-at-entry.** Upstream fades the indicator in when the screen appears and
                //    out 2 s later with nobody having scrolled (`ScreenStage.New`); here a scrollable
                //    list that is never scrolled keeps its indicator at alpha 0 forever.
                //  - **no idle hold.** Upstream keeps it fully visible for the 2 s the idling detector
                //    counts (`Scaffold.kt:120-125`); here the fade toward 0 starts on the first tune
                //    that sees `isScrollInProgress` false, so the indicator leaves with the gesture.
                //  - **tune cadence instead of snapshot cadence.** Upstream re-reads both terms inside
                //    `snapshotFlow` (`Scaffold.kt:167-169`), which re-emits on any snapshot invalidation;
                //    both reads here happen once per tune, so the fade moves only as often as something
                //    re-tunes this body, and only if the provider's own getters are backed by observable
                //    state. `isScrollable`/`isScrollInProgress` off a bare `RecyclerView.scrollState`
                //    (`lazy/WearLazyColumn.kt:51-52`) are not, so a caller that implements the provider
                //    as plain reads must re-tune for the indicator to appear at all — the same
                //    obligation [scrollAway] documents (`ScrollAway.kt:132-135`).
                // The missing term is the one the pager scaffold lacks too for its page indicator, but
                // there it is the left operand of an `||` (upstream `material3/PagerScaffold.kt:337-338`,
                // recorded at `PagerScaffold.kt:52-67`) and here the left operand of an `&&`, so the
                // two deviations are not interchangeable. The fade itself is upstream's
                // `AnimatedIndicator` half — alpha starting at 0 (`Scaffold.kt:166`) and `animate` with
                // `INDICATOR_FADE_OUT_ANIMATION` (`:170-181`) — running on the host view's clock.
                Node(
                    modifier = Modifier
                        .matchParentSize()
                        .viewClass(WearScreenScaffoldIndicatorHostView::class.java)
                        .thenViewAttribute<WearScreenScaffoldIndicatorHostView, ScreenScaffoldIndicatorFade?>(
                            uniqueKey,
                            ScreenScaffoldIndicatorFade(
                                isVisible = provider.isScrollable && provider.isScrollInProgress,
                                animationSpec = INDICATOR_FADE_OUT_ANIMATION,
                            ),
                        ) { this.fade = it },
                    content = { with(BoxScopeInstance) { indicator() } },
                )
            } else {
                // `:768`'s `?: scrollIndicator?.let { it() }`: no wrapper, no placement, no fade —
                // the slot lands in the host's `BoxScope` and is expected to carry its own
                // CenterEnd alignment, exactly as upstream expects of the caller (`:714-716`).
                indicator()
            }
        }
    }
}

/** Contains the default values used by [ScreenScaffold] */
object ScreenScaffoldDefaults {

    /**
     * The default space between [EdgeButton] and list content in [ScreenScaffold]
     * (`material3/ScreenScaffold.kt:777`).
     */
    val EdgeButtonSpacing: Dp = 16.dp

    /**
     * The minimum space between [EdgeButton] and list content (`material3/ScreenScaffold.kt:779-780`):
     * `EdgeButtonVerticalPadding`, the padding the button already carries above and below itself, so
     * a requested gap that small costs nothing extra.
     */
    val EdgeButtonMinSpacing: Dp = EdgeButtonVerticalPadding

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

/**
 * Upstream's `ReplacePaddingValues` (`material3/ScreenScaffold.kt:886-889`), the same delegating
 * `PaddingValues` that keeps every edge but the bottom; the `edgeButton` overload hands it to the
 * content at `:610-624`. This is a second private copy of it — the first is the one
 * `AlertDialog.kt` carries for `AlertDialogContent`'s `edgeButton` overloads — because both files
 * need the class and neither may reach into the other's private scope.
 */
private class ScreenScaffoldEdgePaddingValues(
    private val delegate: PaddingValues,
    private val bottomPadding: Dp,
) : PaddingValues by delegate {
    override fun calculateBottomPadding(): Dp = bottomPadding

    override fun equals(other: Any?): Boolean =
        other is ScreenScaffoldEdgePaddingValues &&
            other.delegate == delegate &&
            other.bottomPadding == bottomPadding

    override fun hashCode(): Int = 31 * delegate.hashCode() + bottomPadding.hashCode()
}

/**
 * Upstream's `intrinsicButtonHeight` (`material3/ScreenScaffold.kt:594-600`, measured by
 * `measurable.maxIntrinsicHeight` at `:866`) does not have to be measured at all for the slot this
 * overload is meant for: `EdgeButton`'s own `maxIntrinsicHeight` is
 * `buttonSize.maximumHeightPlusPadding()` — `maximumHeight + VERTICAL_PADDING * 2`
 * (`material3/EdgeButton.kt:622-625`, `:297`, `:629`) — and `EdgeButtonSize.Small` is the default
 * size (`material3/EdgeButton.kt:146`). So the number upstream's measurement returns for a default
 * [EdgeButton] is [EdgeButtonSize.Small]'s 56.dp plus twice the 3.dp the button pads itself with,
 * and that is what is written down here.
 *
 * What it cannot follow is a caller whose slot is another size, or not an [EdgeButton] at all: the
 * measurement that could answer that is the band view's own measure pass, and its result cannot reach
 * this tune without a state write per layout pass — which in this engine retunes the whole body,
 * recomputing the gap every frame of the reveal. So a non-default slot gets an inset computed for
 * Small. `AlertDialog.kt` carries the same stand-in for its own default slot, at
 * [EdgeButtonSize.Medium] because `AlertDialogDefaults.EdgeButton` is Medium.
 */
private val ScreenScaffoldEdgeButtonHeight: Dp =
    EdgeButtonSize.Small.maximumHeight + EdgeButtonVerticalPadding + EdgeButtonVerticalPadding

/**
 * `INDICATOR_FADE_OUT_ANIMATION` (`material3/Scaffold.kt:231-232`), `internal` there and the default
 * `animationSpec` of `AnimatedIndicator` (`:156`), which is what `ScreenScaffold`'s call at
 * `ScreenScaffold.kt:760-767` runs on. The one upstream name for it that this module publishes is
 * [PagerScaffoldDefaults.FadeOutAnimationSpec], the same spring re-declared for the pager side.
 */
private val INDICATOR_FADE_OUT_ANIMATION: AnimationSpec<Float> =
    spring<Float>(stiffness = Spring.StiffnessMediumLow)

/** `EDGE_BUTTON_HEIGHT_ANIMATION_THRESHOLD` — `material3/ScreenScaffold.kt:985`, private there. */
private val EDGE_BUTTON_HEIGHT_ANIMATION_THRESHOLD: Dp = 16.dp

/** `DEFAULT_EDGE_BUTTON_ANIMATION_SPEC` — `material3/ScreenScaffold.kt:986-987`, private there. */
private val DEFAULT_EDGE_BUTTON_ANIMATION_SPEC: AnimationSpec<Float> =
    spring<Float>(stiffness = Spring.StiffnessMediumLow)
