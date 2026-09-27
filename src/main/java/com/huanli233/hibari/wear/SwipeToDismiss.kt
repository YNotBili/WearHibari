package com.huanli233.hibari.wear

import com.huanli233.hibari.animation.AnimationSpec
import com.huanli233.hibari.animation.LinearOutSlowInEasing
import com.huanli233.hibari.animation.SpringSpec
import com.huanli233.hibari.animation.TweenSpec
import com.huanli233.hibari.animation.animate
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.getValue
import com.huanli233.hibari.runtime.mutableStateOf
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.runtime.setValue
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.util.lerp
import kotlin.math.abs
import kotlin.math.max

/**
 * States used as targets for the anchor points for swipe-to-dismiss.
 *
 * Ported from `androidx.wear.compose.foundation.SwipeToDismissValue`
 * (`BasicSwipeToDismissBox.kt:514-520`). The machine has two states because the box has two anchors,
 * `Default to 0f` and `Dismissed to screenWidthPx` (`BasicSwipeToDismissBox.kt:120-121`): it is
 * `Default` before and during a swipe, and only reaches `Dismissed` when a settle animation
 * *finishes* on the far anchor — see [SwipeToDismissBoxState.targetValue] versus
 * [SwipeToDismissBoxState.currentValue], and [SwipeToDismissMath.settledValue].
 */
public enum class SwipeToDismissValue {
    /** The state of the SwipeToDismissBox before the swipe started. */
    Default,

    /** The state of the SwipeToDismissBox after the swipe passes the swipe-to-dismiss threshold. */
    Dismissed,
}

/**
 * Keys used to persistent state in the swipe-to-dismiss box.
 *
 * Ported from `androidx.wear.compose.foundation.SwipeToDismissKeys`
 * (`BasicSwipeToDismissBox.kt:497-511`).
 *
 * Gap: upstream passes these to Compose's `key(...)` (`BasicSwipeToDismissBox.kt:173`) so that
 * saveable state follows a screen when it moves between the background and the foreground slot.
 * Hibari has no `key()` and no saveable-state registry, so these constants are accepted by the API
 * and otherwise inert; keep passing them so a later slot-identity port has something to hang on.
 */
public enum class SwipeToDismissKeys {
    /**
     * The default background key to identify the content displayed by the content block when
     * isBackground == true.
     */
    Background,

    /**
     * The default content key to identify the content displayed by the content block when
     * isBackground == false.
     */
    Content,
}

/** Contains defaults for the swipe-to-dismiss box. */
public object SwipeToDismissBoxDefaults {
    /**
     * The default animation that will be used to animate to a new state after the swipe gesture.
     *
     * `BasicSwipeToDismissBox.kt:488`, which forwards `SwipeableV2Defaults.AnimationSpec`
     * (`SwipeableV2.kt:666`) — a default [SpringSpec], *not* the 200 ms tween used by
     * [rememberSwipeToDismissBoxState]. Upstream really is inconsistent here
     * (`BasicSwipeToDismissBox.kt:332` versus `:474`); the difference is preserved verbatim.
     */
    public val AnimationSpec: SpringSpec<Float> = SpringSpec<Float>()

    /**
     * The default width of the area which might trigger a swipe with the edge-swipe modifier.
     * `BasicSwipeToDismissBox.kt:493`.
     */
    public val EdgeWidth: Dp = 30.dp
}

/**
 * The arithmetic of swipe-to-dismiss: which anchor a swipe settles at, and what a given dismissal
 * progress looks like.
 *
 * Every formula below is transcribed from upstream, but upstream keeps them out of reach —
 * `computeTarget` is a private method of the internal `SwipeableV2State` (`SwipeableV2.kt:509-549`),
 * the constants are file-private (`BasicSwipeToDismissBox.kt:637-642`) and the scale/scrim/translation
 * maths is inline inside a `graphicsLayer`/`drawWithContent` lambda
 * (`BasicSwipeToDismissBox.kt:184-240`). Collecting them in one public object is the only new
 * structure here; no number, comparison operator or clamp is changed.
 *
 * No function in this object takes an Android type, so they are pinned directly by
 * `SwipeToDismissTest` on the JVM.
 */
