package com.huanli233.hibari.wear

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.huanli233.hibari.animation.AnimationSpec
import com.huanli233.hibari.animation.FloatDecayAnimationSpec
import com.huanli233.hibari.animation.FloatExponentialDecaySpec
import com.huanli233.hibari.animation.Spring
import com.huanli233.hibari.animation.animate
import com.huanli233.hibari.animation.spring
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.attributes.matchParentSize
import com.huanli233.hibari.runtime.State
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.bindState
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.currentTuner
import com.huanli233.hibari.runtime.locals.LocalLayoutDirection
import com.huanli233.hibari.runtime.mutableStateOf
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.ref
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.LayoutDirection
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.calculateEndPadding
import com.huanli233.hibari.ui.unit.calculateStartPadding
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.unit.toPx
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.view.WearPagerView
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.Job

/**
 * Ported from androidx.wear.compose.foundation.pager.{Pager, PagerState, PagerScope,
 * PagerLayoutInfo} — the whole Wear paging surface, re-expressed on `android.view.View` through
 * [WearPagerView].
 *
 * ## What the reference tree does and does not contain
 *
 * The four upstream files are 717 lines in total, and they are a *wrapper*: `HorizontalPager`
 * (`Pager.kt:107-200`) and `VerticalPager` (`:241-294`) configure and then delegate to
 * `androidx.compose.foundation.pager.HorizontalPager`/`VerticalPager`, and `PagerStateImpl`
 * (`PagerState.kt:195-224`) *extends* `androidx.compose.foundation.pager.PagerState`. **Neither
 * compose-foundation file exists in the reference tree** — only the `androidx.wear.compose` tree is
 * there.
 * So the half of upstream's semantics that is readable and ported here is the Wear half: page-size
 * `Fill` with zero page spacing and `SnapPosition.Start` (`Pager.kt:172-180`, `:269-277`), the
 * 1.10× touch-slop multiplier (`Pager.kt:126`, `:415`), the left-edge gesture exclusion for
 * swipe-to-dismiss (`Pager.kt:313-340`), the per-page focus group (`:187-193`), the one-page-per-fling
 * snap defaults (`Pager.kt:369-397`), the state surface (`PagerState.kt:66-193`) and the
 * `PagerLayoutInfo` contract (`PagerLayoutInfo.kt:27-33`).
 *
 * The half that is **not reachable from this source** is compose-foundation's: its exact
 * `currentPage`/`currentPageOffsetFraction` derivation from the raw scroll offset, its
 * decay-then-snap `SnapFlingBehavior` pipeline, its pre-fetcher, the measure-time cost model behind
 * `beyondViewportPageCount`, and the rule behind `animateScrollToPage`'s "pre-jump to a nearer page"
 * (`PagerState.kt:146-150`) — for which this port substitutes the approximation documented on
 * [PagerState.animateScrollToPage]. Where a number is needed and unreadable, this file states the
 * formula it uses and why that matches the KDoc that *is* readable (`PagerState.kt:62-64`, `:79-84`,
 * `:94-104`; `Pager.kt:360-367`).
 *
 * ## Architecture
 *
 * [WearPagerView] is a `ViewGroup` that owns the scroll offset itself and composes a *window* of
 * pages — every page intersecting the padded viewport plus `beyondViewportPageCount` on each side —
 * through `hibari-recyclerview`'s `HibariViewHolder`, which is upstream's composition model rather
 * than an approximation of it. A `RecyclerView` + `PagerSnapHelper` was rejected because the snap rule
 * the Wear API promises (`maxFlingPages`, `snapPositionalThreshold`) is not expressible through
 * `PagerSnapHelper`; the reasons are on the view's own KDoc.
 *
 * ## Which state is observable at what rate
 *
 * A state read inside a `@Tunable` body subscribes that whole subtree, so the per-frame offset and the
 * page-granular current page are separate snapshots: [currentPage] changes once per page crossed,
 * while [currentPageOffsetFraction] changes every frame of a drag. Reading the latter inside a
 * `@Tunable` body re-tunes that subtree per frame — the warning upstream gives at
 * `PagerState.kt:106-116`, but at coarser granularity here — which is why the components that follow a
 * drag ([AnimatedPage], [WearPagerIndicatorSlotView]) take the value through `bindState` instead of
 * reading it.
 */

/**
 * The axis a pager scrolls along — the port of `androidx.compose.foundation.gestures.Orientation` as
 * used by `PagerLayoutInfo.orientation` (`PagerLayoutInfo.kt:31`).
 *
 * **Substituted type.** Compose's `Orientation` lives in `androidx.compose.foundation.gestures`, which
 * is not in the reference tree and may not be imported here. `androidx.viewpager2`'s `ORIENTATION_*`
 * constants are not used either: that artifact only reaches this compile classpath transitively,
 * through `com.google.android.material`, and pinning a public axis type to a transitive dependency of
 * an unrelated widget is not a foundation worth having. The two values carry the same meaning.
 */
enum class PagerOrientation {
    Horizontal,
    Vertical,
}

/**
 * Contains useful information about the currently displayed layout state of a [HorizontalPager] or
 * [VerticalPager], available after the first measure pass.
 *
 * Ported from `PagerLayoutInfo.kt:27-33`, including the `sealed` modifier (legal here because the
 * only implementation is in this module).
 */
sealed interface PagerLayoutInfo {
    /** The main axis size of the pages in this pager, in pixels. */
    val pageSize: Int

