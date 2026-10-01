package com.huanli233.hibari.wear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.huanli233.hibari.animation.AnimationSpec
import com.huanli233.hibari.animation.Spring
import com.huanli233.hibari.animation.animate
import com.huanli233.hibari.animation.spring
import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.attributes.matchParentSize
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.bindState
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.locals.LocalLayoutDirection
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.CircleShape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.layout.Alignment
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.IntSize
import com.huanli233.hibari.ui.unit.LayoutDirection
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Ported from androidx.wear.compose.material3.PagerScaffold.
 *
 * Upstream's scaffold stacks a pager and a page indicator and coordinates the indicator's (and
 * `TimeText`'s) visibility with whether the pager is being paged. The stacking, the alignment, the
 * fade and [AnimatedPage]'s whole scale-and-scrim animation are ported; the coordination half is not,
 * because the app-level registry it hangs on is not in Hibari.
 *
 * **Not ported** — `PagerScaffold.kt:312-330`, the head of `PagerScaffoldImpl`:
 * `LocalScaffoldState.current`, `screenContent.updateIfNeeded(key, timeText = null,
 * scrollInfoProvider)`, the `DisposableEffect { onDispose { removeScreen(key) } }`,
 * `UpdateIdlingDetectorIfNeeded()` and the `LaunchedEffect(screenIsActive, scaffoldState)` that adds or
 * removes the screen. That is the app scaffold's registry of "which screen is showing, what scrolls
 * it, is it idling", and what is missing is the registry, **not** the scroll-info plumbing:
 * [ScrollInfoProvider] is ported (`ScrollAway.kt:76-103`, all five values) and [ScreenStage] with it
 * (`ScrollAway.kt:33-53`). Two things have no counterpart: `LocalScaffoldState` / `ScaffoldState` /
 * `ScreenContent` themselves (`material3/Scaffold.kt:51-150`), and the `LocalScreenIsActive` the
 * registry writes behind (`PagerScaffold.kt:323`), which `Pager.kt:573-576`, `WearPagerView.kt:104`
 * and `WearSwipeToDismissView.kt:82` all record as absent. Upstream's
 * `ScrollInfoProvider(pagerState)` factory (`PagerScaffold.kt:86`,
 * `foundation/ScrollInfoProvider.kt:122-123`) has no counterpart either, and for a specific reason:
 * its `isScrollable` reads `state.canScrollBackward || state.canScrollForward` (`:378-379`), the two
 * `ScrollableState` accessors [PagerState] declares as not ported (`Pager.kt:211-216`).
 *
 * What survives of the visibility rule is the pager half of `:337-338`, `pagerState.isScrollInProgress`;
 * `screenStage.value != ScreenStage.Idle` — the other half of that `||`, which is what holds the
 * indicator up while the idling detector still counts the wearer as reading — cannot be evaluated
 * here. So with [PagerScaffoldDefaults.FadeOutAnimationSpec] the indicator fades as soon as the pager
 * settles instead of after upstream's 2 s idle delay (`material3/Scaffold.kt:120-126`, `IDLE_DELAY` at
 * `:192`).
 */

/**
 * One of the Wear Material3 scaffold components: the structure of a horizontal pager, with its page
 * indicator centred on the bottom edge.
 *
 * Ported from `PagerScaffold.kt:76-93`; the indicator alignment is `Alignment.BottomCenter` there
 * (`:91`).
 *
 * @param pagerState the state of the pager controlling the page content.
 * @param modifier the modifier to be applied to the scaffold.
 * @param pageIndicator the page indicator to display, or `null` for none. Upstream's default is
 *   `{ HorizontalPageIndicator(pagerState) }` (`PagerScaffold.kt:80`) and that is the default here,
 *   now that [HorizontalPageIndicator] takes a [PagerState] of its own: the live page numbers reach the
 *   drawing pass through the indicator's own `bindState` channel — see [PageIndicatorImpl] — so the
 *   slot no longer has to settle for a tune-time page count and a guessed current page.
 * @param pageIndicatorAnimationSpec null, so the indicator is visible at all times; pass
 *   [PagerScaffoldDefaults.FadeOutAnimationSpec] to show it only while paging.
 * @param content where the [HorizontalPager] goes.
 */
