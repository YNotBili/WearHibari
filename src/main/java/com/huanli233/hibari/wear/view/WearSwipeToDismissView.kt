package com.huanli233.hibari.wear.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import com.huanli233.hibari.animation.LinearOutSlowInEasing
import com.huanli233.hibari.animation.TweenSpec
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.isUnspecified
import com.huanli233.hibari.wear.EdgeSwipeState
import com.huanli233.hibari.wear.SwipeToDismissBoxDefaults
import com.huanli233.hibari.wear.SwipeToDismissBoxState
import com.huanli233.hibari.wear.SwipeToDismissMath
import com.huanli233.hibari.wear.SwipeToDismissValue
import com.huanli233.hibari.wear.WearScreen
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Upstream's `SWIPE_TO_DISMISS_BOX_ANIMATION_SPEC` (`BasicSwipeToDismissBox.kt:642`), which is
 * `rememberSwipeToDismissBoxState`'s default (`:474`) and therefore the spec the sample takes.
 *
 * The port of it in `SwipeToDismiss.kt:668` is `private` to that file, and the state's own
 * constructor default is the *other* one — the spring at `:488` / `SwipeableV2.kt:666`, kept
 * verbatim — so a view that has to own a state of its own needs its own copy of the tween. A caller
 * that passes a state in keeps whatever spec that state was built with; this is only the fallback.
 */
private val SwipeToDismissViewAnimationSpec: TweenSpec<Float> =
    TweenSpec<Float>(200, 0, LinearOutSlowInEasing)