public object SwipeToDismissMath {

    /** `BasicSwipeToDismissBox.kt:637`, upstream private; public here so the test can name it. */
    public const val SWIPE_THRESHOLD: Float = 0.5f

    /** `BasicSwipeToDismissBox.kt:638`. */
    public const val SCALE_MAX: Float = 1f

    /** `BasicSwipeToDismissBox.kt:639`. */
    public const val SCALE_MIN: Float = 0.7f

    /** `BasicSwipeToDismissBox.kt:640`. */
    public const val MAX_CONTENT_SCRIM_ALPHA: Float = 0.3f

    /** `BasicSwipeToDismissBox.kt:641`. */
    public const val MAX_BACKGROUND_SCRIM_ALPHA: Float = 0.5f

    /**
     * The velocity a fling has to exceed to dismiss without reaching [SWIPE_THRESHOLD], in dp per
     * second. `SwipeableV2.kt:672` (`SwipeableV2Defaults.VelocityThreshold = 800.dp`), reached through
     * the untouched default of `SwipeableV2State.velocityThreshold` (`SwipeableV2.kt:230`).
     */
    public const val VELOCITY_THRESHOLD_DP: Float = 800f

    /**
     * The [SWIPE_THRESHOLD] as a fraction of the distance between the two anchors, i.e. upstream's
     * `positionalThreshold = fractionalPositionalThreshold(SWIPE_THRESHOLD)`
     * (`BasicSwipeToDismissBox.kt:462`, `SwipeableV2.kt:657-660`).
     */
    public fun positionalThresholdPx(distancePx: Float): Float =
        abs(distancePx * SWIPE_THRESHOLD)

    /** [VELOCITY_THRESHOLD_DP] converted to px per second; `Dp.toPx()` is `value * density`. */
    public fun velocityThresholdPx(density: Float): Float = VELOCITY_THRESHOLD_DP * density

    /**
     * How far the foreground has been swiped, as the `0f..1f` progress every visual below is driven
     * by. Measured against the *screen* width, not the box width: upstream's anchors are
     * `Default to 0f, Dismissed to maxWidthPx` with `maxWidthPx = screenWidthDp.dp.toPx()`
     * (`BasicSwipeToDismissBox.kt:118-121, 147-156`). An uninitialised (NaN) offset and a zero width
     * both report `0f`.
     */
    public fun dismissalProgress(offsetPx: Float, maxWidthPx: Float): Float =
        if (offsetPx.isNaN() || maxWidthPx == 0f) {
            0f
        } else {
            (offsetPx / maxWidthPx).coerceIn(0f, 1f)
        }

    /**
     * `progress > 0`, which is what gates the background slot in upstream (`BasicSwipeToDismissBox.kt:157`,
     * used at `:174`).
     */
    public fun isSwiping(progress: Float): Boolean = progress > 0f

    /**
     * The uniform scale of the foreground content: `lerp(SCALE_MAX, SCALE_MIN, progress)` clamped to
     * `SCALE_MIN..SCALE_MAX` (`BasicSwipeToDismissBox.kt:185-187`). Interpolated *against progress*,
     * from full size at rest to [SCALE_MIN] at full dismissal, and applied to both axes
     * (`scaleX = scaleY = scale`, `:205-206`).
     */
    public fun contentScale(progress: Float): Float =
        lerp(SCALE_MAX, SCALE_MIN, progress).coerceIn(SCALE_MIN, SCALE_MAX)

    /**
     * The alpha of the scrim painted *over the foreground content* — `progress / 2f` capped at
     * [MAX_CONTENT_SCRIM_ALPHA] (`BasicSwipeToDismissBox.kt:210-216`). The cap binds from
     * `progress = 0.6` onwards.
     */
    public fun contentScrimAlpha(progress: Float): Float =
        (progress / 2f).coerceAtMost(MAX_CONTENT_SCRIM_ALPHA)

