/*
 * Port of Google's Wear Compose SwipeToReveal onto `android.view.View`.
 *
 * Two upstream files feed this one:
 *  - `androidx/wear/compose/material3/SwipeToReveal.kt` (1973 lines) — the current, non-deprecated
 *    component, its `RevealState`/`RevealValue`/`RevealDirection`/`RevealActionType`,
 *    `SwipeToRevealDefaults`, `SwipeToRevealScope` and the fling/threshold maths. This is the API
 *    shape ported here.
 *  - `androidx/wear/compose/foundation/SwipeToReveal.kt` (1002 lines) — the deprecated
 *    `ExperimentalWearFoundationApi` predecessor. It contributes `createRevealAnchors` (`:240-260`),
 *    `SwipeToRevealDefaults.RevealingRatio`/`PositionalThreshold`/`AnimationSpec`/`Padding`
 *    (`:805-861`) and the public `RevealActionType`/`lastActionType` (`:196-219`, `:296`), none of
 *    which material3 publishes any more. Where the two disagree, material3 wins and the difference is
 *    written down at the member.
 *
 * Header deviations from both, all of them gaps rather than choices:
 *
 * 1. NO POSITIONAL-THRESHOLD FLING MACHINERY. Upstream's `RevealState` is a thin shell over
 *    `AnchoredDraggableState` (`material3/SwipeToReveal.kt:1510`), and the settle physics live in
 *    `SwipeToRevealDefaults.flingBehavior` → `anchoredDraggableFlingBehavior` →
 *    `snapFlingBehavior(...)` (`:1018-1048`, `:1762-1828`), i.e. in
 *    `androidx.compose.foundation.gestures.snapping`, which is not vendored in the reference tree and
 *    has no Views counterpart. What *is* vendored and verifiable is the target-selection maths it
 *    calls: `DraggableAnchors.computeTarget` (`:1832-1883`, upstream's own copy of
 *    compose-foundation's private function) and `calculateSnapOffset` (`:1816-1827`). Those two are
 *    ported verbatim as [RevealMath.computeTarget], and the fling behaviour itself becomes the
 *    suspend [RevealState.settle] the view calls when a drag ends — the same collapse
 *    `SwipeableV2State.settle` already performs in `SwipeToDismiss.kt:626-641`.
 * 2. TWO DEFAULTS THAT CANNOT BE READ. `AnchoredDraggableDefaults.SnapAnimationSpec` and
 *    `.PositionalThreshold` (used at `:1024` and `:1032`) are compose-foundation symbols that are not
 *    in this reference tree, so their literals cannot be verified. Rather than invent a number, this
 *    port substitutes the *verifiable* upstream pair from the foundation file — the 200 ms
 *    `FastOutSlowIn` tween (`foundation/SwipeToReveal.kt:807-808`) and `totalDistance * 0.5f`
 *    (`:828-830`) — both of which are what that older, published component shipped. They sit behind
 *    [SwipeToRevealDefaults.AnimationSpec] and
 *    [SwipeToRevealDefaults.PositionalThreshold], so once the compose-foundation literals are
 *    verified this is a two-line change, not a hunt. Everything else keeps upstream's numbers
 *    exactly.
 * 3. NO MUTEX. `RevealState.drag` (`:1492-1508`) takes a `MutatePriority`; `MutatePriority` and
 *    `InternalMutatorMutex` are `internal` to `hibari-animation` (`InternalMutatorMutex.kt:34`) and
 *    unreachable, exactly as recorded for the sibling component at `SwipeToDismiss.kt:408-411`. The
 *    parameter is dropped, the coercion it performed (offset into `[minPosition, maxPosition]`) is
 *    kept in [SwipeToRevealDragScope.dragTo], and serialisation is the view's, which delivers touch
 *    and animation callbacks on the main thread one at a time.
 * 4. NO SEMANTICS, NO HYPERLINK-STYLE SCROLL AXIS. `Modifier.semantics { horizontalScrollAxisRange =
 *    … }` (`:398-425`) exists upstream only so `AndroidComposeView` can answer `canScroll` for the
 *    system's `ScrollDismissLayout`; there is no such question here, and no semantics layer at all
 *    (same omission as `Button.kt`, `Card.kt`, `WearSwipeToDismissView.kt:134-137`).
 *    `performHapticFeedback` (`:1886-1897`, `HAPTIC_DEBOUNCING_TIME`, `skipPartialHaptic`) is ported
 *    against the platform `View.performHapticFeedback` in the view file, because Hibari has no
 *    `LocalHapticFeedback` (already recorded at `WEAR_PORT_CONTRACT.md:120-127` and
 *    `Stepper.kt` header note 7).
 * 5. `LocalTextConfiguration` does not exist here, so `ActionText`'s `TextOverflow.Ellipsis` +
 *    `maxLines = 1` (`:1202-1211`) is applied per call on the button's own text view — see
 *    `RevealActionButtons.kt`.
 * 6. `CustomTouchSlopProvider(newTouchSlop = touchSlop * CustomTouchSlopMultiplier)` (`:317-319`,
 *    multiplier `1.20f` at `:1924`) is ported and used: the component resolves
 *    [currentTouchSlop], multiplies it by [SwipeToRevealDefaults.CustomTouchSlopMultiplier], wraps its
 *    own subtree in [CustomTouchSlopProvider] and hands the same number to the box, because a `View`
 *    has no ambient to consult at gesture time.
 */

package com.huanli233.hibari.wear

import android.view.View
import com.huanli233.hibari.animation.AnimationSpec
import com.huanli233.hibari.animation.CubicBezierEasing
import com.huanli233.hibari.animation.Easing
import com.huanli233.hibari.animation.FastOutSlowInEasing
import com.huanli233.hibari.animation.LinearEasing
import com.huanli233.hibari.animation.TweenSpec
import com.huanli233.hibari.animation.animate
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.Row
import com.huanli233.hibari.foundation.attributes.matchParentSize
import com.huanli233.hibari.foundation.attributes.matchParentWidth
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.getValue
import com.huanli233.hibari.runtime.mutableStateOf
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.runtime.setValue
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Offset
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.view.WearRevealActionRowView
import com.huanli233.hibari.wear.view.WearSwipeToRevealView
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Different values that determine the state of the [SwipeToReveal] composable, reflected in
 * [RevealState.currentValue]. [RevealValue.Covered] is the default state where none of the actions
 * are revealed yet.
 *
 * Direction is not localised: the default is [RevealDirection.RightToLeft], and
 * [RevealValue.RightRevealing]/[RevealValue.RightRevealed] mean the actions came in from the right.
 * With [RevealDirection.Bidirectional] the left pair is used as well.
 *
 * Ported from `androidx.wear.compose.material3.RevealValue` (`material3/SwipeToReveal.kt:1289-1337`),
 * including the `require(value in 0..15)` guard (`:1291-1293`) and the five ordinals: the numbers are
 * *not* the deprecated foundation ones (`-2..2`, `foundation/SwipeToReveal.kt:116-147`), so
 * `LeftRevealed` is `0` here and `-2` there. A caller migrating from the foundation enum by raw int
 * would flip sides, which is why the guard is kept verbatim.
 */
@JvmInline
public value class RevealValue internal constructor(internal val value: Int) {
    init {
        require(value in 0..15) { "Invalid RevealValue value: $value" }
    }

    public companion object {
        /**
         * The value which represents the state in which the whole revealable content is fully
         * revealed, and they are displayed on the left side of the screen. This also represents the
         * state in which one of the actions has been triggered/performed.
         *
         * Only used when the swipe direction is [RevealDirection.Bidirectional] and the user swipes
         * from the left side of the screen.
         */
        public val LeftRevealed: RevealValue = RevealValue(0)

        /**
         * The value which represents the state in which all the actions are revealed and the top
         * content is not being swiped, displayed on the left side of the screen; no action has been
         * triggered yet.
         *
         * Only used when the swipe direction is [RevealDirection.Bidirectional] and the user swipes
         * from the left side of the screen.
         */
        public val LeftRevealing: RevealValue = RevealValue(1)

        /**
         * The default first value which generally represents the state where the revealable actions
         * have not been revealed yet. In this state, none of the actions have been triggered or
         * performed yet.
         */
        public val Covered: RevealValue = RevealValue(2)

        /**
         * The value which represents the state in which all the actions are revealed and the top
         * content is not being swiped. In this state, none of the actions have been triggered or
         * performed yet, and they are displayed on the right side of the screen.
         */
        public val RightRevealing: RevealValue = RevealValue(3)

        /**
         * The value which represents the state in which the whole revealable content is fully
         * revealed, and the actions are revealed on the right side of the screen. This also
         * represents the state in which one of the actions has been triggered/performed.
         */
        public val RightRevealed: RevealValue = RevealValue(4)
    }

    override fun toString(): String = when (this) {
        LeftRevealed -> "LeftRevealed"
        LeftRevealing -> "LeftRevealing"
        Covered -> "Covered"
        RightRevealing -> "RightRevealing"
        RightRevealed -> "RightRevealed"
        else -> "RevealValue($value)"
    }
}

/**
 * Different values [SwipeToReveal] can reveal the actions from.
 *
 * Not localised, with the default being [RevealDirection.RightToLeft] to prevent conflict with the
 * system-wide swipe-to-dismiss gesture, so it is strongly advised to respect the default.
 *
 * Ported from `androidx.wear.compose.material3.RevealDirection`
 * (`material3/SwipeToReveal.kt:1346-1365`). Upstream's *foundation* file spells the second case
 * `Both` (`foundation/SwipeToReveal.kt:165-183`) and the material3 file `Bidirectional`; they are the
 * same option of the same switch, and one port cannot carry both names, so the current
 * (`Bidirectional`) one wins.
 */