@Tunable
fun HorizontalPagerScaffold(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    pageIndicator: (@Tunable () -> Unit)? = {
        HorizontalPageIndicator(pagerState = pagerState)
    },
    pageIndicatorAnimationSpec: AnimationSpec<Float>? = null,
    content: @Tunable () -> Unit,
) {
    PagerScaffoldImpl(
        pagerState = pagerState,
        modifier = modifier,
        pageIndicator = pageIndicator,
        pageIndicatorAlignment = Alignment.BottomCenter,
        pageIndicatorAnimationSpec = pageIndicatorAnimationSpec,
        pager = content,
    )
}

/**
 * The vertical sibling of [HorizontalPagerScaffold], ported from `PagerScaffold.kt:133-150`: a
 * [VerticalPageIndicator] at `Alignment.CenterEnd` (`:148`), which is the right edge in LTR and the
 * left in RTL because it really is an end-alignment here, not a hardcoded gravity.
 *
 * @param pagerState the state of the pager controlling the page content.
 * @param modifier the modifier to be applied to the scaffold.
 * @param pageIndicator the page indicator, or `null` for none; upstream's default is
 *   `{ VerticalPageIndicator(pagerState) }` (`PagerScaffold.kt:137`), and see
 *   [HorizontalPagerScaffold] for how its numbers reach the drawing pass now that the indicator takes
 *   a [PagerState].
 * @param pageIndicatorAnimationSpec null keeps the indicator visible; see [HorizontalPagerScaffold].
 * @param content where the [VerticalPager] goes.
 */
@Tunable
fun VerticalPagerScaffold(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    pageIndicator: (@Tunable () -> Unit)? = {
        VerticalPageIndicator(pagerState = pagerState)
    },
    pageIndicatorAnimationSpec: AnimationSpec<Float>? = null,
    content: @Tunable () -> Unit,
) {
    PagerScaffoldImpl(
        pagerState = pagerState,
        modifier = modifier,
        pageIndicator = pageIndicator,
        pageIndicatorAlignment = Alignment.CenterEnd,
        pageIndicatorAnimationSpec = pageIndicatorAnimationSpec,
        pager = content,
    )
}

@Tunable
private fun PagerScaffoldImpl(
    pagerState: PagerState,
    modifier: Modifier,
    pageIndicator: (@Tunable () -> Unit)?,
    pageIndicatorAlignment: Alignment,
    pageIndicatorAnimationSpec: AnimationSpec<Float>?,
    pager: @Tunable () -> Unit,
) {
    val indicator = pageIndicator
    Box(modifier = modifier.matchParentSize()) {
        // `Box(modifier) { pager(); AnimatedIndicator(...) }` (`PagerScaffold.kt:332-344`): the pager
        // is the bottom layer and the indicator is drawn over it, aligned inside the same box.
        pager()
        if (indicator != null) {
            // Upstream's `Modifier.align(pageIndicatorAlignment)` (`:341`) is applied by the slot view
            // itself, which is what lets the end-alignment resolve against the layout direction; the
            // caller's slot is not handed a `BoxScope`, having no use for `align` any more.
            //
            // This view carries the fade and the alignment only — upstream's `AnimatedIndicator`
            // (`material3/Scaffold.kt:152-188`) plus that `align`. The page numbers are the
            // indicator's own business now that [HorizontalPageIndicator] and [VerticalPageIndicator]
            // take a [PagerState]: the default slot binds them on itself, and a caller-supplied slot
            // that does not bind anything simply does not move, which is also what upstream's
            // `pageIndicator` parameter does with a slot that reads no state.
            Node(
                modifier = Modifier
                    .matchParentSize()
                    .viewClass(WearPagerIndicatorSlotView::class.java)
                    .thenViewAttribute<WearPagerIndicatorSlotView, Alignment>(
                        uniqueKey,
                        pageIndicatorAlignment,
                    ) { this.indicatorAlignment = it }
                    .thenViewAttribute<WearPagerIndicatorSlotView, AnimationSpec<Float>?>(
                        uniqueKey,
                        pageIndicatorAnimationSpec,
                    ) { this.fadeAnimationSpec = it }
                    .bindState(uniqueKey, pagerState.scrollValues) {
                        (this as WearPagerIndicatorSlotView).onScrollValues(it)
                    },
                content = indicator,
            )
        }
    }
}

