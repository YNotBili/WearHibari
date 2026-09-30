package com.huanli233.hibari.wear.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.huanli233.hibari.animation.Easing
import com.huanli233.hibari.animation.FastOutSlowInEasing
import com.huanli233.hibari.animation.LinearEasing
import com.huanli233.hibari.animation.Spring
import com.huanli233.hibari.animation.SpringSpec
import com.huanli233.hibari.animation.animate
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.Offset
import com.huanli233.hibari.ui.unit.isUnspecified
import com.huanli233.hibari.wear.RevealActionType
import com.huanli233.hibari.wear.RevealDirection
import com.huanli233.hibari.wear.GestureInclusion
import com.huanli233.hibari.wear.RevealMath
import com.huanli233.hibari.wear.RevealState
import com.huanli233.hibari.wear.RevealValue
import com.huanli233.hibari.wear.SwipeToRevealDefaults
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The `ViewGroup` that turns [RevealState] into touch handling and pixels — the port of
 * `androidx.wear.compose.material3.SwipeToReveal` (`material3/SwipeToReveal.kt:241-677`).
 *
 * ## The three slots
 *
 * Upstream draws, in order: the reveal actions (`:442-628`, inside an overlay `Box` +
 * `AnimatedContent`), then the content (`:629-640`). Here that is three children **in this order**: the
 * action row, the undo row, the content. [com.huanli233.hibari.wear.SwipeToReveal] always emits all
 * three, the rows simply being empty when the caller passed no slots, because a `ViewGroup` addresses
 * children by index and a slot appearing or disappearing between retunes would move the content out
 * from under the finger.
 *
 * The overlay `Box(Modifier.matchParentSize(), contentAlignment = CenterRight)` (`:443-447`) is folded
 * into [WearRevealActionRowView], which right-aligns its own slots; see the notes there.
 *
 * ## The gesture
 *
 * `Modifier.anchoredDraggable(state, Orientation.Horizontal, enabled = …)` (`:340-348`) is a horizontal
 * drag that moves [RevealState.offset] by the pointer delta, gated on `allowSwipe` (the gesture
 * inclusion test) and on the reveal not already being complete. Translated to Views:
 *
 *  - [onInterceptTouchEvent] takes the sequence once a horizontal move beats
 *    `ViewConfiguration.scaledTouchSlop * SwipeToRevealDefaults.CustomTouchSlopMultiplier` and beats the
 *    vertical component — upstream's `CustomTouchSlopProvider(newTouchSlop = touchSlop * 1.20f)`
 *    (`:317-319`, `:1924`) resolved by [com.huanli233.hibari.wear.SwipeToReveal] and handed over as
 *    [touchSlopPx]. A tap therefore still reaches the action buttons underneath, which is what
 *    `setOnClickListener`-style semantics need.
 *  - The sign: a right-to-left swipe must produce a *negative* offset, because the anchors upstream
 *    builds sit at `-screenWidthPx * direction` (`:385-387`) and [RevealMath.contentOffsetX] forbids a
 *    positive offset in the one-direction case (`:634`). So the raw pointer delta goes into
 *    [RevealState.dispatchRawDelta] unwrapped, and a right-ward drag on a `RightToLeft` reveal is
 *    absorbed by the `maxPosition` clamp rather than moving anything.
 *  - `enabled = false` while [RevealValue.LeftRevealed] / [RevealValue.RightRevealed] (`:343-346`) is
 *    honoured by refusing to intercept, so a revealed row's buttons take touch.
 *  - A drag that begins while a settle animation runs does not wait for the slop — upstream's
 *    `startDragImmediately = state.isAnimationRunning` (`SwipeableV2.kt:145`), the same rule the sibling
 *    box carries (`WearSwipeToDismissView.kt:710-718`).
 *  - The release runs [RevealState.settle], the stand-in for `flingBehavior`
 *    (`SwipeToRevealDefaults.flingBehavior`, `:1018-1048`) whose target maths
 *    [RevealMath.computeTarget] carries verbatim.
 *
 * ## The effects
 *
 * Upstream's two `LaunchedEffect`s (`:641-674`) have no host, so both are polled from
 * [applyVisualState], which runs on every frame of a drag, a settle or a cross-fade: `resetLastState`
 * and `onSwipePrimaryAction` (with `lastActionType` stamped *before* the callback, as `:650-653` does
 * it) plus the haptics. The `require(...)` for a `RightToLeft` reveal sitting on a left-hand value
 * (`:264-270`) is checked in the same pass, because nothing recomposes to check it in.
 *
 * Haptics: `HapticFeedbackType.GestureThresholdActivate` (`:1895`) is
 * `HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE`, which is API 31. Below that the reveal is silent
 * rather than substituted — the rule `Stepper.kt` and `Slider.kt` state for their own guarded constants
 * — and upstream's 500 ms debounce (`:1910`, `:1886-1897`) is applied here too, so a fast fling crossing
 * both thresholds still ticks once.
 *
 * ## The animation
 *
 * No `ValueAnimator`: the settle *is* [RevealState.settle], a suspend call running the spec the state
 * carries, launched on [animationScope] (the `SupervisorJob() + Dispatchers.Main.immediate` shape the
 * module already uses — `lazy/WearExpandable.kt:78`, `view/WearPickerViews.kt:1842-1843`) and observed by
 * a `Choreographer.FrameCallback` ticker, exactly as `WearSwipeToDismissView` does (`:784-819`).
 * Everything upstream expresses as `animateFloatAsState` is a [RevealInterpolator] advanced on the same
 * ticker: all of those calls in this file use `tween(...)` (`:472-481`, `:519-552`), so a tween
 * reproduces them exactly except that an interrupted tween restarts from the current value with zero
 * velocity instead of continuing with its old one.
 *
 * Because the revealed width feeds the slots' *measured* sizes, one frame can mean a re-measure of the
 * row; [WearRevealActionRowView.applyGeometry] is therefore called from the ticker as well as from
 * [onLayout]. That is the price of upstream's `.layout { constraints.copy(maxWidth = …) }` (`:559-583`)
 * being a per-frame lambda in Compose and an explicit call here.
 *
 * @see RevealState
 * @see RevealMath
 */
