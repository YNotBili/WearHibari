package com.huanli233.hibari.wear.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import com.huanli233.hibari.animation.AnimationState
import com.huanli233.hibari.animation.AnimationVector1D
import com.huanli233.hibari.animation.CubicBezierEasing
import com.huanli233.hibari.animation.Easing
import com.huanli233.hibari.animation.FastOutSlowInEasing
import com.huanli233.hibari.animation.SpringSpec
import com.huanli233.hibari.animation.animateDecay
import com.huanli233.hibari.animation.animateTo
import com.huanli233.hibari.animation.calculateTargetValue
import com.huanli233.hibari.animation.exponentialDecay
import com.huanli233.hibari.animation.spring
import com.huanli233.hibari.animation.tween
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.foundation.BoxScopeInstance
import com.huanli233.hibari.recyclerview.HibariViewHolder
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.Tunation
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.unit.toPx
import com.huanli233.hibari.wear.PickerRotaryBehavior
import com.huanli233.hibari.wear.PickerScope
import com.huanli233.hibari.wear.PickerState
import com.huanli233.hibari.wear.lazy.ListTransformParams
import com.huanli233.hibari.wear.wearReduceMotionEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What [WearPickerView] draws and scrolls, minus the content lambdas, as one comparable value so a
 * retune that changes nothing leaves the scroller — and the rows it has materialised — alone.
 *
 * [gradientColor] keeps upstream's three-way meaning: any real colour paints the top and bottom fade
 * rects, [Color.Unspecified] selects the `BlendMode.Modulate` mask path instead.
 */
internal data class PickerProps(
    val state: PickerState,
    val verticalSpacingPx: Int,
    val gradientRatio: Float,
    val gradientColor: Color,
    val transform: ListTransformParams,
    val readOnly: Boolean,
    val userScrollEnabled: Boolean,
    val rotary: PickerRotaryBehavior?,
    val valueDescription: String?,
)

/** The slot lambdas, in a second attribute so a colour or number change never rebinds rows. */
internal data class PickerContentProps(
    val parentTunation: Tunation,
    val scope: PickerScope,
    val option: @Tunable PickerScope.(index: Int) -> Unit,
    val readOnlyLabel: (@Tunable BoxScope.() -> Unit)?,
    val onSelected: () -> Unit,
)

/** [WearPickerGroupView]'s knobs; the two spring numbers are upstream's `fastSpatialSpec()`. */
internal data class PickerGroupProps(
    val autoCenter: Boolean,
    val propagateMinConstraints: Boolean,
    val singlePointerInput: Boolean,
    val centeringDampingRatio: Float,
    val centeringStiffness: Float,
    /** `PickerGroup.selectedPickerState`, whose picker answers the row's scroll actions. */
    val selectedState: PickerState?,
)

/**
 * The column behind `Picker`: it materialises option content for the rows on screen, applies Wear's
 * per-item scale/fade, snaps, flings and answers rotary input.
 *
 * Why this is not a `RecyclerView`. `Picker` is a uniform-pitch list pinned to a focal line, and its
 * behaviour needs more than `WearableLinearLayoutManager` can give: javap on the `androidx.wear:wear`
 * 1.4.0 class exposes only `setLayoutCallback`/`getLayoutCallback`/`scrollVerticallyBy`/
 * `onLayoutChildren`, so
 *  - there is no `FlingBehavior` seam for upstream's `ScalingLazyColumnSnapFlingBehavior`, and a
 *    `RecyclerView` fling settles on `OverScroller`'s friction instead of upstream's decay-then-snap;
 *  - children are positioned by their untransformed decor bounds, so the picker's `edgeScale = 0.45f`
 *    would leave the full-height gaps that
 *    [com.huanli233.hibari.wear.lazy.WearListTransformLayoutCallback] documents as its own
 *    limitation — with a `0.45f` transition area that gap covers most of a watch screen;
 *  - `centerItemScrollOffset` is only recoverable from a `RecyclerView` as an unsigned distance,
 *    while both the rotary snap maths (`expectedDistanceTo`) and the read-only shim need the signed
 *    one.
 * A picker shows at most a handful of rows, so nothing is lost by owning the container, and rows are
 * still only materialised while on screen.
 *
 * Deviations from upstream: mostly forced by the Views model, a few just places this port is behind.
 *  - Upstream's `BlendMode.Modulate` is [PorterDuff.Mode.MULTIPLY] here, applied inside
 *    [Canvas.saveLayer]: the same premultiplied multiply, and the layer is the analogue of the
 *    `CompositingStrategy.Offscreen` layer upstream installs for exactly this branch.
 *  - `Role.ValuePicker` has no `AccessibilityNodeInfo` counterpart, and upstream's click hint is an
 *    accessibility *action label* — `getString(Strings.PickerClickToAdjustHint)` while read-only and
 *    `getString(Strings.PickerClickToSelectHint)` otherwise, i.e.
 *    `R.string.wear_m3c_picker_click_to_adjust_hint` / `..._select_hint` (`internal/Strings.kt`,
 *    referenced from `Picker.kt`) — which a framework `AccessibilityNodeInfo` cannot carry. The
 *    description and the scroll actions are ported; the label degrades to [View.isClickable]'s
 *    generic click action. The hint *text* is not in the reference tree, so nothing here asserts it.
 *  - Upstream's `semantics { scrollToIndex { state.scrollToOption(it); onSelected() } }` is **not**
 *    ported, and cannot be: it reaches TalkBack as `ACTION_SCROLL_TO_POSITION` with an item-index
 *    argument, which exist only on `AccessibilityNodeInfoCompat`, and a framework `View` exposes
 *    actions through the integer-only `performAccessibilityAction(Int, Bundle)`. So jumping straight
 *    to a named option by swipe-a-then-tap is missing here; scrolling one option at a time is not.
 *  - `Modifier.scrollableForTouchExploration` (TalkBack's swipe driving the snap fling) becomes
 *    [performAccessibilityAction] with `ACTION_SCROLL_FORWARD`/`ACTION_SCROLL_BACKWARD`.
 *  - Rotary events go through a conflated channel collected with `collectLatest`, as upstream's
 *    `RotaryInputNode` does (`foundation/rotary/RotaryScrollable.kt:1757`, `:1768-1790`, `:1798`), so a
 *    new event drops any queued one and cancels the handler still running for the previous one at its
 *    next suspension point. What it does not cancel is the running snap: upstream's `performScroll` is a
 *    `suspend CoroutineScope.` extension (`:1431`) that starts `snapJob` with `with(this) { async{…} }`
 *    (`:1517`), so the snap belongs to the pump's `launch` scope, and [rotaryJob] and
 *    [rotaryScrollJob] living on [animatorScope] is that same shape rather than a shortcut.
 *  - Rotary haptics use [HapticFeedbackConstants.CLOCK_TICK]: upstream's per-device constant table
 *    (`Haptics.kt`'s `HapticConstants`, with Galaxy/Wear-3.5/Wear-4 magic numbers, plus API 35's
 *    `ScrollFeedbackProvider`) is not portable. Its 30 ms throttle is, and [performSnapHaptic] applies
 *    it — as first-event-only rather than upstream's first-and-last, for the reason given there.
 *  - `rotaryScrollable`'s overscroll and nested-scroll plumbing is dropped: nothing in the ported
 *    surface nests a picker inside another scrolling parent, so upstream's trailing `fling(0f)` into
 *    the overscroll effect has no destination.
 *  - Nor is `ScrollableState.scroll(MutatePriority)`'s mutator mutex ported. Upstream routes the touch
 *    fling, the rotary scroll and the snap through that one entry point, so starting any of them
 *    cancels whichever else is in flight. Here each has its own [Job] and none cancels another, so a
 *    crown turn begun during a touch fling adds to it rather than replacing it.
 *  - The average item size reported to the rotary provider is the first bound row's height, which is
 *    upstream's own stated assumption ("all items in picker have the same height").
 *  - `LocalReduceMotion` is sampled through the module's one source, `wearReduceMotionEnabled`, when
 *    the attributes land and on attach rather than observed for changes, since the ported surface has
 *    no tunation local for it. Both of upstream's uses are covered: the shim alpha snaps instead of
 *    animating, and [resolvedTransform] blanks the per-item scale and fade the way
 *    `ReduceMotionScalingParams` does.
 *  - Focus: upstream routes it through `hierarchicalFocusGroup` / `requestFocusOnHierarchyActive`,
 *    which have no Views counterpart. Here the picker that [autoCenteringTarget] marks — which is
 *    exactly the picker a `PickerGroup` selects — takes focus when it becomes the target, because
 *    rotary events only reach a `View` that holds focus.
 *  - Content rows are (re)bound only when their option index changes or the slot lambdas are
 *    replaced, not on every measure: `HibariViewHolder.bind` re-tunes its whole subtree.
 */
class WearPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    private val viewConfiguration = ViewConfiguration.get(context)
    private val accessibilityManager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    private var animatorScope = pickerAnimatorScope()

    private val fillPaint = Paint()
    private val maskPaint = Paint().also { it.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) }
    private val layerBounds = RectF()

    private val rows = ArrayList<PickerRowView>()
    private val rowPool = ArrayList<PickerRowView>()
    private var labelHost: FrameLayout? = null
    private var labelHolder: HibariViewHolder? = null

    private var props: PickerProps? = null
    private var contentProps: PickerContentProps? = null

    /**
     * [PickerProps.transform] with `LocalReduceMotion` folded in, which is what upstream's
     * `ScalingLazyColumn` does at composition time: `if (reduceMotion) ReduceMotionScalingParams(
     * scalingParams) else scalingParams`, and `ReduceMotionScalingParams` pins `edgeScale` and
     * `edgeAlpha` to `1.0f`. Resolved when the attributes land and on attach rather than per row,
     * because reading the setting is a `ContentResolver` query and the ported surface has no local to
     * observe it for changes.
     */
    private var resolvedTransform: ListTransformParams? = null

    /**
     * Bumped whenever the slot lambdas are replaced, because a retune hands back equal-but-new
     * closures: the rows have to be re-tuned to pick up what they now capture, but not before.
     */
    private var contentGeneration = 0

    /** The row window the last [updateRows] pass filled; scrolling only refills when it moves. */
    private var boundWindow: Pair<Int, Int>? = null

    /**
     * Signed distance from the viewport focal line to the centre of `state.centerItemIndex`, positive
     * meaning below the line — upstream's `centerItemScrollOffset`, which Compose reports as an `Int`
     * while its internal scroll position is a float. The float is kept and nothing is rounded.
     */
    private var centerOffsetPx = 0f

    private var lastMotionY = 0f
    private var dragSlop = 0f
    private var dragging = false
    private var activePointerId = INVALID_POINTER
    private var velocityTracker: VelocityTracker? = null
    private var scrollJob: Job? = null

    /** `animatedShimColorAlpha`: 1f while the picker shows only its selected value. */
    private var shimAlpha = 0f
    private var shimJob: Job? = null
    private var wasEditable = false

    /**
     * `PickerGroupItem`'s `pointerInput { awaitFirstDown(requireUnconsumed = true); onSelected() }`,
     * which upstream installs only while this item is neither selected nor explored by touch. Set by
     * [com.huanli233.hibari.wear.pickerSelectOnDown].
     */
    var pickerSelectOnDown = false

    /** Set by `PickerGroupItem` through [com.huanli233.hibari.wear.pickerAutoCenteringTarget]. */
    var autoCenteringTarget = false
        set(value) {
            if (field == value) return
            field = value
            (parent as? WearPickerGroupView)?.requestLayout()
            if (value) requestFocusIfNeeded()
        }

    // --- rotary gesture state (`HighRes`/`LowResSnapRotaryScrollableBehavior`) ------------------
    private var rotaryIsLowRes = false
    private var previousRotaryEventTime = -1L
    private var rotaryAccumulatedDelta = 0f
    private var rotaryScrollDistance = 0f
    private var rotarySnapTarget = 0
    private var rotaryTargetUpdated = false
    private var rotarySequentialSnap = false
    private var rotarySequentialScroll = false
    private var rotaryThreshold = PickerThresholdHandler(1f, 1.5f) { averageItemSizePx() }
    private var rotaryJob: Job? = null
    private var rotaryScrollAnim = AnimationState(0f)
    private var rotaryScrollPrevious = 0f
    private var rotaryScrollJob: Job? = null
    private var rotarySnapAnim = AnimationState(0f)

    /** A queued `RotaryScrollEvent`, as far as this port needs one: time, delta, behaviour. */
    private data class RotaryEvent(
        val timestampMillis: Long,
        val delta: Float,
        val behavior: PickerRotaryBehavior,
    )

    /**
     * Upstream's `RotaryInputNode.channel` (`foundation/rotary/RotaryScrollable.kt:1757`). Rebuilt on
     * every attach, for the reason recorded in [onAttachedToWindow].
     */
    private var rotaryEvents: Channel<RotaryEvent>? = null

    /** `Haptics.kt:87`'s `throttleThresholdMs`, applied to the tick this port emits. */
    private var lastSnapHapticTime = 0L

    init {
        dragSlop = viewConfiguration.scaledTouchSlop.toFloat()
        // Rotary events reach a View only while it holds focus; upstream gets that focus through
        // `Modifier.rotaryScrollable(focusRequester)` plus the hierarchical focus coordinator.
        isFocusable = true
        isFocusableInTouchMode = true
    }

    internal var pickerProps: PickerProps?
        get() = props
        set(value) {
            applyProps(value)
        }

    internal var pickerContent: PickerContentProps?
        get() = contentProps
        set(value) {
            if (contentProps === value) return
            contentProps = value
            contentGeneration++
            if (value != null) {
                rows.forEach { bindRow(it) }
                applyReadOnlyLabel()
            }
        }

    private fun applyProps(next: PickerProps?) {
        val previous = props
        props = next
        if (next == null) return
        val first = previous == null
        resolvedTransform = next.transform.copy(reduceMotion = wearReduceMotionEnabled(context))
        rotaryIsLowRes = next.rotary != null && isLowResInput()
        if (!first && previous!!.rotary != next.rotary) resetRotaryGesture()
        contentDescription = next.valueDescription
        // `semantics { onClick(hint) { onSelected() } }`: TalkBack offers a click action, which
        // arrives through performClick.
        isClickable = true
        // Upstream's `state.scalingLazyListState` is the state the column reads and writes; here the
        // view registers itself so `scrollToOption`/`animateScrollToOption`/`canScroll*` have a
        // target even before the first measure.
        if (previous?.state !== next.state) {
            previous?.state?.let { if (it.picker === this) it.picker = null }
            next.state.picker = this
        }

        if (first) {
            centerOffsetPx = next.state.centerItemScrollOffset
            shimAlpha = if (next.readOnly) 1f else 0f
            wasEditable = !next.readOnly
            boundWindow = null
            requestLayout()
        } else if (previous.readOnly != next.readOnly) {
            animateShimTo(if (next.readOnly) 1f else 0f, snap = wearReduceMotionEnabled(context))
            // Upstream's `forceScrollWhenReadOnly`: a picker that has ever been editable re-centres
            // instantly when it turns read-only, so the shim is covering the right row.
            if (next.readOnly) {
                if (wasEditable) {
                    scrollToOption(next.state.selectedOptionIndex)
                    wasEditable = false
                }
            } else {
                wasEditable = true
            }
            applyReadOnlyLabel()
        }
        invalidate()
    }

    /** Rotary events reach a `View` only while it holds focus; `PickerGroup` selects by focus. */
    private fun requestFocusIfNeeded() {
        if (autoCenteringTarget && isAttachedToWindow) requestFocus()
    }

    /** `isLowResInput()` in RotaryScrollable.kt: a bezel rather than a crown/rsb. */
    private fun isLowResInput(): Boolean =
        context.packageManager.hasSystemFeature(FEATURE_LOW_RES_ROTARY)

    /**
     * `LocalTouchExplorationStateProvider`: upstream's listener yields
     * `isEnabled && isTouchExplorationEnabled`, read here at the moment the gesture happens.
     */
    private val touchExplorationEnabled: Boolean
        get() = accessibilityManager?.isEnabled == true &&
            accessibilityManager?.isTouchExplorationEnabled == true

    // region geometry ---------------------------------------------------------------------------

    private val drawTopPx: Float get() = paddingTop.toFloat()
    private val drawHeightPx: Float get() = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(0f)

    /** The 1.dp inset upstream adds so its rectangles do not jitter (b/223386180). */
    private val viewportInsetPx: Float get() = DrawInsetDp.toPx(this).toFloat()

    private val viewportTopPx: Float get() = drawTopPx + viewportInsetPx
    private val viewportHeightPx: Float get() = max(0f, drawHeightPx - 2f * viewportInsetPx)

    /** `viewportCenterLinePx`: half the viewport rounded down, so odd heights bias upward. */
    private val focalLinePx: Float get() = viewportTopPx + floor(viewportHeightPx / 2f)

    private val contentWidthPx: Int get() = max(0, width - paddingLeft - paddingRight)

    private val state: PickerState? get() = props?.state

    private fun centerIndex(): Int = state?.centerItemIndex ?: 0

    /** Upstream's `visibleItems.fastFirstOrNull { it.index == centerItemIndex } ?: visibleItems[size/2]`. */
    private fun anchorRow(): PickerRowView? =
        rows.firstOrNull { it.itemIndex == centerIndex() } ?: rows.getOrNull(rows.size / 2)

    /** Uniform slot height plus the gap: upstream's `Arrangement.spacedBy(verticalSpacing)`. */
    private fun pitchPx(): Float {
        val row = anchorRow() ?: return 0f
        return (row.measuredHeight + (props?.verticalSpacingPx ?: 0)).toFloat()
    }

    /** `PickerRotarySnapLayoutInfoProvider.averageItemSize`: the first visible row's height. */
    private fun averageItemSizePx(): Float = rows.firstOrNull()?.measuredHeight?.toFloat() ?: 0f

    private fun itemLimit(): Int = state?.numberOfItems() ?: 0

    /** Signed distance from the focal line to the centre of [itemIndex]: upstream's `unadjustedOffset`. */
    private fun distanceToCentre(itemIndex: Int): Float =
        (itemIndex - centerIndex()) * pitchPx() + centerOffsetPx

    /** Keep `|centerItemScrollOffset| <= pitch/2` by handing whole pitches to the index. */
    private fun normalizeCenter() {
        val state = state ?: return
        val pitch = pitchPx()
        if (pitch <= 0f) return
        var index = state.centerItemIndex
        var offset = centerOffsetPx
        while (offset > pitch / 2f) {
            index -= 1
            offset -= pitch
        }
        while (offset <= -pitch / 2f) {
            index += 1
            offset += pitch
        }
        val limit = itemLimit()
        if (limit > 0) {
            val clamped = index.coerceIn(0, limit - 1)
            if (clamped != index) {
                offset += (clamped - index) * pitch
                index = clamped
            }
        }
        state.centerItemIndex = index
        state.centerItemScrollOffset = offset
        centerOffsetPx = offset
    }

    /**
     * Positive [delta] scrolls forward: content moves up and later items reach the focal line.
     * Returns the consumed part, which is how upstream detects having hit an edge.
     */
    private fun scrollByPixels(delta: Float): Float {
        val state = state ?: return 0f
        val pitch = pitchPx()
        if (pitch <= 0f || delta.isNaN()) return 0f
        val items = itemLimit()
        var consumed = delta
        if (!state.shouldRepeatOptions && items > 1) {
            // `AutoCenteringParams(itemIndex = 0)` pads both ends by half a viewport, which lets the
            // first and last items reach the focal line and nothing beyond: item 0's centre stays
            // inside [-(items-1)*pitch, 0], and scrolling by delta moves it by exactly -delta.
            val head = distanceToCentre(0)
            consumed = delta.coerceIn(head, head + (items - 1) * pitch)
        }
        if (consumed == 0f) return 0f
        centerOffsetPx -= consumed
        normalizeCenter()
        updateRows(force = false)
        placeRows()
        invalidate()
        return consumed
    }

    private fun setScrollInProgress(inProgress: Boolean) {
        state?.setScrollInProgress(inProgress)
    }

    // endregion

    // region rows ------------------------------------------------------------------------------

    private fun acquireRow(): PickerRowView {
        val row = rowPool.removeLastOrNull() ?: PickerRowView(context).also {
            it.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        if (row.parent == null) addView(row)
        rows.add(row)
        return row
    }

    /**
     * Re-tunes one row's subtree. [HibariViewHolder.bind] re-runs the content every time it is
     * called, so this is deliberately driven by [bindRowIfNeeded] rather than by every measure.
     */
    private fun bindRow(row: PickerRowView) {
        val content = contentProps ?: return
        val optionIndex = optionIndexOf(row.itemIndex)
        val holder = row.holder ?: HibariViewHolder(row, content.parentTunation).also {
            row.holder = it
        }
        row.boundOptionIndex = optionIndex
        row.boundGeneration = contentGeneration
        // Both halves of the extension-lambda call have to be stable expressions: `scope.option(i)`
        // only resolves once `option` is a local, not a property access.
        val scope = content.scope
        val option = content.option
        holder.bind { scope.option(optionIndex) }
    }

    private fun bindRowIfNeeded(row: PickerRowView) {
        val content = contentProps ?: return
        if (row.holder != null &&
            row.boundGeneration == contentGeneration &&
            row.boundOptionIndex == optionIndexOf(row.itemIndex)
        ) {
            return
        }
        bindRow(row)
    }

    private fun optionIndexOf(itemIndex: Int): Int {
        val state = state ?: return 0
        return (itemIndex + state.optionsOffset) % state.numberOfOptions
    }

    /** Detach every row outside `[first, last]`, then make sure the window itself is bound. */
    private fun fillWindow(first: Int, last: Int) {
        val iterator = rows.iterator()
        while (iterator.hasNext()) {
            val row = iterator.next()
            if (row.itemIndex in first..last) continue
            iterator.remove()
            removeView(row)
            row.holder?.recycle()
            row.holder = null
            row.itemIndex = INVALID_ITEM
            row.boundOptionIndex = INVALID_ITEM
            row.boundGeneration = INVALID_GENERATION
            if (rowPool.size < MaxPooledRows) rowPool.add(row)
        }
        for (index in first..last) {
            if (rows.none { it.itemIndex == index }) acquireRow().itemIndex = index
        }
    }

    /**
     * Fill and measure the visible row window. [force] is true from [onMeasure]; from a scroll it is
     * false, so the work only happens when the window actually moved — the row pitch is uniform, so
     * the window changes once per item, not once per frame.
     */
    private fun updateRows(force: Boolean) {
        val props = props ?: return
        val width = contentWidthPx
        val items = itemLimit()
        if (width <= 0 || viewportHeightPx <= 0f || items <= 0) return

        val window = visibleWindow(items)
        if (!force && window == boundWindow && rows.isNotEmpty()) return
        boundWindow = window

        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(
            max(1, viewportHeightPx.roundToInt()),
            MeasureSpec.AT_MOST,
        )
        fillWindow(window.first, window.second)
        rows.forEach { bindRowIfNeeded(it) }
        rows.forEach { it.measure(widthSpec, heightSpec) }
        rows.sortBy { it.itemIndex }
    }

    /** First/last item index whose untransformed box can touch the viewport. */
    private fun visibleWindow(items: Int): Pair<Int, Int> {
        val pitch = max(pitchPx(), 1f)
        val reach = ceil((viewportHeightPx / 2f + pitch) / pitch).toInt() + 1
        val centre = centerIndex()
        return (centre - reach).coerceAtLeast(0) to (centre + reach).coerceAtMost(items - 1)
    }

    private fun placeRows() {
        if (width == 0 || height == 0) return
        val anchor = anchorRow() ?: return
        val line = focalLinePx
        val spacing = (props?.verticalSpacingPx ?: 0).toFloat()
        val anchorPosition = rows.indexOf(anchor)

        // Anchor on the focal row, then stack outwards: the approach a lazy list runs from its anchor,
        // which is what `Arrangement.spacedBy(verticalSpacing)` produces for a uniform list.
        val anchorCentre = line + centerOffsetPx
        placeRow(anchor, anchorCentre, line)
        var cursor = anchorCentre + anchor.measuredHeight / 2f + spacing
        for (i in (anchorPosition + 1) until rows.size) {
            val row = rows[i]
            placeRow(row, cursor + row.measuredHeight / 2f, line)
            cursor += row.measuredHeight + spacing
        }
        cursor = anchorCentre - anchor.measuredHeight / 2f - spacing
        for (i in (anchorPosition - 1) downTo 0) {
            val row = rows[i]
            placeRow(row, cursor - row.measuredHeight / 2f, line)
            cursor -= row.measuredHeight + spacing
        }
    }

    private fun placeRow(row: PickerRowView, centre: Float, line: Float) {
        val top = (centre - row.measuredHeight / 2f).roundToInt()
        val left = (contentWidthPx - row.measuredWidth) / 2 + paddingLeft
        row.layout(left, top, left + row.measuredWidth, top + row.measuredHeight)

        val transform = resolvedTransform ?: return
        // `calculateScaleAndAlpha` reads the item's top inside the viewport, not inside the view.
        val progress = transform.progressFor(
            top = (top - viewportTopPx).toFloat(),
            height = row.measuredHeight.toFloat(),
            viewportHeight = viewportHeightPx,
        )
        if (progress == 0f) {
            row.scaleX = 1f
            row.scaleY = 1f
            row.alpha = 1f
            row.pivotY = 0f
            return
        }
        // Upstream's `scaledTop` pivot: above the midline a row shrinks toward its bottom edge, below
        // it toward its top, so the gap always opens away from the focal line.
        row.pivotX = row.width / 2f
        row.pivotY = if (centre < line) row.height.toFloat() else 0f
        val scale = transform.scaleFor(progress)
        row.scaleX = scale
        row.scaleY = scale
        row.alpha = transform.alphaFor(progress)
    }

    /**
     * Frame the label host over the content area, at top-start — where upstream's outer `Box` puts it
     * (`material3/Picker.kt:302-305`, default `Alignment.TopStart`, while the column itself is
     * `.align(Center)`). Called from [onMeasure] and [onLayout] rather than from [placeRows], which
     * returns early while there is no anchor row: the label has nothing to do with the row window, and
     * [dispatchDraw] translates by this frame.
     */
    private fun layoutLabelHost() {
        val host = labelHost ?: return
        host.layout(
            paddingLeft,
            paddingTop,
            paddingLeft + host.measuredWidth,
            paddingTop + host.measuredHeight,
        )
    }

    /** The label slot only exists while the picker is read-only, which is when upstream shows it. */
    private fun applyReadOnlyLabel() {
        val content = contentProps
        val label = content?.readOnlyLabel
        if (content == null || label == null || props?.readOnly != true) {
            if (labelHost != null) {
                labelHolder?.recycle()
                labelHolder = null
                labelHost?.let { removeView(it) }
                labelHost = null
            }
            return
        }
        val host = labelHost ?: FrameLayout(context).also {
            it.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            labelHost = it
        }
        // It only has to *be* a child, for measure/layout and for the accessibility tree: [drawChild]
        // holds it back from the normal pass and [dispatchDraw] composites it above the fade and the
        // shim, so upstream's overlay order inside the `Box` (`material3/Picker.kt:302-305`) is
        // reproduced without re-parenting. Moving it on every content change would detach and re-attach
        // the tuned subtree each retune.
        if (host.parent == null) addView(host)
        val holder = labelHolder ?: HibariViewHolder(host, content.parentTunation).also { labelHolder = it }
        holder.bind { BoxScopeInstance.label() }
    }

    // endregion

    // region measure, layout, draw --------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // A lazy list fills its cross axis, and its main axis too while the constraints are bounded;
        // only an unbounded height falls back to one row.
        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> max(suggestedMinimumWidth, anchorRow()?.measuredWidth ?: 0)
            else -> MeasureSpec.getSize(widthMeasureSpec)
        }
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        var height = when (heightMode) {
            MeasureSpec.UNSPECIFIED -> max(suggestedMinimumHeight, anchorRow()?.measuredHeight ?: 0)
            else -> MeasureSpec.getSize(heightMeasureSpec)
        }
        setMeasuredDimension(width, height)

        if (anchorRow()?.measuredHeight != 0) {
            bootstrapRow(width)
            // With no height given, one row is the answer — but only once a row has been measured.
            if (heightMode == MeasureSpec.UNSPECIFIED) {
                height = max(suggestedMinimumHeight, anchorRow()?.measuredHeight ?: 0)
                setMeasuredDimension(width, height)
            }
        }
        labelHost?.measure(
            MeasureSpec.makeMeasureSpec(max(0, width - paddingLeft - paddingRight), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(max(0, height - paddingTop - paddingBottom), MeasureSpec.EXACTLY),
        )
        layoutLabelHost()
        // The scroll position lives in the state, so adopt it, then hand whole pitches to the index:
        // both the window and the placement need the pitch, which needs an already-measured row.
        props?.state?.let { centerOffsetPx = it.centerItemScrollOffset }
        normalizeCenter()
        updateRows(force = true)
        normalizeCenter()
        placeRows()
    }

    /**
     * Bind the focal row once so its height can drive the pitch before the real measure — what a lazy
     * list does when it pre-measures its first item.
     */
    private fun bootstrapRow(width: Int) {
        val state = state ?: return
        if (state.numberOfItems() <= 0) return
        if (rows.any { it.itemIndex == state.centerItemIndex && it.measuredHeight > 0 }) return
        val row = anchorRow() ?: acquireRow().also { it.itemIndex = state.centerItemIndex }
        bindRow(row)
        val available = if (measuredHeight > 0) measuredHeight else resources.displayMetrics.heightPixels
        row.measure(
            MeasureSpec.makeMeasureSpec(max(1, width - paddingLeft - paddingRight), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(max(1, available - paddingTop - paddingBottom), MeasureSpec.AT_MOST),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        placeRows()
        layoutLabelHost()
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A detached view cancelled its scope, and a `SupervisorJob` does not come back; make a new
        // one rather than letting a re-attached picker silently stop animating.
        if (!animatorScope.isActive) animatorScope = pickerAnimatorScope()
        // The channel is rebuilt per attach rather than drained, because that is the difference between
        // this host and upstream's: their channel is a `val` on a `Modifier.Node`
        // (`RotaryScrollable.kt:1757`), so a node re-entering composition gets a fresh channel and the
        // event left in the old conflate slot dies with the node, while a recycled `View` outlives
        // attach cycles. A `tryReceive` drain would keep reusing a channel upstream never reuses.
        val events = Channel<RotaryEvent>(capacity = Channel.CONFLATED)
        rotaryEvents = events
        // `collectLatest` cancels the handler still running for the previous event at its next
        // suspension point - the `delay` in the partial-scroll branch, after which it would otherwise
        // reset the scroll state and re-snap onto a target a newer event has already moved. The snap
        // animations deliberately do NOT ride that per-emission job: upstream's `performScroll` is a
        // `suspend CoroutineScope.` extension (`:1431`) and starts its snap with
        // `with(this) { async { ... } }` (`:1517`), so `snapJob` belongs to the pump's own `launch`
        // scope. [rotaryJob] and [rotaryScrollJob] hanging off [animatorScope] is the same shape.
        animatorScope.launch {
            events.receiveAsFlow().collectLatest { event ->
                performRotaryScroll(event.timestampMillis, event.delta, event.behavior)
            }
        }
        props?.let {
            resolvedTransform = it.transform.copy(reduceMotion = wearReduceMotionEnabled(context))
        }
        props?.state?.picker = this
        requestFocusIfNeeded()
    }

    override fun onDetachedFromWindow() {
        animatorScope.cancel()
        // The pump is a child of that scope, so it is gone with it; drop the channel as well rather
        // than leaving an unconsumed event parked in the conflate slot.
        rotaryEvents = null
        scrollJob?.cancel()
        rotaryJob?.cancel()
        rotaryScrollJob?.cancel()
        shimJob?.cancel()
        velocityTracker?.recycle()
        velocityTracker = null
        state?.let { if (it.picker === this) it.picker = null }
        rows.forEach { row ->
            row.holder?.recycle()
            row.holder = null
            row.boundGeneration = INVALID_GENERATION
        }
        rows.clear()
        rowPool.clear()
        boundWindow = null
        labelHolder?.recycle()
        labelHolder = null
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val props = props
        if (props == null) {
            super.dispatchDraw(canvas)
            drawLabelLast(canvas)
            return
        }
        // `gradientColor == Color.Unspecified` is upstream's masking branch: the content is composited
        // into an offscreen layer and multiplied by the fade, so what is behind the picker shows
        // through instead of being painted over.
        val maskMode = props.gradientColor.isUnspecified
        val needsLayer = maskMode && (props.gradientRatio > 0f || shimAlpha > 0f)
        val token = if (needsLayer) {
            layerBounds.set(0f, drawTopPx, width.toFloat(), drawTopPx + drawHeightPx)
            canvas.saveLayer(layerBounds, null)
        } else {
            -1
        }

        super.dispatchDraw(canvas)

        if (shimAlpha > 0f && rows.isNotEmpty()) {
            if (maskMode) drawShimMask(canvas) else drawShim(canvas, props.gradientColor)
        }
        if (props.gradientRatio > 0f) {
            if (maskMode) {
                drawGradientMask(canvas, props.gradientRatio)
            } else {
                drawGradient(canvas, props.gradientColor, props.gradientRatio)
            }
        }
        if (needsLayer) canvas.restoreToCount(token)
        drawLabelLast(canvas)
    }

    /**
     * The read-only label goes on last, and outside the mask layer: upstream puts it in the outer `Box`
     * (`material3/Picker.kt:302-305`), above both the fade and the read-only shim. Drawn with the rows
     * it would sit under them, and a read-only column sets `shimAlpha = 1f` (`onPickerPropsChanged`),
     * which would bury "HOUR"/"MINUTE" entirely. [drawChild] holds the host back from the normal pass
     * so this is the only place it is composited; it translates by the frame [layoutLabelHost] gave it,
     * because `View.draw` expects to be positioned by its parent.
     */
    private fun drawLabelLast(canvas: Canvas) {
        val label = labelHost ?: return
        if (label.visibility == VISIBLE) {
            val labelToken = canvas.save()
            canvas.translate(label.left.toFloat(), label.top.toFloat())
            label.draw(canvas)
            canvas.restoreToCount(labelToken)
        }
    }

    /**
     * Keeps [labelHost] out of the normal child pass so [dispatchDraw] can put it above the shim —
     * `ViewGroup` would otherwise draw it with the rows, in child order. Clipping and the parent's
     * own translate still apply, because the canvas handed to [View.draw] is the parent's.
     */
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child === labelHost) return false
        return super.drawChild(canvas, child, drawingTime)
    }

    /** `ContentDrawScope.drawGradient`: colour outside, transparent toward the focal band. */
    private fun drawGradient(canvas: Canvas, gradientColor: Color, ratio: Float) {
        if (gradientColor.isUnspecified) return
        val argb = gradientColor.toArgb()
        val transparent = argb and 0x00FFFFFF
        val top = drawTopPx
        val bottom = drawTopPx + drawHeightPx
        fillPaint.xfermode = null
        fillPaint.shader = LinearGradient(
            width / 2f, top, width / 2f, top + drawHeightPx * ratio,
            argb, transparent, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, top, width.toFloat(), top + drawHeightPx * ratio, fillPaint)
        fillPaint.shader = LinearGradient(
            width / 2f, bottom - drawHeightPx * ratio, width / 2f, bottom,
            transparent, argb, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, bottom - drawHeightPx * ratio, width.toFloat(), bottom, fillPaint)
        fillPaint.shader = null
    }

    /** `ContentDrawScope.drawShim`: two solid rects at the animated alpha, hiding all but the centre. */
    private fun drawShim(canvas: Canvas, gradientColor: Color) {
        if (gradientColor.isUnspecified || shimAlpha <= 0f) return
        val height = shimHeightPx
        if (height <= 0f) return
        fillPaint.xfermode = null
        fillPaint.shader = null
        // Upstream replaces the colour's alpha (`gradientColor.copy(alpha = alpha)`), it does not
        // multiply the two together.
        fillPaint.color = gradientColor.copy(alpha = shimAlpha).toArgb()
        canvas.drawRect(0f, drawTopPx, width.toFloat(), drawTopPx + height, fillPaint)
        canvas.drawRect(
            0f, drawTopPx + drawHeightPx - height, width.toFloat(), drawTopPx + drawHeightPx, fillPaint,
        )
    }

    /** `(size.height - centerItem.unadjustedSize - verticalSpacing) / 2`: the space the rows do not take. */
    private val shimHeightPx: Float
        get() {
            val props = props ?: return 0f
            val centre = anchorRow() ?: return 0f
            return (drawHeightPx - centre.measuredHeight - props.verticalSpacingPx) / 2f
        }

    /** `ContentDrawScope.drawGradientMask`: the base brush, multiplied against the layer. */
    private fun drawGradientMask(canvas: Canvas, ratio: Float) {
        val top = drawTopPx
        val height = drawHeightPx
        fillPaint.xfermode = maskPaint.xfermode
        fillPaint.shader = LinearGradient(
            0f, top, 0f, top + height,
            intArrayOf(AndroidColor.TRANSPARENT, AndroidColor.WHITE, AndroidColor.WHITE, AndroidColor.TRANSPARENT),
            floatArrayOf(0f, ratio, 1f - ratio, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, top, width.toFloat(), top + height, fillPaint)
        fillPaint.shader = null
    }

    /** `ContentDrawScope.drawShimMask`: white where content may show, faded where the shim sits. */
    private fun drawShimMask(canvas: Canvas) {
        val height = drawHeightPx
        if (height <= 0f) return
        val ratio = (shimHeightPx / height).coerceIn(0f, 0.5f)
        val animated = Color.White.copy(alpha = 1f - shimAlpha).toArgb()
        fillPaint.xfermode = maskPaint.xfermode
        fillPaint.shader = LinearGradient(
            0f, drawTopPx, 0f, drawTopPx + height,
            intArrayOf(animated, animated, AndroidColor.WHITE, AndroidColor.WHITE, animated, animated),
            floatArrayOf(0f, ratio, ratio, 1f - ratio, 1f - ratio, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, drawTopPx, width.toFloat(), drawTopPx + height, fillPaint)
        fillPaint.shader = null
    }

    // endregion

    // region animation --------------------------------------------------------------------------

    /** `LaunchedEffect(readOnly) { animatedShimColorAlpha.animateTo(target, slowEffectsSpec()) }`. */
    private fun animateShimTo(target: Float, snap: Boolean) {
        shimJob?.cancel()
        if (snap) {
            shimAlpha = target
            invalidate()
            return
        }
        shimJob = animatorScope.launch {
            AnimationState(shimAlpha).animateTo(target, animationSpec = slowEffectsSpec()) {
                shimAlpha = value
                invalidate()
            }
            shimAlpha = target
            invalidate()
        }
    }

    /** `MaterialTheme.motionScheme.slowEffectsSpec()`: no overshoot at stiffness 260f. */
    private fun slowEffectsSpec(): SpringSpec<Float> =
        spring<Float>(dampingRatio = SpringDampingNoBouncy, stiffness = EffectsSlowStiffness)

    // endregion

    // region programmatic scrolling --------------------------------------------------------------

    /** `state.scrollToOption(index)` — upstream's `scrollToItem(getClosestTargetItemIndex(index), 0)`. */
    internal fun scrollToOption(option: Int) {
        val state = state ?: return
        scrollToItem(state.closestTargetItemIndex(option))
    }

    private fun scrollToItem(itemIndex: Int) {
        val state = state ?: return
        state.centerItemIndex = itemIndex
        state.centerItemScrollOffset = 0f
        centerOffsetPx = 0f
        requestLayout()
        invalidate()
    }

    /**
     * `state.animateScrollToOption(index)`: the same destination as [scrollToOption], reached by
     * animating the scroll. Not the same curve — upstream delegates the timing to Compose's
     * `LazyListState.animateScrollToItem` (reached via `ScalingLazyListState.scrollToItem(animated =
     * true, …)`), whose spec is picked by how many items are crossed and lives outside the reference
     * tree, so it cannot be cited or reproduced here. The rotary snap spring is used instead: it
     * always lands exactly on the item centre, which is the property both callers of this actually
     * depend on.
     */
    internal fun animateScrollToOption(option: Int) {
        val state = state ?: return
        scrollAnimated(distanceToCentre(state.closestTargetItemIndex(option)))
    }

    private fun scrollAnimated(distance: Float, velocity: Float = 0f) {
        scrollJob?.cancel()
        if (distance == 0f) return
        setScrollInProgress(true)
        scrollJob = animatorScope.launch {
            var last = 0f
            AnimationState(0f, velocity).animateTo(distance, animationSpec = snapAnimationSpec()) {
                scrollByPixels(value - last)
                last = value
            }
            scrollByPixels(distance - last)
            setScrollInProgress(false)
        }
    }

    /** The spring `RotarySnapHandler` settles with: stiffness 200f, snap threshold 0.1f. */
    private fun snapAnimationSpec(): SpringSpec<Float> =
        spring<Float>(stiffness = RotaryDefaultStiffness, visibilityThreshold = SnapVisibilityThreshold)

    /** `RotarySnapHandler.snapToClosestItem`, awaited: `tween(100, FastOutSlowInEasing)` onto the line. */
    private suspend fun snapToClosestItem() {
        var previousPosition = 0f
        AnimationState(0f).animateTo(
            targetValue = -(state?.centerItemScrollOffset ?: 0f),
            animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing),
        ) {
            scrollByPixels(value - previousPosition)
            previousPosition = value
        }
        rotarySnapTarget = centerIndex()
    }

    private fun startSnapToClosestItem() {
        scrollJob?.cancel()
        scrollJob = animatorScope.launch { snapToClosestItem() }
    }

    /** `RotaryScrollHandler.scrollToTarget`: the spring that carries the crown's accumulated pixels. */
    private fun scrollRotaryTo(targetValue: Float) {
        rotaryScrollJob?.cancel()
        rotaryScrollJob = animatorScope.launch {
            var previousPosition = rotaryScrollPrevious
            rotaryScrollAnim.animateTo(
                targetValue,
                animationSpec = if (atTheEdge()) {
                    spring<Float>(visibilityThreshold = EdgeVisibilityThreshold)
                } else {
                    spring()
                },
                sequentialAnimation = rotarySequentialScroll,
            ) {
                scrollByPixels(value - previousPosition)
                previousPosition = value
                rotaryScrollPrevious = value
                rotarySequentialScroll = value != targetValue
            }
            // Upstream ends with fling(0f) to hand control to the overscroll effect; there is none.
        }
    }

    // endregion

    // region fling ------------------------------------------------------------------------------

    /**
     * `ScalingLazyColumnSnapFlingBehavior.performFling`, copied: decay until the speed drops under
     * [SnapSpeedThreshold], choose the item whose landing point is nearest the decay target, then run a
     * cubic bézier that starts at the current speed and ends at rest on that item.
     *
     * Where the framework's snap behaviour cannot be reproduced: nothing here — the decay curve, the
     * landing choice and the bézier construction are upstream's, because the container is owned. With
     * a `RecyclerView` the decay phase would have been `OverScroller`'s instead.
     */
    private fun performFling(initialVelocity: Float) {
        if (initialVelocity.isNaN()) return
        scrollJob?.cancel()
        setScrollInProgress(true)
        scrollJob = animatorScope.launch {
            val decay = exponentialDecay<Float>()
            val animationState = AnimationState(0f, initialVelocity)
            var lastValue = 0f
            val isAFling = abs(initialVelocity) > 1f && rows.size > 1
            val finalTarget: Float
            if (isAFling) {
                val decayTarget = decay.calculateTargetValue(0f, initialVelocity)
                var endOfListReached = false
                animationState.animateDecay(decay) {
                    val delta = value - lastValue
                    val consumed = scrollByPixels(delta)
                    lastValue = value
                    if (abs(velocity) < SnapSpeedThreshold) cancelAnimation()
                    if (abs(delta - consumed) > 0.1f) {
                        endOfListReached = true
                        cancelAnimation()
                    }
                }
                if (endOfListReached) {
                    // Could not scroll as far as requested, so snap to the current item and finish.
                    scrollByPixels(-centerOffsetPx)
                    setScrollInProgress(false)
                    return@launch
                }
                finalTarget = rows
                    .map { lastValue + distanceToCentre(it.itemIndex) }
                    .minByOrNull { abs(it - decayTarget) }
                    ?: decayTarget
            } else {
                finalTarget = -centerOffsetPx
            }

            val distance = finalTarget - lastValue
            if (distance != 0.0f) {
                val initialSpeed = animationState.velocity
                val initialInertia = 0.5f
                val finalSnapDuration = pickerLerp(
                    FinalSnapDurationMin,
                    FinalSnapDurationMax,
                    abs(initialSpeed) / SnapSpeedThreshold,
                )
                val adjustedSpeed = initialSpeed * finalSnapDuration / distance
                val easingX0 = initialInertia / sqrt(1f + adjustedSpeed * adjustedSpeed)
                val easingY0 = easingX0 * adjustedSpeed
                val easingX1 = 0.8f
                val easingY1 = if (easingX0 > easingY0) 0.8f else 1f
                var previous = lastValue
                animationState.animateTo(
                    finalTarget,
                    animationSpec = tween(
                        durationMillis = (finalSnapDuration * 1000).roundToInt(),
                        easing = CubicBezierEasing(easingX0, easingY0, easingX1, easingY1),
                    ),
                ) {
                    scrollByPixels(value - previous)
                    previous = value
                }
            }
            setScrollInProgress(false)
        }
    }

    // endregion

    // region touch ------------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val props = props ?: return false
        val content = contentProps
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            if (content != null && pickerSelectOnDown && props.readOnly && !touchExplorationEnabled) {
                // `PickerGroupItem`'s pointerInput: the first down on an unselected picker selects it,
                // and it is a down rather than a click, so the group can react before the list does.
                content.onSelected()
            }
            if (!autoCenteringTarget) requestFocus()
        }
        if (!props.userScrollEnabled) return false
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastMotionY = event.y
                dragging = false
                scrollJob?.cancel()
                rotaryJob?.cancel()
                rotaryScrollJob?.cancel()
                setScrollInProgress(true)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true
                val y = event.getY(index)
                val dy = y - lastMotionY
                if (!dragging && abs(dy) < dragSlop) return true
                dragging = true
                scrollByPixels(-dy)
                lastMotionY = y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.addMovement(event)
                val tracker = velocityTracker
                velocityTracker = null
                val wasDragging = dragging
                dragging = false
                activePointerId = INVALID_POINTER
                val flingVelocity = if (tracker != null && wasDragging && action == MotionEvent.ACTION_UP) {
                    tracker.computeCurrentVelocity(
                        1000,
                        viewConfiguration.scaledMaximumFlingVelocity.toFloat(),
                    )
                    val yVelocity = tracker.yVelocity
                    tracker.recycle()
                    if (abs(yVelocity) > viewConfiguration.scaledMinimumFlingVelocity.toFloat()) -yVelocity else 0f
                } else {
                    tracker?.recycle()
                    0f
                }
                if (flingVelocity != 0f) performFling(flingVelocity) else startSnapToClosestItem()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Upstream exposes selection through `semantics { onClick(hint) { onSelected() } }`, so the
     * accessibility click is what triggers it; a plain tap on an editable picker does nothing there,
     * which is why [onTouchEvent] never calls this.
     */
    override fun performClick(): Boolean {
        contentProps?.onSelected?.invoke()
        return super.performClick()
    }

    // endregion

    // region rotary -----------------------------------------------------------------------------

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_SCROLL) return super.onGenericMotionEvent(event)
        if (event.source and InputDevice.SOURCE_ROTARY_ENCODER == 0) {
            return super.onGenericMotionEvent(event)
        }
        val props = props ?: return super.onGenericMotionEvent(event)
        val behavior = props.rotary ?: return super.onGenericMotionEvent(event)
        if (!props.userScrollEnabled) return super.onGenericMotionEvent(event)
        val axisDelta = event.getAxisValue(MotionEvent.AXIS_SCROLL)
        val delta = if (axisDelta != 0f) axisDelta else event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (delta == 0f) return super.onGenericMotionEvent(event)
        // `channel.trySend(event)` (`RotaryScrollable.kt:1798`): the send never blocks and never fails
        // for a conflated channel, so the event either reaches the pump or replaces the one already
        // waiting for it.
        rotaryEvents?.trySend(RotaryEvent(event.eventTime, delta, behavior))
        return true
    }

    /**
     * `RotaryInputNode` and `RotaryScrollLogic` collapse into this: Compose pumps each
     * `RotaryScrollEvent` through a conflated channel into the behaviour, and for a non-reversed
     * vertical list its two direction negations cancel, so a clockwise turn advances the picker.
     */
    private suspend fun performRotaryScroll(
        timestampMillis: Long,
        delta: Float,
        behavior: PickerRotaryBehavior,
    ) {
        if (isNewRotaryEvent(timestampMillis)) {
            resetRotaryGesture()
            rotaryThreshold = PickerThresholdHandler(
                minThresholdDivider = behavior.minThresholdDivider,
                maxThresholdDivider = behavior.maxThresholdDivider,
                averageItemSize = { averageItemSizePx() },
            )
            rotaryThreshold.startTracking(timestampMillis)
            rotarySnapTarget = centerIndex()
            rotaryScrollPrevious = 0f
            rotaryScrollAnim = AnimationState(0f)
        } else if (!rotaryIsLowRes && !isOppositeValueAfterScroll(delta)) {
            // High-res encoders emit backwards jitter at the start and end of a turn; upstream keeps
            // only the forward-pointing samples for the velocity estimate.
            rotaryThreshold.updateTracking(timestampMillis, delta)
        }
        previousRotaryEventTime = timestampMillis

        if (rotaryIsLowRes) {
            performLowResRotaryScroll(delta, behavior)
        } else {
            performHighResRotaryScroll(delta, behavior)
        }
    }

    /** `BaseRotaryScrollableBehavior.isNewScrollEvent`, with its 200 ms gesture threshold. */
    private fun isNewRotaryEvent(timestamp: Long): Boolean {
        val timeDelta = timestamp - previousRotaryEventTime
        return previousRotaryEventTime == -1L || timeDelta > GestureThresholdTimeMillis
    }

    private fun isOppositeValueAfterScroll(delta: Float): Boolean =
        rotaryScrollDistance * delta < 0f && abs(delta) < abs(rotaryScrollDistance)

    /**
     * `LowResSnapRotaryScrollableBehavior`: a bezel reports one detent at a time, so each accumulated
     * pixel of delta moves exactly one item; there is no threshold maths and no partial scroll.
     */
    private fun performLowResRotaryScroll(delta: Float, behavior: PickerRotaryBehavior) {
        rotaryAccumulatedDelta += delta
        if (abs(rotaryAccumulatedDelta) <= 1f) return
        val snapDistanceInItems = if (rotaryAccumulatedDelta > 0f) 1 else -1
        // Upstream fires this unconditionally for low-res, unlike the high-res branch.
        performSnapHaptic(behavior)
        val sequentialSnap = rotaryJob?.isActive == true
        updateSnapTarget(snapDistanceInItems, sequentialSnap)
        if (!sequentialSnap) {
            rotaryJob?.cancel()
            rotaryJob = animatorScope.launch { snapToTargetItem(behavior, sequentialSnap) }
        }
        rotaryAccumulatedDelta = 0f
    }

    /**
     * `HighResSnapRotaryScrollableBehavior`: the crown's pixels are compared against a speed-dependent
     * threshold; below it the content slides by a proximity-damped fraction and then settles onto the
     * nearest item, above it one or two items are snapped.
     */
    private suspend fun performHighResRotaryScroll(delta: Float, behavior: PickerRotaryBehavior) {
        val snapThreshold = rotaryThreshold.calculateSnapThreshold()
        if (rotaryJob?.isActive != true && snapThreshold > 0f) {
            rotaryScrollDistance += delta * calculateProximityFactor(snapThreshold)
        }
        rotaryAccumulatedDelta += delta

        if (abs(rotaryAccumulatedDelta) > snapThreshold && snapThreshold > 0f) {
            // Upstream's `resetScrolling()` here: it cancels the scroll *and* replaces the handler, so
            // the next partial scroll starts from a zeroed animation. Without the reset the stale
            // `rotaryScrollAnim.value` would be animated down to the new, much smaller target, which
            // reads as a jump backwards.
            resetRotaryScrolling()
            // A cap on snaps per event, or the snap would fly past the target.
            val snapDistanceInItems = (rotaryAccumulatedDelta / snapThreshold)
                .toInt()
                .coerceIn(-MaxSnapsPerEvent, MaxSnapsPerEvent)
            rotaryAccumulatedDelta -= snapThreshold * snapDistanceInItems
            val sequentialSnap = rotaryJob?.isActive == true
            if (edgeNotReached(snapDistanceInItems)) performSnapHaptic(behavior)
            updateSnapTarget(snapDistanceInItems, sequentialSnap)
            if (!sequentialSnap) {
                rotaryJob?.cancel()
                rotaryJob = animatorScope.launch { snapToTargetItem(behavior, sequentialSnap) }
            }
            return
        }

        if (rotaryJob?.isActive == true) return
        if (behavior.resistanceFactor > 0f) scrollRotaryTo(rotaryScrollDistance / behavior.resistanceFactor)
        delay(SnapDelayMillis)
        // `resetScrolling()`: a fresh scroll handler, so the next event starts from zero velocity.
        resetRotaryScrolling()
        rotaryAccumulatedDelta = 0f
        updateSnapTarget(0, sequentialSnap = false)
        // Upstream does not await this: `snapJob = async { snapToClosestItem() }`, and `snapJob`
        // staying live is what gates `rotaryScrollDistance += delta * proximityFactor` above. Leaving
        // it untracked here let the next event start a partial scroll over the top of the settle.
        rotaryJob?.cancel()
        rotaryJob = animatorScope.launch { snapToClosestItem() }
    }

    /** `HighResSnapRotaryScrollableBehavior.resetScrolling`: drop the in-flight scroll and its state. */
    private fun resetRotaryScrolling() {
        rotaryScrollJob?.cancel()
        rotaryScrollAnim = AnimationState(0f)
        rotaryScrollPrevious = 0f
        rotarySequentialScroll = false
        rotaryScrollDistance = 0f
    }

    /** `calculateProximityFactor`: the nearer to the threshold, the less the pending scroll moves. */
    private fun calculateProximityFactor(snapThreshold: Float): Float =
        1f - ScrollProximityEasing.transform(abs(rotaryScrollDistance) / snapThreshold)

    private fun edgeNotReached(snapDistanceInItems: Int): Boolean {
        val items = itemLimit()
        val topReached = rotarySnapTarget <= 0
        val bottomReached = items > 0 && rotarySnapTarget >= items - 1
        return (!topReached && snapDistanceInItems < 0) || (!bottomReached && snapDistanceInItems > 0)
    }

    /** `RotarySnapHandler.updateSnapTarget`. */
    private fun updateSnapTarget(moveForElements: Int, sequentialSnap: Boolean) {
        rotarySequentialSnap = sequentialSnap
        rotaryTargetUpdated = true
        val items = itemLimit()
        var target = if (sequentialSnap) {
            rotarySnapTarget + moveForElements
        } else {
            centerIndex() + moveForElements
        }
        target = if (items > 0) target.coerceIn(0, items - 1) else 0
        rotarySnapTarget = target
    }

    /**
     * `RotarySnapHandler.snapToTargetItem`: animate to the estimated distance of the target, restarting
     * whenever the focal item changes underneath the animation, then spring onto the target's centre.
     */
    private suspend fun snapToTargetItem(behavior: PickerRotaryBehavior, sequentialSnap: Boolean) {
        val snapOffsetPx = behavior.snapOffsetDp.toPx(this)
        // `RotarySnapHandler` keeps one `anim` for the life of the handler and only replaces it when
        // the snap was not sequential, so the velocity carries across a target update.
        if (!sequentialSnap) rotarySnapAnim = AnimationState(0f)
        val anim = rotarySnapAnim
        var expectedDistance = 0f
        var snapTargetUpdated = rotaryTargetUpdated.also { rotaryTargetUpdated = false }

        while (snapTargetUpdated) {
            snapTargetUpdated = false
            var continueFirstScroll = true
            while (continueFirstScroll) {
                val latestCenterItem = centerIndex()
                expectedDistance = expectedDistanceTo(rotarySnapTarget, snapOffsetPx)
                continueFirstScroll = false
                var previousPosition = anim.value
                anim.animateTo(
                    previousPosition + expectedDistance,
                    animationSpec = if (atTheEdge()) {
                        spring<Float>(visibilityThreshold = EdgeVisibilityThreshold)
                    } else {
                        spring<Float>(
                            stiffness = RotaryDefaultStiffness,
                            visibilityThreshold = SnapVisibilityThreshold,
                        )
                    },
                    sequentialAnimation = anim.velocity != 0f,
                ) {
                    if (rotaryTargetUpdated) {
                        rotaryTargetUpdated = false
                        snapTargetUpdated = true
                        cancelAnimation()
                    }
                    scrollByPixels(value - previousPosition)
                    previousPosition = value
                    if (latestCenterItem != centerIndex()) {
                        continueFirstScroll = true
                        cancelAnimation()
                        return@animateTo
                    }
                    if (centerIndex() == rotarySnapTarget) {
                        expectedDistance = -(state?.centerItemScrollOffset ?: 0f) + snapOffsetPx
                        continueFirstScroll = false
                        cancelAnimation()
                    }
                }
            }
            if (snapTargetUpdated || atTheEdge()) continue
            var previousPosition = anim.value
            anim.animateTo(
                previousPosition + expectedDistance,
                animationSpec = SpringSpec(
                    stiffness = RotaryDefaultStiffness,
                    visibilityThreshold = SnapVisibilityThreshold,
                ),
                sequentialAnimation = anim.velocity != 0f,
            ) {
                if (rotaryTargetUpdated) {
                    rotaryTargetUpdated = false
                    snapTargetUpdated = true
                    cancelAnimation()
                }
                scrollByPixels(value - previousPosition)
                previousPosition = value
            }
        }
    }

    /** `RotarySnapHandler.expectedDistanceTo`. */
    private fun expectedDistanceTo(index: Int, targetScrollOffsetPx: Int): Float {
        val averageSize = averageItemSizePx()
        val indexesDiff = index - centerIndex()
        return (averageSize * indexesDiff) + targetScrollOffsetPx -
            (state?.centerItemScrollOffset ?: 0f)
    }

    private fun atTheEdge(): Boolean {
        val state = state ?: return false
        if (state.shouldRepeatOptions) return false
        return !canScrollBackward() || !canScrollForward()
    }

    internal fun canScrollBackward(): Boolean {
        val state = state ?: return false
        if (state.shouldRepeatOptions) return true
        return distanceToCentre(0) < 0f
    }

    internal fun canScrollForward(): Boolean {
        val state = state ?: return false
        if (state.shouldRepeatOptions) return true
        return distanceToCentre(itemLimit() - 1) > 0f
    }

    private fun performSnapHaptic(behavior: PickerRotaryBehavior) {
        if (!behavior.hapticsEnabled) return
        // Upstream funnels every rotary haptic through `receiveAsFlow().throttleLatest(30)`
        // (`foundation/rotary/Haptics.kt:90`, `:95`), whose own comment (`:88-89`) describes it as
        // keeping the first *and last* event in the window. Keeping only the first is the same guard
        // without a flow operator, and what it drops is a second tick inside 30 ms of the first: the
        // snap target itself is unaffected, because the throttle sits after `updateSnapTarget` and
        // upstream's handler thread never feeds back into the scroll.
        val now = System.currentTimeMillis()
        if (now <= lastSnapHapticTime + RotaryHapticThrottleMillis) return
        lastSnapHapticTime = now
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun resetRotaryGesture() {
        rotaryAccumulatedDelta = 0f
        rotaryScrollDistance = 0f
        rotarySequentialScroll = false
        rotaryJob?.cancel()
        rotaryJob = null
    }

    // endregion

    // region accessibility ----------------------------------------------------------------------

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val props = props ?: return
        info.isScrollable = props.userScrollEnabled
        // `Role.ValuePicker` would rewrite the class name to a NumberPicker; there is no
        // AccessibilityNodeInfo equivalent for it, so the class name stays the framework default.
        info.isFocusable = !props.readOnly
    }

    /**
     * `Modifier.scrollableForTouchExploration(state)` gives TalkBack's swipe the picker's snap fling;
     * here a scroll action moves exactly one option and settles on it.
     */
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val props = props ?: return super.performAccessibilityAction(action, arguments)
        if (!props.userScrollEnabled) return super.performAccessibilityAction(action, arguments)
        return when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
                scrollOneItemBy(1)
                true
            }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                scrollOneItemBy(-1)
                true
            }
            else -> super.performAccessibilityAction(action, arguments)
        }
    }

    internal fun scrollOneItemBy(items: Int) {
        val target = (centerIndex() + items).coerceIn(0, (itemLimit() - 1).coerceAtLeast(0))
        val behavior = props?.rotary
        updateSnapTarget(0, sequentialSnap = false)
        rotarySnapTarget = target
        rotaryTargetUpdated = false
        rotaryJob?.cancel()
        if (behavior == null) {
            scrollToItem(target)
        } else {
            rotaryJob = animatorScope.launch { snapToTargetItem(behavior, sequentialSnap = false) }
        }
    }

    // endregion

    companion object {
        private val DrawInsetDp: Dp = 1f.dp
        private const val MaxPooledRows = 12
        private const val INVALID_POINTER = -1
        private const val INVALID_ITEM = -1
        private const val INVALID_GENERATION = -1

        // ScalingLazyColumnSnapFlingBehavior.kt
        private const val SnapSpeedThreshold = 1200f
        private const val FinalSnapDurationMin = .1f
        private const val FinalSnapDurationMax = .35f

        // MotionScheme.kt
        private const val SpringDampingNoBouncy = 1f
        private const val EffectsSlowStiffness = 260f

        // RotaryScrollable.kt
        private const val RotaryDefaultStiffness = 200f
        private const val SnapVisibilityThreshold = 0.1f
        private const val EdgeVisibilityThreshold = 0.3f
        private const val GestureThresholdTimeMillis = 200L
        private const val SnapDelayMillis = 100L

        /** `Haptics.kt:90` `throttleThresholdMs`. */
        private const val RotaryHapticThrottleMillis = 30L
        private const val MaxSnapsPerEvent = 2
        private const val FEATURE_LOW_RES_ROTARY = "android.hardware.rotaryencoder.lowres"

        private val ScrollProximityEasing: Easing = CubicBezierEasing(0.0f, 0.0f, 0.5f, 1.0f)
    }
}

