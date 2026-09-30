package com.huanli233.hibari.wear.view

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import com.huanli233.hibari.animation.animate
import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.recyclerview.HibariViewHolder
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.Tunation
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.wear.MaxPageOffset
import com.huanli233.hibari.wear.MinPageOffset
import com.huanli233.hibari.wear.PagerConfig
import com.huanli233.hibari.wear.PagerDefaults
import com.huanli233.hibari.wear.PagerGestureInclusion
import com.huanli233.hibari.wear.PagerOrientation
import com.huanli233.hibari.wear.PagerScope
import com.huanli233.hibari.wear.PagerScroll
import com.huanli233.hibari.wear.PagerState
import com.huanli233.hibari.wear.WearPagerScopeImpl
import com.huanli233.hibari.wear.hierarchicalFocusGroup
import com.huanli233.hibari.wear.pagerClampPage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The `ViewGroup` behind [com.huanli233.hibari.wear.HorizontalPager] and
 * [com.huanli233.hibari.wear.VerticalPager]: the port of what
 * `androidx.wear.compose.foundation.pager.Pager` configures on top of compose-foundation's `Pager`
 * (`Pager.kt:106-294`), plus the pixel half of `PagerState` (`PagerState.kt:195-224`).
 *
 * ## Why a hand-rolled window rather than a RecyclerView
 *
 * `androidx.recyclerview.widget` *is* on this module's classpath, so `RecyclerView` +
 * `PagerSnapHelper` was the obvious candidate. It was rejected because the two things the Wear API
 * actually promises are the ones `RecyclerView` cannot be talked into: snapping at most
 * `maxFlingPages` pages, with `snapPositionalThreshold` deciding snap-back versus advance
 * (`Pager.kt:342-367`), and a continuous, exactly-known scroll offset to derive
 * `currentPageOffsetFraction` from. `PagerSnapHelper` snaps to the *nearest* page after a decay whose
 * distance it computes internally, and caps nothing. So the offset model is owned here, in the same
 * shape [WearSwipeToDismissView] owns its swipe offset.
 *
 * What that choice gives up is `RecyclerView`'s recycler *pool*: a page's view tree is created when
 * the page enters the window and disposed when it leaves, with no reuse across different page
 * indices. Compose does the same — pages outside the viewport plus `beyondViewportPageCount` are
 * disposed, not kept (`Pager.kt:79-84`) — so the difference is amortisation, not semantics.
 *
 * ## Composition window
 *
 * The live set is every page intersecting the padded viewport plus `beyondViewportPageCount` on each
 * side, so at rest with upstream's default of `0` exactly one page is composed and two during a drag
 * — upstream's own numbers (`Pager.kt:407-412`, `PageSize.Fill` at `:172`, `pageSpacing = 0.dp` at
 * `:174`). Each page keeps its own slot while it stays in the window, so a page's composed content —
 * and anything remembered inside it — survives a neighbouring page scrolling past, which is what
 * upstream's `key` promises (`Pager.kt:96-99`).
 *
 * ## The offset model
 *
 * `forwardPx` is the distance the viewport has travelled from page 0's snapped position along the
 * scroll axis: `settledPage * pageSizePx + offsetPx`, which is compose-foundation's
 * `scrollPosition`/`scrollOffset` pair and what [PagerScroll] stores. Children are laid out at their
 * *snapped* positions and moved by one translation write each, so a frame of scrolling costs no
 * layout pass.
 *
 * [PagerConfig.isRtl] and [PagerConfig.reverseLayout] collapse into one mirror flag: for a pager
 * whose pages are exactly one viewport wide with `SnapPosition.Start` (`Pager.kt:180`), laying pages
 * out right-to-left is the same geometry as laying them out from the far edge in reverse order, so
 * `mirrored = isRtl != reverseLayout` reproduces both knobs and their combination.
 *
 * ## Gestures
 *
 * The drag threshold is `ViewConfiguration.scaledTouchSlop * PagerDefaults.CustomTouchSlopMultiplier`
 * (`Pager.kt:126`, `:415`), and — the part upstream's inner
 * `CustomTouchSlopProvider(newTouchSlop = originalTouchSlop)` at `:182-183` does — a page's own
 * children keep the unmultiplied slop, being separate views with separate thresholds.
 *
 * A gesture that starts in the left-edge zone of the first page is refused outright, so the parent's
 * swipe-to-dismiss handler receives it: `Pager.kt:142-157` plus `PagerDefaults.gestureInclusion`
 * (`:313-340`) re-expressed in [onInterceptTouchEvent] instead of `pointerInput`. Upstream signals the
 * same refusal twice — it stops the drag *and* publishes `ScrollAxisRange(0f, 0f)` so the system's
 * `ScrollDismissLayout` will take over (`:158-169`); the second half has no target here (no semantics
 * layer) and is not needed, because a Views parent sees the refused gesture directly.
 *
 * `requestDisallowInterceptTouchEvent` is honoured, as every Views scrolling child expects; that is
 * this port's stand-in for a child that consumed the pointer before the pager's `pointerInput` saw it.
 *
 * ## What is not here
 *
 *  - Rotary/crown input: absent from Hibari, see [com.huanli233.hibari.wear.HorizontalPager]. The
 *    per-page `hierarchicalFocusGroup` of `Pager.kt:187-193` **is** applied, in [PagerPageTree]; the
 *    `LocalScreenIsActive` provider of `:184-186` is not, there being no screen-active local here.
 *  - The pre-fetcher ("the pages automatically composed and laid out by the pre-fetcher in the
 *    direction of the scroll", `Pager.kt:83-84`): compose-foundation internal, no source in the
 *    reference tree, nothing to port.
 *  - Overscroll fling past the first or last page: [scrollByRawDelta] clamps to the snap range.
 *    Compose's edge behaviour lives in the unreadable half, so no number is invented for it.
 */
class WearPagerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    /**
     * Per-tune configuration. Every structural input is compared as one immutable value, so a retune
     * that changes nothing leaves the composed pages alone; the page *content* is not part of it (its
     * identity changes every tune) and is re-bound instead, which is this port's equivalent of
     * recomposing a page.
     */
    var pagerConfig: PagerConfig = DefaultConfig
        set(value) {
            val previous = field
            field = value
            preservePositionByKey(previous, value)
            position = pagerClampPage(position, value.pageCount)
            if (previous.orientation != value.orientation ||
                previous.isRtl != value.isRtl ||
                previous.reverseLayout != value.reverseLayout ||
                previous.pageCount != value.pageCount ||
                previous.beyondViewportPageCount != value.beyondViewportPageCount ||
                previous.startPaddingPx != value.startPaddingPx ||
                previous.endPaddingPx != value.endPaddingPx ||
                previous.topPaddingPx != value.topPaddingPx ||
                previous.bottomPaddingPx != value.bottomPaddingPx
            ) {
                // Viewport geometry moved, so snapped positions and page sizes must be recomputed.
                requestLayout()
            }
            pushScroll(scrolling = isScrolling)
            applyTranslation()
            syncWindow()
        }

    /** The gesture exclusion of [com.huanli233.hibari.wear.HorizontalPager]; `null` allows all. */
    var gestureInclusion: PagerGestureInclusion? = null

    // region wiring pushed in from Pager.kt

    private var state: PagerState? = null
    private var parentTunation: Tunation? = null
    private var pageContent: (@Tunable PagerScope.(page: Int) -> Unit)? = null

    /**
     * Push in the three things that cannot live in [pagerConfig]: the state the view must not pretend
     * to own, the [Tunation] a page tree hangs off, and the content lambda.
     *
     * **It runs once per view, not once per tune.** The caller hands it over through `Modifier.ref`
     * (`Pager.kt:721-723`), and a `ref` is applied only when the node creates its view
     * (`Renderer.kt:162`) — the reuse path (`Patcher.applyChange:151-157`) writes attributes and never
     * re-runs a `ref`. [onAttachedToWindow] covers the case where the *view* survives but the window
     * does not, so [PagerState.pager] is never stale; what is not covered is a retune that swaps the
     * content lambda or hands over a different [PagerState]: the live pages keep composing the
     * closure this call first stored, so host data outside the pager does not reach an already
     * composed page. The fix is to ride the binding on an attribute whose value changes per tune
     * rather than on a `ref`; that is recorded as open work rather than done here, because
     * [rebindLiveSlots] plus [requestLayout] on every tune is a real cost this module has no measure
     * for yet.
     */
    fun bindPages(
        state: PagerState,
        parentTunation: Tunation,
        content: @Tunable PagerScope.(page: Int) -> Unit,
    ) {
        val firstAttach = this.state !== state
        this.state = state
        this.parentTunation = parentTunation
        this.pageContent = content
        state.pager = this
        if (firstAttach) {
            // The state may already carry a page: `rememberPagerState(initialPage = 3)` or a
            // `scrollToPage` issued before the pager was placed. The offset fraction is applied by
            // `onMeasure`, which is the first moment a page fraction means anything in pixels.
            position = state.settledPage
        }
        rebindLiveSlots()
        requestLayout()
    }

    // endregion

    // region the page window

    /** One composed page: its own slot, so its content survives while it stays in the window. */
    private class Slot(val container: FrameLayout, val holder: HibariViewHolder, var page: Int)

    private val slots = ArrayList<Slot>(4)
    private val window = ArrayList<Int>(8)

    private fun composePage(page: Int, content: @Tunable PagerScope.(page: Int) -> Unit): Slot {
        val host = parentTunation ?: throw IllegalStateException(
            "WearPagerView has no parent Tunation: bindPages() must run before a page is composed"
        )
        val pagerState = state ?: throw IllegalStateException(
            "WearPagerView has no PagerState: bindPages() must run before a page is composed"
        )
        val container = WearPagerSlotContainer(context)
        addView(container)
        val holder = HibariViewHolder(container, host)
        holder.bind { PagerPageTree(pagerState, page, content) }
        return Slot(container, holder, page)
    }

    /** Re-runs each live page's content, which is what a retune does to an already-composed page. */
    private fun rebindLiveSlots() {
        val content = pageContent ?: return
        val pagerState = state ?: return
        for (slot in slots) {
            slot.holder.bind { PagerPageTree(pagerState, slot.page, content) }
        }
    }

    /** Pages intersecting the padded viewport, plus `beyondViewportPageCount` on each side. */
    private fun syncWindow() {
        val config = pagerConfig
        val content = pageContent
        val size = pageSizePx
        if (content == null || config.pageCount <= 0 || size <= 0) {
            recycleAllSlots()
            return
        }
        val page = forwardPx() / size
        val first = (floorOf(page) - config.beyondViewportPageCount).coerceAtLeast(0)
        val last = (ceilOf(page) + config.beyondViewportPageCount)
            .coerceAtMost(config.pageCount - 1)
        window.clear()
        for (candidate in first..last) window.add(candidate)
        var changed = false
        for (i in slots.size - 1 downTo 0) {
            if (slots[i].page !in window) {
                recycleSlotAt(i)
                changed = true
            }
        }
        for (candidate in window) {
            if (slots.none { it.page == candidate }) {
                slots.add(composePage(candidate, content))
                changed = true
            }
        }
        if (changed) requestLayout()
    }

    private fun recycleSlotAt(index: Int) {
        val slot = slots.removeAt(index)
        removeView(slot.container)
        slot.holder.recycle()
    }

    private fun recycleAllSlots() {
        for (i in slots.size - 1 downTo 0) recycleSlotAt(i)
    }

    /**
     * `key`'s promise (`Pager.kt:96-99`): when pages are inserted or removed before the current one,
     * the page the wearer is looking at stays on screen. The old page is identified by its key and
     * looked up in the new index space, which is the same rule compose's lazy layout applies.
     */
    private fun preservePositionByKey(previous: PagerConfig, current: PagerConfig) {
        val key = current.key ?: return
        if (previous.pageCount == current.pageCount) return
        val oldKey = previous.key?.invoke(position) ?: return
        for (candidate in 0 until current.pageCount) {
            if (key(candidate) == oldKey) {
                if (candidate != position) position = candidate
                return
            }
        }
    }

    // endregion

    // region measure, layout, scroll maths

    private var position = 0
    private var offsetPx = 0f
    private var pageSizePx = 0
    private var initialFractionApplied = false

    private fun isVertical(): Boolean = pagerConfig.orientation == PagerOrientation.Vertical

    /** Mirror flag: see the class KDoc for why RTL and `reverseLayout` collapse into one boolean. */
    private fun isMirrored(): Boolean = pagerConfig.isRtl != pagerConfig.reverseLayout

    /** One page along the scroll axis, or 0 before the first measure pass. */
    private fun forwardUnit(): Int = pageSizePx

    /** The continuous distance travelled from page 0's snapped position, in the reading direction. */
    private fun forwardPx(): Float = position * pageSizePx + offsetPx

    private fun floorOf(value: Float): Int = floor(value).toInt()

    private fun ceilOf(value: Float): Int = ceil(value).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val config = pagerConfig
        val width = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)

        val contentWidth = (width - config.startPaddingPx - config.endPaddingPx).coerceAtLeast(0)
        val contentHeight = (height - config.topPaddingPx - config.bottomPaddingPx).coerceAtLeast(0)
        // `PageSize.Fill` with `pageSpacing = 0.dp` (`Pager.kt:172-174`): a page is the padded
        // viewport on the scroll axis, and the padded viewport across the cross axis too.
        pageSizePx = if (isVertical()) contentHeight else contentWidth
        state?.let { applyInitialFraction(it) }

        val childWidthSpec = exactMeasureSpec(if (isVertical()) contentWidth else pageSizePx)
        val childHeightSpec = exactMeasureSpec(if (isVertical()) pageSizePx else contentHeight)
        syncWindow()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(childWidthSpec, childHeightSpec)
        }
    }

    private fun exactMeasureSpec(size: Int): Int =
        MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), MeasureSpec.EXACTLY)

    /**
     * Hand the state its page size, and spend the one-shot `initialPageOffsetFraction`
     * (`PagerState.kt:49`, `:70`) or a `scrollToPage` issued before this pager existed. Upstream
     * constrains the fraction to -0.5..0.5 (`PagerState.kt:211`, `:247-248`), which is the range
     * `currentPageOffsetFraction` documents, so the same clamp is applied here.
     */
    private fun applyInitialFraction(state: PagerState) {
        state.setPageSizePx(pageSizePx)
        if (initialFractionApplied || pageSizePx <= 0) return
        initialFractionApplied = true
        val fraction = state.pendingInitialFraction ?: state.initialPageOffsetFraction
        state.pendingInitialFraction = null
        if (fraction == 0f || offsetPx != 0f) return
        offsetPx = fraction.coerceIn(MinPageOffset, MaxPageOffset) * pageSizePx
        pushScroll(scrolling = false)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val config = pagerConfig
        val contentLeft = config.startPaddingPx
        val contentTop = config.topPaddingPx
        val contentRight = (width - config.endPaddingPx).coerceAtLeast(contentLeft)
        val contentBottom = (height - config.bottomPaddingPx).coerceAtLeast(contentTop)
        val mirrored = isMirrored()
        for (slot in slots) {
            val child = slot.container
            if (child.visibility == View.GONE) continue
            val along = slot.page * pageSizePx
            if (isVertical()) {
                val y = if (mirrored) contentBottom - child.measuredHeight - along
                else contentTop + along
                child.layout(contentLeft, y, contentLeft + child.measuredWidth, y + child.measuredHeight)
            } else {
                val x = if (mirrored) contentRight - child.measuredWidth - along else contentLeft + along
                child.layout(x, contentTop, x + child.measuredWidth, contentTop + child.measuredHeight)
            }
        }
        applyTranslation()
    }

    /** The per-frame half of the scroll: one translation write per live page, no layout pass. */
    private fun applyTranslation() {
        val forward = forwardPx()
        val value = if (isMirrored()) forward else -forward
        for (slot in slots) {
            val child = slot.container
            if (isVertical()) {
                if (child.translationY != value) child.translationY = value
            } else {
                if (child.translationX != value) child.translationX = value
            }
        }
    }

    /**
     * The receiving end of the `bindState` channel from
     * [com.huanli233.hibari.wear.PagerImpl]: anything that changed the state lands here, this view's
     * own gestures or a programmatic scroll alike, so one path positions everything.
     */
    internal fun onScrollValues(values: PagerScroll) {
        if (state?.pager !== this) return
        if (values.position == position && values.offsetPx == offsetPx) {
            // The write came from this view, which has already applied it.
            return
        }
        position = values.position
        offsetPx = values.offsetPx
        applyTranslation()
        syncWindow()
    }

    // endregion

    // region programmatic scrolling (PagerState's half that needs pixels)

    /** Current travel distance, for [PagerState.animateScrollToPage]. */
    fun scrollPixelOffset(): Float = forwardPx()

    /** Move to a travel distance; called on every frame of an animated scroll. */
    fun applyScrollPixel(pixelOffset: Float) {
        setScrollPixel(pixelOffset, scrolling = true)
        applyTranslation()
        syncWindow()
    }

    /** Immediately show [page] offset by [pageOffsetFraction] of a page. */
    fun jumpToScroll(page: Int, pageOffsetFraction: Float) {
        cancelInFlightScroll()
        position = pagerClampPage(page, pagerConfig.pageCount)
        offsetPx = pageOffsetFraction.coerceIn(MinPageOffset, MaxPageOffset) * forwardUnit()
        pushScroll(scrolling = false)
        applyTranslation()
        syncWindow()
    }

    /** Settle on [page] once an animated scroll has finished. */
    fun finishScroll(page: Int, pageOffsetFraction: Float) {
        cancelInFlightScroll()
        position = pagerClampPage(page, pagerConfig.pageCount)
        offsetPx = pageOffsetFraction.coerceIn(MinPageOffset, MaxPageOffset) * forwardUnit()
        isScrolling = false
        state?.endScroll()
        pushScroll(scrolling = false)
        applyTranslation()
        syncWindow()
    }

    /**
     * The stand-in for compose's `MutatorMutex` (`PagerState.kt:127-132`): a new scroll interrupts the
     * settle animation and the caller's [PagerState.animateScrollToPage] alike.
     */
    private fun cancelInFlightScroll() {
        animationJob?.cancel()
        animationJob = null
        state?.scrollJob?.cancel()
    }

    /** `PagerState.dispatchRawDelta`: move by [delta] pixels, returning what was consumed. */
    fun scrollByRawDelta(delta: Float): Float {
        val size = pageSizePx
        if (size <= 0 || pagerConfig.pageCount <= 1) return 0f
        val maximum = (pagerConfig.pageCount - 1) * size.toFloat()
        val from = forwardPx()
        val to = (from + delta).coerceIn(0f, maximum)
        val consumed = to - from
        if (consumed == 0f) return 0f
        setScrollPixel(to, scrolling = true)
        applyTranslation()
        syncWindow()
        return consumed
    }

    private fun setScrollPixel(pixelOffset: Float, scrolling: Boolean) {
        val size = pageSizePx
        if (size <= 0) {
            offsetPx = 0f
            return
        }
        // The snapped page is the one whose start edge the viewport has passed, so the remainder is
        // always within a page of it and `currentPageOffsetFraction` stays inside -0.5..0.5.
        val snapped = floorOf(pixelOffset / size).coerceAtLeast(0)
        position = pagerClampPage(snapped, pagerConfig.pageCount)
        offsetPx = pixelOffset - snapped * size
        pushScroll(scrolling = scrolling)
    }

    private fun pushScroll(scrolling: Boolean) {
        state?.setScroll(position, offsetPx, scrolling)
    }

    // endregion

    // region gestures

    private val viewConfiguration = ViewConfiguration.get(context)

    /** `originalTouchSlop * CustomTouchSlopMultiplier` (`Pager.kt:126`, `:415`). */
    private val dragTouchSlop = viewConfiguration.scaledTouchSlop * PagerDefaults.CustomTouchSlopMultiplier

    private val minimumFlingVelocity = viewConfiguration.scaledMinimumFlingVelocity.toFloat()
    private val maximumFlingVelocity = viewConfiguration.scaledMaximumFlingVelocity.toFloat()

    private var activePointerId = InvalidPointerId
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false
    private var isScrolling = false
    private var gestureRefused = false
    private var velocityTracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!pagerConfig.userScrollEnabled || pageSizePx <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginTracking(event)
                gestureRefused = refuseGesture(downX, downY)
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (gestureRefused || pagerConfig.pageCount <= 1) return false
                velocityTracker?.addMovement(event)
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return false
                val x = event.getX(index)
                val y = event.getY(index)
                if (!shouldStartDrag(x, y)) return false
                beginDrag(x, y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endGesture()
                return false
            }

            else -> velocityTracker?.addMovement(event)
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!pagerConfig.userScrollEnabled || pageSizePx <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Reached when no child wanted the down, which is upstream's plain-draggable pager.
                if (!isDragging) {
                    beginTracking(event)
                    gestureRefused = refuseGesture(downX, downY)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (gestureRefused) return true
                velocityTracker?.addMovement(event)
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)
                if (!isDragging) {
                    if (!shouldStartDrag(x, y)) return true
                    beginDrag(x, y)
                }
                dragTo(x, y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = isDragging
                val velocity = if (wasDragging) trackedVelocity(event) else 0f
                endGesture()
                if (wasDragging) snapToTarget(velocity)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                velocityTracker?.addMovement(event)
                // The drag must not be re-targeted when the finger that started it is still down.
                if (event.findPointerIndex(activePointerId) < 0 && event.pointerCount > 1) {
                    activePointerId = event.getPointerId(0)
                    lastX = event.getX(0)
                    lastY = event.getY(0)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * `Pager.kt:142-157`: `allowPaging` starts false, is set by the first pointer down through
     * `gestureInclusion.ignoreGestureStart`, and with it `userScrollEnabled && allowPaging` decides
     * whether the pager takes the drag at all.
     */
    private fun refuseGesture(x: Float, y: Float): Boolean {
        if (pagerConfig.pageCount <= 1) return true
        val inclusion = gestureInclusion ?: return false
        val pagerState = state ?: return true
        val location = IntArray(2)
        getLocationOnScreen(location)
        return inclusion.ignoreGestureStart(
            screenX = location[0] + x,
            screenWidthPx = rootView.width.toFloat(),
            state = pagerState,
        )
    }

    private fun shouldStartDrag(x: Float, y: Float): Boolean {
        val along = if (isVertical()) abs(y - downY) else abs(x - downX)
        val across = if (isVertical()) abs(x - downX) else abs(y - downY)
        if (along <= across) return false
        // The waiver upstream's `startDragImmediately` gives a gesture that lands mid-animation
        // (`SwipeableV2.kt:145`, which the Wear pager gets by being a draggable).
        return along > dragTouchSlop || isScrolling
    }

    /**
     * `beginDrag` takes the current position because upstream's `detectDragGestures` reports only the
     * moves *after* the slop was crossed: the slop distance itself is never applied to the offset.
     */
    private fun beginDrag(x: Float, y: Float) {
        isDragging = true
        isScrolling = true
        lastX = x
        lastY = y
        cancelInFlightScroll()
        pushScroll(scrolling = true)
    }

    private fun dragTo(x: Float, y: Float) {
        val delta = when {
            isVertical() -> if (isMirrored()) y - lastY else lastY - y
            else -> if (isMirrored()) x - lastX else lastX - x
        }
        lastX = x
        lastY = y
        if (delta == 0f) return
        scrollByRawDelta(delta)
    }

    private fun beginTracking(event: MotionEvent) {
        activePointerId = event.getPointerId(0)
        downX = event.getX(0)
        downY = event.getY(0)
        lastX = downX
        lastY = downY
        gestureRefused = false
        releaseTracking()
        velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
    }

    private fun endGesture() {
        isDragging = false
        activePointerId = InvalidPointerId
        releaseTracking()
    }

    private fun releaseTracking() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /** px per second along the scroll axis, positive being forward. */
    private fun trackedVelocity(event: MotionEvent): Float {
        val tracker = velocityTracker ?: return 0f
        tracker.addMovement(event)
        tracker.computeCurrentVelocity(MillisPerSecond, maximumFlingVelocity)
        return when {
            isVertical() -> if (isMirrored()) tracker.yVelocity else -tracker.yVelocity
            else -> if (isMirrored()) tracker.xVelocity else -tracker.xVelocity
        }
    }

    // endregion

    // region fling and settle

    private val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var animationJob: Job? = null

    /**
     * The snap of `PagerDefaults.snapFlingBehavior` (`Pager.kt:342-384`), implemented per the contract
     * its own KDoc states (`Pager.kt:360-367`):
     *  - a fling at or above the platform minimum velocity *always* moves at least one page in its
     *    direction, and further only as far as the decay projection reaches, capped by `maxFlingPages`
     *    (`:346-349`);
     *  - a slow scroll with no fling moves only past `snapPositionalThreshold` of a page;
     *  - whatever the target, it settles with `snapAnimationSpec`, upstream's no-bounce spring of
     *    stiffness 2000 (`:386-397`) — which is why a Wear page turn has no overshoot.
     *
     * Compose's `SnapFlingBehavior` runs the decay first and snaps with the same spec afterwards; with
     * `maxFlingPages = 1` there is never room for that approach phase, so the single spring written
     * here reproduces upstream's behaviour at the defaults and one spring for larger caps.
     */
    private fun snapToTarget(velocityPxPerSecond: Float) {
        val config = pagerConfig
        val size = pageSizePx
        if (size <= 0 || config.pageCount <= 0) {
            isScrolling = false
            state?.endScroll()
            return
        }
        val lastPage = (config.pageCount - 1).toFloat()
        val from = forwardPx()
        val settled = position.toFloat()
        val targetPage = if (abs(velocityPxPerSecond) >= minimumFlingVelocity) {
            val projected = config.decayAnimationSpec.getTargetValue(0f, velocityPxPerSecond)
            var target = ((from + projected) / size).roundToInt().toFloat()
            // "any fling that has high enough velocity will *always* move to the next page in the
            // direction of the fling" (`Pager.kt:366-367`).
            if (target == settled) target = settled + if (velocityPxPerSecond > 0) 1f else -1f
            target.coerceIn(settled - config.maxFlingPages, settled + config.maxFlingPages)
        } else {
            val fraction = offsetPx / size
            if (abs(fraction) >= config.snapPositionalThreshold) {
                settled + if (fraction > 0) 1f else -1f
            } else {
                settled
            }
        }.coerceIn(0f, lastPage)

        state?.setTargetPage(targetPage.toInt())
        animateToPixel(targetPage * size, config)
    }

    private fun animateToPixel(toPx: Float, config: PagerConfig) {
        val fromPx = forwardPx()
        if (fromPx == toPx) {
            finishSettledScroll()
            return
        }
        animationJob?.cancel()
        isScrolling = true
        pushScroll(scrolling = true)
        val spec = config.snapAnimationSpec
        animationJob = animationScope.launch {
            try {
                animate(fromPx, toPx, 0f, spec) { value, _ ->
                    setScrollPixel(value, scrolling = true)
                    applyTranslation()
                    syncWindow()
                }
            } finally {
                animationJob = null
                finishSettledScroll()
            }
        }
    }

    /** Land exactly on the snapped page and report the scroll as finished. */
    private fun finishSettledScroll() {
        val size = pageSizePx
        if (size > 0) {
            position = pagerClampPage(
                ((position * size + offsetPx) / size).roundToInt(),
                pagerConfig.pageCount,
            )
            offsetPx = 0f
        }
        isScrolling = false
        state?.endScroll()
        pushScroll(scrolling = false)
        applyTranslation()
        syncWindow()
    }

    /**
     * Re-claim the state after a detach. [bindPages] runs from the node's `ref`, and a `ref` runs only
     * when the view is *created* (`Renderer.kt:162`; the reuse path in `Patcher.applyChange`
     * (`:151-157`) applies attributes and never re-runs a `ref`), so nothing else would put
     * [PagerState.pager] back after [onDetachedFromWindow] drops it. Without this, a pager that leaves
     * and re-enters the window — a page swiped away and back, a `Dialog` dismissed — answers
     * `scrollToPage` and crown-less `dispatchRawDelta` to nobody.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        state?.pager = this
    }

    override fun onDetachedFromWindow() {
        animationJob?.cancel()
        animationJob = null
        endGesture()
        isScrolling = false
        // A detached pager must not keep receiving programmatic scrolls; [onAttachedToWindow] re-claims
        // it on the way back in.
        state?.let { if (it.pager === this) it.pager = null }
        super.onDetachedFromWindow()
    }

    // endregion

    /**
     * `FrameLayout.LayoutParams` so a `BoxScope.gravity` written on a page still lands on params that
     * have a `gravity` field — the same reason `WearSwipeToDismissView` declares its own; the pager
     * ignores the value, because `PageSize.Fill` (`Pager.kt:172`) makes every page the whole padded
     * viewport.
     */
    class LayoutParams : FrameLayout.LayoutParams {

        constructor(width: Int, height: Int) : super(width, height)

        constructor(source: ViewGroup.LayoutParams) : super(source)

        constructor(source: MarginLayoutParams) : super(source)

        constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams): LayoutParams =
        if (p is LayoutParams) LayoutParams(p) else LayoutParams(p.width, p.height)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams): Boolean = p is LayoutParams

    private companion object {
        const val InvalidPointerId = -1

        /** `VelocityTracker.computeCurrentVelocity(units, maxVelocity)` wants milliseconds. */
        const val MillisPerSecond = 1000

        val DefaultConfig = PagerConfig(
            orientation = PagerOrientation.Horizontal,
            pageCount = 0,
            beyondViewportPageCount = 0,
            startPaddingPx = 0,
            endPaddingPx = 0,
            topPaddingPx = 0,
            bottomPaddingPx = 0,
            isRtl = false,
            reverseLayout = false,
            userScrollEnabled = true,
            maxFlingPages = 1,
            snapPositionalThreshold = 0.5f,
            snapAnimationSpec = PagerDefaults.SnapAnimationSpec,
            // The same instance every default `PagerFlingSpec` carries, so an untuned pager's config
            // compares equal to a tuned one on that component: `PagerConfig` is a data class and
            // `FloatExponentialDecaySpec` has no `equals`, so a fresh object here per tune would make
            // every retune look like a structural change to the attribute diff.
            decayAnimationSpec = PagerDefaults.DefaultDecayAnimationSpec,
            key = null,
        )
    }
}