class WearSwipeToRevealView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    /**
     * The state this reveal drives — one per revealable item, kept across retunes by
     * [com.huanli233.hibari.wear.rememberRevealState]. Replacing it cancels everything in flight and
     * forces a measure, because a fresh state has no anchors and no offset.
     */
    var revealState: RevealState = RevealState()
        set(value) {
            if (field === value) return
            endGesture()
            settleJob?.cancel()
            settleJob = null
            effectJob?.cancel()
            effectJob = null
            isSettling = false
            value.onFastFling = {
                // `onFastFling` (`:1036-1045`): a fling that skipped the positional checks towards a
                // Revealing anchor owes the user no second tick.
                if (
                    value.targetValue == RevealValue.LeftRevealing ||
                    value.targetValue == RevealValue.RightRevealing
                ) {
                    value.skipPartialHaptic = true
                }
            }
            anchoredScreenWidthPx = Float.NaN
            field = value
            lastEffectValue = RevealValue.Covered
            lastEffectActionType = RevealActionType.None
            lastObservedTarget = RevealValue.Covered
            showingUndo = false
            undoTransitionStartElapsed = -1L
            isUndoTransitionRunning = false
            requestLayout()
            applyVisualState()
        }

    /** The `revealDirection` parameter (`:251`). */
    var revealDirection: RevealDirection = RevealDirection.RightToLeft
        set(value) {
            if (field == value) return
            field = value
            anchoredScreenWidthPx = Float.NaN
            requestLayout()
        }

    /**
     * The `hasPartiallyRevealedState` parameter (`:252`): with `false` and a single action there is no
     * Revealing anchor at all (`:355-360`).
     */
    var hasPartiallyRevealedState: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            anchoredScreenWidthPx = Float.NaN
            requestLayout()
        }

    /** Whether a `secondaryAction` was passed (`:293`), which selects the 130 dp anchor. */
    var hasSecondaryAction: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            anchoredScreenWidthPx = Float.NaN
            requestLayout()
        }

    /**
     * The `gestureInclusion` parameter (`:253-258`). Its default is
     * [SwipeToRevealDefaults.gestureInclusion] for the state this view starts with;
     * [com.huanli233.hibari.wear.SwipeToReveal] always passes an inclusion and a state together, in that
     * attribute order, so a retune replaces both and the inclusion never points at a stale state.
     */
    var gestureInclusion: GestureInclusion =
        SwipeToRevealDefaults.gestureInclusion(revealState)

    /**
     * The horizontal slop, in px, a move has to beat before this box takes the gesture — upstream's
     * `CustomTouchSlopProvider(newTouchSlop = touchSlop * 1.20f)` (`material3/SwipeToReveal.kt:317-319`,
     * `:1924`), resolved by [com.huanli233.hibari.wear.SwipeToReveal] through
     * [com.huanli233.hibari.wear.currentTouchSlop] and handed over as an attribute. The default is the
     * same product read straight from the platform, for a box placed without the component.
     */
    var touchSlopPx: Float =
        ViewConfiguration.get(context).scaledTouchSlop * SwipeToRevealDefaults.CustomTouchSlopMultiplier

    /**
     * The `onSwipePrimaryAction` callback (`:653`). A plain settable property rather than an
     * `add*Listener`, because a lambda attribute is re-applied on every retune.
     */
    var onSwipePrimaryAction: () -> Unit = {}

    /** The `actionContentSpacing` parameter (`:259`) — the gap between the content and the actions. */
    var actionContentSpacing: Dp = SwipeToRevealDefaults.ActionContentSpacing
        set(value) {
            if (field == value) return
            field = value
            rowsForEach { row -> row.actionContentSpacing = value }
        }

    // region slots

    /** The reveal actions row: index 0, upstream's overlay `Box` (`:443-628`). */
    private val actionsRow: WearRevealActionRowView?
        get() = getChildAt(0) as? WearRevealActionRowView

    /** The undo row: index 1, the other arm of the same `AnimatedContent` (`:470-500`). */
    private val undoRow: WearRevealActionRowView?
        get() = if (childCount > 1) getChildAt(1) as? WearRevealActionRowView else null

    /** The content: upstream's `Row { content() }`, the last child (`:629-640`). */
    private val contentChild: View?
        get() = if (childCount == 0) null else getChildAt(childCount - 1)

    private inline fun rowsForEach(block: (WearRevealActionRowView) -> Unit) {
        for (i in 0 until childCount) {
            (getChildAt(i) as? WearRevealActionRowView)?.let(block)
        }
    }

    // endregion

    // region measure and layout

    private var anchoredScreenWidthPx: Float = Float.NaN
    private var anchoredWidth: Int = -1
    private var anchoredHeight: Int = -1
    private var anchoredDensity: Float = Float.NaN
    private var anchoredDirection: Int = 1
    private var anchoredSecondary: Boolean = false
    private var anchoredPartial: Boolean = true

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // `modifier.fillMaxWidth()` (`:323`) with a height that wraps the content: the overlay rows are
        // `matchParentSize()` (`:444`) and so never contribute a size of their own.
        val width = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val content = contentChild
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        if (content != null && content.visibility != View.GONE) {
            content.measure(
                MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.AT_MOST),
                heightMeasureSpec,
            )
        }
        val height = if (content != null && content.visibility != View.GONE) {
            content.measuredHeight
        } else {
            getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        }
        setMeasuredDimension(width, height)

        installAnchors(width, height)

        val rowWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val rowHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === content) continue
            if (child.visibility == View.GONE) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(rowWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY),
            )
            (child as? WearRevealActionRowView)?.applyGeometry()
        }
    }

    /**
     * The `onSizeChanged` block (`:349-397`) in full: the component width, the revealing anchor, the
     * threshold, the revealed ratio and the anchor map. Re-run whenever anything it reads moved, which
     * upstream gets for free from recomposition.
     */
    private fun installAnchors(width: Int, height: Int) {
        val density = resources.displayMetrics.density
        val screenWidthPx = resources.configuration.screenWidthDp * density
        val direction = directionFactor()
        if (
            screenWidthPx == anchoredScreenWidthPx && width == anchoredWidth && height == anchoredHeight &&
            density == anchoredDensity && direction == anchoredDirection &&
            hasSecondaryAction == anchoredSecondary && hasPartiallyRevealedState == anchoredPartial
        ) {
            return
        }
        anchoredScreenWidthPx = screenWidthPx
        anchoredWidth = width
        anchoredHeight = height
        anchoredDensity = density
        anchoredDirection = direction
        anchoredSecondary = hasSecondaryAction
        anchoredPartial = hasPartiallyRevealedState

        val state = revealState
        val componentWidthPx = width.toFloat()
        val anchorWidthPx =
            (
                if (hasSecondaryAction) {
                    SwipeToRevealDefaults.DoubleActionAnchorWidth
                } else {
                    SwipeToRevealDefaults.SingleActionAnchorWidth
                }
                ).value * density
        val revealingAnchorPx =
            if (!hasSecondaryAction && !hasPartiallyRevealedState) {
                null
            } else {
                RevealMath.revealingAnchorPx(anchorWidthPx, screenWidthPx, componentWidthPx)
            }
        // `:356-382` — with no revealing anchor the threshold and the ratio keep their previous values,
        // because upstream only assigns them inside the `!= null` branch.
        if (revealingAnchorPx != null) {
            state.revealThreshold =
                RevealMath.revealThresholdPx(screenWidthPx, componentWidthPx, revealingAnchorPx)
            state.revealedRatio = RevealMath.calculateRevealedRatio(
                hasNoSecondaryAction = !hasSecondaryAction,
                anchorWidthPx = anchorWidthPx,
                screenWidthPx = screenWidthPx,
                componentWidthPx = componentWidthPx,
                revealingAnchorPx = revealingAnchorPx,
            )
        }
        state.componentWidthPx = componentWidthPx
        state.screenWidthPx = screenWidthPx
        // The Boolean `updateAnchors` reports — "reconcile the running animation" — is discarded, as
        // `:396` discards it: Hibari has no `AnchorChangeHandler` (see the state's KDoc, gap bullet).
        state.updateAnchors(
            RevealMath.anchorsFor(
                screenWidthPx = screenWidthPx,
                revealingAnchorPx = revealingAnchorPx,
                direction = direction,
                isBidirectional = revealDirection == RevealDirection.Bidirectional,
            ),
            density,
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val content = contentChild
        if (content != null && content.visibility != View.GONE) {
            content.layout(
                paddingLeft,
                paddingTop,
                paddingLeft + content.measuredWidth,
                paddingTop + content.measuredHeight,
            )
        }
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === content) continue
            if (child.visibility == View.GONE) continue
            child.layout(
                paddingLeft,
                paddingTop,
                paddingLeft + child.measuredWidth,
                paddingTop + child.measuredHeight,
            )
            (child as? WearRevealActionRowView)?.applyGeometry()
        }
        applyVerticalOffsets()
        applyVisualState()
    }

    /**
     * `calculateVerticalOffsetBasedOnScreenPosition` (`:575-582`, `:1713-1742`) — the revealed actions
     * are centred on the *visible* part of a tall item that runs off the window.
     *
     * Deviation: upstream compares `positionOnScreen()` (screen space) with `boundsInWindow()` (window
     * space); this port works entirely in window space, which is the same answer for the single
     * fullscreen window this module renders into, and is the only space a `View` can ask for.
     */
    private fun applyVerticalOffsets() {
        val root = rootView
        val boxLocation = IntArray(2)
        getLocationInWindow(boxLocation)
        val windowHeight = root.height
        rowsForEach { row ->
            row.verticalOffsetPx = RevealMath.calculateVerticalOffsetBasedOnScreenPosition(
                childHeight = row.measuredHeight,
                parentTopOnScreen = boxLocation[1],
                parentHeight = height,
                windowTop = 0f,
                windowBottom = windowHeight.toFloat(),
                windowCenterY = windowHeight / 2f,
                isPositioned = isAttachedToWindow && windowHeight > 0,
            )
        }
    }

    /** Upstream's `LocalLayoutDirection.current == LayoutDirection.Rtl` factor (`:262`, `:1073`). */
    internal fun directionFactor(): Int = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1

    // endregion

    // region applying the state to the children

    /** `canSwipeRight` (`:427`): only a bidirectional reveal ever shows actions on the left. */
    private val canSwipeRight: Boolean get() = revealDirection == RevealDirection.Bidirectional

    private var showingUndo = false
    private var undoTransitionStartElapsed = -1L
    private var isUndoTransitionRunning = false

    /** The `swipeCompleted` of `:449-451`. */
    private val swipeCompleted: Boolean
        get() = revealState.currentValue == RevealValue.RightRevealed ||
            revealState.currentValue == RevealValue.LeftRevealed

    /**
     * Push the state onto the children. Called from the drag, from the ticker, from [onLayout] and
     * whenever a property that changes the picture is retuned.
     */
    private fun applyVisualState() {
        val state = revealState
        if (
            revealDirection == RevealDirection.RightToLeft &&
            (state.currentValue == RevealValue.LeftRevealing || state.currentValue == RevealValue.LeftRevealed)
        ) {
            // `require(...)` (`:264-270`). Upstream throws out of composition; a draw pass is the only
            // place left to do it, and the message is upstream's.
            error(
                "RevealState value can't be LeftRevealing or LeftRevealed if reveal direction is " +
                    "RightToLeft."
            )
        }

        syncEffects()

        val now = SystemClock.elapsedRealtime()
        val absOffset = abs(state.offset)
        val swipingRight = state.offset * directionFactor() > 0f
        // `shouldDrawActions` (`:434-439`).
        val shouldDrawActions = absOffset > 0f && (canSwipeRight || !swipingRight)

        val hasUndo = undoRow?.hasVisibleSlotFor(state.lastActionType) == true
        val wantsUndo = swipeCompleted && hasUndo
        if (wantsUndo != showingUndo) {
            showingUndo = wantsUndo
            undoTransitionStartElapsed = now
            isUndoTransitionRunning = true
        }
        val undoAlpha: Float
        val actionsAlpha: Float
        if (undoTransitionStartElapsed < 0L) {
            // No transition has ever started: the actions arm is the resting one (`AnimatedContent`
            // with `targetState = false` composes only the actions).
            undoAlpha = 0f
            actionsAlpha = 1f
        } else {
            val elapsed = now - undoTransitionStartElapsed
            // `AnimatedContent`'s two transforms (`:459-467`, `:1676-1711`).
            if (showingUndo) {
                undoAlpha = RevealMath.undoEnterAlpha(elapsed)
                actionsAlpha = RevealMath.undoExitAlpha(elapsed)
                undoRow?.scaleFactor = RevealMath.undoEnterScale(elapsed)
            } else {
                undoAlpha = RevealMath.undoCancelledExitAlpha(elapsed)
                actionsAlpha = RevealMath.undoCancelledEnterAlpha(elapsed)
                undoRow?.scaleFactor = 1f
            }
            if (elapsed >= RevealMath.UNDO_TRANSITION_DURATION.toLong()) {
                isUndoTransitionRunning = false
            }
        }

        var rowsAnimating = false
        val actions = actionsRow
        if (actions != null) {
            setRowVisibility(actions, shouldDrawActions && actionsAlpha > 0f)
            rowsAnimating = actions.applyFrame(actionsAlpha)
        }
        val undo = undoRow
        if (undo != null) {
            setRowVisibility(undo, shouldDrawActions && undoAlpha > 0f)
            rowsAnimating = undo.applyFrame(undoAlpha) or rowsAnimating
        }

        // `Modifier.absoluteOffset { … }` on the content (`:631-637`): before the first measure there is
        // no offset to draw, so nothing moves.
        val content = contentChild
        if (content != null) {
            val translation = RevealMath.contentOffsetX(
                offsetPx = (state.currentOffset ?: 0f) * directionFactor(),
                canSwipeRight = canSwipeRight,
            )
            if (content.translationX != translation) content.translationX = translation
        }

        invalidate()
        if (
            isDragging || isSettling || state.isAnimationRunning ||
            isUndoTransitionRunning || rowsAnimating
        ) startTicking()
    }

    /**
     * `INVISIBLE` and not `GONE`: `shouldDrawActions` (`:434`) is upstream's *composition* gate, and a
     * `View` that stopped being measured would lose its slots' sizes and pop on the next frame.
     */
    private fun setRowVisibility(row: View, visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.INVISIBLE
        if (row.visibility != target) row.visibility = target
    }

    // endregion

    // region the effect hosts

    private var lastEffectValue: RevealValue = RevealValue.Covered
    private var lastEffectActionType: RevealActionType = RevealActionType.None
    private var lastObservedTarget: RevealValue = RevealValue.Covered
    private var effectJob: Job? = null

    /**
     * The two `LaunchedEffect`s of `:641-674`, run when the values they are keyed on change — the ticker
     * is the only thing here that notices a change. Like a `LaunchedEffect`, a new run cancels the old.
     */
    private fun syncEffects() {
        val state = revealState
        val current = state.currentValue
        val actionType = state.lastActionType
        if (current != lastEffectValue || actionType != lastEffectActionType) {
            lastEffectValue = current
            lastEffectActionType = actionType
            effectJob?.cancel()
            effectJob = animationScope.launch {
                if (current != RevealValue.Covered) {
                    state.resetLastState(state)
                }
                if (
                    (current == RevealValue.LeftRevealed || current == RevealValue.RightRevealed) &&
                    state.lastActionType == RevealActionType.None
                ) {
                    // Full swipe triggers the main action, but does not set the click type.
                    // Explicitly set the click type as main action when full swipe occurs. (`:650-653`)
                    state.lastActionType = RevealActionType.PrimaryAction
                    onSwipePrimaryAction()
                }
            }
        }
        val target = state.targetValue
        if (target != lastObservedTarget) {
            lastObservedTarget = target
            val isFullReveal =
                target == RevealValue.LeftRevealed || target == RevealValue.RightRevealed
            val isPartialReveal =
                target == RevealValue.LeftRevealing || target == RevealValue.RightRevealing
            if (isFullReveal) {
                performHapticFeedback()
            } else if (
                RevealMath.IS_DUAL_FLING_THRESHOLD_ENABLED &&
                isPartialReveal &&
                current == RevealValue.Covered &&
                abs(state.offset) < state.revealThreshold
            ) {
                if (!state.skipPartialHaptic) performHapticFeedback()
            }
            state.skipPartialHaptic = false
        }
    }

    /**
     * `performHapticFeedback` (`:1886-1897`) against the platform constant its
     * `GestureThresholdActivate` maps to. API 31 and up only; see the class KDoc for why nothing is
     * substituted below that.
     */
    private fun performHapticFeedback() {
        val now = System.currentTimeMillis()
        if (now <= revealState.lastHapticFeedbackTime + RevealMath.HAPTIC_DEBOUNCING_TIME) return
        revealState.lastHapticFeedbackTime = now
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // Use GestureThresholdActivate for both haptics, as it triggers HapticConstant#23 (`:1893-1895`).
        performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
    }

    // endregion

    // region touch

    private val viewConfiguration = ViewConfiguration.get(context)

    private val maxFlingVelocityPxPerSecond = viewConfiguration.scaledMaximumFlingVelocity.toFloat()

    private var activePointerId = InvalidPointerId
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var isDragging = false
    private var allowSwipe = true
    private var velocityTracker: VelocityTracker? = null

    /** `enabled` of `anchoredDraggable` (`:343-346`): not while revealed, and not when excluded. */
    private val gestureEnabled: Boolean
        get() = allowSwipe &&
            revealState.currentValue != RevealValue.LeftRevealed &&
            revealState.currentValue != RevealValue.RightRevealed

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginTracking(event)
                // `awaitFirstDown(requireUnconsumedEvent = false, PointerEventPass.Initial)` +
                // `gestureInclusion.ignoreGestureStart(...)` (`:327-339`).
                allowSwipe = !gestureInclusion.ignoreGestureStart(Offset(downX, downY), this)
                return false
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                velocityTracker?.addMovement(event)
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!gestureEnabled) return false
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return false
                val x = event.getX(index)
                val y = event.getY(index)
                if (isDragging) return true
                if (!shouldStartDrag(x, y)) return false
                beginDrag()
                dragTo(x)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endGesture()
                return false
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Reached when no child wanted the down, which is the case `anchoredDraggable` handles
                // (`:340-348`).
                if (!isDragging) beginTracking(event)
                allowSwipe = !gestureInclusion.ignoreGestureStart(Offset(downX, downY), this)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                velocityTracker?.addMovement(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!gestureEnabled) return true
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)
                if (!isDragging) {
                    if (shouldStartDrag(x, y)) {
                        beginDrag()
                        dragTo(x)
                    }
                } else {
                    dragTo(x)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = isDragging
                val velocity = if (wasDragging) trackedVelocity(event) else 0f
                endGesture()
                if (wasDragging) runSettle(velocity)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * The drag test: horizontal beats vertical, and past the (upstream-multiplied) slop — except that a
     * move while an animation is still running starts immediately (`startDragImmediately`,
     * `SwipeableV2.kt:145`). A vertical drag is never taken, which is the stand-in for "a scrolling child
     * consumed it first".
     */
    private fun shouldStartDrag(x: Float, y: Float): Boolean {
        val dx = abs(x - downX)
        if (dx <= abs(y - downY)) return false
        if (dx <= touchSlopPx && !revealState.isAnimationRunning) return false
        return true
    }

    private fun beginTracking(event: MotionEvent) {
        activePointerId = event.getPointerId(0)
        downX = event.getX(0)
        downY = event.getY(0)
        lastX = downX
        releaseTracking()
        velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
    }

    /** A drag takes the offset over from any running settle animation — upstream's mutex job. */
    private fun beginDrag() {
        isDragging = true
        settleJob?.cancel()
        settleJob = null
        isSettling = false
        startTicking()
    }

    /** `DragScope.dragBy(pixels) { state.dispatchRawDelta(pixels) }`, leftover dropped as upstream's is. */
    private fun dragTo(x: Float) {
        val delta = x - lastX
        lastX = x
        if (delta == 0f) return
        revealState.dispatchRawDelta(delta)
        applyVisualState()
    }

    /** px per second, the unit [RevealState.settle]'s velocity is in. */
    private fun trackedVelocity(event: MotionEvent): Float {
        val tracker = velocityTracker ?: return 0f
        tracker.addMovement(event)
        tracker.computeCurrentVelocity(MillisPerSecond, maxFlingVelocityPxPerSecond)
        return tracker.xVelocity
    }

    private fun endGesture() {
        isDragging = false
        activePointerId = InvalidPointerId
        downX = 0f
        downY = 0f
        lastX = 0f
        releaseTracking()
    }

    private fun releaseTracking() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    // endregion

    // region animation plumbing

    /**
     * The scope every animation and effect body runs in. `internal` because an action button's click
     * joins it, which is upstream's single `rememberCoroutineScope` (`:282`) that both the buttons and
     * the effects share (`:848`, `:1138`).
     */
    internal val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settleJob: Job? = null
    private var isSettling = false
    private var isTicking = false
    private val ticker = Ticker()

    private inner class Ticker : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            isTicking = false
            applyVisualState()
        }
    }

    private fun startTicking() {
        if (isTicking || !isAttachedToWindow) return
        isTicking = true
        Choreographer.getInstance().postFrameCallback(ticker)
    }

    private fun stopTicking() {
        if (!isTicking) return
        Choreographer.getInstance().removeFrameCallback(ticker)
        isTicking = false
    }

    /**
     * `onDragStopped = { velocity -> launch { state.settle(velocity) } }` — the fling behaviour
     * `anchoredDraggable` installs (`:347`, `:1018-1048`), collapsed into the call the state exposes.
     *
     * Guarded by [RevealState.hasAnchorForValue] because [RevealState.settle] reads `requireOffset()` and
     * the anchor set, neither of which exists before the first [onMeasure].
     */
    private fun runSettle(velocityPxPerSecond: Float) {
        val state = revealState
        if (!state.hasAnchorForValue(RevealValue.Covered)) return
        settleJob?.cancel()
        isSettling = true
        startTicking()
        settleJob = animationScope.launch {
            try {
                state.settle(velocityPxPerSecond)
            } finally {
                isSettling = false
                startTicking()
            }
        }
    }

    /**
     * Animate to [target] the way an action-button click does (`:1137-1158`), on this view's scope so a
     * new gesture interrupts it the same way it interrupts a settle.
     */
    internal fun runActionAnimation(target: RevealValue) {
        val state = revealState
        if (!state.hasAnchorForValue(target) && target != RevealValue.Covered) return
        settleJob?.cancel()
        isSettling = true
        startTicking()
        settleJob = animationScope.launch {
            try {
                state.animateTo(target)
            } finally {
                isSettling = false
                startTicking()
            }
        }
    }

    override fun onDetachedFromWindow() {
        stopTicking()
        settleJob?.cancel()
        settleJob = null
        effectJob?.cancel()
        effectJob = null
        isSettling = false
        endGesture()
        super.onDetachedFromWindow()
    }

    // endregion

    /**
     * `FrameLayout.LayoutParams` so a `BoxScope.gravity` written on a slot still lands on params that
     * have a `gravity` field (same reasoning as `WearSwipeToDismissView.LayoutParams`, `:860-882`).
     */
    class LayoutParams : FrameLayout.LayoutParams {

        constructor(width: Int, height: Int) : super(width, height)

        constructor(width: Int, height: Int, gravity: Int) : super(width, height, gravity)

        constructor(source: LayoutParams) : super(source)

        constructor(source: ViewGroup.LayoutParams) : super(source)

        constructor(source: MarginLayoutParams) : super(source)

        constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
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
    }
}

