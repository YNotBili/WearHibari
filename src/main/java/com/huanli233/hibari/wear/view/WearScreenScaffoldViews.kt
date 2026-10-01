package com.huanli233.hibari.wear.view

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.view.View
import com.huanli233.hibari.animation.AnimationSpec
import com.huanli233.hibari.animation.animate
import com.huanli233.hibari.ui.layout.Alignment
import com.huanli233.hibari.ui.unit.IntSize
import com.huanli233.hibari.ui.unit.LayoutDirection
import com.huanli233.hibari.wear.EdgeButtonVerticalPadding
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One tune's worth of the inputs the `dynamicHeight` reveal of `material3/ScreenScaffold.kt` runs on,
 * handed to [WearScreenScaffoldEdgeButtonBandView] as one immutable value: the
 * `(isScrollInProgress, targetHeight)` pair whose changes the `snapshotFlow` at `:647-649` reacts to,
 * plus its threshold (`:659-662`, upstream's 16.dp at `:985`) and spring (`:666`, upstream's
 * `StiffnessMediumLow` at `:986-987`), with both heights resolved to px.
 *
 * The `ScreenScaffold` edge-button overload is its only constructor: one
 * [com.huanli233.hibari.wear.ScrollInfoProvider] read per tune. `AlertDialogContent`'s `edgeButton`
 * body does not build one — a dialog is handed no provider, so its slot keeps upstream's static
 * placement instead of this reveal. The provider itself is deliberately not handed to the view, so no
 * per-frame state write of the same number can retune the scaffold subtree.
 */
data class ScreenScaffoldEdgeButtonMotion(
    val targetHeightPx: Float,
    val isScrollInProgress: Boolean,
    val thresholdPx: Float,
    val animationSpec: AnimationSpec<Float>,
)

/**
 * One tune's worth of what `AnimatedIndicator` (`material3/Scaffold.kt:152-188`) reacts to: the
 * boolean its `isVisible()` lambda returns, fed to [WearScreenScaffoldIndicatorHostView] so the fade
 * runs on the view's clock — the `snapshotFlow { if (isVisible()) 1f else 0f }` + `animate` pair of
 * `:167-181` — and no state read inside the scaffold's own tune subscribes the subtree to the fade.
 */
data class ScreenScaffoldIndicatorFade(
    val isVisible: Boolean,
    val animationSpec: AnimationSpec<Float>,
)