/**
 * `ThresholdHandler` from RotaryScrollable.kt: how many pixels of crown rotation are needed to move
 * one item, loosened as the crown speeds up, with exponential smoothing on the velocity to cut jitter.
 */
private class PickerThresholdHandler(
    private val minThresholdDivider: Float,
    private val maxThresholdDivider: Float,
    private val averageItemSize: () -> Float,
) {
    private val minVelocity = 300f
    private val maxVelocity = 3000f
    private val smoothingConstant = 0.4f
    private val tracker = PickerRotaryVelocityTracker()
    private var smoothedVelocity = 0f

    fun startTracking(time: Long) {
        tracker.start(time)
        smoothedVelocity = 0f
    }

    fun updateTracking(timestamp: Long, delta: Float) {
        tracker.move(timestamp, delta)
        applySmoothing()
    }

    /** Threshold = average item size / a divider that tightens between the sensitivity extremes. */
    fun calculateSnapThreshold(): Float =
        averageItemSize() / pickerLerp(minThresholdDivider, maxThresholdDivider, fraction())

    private fun fraction(): Float =
        ThresholdDividerEasing.transform(pickerInverseLerp(minVelocity, maxVelocity, smoothedVelocity))

    private fun applySmoothing() {
        val velocity = tracker.velocity
        if (velocity != 0.0f) {
            smoothedVelocity =
                smoothingConstant * abs(velocity) + (1 - smoothingConstant) * smoothedVelocity
        }
    }

    companion object {
        private val ThresholdDividerEasing: Easing = CubicBezierEasing(0.5f, 0.0f, 0.5f, 1.0f)
    }
}