/**
 * Animates a page within a pager with a scaling and scrim effect based on its position, ported from
 * `PagerScaffold.kt:168-238`.
 *
 * The numbers are transcribed literally: the offset is quantised to `screenWidthDp / 2` intervals
 * (`:178-187`, `screenHeightDp / 2` for a vertical pager), the page scales from 1 to 0.55 (`:211`)
 * around a pivot that swaps between its far and near edge depending on which page is moving and which
 * way (`:193-208`), a `contentScrimColor` scrim of up to half alpha is drawn over the content
 * (`:220-231`), and everything is clipped to [CircleShape] (`:234`).
 *
 * Three adaptations, none of them to a number:
 *  - `LocalReduceMotion` (`:175`) has no Hibari local, so it is read from the Wear `reduce_motion`
 *    setting at tune time, through the module's one source [wearReduceMotionEnabled]. The branch
 *    itself is faithful: reduce motion removes the scale layer (`:190`) and leaves the scrim
 *    and the clip alone.
 *  - the axis is not read from [PagerState.layoutInfo] at all: that getter takes a snapshot read of
 *    the per-frame scroll state and would re-tune this page on every frame of a drag. Upstream gets
 *    the same "sample once and hold it" for free from
 *    `remember(pagerState) { pagerState.layoutInfo.orientation }` (`:177`), and
 *    [PagerState.nodeOrientation] is that held value here — a plain field, so reading it subscribes
 *    nothing. Both uses of the axis (which axis to quantise against, which axis to pivot on) are axis
 *    choices, not per-frame numbers.
 *  - `contentScrimColor` defaults to null and resolves in the body: a `@Tunable` default expression
 *    cannot read `MaterialTheme`.
 *
 * @param pageIndex the index of the page being animated.
 * @param pagerState the state of the pager showing this page.
 * @param contentScrimColor the colour of the scrim applied during transitions; `Color.Unspecified`
 *   applies none, which is what upstream's `isSpecified` test at `:220` means.
 * @param content the content of the page.
 */
@Tunable
fun AnimatedPage(
    pageIndex: Int,
    pagerState: PagerState,
    contentScrimColor: Color? = null,
    content: @Tunable () -> Unit,
) {
    val scrim = contentScrimColor ?: MaterialTheme.colorScheme.background
    val direction = LocalLayoutDirection.current
    val context = currentContext
    val configuration = context.resources.configuration
    val isHorizontal = pagerState.nodeOrientation == PagerOrientation.Horizontal
    val spec = PagerAnimatedPageSpec(
        pageIndex = pageIndex,
        // `(if (orientation == Orientation.Horizontal) screenWidthDp() else screenHeightDp()) / 2`
        // (`PagerScaffold.kt:178-179`).
        numberOfIntervals =
            (if (isHorizontal) configuration.screenWidthDp else configuration.screenHeightDp) / 2f,
        isHorizontal = isHorizontal,
        isRtl = direction == LayoutDirection.Rtl,
        reduceMotion = wearReduceMotionEnabled(context),
        contentScrimColor = scrim,
    )
    Node(
        modifier = Modifier
            .matchParentSize()
            .viewClass(WearPagerAnimatedPageView::class.java)
            .thenViewAttribute<WearPagerAnimatedPageView, PagerAnimatedPageSpec>(uniqueKey, spec) {
                this.spec = it
            }
            .bindState(uniqueKey, pagerState.scrollValues) {
                (this as WearPagerAnimatedPageView).onScrollValues(it)
            }
            .thenViewAttribute<WearPagerAnimatedPageView, PagerState>(
                uniqueKey,
                pagerState,
            ) { bindPagerState(it) },
        content = content,
    )
}