@JvmInline
public value class RevealDirection internal constructor(internal val value: Int) {
    public companion object {
        /**
         * The default value which allows the user to swipe right to left to reveal or execute the
         * actions. It's strongly advised to respect the default behavior to avoid conflict with the
         * swipe-to-dismiss gesture.
         */
        public val RightToLeft: RevealDirection = RevealDirection(0)

        /**
         * The value which allows the user to swipe in either direction to reveal or execute the
         * actions. This should not be used if the component is used in an activity as the gesture
         * might conflict with the swipe-to-dismiss gesture and could be confusing for the users.
         * This is only supported for rare cases where the current screen does not support swipe to
         * dismiss.
         */
        public val Bidirectional: RevealDirection = RevealDirection(1)
    }

    override fun toString(): String = if (value == 1) "Bidirectional" else "RightToLeft"
}

/**
 * Different values which can trigger the state change from one [RevealValue] to another. These are
 * not set by themselves and need to be set appropriately with [RevealState.snapTo] and
 * [RevealState.animateTo].
 *
 * Ported from `androidx.wear.compose.material3.RevealActionType`
 * (`material3/SwipeToReveal.kt:1584-1612`), whose four ordinals and `require(value in 0..15)` are
 * kept; note the deprecated foundation enum numbers `None` as `-1`
 * (`foundation/SwipeToReveal.kt:217`) where material3 numbers it `3` (`:1610`).
 *
 * Deviation: material3 declares this `internal` (`:1585`) and hides [RevealState.lastActionType] with
 * it, while the foundation file publishes both (`:196-219`, `:296`). It is public here for the
 * foundation reason — the action buttons and the reveal view live in another file and have to read
 * and write it — so this port follows the foundation's published surface with material3's numbering.
 */
@JvmInline
public value class RevealActionType internal constructor(internal val value: Int) {
    init {
        require(value in 0..15) { "Invalid RevealActionType value: $value" }
    }

    public companion object {
        /**
         * Represents the primary action composable of [SwipeToReveal]. This corresponds to the
         * mandatory `primaryAction` parameter of [SwipeToReveal].
         */
        public val PrimaryAction: RevealActionType = RevealActionType(0)

        /**
         * Represents the secondary action composable of [SwipeToReveal]. This corresponds to the
         * optional `secondaryAction` composable of [SwipeToReveal].
         */
        public val SecondaryAction: RevealActionType = RevealActionType(1)

        /**
         * Represents the undo action composable of [SwipeToReveal]. This corresponds to the
         * `undoAction` composable of [SwipeToReveal] which is shown once an action is performed.
         */
        public val UndoAction: RevealActionType = RevealActionType(2)

        /** Default value when none of the above are applicable. */
        public val None: RevealActionType = RevealActionType(3)
    }

    override fun toString(): String = when (value) {
        0 -> "PrimaryAction"
        1 -> "SecondaryAction"
        2 -> "UndoAction"
        else -> "None"
    }
}

/**
 * Creates the required anchors to which the top content can be swiped, to reveal the actions. Each
 * value should be in the range `[0..1]`, where 0 represents right most end and 1 represents the full
 * width of the top content starting from right and ending on left.
 *
 * Ported from `androidx.wear.compose.foundation.createRevealAnchors`
 * (`foundation/SwipeToReveal.kt:240-260`) — the map, the sign flip and the iteration order are
 * upstream's (`LeftRevealed, LeftRevealing, Covered, RightRevealing, RightRevealed` for
 * bidirectional, because [RevealMath.computeTarget]'s "tie keeps the first key" rule reads map order,
 * and [RevealMath.nearestAnchor]'s stationary-target case walks it too).
 *
 * These are *fractions*. Upstream multiplies them by the swipeable width inside `swipeAnchors`
 * (`foundation/SwipeToReveal.kt:581-587`), which is where the sign flip
 * (`-state.swipeAnchors[value]!! * swipeableWidth`) happens; material3 does not use this function at
 * all — it builds pixel anchors in `onSizeChanged` (`material3/SwipeToReveal.kt:383-395`, ported as
 * [RevealMath.anchorsFor]). A state built from these fractions therefore needs
 * [RevealState.updateAnchors] with the products, which is what the view does.
 *
 * @param coveredAnchor Anchor for the [RevealValue.Covered] value
 * @param revealingAnchor Anchor for the [RevealValue.LeftRevealing] or [RevealValue.RightRevealing]
 *   value
 * @param revealedAnchor Anchor for the [RevealValue.LeftRevealed] or [RevealValue.RightRevealed]
 *   value
 * @param revealDirection The direction in which the content can be swiped. It's strongly advised to
 *   keep the default [RevealDirection.RightToLeft] in order to preserve compatibility with the
 *   system wide swipe to dismiss gesture.
 */
public fun createRevealAnchors(
    coveredAnchor: Float = 0f,
    revealingAnchor: Float = SwipeToRevealDefaults.RevealingRatio,
    revealedAnchor: Float = 1f,
    revealDirection: RevealDirection = RevealDirection.RightToLeft,
): Map<RevealValue, Float> {
    if (revealDirection == RevealDirection.Bidirectional) {
        return mapOf(
            RevealValue.LeftRevealed to -revealedAnchor,
            RevealValue.LeftRevealing to -revealingAnchor,
            RevealValue.Covered to coveredAnchor,
            RevealValue.RightRevealing to revealingAnchor,
            RevealValue.RightRevealed to revealedAnchor,
        )
    }
    return mapOf(
        RevealValue.Covered to coveredAnchor,
        RevealValue.RightRevealing to revealingAnchor,
        RevealValue.RightRevealed to revealedAnchor,
    )
}

/**
 * The two `GestureInclusion` implementations of the reveal, against this module's single
 * [GestureInclusion] port (`GestureInclusion.kt`, the shared home upstream also has —
 * `material3/SwipeToReveal.kt:114` *imports* the foundation type rather than re-declaring it).
 *
 * Bodies are upstream's, translated only in how the two geometry facts are read:
 * `layoutCoordinates.localToScreen(offset)` becomes [gestureInclusionScreenOffset] and
 * `findRootCoordinates().size.width` becomes [gestureInclusionRootWidthPx].
 */
private class RevealDefaultGestureInclusion(
    private val revealState: RevealState,
    private val edgeZoneFraction: Float,
) : GestureInclusion {
    override fun ignoreGestureStart(offset: Offset, view: View): Boolean {
        val screenOffset = gestureInclusionScreenOffset(offset, view)
        val screenWidth = gestureInclusionRootWidthPx(view)
        return revealState.currentValue == RevealValue.Covered &&
            screenOffset.x <= screenWidth * edgeZoneFraction
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RevealDefaultGestureInclusion

        if (edgeZoneFraction != other.edgeZoneFraction) return false
        if (revealState != other.revealState) return false

        return true
    }

    override fun hashCode(): Int {
        var result = edgeZoneFraction.hashCode()
        result = 31 * result + revealState.hashCode()
        return result
    }
}

/** The `BidirectionalGestureInclusion` of `material3/SwipeToReveal.kt:1645-1659`: never ignore. */
private object RevealBidirectionalGestureInclusion : GestureInclusion {
    override fun ignoreGestureStart(offset: Offset, view: View): Boolean = false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return javaClass == other?.javaClass
    }

    override fun hashCode(): Int = javaClass.hashCode()
}

/**
 * The arithmetic of the reveal: which anchor a swipe settles at, what each slot looks like at a given
 * offset, and how the undo cross-fade is timed.
 *
 * Every formula is transcribed from upstream and every constant carries the line it came from. The
 * object exists because upstream's copies are unreachable: `computeTarget` is a private function in
 * the same file (`material3/SwipeToReveal.kt:1832-1883`), `fadeInFraction`-style fades are inline in
 * `graphicsLayer` lambdas (`:1124-1135`, `:1229-1239`), and the animation constants are
 * file-privates (`:1927-1967`). Collecting them is the only new structure here; no number, comparison
 * operator or clamp is changed.
 *
 * No function takes an Android type, so the whole object is pinned from the JVM the way
 * `SwipeToDismissMath` is.
 */
public object RevealMath {

    /** `FULL_SWIPE_THRESHOLD_FRACTION` (`material3/SwipeToReveal.kt:1967`). */
    public const val FULL_SWIPE_THRESHOLD_FRACTION: Float = 0.75f

    /** `VelocityNearThreshold` (`:1916`), in dp: the speed that may advance one anchor. */
    public const val VELOCITY_NEAR_THRESHOLD_DP: Float = 200f

    /** `VelocityRevealedThreshold` (`:1922`), in dp: the speed that jumps to the far end. */
    public const val VELOCITY_REVEALED_THRESHOLD_DP: Float = 800f

    /** The `800.dp` literal of the flag-off branch (`:1859`). */
    public const val LEGACY_VELOCITY_THRESHOLD_DP: Float = 800f

    /**
     * `WearComposeMaterial3Flags.isSwipeToRevealDualFlingThresholdEnabled` (`:60`). The flag object's
     * own KDoc states the flags are "always on in the published artifact" (`:WearComposeMaterial3Flags.kt:19-22`),
     * so this port hard-codes the on-path but keeps the off-path below transcribed, because
     * `computeTarget` and [revealThresholdPx] both branch on it.
     */
    public const val IS_DUAL_FLING_THRESHOLD_ENABLED: Boolean = true

    /** `BUTTON_VISIBLE_THRESHOLD_AS_SCREEN_WIDTH_PERCENTAGE` (`:1948`). */
    public const val BUTTON_VISIBLE_FRACTION: Float = 0.06f

    /** `BUTTON_FADE_IN_END_THRESHOLD_AS_SCREEN_WIDTH_PERCENTAGE` (`:1951`). */
    public const val BUTTON_FADE_IN_END_FRACTION: Float = 0.12f

    /** `SINGLE_ICON_VISIBLE_THRESHOLD_AS_SCREEN_WIDTH_PERCENTAGE` (`:1954`). */
    public const val SINGLE_ICON_VISIBLE_FRACTION: Float = 0.15f

    /** `DOUBLE_ICON_VISIBLE_THRESHOLD_AS_SCREEN_WIDTH_PERCENTAGE` (`:1957`). */
    public const val DOUBLE_ICON_VISIBLE_FRACTION: Float = 0.30f

