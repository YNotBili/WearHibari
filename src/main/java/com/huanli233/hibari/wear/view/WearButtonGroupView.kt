package com.huanli233.hibari.wear.view

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.huanli233.hibari.animation.AnimationState
import com.huanli233.hibari.animation.AnimationVector1D
import com.huanli233.hibari.animation.SpringSpec
import com.huanli233.hibari.animation.animateTo
import com.huanli233.hibari.animation.spring
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.unit.Density
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.takeOrElse
import com.huanli233.hibari.ui.unit.toPx
import com.huanli233.hibari.wear.ButtonGroupDefaults
import com.huanli233.hibari.wear.ContainerDrawable
import com.huanli233.hibari.wear.buttonGroupComputeWidths
import com.huanli233.hibari.wear.buttonGroupPressExpandedWidths
import com.huanli233.hibari.wear.waitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Everything [WearButtonGroupView] needs to place its children, as one immutable value.
 *
 * Hibari diffs attributes with `equals`, so the group is configured with a single spec instead of a
 * handful of live properties: a retune that changes nothing leaves the attribute equal and skips
 * `requestLayout()`.
 *
 * @param spacing gap between children; upstream's `ButtonGroupDefaults.Spacing`.
 * @param expansionWidth how much a touched child grows while its neighbours shrink.
 * @param verticalAlignment `Gravity.TOP`, `Gravity.CENTER_VERTICAL` or `Gravity.BOTTOM`; the Views
 *   stand-in for upstream's `Alignment.Vertical`.
 * @param shape the pill the group as a whole reads as; its outer corners are handed to the first and
 *   last child and its inner corners are collapsed. Not a port — upstream's `ButtonGroup` measures a
 *   `Layout` and never looks at a child's shape; see
 *   [com.huanli233.hibari.wear.SplitButtonGroup] for where this came from.
 * @param collapsedCornerSize radius a shared edge keeps, so adjacent children form one pill. Part of
 *   the same non-port as [shape].
 * @param connectEdges false leaves every child's own container alone, which is upstream; true lets
 *   the group rewrite them from the child's position in the group. Also part of that non-port.
 * @param defaultMinWidth minimum width for a child that never called `Modifier.minWidth`, i.e.
 *   upstream's `ButtonGroupParentData.DEFAULT.minWidth`.
 */
data class ButtonGroupLayout(
    val spacing: Dp = ButtonGroupDefaults.Spacing,
    val expansionWidth: Dp = ButtonGroupDefaults.ExpansionWidth,
    val verticalAlignment: Int = Gravity.CENTER_VERTICAL,
    val shape: Shape = ButtonGroupDefaults.shape,
    val collapsedCornerSize: Dp = ButtonGroupDefaults.CollapsedCornerSize,
    val connectEdges: Boolean = false,
    val defaultMinWidth: Dp = ButtonGroupDefaults.MinWidth,
)