/**
 * Everything [AnimatedPage] decides at tune time, in one immutable value so a retune that changes
 * nothing leaves the view alone. The per-frame pair stays out of it: see [WearPagerAnimatedPageView].
 */
data class PagerAnimatedPageSpec(
    val pageIndex: Int,
    val numberOfIntervals: Float,
    val isHorizontal: Boolean,
    val isRtl: Boolean,
    val reduceMotion: Boolean,
    val contentScrimColor: Color,
)

/** Contains default values used for [HorizontalPagerScaffold] and [VerticalPagerScaffold]. */
object PagerScaffoldDefaults {
    /**
     * High `SnapPositionalThreshold`, for contexts where even a light gesture should trigger movement,
     * e.g. navigating a long list (`PagerScaffold.kt:241-249`).
     */
    val HighSnapPositionalThreshold: Float = 0.35f

    /**
     * Recommended `SnapPositionalThreshold` when the wearer is moving, or with fewer than ten pages
     * (`PagerScaffold.kt:251-265`).
     */
    val LowSnapPositionalThreshold: Float = 0.1f

    /**
     * The fade spec to hand `pageIndicatorAnimationSpec` so the indicator only shows during paging
     * (`PagerScaffold.kt:294-298`).
     *
     * The value is `INDICATOR_FADE_OUT_ANIMATION`, `spring(stiffness = Spring.StiffnessMediumLow)`
     * (`material3/Scaffold.kt:231-232`); that declaration is file-`internal` there and unreachable
     * from a source set that may not import `androidx.compose.*`, and this port's `Scaffold.kt` keeps
     * its own private copy for the scroll-indicator slot (`INDICATOR_FADE_OUT_ANIMATION` there), so
     * the same spring is written out here as the public value upstream exposes through this name.
     */
    val FadeOutAnimationSpec: AnimationSpec<Float> =
        spring<Float>(stiffness = Spring.StiffnessMediumLow)

    /**
     * The recommended fling behaviour for a Wear pager under Material3: snap at most one page at a
     * time, with the high positional threshold (`PagerScaffold.kt:267-292`).
     *
     * Upstream returns a `TargetedFlingBehavior`; this returns the same three tuned numbers as a
     * [PagerFlingSpec], to pass as `flingSpec = PagerScaffoldDefaults.snapWithSpringFlingBehavior(s)`
     * on [HorizontalPager]/[VerticalPager]. Upstream's snap spec is
     * `MaterialTheme.motionScheme.defaultSpatialSpec()` (`:289`). `MaterialTheme.motionScheme` exists
     * (Theme.kt:51) but is not read here: this factory has to stay callable from a `@Tunable` default
     * expression, and those are hoisted into a non-`@Tunable` `$default` where no tuner reaches them.
     * So [PagerDefaults.SnapAnimationSpec] — the no-bounce, stiffness-2000 spring the wear pager
     * settles with everywhere else in this port — is used, and it is a different spring from the
     * theme's standard spatial one.
     */
    fun snapWithSpringFlingBehavior(state: PagerState): PagerFlingSpec =
        PagerDefaults.snapFlingBehavior(
            state = state,
            maxFlingPages = 1,
            snapAnimationSpec = PagerDefaults.SnapAnimationSpec,
            snapPositionalThreshold = HighSnapPositionalThreshold,
        )
}