    /** `SINGLE_ICON_FADE_IN_END_THRESHOLD_AS_SCREEN_WIDTH_PERCENTAGE` (`:1960`). */
    public const val SINGLE_ICON_FADE_IN_END_FRACTION: Float = 0.21f

    /** `DOUBLE_ICON_FADE_IN_END_THRESHOLD_AS_SCREEN_WIDTH_PERCENTAGE` (`:1963`). */
    public const val DOUBLE_ICON_FADE_IN_END_FRACTION: Float = 0.36f

    /** `FULL_SCREEN_PADDING_FRACTION` (`:1965`), the undo button's horizontal padding. */
    public const val FULL_SCREEN_PADDING_FRACTION: Float = 0.0625f

    /** `HAPTIC_DEBOUNCING_TIME` (`:1910`), the window in which a second tick is swallowed. */
    public const val HAPTIC_DEBOUNCING_TIME: Long = 500L

    /** `SHORT_ANIMATION` (`:1927`). */
    public const val SHORT_ANIMATION: Int = 50

    /** `FLASH_ANIMATION` (`:1930`). */
    public const val FLASH_ANIMATION: Int = 100

    /** `RAPID_ANIMATION` (`:1933`). */
    public const val RAPID_ANIMATION: Int = 200

    /** `QUICK_ANIMATION` (`:1936`). */
    public const val QUICK_ANIMATION: Int = 250

    /** `STANDARD_IN_OUT` (`:1939`). */
    public val StandardInOut: Easing = CubicBezierEasing(0.20f, 0.0f, 0.0f, 1.00f)

    /**
     * `startFadeInFraction` (`:1744-1749`) — the fraction of the *screen* width a swipe has to cover
     * before the slot's icon starts to appear.
     */
    public fun startFadeInFraction(hasSecondaryAction: Boolean): Float =
        if (hasSecondaryAction) DOUBLE_ICON_VISIBLE_FRACTION else SINGLE_ICON_VISIBLE_FRACTION

    /** `endFadeInFraction` (`:1751-1756`) — where that icon fade is finished. */
    public fun endFadeInFraction(hasSecondaryAction: Boolean): Float =
        if (hasSecondaryAction) DOUBLE_ICON_FADE_IN_END_FRACTION else SINGLE_ICON_FADE_IN_END_FRACTION

    /**
     * A linear `0f..1f` ramp across `[fadeInStart, fadeInEnd]` of swiped distance, which is the body
     * shared by the button fade (`:1129-1135`) and the icon fade (`:1232-1239`). Both read
     * `abs(revealState.offset)`, both clamp the offset into the window first, and both report `0f`
     * below the start — note that upstream's `shouldDisplayButton` gate is *strictly* greater than the
     * start, so the value at exactly the start is `0f` either way.
     */
    public fun fadeInFraction(offsetPx: Float, fadeInStart: Float, fadeInEnd: Float): Float =
        if (offsetPx > fadeInStart) {
            val coercedOffset = min(max(offsetPx, fadeInStart), fadeInEnd)
            (coercedOffset - fadeInStart) / (fadeInEnd - fadeInStart)
        } else {
            0f
        }

    /**
     * The action button's own alpha (`:1125-1135`): zero below 6% of the screen width, ramping to 1 by
     * 12%. [screenWidthPx] of `0f` (not measured yet) keeps the button invisible rather than dividing
     * by zero, which is what upstream's uninitialised `screenWidthPx` would otherwise do.
     */
    public fun buttonAlpha(absOffsetPx: Float, screenWidthPx: Float): Float {
        if (screenWidthPx.isNaN() || screenWidthPx <= 0f) return 0f
        return fadeInFraction(
            absOffsetPx,
            screenWidthPx * BUTTON_VISIBLE_FRACTION,
            screenWidthPx * BUTTON_FADE_IN_END_FRACTION,
        )
    }

    /**
     * The icon wrapper's alpha (`:1229-1239`), ramped across the [startFadeInFraction] /
     * [endFadeInFraction] pair for one or two actions.
     */
    public fun iconAlpha(
        absOffsetPx: Float,
        screenWidthPx: Float,
        iconStartFadeInFraction: Float,
        iconEndFadeInFraction: Float,
    ): Float {
        if (screenWidthPx.isNaN() || screenWidthPx <= 0f) return 0f
        return fadeInFraction(
            absOffsetPx,
            screenWidthPx * iconStartFadeInFraction,
            screenWidthPx * iconEndFadeInFraction,
        )
    }

    /**
     * The `revealingAnchorPx` of `:355-360`: the partially-revealed anchor, expressed as the action
     * width scaled from the screen to the component. `null` in upstream means "no Revealing anchor at
     * all", which is what `hasNoSecondaryAction && !hasPartiallyRevealedState` produces (`:356`), and
     * the caller passes `null` through to [anchorsFor] and keeps `revealThreshold` at its `0f`
     * initial (`:362-382`).
     */
    public fun revealingAnchorPx(
        anchorWidthPx: Float,
        screenWidthPx: Float,
        componentWidthPx: Float,
    ): Float = (anchorWidthPx / screenWidthPx) * componentWidthPx

    /**
     * `revealState.revealThreshold` (`:362-372`). With the dual-fling flag on — the published default
     * — the threshold is 75% of the *screen* minus the inset of the component from the screen edges,
     * which is what lets a partially revealed item still be completed by a modest fling; with it off
     * it is the revealing anchor itself.
     */
    public fun revealThresholdPx(
        screenWidthPx: Float,
        componentWidthPx: Float,
        revealingAnchorPx: Float,
        isDualFlingEnabled: Boolean = IS_DUAL_FLING_THRESHOLD_ENABLED,
    ): Float =
        if (isDualFlingEnabled) {
            FULL_SWIPE_THRESHOLD_FRACTION * screenWidthPx - (screenWidthPx - componentWidthPx) / 2f
        } else {
            revealingAnchorPx
        }

    /** `calculateRevealedRatio` (`:822-836`), verbatim including the `||` and the `coerceAtLeast`. */
    public fun calculateRevealedRatio(
        hasNoSecondaryAction: Boolean,
        anchorWidthPx: Float,
        screenWidthPx: Float,
        componentWidthPx: Float,
        revealingAnchorPx: Float,
    ): Float =
        if (hasNoSecondaryAction || anchorWidthPx / screenWidthPx == 1f) {
            0.5f
        } else {
            (
                (
                    FULL_SWIPE_THRESHOLD_FRACTION * screenWidthPx -
                        (screenWidthPx - componentWidthPx) / 2f -
                        revealingAnchorPx
                    ) / (screenWidthPx - revealingAnchorPx)
                ).coerceAtLeast(0f)
        }

    /**
     * The anchor map `DraggableAnchors { … }` builds in `onSizeChanged` (`:383-395`), in px.
     *
     * [direction] is upstream's `if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1`
     * (`:262`), and the signs are upstream's: `Covered` sits at `0f`, the right-hand values at
     * `-screenWidthPx * direction` / `-revealingAnchorPx * direction` and the left-hand ones the other
     * way round. Insertion order is part of the contract — [nearestAnchor] keeps the first key on a
     * tie — so build it in this order.
     *
     * The `screenWidthPx` used is the *screen*'s, never the component's, which is why a full swipe of
     * a narrow item moves it off its own bounds.
     */
    public fun anchorsFor(
        screenWidthPx: Float,
        revealingAnchorPx: Float?,
        direction: Int,
        isBidirectional: Boolean,
    ): Map<RevealValue, Float> {
        val anchors = LinkedHashMap<RevealValue, Float>(5)
        anchors[RevealValue.Covered] = 0f
        anchors[RevealValue.RightRevealed] = -screenWidthPx * direction
        if (revealingAnchorPx != null) {
            anchors[RevealValue.RightRevealing] = -revealingAnchorPx * direction
        }
        if (isBidirectional) {
            anchors[RevealValue.LeftRevealed] = screenWidthPx * direction
            if (revealingAnchorPx != null) {
                anchors[RevealValue.LeftRevealing] = revealingAnchorPx * direction
            }
        }
        return anchors
    }

    /** `DraggableAnchors.minPosition()`: the smallest anchor, or `Infinity` with none. */
    public fun minPosition(anchors: Map<RevealValue, Float>): Float =
        anchors.minOfOrNull { (_, offset) -> offset } ?: Float.POSITIVE_INFINITY

    /** `DraggableAnchors.maxPosition()`: the largest anchor, or `-Infinity` with none. */
    public fun maxPosition(anchors: Map<RevealValue, Float>): Float =
        anchors.maxOfOrNull { (_, offset) -> offset } ?: Float.NEGATIVE_INFINITY

    /**
     * `closestAnchor(offset)` with no direction — the minimum-distance anchor, keeping the first key
     * in map order on a tie (`:1845`, `:1852-1853`).
     */
    public fun nearestAnchor(
        anchors: Map<RevealValue, Float>,
        offsetPx: Float,
    ): RevealValue? {
        var best: RevealValue? = null
        var bestCost = Float.POSITIVE_INFINITY
        for ((value, anchor) in anchors) {
            val cost = abs(anchor - offsetPx)
            if (cost < bestCost) {
                best = value
                bestCost = cost
            }
        }
        return best
    }

    /**
     * `closestAnchor(offset, searchUpwards)` — the nearest anchor *in the searched direction*, with
     * anchors behind the offset costing `POSITIVE_INFINITY` and a genuine tie between two anchors that
     * are both ahead keeping the first key in map order, since the comparison is strict. When every
     * anchor is behind there is no answer at all and this returns `null`, which is what the caller's
     * `?:` branch expects; upstream's copy reaches the same place through `error()`. Same rule
     * `SwipeToDismissMath.closestAnchor` implements for the two-anchor dismiss case, transcribed again
     * here because the keys differ.
     */
    public fun closestAnchor(
        anchors: Map<RevealValue, Float>,
        offsetPx: Float,
        searchUpwards: Boolean,
    ): RevealValue? {
        var best: RevealValue? = null
        var bestCost = Float.POSITIVE_INFINITY
        for ((value, anchor) in anchors) {
            val delta = if (searchUpwards) anchor - offsetPx else offsetPx - anchor
            val cost = if (delta < 0) Float.POSITIVE_INFINITY else delta
            if (cost < bestCost) {
                best = value
                bestCost = cost
            }
        }
        return best
    }