/**
 * Ported from the `Layout { ... }` measure/place block of `ButtonGroup` in
 * androidx.wear.compose.material3 (`:130`-`:202`). The arithmetic is upstream's, and shared with the
 * composable through [buttonGroupComputeWidths] and [buttonGroupPressExpandedWidths]: weights are
 * distributed over the bounded width, the press expansion then grows the held child at its
 * neighbours' expense, children are measured at an exact width, the group is as tall as its tallest
 * child, and the row is mirrored for RTL.
 *
 * The press expansion is animated, per child, by the two springs upstream uses: [drivePress] is
 * `EnlargeOnPressNode.launchCollectionJob`'s `collectLatest` branch, and each child's 0..1 value is
 * read back into `animatedSizes` at measure time exactly as upstream reads
 * `configs[it].pressedState.value`. A child that never called `Modifier.animateWidth` has no press
 * value to read, so it neither grows nor is told to shrink.
 *
 * What this adds on top of upstream is the connected-corner rewrite in [applyConnectedShapes], which
 * is not a port at all — see [com.huanli233.hibari.wear.SplitButtonGroup]. It only rewrites child
 * backgrounds during `onLayout`; none of the measurement above depends on it, and turning
 * `connectEdges` off leaves the geometry exactly upstream's.
 *
 * Deliberate differences from upstream, and which side is right:
 *  - One pointer at a time. Upstream keeps a per-child list of `PressInteraction.Press`es, so two
 *    fingers on two children grow both at once; this view tracks the single slot its first pointer
 *    went down on. The width arithmetic does handle several non-zero press values at once —
 *    [buttonGroupPressExpandedWidths] is the upstream loop, compounding in index order — so this is
 *    an input limitation, not a modelling one.
 *  - A child that is not clickable still grows here, because the group cannot tell a press the child
 *    consumed from one it ignored; upstream only ever sees the interactions its clickable children
 *    publish.
 *  - A neighbour squeezed past zero pixels by the expansion is measured at 0. Upstream hands
 *    `constraints.copy(minWidth = widths[ix], maxWidth = widths[ix])` straight to Compose, whose
 *    `Constraints` rejects a negative minWidth, so the same squeeze there is an exception rather than
 *    a flat button. Upstream is arguably the better diagnostic; the clamp keeps a Wear app on screen.
 *  - `transformation` (Compose's `SurfaceTransformation`) is not applied, because it is not ported.
 *  - A child measured with `Gravity.TOP`/`BOTTOM`/`CENTER_VERTICAL` is aligned by gravity; any other
 *    value is centred, since a row only has three vertical positions.
 */