    /** The orientation of this pager. */
    val orientation: PagerOrientation
}

/**
 * `PagerLayoutInfoImpl` (`PagerState.kt:226-245`): a value-equal view of the live layout state, so
 * a subscriber that compares it does not see churn. Upstream's `equals`/`hashCode` are exactly the
 * two properties, which is what a `data class` generates.
 */
internal data class PagerLayoutInfoImpl(
    override val pageSize: Int,
    override val orientation: PagerOrientation,
) : PagerLayoutInfo

/**
 * The marker receiver of a pager's content lambda (`PagerScope.kt:19-21`). Upstream declares it
 * `sealed interface` with one internal object receiver and no members — it exists so a page body
 * cannot borrow a scope from another container — and that is reproduced literally.
 */
sealed interface PagerScope

internal object WearPagerScopeImpl : PagerScope

/**
 * The port of `androidx.wear.compose.foundation.GestureInclusion` as `PagerDefaults.gestureInclusion`
 * uses it (`Pager.kt:313-340`, `:142-157`).
 *
 * **Signature adapted, behaviour not.** Upstream's predicate takes `(offset: Offset,
 * layoutCoordinates: LayoutCoordinates)` and calls `localToScreen` / `findRootCoordinates().size`
 * itself. Hibari has no `LayoutCoordinates`, so [WearPagerView] does those two lookups — it is the
 * view that owns the pointer position and knows its root — and hands the resolved numbers over. The
 * rule applied is upstream's verbatim: on the first page only, a gesture that starts to the left of
 * `edgeZoneFraction` of the screen width is left for a swipe-to-dismiss handler; on any other page,
 * or while touch exploration is on, the pager always keeps the gesture.
 */
interface PagerGestureInclusion {
    /**
     * @param screenX the gesture start, in screen pixels on the x axis, as `localToScreen` gave
     *   upstream.
     * @param screenWidthPx the root width in pixels, as `findRootCoordinates().size.width` gave
     *   upstream.
     * @param state the state whose [PagerState.currentPage] decides whether this is the first page.
     */
    fun ignoreGestureStart(screenX: Float, screenWidthPx: Float, state: PagerState): Boolean
}

/**
 * The three tuned numbers behind [PagerDefaults.snapFlingBehavior], which upstream folds into a
 * `TargetedFlingBehavior`.
 *
 * **Substituted type.** `TargetedFlingBehavior`, `SnapLayoutInfoProvider` and
 * `PagerSnapDistance.atMost(maxFlingPages)` (`Pager.kt:377-383`) are compose-foundation gestures with
 * no counterpart in Hibari and no source in the reference tree, so they cannot be ported as objects.
 * The behaviour they encode — snap at most [maxFlingPages] pages, snap back unless
 * [snapPositionalThreshold] of a page has been scrolled, settle with [snapAnimationSpec] — is
 * implemented by the pager view's snap step from these values, exactly as `Pager.kt:360-367`
 * documents them.
 */
data class PagerFlingSpec(
    val maxFlingPages: Int = 1,
    val decayAnimationSpec: FloatDecayAnimationSpec = PagerDefaults.DefaultDecayAnimationSpec,
    val snapAnimationSpec: AnimationSpec<Float> = PagerDefaults.SnapAnimationSpec,
    val snapPositionalThreshold: Float = 0.5f,
)

/**
 * Creates and remembers a [PagerState] to be used with a Wear pager.
 *
 * Ported from `PagerState.kt:46-56`. `rememberSaveable(saver = PagerState.Saver)` becomes
 * [remember]: Hibari declares no `rememberSaveable` and no `Saver` type in any module, so there is
 * nothing to plug `PagerState.Saver` (`PagerState.kt:173-192`) into and the page position does not
 * survive process or config restoration. The gap is the missing hook, not the platform — Android's
 * own `Bundle` restoration is available and `PagerState` has only ints and a float to put in it —
 * so this is unwritten, which is why it is stated here rather than left silent.
 *
 * The `.apply { pagerState.pageCountState.value = pageCount }` of `:55` is kept: the provider lambda
 * is re-assigned on every tune, so a `pageCount` that closes over changing data is re-read without
 * rebuilding the state (and with it the scroll position).
 *
 * @param initialPage the page shown first.
 * @param initialPageOffsetFraction how far that page is offset from its snapped position, as a
 *   fraction of the page size; upstream constrains it to -0.5..0.5 (`PagerState.kt:49-50`).
 * @param pageCount the number of pages this pager will have.
 */
@Tunable
fun rememberPagerState(
    initialPage: Int = 0,
    initialPageOffsetFraction: Float = 0f,
    pageCount: () -> Int,
): PagerState {
    val state = remember { PagerState(initialPage, initialPageOffsetFraction, pageCount) }
    state.pageCountProvider = pageCount
    return state
}

/**
 * The state of a Wear [HorizontalPager] or [VerticalPager], ported from `PagerState.kt:66-193`.
 *
 * Upstream splits this class in two only because the outer half has to delegate to a
 * `ComposePagerState` it wraps (`PagerState.kt:73`, `:195-224`); with no wrapped state there is
 * nothing to split, so the fields live here directly. `: ScrollableState` is **not ported**
 * (`PagerState.kt:72`, `:120-132`) — `ScrollableState`, `MutatePriority` and `ScrollScope` are
 * compose-foundation types — but [isScrollInProgress] and [dispatchRawDelta] keep their upstream
 * shapes, and [scroll] has no equivalent: with no `MutatePriority` there is nothing to pass.
 * [interactionSource] (`PagerState.kt:165-171`) is not ported either; Hibari has no
 * `InteractionSource`.
 *
 * @param currentPage the index of the page shown first.
 * @param currentPageOffsetFraction its offset from the snapped position, as a page fraction;
 *   upstream constrains it to -0.5..0.5 (`PagerState.kt:69-71`).
 * @param pageCount the number of pages in this pager.
 */