/**
 * The band the `ScreenScaffold` edge-button slot is measured into: upstream's
 * `Modifier.align(Alignment.BottomCenter).dynamicHeight(onIntrinsicHeightMeasured) { height }`
 * (`material3/ScreenScaffold.kt:590-606`) fused with the `SubcomposeLayout` placement that puts the
 * button slot over the content (`:626-639`), plus the `LaunchedEffect` that decides between the
 * snapped and the animated height (`:641-679`).
 *
 * The node it hosts is placed like upstream's measurable: measured against
 * `Constraints(maxWidth, maxWidth, h, h)` (`:869-870`) — full width, the band's height — and placed
 * bottom-centred (`:875-882`). One deviation, mechanical: upstream's band height *includes* the
 * button's own `padding(vertical = EdgeButtonVerticalPadding)` (`:594-606` feeding
 * `EdgeButton.kt:184` and the `.layout` at `:185-211`), and this port's
 * [com.huanli233.hibari.wear.EdgeButton] folds that padding into its
 * *measured* height outside the box it draws
 * (`view/WearEdgeButtonView.kt:186-194`), so the band subtracts `2 × 3.dp` from the height it hands
 * the child and the subtree ends up the same `h` tall as upstream's. A slot that is not an
 * `EdgeButton` loses those 6 dp of band instead — the slot is documented for `EdgeButton`. Below 6 dp
 * the reverse also holds: [com.huanli233.hibari.wear.EdgeButton] reports its folded padding even at a
 * drawn height of 0, so the child is 6 dp tall where upstream's clamps to 0 — at a band that short
 * the button's own container fade is 0 alpha, so nothing paints differently.
 *
 * The height rules are `:647-679` verbatim: while `isScrollInProgress` the target is snapped to and
 * any running animation is stopped; once it is not, a target further than
 * `EDGE_BUTTON_HEIGHT_ANIMATION_THRESHOLD` (16.dp, `:985`) away animates with
 * `DEFAULT_EDGE_BUTTON_ANIMATION_SPEC` (a `StiffnessMediumLow` spring, `:986-987`), and anything
 * closer snaps. The target itself arrives once per tune as [motion] — the tune-time read of
 * `provider.lastItemOffset` documented at the call site in `Scaffold.kt`, the consumption pattern
 * `Modifier.scrollAway` already publishes (`ScrollAway.kt:128-141`) — and this view carries it between
 * tunes on its own clock, so a reveal costs no retune.
 *
 * Two observable differences follow from that, and they are this view's whole cost:
 *  - Upstream reads the target again **inside the measure pass** (`:601-602`, `:864`), so a fling that
 *    keeps moving `lastItemOffset` moves the band every frame without recomposing. Here a new target
 *    only exists when the tune re-runs and delivers a new [motion], so the reveal follows the list at
 *    the cadence of whatever re-tunes it and holds at the last delivered height in between — the same
 *    limitation `scrollAway` records for its own numbers (`ScrollAway.kt:132-135`).
 *  - Upstream's snapped height during a scroll is `currentEdgeButtonTargetHeight` read live, and this
 *    view's is the snapped [motion] target. Identical while every tune carries the current value.
 *
 * The spring keeps its momentum the way upstream's `Animatable` does, through [velocityPxPerSec]:
 * `animateTo`'s `initialVelocity` defaults to the live `velocity`, read at the call site *before* the
 * interruption cancels the running animation (`Animatable.kt:210`, `:281`), so a target that changes
 * mid-spring continues from the velocity it had rather than restarting from rest. Every snap zeroes it
 * because `snapTo` and `stop` both run `endAnimation()` (`Animatable.kt:334-338`, `:359`, `:382`).
 *
 * [motion] is null only in the window before the first attribute is applied and after
 * [onDetachedFromWindow]; the provider-less `ScreenScaffold` slot never routes through this view at
 * all (that case is the two-box shape in `Scaffold.kt`), so the natural-height measure below is a
 * first-frame fallback, not a ported mode.
 */
class WearScreenScaffoldEdgeButtonBandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * The live band target. Gaining, losing or replacing it applies upstream's pair-rule immediately,
     * the way the `snapshotFlow` pair (`ScreenScaffold.kt:647-650`) fires on every change of either
     * half. `null` is the before-first-attribute and after-detach state, which measures the slot at
     * its natural height.
     */
    internal var motion: ScreenScaffoldEdgeButtonMotion? = null
        set(value) {
            field = value
            applyMotion(value)
        }

    /**
     * `Animatable(currentEdgeButtonTargetHeight)` (`:584`): the first pair lands without an
     * animation, which this sentinel -1 makes the second pair's rule see the initial snap instead of
     * a spring from zero.
     */
    private var animatedHeightPx = -1f

    /**
     * The band's spring velocity, kept between animations because upstream's is kept by the
     * `Animatable` it calls `animateTo` on: `initialVelocity` defaults to the live `velocity` and is
     * read at the call site, before the interruption cancels the running animation
     * (`Animatable.kt:210`, `:281`), so a target that changes mid-spring continues from it instead of
     * restarting from rest. Both the finished and the cancelled path then zero it — `endAnimation()`
     * (`Animatable.kt:302`, `:306`, `:334-338`), which is also what `snapTo` and `stop` run
     * (`:359`, `:382`).
     */
    private var velocityPxPerSec = 0f
    private var animJob: Job? = null
    private val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun applyMotion(m: ScreenScaffoldEdgeButtonMotion?) {
        // One delivered pair is one `snapshotFlow` emission, and `collectLatest` (`:650`) cancels the
        // previous block before the new body runs — which is the only reason `:669-676`'s
        // "nothing running" snap can ever fire. The start velocity is read first, mirroring the order
        // `animateTo`'s default argument is evaluated in.
        val fromVelocityPxPerSec = velocityPxPerSec
        animJob?.cancel()
        animJob = null
        velocityPxPerSec = 0f
        if (m == null) {
            animatedHeightPx = -1f
            requestLayout()
            return
        }
        if (animatedHeightPx < 0f) {
            animatedHeightPx = m.targetHeightPx
            requestLayout()
            return
        }
        if (m.isScrollInProgress) {
            // `:651-657`: `stop()` then `snapTo`, both of which are "cancel the job, write the value".
            if (animatedHeightPx != m.targetHeightPx) {
                animatedHeightPx = m.targetHeightPx
                requestLayout()
            }
        } else if (abs(m.targetHeightPx - animatedHeightPx) > m.thresholdPx) {
            // `:658-668`: `animateTo` with the spring. This port's `animate` takes the value from the
            // live [animatedHeightPx] and the velocity from [fromVelocityPxPerSec], which is what
            // `Animatable` resumes from.
            animJob = animationScope.launch {
                try {
                    animate(
                        animatedHeightPx,
                        m.targetHeightPx,
                        fromVelocityPxPerSec,
                        m.animationSpec,
                    ) { value, velocity ->
                        velocityPxPerSec = velocity
                        if (animatedHeightPx != value) {
                            animatedHeightPx = value
                            requestLayout()
                        }
                    }
                    // Reached only when the spring ran to its end; a cancellation throws past it and
                    // leaves the last frame's velocity for the next start to read.
                    velocityPxPerSec = 0f
                } finally {
                    animJob = null
                }
            }
        } else if (animatedHeightPx != m.targetHeightPx) {
            // `:669-676`: inside the threshold and nothing running — snap.
            animatedHeightPx = m.targetHeightPx
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
        val density = resources.displayMetrics.density
        // The button's own padding, which upstream keeps inside the band (`:594-606` +
        // `EdgeButton.kt:184`, the 3.dp at `:629`) and this port's view keeps outside its measured
        // height (`WearEdgeButtonView.kt:186-194`). See the class KDoc.
        val buttonPaddingPx = (EdgeButtonVerticalPadding.value * density * 2f).roundToInt()
        val bandHeightPx = if (motion == null || animatedHeightPx < 0f) {
            -1
        } else {
            animatedHeightPx.roundToInt().coerceAtLeast(0)
        }
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (bandHeightPx < 0) {
                child.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST),
                )
            } else {
                child.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(
                        (bandHeightPx - buttonPaddingPx).coerceAtLeast(0),
                        MeasureSpec.EXACTLY,
                    ),
                )
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            // `IntOffset((wrapperWidth - placeable.width) / 2, wrapperHeight - placeable.height)`
            // (`ScreenScaffold.kt:876-881`), the wrapper being this full-size band.
            val x = (width - child.measuredWidth) / 2
            val y = height - child.measuredHeight
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    override fun onDetachedFromWindow() {
        animJob?.cancel()
        animJob = null
        // Back to the "no pair has landed" sentinel rather than keeping the interrupted value:
        // upstream's `remember { Animatable(currentEdgeButtonTargetHeight) }` (`:584`) is re-created
        // from the current target when the subtree comes back, so a re-attach snaps once instead of
        // springing from a height the reveal has long since left behind.
        animatedHeightPx = -1f
        velocityPxPerSec = 0f
        super.onDetachedFromWindow()
    }

    // FrameLayout's default child params already carry `gravity`, so a `BoxScope.gravity` written by
    // a slot is a `FrameLayout.LayoutParams` this view casts safely.
}