/**
 * The `ViewGroup` that turns [SwipeToDismissBoxState] into touch handling and pixels — the port of
 * `androidx.wear.compose.foundation.BasicSwipeToDismissBox` (`BasicSwipeToDismissBox.kt:109-253`,
 * with the `onDismissed` overload at `:296-319`), and the replacement for the platform
 * `androidx.wear.widget.SwipeDismissFrameLayout` that `ScreenScaffold` used to mount.
 *
 * ## The two slots
 *
 * Upstream emits its `content(isBackground)` twice, in a `repeat(2)` loop with `isBackground = it == 0`
 * (`:170-171`), inside a `Box` that also fills the viewport (`:126-142`). Here that is two children,
 * **in that order**: the background slot first, the foreground slot last. With a single child there is
 * no background slot, which is the shape of a screen that has nothing behind it.
 *
 * The background slot's *existence* upstream is conditional: `if (!isBackground ||
 * (userSwipeEnabled && isSwiping))` (`:174`) — at rest it is not composed at all. There is no
 * composition to run per frame here, so the condition is applied as visibility
 * (`View.GONE`/`View.VISIBLE`) in [applyVisualState], which reproduces what the user sees
 * (nothing measured, nothing laid out, nothing drawn, nothing focusable at rest) but is **not**
 * equivalent underneath: content remembered inside the background slot survives a swipe instead of
 * being created fresh on each one. Same class of gap upstream already has for `key(...)`
 * (`:173`, documented at `SwipeToDismiss.kt:43-46`).
 *
 * `Modifier.hierarchicalFocusGroup(!isBackground)` (`:181`) and
 * `CompositionLocalProvider(LocalScreenIsActive provides …)` (`:175-178`) have no Views counterpart
 * and are **not ported**: focus order follows child order here, and no "screen is active" local is
 * pushed into the background slot while it is offscreen.
 *
 * ## The gesture
 *
 * Upstream puts `Modifier.swipeableV2(state, Orientation.Horizontal, enabled = userSwipeEnabled)` on
 * the box itself (`:137-141`), which is just `Modifier.draggable` with
 * `startDragImmediately = state.isAnimationRunning` and `onDragStopped = { velocity -> launch {
 * state.settle(velocity) } }` (`SwipeableV2.kt:138-147`): a horizontal drag *anywhere in the box*
 * moves the offset. The left-edge band belongs to a different modifier, `edgeSwipeToDismiss`
 * (`:554-617`), which is applied to content that owns its own gestures and only intercepts a swipe
 * that begins within `edgeWidth` of the left edge (`:530-532`, `:585-609`), handing the delta to the
 * box through a nested scroll connection (`:378-455`).
 *
 * Views cannot copy that split literally, for two reasons: a parent sees touch events *before* its
 * children (there is no per-axis cooperative consumption to observe), and this module has no nested
 * scroll host — `edgeNestedScrollConnection` is already recorded as a gap at
 * `SwipeToDismiss.kt:412-413`. So the box owns the whole gesture, in two modes:
 *
 *  - [edgeSwipeEnabled] `= false` (the default) — upstream's *box*: intercept a horizontal drag that
 *    begins anywhere in the view, once it has passed the touch slop. Vertical drags are never taken,
 *    which is the stand-in for "the vertical scroll child consumed them first".
 *  - [edgeSwipeEnabled] `= true` — upstream's *edge* restriction, which is a rule about taking a
 *    gesture **away from a child**: [onInterceptTouchEvent] then only intercepts while
 *    [EdgeSwipeState] is `SwipingToDismiss`, i.e. the down landed inside [edgeWidth] and the next
 *    move went rightward (`:585-609`, transcribed as [SwipeToDismissMath.nextEdgeSwipeState]). A down
 *    outside the band means `SwipingToPage` and every child keeps everything (`:591`). The box's own
 *    drag is not gated by it, because upstream's `swipeableV2` is not: the edge machine lives on the
 *    content and only decides what the nested scroll connection forwards.
 *
 * A gesture that no child wanted at all still reaches [onTouchEvent] and moves the offset, which is
 * exactly the `swipeableV2` case (`SwipeableV2.kt:138-141`).
 *
 * Three deliberate translations, all of them because Views has no equivalent of Compose's pointer
 * machinery:
 *  1. slop: `abs(dx) > ViewConfiguration.scaledTouchSlop` and `abs(dx) > abs(dy)` gate interception,
 *     standing in for `detectDragGestures`' touch slop plus the fact that a child which consumed the
 *     drag never let the box see it. Compose's own slop source (`viewConfiguration.touchSlop`) is the
 *     same 8dp-into-px number, so the two agree.
 *  2. `startDragImmediately = state.isAnimationRunning` (`SwipeableV2.kt:145`) is honoured: while a
 *     settle animation is running a horizontal move intercepts without waiting for the slop.
 *  3. the drag ends the same way for up and cancel, and settle runs whenever a drag actually moved
 *     the offset. Upstream's `SwipeToDismissInProgress` exists only so `onPreFling` can decide to
 *     settle (`:427-439`); here [isDragging] plays that role. `Modifier.draggable`'s body
 *     (`androidx.compose.foundation.gestures`) is **not in this reference tree**, so whether a cancel
 *     reports the tracked velocity or zero cannot be checked; the tracked velocity is used for both.
 *
 * A drag that takes over cancels the running settle coroutine. That is the one job upstream's
 * `InternalMutatorMutex` does (`SwipeableV2.kt:234`, `:245-255`; a mutator interrupts another of
 * equal or lower priority, `InternalMutatorMutex.kt:47-48`) and which `SwipeToDismiss.kt:408-410`
 * records as dropped: the state itself serialises fine on the main thread, but only the view can
 * decide that the finger wins over the animation — `:145`'s `startDragImmediately` exists precisely
 * because a gesture is expected to begin mid-animation.
 *
 * `Modifier.systemGestureExclusion()` (`:131-136`, applied when swiping is enabled above API 33) is
 * ported as [View.setSystemGestureExclusionRects] over the full bounds, which is what the platform
 * API means by it; the platform clamps the request to its own edge budget. This is what keeps the
 * Android 14+ system back gesture from eating the swipe the box now owns. Nothing here registers an
 * `OnBackInvokedCallback`, so `enableOnBackInvokedCallback` in the app manifest does not double-dismiss
 * through this view: back stays with the navigator, the edge stays with us.
 *
 * Upstream's `semantics { horizontalScrollAxisRange = … }` hack
 * (`SwipeableV2.kt:104-133`), whose only purpose was to tell `AndroidComposeView`'s host whether the
 * system's `ScrollDismissLayout` should intercept, is **not ported** — with the gesture owned here
 * there is nobody left to answer.
 *
 * ## The pixels
 *
 * Draw order follows upstream's modifier chains, outermost modifier first
 * (`:184-229` for the foreground, `:231-239` for the background):
 *  1. the background slot, then `backgroundScrimColor` at `MAX_BACKGROUND_SCRIM_ALPHA * (1 - progress)`
 *     over it — always a rect, even on a round screen (`:238`);
 *  2. `backgroundScrimColor` at full alpha as the curtain *behind* the foreground (`:229`),
 *  3. the foreground slot, translated and scaled by
 *     [SwipeToDismissMath.contentTranslationXPx]/[SwipeToDismissMath.contentScale] written onto the
 *     child's own [View.translationX]/[View.scaleX]/[View.scaleY] (`:204-206`), which is exactly
 *     upstream's `translationX`/`scaleX`/`scaleY` on the layer, and scales around the centre because
 *     both Compose's layer origin and a `View`'s default pivot are the centre (`:184-207`);
 *  4. `contentScrimColor` at `min(progress / 2f, MAX_CONTENT_SCRIM_ALPHA)` over the foreground,
 *     `drawCircle` on a round screen and `drawRect` otherwise (`:210-221`);
 *  5. on a round screen while swiping, the foreground is clipped to a circle first (`:223-228`).
 *
 * Steps 2-4 happen inside the clip and are drawn over the *transformed* rect of the child, because
 * upstream's scrim and background live inside the same `graphicsLayer` as the translation and the
 * scale (`:184`, `:208`, `:223`, `:229`) — an axis-aligned uniform scale maps a full-bleed rect onto
 * the child's image, so this is the same coverage. [state] is read live at draw time, so there is no
 * stale value to keep in sync.
 *
 * Two numbers worth naming: the offset is measured against the *screen* width, never the view's
 * (`:118`, `:121`, and `SwipeToDismiss.kt:127-142`), so `contentTranslationXPx` and the scrims take
 * [SwipeToDismissBoxState.maxWidthPx] rather than `width`; and because this platform's alpha is
 * 8-bit, `progress / 2f`-style fractions quantise to 1/255 where Compose keeps them `Float`.
 *
 * ## The animation
 *
 * No `ValueAnimator`: the settle *is* [SwipeToDismissBoxState.settle], a suspend call running the spec
 * the state carries (`SwipeToDismiss.kt:626-641`, upstream `SwipeableV2.kt:435-462`), launched on
 * [animationScope]. Its shape — `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` — is
 * the one already used twice in this module, by `ExpandableState.animationScope`
 * (`lazy/WearExpandable.kt:78`) and `pickerAnimatorScope()` (`view/WearPickerViews.kt:1842-1843`);
 * `Main.immediate` because a resumption already on the main thread should not wait for another turn.
 * That scope is enough for the `withFrameNanos` inside `animate`: `hibari-runtime` falls back to
 * `DefaultMonotonicFrameClock`, a `Choreographer`-backed clock (`MonotonicFrameClock.kt:13-41`,
 * `:127-131`), so no clock has to be installed in the context.
 *
 * Reading the animated offset is the other half, and a `View` is not a composition: nothing invalidates
 * on the snapshot writes `settle` makes. So a `Choreographer.FrameCallback` ticker — the same device
 * `Placeholder.kt:285-364` uses for the same reason — calls [applyVisualState] once per frame while a
 * drag or a settle is in flight, and keeps running for one more frame after both are over so the
 * settled (or snapped-back) state is the one that gets drawn.
 *
 * Dismissal is reported through [onDismissed], because upstream's `LaunchedEffect(state.currentValue)`
 * (`:305-310`) has no host here. [SwipeToDismiss.kt:422-426] is explicit that whoever owns the box owes
 * those two statements in that order — snap back to `Default`, *then* the callback — which is why a
 * re-entered screen never shows a dismissed frame; that is done here at the end of the settle block.
 *
 * Everything is cancelled on the way out: [onDetachedFromWindow] cancels the settle job and drops the
 * frame callback, and replacing [state] (which is what a retune does) does both plus releases the
 * pointer and the gesture, so no animation from the previous state can write into the new one.
 *
 * ## What is *not* honoured
 *
 *  - `child.requestDisallowInterceptTouchEvent(...)`, the Views convention every swipe-intercepting
 *    container obeys: no such mechanism exists upstream (`Compose` reads per-event consumption
 *    instead), so obeying it would be an invention. [edgeSwipeEnabled] is the escape hatch a child
 *    has.
 *  - `backgroundKey`/`contentKey` ([SwipeToDismissKeys]) are inert, as recorded at
 *    `SwipeToDismiss.kt:43-46`.
 *  - `Modifier.swipeAnchors`' anchor-change reconciliation (`SwipeableV2.kt:181-190`) is not wired,
 *    because upstream's box never uses it: `BasicSwipeToDismissBox.kt:123` calls `updateAnchors` in a
 *    `SideEffect` and discards the "needs reconciling" result (`SwipeableV2.kt:360-372`). A rotation
 *    during a dismissal animation therefore leaves the animation aimed at the old screen width, here
 *    and upstream alike. [SwipeToDismissBoxState.updateAnchors] is still called from the measure pass
 *    with the map the `SideEffect` builds (`:119-124`), and skipped when the screen width and density
 *    have not moved, which is `SwipeableV2.kt:181`'s own `previousAnchors != newAnchors` test.
 *  - Toggling [isSwipeEnabled] mid-drag ends the drag by settling at zero velocity. Upstream's
 *    `enabled` feeds `Modifier.draggable` (`:140`), whose `pointerInput` restarts and abandons the
 *    gesture without settling, leaving the offset frozen wherever it was; freezing the foreground
 *    half off-screen is not worth copying.
 *
 * @see SwipeToDismissBoxState
 * @see SwipeToDismissMath
 */