class WearButtonGroupView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    /**
     * Per-child configuration, carried on the layout params because that is where a `ViewGroup`
     * keeps it: this is the Views form of upstream's `ButtonGroupParentData` plus the two
     * `ParentDataModifierNode`s (`ButtonGroupNode` at `:372`, `EnlargeOnPressNode` at `:422`) that
     * build it.
     */
    class LayoutParams(width: Int, height: Int) : LinearLayout.LayoutParams(width, height) {

        /**
         * Upstream's `ButtonGroupNode.minWidth`: unspecified until `Modifier.minWidth` supplies a
         * value, at which point it *replaces* the group default rather than being clamped to it —
         * which is `minWidth.takeOrElse { prev.minWidth }` at `:378`.
         */
        var minWidth: Dp = Dp.Unspecified

        /** True once this child has called `Modifier.animateWidth`. */
        var animatesOnPress: Boolean = false

        /** Upstream's `ButtonGroupParentData.pressedState`, here plus the job driving it. */
        internal val press = ButtonGroupPress()

        init {
            // Upstream's default is 1f (`ButtonGroupParentData.DEFAULT`), not LinearLayout's 0f.
            weight = 1f
        }
    }

    var buttonGroupLayout: ButtonGroupLayout = ButtonGroupLayout()
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** Child view indices that were visible at the last measure, in order. */
    private var slotIndices = IntArray(0)

    /** Computed width per slot, in pixels. */
    private var slotWidths = IntArray(0)

    /** Slot under the first pointer, or -1; see the one-pointer note on the class. */
    private var pressedSlot = -1

    private var animatorScope = buttonGroupAnimationScope()

    override fun checkLayoutParams(params: ViewGroup.LayoutParams?): Boolean = params is LayoutParams

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams =
        LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    override fun generateLayoutParams(params: ViewGroup.LayoutParams): LayoutParams =
        LayoutParams(params.width, params.height)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A detached view cancelled its scope and a `SupervisorJob` does not come back; without a
        // fresh scope a re-attached group would press without ever animating.
        if (!animatorScope.isActive) animatorScope = buttonGroupAnimationScope()
    }

    override fun onDetachedFromWindow() {
        animatorScope.cancel()
        pressedSlot = -1
        for (index in 0 until childCount) {
            (getChildAt(index).layoutParams as? LayoutParams)?.press?.reset()
        }
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> setPressedSlot(slotUnder(event))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setPressedSlot(-1)
        }
        return super.dispatchTouchEvent(event)
    }

    /**
     * Tell every child that opted into [com.huanli233.hibari.wear.ButtonGroupScope.animateWidth]
     * whether it is the one under [slot]. Upstream addresses the press to the child that owns the
     * pointer instead; asking all of them is the same predicate seen from the parent, and the point of
     * the expansion is that the neighbours have to be told too, because they are the ones that shrink.
     */
    private fun setPressedSlot(slot: Int) {
        if (slot == pressedSlot) return
        pressedSlot = slot
        for (index in slotIndices.indices) {
            val params = getChildAt(slotIndices[index]).layoutParams
            if (params is LayoutParams && params.animatesOnPress) {
                drivePress(params.press, pressed = index == slot)
            }
        }
    }

    /**
     * `EnlargeOnPressNode.launchCollectionJob`'s `collectLatest` body (`:462`): the newest press state
     * cancels the branch still running, a press grows to 1f on [ButtonGroupPressDownSpec], and a
     * release waits for upstream's threshold before retracting on [ButtonGroupPressUpSpec].
     *
     * Upstream drives an `Animatable`, whose mutex serialises overlapping `animateTo`s; this reuses
     * the one [AnimationState] per child and gets the same serialisation from cancelling the previous
     * job first, exactly as `collectLatest` does. The value and velocity in that state carry across
     * the interruption either way, so an interrupted press does not jump.
     */
    private fun drivePress(press: ButtonGroupPress, pressed: Boolean) {
        if (press.pressed == pressed) return
        press.pressed = pressed
        val state = press.state
        // Whether this flip interrupts a run: if it does, the new animation picks up the frame time
        // the old one stopped on rather than starting a fresh clock a frame later.
        val interrupted = state.isRunning
        press.job?.cancel()
        press.job = animatorScope.launch {
            if (pressed) {
                state.animateTo(1f, ButtonGroupPressDownSpec, sequentialAnimation = interrupted) {
                    requestLayout()
                }
            } else {
                // Upstream's `ButtonGroup.kt:466` wait, bail-out and all, ported through
                // [waitUntil]. The pairing is what makes that cap load-bearing: `collectLatest` cancels
                // the running branch when the release arrives and `ButtonGroup.kt:464` launches the grow
                // *inside* that branch, so the release cancels the only animation that can raise the
                // value it then waits for — a tap shorter than the ~36 ms the 5600f spring needs to pass
                // 0.75 leaves the child at its current expansion until the one second cap fires. Upstream
                // guards the same wait with `!progress.isRunning` at `RoundButton.kt:114` and
                // `AnimatedToggleRoundedCornerShape.kt:130`; the `ButtonGroup` copy has no such escape
                // hatch, and it is ported as written because the criterion is fidelity and the cost is a
                // stall rather than a wrong width. This is the line to change if the integration pass
                // would rather ship the corrected version.
                waitUntil { state.value > ButtonGroupPressRetractFloor }
                state.animateTo(0f, ButtonGroupPressUpSpec) { requestLayout() }
            }
            requestLayout()
        }
    }

    @SuppressLint("DrawAllocation")
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        require(MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            "ButtonGroup width cannot be unbounded."
        }

        val layoutSpec = buttonGroupLayout
        val horizontalPadding = paddingLeft + paddingRight
        val verticalPadding = paddingTop + paddingBottom
        val availableWidth = (MeasureSpec.getSize(widthMeasureSpec) - horizontalPadding).coerceAtLeast(0)
        val spacingPx = layoutSpec.spacing.toPx(this)
        val density = resources.displayMetrics.density

        slotIndices = visibleSlots()
        val items = slotIndices.map { childIndex ->
            val params = getChildAt(childIndex).layoutParams as? LayoutParams
            val minWidth = (params?.minWidth ?: Dp.Unspecified)
                .takeOrElse { layoutSpec.defaultMinWidth }
            val weight = params?.weight?.takeIf { it.isFinite() } ?: 1f
            // Upstream converts in float pixels (`it.minWidth.toPx()`, `:146`), not rounded pixels.
            buttonGroupDpToFloatPx(minWidth, density) to weight
        }

        val widths = buttonGroupComputeWidths(items, spacingPx, availableWidth)

        val animatedSizes = FloatArray(slotIndices.size) { slot ->
            val params = getChildAt(slotIndices[slot]).layoutParams
            if (params is LayoutParams && params.animatesOnPress) params.press.state.value else 0f
        }
        buttonGroupPressExpandedWidths(
            widths = widths,
            animatedSizes = animatedSizes,
            expansionWidthPx = buttonGroupDpToFloatPx(layoutSpec.expansionWidth, density),
        )

        slotWidths = widths

        var tallest = 0
        for (index in widths.indices) {
            val child = getChildAt(slotIndices[index])
            val childHeightSpec =
                getChildMeasureSpec(heightMeasureSpec, verticalPadding, child.layoutParams.height)
            child.measure(
                MeasureSpec.makeMeasureSpec(widths[index].coerceAtLeast(0), MeasureSpec.EXACTLY),
                childHeightSpec,
            )
            tallest = max(tallest, child.measuredHeight)
        }

        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = if (heightMode == MeasureSpec.UNSPECIFIED) 0 else MeasureSpec.getSize(heightMeasureSpec)
        // Compose hands a wrap-content parent minHeight 0 and an exact one both ends of the range.
        val minHeight = if (heightMode == MeasureSpec.EXACTLY) heightSize - verticalPadding else 0
        val maxHeight = (heightSize - verticalPadding).coerceAtLeast(minHeight)
        val height = tallest.coerceIn(minHeight, maxHeight)

        setMeasuredDimension(availableWidth + horizontalPadding, height + verticalPadding)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val layoutSpec = buttonGroupLayout
        val count = slotWidths.size
        if (count == 0 || count != slotIndices.size) return

        val spacingPx = layoutSpec.spacing.toPx(this)
        val groupHeight = (b - t) - paddingTop - paddingBottom
        val actualWidth = slotWidths.sum() + spacingPx * (count - 1)
        val rightToLeft = layoutDirection == View.LAYOUT_DIRECTION_RTL

        var x = 0
        for (index in 0 until count) {
            val child = getChildAt(slotIndices[index])
            val width = slotWidths[index]
            val childHeight = child.measuredHeight
            val actualX = if (!rightToLeft) x else actualWidth - x - width
            val top = paddingTop + buttonGroupVerticalOffset(layoutSpec.verticalAlignment, groupHeight, childHeight)
            child.layout(paddingLeft + actualX, top, paddingLeft + actualX + width, top + childHeight)
            x += width + spacingPx
        }

        applyConnectedShapes(layoutSpec, count, rightToLeft)
    }

    /**
     * Give each child only the corners its position in the group allows, by rebuilding its container
     * with the same colours and a collapsed shape. Not a port: upstream's `ButtonGroup` measure block
     * never looks at a child's shape — see the note on
     * [com.huanli233.hibari.wear.ButtonGroupDefaults.buttonShape].
     *
     * The comparison against the live shape is what keeps this allocation-free in steady state: once
     * a child has the shape its slot demands, later layouts do nothing, so the child's own
     * pressed/disabled colour animation is never restarted by a layout pass.
     */
    private fun applyConnectedShapes(layoutSpec: ButtonGroupLayout, count: Int, rightToLeft: Boolean) {
        if (!layoutSpec.connectEdges || count < 2) return
        for (index in 0 until count) {
            val child = getChildAt(slotIndices[index])
            val container = child.background as? ContainerDrawable ?: continue
            val shape = ButtonGroupDefaults.buttonShape(
                index = index,
                itemCount = count,
                shape = layoutSpec.shape,
                collapsedCornerSize = layoutSpec.collapsedCornerSize,
                isRightToLeft = rightToLeft,
            )
            if (container.spec.shape != shape) {
                child.background = ContainerDrawable(container.spec.copy(shape = shape), container.density)
            }
        }
    }

    private fun visibleSlots(): IntArray {
        val total = childCount
        val slots = IntArray(total)
        var size = 0
        for (i in 0 until total) {
            if (getChildAt(i).visibility != View.GONE) slots[size++] = i
        }
        return slots.copyOf(size)
    }

    private fun slotUnder(event: MotionEvent): Int {
        for (index in slotIndices.indices) {
            val child = getChildAt(slotIndices[index])
            if (event.x >= child.left && event.x < child.right &&
                event.y >= child.top && event.y < child.bottom
            ) {
                return index
            }
        }
        return -1
    }
}