/**
 * A `tween` advanced by a frame ticker instead of a coroutine — the stand-in for
 * `animateFloatAsState(targetValue, tween(...))` (`material3/SwipeToReveal.kt:472-481`, `:519-552`).
 *
 * Every animated float in upstream's `SwipeToReveal` passes an explicit `tween`, so no spring behaviour is
 * lost by this shape; what is lost is velocity continuity, i.e. an interruption restarts from the current
 * value at zero velocity rather than carrying the old one.
 */
internal class RevealInterpolator(initial: Float) {

    var value: Float = initial
        private set

    private var startValue = initial
    private var targetValue = initial
    private var startTime = -1L
    private var durationMillis = 0L
    private var delayMillis = 0L
    private var easing: Easing = LinearEasing

    /** Retarget, the way `animateFloatAsState` reacts to a changed target value. */
    fun animateTo(
        target: Float,
        durationMillis: Int,
        delayMillis: Int,
        easing: Easing,
        now: Long,
    ) {
        if (target == targetValue) return
        startValue = value
        targetValue = target
        startTime = now
        this.durationMillis = durationMillis.toLong()
        this.delayMillis = delayMillis.toLong()
        this.easing = easing
    }

    /** Advance to [now]; true while an animation is still running, so the ticker keeps going. */
    fun advance(now: Long): Boolean {
        if (startTime < 0L) return false
        val elapsed = now - startTime
        value = if (durationMillis <= 0L) {
            targetValue
        } else {
            RevealMath.tweenFraction(elapsed, durationMillis, delayMillis, easing) *
                (targetValue - startValue) + startValue
        }
        if (elapsed >= durationMillis + delayMillis) {
            value = targetValue
            startTime = -1L
            return false
        }
        return true
    }
}