    /**
     * The alpha of the scrim painted *over the background slot*, which fades out as the foreground
     * lifts away: `MAX_BACKGROUND_SCRIM_ALPHA * (1 - progress)`
     * (`BasicSwipeToDismissBox.kt:231-238`). The same colour also sits behind the foreground at full
     * alpha as the `.background(backgroundScrimColor)` of that layer (`:229`).
     */
    public fun backgroundScrimAlpha(progress: Float): Float =
        MAX_BACKGROUND_SCRIM_ALPHA * (1 - progress)

    /**
     * The half-width the foreground is pushed right by the squeeze, `max(0f, (1f - scale) * maxWidthPx / 2f)`
     * (`BasicSwipeToDismissBox.kt:188-189`).
     */
    public fun squeezeOffsetPx(progress: Float, maxWidthPx: Float): Float =
        max(0f, (1f - contentScale(progress)) * maxWidthPx / 2f)

    /**
     * The foreground's `translationX` (`BasicSwipeToDismissBox.kt:191-204`). While squeezing it is
     * [squeezeOffsetPx]; once the dismissal is animating to `Dismissed` it slides from that offset to
     * the full width, weighted by how far past `progress = 0.7f` the gesture is.
     */
    public fun contentTranslationXPx(
        progress: Float,
        maxWidthPx: Float,
        squeezeMode: Boolean,
    ): Float {
        val squeezeOffset = squeezeOffsetPx(progress, maxWidthPx)
        return if (squeezeMode) {
            squeezeOffset
        } else {
            lerp(squeezeOffset, maxWidthPx, max(0f, progress - 0.7f) / 0.3f)
        }
    }

    /**
     * Squeeze mode after `state.isAnimationRunning` changes: `LaunchedEffect(state.isAnimationRunning)
     * { if (state.targetValue == Dismissed) squeezeMode = false }` (`BasicSwipeToDismissBox.kt:159-163`).
     * The key only decides *when* this runs, so it is not a parameter; the body reads the target value
     * and leaves the flag alone unless it is heading for `Dismissed`.
     */
    public fun squeezeModeAfterAnimationChange(
        squeezeMode: Boolean,
        targetValue: SwipeToDismissValue,
    ): Boolean = if (targetValue == SwipeToDismissValue.Dismissed) false else squeezeMode

    /**
     * Squeeze mode after `state.targetValue` changes: `LaunchedEffect(state.targetValue) { if
     * (!squeezeMode && targetValue == Default) squeezeMode = true }`
     * (`BasicSwipeToDismissBox.kt:164-168`).
     */
    public fun squeezeModeAfterTargetChange(
        squeezeMode: Boolean,
        targetValue: SwipeToDismissValue,
    ): Boolean =
        if (!squeezeMode && targetValue == SwipeToDismissValue.Default) true else squeezeMode