/**
 * `RotaryVelocityTracker` wraps Compose's `VelocityTracker1D`, which has no Views counterpart. This is
 * an ordinary least-squares fit of accumulated rotary position against time, reported in pixels per
 * second over the samples since [start]: the same quantity the snap threshold reads, and only its
 * magnitude is used, so the weighting difference is invisible.
 */
private class PickerRotaryVelocityTracker {
    private val times = LongArray(MaxSamples)
    private val positions = FloatArray(MaxSamples)
    private var count = 0
    private var accumulated = 0f

    val velocity: Float get() = calculateVelocity()

    fun start(currentTime: Long) {
        count = 0
        accumulated = 0f
        move(currentTime, 0f)
    }

    fun move(currentTime: Long, delta: Float) {
        accumulated += delta
        if (count == times.size) {
            times.copyInto(times, 0, 1, count)
            positions.copyInto(positions, 0, 1, count)
            count--
        }
        times[count] = currentTime
        positions[count] = accumulated
        count++
    }

    private fun calculateVelocity(): Float {
        if (count < 2) return 0f
        var sumTime = 0.0
        var sumPosition = 0.0
        var sumTimeSquare = 0.0
        var sumTimePosition = 0.0
        for (i in 0 until count) {
            val time = times[i].toDouble()
            val position = positions[i].toDouble()
            sumTime += time
            sumPosition += position
            sumTimeSquare += time * time
            sumTimePosition += time * position
        }
        val denominator = count * sumTimeSquare - sumTime * sumTime
        if (denominator == 0.0) return 0f
        val perMillisecond = (count * sumTimePosition - sumTime * sumPosition) / denominator
        return (perMillisecond * 1000.0).toFloat()
    }