class PagerState(
    currentPage: Int = 0,
    currentPageOffsetFraction: Float = 0f,
    pageCount: () -> Int,
) {
    /**
     * `PagerStateImpl.pageCountState` (`PagerState.kt:200-202`). A plain var, not a state: writing
     * it per tune would subscribe the host subtree to itself, and the value is only ever read
     * during a measure pass or a gesture.
     */
    internal var pageCountProvider: () -> Int = pageCount

    /** The total number of pages present in this pager. */
    val pageCount: Int get() = pageCountProvider.invoke()

    /**
     * Raw scroll position: the settled page plus the signed pixel distance travelled from its
     * snapped position, which is the pair compose-foundation keeps (`scrollPosition`,
     * `scrollOffset`) and the only thing this class writes per frame.
     */
    private val scrollState = mutableStateOf(
        PagerScroll(
            position = pagerClampPage(currentPage, pageCount()),
            offsetPx = 0f,
            pageSizePx = 0,
            targetPage = currentPage,
            isScrollInProgress = false,
        )
    )

    /**
     * The page the pager is on, as its own snapshot.
     *
     * Split out of [scrollValues] deliberately: [scrollValues] is written on every frame of a drag,
     * and a state read inside a `@Tunable` body subscribes that subtree to every write. Upstream's
     * `currentPage` is a page-granular snapshot for the same reason, and its per-page
     * `hierarchicalFocusGroup(state.currentPage == page)` (`Pager.kt:187-193`) has to stay
     * page-granular to be worth having.
     */
    private val currentPageState = mutableStateOf(scrollState.value.currentPage)

    /** The observable form of [currentPage], for a page body that gates on being current. */
    internal val liveCurrentPage: State<Int> get() = currentPageState

    /** The channel the pager view and every `bindState` subscriber read; see the class KDoc. */
    internal val scrollValues: State<PagerScroll> get() = scrollState

    /**
     * The same value without a subscription. Only safe from outside a tune — a `View` callback such as
     * `onLayout` — because a snapshot read inside a `@Tunable` body subscribes the whole host subtree,
     * which for a per-frame value means re-tuning the screen every frame.
     */
    internal fun peekScroll(): PagerScroll = scrollState.value

    /** The pager currently driving this state; `null` until a [HorizontalPager] has been placed. */
    internal var pager: WearPagerView? = null

    /**
     * The axis the pager sitting on this state scrolls along, which backs [layoutInfo]'s orientation.
     *
     * **Who writes it:** [PagerImpl], once per tune, from the same argument it builds the
     * [PagerConfig] with — the pager that owns the state is the only thing that can know the axis, and
     * it is also the only thing that places a page, so the two can never disagree. Nothing clears it,
     * which is why it is still answerable after the pager view lets go of this state in its
     * `onDetachedFromWindow`; upstream's `layoutInfo` is sticky over a detach in the same way
     * (`PagerState.kt:226-245`).
     *
     * **Why a plain var and not a state:** a read of a state inside a `@Tunable` body subscribes that
     * whole subtree, and this value only ever changes on the tune that is already re-writing the
     * subtree it would subscribe. Observable storage would therefore buy a re-tune nobody needs and
     * cost one more writer to keep in step with [PagerConfig].
     *
     * **What it is equivalent to upstream:** `remember(pagerState) { pagerState.layoutInfo.orientation }`
     * (`PagerScaffold.kt:177`) — a value sampled when the pager is placed and then held, which is what
     * this field is. It is deliberately **not** equivalent to reading [layoutInfo] in a tune body,
     * because that getter also reads the per-frame scroll snapshot and subscribes the reader to every
     * frame of a drag; see that property's own KDoc. Kept on the state rather than read through
     * [WearPagerView.pagerConfig] so it survives that view's `onDetachedFromWindow`, which drops
     * [pager] — upstream's `layoutInfo` is a value the state holds onto for the same reason
     * (`PagerState.kt:226-245`).
     */
    internal var nodeOrientation: PagerOrientation = PagerOrientation.Horizontal

    /**
     * `initialPageOffsetFraction` (`PagerState.kt:49`, `:70`), kept as a fraction: a fraction is only
     * convertible to pixels once the pager has been measured, so [WearPagerView] applies it on its
     * first measure pass.
     */
    internal val initialPageOffsetFraction: Float = currentPageOffsetFraction

    /** The current page displayed by the pager. */
    val currentPage: Int get() = currentPageState.value

    /**
     * The fractional offset from the start of the current page, in the range [-0.5, 0.5], where 0
     * indicates the start of the current page (`PagerState.kt:79-84`).
     */
    val currentPageOffsetFraction: Float get() = scrollState.value.currentPageOffsetFraction

    /**
     * The page that is currently "settled": not updated while the pages are being scrolled, but when
     * the animation or scroll settles (`PagerState.kt:90-96`).
     */
    val settledPage: Int get() = scrollState.value.position

    /**
     * The page this pager intends to settle to; equal to [currentPage] when no scroll is ongoing
     * (`PagerState.kt:98-104`).
     */
    val targetPage: Int get() = scrollState.value.targetPage

    /**
     * Information about the last layout pass — page size and orientation. [pageSize] is zero until the
     * pager has been measured; [orientation] is the axis the pager was last placed on, and stays
     * answerable after the pager view detaches.
     *
     * Like upstream (`PagerState.kt:108-116`), this getter reads the per-frame scroll snapshot, so
     * reading it from a `@Tunable` body subscribes that subtree to every frame of a drag; upstream's
     * own advice there ("avoid using it in the composition") applies here too, which is why
     * [AnimatedPage] takes the axis through its view instead.
     */
    val layoutInfo: PagerLayoutInfo
        get() {
            val scroll = scrollState.value
            return PagerLayoutInfoImpl(
                pageSize = scroll.pageSizePx,
                orientation = nodeOrientation,
            )
        }

    /** Whether a drag, fling or animated scroll is in progress. */
    val isScrollInProgress: Boolean get() = scrollState.value.isScrollInProgress

    /**
     * Scroll by [delta] pixels along the scroll axis, immediately, as `ScrollableState
     * .dispatchRawDelta` did (`PagerState.kt:123-125`), and return how much of it the pager actually
     * consumed (0 at the ends of the range, and 0 with no pager attached).
     */
    fun dispatchRawDelta(delta: Float): Float = pager?.scrollByRawDelta(delta) ?: 0f

    /**
     * Scroll (jump immediately) to a given [page] (`PagerState.kt:134-144`).
     *
     * Suspending because a jump has to be observed by a layout pass before the caller's numbers mean
     * anything; with no pager attached yet the values are still recorded, so the first layout lands on
     * the requested page instead of the state's initial one.
     */
    suspend fun scrollToPage(page: Int, pageOffsetFraction: Float = 0f) {
        val target = pagerClampPage(page, pageCount)
        val pager = this.pager
        if (pager != null) {
            // jumpToScroll cancels any in-flight settle and, through `scrollJob`, any animated scroll.
            pager.jumpToScroll(target, pageOffsetFraction)
            return
        }
        // No pager placed yet: record the numbers so the first layout lands here instead of on
        // `initialPage`. Compose throws at this point (`PagerState.kt:127-132`'s attached-state
        // check), which is not helpful across a retune boundary.
        writeScroll(
            scrollState.value.copy(
                position = target,
                offsetPx = 0f,
                targetPage = target,
                isScrollInProgress = false,
            )
        )
        pendingInitialFraction = pageOffsetFraction
    }

    /**
     * A `scrollToPage` issued before the pager exists, in page fractions, applied by the first measure
     * pass. `null` when there is nothing pending.
     */
    internal var pendingInitialFraction: Float? = null

    /**
     * Scroll animate to a given [page] (`PagerState.kt:157-163`), running [animationSpec] on this
     * state's own [currentPageOffsetFraction] scale.
     *
     * **Two deviations, both forced.**
     *  - Compose's `animateScrollToPage` "if the [page] is too far away from [currentPage] … will
     *    pre-jump to a nearer page, compose and animate the rest of the pages"
     *    (`PagerState.kt:146-150`). That pipeline lives in the unreadable compose-foundation half,
     *    so here a distant page is pre-jumped *immediately* to `page ± 1` in the direction of travel
     *    and only that last hop is animated, which is the same user-visible outcome for a pager whose
     *    pages are all one viewport wide.
     *  - Upstream runs this through `scroll(MutatePriority.Default)`, whose job is to cancel the
     *    previous scroll (`PagerState.kt:127-132` plus compose's `InternalMutatorMutex`). There is no
     *    public mutator mutex in Hibari, so [scrollJob] plays that part: any gesture or later call
     *    cancels it before writing.
     *
     * Must be called from the main thread, like every animation driver in this module: the frame
     * clock and the view writes both assume it.
     */
    suspend fun animateScrollToPage(
        page: Int,
        pageOffsetFraction: Float = 0f,
        animationSpec: AnimationSpec<Float> = spring(),
    ) {
        val target = pagerClampPage(page, pageCount)
        val pager = this.pager
        val pageSize = scrollState.value.pageSizePx
        if (pager == null || pageSize <= 0) {
            scrollToPage(target, pageOffsetFraction)
            return
        }
        val fromPx = pager.scrollPixelOffset()
        val toPx = target * pageSize + pageOffsetFraction * pageSize
        scrollJob?.cancel()
        try {
            // Compose's pre-jump, re-expressed: never animate over more than a page, because the
            // pages in between would all have to be composed to be drawn. The jump runs *before*
            // this coroutine claims [scrollJob], because a jump cancels whatever scroll was in
            // flight.
            if (kotlin.math.abs(toPx - fromPx) > pageSize * MaxAnimatedPages) {
                val stepped = pagerClampPage(
                    target - if (toPx > fromPx) MaxAnimatedPages else -MaxAnimatedPages,
                    pageCount,
                )
                pager.jumpToScroll(stepped, 0f)
            }
            // Upstream's contract (`PagerState.kt:98-104`): during an animated scroll from this
            // function, `targetPage` is the page the pager intends to settle to — set it before the
            // first frame, or it stays on the old settled page until the animation lands.
            setTargetPage(target)
            scrollJob = coroutineContext[Job]
            animate(pager.scrollPixelOffset(), toPx, 0f, animationSpec) { value, _ ->
                pager.applyScrollPixel(value)
            }
            pager.finishScroll(target, pageOffsetFraction)
        } finally {
            scrollJob = null
        }
    }

    /** The in-flight [animateScrollToPage]; see that function for why this replaces the mutex. */
    internal var scrollJob: Job? = null

    /** Write the raw position; the only per-frame writer is [WearPagerView]. */
    internal fun setScroll(position: Int, offsetPx: Float, scrolling: Boolean) {
        val current = scrollState.value
        val clamped = pagerClampPage(position, pageCount)
        if (current.position == clamped && current.offsetPx == offsetPx &&
            current.isScrollInProgress == scrolling
        ) {
            return
        }
        writeScroll(
            current.copy(
                position = clamped,
                offsetPx = offsetPx,
                isScrollInProgress = scrolling,
                targetPage = if (scrolling) current.targetPage else clamped,
            )
        )
    }

    /** Record the settled target of a fling or animated scroll while the pages are still moving. */
    internal fun setTargetPage(page: Int) {
        val current = scrollState.value
        val clamped = pagerClampPage(page, pageCount)
        if (current.targetPage == clamped) return
        writeScroll(current.copy(targetPage = clamped))
    }

    /** Called by the pager view from its measure pass (`PagerLayoutInfo.pageSize`). */
    internal fun setPageSizePx(sizePx: Int) {
        val current = scrollState.value
        if (current.pageSizePx == sizePx) return
        // The page size is a divisor of `currentPageOffsetFraction`, so this can move the derived
        // page: a rotation that lands mid-drag has to re-derive rather than keep the old number.
        writeScroll(current.copy(pageSizePx = sizePx))
    }

    /** Mark the end of a scroll so [isScrollInProgress] settles even when the offset did not move. */
    internal fun endScroll() {
        val current = scrollState.value
        if (!current.isScrollInProgress) return
        writeScroll(
            current.copy(
                isScrollInProgress = false,
                targetPage = current.position,
            )
        )
    }

    /**
     * The single write path: publish the frame, then publish the page-granular [currentPage] only if
     * the rounded page actually moved.
     */
    private fun writeScroll(next: PagerScroll) {
        scrollState.value = next
        val page = next.currentPage
        if (currentPageState.value != page) currentPageState.value = page
    }
}