/**
 * `getPageTransitionFraction` (`PagerScaffold.kt:347-357`), verbatim: the page leaving scales down as
 * the offset grows, the one arriving scales up as it shrinks, hence the inverted branch.
 */
private fun getPageTransitionFraction(
    isCurrentPage: Boolean,
    currentPageOffsetFraction: Float,
): Float {
    return if (isCurrentPage) {
        abs(currentPageOffsetFraction)
    } else {
        // interpolate left or right pages in opposite direction
        1 - abs(currentPageOffsetFraction)
    }
}

/**
 * `androidx.compose.ui.util.lerp`, the helper upstream imports at `material3/PagerScaffold.kt:41` and
 * calls twice in `AnimatedPage`: `lerp(start = 1f, stop = 0.55f, fraction = pageTransitionFraction)`
 * for the scale (`:211`) and `lerp(start = 0f, stop = 0.5f, fraction = pageTransitionFraction)` for the
 * scrim alpha (`:228`). Both call sites here pass the same start/stop pair.
 */
private fun pagerLerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

/**
 * The box the page indicator is placed in: upstream's `AnimatedIndicator`
 * (`material3/Scaffold.kt:152-188`) fused with `Modifier.align(pageIndicatorAlignment)`
 * (`PagerScaffold.kt:341`), both of which need a container once there is no `BoxScope`.
 *
 *  - The child is placed through [Alignment.align], so [Alignment.CenterEnd] really is the left edge
 *    under RTL, exactly as upstream's end-alignment is. The slot fills the scaffold box and the
 *    indicator is resolved inside it, which is what `align` does in a `Box`.
 *  - With `fadeAnimationSpec == null` the indicator is always visible (`Scaffold.kt:161-163`); with a
 *    spec it starts at alpha 0 (`Scaffold.kt:166`) and animates to 1 while the pager is scrolling and
 *    back to 0 when it settles (`Scaffold.kt:167-181`'s `snapshotFlow { isVisible() }` + `animate`).
 *    The input is only [PagerState.isScrollInProgress] — see the file KDoc for the missing half.
 *  - It no longer feeds the indicator any page numbers. Upstream's indicator reads
 *    `currentPage`/`currentPageOffsetFraction` inside its own draw pass
 *    (`material3/PageIndicator.kt:219`), and [PageIndicatorImpl] now does the same for itself, so this
 *    view is only the `AnimatedIndicator` half — the box, the alignment and the alpha.
 */
class WearPagerIndicatorSlotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    /** Upstream's `pageIndicatorAlignment`, applied to the single child. */
    var indicatorAlignment: Alignment = Alignment.BottomCenter
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /**
     * The fade spec, or `null` for "always visible". Gaining or losing a spec mid-fade finishes the
     * current fade, which is what `Scaffold.kt:161` renders when the branch changes.
     */
    var fadeAnimationSpec: AnimationSpec<Float>? = null
        set(value) {
            if (field === value) return
            field = value
            fadeJob?.cancel()
            fadeJob = null
            lastScrolling = null
            if (value == null) {
                alpha = 1f
            } else {
                // `remember { mutableFloatStateOf(0f) }` (`Scaffold.kt:166`): an indicator that has
                // just gained a fade spec starts hidden and is animated in if the pager is moving.
                alpha = 0f
            }
        }

    private var lastScrolling: Boolean? = null
    private var fadeJob: Job? = null
    private var childLeft = 0
    private var childTop = 0
    private val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The `bindState` channel: one frame of the pager's scroll position, of which this view uses only
     * [PagerScroll.isScrollInProgress] — the fade's input. The page numbers go to the indicator itself;
     * see the class KDoc.
     */
    internal fun onScrollValues(values: PagerScroll) {
        syncFade(values.isScrollInProgress)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
        val child = singleChild() ?: return
        // The indicator sizes itself from its own spec, so it gets `AT_MOST` bounds and resolves
        // inside them — the wrap-content half of a `Box` child.
        child.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST),
        )
        val offset = indicatorAlignment.align(
            size = IntSize(child.measuredWidth, child.measuredHeight),
            space = IntSize(width, height),
            layoutDirection = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            },
        )
        childLeft = offset.x
        childTop = offset.y
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = singleChild() ?: return
        child.layout(childLeft, childTop, childLeft + child.measuredWidth, childTop + child.measuredHeight)
    }

    /**
     * `AnimatedIndicator`'s `LaunchedEffect(isVisible) { snapshotFlow { … }.distinctUntilChanged()
     * .collectLatest { animate(…) } }` (`Scaffold.kt:167-181`) as a change test on the value that
     * drives it, arriving on the same frame channel that moves the indicator.
     */
    private fun syncFade(isScrolling: Boolean) {
        val spec = fadeAnimationSpec ?: return
        if (lastScrolling == isScrolling) return
        lastScrolling = isScrolling
        fadeJob?.cancel()
        val target = if (isScrolling) 1f else 0f
        val from = alpha
        if (from == target) return
        fadeJob = animationScope.launch {
            try {
                animate(from, target, 0f, spec) { value, _ -> alpha = value }
            } finally {
                fadeJob = null
            }
        }
    }

    override fun onDetachedFromWindow() {
        fadeJob?.cancel()
        fadeJob = null
        lastScrolling = null
        super.onDetachedFromWindow()
    }

    private fun singleChild(): View? = if (childCount == 0) null else getChildAt(0)
}