    /**
     * Which anchor a released drag heads for — `DraggableAnchors.computeTarget`
     * (`material3/SwipeToReveal.kt:1832-1883`), transcribed branch for branch:
     *
     * 1. not moving (`abs(velocity) == 0`) → the nearest anchor, direction ignored (`:1841-1845`);
     * 2. dual-fling on and at least [VELOCITY_NEAR_THRESHOLD_DP] (`:1846-1856`): at or beyond
     *    [VELOCITY_REVEALED_THRESHOLD_DP] the whole anchor set is bypassed and the far end in the
     *    direction of travel wins — after [onFastFling] has run, which is how `skipPartialHaptic` gets
     *    set (`:1036-1045`) — and between the two thresholds the next anchor in the direction of
     *    travel is taken;
     * 3. dual-fling off and at least `800.dp` (`:1857-1861`) → the next anchor in the direction of
     *    travel;
     * 4. otherwise the positional test (`:1862-1882`), whose one interesting detail is `isCompleting`
     *    (`:1871-1873`): a swipe *between* a Revealing and its own Revealed anchor uses
     *    `distance * revealedRatio` instead of the plain threshold, and the measurement origin flips
     *    to the Revealing anchor (`:1875`).
     *
     * Upstream's `!!` on every `closestAnchor` result cannot be reproduced: here an empty anchor map
     * (a view that has not measured yet) reports `null` and [RevealState.settle] keeps the current
     * value, instead of throwing inside a pointer event.
     *
     * @param positionalThreshold upstream's `(totalDistance, isCompleting) -> Float`
     *   (`:1025-1034`); [RevealState]'s own is `RevealState.positionalThreshold`.
     * @param velocityThresholdPx `Dp -> px`, upstream's
     *   `velocityThreshold = { threshold -> with(density) { threshold.toPx() } }` (`:1776`).
     */
    public fun computeTarget(
        currentOffset: Float,
        velocity: Float,
        anchors: Map<RevealValue, Float>,
        positionalThreshold: (totalDistance: Float, isCompleting: Boolean) -> Float,
        velocityThresholdPx: (thresholdDp: Float) -> Float,
        onFastFling: () -> Unit,
        isDualFlingEnabled: Boolean = IS_DUAL_FLING_THRESHOLD_ENABLED,
    ): RevealValue? {
        require(!currentOffset.isNaN()) { "The offset provided to computeTarget must not be NaN." }
        val isMoving = abs(velocity) > 0.0f
        val isMovingForward = isMoving && velocity > 0f
        // When we're not moving, pick the closest anchor and don't consider directionality
        return if (!isMoving) {
            nearestAnchor(anchors, currentOffset)
        } else if (
            isDualFlingEnabled &&
            abs(velocity) >= abs(velocityThresholdPx(VELOCITY_NEAR_THRESHOLD_DP))
        ) {
            if (abs(velocity) >= abs(velocityThresholdPx(VELOCITY_REVEALED_THRESHOLD_DP))) {
                onFastFling()
                if (velocity < 0) {
                    nearestAnchor(anchors, minPosition(anchors))
                } else {
                    nearestAnchor(anchors, maxPosition(anchors))
                }
            } else {
                closestAnchor(anchors, currentOffset, searchUpwards = isMovingForward)
            }
        } else if (
            !isDualFlingEnabled &&
            abs(velocity) >= abs(velocityThresholdPx(LEGACY_VELOCITY_THRESHOLD_DP))
        ) {
            closestAnchor(anchors, currentOffset, searchUpwards = isMovingForward)
        } else {
            val left = closestAnchor(anchors, currentOffset, false)
            val right = closestAnchor(anchors, currentOffset, true)
            if (left == null || right == null) return nearestAnchor(anchors, currentOffset)
            val leftAnchorPosition = anchors.getValue(left)
            val rightAnchorPosition = anchors.getValue(right)
            val distance = abs(leftAnchorPosition - rightAnchorPosition)
            // isCompleting is true when the swipe is transitioning between a "Revealing" state and
            // its corresponding "Revealed" state.
            // This transition uses a custom positional threshold (FULL_SWIPE_THRESHOLD_FRACTION).
            val isCompleting =
                (right == RevealValue.LeftRevealed && left == RevealValue.LeftRevealing) ||
                    (left == RevealValue.RightRevealed && right == RevealValue.RightRevealing)
            val relativeThreshold = abs(positionalThreshold(distance, isCompleting))
            val isLeft =
                if (isCompleting) left == RevealValue.LeftRevealing else isMovingForward
            val closestAnchorFromStart =
                if (isLeft) leftAnchorPosition else rightAnchorPosition
            val relativePosition = abs(closestAnchorFromStart - currentOffset)
            if (relativePosition >= relativeThreshold) {
                if (isLeft) right else left
            } else {
                if (isLeft) left else right
            }
        }
    }

    /**
     * The value a stopped animation ends on: the anchor within `0.5f` px of the end offset, or `null`
     * when it stopped off-anchor and the previous value is kept. Upstream's `settledValue` lives in
     * `AnchoredDraggableState` (not in this tree); the rule is the one already verified for
     * `SwipeableV2State` and used by the sibling at `SwipeToDismissMath.settledValue`
     * (`SwipeableV2.kt:420-425`).
     */
    public fun settledValue(
        anchors: Map<RevealValue, Float>,
        endOffsetPx: Float,
    ): RevealValue? =
        anchors.entries.firstOrNull { (_, anchorOffset) -> abs(anchorOffset - endOffsetPx) < 0.5f }?.key

    /**
     * `calculateVerticalOffsetBasedOnScreenPosition` (`:1713-1742`, and the older
     * `foundation/SwipeToReveal.kt:962-985`), which centres the revealed action row on the *visible*
     * part of a tall item that runs off the screen.
     *
     * Upstream's inputs are a `LayoutCoordinates`; the five numbers this formula actually reads are
     * the component's top and height in screen space and the window's top/bottom/centre-y, so those
     * are the parameters. The material3 file has the extra `maxCenter < minCenter` guard (`:1733-1737`)
     * that the foundation file lacks, and it is kept — a slot taller than its item would otherwise
     * throw inside `coerceIn`.
     *
     * @param childHeight measured height of the action row, px
     * @param parentTopOnScreen the component's top in screen space, upstream's
     *   `positionOnScreen().y.toInt()`
     * @param parentHeight the component's height, `globalPosition.size.height`
     * @param windowTop the window's top in screen space, `boundsInWindow().top`
     * @param windowBottom `boundsInWindow().bottom`
     * @param windowCenterY `boundsInWindow().center.y`
     * @param isPositioned whether the coordinates were ever reported; `false` is upstream's `null`
     *   / unspecified-position early return (`:1717-1719`)
     */
    public fun calculateVerticalOffsetBasedOnScreenPosition(
        childHeight: Int,
        parentTopOnScreen: Int,
        parentHeight: Int,
        windowTop: Float,
        windowBottom: Float,
        windowCenterY: Float,
        isPositioned: Boolean,
    ): Int {
        if (!isPositioned) return 0
        val parentTop = parentTopOnScreen
        val parentBottom = parentTop + parentHeight
        if (parentTop >= windowTop && parentBottom <= windowBottom) {
            // Don't offset if the item is fully on screen
            return 0
        }

        // Avoid going outside parent bounds
        val minCenter = parentTop + childHeight / 2
        val maxCenter = parentTop + parentHeight - childHeight / 2
        if (maxCenter < minCenter) {
            // Odd case where child (action button) is bigger than the parent (the swiped item),
            // and this would cause an exception from the coerceIn below.
            return 0
        }

        val desiredCenter = windowCenterY.toInt().coerceIn(minCenter, maxCenter)
        val actualCenter = parentTop + parentHeight / 2
        return desiredCenter - actualCenter
    }

    /**
     * `Modifier.absoluteOffset`'s x for the content slot (`:631-636`): with a one-direction reveal the
     * content may only move left (`coerceAtMost(0)`), with a bidirectional one it follows the offset
     * exactly. The `roundToInt()` is upstream's — the content moves on whole pixels while everything
     * else stays in floats.
     */
    public fun contentOffsetX(offsetPx: Float, canSwipeRight: Boolean): Float {
        val xOffset = offsetPx.roundToInt().toFloat()
        return if (canSwipeRight) xOffset else min(xOffset, 0f)
    }

    /**
     * The `maxWidth` the action row is measured with (`:559-573`): the reveal threshold while the
     * actions are being hidden, otherwise the swiped distance capped at the component width — the
     * `fastCoerceAtMost(componentWidthPx)` of `:568-570`, which the foundation file does not have
     * (`foundation/SwipeToReveal.kt:704-713`).
     */
    public fun slotClipWidthPx(
        absOffsetPx: Float,
        componentWidthPx: Float,
        revealThresholdPx: Float,
        hideActions: Boolean,
    ): Int =
        (
            if (hideActions) {
                revealThresholdPx
            } else {
                min(absOffsetPx, componentWidthPx)
            }
            ).let { max(0f, it) }
            .roundToInt()

    /**
     * The undo cross-fade, `fadeInUndo()` (`:1676-1700`): the undo slot fades in over
     * [RAPID_ANIMATION] ms after a [FLASH_ANIMATION] ms delay with `LinearEasing`, scales in from
     * `1.2f` over the same window with [StandardInOut], and what it replaces fades out over
     * [FLASH_ANIMATION] ms with `LinearEasing`. [elapsedMillis] is the time since the transition
     * started.
     *
     * Deviation: upstream expresses these as `ContentTransform` enter/exit specs run by
     * `AnimatedContent`, which owns the composition lifetime of both sides. Here they are pure
     * functions of elapsed time, evaluated on the view's frame ticker, so the outgoing slot is hidden
     * rather than disposed. The numbers, delays and easings are unchanged.
     */
    public fun undoEnterAlpha(elapsedMillis: Long): Float =
        tweenFraction(elapsedMillis, RAPID_ANIMATION.toLong(), FLASH_ANIMATION.toLong(), LinearEasing)