    companion object {
        private const val MaxSamples = 8
    }
}

/** One row of option content: a host view whose subtree is produced by tuning the `option` slot. */
private class PickerRowView(context: Context) : FrameLayout(context) {
    var holder: HibariViewHolder? = null
    var itemIndex: Int = -1

    /** What the holder is currently tuned to, so a measure pass does not re-tune it. */
    var boundOptionIndex: Int = -1
    var boundGeneration: Int = -1
}

/**
 * `AutoCenteringRow` from PickerGroup.kt: a horizontal row that slides so the child marked
 * [com.huanli233.hibari.wear.pickerAutoCenteringTarget] sits under the centre line, animating the
 * slide, and centres the whole row when no child is marked.
 *
 * It extends `FrameLayout` so its children can carry the usual `Modifier.width`/`height` layout
 * attributes; placement is its own entirely, which is why `super.onMeasure`/`super.onLayout` are not
 * called. Upstream measures children with `constraints.copyMaxDimensions()` (min := max), forcing each
 * one to the row's size; a child that declares its own size still wins, just as a Compose
 * `Modifier.width` would, and `propagateMinConstraints = true` hands the incoming min..max range to
 * the children instead, which in Views is the ordinary wrap behaviour.
 *
 * `Modifier.singlePointerInput` is enforced here rather than in the picker: when
 * [PickerGroupProps.singlePointerInput] is set — upstream applies the filter only when touch
 * exploration is off and `autoCenter` is on — the row intercepts the gesture as soon as a second
 * finger touches it, so no other column can be selected or scrolled by that finger. Deviation: the
 * first finger's scroll is cancelled instead of continuing, because a `ViewGroup` cannot forward one
 * pointer of a multi-pointer stream to a child while swallowing the rest.
 */