/**
 * One immutable frame of a [PagerState]'s scroll position.
 *
 * [currentPage] and [currentPageOffsetFraction] are derived here because compose-foundation's
 * derivation is not in the reference tree; the rule is the one [PagerState]'s own KDoc states
 * (`PagerState.kt:62-64`, `:79-84`): the current page is the page whose snapped position the
 * viewport is nearest, so the fraction left over is by construction inside -0.5..0.5, and 0 means
 * "at the start of the current page".
 */
internal data class PagerScroll(
    val position: Int,
    val offsetPx: Float,
    val pageSizePx: Int,
    val targetPage: Int,
    val isScrollInProgress: Boolean,
) {
    /** Continuous page-space position of the viewport's leading edge. */
    val pagePosition: Float
        get() = if (pageSizePx > 0) position + offsetPx / pageSizePx else position.toFloat()

    val currentPage: Int
        get() = pagePosition.roundToInt().coerceAtLeast(0)

    val currentPageOffsetFraction: Float
        get() = if (pageSizePx > 0) pagePosition - currentPage else 0f
}

/**
 * A horizontally scrolling pager optimised for Wear, ported from `Pager.kt:106-200`.
 *
 * Upstream's parameter list is kept in order and meaning; two parameters change shape and three
 * cannot exist here:
 *  - `flingBehavior: TargetedFlingBehavior` becomes [flingSpec] — see [PagerFlingSpec].
 *  - `gestureInclusion: GestureInclusion` becomes [gestureInclusion] — see [PagerGestureInclusion].
 *  - `rotaryScrollableBehavior` (`Pager.kt:117`, `:127-135`) is **not ported**: there is no reusable
 *    rotary surface to hand it. `RotaryScrollableDefaults.snapBehavior` and `Modifier.rotaryScrollable`
 *    have no counterpart, and the one crown implementation this module does have lives inside
 *    `WearPickerView` and scrolls a [PickerState] through [PickerRotaryBehavior] — it is not something
 *    a pager can adopt, so crown input cannot drive this pager. The focus half of `:127-135` is **not**
 *    part of that gap: [requestFocusOnHierarchyActive] and [HierarchicalFocusRequester] (in place of
 *    Compose's `FocusRequester`) both exist, and [WearPagerView] applies [hierarchicalFocusGroup] per
 *    page. On [VerticalPager] upstream defaults the behaviour *on*, so a Wear device's crown does
 *    nothing here while it does something upstream — the single largest functional gap.
 *
 * Per-page behaviour from `Pager.kt:181-198` maps as follows:
 *  - `CustomTouchSlopProvider(newTouchSlop = original * 1.10f)` (`:126`, `:415`) is applied to the
 *    pager's own drag threshold in [WearPagerView], including the inner `:182-183` reset to the
 *    original slop for a page's own gestures, which the slot containers reproduce.
 *  - `Modifier.hierarchicalFocusGroup(state.currentPage == page)` (`:187-193`) **is ported**: each
 *    page is wrapped in it by [WearPagerView], gated on the page-granular [PagerState.currentPage]
 *    snapshot so a page turn costs one re-tune of the two affected pages rather than one per frame.
 *    Upstream applies it only when `rotaryScrollableBehavior == null`, which is always here; the
 *    `requestFocusOnHierarchyActive()` of `:129` is the rotary half and stays unported with it.
 *  - `LocalScreenIsActive provides (state.currentPage == page && parentScreenActive)` (`:184-186`)
 *    is **not provided**: this module declares no screen-active local, so a page's content cannot
 *    pause itself per page. Off-screen pages are not composed at all, which covers the cheap half of
 *    what it was for. The inputs are NOT the blocker, and an earlier note here claimed they were:
 *    [ScrollInfoProvider] is ported (`ScrollAway.kt:76`), `ambientMode()` is ported
 *    (`AmbientMode.kt:100`) and the very boolean upstream would write into the local is already
 *    computed for the focus group at `view/WearPagerView.kt:865` (`state.liveCurrentPage.value ==
 *    page`). Porting this means declaring the local and providing that expression per page — unwritten,
 *    not impossible.
 *  - `semantics { horizontalScrollAxisRange = … }` (`:158-169`), whose `else` branch exists to "signal
 *    system swipe to dismiss that it can take over", is **not ported** — no semantics layer here —
 *    but the *gesture* consequence it encoded is kept: while a gesture is excluded by
 *    [gestureInclusion] the pager refuses the drag, so the parent swipe-to-dismiss handler gets it.
 *
 * @param state the state to control this pager.
 * @param modifier applied to this pager's outer layout.
 * @param contentPadding padding around the whole content, applied after clipping, which is what makes
 *   it usable for a curved or inset screen (`Pager.kt:76-78`).
 * @param beyondViewportPageCount pages composed and laid out before and after the visible ones.
 *   Upstream warns that a large value defeats lazy loading (`Pager.kt:79-84`); the same applies to
 *   [WearPagerView]'s window.
 * @param flingSpec the snap parameters used after a scroll gesture, upstream's `flingBehavior`.
 * @param userScrollEnabled whether the user may page by gesture; programmatic scrolling through
 *   [PagerState.scrollToPage] keeps working either way.
 * @param gestureInclusion which gesture starts the pager refuses, so that a swipe-to-dismiss handler
 *   can take them; `PagerDefaults.gestureInclusion(state)` is upstream's default and allows gestures
 *   everywhere except the left edge of the first page. Upstream's parameter is non-null with that
 *   factory as its default value; here it is nullable with `null` meaning the same default, because a
 *   `@Tunable` call cannot sit in a default expression — those are hoisted into a non-`@Tunable`
 *   `$default` method, so the factory is resolved in the body. The name and position are upstream's.
 * @param reverseLayout reverse the direction of scrolling and layout.
 * @param key a stable unique key per page, so a page keeps its composed content — and anything
 *   remembered inside it — when the window moves. `null` means the position is the key.
 * @param content the content of each page.
 */