    /** The `scaleIn(initialScale = 1.2f)` companion of [undoEnterAlpha] (`:1687-1696`). */
    public fun undoEnterScale(elapsedMillis: Long): Float {
        val fraction = tweenFraction(
            elapsedMillis,
            RAPID_ANIMATION.toLong(),
            FLASH_ANIMATION.toLong(),
            StandardInOut,
        )
        return 1.2f + (1f - 1.2f) * fraction
    }

    /** The `fadeOut(tween(FLASH_ANIMATION))` of the actions being replaced (`:1697-1700`). */
    public fun undoExitAlpha(elapsedMillis: Long): Float =
        1f - tweenFraction(elapsedMillis, FLASH_ANIMATION.toLong(), 0L, LinearEasing)

    /**
     * `fadeOutUndo()` (`:1702-1711`): the actions come back with a *zero-duration* fade delayed by
     * [SHORT_ANIMATION] ms (upstream's comment is explicit that the enter transition is mandatory, so
     * it fades in "in 0 milliseconds"), and the undo slot fades out over [SHORT_ANIMATION] ms with
     * `LinearEasing`.
     */
    public fun undoCancelledEnterAlpha(elapsedMillis: Long): Float =
        if (elapsedMillis >= SHORT_ANIMATION) 1f else 0f

    /** The undo slot's `fadeOut(tween(SHORT_ANIMATION))` (`:1708-1710`). */
    public fun undoCancelledExitAlpha(elapsedMillis: Long): Float =
        1f - tweenFraction(elapsedMillis, SHORT_ANIMATION.toLong(), 0L, LinearEasing)

    /** How long the undo transition takes to be over, so the ticker knows when to stop. */
    public const val UNDO_TRANSITION_DURATION: Int = RAPID_ANIMATION + FLASH_ANIMATION

    /**
     * `fadeIn`/`fadeOut` evaluated as a function of elapsed time — `Easing.transform` applied to the
     * clamped progress, which is exactly what `VectorizedTweenSpec` computes frame by frame.
     */
    public fun tweenFraction(
        elapsedMillis: Long,
        durationMillis: Long,
        delayMillis: Long,
        easing: Easing,
    ): Float {
        if (durationMillis <= 0L) return 1f
        val raw = (elapsedMillis - delayMillis).toFloat() / durationMillis.toFloat()
        return easing.transform(raw.coerceIn(0f, 1f))
    }
}

/** Contains defaults for the Material 3 [SwipeToReveal] component. */
public object SwipeToRevealDefaults {

    /** Standard height for a large revealed action, such as when the swiped item is a Card. */
    public val LargeActionButtonHeight: Dp = 84.dp

    /**
     * The default value used to configure the size of the left edge zone in a [SwipeToReveal]. The
     * left edge zone in this case refers to the leftmost edge of the screen, in this region it is
     * common to disable scrolling in order for swipe-to-dismiss handlers to take over.
     */
    public val LeftEdgeZoneFraction: Float = 0.15f

    /** The default space between actions and content. (`ActionContentSpacing`, `:979`.) */
    public val ActionContentSpacing: Dp = 4.dp

    /**
     * Default padding space between action slots (`:1059`, and `foundation/SwipeToReveal.kt:811`).
     *
     * Upstream keeps it `internal`; it is public here because the row view, which is the thing that
     * has to place it, is in another file. Nothing else in this module can see it either way.
     */
    public val Padding: Dp = 4.dp

    /**
     * Default ratio of the content displayed when in [RevealValue.RightRevealing] state, i.e. all the
     * actions are revealed and the top content is not being swiped. For example, a value of 0.7 means
     * that 70% of the width is used to place the actions.
     *
     * `foundation/SwipeToReveal.kt:818`. Material3 has no such constant — its revealing anchor is
     * [SingleActionAnchorWidth]/[DoubleActionAnchorWidth] over the screen width
     * (`material3/SwipeToReveal.kt:355-360`) — so this one only feeds [createRevealAnchors].
     */
    public val RevealingRatio: Float = 0.7f

    /**
     * Default position threshold that needs to be swiped in order to transition to the next state.
     * Used in conjunction with [RevealingRatio]; for example, a threshold of 0.5 with a revealing
     * ratio of 0.7 means that the user needs to swipe at least 35% (0.5 * 0.7) of the component width
     * to go from [RevealValue.Covered] to [RevealValue.RightRevealing] and at least 85%
     * (0.7 + 0.5 * (1 - 0.7)) of the component width to go from [RevealValue.RightRevealing] to
     * [RevealValue.RightRevealed].
     *
     * `foundation/SwipeToReveal.kt:828-830`. Material3 uses
     * `AnchoredDraggableDefaults.PositionalThreshold` for the non-completing case
     * (`material3/SwipeToReveal.kt:1032`), a compose-foundation literal that is not in this reference
     * tree — see header note 2 — so this is the value [RevealState.positionalThreshold] starts from.
     */
    public val PositionalThreshold: (totalDistance: Float) -> Float = { totalDistance: Float ->
        totalDistance * 0.5f
    }

    /**
     * Default animation spec used when moving between states
     * (`foundation/SwipeToReveal.kt:807-808`). Material3 settles with
     * `AnchoredDraggableDefaults.SnapAnimationSpec` (`material3/SwipeToReveal.kt:1024`), which is not
     * in this reference tree — see header note 2 — so [RevealState] animates with this tween.
     */
    public val AnimationSpec: AnimationSpec<Float> =
        TweenSpec(durationMillis = RevealMath.RAPID_ANIMATION, easing = FastOutSlowInEasing)

    /**
     * Width that's required to display both actions in a [SwipeToReveal] composable (`:1051`).
     * Upstream `internal`; public here for the same cross-file reason as [Padding].
     */
    public val DoubleActionAnchorWidth: Dp = 130.dp

    /** Width that's required to display a single action (`:1054`). Upstream `internal`. */
    public val SingleActionAnchorWidth: Dp = 64.dp

    /** The size the action's icon slot is measured at (`:1056`). Upstream `internal`. */
    public val IconSize: Dp = 26.dp

    /**
     * `CustomTouchSlopMultiplier` (`:1924`), the factor [SwipeToReveal] applies to
     * [currentTouchSlop] before publishing the result through [CustomTouchSlopProvider], and the number
     * it hands to [WearSwipeToRevealView] as the gesture's slop.
     */
    public val CustomTouchSlopMultiplier: Float = 1.20f

    /**
     * The default behaviour for when [SwipeToReveal] should handle gestures. In this implementation
     * of [GestureInclusion], swipe events that originate in the left edge of the screen (as
     * determined by [LeftEdgeZoneFraction]) will be ignored, if the [RevealState] is
     * [RevealValue.Covered]. This allows swipe-to-dismiss handlers (if present) to handle the gesture
     * in this region.
     *
     * @param state [RevealState] of the [SwipeToReveal].
     * @param edgeZoneFraction The fraction of the screen width from the left edge where gestures
     *   should be ignored. Defaults to [LeftEdgeZoneFraction].
     */
    public fun gestureInclusion(
        state: RevealState,
        edgeZoneFraction: Float = LeftEdgeZoneFraction,
    ): GestureInclusion = RevealDefaultGestureInclusion(state, edgeZoneFraction)

    /**
     * A behaviour for [SwipeToReveal] to handle all gestures, intended for rare cases where
     * [RevealDirection.Bidirectional] is used and no swipe events are ignored. (`:996-1001`, and
     * `foundation/SwipeToReveal.kt:855-860`.)
     */
    public val bidirectionalGestureInclusion: GestureInclusion
        get() = RevealBidirectionalGestureInclusion

    /**
     * The velocity the "advance to the next anchor" fling needs, in px/s —
     * `velocityThreshold(VelocityNearThreshold)` (`:1848`). [density] is px per dp, so this is the
     * `with(density) { threshold.toPx() }` of `:1776`.
     */
    public fun velocityNearThresholdPx(density: Float): Float =
        RevealMath.VELOCITY_NEAR_THRESHOLD_DP * density

    /** The velocity that bypasses the positional checks, in px/s (`:1850`). */
    public fun velocityRevealedThresholdPx(density: Float): Float =
        RevealMath.VELOCITY_REVEALED_THRESHOLD_DP * density
}

/**
 * Scope used for suspending drag blocks. Allows setting [RevealState.offset] to a new value.
 *
 * @see [RevealState.drag] to learn how to start the drag and get the access to this scope.
 *
 * Ported from `material3/SwipeToReveal.kt:1372-1380`.
 */
public interface SwipeToRevealDragScope {
    /**
     * Assign a new value for an offset value for [RevealState].
     *
     * @param newOffset new value for [RevealState.offset]. Will be coerced to the boundaries
     *   defined by the available reveal targets
     */
    public fun dragTo(newOffset: Float)
}