class WearPickerGroupView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var animatorScope = pickerAnimatorScope()
    private var centeringJob: Job? = null
    private var anim: AnimationState<Float, AnimationVector1D> = AnimationState(0f)

    /** Upstream's `CenteringOffsetNotInitialized` sentinel: the very first pass has no target yet. */
    private var targetCenteringOffset = CenteringOffsetNotInitialized
    private var animatedOffset = 0f
    private var secondaryPointerSeen = false
    private var props = PickerGroupProps(
        autoCenter = true,
        propagateMinConstraints = false,
        singlePointerInput = false,
        centeringDampingRatio = StandardSpatialDampingRatio,
        centeringStiffness = StandardFastStiffness,
        selectedState = null,
    )

    internal var pickerGroupProps: PickerGroupProps
        get() = props
        set(value) {
            if (props == value) return
            props = value
            // `autoCenter` feeds the default offset, so a change re-derives it on the next pass.
            targetCenteringOffset = CenteringOffsetNotInitialized
            requestLayout()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animatorScope.isActive) animatorScope = pickerAnimatorScope()
    }

    /** `Modifier.singlePointerInput`: swallow everything once a second finger joins the gesture. */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (props.singlePointerInput) {
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> secondaryPointerSeen = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> secondaryPointerSeen = false
            }
        }
        return secondaryPointerSeen
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Only reached while intercepting, which only happens with a secondary pointer in play.
        return props.singlePointerInput && secondaryPointerSeen
    }

    /**
     * Upstream installs `Modifier.scrollableForTouchExploration(selectedPickerState)` on the row
     * while touch exploration is on, so TalkBack's swipe scrolls the selected picker with its snap
     * fling. Here the row forwards the two scroll actions to that picker's own view, which already
     * snaps one item at a time.
     */
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val items = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1
            else -> return super.performAccessibilityAction(action, arguments)
        }
        val picker = props.selectedState?.picker ?: return false
        picker.scrollOneItemBy(items)
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = props.selectedState != null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        // `rowWidth = if (constraints.hasBoundedWidth) maxWidth else minWidth`
        val width = if (widthMode == MeasureSpec.UNSPECIFIED) suggestedMinimumWidth
        else MeasureSpec.getSize(widthMeasureSpec)
        val rowHeightHint = if (heightMode == MeasureSpec.UNSPECIFIED) suggestedMinimumHeight
        else MeasureSpec.getSize(heightMeasureSpec)

        var maxChildHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (props.propagateMinConstraints || widthMode == MeasureSpec.UNSPECIFIED) {
                measureChild(child, widthMeasureSpec, heightMeasureSpec)
            } else {
                child.measure(
                    MeasureSpec.makeMeasureSpec(
                        sizeFor(child, width, horizontal = true),
                        MeasureSpec.EXACTLY,
                    ),
                    MeasureSpec.makeMeasureSpec(
                        sizeFor(child, rowHeightHint, horizontal = false),
                        MeasureSpec.EXACTLY,
                    ),
                )
            }
            maxChildHeight = max(maxChildHeight, child.measuredHeight)
        }

        // `calculateHeight`: maxChildrenHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> min(maxChildHeight, MeasureSpec.getSize(heightMeasureSpec))
            else -> maxChildHeight
        }

        setMeasuredDimension(width, height)

        val newTargetOffset = findTargetCenteringOffset()
        if (newTargetOffset != null) {
            targetCenteringOffset = newTargetOffset.toFloat()
        } else if (targetCenteringOffset == CenteringOffsetNotInitialized) {
            targetCenteringOffset = computeDefaultCenteringOffset().toFloat()
        }
        // A pass before anything is settled animates from 0f, exactly like upstream's sentinel check.
        val offsetForAnimation =
            if (targetCenteringOffset == CenteringOffsetNotInitialized) 0f else targetCenteringOffset
        animateCentering(offsetForAnimation)
        placeChildren()
    }

    /** The child's own declared size when it has one, otherwise the row's size (`copyMaxDimensions`). */
    private fun sizeFor(child: View, rowSize: Int, horizontal: Boolean): Int {
        val lp = child.layoutParams
        val declared = if (horizontal) lp?.width else lp?.height
        return if (declared != null && declared > 0) declared else max(1, rowSize)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        placeChildren()
    }

    private fun placeChildren() {
        val rowHeight = if (height > 0) height else measuredHeight
        var x = rowWidth / 2f - animatedOffset
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val left = if (rtl) rowWidth - x - child.measuredWidth else x
            val top = (rowHeight - child.measuredHeight) / 2f
            val roundedLeft = left.roundToInt()
            val roundedTop = top.roundToInt()
            child.layout(
                roundedLeft,
                roundedTop,
                roundedLeft + child.measuredWidth,
                roundedTop + child.measuredHeight,
            )
            x += child.measuredWidth
        }
    }

    private val rowWidth: Int get() = if (width > 0) width else measuredWidth

    /** `findTargetCenteringOffset`: the marked child's centre, or null when none is marked. */
    private fun findTargetCenteringOffset(): Int? {
        var currentWidth = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (child is WearPickerView && child.autoCenteringTarget) {
                return currentWidth + child.measuredWidth / 2
            }
            currentWidth += child.measuredWidth
        }
        return null
    }

    /** `computeDefaultCenteringOffset`: centre the first child, else the whole row. */
    private fun computeDefaultCenteringOffset(): Int {
        var sum = 0
        var firstWidth = -1
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (firstWidth < 0) firstWidth = child.measuredWidth
            sum += child.measuredWidth
        }
        return if (props.autoCenter && firstWidth >= 0) firstWidth / 2 else sum / 2
    }

    /** `animateFloatAsState(target, MaterialTheme.motionScheme.fastSpatialSpec())`. */
    private fun animateCentering(target: Float) {
        if (animatedOffset == target) return
        centeringJob?.cancel()
        centeringJob = animatorScope.launch {
            anim.animateTo(
                target,
                animationSpec = SpringSpec(
                    dampingRatio = props.centeringDampingRatio,
                    stiffness = props.centeringStiffness,
                ),
            ) {
                animatedOffset = value
                placeChildren()
                invalidate()
            }
            animatedOffset = target
            placeChildren()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        centeringJob?.cancel()
        animatorScope.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val CenteringOffsetNotInitialized = Float.MIN_VALUE

        // MotionScheme.kt, `MotionScheme.standard().fastSpatialSpec()` — see the note in PickerGroup.
        private const val StandardSpatialDampingRatio = 1f
        private const val StandardFastStiffness = 1400f
    }
}

/**
 * Every picker animation runs on the main thread's frame clock; `immediate` so a resumption that is
 * already on the main thread does not wait for another turn.
 */
private fun pickerAnimatorScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** Compose's `lerp` for Float, which does **not** clamp its fraction — upstream relies on that. */
private fun pickerLerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

/** `inverseLerp` from ScalingLazyColumnMeasure.kt, clamped to `0f..1f`. */
private fun pickerInverseLerp(start: Float, stop: Float, value: Float): Float =
    ((value - start) / (stop - start)).coerceIn(0f, 1f)
