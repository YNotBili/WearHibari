package com.huanli233.hibari.wear

import android.view.View
import com.huanli233.hibari.animation.AnimationSpec
import com.huanli233.hibari.animation.CubicBezierEasing
import com.huanli233.hibari.animation.animateFloatAsState
import com.huanli233.hibari.animation.snap
import com.huanli233.hibari.animation.tween
import com.huanli233.hibari.foundation.attributes.alpha
import com.huanli233.hibari.foundation.attributes.scaleX
import com.huanli233.hibari.foundation.attributes.scaleY
import com.huanli233.hibari.foundation.attributes.translationY
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.thenUnitViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.unit.toPx
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.wear.tokens.MotionDurationTokens

/**
 * Ported from androidx.wear.compose.material3.ScreenStage (`material3/ScrollAway.kt:63-99`), the
 * upstream doc's own three-valued model of a screen's life: [New] when it first appears, [Scrolling]
 * while a list is being moved, [Idle] once it has settled. `TimeText`, [ScrollIndicator] and
 * [scrollAway] branch on it, and upstream's `AppScaffold` is what normally produces it.
 *
 * Upstream's `@Immutable` is a Compose annotation about snapshot-read stability and is the one thing
 * dropped; the `internal constructor` + `value`-class packing is kept verbatim, so `New == New` holds
 * by value exactly as it does there.
 */
@JvmInline
value class ScreenStage internal constructor(internal val value: Int) {

    companion object {
        /** `ScreenStage.New` — `ScrollAway.kt:70-75`: the screen was just shown. */
        val New: ScreenStage = ScreenStage(0)

        /** `ScreenStage.Idle` — `:76-81`: not scrolling, and the settle time has passed. */
        val Idle: ScreenStage = ScreenStage(1)

        /** `ScreenStage.Scrolling` — `:82-89`: the list is being moved right now. */
        val Scrolling: ScreenStage = ScreenStage(2)
    }

    override fun toString(): String = when (this) {
        New -> "New"
        Idle -> "Idle"
        Scrolling -> "Scrolling"
        else -> "Unknown"
    }
}

/**
 * Ported from androidx.wear.compose.foundation.ScrollInfoProvider
 * (`foundation/ScrollInfoProvider.kt:24-73`), the read-only face [scrollAway] needs from a scrolling
 * container. Upstream declares it in the foundation package; this module has one `wear` package and
 * already folds foundation's pieces into it (`CurvedLayout`, `lazy/WearListTransform`), and
 * `Modifier.scrollAway` cannot take the type without it, so it lives beside its only consumer here.
 *
 * **What is not ported, and the exact reason.** Upstream's five `ScrollInfoProvider(state)` factories
 * (`:82`, `:93`, `:104`, `:116`, `:128` — for `ScalingLazyListState`, `LazyListState`,
 * `TransformingLazyColumnState`, `ScrollState`, `PagerState`) are adapters onto Compose layout info.
 * The `ScalingLazyListState` one (`:136-198`) reads `state.layoutInfo.visibleItemsInfo` for the index-1
 * item's `startOffset(...)` and `offset`, `layoutInfo.totalItemsCount`, `config.value.viewportHeightPx`,
 * `config.value.reverseLayout` and `config.value.autoCentering.itemIndex`. Hibari's
 * [com.huanli233.hibari.wear.lazy.ScalingLazyListState] publishes `centerItemIndex`,
 * `centerItemOffset`, `isScrollInProgress`, `canScrollForward` and `canScrollBackward` and none of
 * those, so a factory here could only invent the numbers. The interface is therefore the port's
 * boundary, exactly as `ScrollIndicator.kt:208-244` made `itemCount` / `visibleItemCount` its
 * boundary: the code that owns the `RecyclerView` computes the five values — for an item-top anchor
 * that is `-child.top` of the first attached child, which is what upstream's `-it.offset` reduces to —
 * and writes them where the tune can read them.
 */
interface ScrollInfoProvider {

    /**
     * `ScrollInfoProvider.isScrollAwayValid` — `:28-31`: whether the anchor item exists at all. When
     * false, [scrollAway] shows its content outright (`ScrollAway.kt:155-159`).
     */
    val isScrollAwayValid: Boolean

    /** `ScrollInfoProvider.isScrollable` — `:33`: whether the container can be scrolled. */
    val isScrollable: Boolean