/**
 * One child's animated 0..1 pressed value and the coroutine driving it: upstream's
 * `EnlargeOnPressNode.pressedAnimatable` and the `collectionJob` that feeds it.
 */
internal class ButtonGroupPress {

    /** Read at measure time as upstream reads `configs[it].pressedState.value`. */
    var state: AnimationState<Float, AnimationVector1D> = AnimationState(0f)

    /** The single `collectLatest` branch: the next press state cancels whatever is running. */
    var job: Job? = null

    var pressed: Boolean = false

    /**
     * Drop a half-travelled animation. Called when the group leaves the window, where the scope is
     * cancelled and the value would otherwise stay wherever the cancellation stopped it while
     * [pressed] claims nothing is held.
     */
    fun reset() {
        job = null
        pressed = false
        state = AnimationState(0f)
    }
}

/**
 * Upstream's `MotionScheme.standard().fastSpatialSpec<Float>().faster(100f)` (`ButtonGroup.kt:95`).
 *
 * These are springs, not tweens: there is no duration here to take from
 * [com.huanli233.hibari.wear.tokens.MotionDurationTokens], only the damping ratio and stiffnesses of
 * `MotionScheme.kt:260-269` — `StandardSpatialDampingRatio = 1.0f`, `StandardFastStiffness = 1400f`,
 * `StandardSlowStiffness = 260f` — and `AnimationSpecUtils.kt:60`, where `faster(100f)` is
 * `speedFactor(2f)` and `speedFactor` squares its factor onto the stiffness. Hence 1400f -> 5600f for
 * the grow, with the damping ratio untouched.
 */