/**
 * The container [AnimatedPage] animates: `Modifier.graphicsLayer { scaleX, scaleY, transformOrigin }`
 * then `.drawWithContent { drawContent(); drawCircle(scrim) }` then `.clip(CircleShape)`
 * (`PagerScaffold.kt:189-237`).
 *
 * The scale and the pivot are written onto the view itself, which is what a `graphicsLayer` is. The
 * scrim and the clip happen in [dispatchDraw] in upstream's order — content, then scrim, both inside
 * the circle — because `clip` is the innermost modifier of that chain.
 *
 * Sizing follows upstream's bare `Box`: the view resolves to its content, so on a page that is not a
 * full circle the circle is inscribed in the content box. Compose's `CircleShape` is a circle of
 * `min/2` radius; an `addOval` over the same box is identical when the box is square, which a
 * full-bleed Wear page is, and an oval rather than a circle when it is not.
 */
class WearPagerAnimatedPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    /** Immutable per-tune inputs; see [PagerAnimatedPageSpec]. */
    var spec: PagerAnimatedPageSpec? = null
        set(value) {
            field = value
            invalidate()
        }

    private var pagerState: PagerState? = null
    private val clipPath = Path()
    private val clipBounds = RectF()
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Bound by [AnimatedPage] through a state-valued attribute rather than a `Modifier.ref`, because a
     * `ref` runs only at view creation (`Renderer.kt:162`) and would keep quantising against a state
     * the caller has since replaced. The page indicator no longer needs the same treatment: it takes
     * the [PagerState] itself and binds the scroll channel on its own view — see
     * [PageIndicatorImpl].
     */
    fun bindPagerState(state: PagerState) {
        pagerState = state
    }

    /** One frame of the pager's scroll position, from the `bindState` channel. */
    internal fun onScrollValues(values: PagerScroll) {
        applyTransform(values)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = suggestedMinimumWidth
        var height = suggestedMinimumHeight
        var contentWidth = 0
        var contentHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            // A bare `Box` gives its child the parent's constraints and takes the union of what the
            // children asked for.
            child.measure(widthMeasureSpec, heightMeasureSpec)
            contentWidth = maxOf(contentWidth, child.measuredWidth)
            contentHeight = maxOf(contentHeight, child.measuredHeight)
        }
        width = resolveSize(contentWidth, widthMeasureSpec)
        height = resolveSize(contentHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        }
        val state = pagerState ?: return
        applyTransform(state.peekScroll())
    }

    /**
     * The body of `graphicsLayer { … }` (`PagerScaffold.kt:189-214`), including the quantisation
     * upstream does in a `derivedStateOf` (`:181-187`) — a per-frame job with a per-frame consumer, so
     * it happens here rather than in a subscription that would re-tune the screen.
     */
    private fun applyTransform(values: PagerScroll) {
        val config = spec ?: return
        if (width == 0 && height == 0) return
        if (config.reduceMotion) {
            // `if (isReduceMotionEnabled) Modifier` (`:190`): no layer at all, so no scale and no
            // pivot, while the scrim and the clip below still apply.
            if (scaleX != 1f || scaleY != 1f) {
                scaleX = 1f
                scaleY = 1f
            }
            invalidate()
            return
        }
        val raw = values.currentPageOffsetFraction
        val intervals = config.numberOfIntervals
        // `(fraction * n).toInt() / n` (`:183-186`) — truncation, upstream's own rounding.
        val offsetFraction = if (intervals > 0f) {
            (raw * intervals).toInt() / intervals
        } else {
            raw
        }
        val direction = if (config.isRtl) -1f else 1f
        val isSwipingRightToLeft = direction * offsetFraction > 0f
        val isSwipingLeftToRight = direction * offsetFraction < 0f
        val isCurrentPage = config.pageIndex == values.currentPage
        val shouldAnchorRight =
            (isSwipingRightToLeft && isCurrentPage) || (isSwipingLeftToRight && !isCurrentPage)
        val pivotFraction = if (shouldAnchorRight) 1f else 0f
        val pivotX = if (config.isHorizontal) pivotFraction * width else width / 2f
        val pivotY = if (config.isHorizontal) height / 2f else pivotFraction * height
        val transition = getPageTransitionFraction(isCurrentPage, offsetFraction)
        val scale = pagerLerp(1f, PageEndScale, transition)
        if (this.pivotX != pivotX) this.pivotX = pivotX
        if (this.pivotY != pivotY) this.pivotY = pivotY
        if (scaleX != scale || scaleY != scale) {
            scaleX = scale
            scaleY = scale
        }
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val config = spec
        val radius = minOf(width, height) / 2f
        if (radius > 0f) {
            clipBounds.set(
                width / 2f - radius,
                height / 2f - radius,
                width / 2f + radius,
                height / 2f + radius,
            )
            clipPath.reset()
            clipPath.addOval(clipBounds, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
        }
        super.dispatchDraw(canvas)
        if (config != null && radius > 0f && config.contentScrimColor.isSpecified) {
            val state = pagerState
            val values = state?.peekScroll()
            if (values != null) {
                val isCurrentPage = config.pageIndex == values.currentPage
                // The quantisation of `:181-187` feeds the scrim too (`:224`), reduce motion or
                // not — only the graphicsLayer branch of `:190` is dropped for reduce motion.
                val fraction = if (config.numberOfIntervals <= 0f) {
                    values.currentPageOffsetFraction
                } else {
                    (values.currentPageOffsetFraction * config.numberOfIntervals).toInt() /
                        config.numberOfIntervals
                }
                val transition = getPageTransitionFraction(isCurrentPage, fraction)
                // `contentScrimColor.copy(alpha = lerp(0f, 0.5f, fraction))` (`:225-229`).
                scrimPaint.color = config.contentScrimColor
                    .copy(alpha = pagerLerp(0f, MaxScrimAlpha, transition))
                    .toArgb()
                canvas.drawCircle(width / 2f, height / 2f, radius, scrimPaint)
            }
        }
        if (radius > 0f) canvas.restore()
    }

    private companion object {
        /** `lerp(start = 1f, stop = 0.55f, …)` (`PagerScaffold.kt:211`). */
        const val PageEndScale = 0.55f

        /** `lerp(start = 0f, stop = 0.5f, …)` (`PagerScaffold.kt:226-229`). */
        const val MaxScrimAlpha = 0.5f
    }
}