@Tunable
fun HorizontalPager(
    state: PagerState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    beyondViewportPageCount: Int = PagerDefaults.BeyondViewportPageCount,
    flingSpec: PagerFlingSpec = PagerDefaults.snapFlingBehavior(state = state),
    userScrollEnabled: Boolean = true,
    gestureInclusion: PagerGestureInclusion? = null,
    reverseLayout: Boolean = false,
    key: ((index: Int) -> Any)? = null,
    content: @Tunable PagerScope.(page: Int) -> Unit,
) {
    // Upstream's default (`Pager.kt:114`) reads `currentContext`, so it resolves in this body rather
    // than in the hoisted `$default`. `PagerImpl` keeps a null here to mean "no exclusion", which is
    // what [VerticalPager] passes.
    val inclusion = gestureInclusion ?: PagerDefaults.gestureInclusion(state = state)
    PagerImpl(
        state = state,
        modifier = modifier,
        orientation = PagerOrientation.Horizontal,
        contentPadding = contentPadding,
        beyondViewportPageCount = beyondViewportPageCount,
        flingSpec = flingSpec,
        userScrollEnabled = userScrollEnabled,
        gestureInclusion = inclusion,
        reverseLayout = reverseLayout,
        key = key,
        content = content,
    )
}

/**
 * A vertically scrolling pager optimised for Wear, ported from `Pager.kt:240-294`.
 *
 * Same mapping as [HorizontalPager], with the two differences upstream has: `VerticalPager` takes no
 * `gestureInclusion` at all (`:241-252` — only the horizontal pager reserves the left edge for
 * swipe-to-dismiss), and its `rotaryScrollableBehavior` defaults to
 * `RotaryScrollableDefaults.snapBehavior(state)` rather than to `null`, i.e. rotary is on by default
 * upstream and cannot be ported at all here.
 */