/**
 * A class to keep track of the state of the composable. It can be used to customise the behavior and
 * state of the composable.
 *
 * Ported from `androidx.wear.compose.material3.RevealState` (`material3/SwipeToReveal.kt:1389-1568`).
 * Upstream stores every mutable bit in an `AnchoredDraggableState` created with no anchors
 * (`:1510`) and lets the composable's `onSizeChanged` install them (`:383-396`); that class is a
 * compose-foundation symbol that is not in this reference tree, so the offset bookkeeping is inlined
 * here and kept behaviourally identical to the arithmetic this file *does* carry —
 * [RevealMath.computeTarget], [RevealMath.closestAnchor], [RevealMath.settledValue] — which is the
 * same inlining the sibling `SwipeToDismissBoxState` performs over `SwipeableV2State`
 * (`SwipeToDismiss.kt:393-430`). Consequences worth naming:
 *
 * - The constructor is upstream's `RevealState(initialValue)` (`:1389`). The deprecated foundation
 *   `RevealState` took `animationSpec`, `confirmValueChange`, `positionalThreshold`, `anchors` and a
 *   `CoroutineScope` (`foundation/SwipeToReveal.kt:274-283`) and none of them survived into material3,
 *   so none are here either; [positionalThreshold] and [animationSpec] are instead members with
 *   upstream's *effective* values.
 * - No `@Stable`/`@RememberInComposition` annotation exists in Hibari, so the constructor contract is
 *   prose only: build one per revealable item and keep it across retunes with [rememberRevealState].
 * - `SwipeableV2State`/`AnchoredDraggableState`'s mutable members are reached by a `Modifier`
 *   upstream; here they are `internal` because the view pass *is* that modifier. Nothing was added to
 *   the surface and no knob upstream does not have was invented — [updateAnchors], [dispatchRawDelta],
 *   [settle] and [currentOffset] are the four the modifier would call.
 * - Gap: `AnchoredDraggableState`'s `AnchorChangeHandler` reconciliation
 *   (`androidx.compose.foundation.gestures`, reached through `Modifier.swipeAnchors`; the foundation
 *   file's equivalent is `foundation/SwipeToReveal.kt:578-587`) is not ported — it is the same
 *   positional-threshold machinery header note 1 already excludes, and `SwipeableV2.kt` is not
 *   vendored either. A retune that changes the screen width mid-animation therefore leaves the
 *   animation aimed at the old anchors, which is also what the sibling records for the dismiss box
 *   (`WearSwipeToDismissView.kt:200-207`).
 * - Gap: nested scrolling. The foundation file dispatches the drag through a
 *   `NestedScrollDispatcher` (`foundation/SwipeToReveal.kt:282`, `:593`); material3 dropped it and
 *   Hibari has no nested-scroll host in this module at all (`SwipeToDismiss.kt:411-413`), so a
 *   scrolling list child and the reveal compete through plain `onInterceptTouchEvent` rules instead.
 * - Deviation: [lastActionType], [revealThreshold] and [revealedRatio] are `internal` upstream
 *   (`:1524`, `:1533`, `:1539`); [lastActionType] is public because the foundation file publishes it
 *   (`foundation/SwipeToReveal.kt:296`) and the other two are `internal`, i.e. reachable from this
 *   module's view file.
 *
 * @param initialValue The initial value of the [RevealValue].
 */
public class RevealState(private val initialValue: RevealValue = RevealValue.Covered) {


    /**
     * The anchors of [RevealMath.anchorsFor], in px, empty until the view has measured.
     * Upstream's `anchoredDraggableState.anchors` (`:1510`, filled at `:396`).
     */
    internal var anchors: Map<RevealValue, Float> by mutableStateOf(emptyMap<RevealValue, Float>())

    private var offsetState: Float? by mutableStateOf<Float?>(null)

    private var currentValueState: RevealValue by mutableStateOf(initialValue)

    private var animationTarget: RevealValue? by mutableStateOf<RevealValue?>(null)

    private var lastVelocityState: Float by mutableStateOf(0f)

    /** px per dp, carried by [updateAnchors] for the velocity thresholds. */
    internal var densityPxPerDp: Float = Float.NaN

    /**
     * The current [RevealValue] based on the status of the component — upstream's
     * `anchoredDraggableState.settledValue` (`:1391-1392`), i.e. the value a settle animation last
     * landed on, not the one it is heading for.
     *
     * @see RevealMath.settledValue
     */
    public val currentValue: RevealValue get() = currentValueState

    /**
     * The target [RevealValue] based on the status of the component. This will be equal to the
     * [currentValue] if there is no animation running or swiping has stopped. Otherwise, this returns
     * the next [RevealValue] based on the animation/swipe direction. (`:1399-1400`, which is
     * `anchoredDraggableState.targetValue` = `computeTarget(offset, settledValue, 0f)` while dragging
     * and the running animation's target while animating.)
     */
    public val targetValue: RevealValue
        get() = animationTarget ?: run {
            val currentOffset = offsetState
            if (currentOffset != null && !currentOffset.isNaN() && anchors.isNotEmpty()) {
                RevealMath.computeTarget(
                    currentOffset = currentOffset,
                    velocity = 0f,
                    anchors = anchors,
                    positionalThreshold = positionalThreshold,
                    velocityThresholdPx = ::velocityToPx,
                    onFastFling = {},
                ) ?: currentValue
            } else {
                currentValue
            }
        }

    /** Returns whether the animation is running or not. (`:1403-1404`.) */
    public val isAnimationRunning: Boolean get() = animationTarget != null

    /**
     * The current amount by which the revealable content has been revealed by, in px and negative for
     * a right-hand reveal (`:1407-1408`). Reported as `0f` before the first measure — the foundation
     * file's `swipeableState.offset ?: 0f` (`foundation/SwipeToReveal.kt:329-330`), because every
     * consumer of this in a draw pass takes `abs()` of it and a `NaN` would poison the alpha.
     */
    public val offset: Float get() = offsetState ?: 0f

    /** The raw offset, or `null` while it has never been written. */
    internal val currentOffset: Float? get() = offsetState

    /**
     * The velocity the last settle animation ended with; reset to `0f` when it completed
     * (`SwipeableV2.kt:331-332, 416`, the model for this field).
     */
    internal val lastVelocity: Float get() = lastVelocityState

    /** `RevealActionType.None` until an action button is clicked (`:1524`). */
    public var lastActionType: RevealActionType by mutableStateOf(RevealActionType.None)

    /**
     * The threshold, in pixels, where the revealed actions are fully visible but the existing content
     * would be left in place if the reveal action was stopped (`:1533`, written at `:363-372`).
     */
    internal var revealThreshold: Float by mutableStateOf(0.0f)

    /**
     * The ratio used to calculate the positional threshold while swiping between Revealing and
     * Revealed states (`:1539`, written at `:374-381`).
     */
    internal var revealedRatio: Float = 0.5f

    /**
     * The component's own width in px — upstream's `componentWidthPx` local (`:315`, written at
     * `:353`, read at `:569`). It lives here because the row view reads it while drawing, and a view
     * has no closure over a composable's locals.
     */
    internal var componentWidthPx: Float = 0f

    /** The screen width in px the anchors were built from, so a retune can be recognised. */
    internal var screenWidthPx: Float = Float.NaN

    /**
     * The `positionalThreshold` argument of `flingBehavior` (`:1025-1034`): while a swipe is
     * *completing* (Revealing → Revealed) it is `distance * revealedRatio`, otherwise the default.
     * The `isSwipeToRevealDualFlingThresholdEnabled` gate is kept as upstream wrote it.
     */
    internal val positionalThreshold: (totalDistance: Float, isCompleting: Boolean) -> Float =
        { distance, isCompleting ->
            if (isCompleting && RevealMath.IS_DUAL_FLING_THRESHOLD_ENABLED) {
                distance * revealedRatio
            } else {
                SwipeToRevealDefaults.PositionalThreshold(distance)
            }
        }

    /**
     * The spec a settle or an `animateTo` runs with — upstream's
     * `AnchoredDraggableDefaults.SnapAnimationSpec` (`:1024`), substituted per header note 2.
     */
    internal val animationSpec: AnimationSpec<Float> = SwipeToRevealDefaults.AnimationSpec

    /** Whether the [value] has an anchor associated with it. */
    public fun hasAnchorForValue(value: RevealValue): Boolean = anchors.containsKey(value)

    /**
     * Get the offset position for an associated [RevealValue]
     *
     * @param revealValue The value to look up
     * @return The offset position of the revealValue, or [Float.NaN] if the revealValue does not
     *   exist or not supported by current SwipeToReveal and RevealState instance (`:1471-1473`).
     */
    public fun positionOf(revealValue: RevealValue): Float = anchors[revealValue] ?: Float.NaN

    /** The smallest anchor, or [Float.POSITIVE_INFINITY] before the anchors are installed. */
    public val minPosition: Float get() = RevealMath.minPosition(anchors)

    /** The largest anchor, or [Float.NEGATIVE_INFINITY] before the anchors are installed. */
    public val maxPosition: Float get() = RevealMath.maxPosition(anchors)

    /**
     * Require the current offset.
     *
     * @throws IllegalStateException If the offset has not been initialized yet (`:1546`).
     */
    internal fun requireOffset(): Float =
        checkNotNull(currentOffset) {
            "The offset was read before being initialized. Is the reveal measured yet?"
        }

    /**
     * Install the px anchors and the density, i.e. the `onSizeChanged` block's
     * `anchoredDraggableState.updateAnchors(draggableAnchors)` (`:383-396`). Returns whether the
     * caller has to reconcile an in-progress animation, upstream's own contract for that call; the
     * first call snaps to the initial value's anchor and reports `false`.
     */
    internal fun updateAnchors(anchors: Map<RevealValue, Float>, density: Float): Boolean {
        val previousAnchors = this.anchors
        this.anchors = anchors
        this.densityPxPerDp = density
        if (previousAnchors.isEmpty()) {
            if (anchors.containsKey(initialValue)) snap(initialValue)
            // Only the first call, with its initial anchor present, is settled; anything else needs
            // the AnchorChangeHandler that header note 3 lists as a gap.
            return !anchors.containsKey(initialValue)
        }
        return previousAnchors != anchors
    }

    /**
     * Drag by the supplied [delta], coerce it in the swipe bounds and return the remaining available.
     * These bounds are determined by the screen width and the configured [RevealDirection]
     * (`:1460-1462`). The arithmetic is `SwipeableV2State.dispatchRawDelta`
     * (`SwipeableV2.kt:469-499`, already transcribed for the dismiss box at
     * `SwipeToDismiss.kt:569-577`), which is the same consume-and-clamp `AnchoredDraggableState` does.
     *
     * @param delta The delta (positive or negative) to drag
     * @return The consumed delta
     */
    public fun dispatchRawDelta(delta: Float): Float {
        val currentDragPosition = currentOffset ?: 0f
        val min = minPosition
        val max = maxPosition
        if (min > max) return delta
        val clamped = (currentDragPosition + delta).coerceIn(min, max)
        val deltaToConsume = clamped - currentDragPosition
        offsetState = (currentDragPosition + deltaToConsume).coerceIn(min, max)
        return delta - deltaToConsume
    }