/**
 * The placement wrapper for `ScreenScaffold`'s scroll indicator slot: upstream's
 * `AnimatedIndicator(…, modifier = Modifier.align(Alignment.CenterEnd))`
 * (`material3/ScreenScaffold.kt:759-767`, the alignment at `:765`). The fade half of
 * `AnimatedIndicator` runs on this view's clock, not the tune's: [fade] carries the boolean
 * `isVisible()` returned (`material3/Scaffold.kt:167-169`) and the spring
 * `INDICATOR_FADE_OUT_ANIMATION` (`material3/Scaffold.kt:156`, `:231-232`) that the
 * `ScreenScaffold` call site takes as its default, and the alpha between the two is animated here
 * (`Scaffold.kt:170-181`), starting hidden exactly like `remember { mutableFloatStateOf(0f) }`
 * (`:166`). All this view adds on top of that is the placement the swipe host cannot give a child:
 * [WearSwipeToDismissView] forces every child into the content box at the top-left corner (its
 * `onMeasure` doc states the slots are `fillMaxSize` upstream), so an end-alignment needs a container
 * that resolves it itself — the same reason
 * [com.huanli233.hibari.wear.WearPagerIndicatorSlotView] exists for the pager
 * scaffold, with the pager bindings replaced by the one [fade] value. What `isVisible` is made of —
 * and which term of upstream's it cannot have — is documented at the call site in `Scaffold.kt`.
 */
class WearScreenScaffoldIndicatorHostView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        // `remember { mutableFloatStateOf(0f) }` (`material3/Scaffold.kt:166`): the animated alpha
        // starts hidden, so a first delivered value of `true` fades *in* the way the `snapshotFlow`'s
        // first emission does and a first value of `false` never flashes at full alpha on its way to
        // 0. A `View`'s own default is 1, hence the explicit write.
        alpha = 0f
    }

    /**
     * The one tune's worth of `isVisible` + spec the fade reads, applied as it changes. The alpha
     * between the two values is this view's own, exactly as `alphaValue` (`Scaffold.kt:166`) is
     * upstream's: a replacement that carries the same `isVisible` restarts nothing.
     */
    var fade: ScreenScaffoldIndicatorFade? = null
        set(value) {
            if (field == value) return
            field = value
            if (value == null) {
                // `AnimatedIndicator` without a spec and without `isVisible` — the provider-less
                // branch never reaches this view (`ScreenScaffold.kt:768` calls the slot raw), but
                // losing the attribute must not strand a half-faded indicator.
                fadeJob?.cancel()
                fadeJob = null
                alpha = 1f
            } else {
                syncFade(value)
            }
        }

    private var lastVisible: Boolean? = null
    private var fadeJob: Job? = null
    private val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * `snapshotFlow { … }.distinctUntilChanged().collectLatest { animate(…) }`
     * (`material3/Scaffold.kt:167-181`) as a change test on the boolean that drives it: same value,
     * nothing to do; changed value, cancel the running fade and animate from the live alpha — what
     * `animate(alphaValue.floatValue, targetValue, …)` at `:172-174` starts from.
     */
    private fun syncFade(fade: ScreenScaffoldIndicatorFade) {
        if (lastVisible == fade.isVisible) return
        lastVisible = fade.isVisible
        fadeJob?.cancel()
        val target = if (fade.isVisible) 1f else 0f
        if (alpha == target) return
        fadeJob = animationScope.launch {
            try {
                animate(alpha, target, 0f, fade.animationSpec) { value, _ -> alpha = value }
            } finally {
                fadeJob = null
            }
        }
    }

    private var childLeft = 0
    private var childTop = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
        val child = singleChild() ?: return
        // The indicator sizes itself (`.size(boxSize)`), so it wraps inside `AT_MOST` bounds — the
        // wrap-content half of the `Box` upstream's `align` runs inside.
        child.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST),
        )
        val offset = Alignment.CenterEnd.align(
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

    override fun onDetachedFromWindow() {
        fadeJob?.cancel()
        fadeJob = null
        lastVisible = null
        super.onDetachedFromWindow()
    }

    private fun singleChild(): View? =
        if (childCount == 0) null else getChildAt(0).takeUnless { it.visibility == View.GONE }
}