@Tunable
fun VerticalPager(
    state: PagerState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    beyondViewportPageCount: Int = PagerDefaults.BeyondViewportPageCount,
    flingSpec: PagerFlingSpec = PagerDefaults.snapFlingBehavior(state = state),
    userScrollEnabled: Boolean = true,
    reverseLayout: Boolean = false,
    key: ((index: Int) -> Any)? = null,
    content: @Tunable PagerScope.(page: Int) -> Unit,
) {
    PagerImpl(
        state = state,
        modifier = modifier,
        orientation = PagerOrientation.Vertical,
        contentPadding = contentPadding,
        beyondViewportPageCount = beyondViewportPageCount,
        flingSpec = flingSpec,
        userScrollEnabled = userScrollEnabled,
        gestureInclusion = null,
        reverseLayout = reverseLayout,
        key = key,
        content = content,
    )
}

@Tunable
internal fun PagerImpl(
    state: PagerState,
    modifier: Modifier,
    orientation: PagerOrientation,
    contentPadding: PaddingValues,
    beyondViewportPageCount: Int,
    flingSpec: PagerFlingSpec,
    userScrollEnabled: Boolean,
    gestureInclusion: PagerGestureInclusion?,
    reverseLayout: Boolean,
    key: ((index: Int) -> Any)?,
    content: @Tunable PagerScope.(page: Int) -> Unit,
) {
    val context = currentContext
    val direction = LocalLayoutDirection.current
    val parentTunation = currentTuner.tunation
    val isRtl = orientation == PagerOrientation.Horizontal && direction == LayoutDirection.Rtl
    // Sticky for `layoutInfo`: recorded here rather than read back through the view, which lets go of
    // the state when it is detached. A plain field, so this write subscribes nothing.
    state.nodeOrientation = orientation
    // `contentPadding` upstream is applied by the pager itself, after the clip, so it is a viewport
    // inset here rather than a `Modifier.padding` on the node: the page size shrinks with it and the
    // snap positions move with it.
    val config = PagerConfig(
        orientation = orientation,
        pageCount = state.pageCount.coerceAtLeast(0),
        beyondViewportPageCount = beyondViewportPageCount.coerceAtLeast(0),
        startPaddingPx = contentPadding.calculateStartPadding(direction).toPx(context),
        endPaddingPx = contentPadding.calculateEndPadding(direction).toPx(context),
        topPaddingPx = contentPadding.calculateTopPadding().toPx(context),
        bottomPaddingPx = contentPadding.calculateBottomPadding().toPx(context),
        isRtl = isRtl,
        reverseLayout = reverseLayout,
        userScrollEnabled = userScrollEnabled,
        maxFlingPages = flingSpec.maxFlingPages.coerceAtLeast(1),
        snapPositionalThreshold = flingSpec.snapPositionalThreshold,
        snapAnimationSpec = flingSpec.snapAnimationSpec,
        decayAnimationSpec = flingSpec.decayAnimationSpec,
        key = key,
    )
    Node(
        modifier = modifier
            .viewClass(WearPagerView::class.java)
            .matchParentSize()
            .thenViewAttribute<WearPagerView, PagerConfig>(uniqueKey, config) { this.pagerConfig = it }
            .thenViewAttribute<WearPagerView, PagerGestureInclusion?>(uniqueKey, gestureInclusion) {
                this.gestureInclusion = it
            }
            .bindState(uniqueKey, state.scrollValues) {
                (this as WearPagerView).onScrollValues(it)
            }
            .ref { view ->
                val pager = view as? WearPagerView ?: return@ref
                pager.bindPages(state, parentTunation, content)
            },
    )
}