class WearSwipeToDismissView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    /**
     * The state this box drives. One instance per screen, kept across retunes — upstream's
     * `rememberSwipeToDismissBoxState()` (`BasicSwipeToDismissBox.kt:299`, `:472-480`) is what a
     * caller hands in through a `thenViewAttribute`.
     *
     * Replacing it cancels everything in flight and forces a measure, because a fresh state has no
     * anchors and no offset until [onMeasure] installs them.
     */
    var state: SwipeToDismissBoxState = SwipeToDismissBoxState(SwipeToDismissViewAnimationSpec)
        set(value) {
            if (field === value) return
            endGesture()
            settleJob?.cancel()
            settleJob = null
            isSettling = false
            anchoredScreenWidthPx = Float.NaN
            anchoredDensity = Float.NaN
            field = value
            lastAnimationRunning = false
            lastTargetValue = SwipeToDismissValue.Default
            squeezeMode = true
            requestLayout()
            applyVisualState()
        }

    /**
     * Whether the swipe gesture is enabled — upstream's `userSwipeEnabled`
     * (`BasicSwipeToDismissBox.kt:100`, `:114`, `:140`). It gates three things at once, as upstream
     * does: the gesture (`SwipeableV2.kt:138-141`), the background slot (`:174`) and the system
     * gesture exclusion (`:131-136`).
     */
    var isSwipeEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                val wasDragging = isDragging
                endGesture()
                if (wasDragging) runSettle(0f)
                edgeSwipeState = EdgeSwipeState.WaitingForTouch
            }
            updateSystemGestureExclusion()
            applyVisualState()
        }

    /**
     * Invoked once a dismissal animation has *finished* on the far anchor, after the snap back to
     * [SwipeToDismissValue.Default] — upstream's `onDismissed` (`BasicSwipeToDismissBox.kt:297`,
     * called at `:305-310`).
     *
     * A plain settable property rather than an `add*Listener`: a lambda attribute is re-applied on
     * every retune (they are never equal), so an additive API would accumulate callbacks.
     */
    var onDismissed: (() -> Unit)? = null

    /**
     * Restrict the gesture to the left edge, the way `Modifier.edgeSwipeToDismiss` does
     * (`BasicSwipeToDismissBox.kt:530-532`, `:585-609`). Off by default, which is upstream's box
     * (`:137-141` — the whole box is the drag target).
     */
    var edgeSwipeEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            edgeSwipeState = EdgeSwipeState.WaitingForTouch
        }

    /**
     * Width of the left band that can start an edge swipe — `SwipeToDismissBoxDefaults.EdgeWidth`
     * (`BasicSwipeToDismissBox.kt:493`), the default of the `edgeWidth` parameter upstream's modifier
     * takes (`:556`). Read as `Dp.value * density`, i.e. `Dp.toPx()`, not the rounded
     * `Dp.toPx(context)` (`SwipeToDismiss.kt:127`).
     */
    var edgeWidth: Dp = SwipeToDismissBoxDefaults.EdgeWidth

    /**
     * Painted full-alpha behind the foreground content as it lifts away, and over the background slot
     * at [SwipeToDismissMath.backgroundScrimAlpha] (`BasicSwipeToDismissBox.kt:229`, `:231-238`).
     *
     * Upstream reads it from `LocalSwipeToDismissBackgroundScrimColor` (`:144`), which `MaterialTheme`
     * provides as `colorScheme.background` (`material3/MaterialTheme.kt:71`,
     * `material3/SwipeToDismissBox.kt:70`). A `View` has no composition locals, so it arrives as a
     * `thenViewAttribute` instead, and [Color.Unspecified] means that scrim is simply not painted.
     */
    var backgroundScrimColor: Color = Color.Unspecified
        set(value) {
            field = value
            invalidate()
        }

    /**
     * The `LocalSwipeToDismissContentScrimColor` of the same name (`BasicSwipeToDismissBox.kt:145`,
     * `material3/MaterialTheme.kt:72`, `material3/SwipeToDismissBox.kt:71`), drawn over the foreground
     * at [SwipeToDismissMath.contentScrimAlpha] (`:210-216`). [Color.Unspecified] skips it.
     */
    var contentScrimColor: Color = Color.Unspecified
        set(value) {
            field = value
            invalidate()
        }

    // region the two slots

    /** The background slot: index 0, the first `repeat(2)` iteration (`BasicSwipeToDismissBox.kt:170-171`). */
    private val backgroundChild: View?
        get() = if (childCount > 1) getChildAt(0) else null

    /** The foreground slot: the last child, drawn on top of everything else. */
    private val foregroundChild: View?
        get() = if (childCount == 0) null else getChildAt(childCount - 1)

    // endregion

    // region measure and layout

    private var anchoredScreenWidthPx: Float = Float.NaN
    private var anchoredDensity: Float = Float.NaN

    /** `isRoundDevice()` (`BasicSwipeToDismissBox.kt:143`, `materialcore/Resources.kt:40-43`). */
    private var isRoundScreen: Boolean = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The box is `Modifier.fillMaxSize()` (`:129`) and so is every slot (`:180`), so the box takes
        // the size its parent allows and every child is forced into its content box; a child's own
        // LayoutParams size is ignored on purpose, which is also why `matchParentSize()` is not
        // needed at the call site.
        val width = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)

        isRoundScreen = WearScreen.isRound(context)

        val configuration = resources.configuration
        val density = resources.displayMetrics.density
        // `maxWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }` (`:118`)
        // — the screen, not this view.
        val screenWidthPx = configuration.screenWidthDp * density
        if (screenWidthPx != anchoredScreenWidthPx || density != anchoredDensity) {
            anchoredScreenWidthPx = screenWidthPx
            anchoredDensity = density
            // The anchors the `SideEffect` installs (`:119-124`). The Boolean it returns is
            // discarded, exactly as `:123` discards it.
            state.updateAnchors(
                mapOf(
                    SwipeToDismissValue.Default to 0f,
                    SwipeToDismissValue.Dismissed to screenWidthPx,
                ),
                density,
            )
        }

        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val contentHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        val childWidthSpec = MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(childWidthSpec, childHeightSpec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val childLeft = paddingLeft
        val childTop = paddingTop
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.layout(childLeft, childTop, childLeft + child.measuredWidth, childTop + child.measuredHeight)
        }
        updateSystemGestureExclusion()
        applyVisualState()
    }

    /**
     * `Modifier.systemGestureExclusion()`, gated on the same API level and the same flag
     * (`BasicSwipeToDismissBox.kt:131-136`). Compose's own no-argument form is a
     * `androidx.compose.foundation` binary in this tree, so the rect it sets cannot be read here; the
     * full bounds are used, which is what the platform API asks for and what the clamp reduces to on
     * a watch-sized window.
     */
    private fun updateSystemGestureExclusion() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) return
        if (isSwipeEnabled && width > 0 && height > 0) {
            setSystemGestureExclusionRects(listOf(Rect(0, 0, width, height)))
        } else {
            setSystemGestureExclusionRects(emptyList())
        }
    }

    // endregion

    // region drawing

    private val scrimPaint = Paint()
    private val scrimRect = RectF()
    private val circleClipPath = Path()

    override fun dispatchDraw(canvas: Canvas) {
        val progress = state.progress
        val isSwiping = state.isSwiping
        val foreground = foregroundChild
        val drawingTime = getDrawingTime()

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (child === foreground) {
                drawForegroundSlot(canvas, child, progress, isSwiping, drawingTime)
            } else {
                drawBackgroundSlot(canvas, child, progress, drawingTime)
            }
        }
    }

    /** The background slot and the `MAX_BACKGROUND_SCRIM_ALPHA * (1 - progress)` rect over it (`:231-238`). */
    private fun drawBackgroundSlot(canvas: Canvas, child: View, progress: Float, drawingTime: Long) {
        drawChild(canvas, child, drawingTime)
        val alpha = SwipeToDismissMath.backgroundScrimAlpha(progress)
        if (alpha <= 0f || backgroundScrimColor.isUnspecified) return
        scrimPaint.color = backgroundScrimColor.copy(alpha = alpha).toArgb()
        // `drawRect`, upstream's own choice for this scrim even on a round screen (`:238`).
        scrimRect.set(
            child.left.toFloat(),
            child.top.toFloat(),
            child.right.toFloat(),
            child.bottom.toFloat(),
        )
        canvas.drawRect(scrimRect, scrimPaint)
    }

    /** The curtain, the translated and scaled foreground, the content scrim, all inside the round clip. */
    private fun drawForegroundSlot(
        canvas: Canvas,
        child: View,
        progress: Float,
        isSwiping: Boolean,
        drawingTime: Long,
    ) {
        setTransformedBounds(child)
        val clipped = isRoundScreen && isSwiping
        if (clipped) {
            // `graphicsLayer { if (isRound && isSwiping) { clip = true; shape = CircleShape } }` (`:223-228`).
            // A round Wear display is square, so the oval inscribed in these bounds is the circle
            // upstream clips to.
            circleClipPath.reset()
            circleClipPath.addOval(scrimRect, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(circleClipPath)
        }

        if (backgroundScrimColor.isSpecified) {
            scrimPaint.color = backgroundScrimColor.toArgb()
            canvas.drawRect(scrimRect, scrimPaint)
        }

        drawChild(canvas, child, drawingTime)

        val alpha = SwipeToDismissMath.contentScrimAlpha(progress)
        if (alpha > 0f && contentScrimColor.isSpecified) {
            scrimPaint.color = contentScrimColor.copy(alpha = alpha).toArgb()
            if (isRoundScreen) {
                canvas.drawOval(scrimRect, scrimPaint)
            } else {
                canvas.drawRect(scrimRect, scrimPaint)
            }
        }

        if (clipped) canvas.restore()
    }

    /**
     * The axis-aligned image of [child]'s bounds under its own scale-around-pivot plus translation —
     * the rect the scrims have to cover, since upstream draws them inside the same layer as the
     * transform (`BasicSwipeToDismissBox.kt:184-229`).
     */
    private fun setTransformedBounds(child: View) {
        val scaleX = child.scaleX
        val scaleY = child.scaleY
        val pivotX = child.pivotX
        val pivotY = child.pivotY
        val originX = child.left + child.translationX
        val originY = child.top + child.translationY
        scrimRect.set(
            originX + pivotX - pivotX * scaleX,
            originY + pivotY - pivotY * scaleY,
            originX + pivotX + (child.width - pivotX) * scaleX,
            originY + pivotY + (child.height - pivotY) * scaleY,
        )
    }

    // endregion

    // region applying the state to the children

    /**
     * Upstream's `var squeezeMode by remember { mutableStateOf(true) }` (`:158`) and the two
     * `LaunchedEffect`s keyed on `state.isAnimationRunning` and `state.targetValue` (`:159-168`).
     * There is no effect host, so the two "when this changed" keys are polled from
     * [applyVisualState], which runs on every frame of a drag or a settle.
     */
    private var squeezeMode = true
    private var lastAnimationRunning = false
    private var lastTargetValue = SwipeToDismissValue.Default

    private fun syncSqueezeMode() {
        val animationRunning = state.isAnimationRunning
        if (animationRunning != lastAnimationRunning) {
            lastAnimationRunning = animationRunning
            squeezeMode = SwipeToDismissMath.squeezeModeAfterAnimationChange(
                squeezeMode,
                state.targetValue,
            )
        }
        val target = state.targetValue
        if (target != lastTargetValue) {
            lastTargetValue = target
            squeezeMode = SwipeToDismissMath.squeezeModeAfterTargetChange(squeezeMode, target)
        }
    }

    /**
     * Write the current progress onto the children and invalidate. Called from the drag, from the
     * frame ticker, from [onLayout] and whenever a property that changes the picture is retuned.
     */
    private fun applyVisualState() {
        syncSqueezeMode()

        val progress = state.progress
        val maxWidthPx = state.maxWidthPx

        val foreground = foregroundChild
        if (foreground != null) {
            val translationX =
                SwipeToDismissMath.contentTranslationXPx(progress, maxWidthPx, squeezeMode)
            val scale = SwipeToDismissMath.contentScale(progress)
            // `this.translationX = translationX; scaleX = scale; scaleY = scale` (`:204-206`). Written
            // only when it moved: a redundant write dirties the child's render node for nothing.
            if (foreground.translationX != translationX) foreground.translationX = translationX
            if (foreground.scaleX != scale || foreground.scaleY != scale) {
                foreground.scaleX = scale
                foreground.scaleY = scale
            }
            // Compose's layer origin is the centre (`:184-207`), and so is a View's default pivot;
            // setting it explicitly keeps a background on the slot from moving the pivot elsewhere.
            val pivotX = foreground.width / 2f
            val pivotY = foreground.height / 2f
            if (foreground.pivotX != pivotX) foreground.pivotX = pivotX
            if (foreground.pivotY != pivotY) foreground.pivotY = pivotY
        }

        val background = backgroundChild
        if (background != null) {
            // `if (!isBackground || (userSwipeEnabled && isSwiping))` (`:174`).
            val target =
                if (isSwipeEnabled && state.isSwiping) View.VISIBLE else View.GONE
            if (background.visibility != target) background.visibility = target
        }

        invalidate()
    }

    // endregion

    // region touch

    private val viewConfiguration = ViewConfiguration.get(context)
    private val touchSlop = viewConfiguration.scaledTouchSlop
    private val maxFlingVelocityPxPerSecond = viewConfiguration.scaledMaximumFlingVelocity.toFloat()

    private var activePointerId = InvalidPointerId
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var isDragging = false
    private var velocityTracker: VelocityTracker? = null
    private var edgeSwipeState = EdgeSwipeState.WaitingForTouch

    private fun edgeWidthPx(): Float =
        if (edgeWidth.isUnspecified) 0f else edgeWidth.value * resources.displayMetrics.density

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!isSwipeEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginTracking(event)
                // Upstream's edge machine runs on every pointer change including the down
                // (`BasicSwipeToDismissBox.kt:578-592`), and the down is where the band is decided:
                // `if (change.position.x < edgeWidth.toPx()) EdgeClickedWaitingForDirection else
                // SwipingToPage` (`:588-591`).
                advanceEdgeSwipeState(downX, changedToUp = false)
                return false
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                velocityTracker?.addMovement(event)
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return false
                val x = event.getX(index)
                val y = event.getY(index)
                advanceEdgeSwipeState(x, changedToUp = false)
                if (isDragging) return true
                if (!shouldStartDrag(x, y, stealingFromChild = true)) return false
                beginDrag()
                dragTo(x)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // The machine has to see the lift, or the next down is classified from a stale
                // `EdgeClickedWaitingForDirection` — upstream resets it on `changedToUp()` for every
                // gesture, dismissed or not (`BasicSwipeToDismissBox.kt:604-609`).
                val index = event.findPointerIndex(activePointerId)
                val x = if (index >= 0) event.getX(index) else event.x
                advanceEdgeSwipeState(x, changedToUp = true)
                endGesture()
                return false
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isSwipeEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Reached only when no child wanted the down, which is the case upstream's
                // `swipeableV2` handles (`SwipeableV2.kt:138-141`).
                if (!isDragging) {
                    beginTracking(event)
                    advanceEdgeSwipeState(downX, changedToUp = false)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                velocityTracker?.addMovement(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)
                advanceEdgeSwipeState(x, changedToUp = false)
                if (!isDragging) {
                    if (shouldStartDrag(x, y, stealingFromChild = false)) {
                        beginDrag()
                        dragTo(x)
                    }
                } else {
                    dragTo(x)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val index = event.findPointerIndex(activePointerId)
                val x = if (index >= 0) event.getX(index) else event.x
                advanceEdgeSwipeState(x, changedToUp = true)
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
     * The box's own horizontal-drag test (`SwipeableV2.kt:138-147` — a horizontal `draggable` on the
     * box). `stealingFromChild` is `true` only from [onInterceptTouchEvent], where upstream's
     * `edgeSwipeToDismiss` band applies: with [edgeSwipeEnabled] the gesture has to have been
     * classified `SwipingToDismiss` by [SwipeToDismissMath.nextEdgeSwipeState] before the box takes it
     * away from a child (`BasicSwipeToDismissBox.kt:585-609`). The box's own drag is not gated by the
     * band, because upstream's `swipeableV2` is not either.
     *
     * `state.isAnimationRunning` waives the slop — upstream's `startDragImmediately`
     * (`SwipeableV2.kt:145`).
     */
    private fun shouldStartDrag(x: Float, y: Float, stealingFromChild: Boolean): Boolean {
        val dx = abs(x - downX)
        if (dx <= abs(y - downY)) return false
        if (dx <= touchSlop && !state.isAnimationRunning) return false
        if (stealingFromChild && edgeSwipeEnabled && edgeSwipeState != EdgeSwipeState.SwipingToDismiss) {
            return false
        }
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

    /** A drag takes the offset over from any running settle animation; see the class KDoc. */
    private fun beginDrag() {
        isDragging = true
        settleJob?.cancel()
        settleJob = null
        startTicking()
    }

    /** `DragScope.dragBy(pixels) { dispatchRawDelta(pixels) }` (`SwipeableV2.kt:240-242`). */
    private fun dragTo(x: Float) {
        val delta = x - lastX
        lastX = x
        if (delta == 0f) return
        // The leftover is dropped, as `dragBy` drops it.
        state.dispatchRawDelta(delta)
        applyVisualState()
    }

    private fun advanceEdgeSwipeState(x: Float, changedToUp: Boolean) {
        if (!edgeSwipeEnabled) return
        edgeSwipeState = SwipeToDismissMath.nextEdgeSwipeState(
            current = edgeSwipeState,
            x = x,
            previousX = lastX,
            edgeWidthPx = edgeWidthPx(),
            changedToUp = changedToUp,
        )
    }

    /** px per second, the unit `SwipeableV2State.settle`'s velocity is in (`SwipeableV2.kt:435`). */
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
     * The scope the settle/snap suspends in — `SupervisorJob() + Dispatchers.Main.immediate`, the
     * shape `ExpandableState` and the pickers already use (`lazy/WearExpandable.kt:78`,
     * `view/WearPickerViews.kt:1842-1843`).
     *
     * It is never cancelled, only its jobs are: a `View` that is detached and re-attached has no
     * "destroy" to react to, and a scope with no live coroutine holds nothing.
     */
    private val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settleJob: Job? = null
    private var isSettling = false
    private var isTicking = false
    private val ticker = Ticker()

    private inner class Ticker : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            isTicking = false
            applyVisualState()
            // One frame past the end of the gesture and the end of the animation, so the last write
            // to the offset is the one that gets drawn — `Placeholder.kt:340-349` retires on the same
            // reasoning.
            if (isDragging || isSettling || state.isAnimationRunning) startTicking()
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
     * `onDragStopped = { velocity -> launch { state.settle(velocity) } }` (`SwipeableV2.kt:146`),
     * followed by the `onDismissed` overload's `LaunchedEffect(state.currentValue) { snapTo(Default);
     * onDismissed() }` (`BasicSwipeToDismissBox.kt:305-310`) in the order `SwipeToDismiss.kt:422-426`
     * asks for.
     *
     * Guarded by [SwipeToDismissBoxState.hasAnchorForValue] because `settle` reads `requireOffset()`
     * and `requireDensity()`, both of which only exist once [onMeasure] has installed the anchors.
     */
    private fun runSettle(velocityPxPerSecond: Float) {
        if (!state.hasAnchorForValue(SwipeToDismissValue.Dismissed)) return
        settleJob?.cancel()
        isSettling = true
        startTicking()
        settleJob = animationScope.launch {
            try {
                state.settle(velocityPxPerSecond)
                if (state.currentValue == SwipeToDismissValue.Dismissed) {
                    state.snapTo(SwipeToDismissValue.Default)
                    onDismissed?.invoke()
                }
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
        isSettling = false
        endGesture()
        super.onDetachedFromWindow()
    }

    // endregion

    /**
     * `FrameLayout.LayoutParams` so a `BoxScope.gravity` written on a slot still lands on a
     * `LayoutParams` that has a `gravity` field (`thenLayoutAttribute` mutates the child's real
     * params, `hibari-ui/Attribute.kt:84-96`; same reasoning as `WearInsetLayoutView.LayoutParams`).
     *
     * Both slots are forced to the box's content box by [onMeasure], because upstream's slots are
     * `Box(Modifier.fillMaxSize())` (`:180`), so neither the size nor the gravity carried here moves
     * anything — it is here so a caller writing those params cannot hit a `ClassCastException`.
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
    }
}