    /**
     * Find the closest anchor taking into account the velocity — a transcription of
     * `SwipeableV2State.computeTarget` (`SwipeableV2.kt:509-549`) for the two-anchor set the box installs
     * (`BasicSwipeToDismissBox.kt:120-121`), with upstream's structure intact:
     *
     * 1. sitting exactly on the current anchor, or an anchor missing → stay;
     * 2. a fling at or beyond [velocityThresholdPx] in the direction of travel wins outright, and the
     *    positional threshold is never consulted (the *precedence* rule, `:518` and `:533`; note `>=`
     *    and `<=`, so the threshold itself counts as exceeded);
     * 3. otherwise the anchor is taken once the offset passes [positionalThresholdPx] measured from
     *    the current anchor in the direction of travel, with upstream's `offset < 0` special cases for
     *    offsets outside the anchor range kept verbatim (`:525-529`, `:540-546`).
     *
     * @param velocityPxPerSecond the pointer velocity in px per second, as `Modifier.draggable`'s
     *   `onDragStopped` reports it (`SwipeableV2.kt:146`). Pass `0f` to ask what the *current* target
     *   is mid-drag, which is exactly what upstream's `targetValue` does (`SwipeableV2.kt:269-277`).
     * @param density the display density, needed only for [velocityThresholdPx].
     */
    public fun settleTarget(
        offsetPx: Float,
        currentValue: SwipeToDismissValue,
        velocityPxPerSecond: Float,
        anchors: Map<SwipeToDismissValue, Float>,
        density: Float,
    ): SwipeToDismissValue {
        val currentAnchor = anchors[currentValue] ?: return currentValue
        val velocityThresholdInPx = velocityThresholdPx(density)
        return if (currentAnchor == offsetPx) {
            currentValue
        } else if (currentAnchor < offsetPx) {
            // Swiping from lower to upper (positive).
            if (velocityPxPerSecond >= velocityThresholdInPx) {
                closestAnchor(anchors, offsetPx, searchUpwards = true)
            } else {
                val upper = closestAnchor(anchors, offsetPx, searchUpwards = true)
                val distance = abs(anchors.getValue(upper) - currentAnchor)
                val relativeThreshold = positionalThresholdPx(distance)
                val absoluteThreshold = abs(currentAnchor + relativeThreshold)
                if (offsetPx < 0) {
                    if (abs(offsetPx) > absoluteThreshold) currentValue else upper
                } else {
                    if (offsetPx < absoluteThreshold) currentValue else upper
                }
            }
        } else {
            // Swiping from upper to lower (negative).
            if (velocityPxPerSecond <= -velocityThresholdInPx) {
                closestAnchor(anchors, offsetPx, searchUpwards = false)
            } else {
                val lower = closestAnchor(anchors, offsetPx, searchUpwards = false)
                val distance = abs(currentAnchor - anchors.getValue(lower))
                val relativeThreshold = positionalThresholdPx(distance)
                val absoluteThreshold = abs(currentAnchor - relativeThreshold)
                if (offsetPx < 0) {
                    // For negative offsets, larger absolute thresholds are closer to lower anchors
                    // than smaller ones.
                    if (abs(offsetPx) < absoluteThreshold) currentValue else lower
                } else {
                    if (offsetPx > absoluteThreshold) currentValue else lower
                }
            }
        }
    }

    /**
     * The value whose anchor is the closest one in the searched direction, i.e. `Map.closestAnchor`
     * (`SwipeableV2.kt:768-775`): anchors behind [offsetPx] cost `Float.POSITIVE_INFINITY`, and a tie
     * — including "every anchor is behind" — keeps the first key in map order, which is
     * `Default, Dismissed` for `mapOf` at `BasicSwipeToDismissBox.kt:120`.
     */
    public fun closestAnchor(
        anchors: Map<SwipeToDismissValue, Float>,
        offsetPx: Float,
        searchUpwards: Boolean,
    ): SwipeToDismissValue {
        require(anchors.isNotEmpty()) {
            "The anchors were empty when trying to find the closest anchor"
        }
        var best: SwipeToDismissValue? = null
        var bestCost = Float.POSITIVE_INFINITY
        for ((value, anchor) in anchors) {
            val delta = if (searchUpwards) anchor - offsetPx else offsetPx - anchor
            val cost = if (delta < 0) Float.POSITIVE_INFINITY else delta
            if (best == null || cost < bestCost) {
                best = value
                bestCost = cost
            }
        }
        return best ?: anchors.keys.first()
    }

    /**
     * The value the state ends up at once a settle animation has stopped, i.e. the anchor within
     * `0.5f` px of the end offset, or `null` when the animation stopped off-anchor and the previous
     * value is kept (`SwipeableV2.kt:420-425`).
     */
    public fun settledValue(
        anchors: Map<SwipeToDismissValue, Float>,
        endOffsetPx: Float,
    ): SwipeToDismissValue? =
        anchors.entries.firstOrNull { (_, anchorOffset) -> abs(anchorOffset - endOffsetPx) < 0.5f }?.key