/**
 * The row of revealed actions — upstream's `Row(...)` inside the overlay `Box`
 * (`material3/SwipeToReveal.kt:555-624` for the actions, `:482-500` for the undo arm), plus that `Row`'s
 * `.layout { … }` modifier (`:559-583`), which is what gives the row its reveal-sized width.
 *
 * Children are the slot buttons, in emission order: [com.huanli233.hibari.wear.SwipeToReveal] emits
 * `secondaryAction, primaryAction` into the actions arm, and this view shows them right-aligned, which is
 * upstream's `Arrangement.Absolute.Right` over `Spacer(spacing) → secondary → Spacer(4dp) → primary`
 * (`:587-604`). When the content is being swiped to the right — only possible with
 * [RevealDirection.Bidirectional] — the placement mirrors, which is upstream's `else` arm (`:605-623`).
 *
 * [applyGeometry] is the Views form of that per-frame `measure`: the revealed width *is* the row's width,
 * so a frame can change the slots' measured sizes and nothing in the framework would notice. It is called
 * from [WearSwipeToRevealView.applyVisualState] through [applyFrame], and from [onLayout].
 *
 * The undo arm gets the whole row ([`:482-487`]'s `Row(fillMaxWidth(), Arrangement.Center)` with one
 * weight-1 slot) and hides whichever of its two slots the current [RevealActionType] does not select.
 */
class WearRevealActionRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    /** The state whose offset and value drive every width and alpha in this row. */
    var state: RevealState? = null
        set(value) {
            if (field === value) return
            field = value
            requestLayout()
            invalidate()
        }

    /** True for the undo arm of the `AnimatedContent` (`:470-500`), false for the actions arm. */
    var isUndoRow: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** Whether the component has a secondary action, which selects the icon fade fractions (`:1744-1756`). */
    var hasSecondaryAction: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            iconStartFadeInFraction = RevealMath.startFadeInFraction(value)
            iconEndFadeInFraction = RevealMath.endFadeInFraction(value)
            requestLayout()
        }

    /** The `actionContentSpacing` parameter (`:259`) — the gap between the content and the actions. */
    var actionContentSpacing: Dp = SwipeToRevealDefaults.ActionContentSpacing
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** The y shift computed by the box, upstream's `calculateVerticalOffsetBasedOnScreenPosition`. */
    var verticalOffsetPx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            applyGeometry()
        }

    /** The `scaleIn(initialScale = 1.2f)` of the undo arm's enter transition (`:1687-1696`). */
    var scaleFactor: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            pivotX = width / 2f
            pivotY = height / 2f
            scaleX = value
            scaleY = value
        }

    /** Start of this row's icons' fade-in window, from [RevealMath.startFadeInFraction] (`:1081`). */
    internal var iconStartFadeInFraction: Float = RevealMath.startFadeInFraction(false)
        private set

    /** End of that window (`:1082`, [RevealMath.endFadeInFraction]). */
    internal var iconEndFadeInFraction: Float = RevealMath.endFadeInFraction(false)
        private set

    private val secondaryWeight = RevealInterpolator(1f)
    private val secondaryAlpha = RevealInterpolator(1f)
    private val primaryAlpha = RevealInterpolator(1f)
    private val revealedContentAlpha = RevealInterpolator(1f)
    private val undoActionAlpha = RevealInterpolator(0f)

    private val slotSpacingPx: Float
        get() = SwipeToRevealDefaults.Padding.value * resources.displayMetrics.density

    private val contentSpacingPx: Float
        get() = if (actionContentSpacing.isUnspecified) {
            0f
        } else {
            actionContentSpacing.value * resources.displayMetrics.density
        }

    /**
     * Whether this row carries a slot that [RevealState.lastActionType] would show — upstream's
     * `hasUndoAction` (`:453-457`), the second half of the `AnimatedContent` target (`:460`). Answered
     * from the children because the component emits both undo slots and the choice is made at reveal time.
     */
    internal fun hasVisibleSlotFor(actionType: RevealActionType): Boolean {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val params = child.layoutParams as? LayoutParams ?: continue
            if (showsForLastActionType(params.showsWhenLastActionType, actionType)) return true
        }
        return false
    }

    /**
     * Upstream's `when (revealState.lastActionType) { SecondaryAction -> undoSecondary; else -> undoPrimary }`
     * (`:489-498`): anything that is not the secondary action gets the primary undo.
     */
    private fun showsForLastActionType(
        marker: RevealActionType,
        lastActionType: RevealActionType,
    ): Boolean =
        if (lastActionType == RevealActionType.SecondaryAction) {
            marker == RevealActionType.SecondaryAction
        } else {
            marker != RevealActionType.SecondaryAction
        }

    /**
     * Advance this row's tweens and re-place its slots. [extraAlpha] is the cross-fade factor the box is
     * running (`AnimatedContent`, `:459-467`), multiplied into the row's own alpha, because upstream nests
     * the two `graphicsLayer`s the same way.
     *
     * @return true while one of the row's tweens, slot visibilities or mirror state is still moving, so
     *   the box knows to schedule another frame after this one.
     */
    fun applyFrame(extraAlpha: Float): Boolean {
        val state = state ?: return false
        val now = SystemClock.elapsedRealtime()

        val absOffset = abs(state.offset)
        val swipeCompleted =
            state.currentValue == RevealValue.RightRevealed ||
                state.currentValue == RevealValue.LeftRevealed
        val lastActionIsSecondary = state.lastActionType == RevealActionType.SecondaryAction
        // `isWithinRevealOffset` (`:504-508`), upstream's `derivedStateOf` written as a comparison.
        val isWithinRevealOffset = absOffset <= state.revealThreshold
        val hideActions = !isWithinRevealOffset && lastActionIsSecondary
        val showSecondaryAction = isWithinRevealOffset || lastActionIsSecondary

        var animating: Boolean
        val rowAlpha: Float
        if (!isUndoRow) {
            secondaryWeight.animateTo(
                if (showSecondaryAction) 1f else 0f,
                RevealMath.QUICK_ANIMATION,
                0,
                // `tween(durationMillis = QUICK_ANIMATION)` with no easing argument (`:519-524`), i.e.
                // tween's own FastOutSlowIn default.
                FastOutSlowInEasing,
                now,
            )
            secondaryAlpha.animateTo(
                if (!showSecondaryAction || hideActions) 0f else 1f,
                RevealMath.QUICK_ANIMATION,
                0,
                LinearEasing,
                now,
            )
            primaryAlpha.animateTo(
                if (hideActions) 0f else 1f,
                RevealMath.FLASH_ANIMATION,
                0,
                LinearEasing,
                now,
            )
            revealedContentAlpha.animateTo(
                if (swipeCompleted) 0f else 1f,
                RevealMath.FLASH_ANIMATION,
                0,
                LinearEasing,
                now,
            )
            animating = secondaryWeight.advance(now) or
                secondaryAlpha.advance(now) or
                primaryAlpha.advance(now) or
                revealedContentAlpha.advance(now)
            rowAlpha = extraAlpha * revealedContentAlpha.value
            slotAlphaPrimary = primaryAlpha.value
            slotAlphaSecondary = secondaryAlpha.value
        } else {
            undoActionAlpha.animateTo(
                if (swipeCompleted) 1f else 0f,
                RevealMath.RAPID_ANIMATION,
                RevealMath.FLASH_ANIMATION,
                RevealMath.StandardInOut,
                now,
            )
            animating = undoActionAlpha.advance(now)
            rowAlpha = extraAlpha * undoActionAlpha.value
            slotAlphaPrimary = 1f
            slotAlphaSecondary = 1f
        }

        if (alpha != rowAlpha) alpha = rowAlpha

        // The row's width, upstream's `maxWidth` in the `.layout` lambda (`:562-573`).
        clipWidthPx = RevealMath.slotClipWidthPx(
            absOffsetPx = absOffset,
            componentWidthPx = state.componentWidthPx,
            revealThresholdPx = state.revealThreshold,
            hideActions = hideActions,
        )

        if (isUndoRow) {
            // Whichever undo slot the current action type does not select is dropped; upstream never
            // composes it (`:488-499`).
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val params = child.layoutParams as? LayoutParams ?: continue
                val target =
                    if (showsForLastActionType(params.showsWhenLastActionType, state.lastActionType)) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                if (child.visibility != target) {
                    child.visibility = target
                    animating = true
                }
            }
        }

        val swipingRight = state.offset * directionFactor() > 0f
        if (mirrored != swipingRight) {
            mirrored = swipingRight
            animating = true
        }

        applyGeometry()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            (child as? WearRevealActionButtonView)?.applyRevealFrame(
                absOffsetPx = absOffset,
                screenWidthPx = state.screenWidthPx,
                slotAlpha = slotAlphaFor(child),
                state = state,
                iconStartFadeInFraction = iconStartFadeInFraction,
                iconEndFadeInFraction = iconEndFadeInFraction,
            )
        }
        invalidate()
        return animating
    }

    private fun slotAlphaFor(child: View): Float {
        if (isUndoRow) return 1f
        val params = child.layoutParams as? LayoutParams ?: return 1f
        return if (params.slotType == RevealActionType.SecondaryAction) {
            slotAlphaSecondary
        } else {
            slotAlphaPrimary
        }
    }

    private var clipWidthPx: Int = 0
    private var effectiveWidth: Int = 0
    private var effectiveHeight: Int = 0
    private var slotAlphaPrimary: Float = 1f
    private var slotAlphaSecondary: Float = 1f
    private var mirrored: Boolean = false

    /** Upstream reads `LocalLayoutDirection` at `:262`; the box's factor is the same fact. */
    private fun directionFactor(): Int {
        val parent = parent
        if (parent is WearSwipeToRevealView) return parent.directionFactor()
        return if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
    }

    /** The animated weight of the secondary slot, `0f` for the undo arm (which has none). */
    private val secondaryWeightValue: Float get() = if (isUndoRow) 0f else secondaryWeight.value

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The box hands this row EXACTLY its own content box (`matchParentSize()`, `:444`); the row's
        // *drawn* width is the reveal clip, applied in [applyGeometry].
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            getDefaultSize(suggestedMinimumHeight, heightMeasureSpec),
        )
        applyGeometry()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        applyGeometry()
    }

    /**
     * Measure and place every slot for the current reveal width — the whole of upstream's
     * `.layout { measurable.measure(constraints.copy(maxWidth = …)) ; placeable.placeRelative(0, y) }`
     * (`:559-583`) plus the `Row`'s own arrangement, and the reason this row does not lean on the
     * framework's layout pass.
     */
    internal fun applyGeometry() {
        // During the box's own measure pass this row has a measured size but no laid-out size yet, and
        // the slots have to be measured in the same pass, so the proposed size stands in for the real
        // one until `layout` lands.
        val boxWidth = if (width > 0) width else measuredWidth
        val boxHeight = if (height > 0) height else measuredHeight
        if (boxWidth == 0 || boxHeight == 0 || childCount == 0) return
        effectiveWidth = boxWidth
        effectiveHeight = boxHeight
        val rowWidth = min(clipWidthPx.coerceAtLeast(0), boxWidth)
        val widths = computeSlotWidths(rowWidth)
        val heightSpec = MeasureSpec.makeMeasureSpec(
            (boxHeight - paddingTop - paddingBottom).coerceAtLeast(0),
            MeasureSpec.EXACTLY,
        )
        val visibleSlots = ArrayList<View>(2)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            visibleSlots.add(child)
            child.measure(
                MeasureSpec.makeMeasureSpec(widths.getOrElse(i) { 0 }.coerceAtLeast(0), MeasureSpec.EXACTLY),
                getChildMeasureSpec(heightSpec, 0, child.layoutParams.height),
            )
        }
        placeSlots(visibleSlots)
    }

    /**
     * The width each slot gets, mirroring Compose's `Row` with weighted children (`:587-624`): the
     * non-weighted children (the `Spacer`s) take their own size first and the weighted ones split what is
     * left in proportion to their weights — with a zero-weight secondary *removed*, which is upstream's
     * "weight cannot be 0 so remove the composable when weight becomes 0" (`:588-590`).
     *
     * The primary slot always has weight `1f`, upstream's `ActionSlot` default (`:1664`).
     */
    private fun computeSlotWidths(rowWidth: Int): IntArray {
        val widths = IntArray(childCount)
        if (rowWidth <= 0) return widths
        if (isUndoRow) {
            // `Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center)` with one weight-1
            // slot (`:482-487`): the undo button gets the whole row.
            for (i in 0 until childCount) widths[i] = rowWidth
            return widths
        }
        val hasSecondary = hasSecondarySlotVisible()
        val spacers = contentSpacingPx + if (hasSecondary) slotSpacingPx else 0f
        val available = (rowWidth - spacers).coerceAtLeast(0f)
        val primaryIndex = childCount - 1
        if (!hasSecondary) {
            widths[primaryIndex] = available.roundToInt()
            return widths
        }
        val weight = secondaryWeightValue
        val totalWeight = 1f + weight
        widths[primaryIndex - 1] = (available * (weight / totalWeight)).roundToInt()
        widths[primaryIndex] = (available - widths[primaryIndex - 1]).coerceAtLeast(0f).roundToInt()
        return widths
    }

    /** The secondary slot exists and its animated weight has not reached zero (`:591-593`). */
    private fun hasSecondarySlotVisible(): Boolean =
        childCount > 1 && secondaryWeightValue > 0f && getChildAt(0).visibility != View.GONE

    /** Place the visible slots, right-aligned for a left-ward swipe and left-aligned for a right-ward one. */
    private fun placeSlots(visibleSlots: List<View>) {
        if (visibleSlots.isEmpty()) return
        val rowW = if (width > 0) width else effectiveWidth
        val rowH = if (height > 0) height else effectiveHeight
        val contentSpacing = contentSpacingPx.roundToInt()
        val slotSpacing = slotSpacingPx.roundToInt()
        if (isUndoRow) {
            val child = visibleSlots.first()
            val childHeight = child.measuredHeight
            val top = ((rowH - childHeight) / 2 + verticalOffsetPx).coerceAtLeast(0)
            child.layout(0, top, child.measuredWidth, top + childHeight)
            return
        }
        val primary = visibleSlots.last()
        val secondary = if (visibleSlots.size > 1) visibleSlots.first() else null
        if (!mirrored) {
            // `Spacer(actionContentSpacing) → secondary → Spacer(Padding) → primary`, packed right
            // (`:587-604`): the primary's right edge sits one content spacing from the row's.
            var right = rowW - contentSpacing
            val primaryWidth = primary.measuredWidth
            val primaryHeight = primary.measuredHeight
            val primaryTop = ((rowH - primaryHeight) / 2 + verticalOffsetPx).coerceAtLeast(0)
            primary.layout(right - primaryWidth, primaryTop, right, primaryTop + primaryHeight)
            if (secondary != null) {
                right -= slotSpacing
                val secondaryWidth = secondary.measuredWidth
                val secondaryHeight = secondary.measuredHeight
                val secondaryTop = ((rowH - secondaryHeight) / 2 + verticalOffsetPx).coerceAtLeast(0)
                secondary.layout(right - secondaryWidth, secondaryTop, right, secondaryTop + secondaryHeight)
            }
        } else {
            // `primary → Spacer(Padding) → secondary → Spacer(actionContentSpacing)`, packed left
            // (`:605-623`).
            var left = contentSpacing
            val primaryWidth = primary.measuredWidth
            val primaryHeight = primary.measuredHeight
            val primaryTop = ((rowH - primaryHeight) / 2 + verticalOffsetPx).coerceAtLeast(0)
            primary.layout(left, primaryTop, left + primaryWidth, primaryTop + primaryHeight)
            left += primaryWidth
            if (secondary != null) {
                left += slotSpacing
                val secondaryWidth = secondary.measuredWidth
                val secondaryHeight = secondary.measuredHeight
                val secondaryTop = ((rowH - secondaryHeight) / 2 + verticalOffsetPx).coerceAtLeast(0)
                secondary.layout(left, secondaryTop, left + secondaryWidth, secondaryTop + secondaryHeight)
            }
        }
    }

    /**
     * The row's own `LayoutParams`, carrying what this row needs to know about each slot: which action it
     * is, and — for the undo arm — which `lastActionType` selects it. Written by the builders in
     * `RevealActionButtons.kt` through `thenLayoutAttribute`, which is why it has to be a real
     * `LayoutParams` subclass.
     */
    class LayoutParams : MarginLayoutParams {

        /** Whether this slot is the secondary action, which decides its weight and its gap. */
        var slotType: RevealActionType = RevealActionType.PrimaryAction

        /**
         * For the undo arm: which `lastActionType` shows this slot (`:489-498`). Ignored by the actions
         * arm.
         */
        var showsWhenLastActionType: RevealActionType = RevealActionType.PrimaryAction

        constructor(width: Int, height: Int) : super(width, height)

        constructor(source: ViewGroup.LayoutParams) : super(source)

        constructor(source: MarginLayoutParams) : super(source)

        constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams): LayoutParams = LayoutParams(p)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams): Boolean = p is LayoutParams
}