    /** `ScrollInfoProvider.isScrollInProgress` — `:35-40`: whether it is being scrolled right now. */
    val isScrollInProgress: Boolean

    /**
     * `ScrollInfoProvider.anchorItemOffset` — `:42-48`: how far the anchor item has scrolled upwards,
     * in **pixels**, never negative, and `Float.NaN` when the anchor item has left the visible list so
     * its offset can no longer be computed. [scrollAway] reads `NaN` as "scrolled fully away".
     */
    val anchorItemOffset: Float

    /**
     * `ScrollInfoProvider.lastItemOffset` — `:50-56`: the gap between the last item's bottom edge and
     * the viewport bottom, in pixels, clamped at 0. Not read by [scrollAway]; it is what upstream's
     * `AppScaffold` uses to decide when to surface the bottom scroll hint.
     */
    val lastItemOffset: Float
}

/**
 * Ported from androidx.wear.compose.material3.scrollAway (`material3/ScrollAway.kt:32-61`) and its
 * `ScrollAwayModifierNode` (`:137-255`): the transform that lifts a top-of-screen element — a
 * `TimeText`, a [PageIndicator], a header — out of the way as the list below it is scrolled up, by
 * translating, scaling and fading it in proportion to how far the anchor item has moved.
 *
 * Upstream applies the four numbers inside `placeWithLayer` (`:150-210`), which is why it does not
 * relayout what it scrolls away. Views give the same property for free: `translationY`, `scaleX`,
 * `scaleY` and `alpha` are view attributes that never touch measure or layout
 * (`foundation.attributes`), so this modifier writes exactly those four on the node it is applied to.
 * The scale pivot is the top edge — upstream's `TransformOrigin(0.5f, 0f)` (`:210`) — and a `View`'s
 * default pivot is already centred horizontally, so only [scrollAwayTopPivot] has to set `pivotY`.
 *
 * The two `Animatable`s (`:141-142`, driven at `:181-202`) become two `animateFloatAsState` calls, with
 * upstream's own specs: the progress runs `tween(MotionTokens.DurationShort4, EasingStandard)`
 * (`:220-229`) and the alpha `tween(DurationMedium1, EasingStandard or EasingStandardDecelerate)`
 * (`:238-251`), and hiding stays instant because upstream's `snapTo(0f)` (`:182-185`) becomes
 * [snap] on the alpha spec.
 *
 * # Two documented differences, neither silent
 *
 *  - Upstream's `scrollingAtTheTop` snap (`:191-198`) compares against
 *    `progressAnimatable.targetValue`, the *previous* target, which `animateFloatAsState` does not
 *    expose. It is not needed: that branch only fires when the target is unchanged (an unchanged
 *    target restarts nothing here either) or when the offset has gone invalid and the previous target
 *    was already non-zero, in which case this port tweens to the same value over 200 ms instead of
 *    snapping to it. Nothing else in the file changes.
 *  - Like `ScrollIndicator` in this module, the modifier is evaluated when the node is tuned, so it
 *    follows a fling only as fast as whatever re-tunes it. Drive it from a
 *    `RecyclerView.OnScrollListener` that writes the anchor offset into state the tune reads; the
 *    animations above are what carry it between those writes.
 *
 * @param scrollInfoProvider The five scroll facts, from the container that owns them; see
 *   [ScrollInfoProvider] for why the caller builds it here rather than being handed a factory.
 * @param screenStage Upstream's non-composable lambda (`:60`) — called once per evaluation here, where
 *   upstream calls it twice per measure (`:170`, `:194`).
 */