/**
 * Everything [WearPagerView] needs that is decided at tune time, in one immutable value so a retune
 * that changes nothing leaves the composed pages alone. The page content lambda is deliberately *not*
 * part of it: it changes identity on nearly every tune and must not look like a structural change.
 */
data class PagerConfig(
    val orientation: PagerOrientation,
    val pageCount: Int,
    val beyondViewportPageCount: Int,
    val startPaddingPx: Int,
    val endPaddingPx: Int,
    val topPaddingPx: Int,
    val bottomPaddingPx: Int,
    val isRtl: Boolean,
    val reverseLayout: Boolean,
    val userScrollEnabled: Boolean,
    val maxFlingPages: Int,
    val snapPositionalThreshold: Float,
    val snapAnimationSpec: AnimationSpec<Float>,
    val decayAnimationSpec: FloatDecayAnimationSpec,
    val key: ((index: Int) -> Any)?,
)

/** Contains the default values used by [HorizontalPager] and [VerticalPager], ported from `Pager.kt:300-413`. */
object PagerDefaults {
    /**
     * The medium-high stiffness upstream gives the pager's snap spring (`Pager.kt:386-390`), declared
     * before [SnapAnimationSpec] because an `object` initialises its members in declaration order.
     */
    private val MediumHighStiffness: Float = 2000f

    /**
     * The default spring for the pager's snap: no bounce, medium-high stiffness
     * (`Pager.kt:392-397`).
     */
    val SnapAnimationSpec: AnimationSpec<Float> =
        spring<Float>(Spring.DampingRatioNoBouncy, MediumHighStiffness)

    /**
     * The default fling decay: [FloatExponentialDecaySpec]'s own defaults, which is compose's
     * `rememberSplineBasedDecay()` in units (see [snapFlingBehavior]'s KDoc).
     *
     * A single shared instance rather than a fresh one in each default expression, because
     * `FloatExponentialDecaySpec` compares by identity: [PagerConfig] is a data class and a
     * `thenViewAttribute` diff on it is what decides whether the pager view re-lays-out its window, so
     * a new decay object per tune would make every retune look like a structural change.
     */
    val DefaultDecayAnimationSpec: FloatDecayAnimationSpec = FloatExponentialDecaySpec()

    /**
     * The size of the left edge zone in a [HorizontalPager] where gestures are left to a
     * swipe-to-dismiss handler (`Pager.kt:399-405`).
     */
    val LeftEdgeZoneFraction: Float = 0.15f

    /**
     * The default number of pages composed before and after the visible ones (`Pager.kt:407-412`).
     */
    val BeyondViewportPageCount: Int = 0