    /**
     * Snaps to the [targetValue] without any animation (if a previous item was already revealed, that
     * item will be reset to the covered state with animation) (`:1416-1422`).
     *
     * @param targetValue The target [RevealValue] where the [currentValue] will be changed to.
     */
    public suspend fun snapTo(targetValue: RevealValue) {
        // Cover the previously open component if revealing a different one
        if (targetValue != RevealValue.Covered) {
            resetLastState(this)
        }
        snap(targetValue)
    }

    /**
     * Animates to the [targetValue] with the animation spec provided (`:1431-1450`).
     *
     * @param targetValue The target [RevealValue] where the [currentValue] will animate to.
     * @throws IllegalStateException if the target [RevealValue] is not valid for current
     *   [RevealState] instance.
     */
    public suspend fun animateTo(targetValue: RevealValue): Unit = animateTo(targetValue, 0f)

    /**
     * [RevealState] internal instance for the state. Upstream's `flingBehavior` hands the release
     * velocity to `snapFlingBehavior`, which turns it into a snap *offset* through
     * `calculateSnapOffset` (`:1816-1827`) and then animates; with no fling host, the two steps live
     * here, which is exactly the shape `SwipeableV2State.settle` has
     * (`SwipeableV2.kt:435-462`, ported at `SwipeToDismiss.kt:626-641`).
     *
     * Unlike the dismiss box, upstream's reveal has no `confirmValueChange` veto on this path
     * (`:1431-1450` never consults one), so none is applied.
     */
    internal suspend fun settle(velocityPxPerSecond: Float) {
        val startOffset = requireOffset()
        val target = RevealMath.computeTarget(
            currentOffset = startOffset,
            velocity = velocityPxPerSecond,
            anchors = anchors,
            positionalThreshold = positionalThreshold,
            velocityThresholdPx = ::velocityToPx,
            onFastFling = { this.onFastFling?.invoke() },
        ) ?: return
        animateTo(target, velocityPxPerSecond)
    }

    /**
     * Hook for the view's `onFastFling` (`:1036-1045`): a fling fast enough to bypass the positional
     * checks sets `skipPartialHaptic` when it is heading for a Revealing anchor, so the partial haptic
     * is not played twice for one motion. Upstream passes the lambda into the fling behaviour; there
     * is no fling behaviour here, so the view installs it.
     */
    internal var onFastFling: (() -> Unit)? = null

    /**
     * `skipPartialHaptic` (`:1516`) — set by [onFastFling], cleared when the target is next observed,
     * which is upstream's `LaunchedEffect(revealState.targetValue)` tail (`:673`).
     */
    internal var skipPartialHaptic: Boolean = false

    /** `lastHapticFeedbackTime` (`:1522`), the `System.currentTimeMillis()` of the last tick. */
    internal var lastHapticFeedbackTime: Long = 0L

    private suspend fun animateTo(targetValue: RevealValue, velocity: Float) {
        check(
            targetValue == RevealValue.Covered || anchors.containsKey(targetValue),
        ) {
            "The RevealValue you're targeting isn't supported by current RevealState instance. " +
                "Please ensure the RevealState was created with appropriate revealDirection or " +
                "hasPartiallyRevealedState that supports the target RevealValue."
        }
        // Cover the previously open component if revealing a different one
        if (targetValue != RevealValue.Covered) {
            resetLastState(this)
        }
        val targetOffset = anchors[targetValue]
        try {
            if (targetOffset == null) {
                currentValueState = targetValue
                return
            }
            try {
                animationTarget = targetValue
                animate(offsetState ?: 0f, targetOffset, velocity, animationSpec) { value, v ->
                    // A spring overshoots on purpose, and the draw passes take abs() of it, so the
                    // value is not clamped here (same reasoning as SwipeableV2.kt:408-411 via
                    // SwipeToDismiss.kt:603-608).
                    offsetState = value
                    lastVelocityState = v
                }
                lastVelocityState = 0f
            } finally {
                animationTarget = null
                val endOffset = requireOffset()
                val endState = RevealMath.settledValue(anchors, endOffset)
                currentValueState = endState ?: currentValue
            }
        } finally {
            if (targetValue == RevealValue.Covered) {
                lastActionType = RevealActionType.None
            }
        }
    }

    private fun snap(targetValue: RevealValue) {
        val targetOffset = anchors[targetValue]
        if (targetOffset != null) {
            dispatchRawDelta(targetOffset - (currentOffset ?: 0f))
            currentValueState = targetValue
            animationTarget = null
        } else {
            currentValueState = targetValue
        }
    }

    /**
     * Call this function to take control of the drag logic and mutate the offset (`:1492-1508`).
     *
     * All actions that change the [offset] of this [RevealState] must be performed within a [drag]
     * block in order to guarantee that mutual exclusion is enforced. The offset provided to
     * [SwipeToRevealDragScope.dragTo] will be coerced to the interval of the min/max offset values of
     * [RevealValue].
     *
     * Deviation: upstream's `dragPriority: MutatePriority` is dropped (header note 3) and so is the
     * "cancelled and re-executed if the layout changes mid-block" behaviour, which needs a
     * composition; the coercion of `dragTo`, which is the part a caller can observe, is kept.
     *
     * @param block The suspending block where the drag mutations are performed
     */
    public suspend fun drag(block: suspend SwipeToRevealDragScope.() -> Unit) {
        val scope = object : SwipeToRevealDragScope {
            override fun dragTo(newOffset: Float) {
                val min = minPosition
                val max = maxPosition
                if (min > max) return
                offsetState = newOffset.coerceIn(min, max)
            }
        }
        scope.block()
    }

    /**
     * Resets last state if a different SwipeToReveal is being moved to new anchor and the last state
     * is in [RevealValue.RightRevealing] mode which represents no action has been performed yet. In
     * [RevealValue.RightRevealed], the action has been performed and it will not be reset
     * (`:1553-1562`).
     */
    internal suspend fun resetLastState(currentState: RevealState) {
        val oldState = SingleSwipeCoordinator.lastUpdatedState.getAndSet(currentState)
        if (currentState != oldState) {
            oldState?.let {
                if (
                    it.currentValue == RevealValue.RightRevealing ||
                    it.currentValue == RevealValue.LeftRevealing
                ) {
                    it.animateTo(RevealValue.Covered)
                }
            }
        }
    }

    private fun velocityToPx(thresholdDp: Float): Float {
        val density = densityPxPerDp
        if (density.isNaN()) {
            // The view has not measured yet, so no fling can have been reported either; treat the
            // threshold as unreachable rather than throwing inside a pointer callback.
            return Float.POSITIVE_INFINITY
        }
        return thresholdDp * density
    }

    /** A singleton instance to keep track of the [RevealState] which was modified the last time. */
    private object SingleSwipeCoordinator {
        var lastUpdatedState: AtomicReference<RevealState?> = AtomicReference(null)
    }
}

/**
 * Create and [remember] a [RevealState]. (`:1575-1577`.)
 *
 * @param initialValue The initial value of the [RevealValue].
 */
@Tunable
public fun rememberRevealState(
    initialValue: RevealValue = RevealValue.Covered,
): RevealState = remember(initialValue) { RevealState(initialValue = initialValue) }