    /**
     * Edge-swipe bookkeeping for the content that owns the viewport: `Modifier.edgeSwipeToDismiss`'s
     * pointer loop (`BasicSwipeToDismissBox.kt:585-609`). All three inputs are in px; [previousX] is
     * only read in the `EdgeClickedWaitingForDirection` arm, mirroring upstream's
     * `change.position.x < change.previousPosition.x`.
     *
     * Deviation: upstream's state enum is `internal` (`BasicSwipeToDismissBox.kt:619-635`) and stays
     * that way, so this function is `internal` too and only the view pass in this module can call it.
     */
    internal fun nextEdgeSwipeState(
        current: EdgeSwipeState,
        x: Float,
        previousX: Float,
        edgeWidthPx: Float,
        changedToUp: Boolean,
    ): EdgeSwipeState {
        val afterDirection = when (current) {
            EdgeSwipeState.SwipeToDismissInProgress,
            EdgeSwipeState.WaitingForTouch,
            ->
                if (x < edgeWidthPx) {
                    EdgeSwipeState.EdgeClickedWaitingForDirection
                } else {
                    EdgeSwipeState.SwipingToPage
                }

            EdgeSwipeState.EdgeClickedWaitingForDirection ->
                if (x < previousX) EdgeSwipeState.SwipingToPage else EdgeSwipeState.SwipingToDismiss

            else -> current // Do nothing
        }
        // When finger is up - reset to WaitingForTouch, or to SwipeToDismissInProgress if the
        // gesture was a swipe towards the dismiss.
        return if (changedToUp) {
            if (afterDirection == EdgeSwipeState.SwipingToDismiss) {
                EdgeSwipeState.SwipeToDismissInProgress
            } else {
                EdgeSwipeState.WaitingForTouch
            }
        } else {
            afterDirection
        }
    }
}

/**
 * An enum which represents a current state of swipe action. Ported from the `internal` enum of the
 * same name (`BasicSwipeToDismissBox.kt:619-635`); see
 * [SwipeToDismissMath.nextEdgeSwipeState] for the transitions.
 */
internal enum class EdgeSwipeState {
    // Waiting for touch, edge was not touched before.
    WaitingForTouch,

    // Edge was touched, now waiting for the second touch to determine whether we swipe left or
    // right.
    EdgeClickedWaitingForDirection,

    // Direction was determined, swiping to dismiss.
    SwipingToDismiss,

    // Direction was determined, all gestures are handled by the page itself.
    SwipingToPage,

    // Swipe was finished, used to handle fling.
    SwipeToDismissInProgress,
}

/**
 * State for the swipe-to-dismiss box.
 *
 * Ported from `androidx.wear.compose.foundation.SwipeToDismissBoxState`
 * (`BasicSwipeToDismissBox.kt:329-464`). Upstream delegates every mutable bit to an internal
 * `SwipeableV2State` (`SwipeableV2.kt:224-603`) that also serves other components; that class is not
 * ported, so its offset bookkeeping (`dispatchRawDelta`, `settle`, `animateTo`, `snap`, the anchors,
 * the density) is folded into this one and kept behaviourally identical. Consequences worth naming:
 *
 * - No `@Stable`/`@RememberInComposition` annotation exists in Hibari, so the constructor contract is
 *   prose only: build one per screen and keep it across tunations via [rememberSwipeToDismissBoxState].
 * - `SwipeableV2State`'s mutable members are `internal` to Compose upstream; here they are `public`
 *   because the view pass *is* the caller that `Modifier.swipeableV2` was. Nothing was added to the
 *   surface, and no knob upstream does not have was invented.
 * - `InternalMutatorMutex`, which `SwipeableV2State` uses to serialise swipes (`SwipeableV2.kt:234`),
 *   is `internal` to `hibari-animation` and cannot be reached, so `snapTo`/`animateTo`/`settle` do not
 *   serialise against each other; a Views input pipeline delivers touch and animation callbacks on the
 *   main thread one at a time, which is where that job goes instead.
 * - `nestedScrollDispatcher` is left at upstream's `null` (`BasicSwipeToDismissBox.kt:457-463` never
 *   passes one), so dropping it is faithful. `edgeNestedScrollConnection`
 *   (`BasicSwipeToDismissBox.kt:378-455`) is the one part with no host at all — gap, not ported.
 * - Upstream recomputes `progress`/`isSwiping`/`squeezeMode` through `derivedStateOf` and `SideEffect`
 *   (`BasicSwipeToDismissBox.kt:119-168`). There is no composition to run those in, so they are plain
 *   getters here ([progress], [isSwiping]) and plain functions
 *   ([SwipeToDismissMath.squeezeModeAfterAnimationChange]), and the anchors that `SideEffect` pushed
 *   into the state must be installed explicitly via [updateAnchors].
 * - The animation driver is the `suspend` pair [animateTo]/[settle]; upstream reaches for
 *   `animateTo`/`Animatable` only inside a coroutine the composition owns, so a view pass calls these
 *   from a scope carrying Hibari's frame clock and reads [offset]/[progress] each frame.
 * - Gap for the view pass: the `onDismissed` overload of the box is nothing but
 *   `LaunchedEffect(state.currentValue) { if (currentValue == Dismissed) { snapTo(Default);
 *   onDismissed() } }` (`BasicSwipeToDismissBox.kt:305-310`). There is no effect host here, so whoever
 *   owns the box watches [currentValue] and runs those two statements in that order — the snap lands
 *   *before* the callback, which is why a re-entered screen never shows a dismissed frame.
 *
 * @param animationSpec the default animation that will be used to animate to a new state.
 * @param confirmStateChange callback invoked to confirm or veto a pending state change.
 */