    /**
     * The touch-slop multiplier the Wear pager drags with (`Pager.kt:126`, `CustomTouchSlopMultiplier`
     * at `:415`): 10% above the platform slop, so a page turn needs a slightly more deliberate move
     * than a tap-and-drag of a child.
     */
    const val CustomTouchSlopMultiplier: Float = 1.10f

    /**
     * Default fling behaviour for pagers on Wear: snaps at most one page at a time
     * (`Pager.kt:369-384`).
     *
     * Upstream returns a `TargetedFlingBehavior` built from
     * `PagerSnapDistance.atMost(maxFlingPages)`; [PagerFlingSpec] carries the same four inputs, and
     * [state] is unused by this factory because the snap maths read the state through the view that
     * owns it. Kept in the signature because upstream's call sites name it.
     *
     * @param state the state of the pager the behaviour is applied to.
     * @param maxFlingPages the maximum number of pages the pager may move after the scroll gesture
     *   ends (`Pager.kt:346-349`).
     * @param decayAnimationSpec the spec used to project how far a fling would carry. Upstream's
     *   default is `rememberSplineBasedDecay()` (`:373`), which is a compose-animation binary with no
     *   source in the reference tree; [DefaultDecayAnimationSpec] is hibari-animation's own decay, and
     *   the shared instance rather than a new one so the config this feeds keeps comparing equal.
     *   The two agree in units because compose's spline spec is dp-normalised on both the value and
     *   the velocity, so the density cancels and the projected distance is `velocity / 4.2` either
     *   way.
     * @param snapAnimationSpec the spec used to settle the page into position (`Pager.kt:354-359`).
     * @param snapPositionalThreshold the fraction of a page that a slow scroll must cover before the
     *   pager moves forward instead of snapping back (`Pager.kt:360-367`).
     */
    fun snapFlingBehavior(
        state: PagerState,
        maxFlingPages: Int = 1,
        decayAnimationSpec: FloatDecayAnimationSpec = DefaultDecayAnimationSpec,
        snapAnimationSpec: AnimationSpec<Float> = SnapAnimationSpec,
        snapPositionalThreshold: Float = 0.5f,
    ): PagerFlingSpec = PagerFlingSpec(
        maxFlingPages = maxFlingPages,
        decayAnimationSpec = decayAnimationSpec,
        snapAnimationSpec = snapAnimationSpec,
        snapPositionalThreshold = snapPositionalThreshold,
    )

    /**
     * The behaviour for when [HorizontalPager] should handle gestures (`Pager.kt:296-340`), with the
     * adapted probe documented on [PagerGestureInclusion].
     *
     * Upstream re-memoises this on `touchExplorationServicesEnabled` because it reads it as state
     * (`:318-321`). `touchExplorationState` is not in Hibari, so [WearPagerView] reads the live value
     * from `AccessibilityManager` at the moment a gesture starts, which answers the same question and
     * cannot go stale — so this factory needs no re-creation.
     *
     * `@Tunable` because it resolves the [Context] from the tune, as upstream's `@Composable` does
     * (`:313`). That is also why [HorizontalPager] cannot call it from a default expression: those are
     * hoisted into a non-`@Tunable` `$default` method.
     *
     * @param state the state of the pager, used to determine the current page.
     * @param edgeZoneFraction the fraction of the screen width from the left edge where gestures are
     *   ignored on the first page.
     */
    @Tunable
    fun gestureInclusion(
        state: PagerState,
        edgeZoneFraction: Float = LeftEdgeZoneFraction,
    ): PagerGestureInclusion = PagerEdgeGestureInclusion(
        state = state,
        edgeZoneFraction = edgeZoneFraction,
        context = currentContext,
    )
}

/**
 * `PagerDefaults.gestureInclusion`'s body (`Pager.kt:313-340`), page 0 / left edge only.
 *
 * [context] is captured where the factory is called because a `View` has no composition to read
 * `LocalContext` from; `AccessibilityManager` is the same source compose's
 * `DefaultTouchExplorationStateProvider` wraps, read at the moment the gesture starts.
 */
private class PagerEdgeGestureInclusion(
    private val state: PagerState,
    private val edgeZoneFraction: Float,
    private val context: Context,
) : PagerGestureInclusion {
    override fun ignoreGestureStart(
        screenX: Float,
        screenWidthPx: Float,
        pagerState: PagerState,
    ): Boolean {
        if (pagerState !== state) return false
        val accessibility =
            ContextCompat.getSystemService(context, AccessibilityManager::class.java)
        if (accessibility?.isTouchExplorationEnabled == true || state.currentPage != 0) {
            return false
        }
        // On page 0 only gestures to the right of the edge zone may be taken by the pager.
        return screenX <= screenWidthPx * edgeZoneFraction
    }
}

/** Keep a page index inside a pager that may currently have no pages at all. */
internal fun pagerClampPage(page: Int, pageCount: Int): Int =
    if (pageCount <= 0) 0 else page.coerceIn(0, pageCount - 1)

/**
 * The bounds `initialPageOffsetFraction` and a saved offset are held to
 * (`PagerState.kt:211`, `:247-248`) — the range [PagerState.currentPageOffsetFraction] documents.
 */
internal const val MinPageOffset = -0.5f
internal const val MaxPageOffset = 0.5f

/** Above this many pages, [PagerState.animateScrollToPage] pre-jumps rather than animating. */
private const val MaxAnimatedPages = 1