/**
 * One revealed action button — upstream's `ActionButton` (`material3/SwipeToReveal.kt:1062-1196`), i.e. a
 * `Button(shape = CircleShape, modifier = modifier.padding(...).fillMaxWidth().graphicsLayer { alpha = … })`
 * (`:1122-1167`) whose content is a centred `Row` of an optional icon and an optional text
 * (`:1168-1194`).
 *
 * In Views the container and that content row are the same [LinearLayout]: the circle is the
 * [com.huanli233.hibari.wear.ContainerSpec] background the builder puts on this view, and the children are
 * the slots, addressed by index — child 0 is the icon when [hasIconSlot] and the last child is the text
 * when [hasTextSlot], which is exactly the emission order of the builder.
 *
 * Per frame, [applyRevealFrame] does what upstream does with three modifiers and one scope:
 *  - the button's fade in from the swiped distance (`:1125-1135`, [RevealMath.buttonAlpha]), times the
 *    `ActionSlot` opacity above it (`:1661-1674`);
 *  - the icon wrapper's fade in (`:1229-1239`, [RevealMath.iconAlpha]) across the fractions chosen by
 *    [RevealMath.startFadeInFraction] / [RevealMath.endFadeInFraction];
 *  - the primary action's `AnimatedVisibility` of the text (`:1178-1190`), whose four spring specs
 *    (`fadeIn(spring(StiffnessLow))`, `expandHorizontally(spring(StiffnessMedium))`,
 *    `fadeOut(spring(StiffnessHigh))`, `shrinkHorizontally(spring(StiffnessMedium))`) run as two `animate()`
 *    coroutines, damping left at `spring()`'s default as upstream leaves it.
 *
 * Deviation: `expandHorizontally` grows the text by *clipping* it here, applied to the child's canvas in
 * [drawChild]. Hibari's `AnimatedVisibilityScopeImpl.targetSize` has no writer — a documented core gap —
 * so a real `expandHorizontally()` modifier would measure the text as zero-width forever. The clip is what
 * that modifier reduces to once `clip = true`, and the text keeps its natural measured width while it
 * grows, so it never reflows.
 */
class WearRevealActionButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        // Qualified because Kotlin does not resolve a superclass's Java statics by simple name.
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    /** The state the click drives; upstream closes over it at `:1137-1159`. */
    var revealState: RevealState? = null

    /** Which of the three action kinds this button is (upstream's `revealActionType`, `:1066`). */
    var actionType: RevealActionType = RevealActionType.PrimaryAction

    /**
     * `shouldSetLastActionType` (`:1071`): `true` for the primary action (`:885`), `hasSecondaryUndo` for
     * the secondary (`:922`), and the default `false` for the undo arm (`:953-961`).
     */
    var shouldSetLastActionType: Boolean = false

    /** Whether an icon slot was emitted (upstream's `action.icon?.let { … }`, `:1173`). */
    var hasIconSlot: Boolean = false

    /** Whether a text slot was emitted (`:1176-1193`). */
    var hasTextSlot: Boolean = false

    /** The caller's `onClick` (`SwipeToRevealAction.onClick`, `:1249`), run even if the animation breaks. */
    var onActionClick: () -> Unit = {}

    /**
     * The click, `:1137-1159`: the undo animates back to [RevealValue.Covered]; an action animates to
     * whichever Revealed value lies in the direction of travel (`:1145-1151`, whose `direction` is the
     * layout-direction factor of `:1073`) — and only when [shouldSetLastActionType], which is how upstream
     * keeps a secondary click from stamping the state when there is nothing to undo (`:922`). The caller's
     * [onActionClick] runs in `finally`, i.e. *after* the settle, which is upstream's shape and the reason
     * the callback feels deliberate rather than instant.
     */
    fun performActionClick() {
        val state = revealState ?: return
        val direction = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
        val undoType = actionType == RevealActionType.UndoAction
        val target = when {
            undoType -> RevealValue.Covered
            shouldSetLastActionType ->
                if (state.offset * direction > 0) RevealValue.LeftRevealed else RevealValue.RightRevealed
            else -> null
        }
        // A click joins the box's animation scope so a new drag interrupts it, which is what upstream's
        // shared `rememberCoroutineScope` gives it (`:282`, `:848`); `clickScope` only covers a button
        // that was placed outside a reveal box.
        val scope = revealHost?.animationScope ?: clickScope
        scope.launch {
            try {
                if (target == null) return@launch
                if (!undoType) state.lastActionType = actionType
                state.animateTo(target)
            } finally {
                // Execute onClick even if the animation gets interrupted (`:1155`).
                onActionClick()
            }
        }
    }

    /** This button's box, two levels up: row → box (`runActionAnimation`, `animationScope`). */
    private val revealHost: WearSwipeToRevealView?
        get() = (parent as? WearRevealActionRowView)?.parent as? WearSwipeToRevealView

    private val clickScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Drive the fades for one frame. [slotAlpha] is the `ActionSlot` opacity above this button
     * (`:1661-1674`), multiplied with the offset-driven fade because upstream nests the two layers.
     */
    internal fun applyRevealFrame(
        absOffsetPx: Float,
        screenWidthPx: Float,
        slotAlpha: Float,
        state: RevealState,
        iconStartFadeInFraction: Float,
        iconEndFadeInFraction: Float,
    ) {
        val buttonAlpha = slotAlpha * RevealMath.buttonAlpha(absOffsetPx, screenWidthPx)
        if (alpha != buttonAlpha) alpha = buttonAlpha
        if (hasIconSlot && childCount > 0) {
            val icon = getChildAt(0)
            val iconAlpha = RevealMath.iconAlpha(
                absOffsetPx = absOffsetPx,
                screenWidthPx = screenWidthPx,
                iconStartFadeInFraction = iconStartFadeInFraction,
                iconEndFadeInFraction = iconEndFadeInFraction,
            )
            if (icon.alpha != iconAlpha) icon.alpha = iconAlpha
        }
        if (hasTextSlot && usesAnimatedText) {
            val visible = state.targetValue == RevealValue.RightRevealed ||
                state.targetValue == RevealValue.LeftRevealed
            if (visible != textVisible) startTextAnimation(visible)
        }
    }

    private var textVisible = false
    private var textAlphaFraction = 0f
    private var textWidthFraction = 0f
    private var textAlphaJob: Job? = null
    private var textWidthJob: Job? = null
    private val textClipBounds = Rect()

    /** The text child, i.e. the last one when [hasTextSlot] is set. */
    private val textChild: View?
        get() = if (hasTextSlot) getChildAt(childCount - 1) else null

    /**
     * The four springs of `AnimatedVisibility` (`:1182-1187`) as two `animate()` calls: alpha with
     * `StiffnessLow` going in and `StiffnessHigh` coming out, horizontal growth with `StiffnessMedium`
     * both ways.
     */
    private fun startTextAnimation(visible: Boolean) {
        textVisible = visible
        val alphaSpec = if (visible) {
            SpringSpec<Float>(stiffness = Spring.StiffnessLow)
        } else {
            SpringSpec<Float>(stiffness = Spring.StiffnessHigh)
        }
        val widthSpec = SpringSpec<Float>(stiffness = Spring.StiffnessMedium)
        val target = if (visible) 1f else 0f
        textAlphaJob?.cancel()
        textWidthJob?.cancel()
        textAlphaJob = clickScope.launch {
            animate(textAlphaFraction, target, 0f, alphaSpec) { value, _ ->
                textAlphaFraction = value
                textChild?.alpha = value
                invalidate()
            }
        }
        textWidthJob = clickScope.launch {
            animate(textWidthFraction, target, 0f, widthSpec) { value, _ ->
                textWidthFraction = value
                invalidate()
            }
        }
    }

    /**
     * Only the primary action's text sits inside an `AnimatedVisibility` (`:1176-1193`); the undo arm's
     * text is drawn directly (`RevealActionType.UndoAction -> ActionText(...)`, `:1192`), so it is never
     * clipped or faded by this view.
     */
    private val usesAnimatedText: Boolean get() = actionType == RevealActionType.PrimaryAction

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child !== textChild || !usesAnimatedText) return super.drawChild(canvas, child, drawingTime)
        val fraction = textWidthFraction
        if (fraction >= 1f) return super.drawChild(canvas, child, drawingTime)
        // `AnimatedVisibility` does not draw a child whose expansion has not started (`:1178`).
        if (fraction <= 0f) return false
        textClipBounds.set(
            child.left,
            child.top,
            child.left + (child.width * fraction).roundToInt(),
            child.bottom,
        )
        val save = canvas.save()
        canvas.clipRect(textClipBounds)
        val drawn = super.drawChild(canvas, child, drawingTime)
        canvas.restoreToCount(save)
        return drawn
    }

    override fun onDetachedFromWindow() {
        textAlphaJob?.cancel()
        textWidthJob?.cancel()
        super.onDetachedFromWindow()
    }
}