public class SwipeToDismissBoxState(
    private val animationSpec: AnimationSpec<Float> = SwipeToDismissBoxDefaults.AnimationSpec,
    private val confirmStateChange: (SwipeToDismissValue) -> Boolean = { true },
) {
    /** `SwipeableV2State.anchors` (`SwipeableV2.kt:348`). */
    private var anchors: Map<SwipeToDismissValue, Float> by
        mutableStateOf(emptyMap<SwipeToDismissValue, Float>())

    private var offsetState: Float? by mutableStateOf<Float?>(null)

    private var currentValueState: SwipeToDismissValue by
        mutableStateOf(SwipeToDismissValue.Default)

    private var animationTarget: SwipeToDismissValue? by mutableStateOf<SwipeToDismissValue?>(null)

    private var lastVelocityState: Float by mutableStateOf(0f)

    /** `SwipeableV2State.density` (`SwipeableV2.kt:350`), px per dp. */
    private var densityPxPerDp: Float = Float.NaN

    /**
     * The current value of the state.
     *
     * Before and during a swipe, corresponds to [SwipeToDismissValue.Default], then switches to
     * [SwipeToDismissValue.Dismissed] if the swipe has been completed.
     */
    public val currentValue: SwipeToDismissValue get() = currentValueState

    /**
     * The target value of the state: where the offset would animate to if the gesture ended right
     * now, with no velocity — upstream's `computeTarget(offset, currentValue, velocity = 0f)`
     * (`SwipeableV2.kt:269-277`). While an animation is running it is that animation's target.
     */
    public val targetValue: SwipeToDismissValue
        get() = animationTarget ?: run {
            val currentOffset = offsetState
            if (currentOffset != null) {
                SwipeToDismissMath.settleTarget(
                    offsetPx = currentOffset,
                    currentValue = currentValue,
                    velocityPxPerSecond = 0f,
                    anchors = anchors,
                    density = requireDensity(),
                )
            } else {
                currentValue
            }
        }

    /**
     * The current offset, or [Float.NaN] if it has not been initialized yet. The offset shows how far
     * the foreground content was swiped from its original position.
     */
    public val offset: Float get() = offsetState ?: Float.NaN

    /** Whether the state is currently animating. */
    public val isAnimationRunning: Boolean get() = animationTarget != null

    /**
     * The velocity of the last known animation; reset to `0f` when an animation completes
     * successfully (`SwipeableV2.kt:331-332, 416`).
     */
    public val lastVelocity: Float get() = lastVelocityState

    /**
     * The minimum offset this state can reach: the smallest anchor, or
     * [Float.NEGATIVE_INFINITY] while the anchors are not installed yet (`SwipeableV2.kt:338`).
     */
    public val minOffset: Float
        get() = anchors.minOfOrNull { (_, anchor) -> anchor } ?: Float.NEGATIVE_INFINITY

    /** The largest anchor, or [Float.POSITIVE_INFINITY] while unset (`SwipeableV2.kt:344`). */
    public val maxOffset: Float
        get() = anchors.maxOfOrNull { (_, anchor) -> anchor } ?: Float.POSITIVE_INFINITY

    /** The `Dismissed` anchor, which is the screen width in px (`BasicSwipeToDismissBox.kt:121`). */
    public val maxWidthPx: Float get() = anchors[SwipeToDismissValue.Dismissed] ?: 0f

    /**
     * How far the foreground has been dismissed, `offset / maxWidthPx` clamped, i.e. the `progress`
     * the whole visual treatment is driven by (`BasicSwipeToDismissBox.kt:147-156`). Feed it to
     * [SwipeToDismissMath.contentScale], [SwipeToDismissMath.contentScrimAlpha] and
     * [SwipeToDismissMath.backgroundScrimAlpha].
     */
    public val progress: Float
        get() = SwipeToDismissMath.dismissalProgress(offset, maxWidthPx)

    /**
     * `progress > 0f`, which is what makes the background slot visible
     * (`BasicSwipeToDismissBox.kt:157`, read at `:174`).
     */
    public val isSwiping: Boolean get() = SwipeToDismissMath.isSwiping(progress)

    /** Whether the [value] has an anchor associated with it (`SwipeableV2.kt:375`). */
    public fun hasAnchorForValue(value: SwipeToDismissValue): Boolean = anchors.containsKey(value)

    /**
     * Require the current offset.
     *
     * @throws IllegalStateException if the offset has not been initialized yet.
     */
    public fun requireOffset(): Float =
        checkNotNull(offsetState) {
            "The offset was read before being initialized. Is the box measured yet?"
        }

    /**
     * Install the anchors and the density, i.e. upstream's `SideEffect { state.density = ...;
     * state.updateAnchors(mapOf(Default to 0f, Dismissed to maxWidthPx)) }`
     * (`BasicSwipeToDismissBox.kt:119-124`). With no composition there is no SideEffect phase, so the
     * view pass calls this from its measure/layout pass with the map upstream builds —
     * `mapOf(SwipeToDismissValue.Default to 0f, SwipeToDismissValue.Dismissed to screenWidthPx)`.
     *
     * Returns whether the caller has to reconcile an in-progress animation, which is upstream's
     * `updateAnchors` contract (`SwipeableV2.kt:360-372`); the first call snaps to the initial value's
     * anchor and reports `false`, matching upstream's `trySnapTo` succeeding uncontended.
     */
    public fun updateAnchors(anchors: Map<SwipeToDismissValue, Float>, density: Float): Boolean {
        val previousAnchorsEmpty = this.anchors.isEmpty()
        this.anchors = anchors
        this.densityPxPerDp = density
        val initialValueHasAnchor =
            if (previousAnchorsEmpty) {
                val hasAnchor = anchors.containsKey(currentValue)
                if (hasAnchor) snap(currentValue)
                hasAnchor
            } else {
                true
            }
        return !initialValueHasAnchor || !previousAnchorsEmpty
    }

    /**
     * Swipe by [delta], coerce it into the anchor bounds and record it (`SwipeableV2.kt:469-499`).
     * Call this from the drag listener with the pointer delta in px.
     *
     * @return the delta left over, i.e. the part the box could not consume.
     */
    public fun dispatchRawDelta(delta: Float): Float {
        val currentDragPosition = offsetState ?: 0f
        val clamped = (currentDragPosition + delta).coerceIn(minOffset, maxOffset)
        val deltaToConsume = clamped - currentDragPosition
        if (abs(deltaToConsume) >= 0) {
            offsetState = ((offsetState ?: 0f) + deltaToConsume).coerceIn(minOffset, maxOffset)
        }
        return delta - deltaToConsume
    }

    /**
     * Set the state without any animation.
     *
     * @param targetValue the new value to set [currentValue] to.
     */
    public suspend fun snapTo(targetValue: SwipeToDismissValue) {
        snap(targetValue)
    }

    /**
     * Animate to [targetValue] (`SwipeableV2.kt:400-430`). If that value has no anchor, [currentValue]
     * is updated without moving the offset.
     *
     * @param targetValue the target value of the animation.
     * @param velocity the velocity the animation starts with, [lastVelocity] by default.
     */
    public suspend fun animateTo(
        targetValue: SwipeToDismissValue,
        velocity: Float = lastVelocity,
    ) {
        val targetOffset = anchors[targetValue]
        if (targetOffset != null) {
            try {
                animationTarget = targetValue
                animate(offsetState ?: 0f, targetOffset, velocity, animationSpec) { value, v ->
                    // Our onDrag coerces the value within the bounds, but an animation may overshoot;
                    // upstream allows the overshoot on purpose (`SwipeableV2.kt:408-411`).
                    offsetState = value
                    lastVelocityState = v
                }
                lastVelocityState = 0f
            } finally {
                animationTarget = null
                val endOffset = requireOffset()
                val endState = SwipeToDismissMath.settledValue(anchors, endOffset)
                currentValueState = endState ?: currentValue
            }
        } else {
            currentValueState = targetValue
        }
    }

    /**
     * Find the closest anchor taking into account the velocity and settle at it with an animation
     * (`SwipeableV2.kt:435-462`). Call this when the drag/fling ends. A change vetoed by
     * `confirmStateChange` rolls back to the value the gesture started from, as upstream does.
     */
    public suspend fun settle(velocity: Float) {
        val previousValue = currentValue
        val targetValue =
            SwipeToDismissMath.settleTarget(
                offsetPx = requireOffset(),
                currentValue = previousValue,
                velocityPxPerSecond = velocity,
                anchors = anchors,
                density = requireDensity(),
            )
        if (confirmStateChange(targetValue)) {
            animateTo(targetValue, velocity)
        } else {
            animateTo(previousValue, velocity)
        }
    }

    private fun snap(targetValue: SwipeToDismissValue) {
        val targetOffset = anchors[targetValue]
        if (targetOffset != null) {
            dispatchRawDelta(targetOffset - (offsetState ?: 0f))
            currentValueState = targetValue
            animationTarget = null
        } else {
            currentValueState = targetValue
        }
    }

    private fun requireDensity(): Float {
        check(!densityPxPerDp.isNaN()) {
            "SwipeToDismissBoxState did not have a density attached. Did the view pass call " +
                "updateAnchors?"
        }
        return densityPxPerDp
    }
}