private const val ButtonGroupSpatialDampingRatio = 1.0f

/** `MotionScheme.kt:268` `StandardFastStiffness`. */
private const val ButtonGroupStandardFastStiffness = 1400f

/** `MotionScheme.kt:269` `StandardSlowStiffness`. */
private const val ButtonGroupStandardSlowStiffness = 260f

/** `ButtonGroup.kt:95`'s `.faster(100f)` -> `speedFactor(1 + 100 / 100)` -> stiffness * 2². */
private const val ButtonGroupDownStiffness = ButtonGroupStandardFastStiffness * 4f

/** The `0.75f` in `waitUntil { pressedAnimatable.value > 0.75f }` (`ButtonGroup.kt:466`). */
private const val ButtonGroupPressRetractFloor = 0.75f

private val ButtonGroupPressDownSpec: SpringSpec<Float> =
    spring<Float>(
        dampingRatio = ButtonGroupSpatialDampingRatio,
        stiffness = ButtonGroupDownStiffness,
    )

/** Upstream's `MaterialTheme.motionScheme.slowSpatialSpec<Float>()`, i.e. `faster` is not applied. */
private val ButtonGroupPressUpSpec: SpringSpec<Float> =
    spring<Float>(
        dampingRatio = ButtonGroupSpatialDampingRatio,
        stiffness = ButtonGroupStandardSlowStiffness,
    )

/** Upstream converts dp to float pixels for both the expansion and the per-child minimums. */
private fun buttonGroupDpToFloatPx(source: Dp, density: Float): Float =
    with(Density(density)) { source.toPx() }

/** Every press animation runs on the main thread's frame clock; `immediate` so a resumption already
 * on the main thread does not wait for another turn. */
private fun buttonGroupAnimationScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** Upstream passes an `Alignment.Vertical`; a row has three of them, so gravity stands in. */
private fun buttonGroupVerticalOffset(verticalAlignment: Int, groupHeight: Int, childHeight: Int): Int =
    when (verticalAlignment) {
        Gravity.TOP -> 0
        Gravity.BOTTOM -> groupHeight - childHeight
        else -> (groupHeight - childHeight) / 2
    }