/**
 * [SwipeToReveal] Material composable. This adds the option to configure up to two additional actions
 * on a composable: a mandatory [primaryAction] and an optional [secondaryAction]. These actions are
 * initially hidden (unless [RevealState] is created with an initial value other than
 * [RevealValue.Covered]) and revealed only when the [content] is swiped - the action buttons can then
 * be clicked. A full swipe of the [content] triggers the [onSwipePrimaryAction] callback, which is
 * expected to match the [primaryAction]'s onClick callback.
 *
 * Adding undo actions allows users to undo a primary or secondary action that may have been performed
 * inadvertently. The corresponding undo action is displayed when the primary or secondary action is
 * triggered via click or swipe and after [SwipeToReveal] has animated to the revealed state. After the
 * undo action is clicked, [SwipeToReveal] animates back to the [RevealValue.Covered] state and the
 * user can then swipe to reveal if they wish to perform another action.
 *
 * For destructive actions like "Delete", consider making this the primary action, and providing the
 * [undoPrimaryAction].
 *
 * When using [SwipeToReveal] with large content items like [Card]s, it is recommended to set the
 * height of the [SwipeToRevealScope.PrimaryActionButton] and [SwipeToRevealScope.SecondaryActionButton]
 * to [SwipeToRevealDefaults.LargeActionButtonHeight] - in other cases, the button displayed by
 * [SwipeToReveal] has height [ButtonDefaults.Height] by default. It is recommended to always use the
 * default undo button height as created by [SwipeToRevealScope.UndoActionButton].
 *
 * If [hasPartiallyRevealedState] = true, [RevealState] should be reset to [RevealValue.Covered] by
 * the caller when scrolling occurs, because the revealed actions are vertically centred on the visible
 * part of the content and a tall item's actions would otherwise sit off-centre after a scroll.
 *
 * If [revealDirection] is [RevealDirection.Bidirectional], the actions revealed on swipe are the same
 * on both sides.
 *
 * Ported from `androidx.wear.compose.material3.SwipeToReveal` (`:241-677`); the deprecated overload at
 * `:782-820` is not ported (it is `DeprecationLevel.HIDDEN`, and its body forwards
 * `undoSecondaryAction` into the `undoPrimaryAction` slot — `:812` — which looks like an upstream
 * typo this port declines to reproduce).
 *
 * What the Views form cannot do, all of it inside [WearSwipeToRevealView]:
 *  - the `LaunchedEffect(currentValue, lastActionType)` pair (`:641-674`) has no host, so the view
 *    polls both and runs the same bodies: `resetLastState`, `onSwipePrimaryAction` (which upstream
 *    fires *after* stamping `lastActionType = PrimaryAction`, `:650-653`), and the two haptic paths.
 *  - `AnimatedContent` (`:459-467`) swaps the action row for the undo row; both rows are composed here
 *    and cross-faded by [RevealMath.undoEnterAlpha] and friends, so a remembered value inside a hidden
 *    row survives where upstream would have dropped it.
 *  - `derivedStateOf`/`animateFloatAsState` (`:429-552`) have no composition to run in; the same
 *    formulas are evaluated per frame by the view from [RevealMath].
 *  - the `require(revealState.currentValue != LeftRevealing && …)` guard for a `RightToLeft` reveal
 *    (`:264-270`) is enforced by the view, which throws on a layout pass, because the check has to
 *    re-run as the state changes.
 *
 * @param primaryAction The primary action of this component.
 *   [SwipeToRevealScope.PrimaryActionButton] should be used to create a button for this slot. If
 *   [undoPrimaryAction] is provided, the undo button will be displayed after [SwipeToReveal] has
 *   animated to the revealed state and the primary action button has been hidden.
 * @param onSwipePrimaryAction A callback which will be triggered when a full swipe is performed. It is
 *   expected that the same callback is given to the [SwipeToRevealScope.PrimaryActionButton]'s onClick
 *   action. If [undoPrimaryAction] is provided, that will be displayed after the swipe gesture is
 *   completed.
 * @param modifier [Modifier] to be applied on the composable.
 * @param secondaryAction Optional secondary action of this component.
 *   [SwipeToRevealScope.SecondaryActionButton] should be used to create a button for this slot.
 * @param undoPrimaryAction Optional undo action for the primary action of this component.
 *   [SwipeToRevealScope.UndoActionButton] should be used to create a button for this slot.
 * @param undoSecondaryAction Optional undo action for the secondary action of this component. Ignored
 *   if [secondaryAction] is null. [SwipeToRevealScope.UndoActionButton] should be used.
 * @param revealState [RevealState] of the [SwipeToReveal]. `null`, the default, remembers one in the
 *   body (upstream's `rememberRevealState()` default, `:250` — see the note in the parameter list).
 * @param revealDirection The direction from which [SwipeToReveal] can reveal the actions. It is
 *   strongly recommended to respect the default value of [RevealDirection.RightToLeft] to avoid
 *   conflicting with the system-side swipe-to-dismiss gesture.
 * @param hasPartiallyRevealedState Determines whether the intermediate states
 *   [RevealValue.RightRevealing] and [RevealValue.LeftRevealing] are used. By default, partially
 *   revealed state is allowed for single actions - set to false to make actions complete when swiped
 *   instead. This flag has no effect if a secondary action is provided.
 * @param gestureInclusion Provides fine-grained control so that touch gestures can be excluded when
 *   they start in a certain region; see [SwipeToRevealDefaults.gestureInclusion] and
 *   [SwipeToRevealDefaults.bidirectionalGestureInclusion]. `null`, the default, picks between those two
 *   by [revealDirection], exactly as upstream's default expression does.
 * @param actionContentSpacing The space between the main content and the actions.
 * @param content The content that will be initially displayed over the other actions provided.
 */
@Tunable
public fun SwipeToReveal(
    primaryAction: @Tunable SwipeToRevealScope.() -> Unit,
    onSwipePrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryAction: (@Tunable SwipeToRevealScope.() -> Unit)? = null,
    undoPrimaryAction: (@Tunable SwipeToRevealScope.() -> Unit)? = null,
    undoSecondaryAction: (@Tunable SwipeToRevealScope.() -> Unit)? = null,
    revealState: RevealState? = null,
    revealDirection: RevealDirection = RevealDirection.RightToLeft,
    hasPartiallyRevealedState: Boolean = true,
    gestureInclusion: GestureInclusion? = null,
    actionContentSpacing: Dp = SwipeToRevealDefaults.ActionContentSpacing,
    content: @Tunable () -> Unit,
) {
    // Upstream's default is `rememberRevealState()` (`:250`); a `@Tunable` call cannot sit in a default
    // expression, because those are hoisted into a non-`@Tunable` `$default` method (same rule that makes
    // `Button.kt`'s `colors` nullable), so `null` means "remember one here" and the parameter keeps its
    // upstream name and position.
    val state = revealState ?: rememberRevealState()
    // Upstream's default (`:253-258`) needs the resolved state, so it moves into the body with it.
    val inclusion = gestureInclusion ?: if (revealDirection == RevealDirection.Bidirectional) {
        SwipeToRevealDefaults.bidirectionalGestureInclusion
    } else {
        SwipeToRevealDefaults.gestureInclusion(state)
    }
    val hasSecondaryAction = secondaryAction != null
    val scope = SwipeToRevealScope(
        revealState = state,
        hasSecondaryAction = hasSecondaryAction,
        hasPrimaryUndo = undoPrimaryAction != null,
        hasSecondaryUndo = undoSecondaryAction != null,
    )
    // `CustomTouchSlopProvider(newTouchSlop = LocalViewConfiguration.current.touchSlop *
    // CustomTouchSlopMultiplier)` (`:317-319`, `:1924`): the slop in force for this subtree, times 1.2,
    // published for anything nested inside and handed to the box, which is the one that has to apply it
    // at gesture time.
    val touchSlopPx = currentTouchSlop(currentContext) * SwipeToRevealDefaults.CustomTouchSlopMultiplier
    // Upstream chooses between the two undo slots inside `AnimatedContent`'s lambda, by
    // `lastActionType` (`:488-499`). Both are emitted here, so each is built through a scope that
    // records which action type it answers to, and the row hides the other one — see the
    // `undoSlotMarker` argument at the second undo emission.

    CustomTouchSlopProvider(newTouchSlop = touchSlopPx) {
        Node(
            modifier = modifier
                // `modifier.fillMaxWidth()` (`:323`): the box is as wide as its parent and as tall as the
                // content, which is what the view's measure pass enforces.
                .matchParentWidth()
                .viewClass(WearSwipeToRevealView::class.java)
                .thenViewAttribute<WearSwipeToRevealView, RevealState>(uniqueKey, state) {
                    this.revealState = it
                }
                .thenViewAttribute<WearSwipeToRevealView, RevealDirection>(uniqueKey, revealDirection) {
                    this.revealDirection = it
                }
                .thenViewAttribute<WearSwipeToRevealView, Boolean>(
                    uniqueKey,
                    hasPartiallyRevealedState,
                ) { this.hasPartiallyRevealedState = it }
                .thenViewAttribute<WearSwipeToRevealView, Boolean>(uniqueKey, hasSecondaryAction) {
                    this.hasSecondaryAction = it
                }
                .thenViewAttribute<WearSwipeToRevealView, GestureInclusion>(
                    uniqueKey,
                    inclusion,
                ) { this.gestureInclusion = it }
                .thenViewAttribute<WearSwipeToRevealView, Function0<Unit>>(
                    uniqueKey,
                    onSwipePrimaryAction,
                ) { this.onSwipePrimaryAction = it }
                .thenViewAttribute<WearSwipeToRevealView, Dp>(uniqueKey, actionContentSpacing) {
                    this.actionContentSpacing = it
                }
                .thenViewAttribute<WearSwipeToRevealView, Float>(uniqueKey, touchSlopPx) {
                    this.touchSlopPx = it
                },
            content = {
                // Child order is the contract the view relies on: 0 = action row, 1 = undo row,
                // 2 = content. It mirrors the emission order of the composable, whose overlay Box
                // (`:443-627`) precedes the content Row (`:629-640`).
                Node(
                    modifier = Modifier
                        .matchParentSize()
                        .viewClass(WearRevealActionRowView::class.java)
                        .thenViewAttribute<WearRevealActionRowView, RevealState>(uniqueKey, state) {
                            this.state = it
                        }
                        .thenViewAttribute<WearRevealActionRowView, Boolean>(
                            uniqueKey,
                            hasSecondaryAction,
                        ) { this.hasSecondaryAction = it }
                        .thenViewAttribute<WearRevealActionRowView, Dp>(
                            uniqueKey,
                            actionContentSpacing,
                        ) { this.actionContentSpacing = it },
                    content = {
                        if (secondaryAction != null) {
                            secondaryAction(scope)
                        }
                        primaryAction(scope)
                    }
                )
                Node(
                    modifier = Modifier
                        .matchParentSize()
                        .viewClass(WearRevealActionRowView::class.java)
                        .thenViewAttribute<WearRevealActionRowView, RevealState>(uniqueKey, state) {
                            this.state = it
                        }
                        // The undo arm's icon fades off the same one-or-two-actions pair as the
                        // actions arm (`startFadeInFraction(hasSecondaryAction)`, `:957-958`), even
                        // though its layout is the centred single-slot one.
                        .thenViewAttribute<WearRevealActionRowView, Boolean>(
                            uniqueKey,
                            hasSecondaryAction,
                        ) { this.hasSecondaryAction = it }
                        .thenViewAttribute<WearRevealActionRowView, Boolean>(uniqueKey, true) {
                            isUndoRow = it
                        },
                    content = {
                        // Upstream picks one of the two inside the AnimatedContent lambda by
                        // `lastActionType` (`:488-499`); with no re-tune to key on, both are emitted and
                        // the row hides the one whose marker does not match `state.lastActionType`.
                        if (undoSecondaryAction != null && hasSecondaryAction) {
                            undoSecondaryAction(
                                SwipeToRevealScope(
                                    revealState = state,
                                    hasSecondaryAction = hasSecondaryAction,
                                    hasPrimaryUndo = undoPrimaryAction != null,
                                    hasSecondaryUndo = undoSecondaryAction != null,
                                    undoSlotMarker = RevealActionType.SecondaryAction,
                                ),
                            )
                        }
                        if (undoPrimaryAction != null) {
                            undoPrimaryAction(scope)
                        }
                    }
                )
                // Upstream's `Row(modifier = Modifier.absoluteOffset { … }) { content() }` (`:629-640`):
                // a plain horizontal row that wraps the content, and the only thing the offset needs to
                // move. The offset itself is written onto this view's translationX by the box, because a
                // Views layout cannot be re-run per frame the way `absoluteOffset`'s lambda is.
                Row(modifier = Modifier, content = { content() })
            }
        )
    }
}