@Tunable
fun Modifier.scrollAway(
    scrollInfoProvider: ScrollInfoProvider,
    screenStage: () -> ScreenStage,
): Modifier {
    val maxScrollOutPx = scrollAwayMaxScrollOut.toPx(currentContext)
    val offsetPx = scrollInfoProvider.anchorItemOffset

    // The whole target computation, `ScrollAway.kt:152-179`, verbatim.
    var (targetProgress, targetAlpha) = when {
        // An anchor item that does not exist: show the content anyway (`:155-159`).
        !scrollInfoProvider.isScrollAwayValid -> 0f to 1f
        // NaN or past the end of the range means the tracked item is off screen (`:160-164`).
        offsetPx.isNaN() || offsetPx > maxScrollOutPx -> 1f to 0f
        else -> (offsetPx / maxScrollOutPx).coerceIn(0f, 1f) to 1f
    }

    val stage = screenStage()
    // Idle or New brings back anything that scrolled away (`:172-179`).
    val showAfterTimeout = stage != ScreenStage.Scrolling &&
        (targetAlpha == 0f || targetProgress > scrollAwayTimeTextVisibilityThreshold)
    if (showAfterTimeout) {
        targetProgress = 0f
        targetAlpha = 1f
    }

    val progress = animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = when {
            // `:195-198`: showing after a timeout carries no progress animation.
            showAfterTimeout && stage != ScreenStage.New -> snap<Float>()
            else -> scrollAwayProgressSpec
        },
    ).value
    val alphaValue = animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = when {
            targetAlpha == 0f -> snap<Float>()  // `:182-185`, "Hide is instant."
            else -> scrollAwayAlphaSpec(targetAlpha)
        },
    ).value

    // `:204-210`: the fade and scale run between 100% and 50% of the content, multiplied together
    // with the alpha channel, while the translation is the full 24.dp.
    val motionFraction = scrollAwayMinMotionOut +
        (scrollAwayMaxMotionOut - scrollAwayMinMotionOut) * progress
    return this
        .alpha(motionFraction * alphaValue)
        .scaleX(motionFraction)
        .scaleY(motionFraction)
        .translationY(-(scrollAwayMaxOffset.toPx(currentContext) * progress))
        .scrollAwayTopPivot()
}

/**
 * `pivotFractionY = 0.0f` (`material3/ScrollAway.kt:210`): scaling away from the top edge instead of
 * the centre. A `View`'s default pivot is its centre, so only the y half needs writing, and it needs
 * writing as a constant because `pivotY = 0f` does not depend on the measured size.
 */
private fun Modifier.scrollAwayTopPivot(): Modifier =
    this.thenUnitViewAttribute<View>(uniqueKey) { pivotY = 0f }

/** `maxScrollOut` — `material3/ScrollAway.kt:257`, 36.dp of scroll that fully scrolls the content out. */
private val scrollAwayMaxScrollOut: Dp = 36.dp

/** `maxOffset` — `:258`, the 24.dp the content ends up lifted by. */
private val scrollAwayMaxOffset: Dp = 24.dp

/** `minMotionOut` / `maxMotionOut` — `:259-261`: "fade and scale effects are between 100% and 50%". */
private const val scrollAwayMinMotionOut = 1f
private const val scrollAwayMaxMotionOut = 0.5f

/** `timeTextVisibilityThreshold` — `:262-263`, past which an idle screen brings the content back. */
private const val scrollAwayTimeTextVisibilityThreshold = 0.55f

/**
 * `MotionTokens.EasingStandard` — `tokens/MotionTokens.kt`, `CubicBezierEasing(.2, 0, 0, 1)`. Declared
 * before the two specs below on purpose: top-level initializers run in declaration order, and a spec
 * that read a not-yet-initialised easing would capture its zero value.
 */
private val scrollAwayEasingStandard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** `MotionTokens.EasingStandardDecelerate` — same file, `CubicBezierEasing(0, 0, 0, 1)`. */
private val scrollAwayEasingStandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)

/**
 * `tween(MotionTokens.DurationShort4, MotionTokens.EasingStandard)` — `ScrollAway.kt:220-229`.
 * [MotionDurationTokens] carries the 200 ms; the two easings above are the `MotionTokens` entries this
 * file needs, and they are cubic-bézier, which hibari-animation has. (`MotionDurationTokens`' KDoc
 * declines the `PathEasing` entries; `ScrollAway` uses none of them.)
 */
private val scrollAwayProgressSpec: AnimationSpec<Float> = tween(
    durationMillis = MotionDurationTokens.DurationShort4,
    easing = scrollAwayEasingStandard,
)

/**
 * `tween(MotionTokens.DurationMedium1, EasingStandard | EasingStandardDecelerate)` —
 * `ScrollAway.kt:238-251`, which picks the decelerating curve on the way out (`targetAlpha <= 0.5`) and
 * the standard one on the way back in.
 */
private fun scrollAwayAlphaSpec(targetAlpha: Float): AnimationSpec<Float> = tween(
    durationMillis = MotionDurationTokens.DurationMedium1,
    easing = if (targetAlpha > 0.5f) scrollAwayEasingStandard else scrollAwayEasingStandardDecelerate,
)