/**
 * One page, wrapped the way `Pager.kt:187-193` wraps it: a `Box` carrying
 * `hierarchicalFocusGroup(state.currentPage == page)`, so among the live pages only the current one is
 * on the focus path.
 *
 * The gate reads [PagerState.liveCurrentPage] — the page-granular snapshot — rather than
 * [PagerState.currentPageOffsetFraction], because this body is a page's own `Tunation`: a read here
 * re-tunes *this page*, and reading the per-frame value would do that on every frame of a drag for a
 * number that cannot change during one.
 */
@Tunable
private fun PagerPageTree(
    state: PagerState,
    page: Int,
    content: @Tunable PagerScope.(page: Int) -> Unit,
) {
    Box(modifier = Modifier.hierarchicalFocusGroup(state.liveCurrentPage.value == page)) {
        content(WearPagerScopeImpl, page)
    }
}

/**
 * The slot a page's composed tree lives in.
 *
 * Forces its child to the full slot box and centres it, so a page whose root node does not fill is
 * arranged the way `verticalAlignment = CenterVertically` / `horizontalAlignment =
 * CenterHorizontally` (`Pager.kt:175`, `:272`) arranges it upstream, instead of dropping into the
 * top-left corner a plain `FrameLayout` gives.
 */
internal class WearPagerSlotContainer(context: Context) : FrameLayout(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val childWidth = MeasureSpec.makeMeasureSpec(
            (width - paddingLeft - paddingRight).coerceAtLeast(0),
            MeasureSpec.EXACTLY,
        )
        val childHeight = MeasureSpec.makeMeasureSpec(
            (height - paddingTop - paddingBottom).coerceAtLeast(0),
            MeasureSpec.EXACTLY,
        )
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(childWidth, childHeight)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            // The child is forced to the content box by `onMeasure`, so both slack terms are zero;
            // they are kept for the case where a child overrides its own measured size.
            val slackX = (width - paddingLeft - paddingRight - child.measuredWidth) / 2
            val slackY = (height - paddingTop - paddingBottom - child.measuredHeight) / 2
            val x = paddingLeft + slackX.coerceAtLeast(0)
            val y = paddingTop + slackY.coerceAtLeast(0)
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }
}