/**
 * `SWIPE_TO_DISMISS_BOX_ANIMATION_SPEC` (`BasicSwipeToDismissBox.kt:642`); upstream keeps it
 * file-private, and it is private here too, so it carries a Hibari-safe name instead of the upstream
 * SCREAMING one.
 */
private val SwipeToDismissAnimationSpec: TweenSpec<Float> =
    TweenSpec<Float>(200, 0, LinearOutSlowInEasing)

/**
 * Create a [SwipeToDismissBoxState] and remember it across tunations.
 * (`BasicSwipeToDismissBox.kt:472-480`.)
 *
 * @param animationSpec the default animation used to animate to a new state. Note that upstream's
 *   default here is the 200 ms `LinearOutSlowIn` tween (`:642`), *not*
 *   [SwipeToDismissBoxDefaults.AnimationSpec] that the constructor defaults to; both are kept.
 * @param confirmStateChange callback to confirm or veto a pending state change.
 */
@Tunable
public fun rememberSwipeToDismissBoxState(
    animationSpec: AnimationSpec<Float> = SwipeToDismissAnimationSpec,
    confirmStateChange: (SwipeToDismissValue) -> Boolean = { true },
): SwipeToDismissBoxState = remember(animationSpec, confirmStateChange) {
    SwipeToDismissBoxState(animationSpec, confirmStateChange)
}
