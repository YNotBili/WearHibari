package com.huanli233.hibari.wear

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import com.huanli233.hibari.animation.CubicBezierEasing
import com.huanli233.hibari.animation.Easing
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.RectangleShape
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.wear.tokens.MotionDurationTokens
import com.huanli233.hibari.wear.tokens.ShapeTokens
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Ported from androidx.wear.compose.material3.Placeholder.
 *
 * The upstream file is a pair of `Modifier` extensions plus the state they coordinate: there is no
 * `Placeholder` composable, no `Outlined` variant and no literal sizes in it - the skeleton is the
 * shape of whatever component carries it, filled at `PlaceholderDefaults.color`, and the shimmer is
 * a 45 degree sweep across the whole screen. Everything in that file is ported except where noted
 * on the individual members.
 */

/**
 * The upstream `Modifier.placeholder`: draws [shape] filled with [color] wherever the component has
 * no provisional content, cross-fading in over the reset animation and wiping off over the wipe-off
 * animation.
 *
 * Deviations, both of them consequences of drawing on a `View` instead of a `DrawScope`:
 *  - Upstream draws with `drawWithContent { rect; drawContent() }`, i.e. *behind* the component's
 *    own content. A `View` only gives a hook below the background or above everything, so this uses
 *    the overlay: the skeleton lands on top. With the default 10%-alpha colour the difference is a
 *    faint wash over whatever the loading component does render, which is the same reading.
 *  - The `Modifier.graphicsLayer { alpha = ... }` upstream hangs on the same modifier (fading the
 *    real content while the placeholder is up) is not ported. Hibari has no per-modifier layer, and
 *    the only whole-view knob - `View.alpha` - would fade the skeleton with it, since a `View`'s
 *    overlay is part of its own render node, and would fight `Modifier.alpha`.
 *  - [color] and [shape] keep upstream's values but [color] is nullable: a `@Tunable` default
 *    expression is hoisted into a non-`@Tunable` method, so the theme colour is resolved in the body.
 *
 * @param placeholderState the state used to coordinate several placeholder effects.
 * @param shape the shape of the placeholder.
 * @param color the color to use in the placeholder; defaults to [PlaceholderDefaults.color].
 */
@Tunable
fun Modifier.placeholder(
    placeholderState: PlaceholderState,
    shape: Shape = PlaceholderDefaults.shape,
    color: Color? = null,
): Modifier = placeholderSkeletonAttrs(
    PlaceholderOverlaySpec(
        state = placeholderState,
        shape = shape,
        color = color ?: PlaceholderDefaults.color,
        reduceMotion = wearReduceMotionEnabled(currentContext),
    ),
)

/**
 * The upstream `Modifier.placeholderShimmer`: a 45 degree gradient from the top-left of the *screen*
 * to its bottom-right, swept across every placeholder at the same time by reading a shared frame
 * clock. Drawn on top of the component, which is exactly where upstream draws it.
 *
 * Nothing is drawn while [placeholderState] is not visible, nor while a reveal animation is running,
 * nor under reduce-motion - all three are upstream's own conditions.
 *
 * Upstream reads `LocalConfiguration` and `LocalDensity` for the screen size and
 * `LayoutCoordinates.positionInRoot()` for this component's offset; this reads
 * `resources.configuration` and walks the view up to its root at draw time, which is the same pair
 * of numbers.
 *
 * @param placeholderState the current placeholder state that determine whether the placeholder
 *   shimmer should be shown.
 * @param shape the shape of the component.
 * @param color the color to use in the shimmer; defaults to [PlaceholderDefaults.shimmerColor].
 */
@Tunable
fun Modifier.placeholderShimmer(
    placeholderState: PlaceholderState,
    shape: Shape = PlaceholderDefaults.shape,
    color: Color? = null,
): Modifier {
    val reduceMotion = wearReduceMotionEnabled(currentContext)
    val spec = if (reduceMotion) {
        null
    } else {
        PlaceholderOverlaySpec(
            state = placeholderState,
            shape = shape,
            color = color ?: PlaceholderDefaults.shimmerColor,
            reduceMotion = false,
        )
    }
    // Always emitted, null included: dropping the attribute wholesale would leave a stale drawable on
    // an already-built view, which upstream's recomposition cannot.
    return placeholderShimmerAttrs(spec)
}

/**
 * Creates a [PlaceholderState] that is remembered across tunations.
 *
 * A [PlaceholderState] should be created for each component that has placeholder data, such as a
 * [Card] or a [Button]. The state is used to coordinate all of the different placeholder effects
 * and animations.
 *
 * @param isVisible the initial state of the placeholder.
 */
@Tunable
fun rememberPlaceholderState(isVisible: Boolean): PlaceholderState {
    val state = remember { PlaceholderState(isVisible) }
    // Upstream applies this from a SideEffect once the composition commits; a retune is that moment
    // here, and the setter ignores a value that has not changed.
    state.isVisible = isVisible
    return state
}

/**
 * Contains the default values used for providing placeholders.
 *
 * There are two distinct but coordinated aspects to placeholders. Firstly [placeholder] which is
 * drawn instead of content that is not yet loaded. Secondly a placeholder shimmer effect
 * [placeholderShimmer] which runs in an animation loop while waiting for the data to load.
 */
object PlaceholderDefaults {
    /** Default [Shape]: upstream's `ShapeTokens.CornerFull`, a circle. */
    val shape: Shape
        get() = ShapeTokens.CornerFull

    /** Default [Color] for [placeholder]. */
    val color: Color
        @Tunable get() = MaterialTheme.colorScheme.onSurface
            .copy(alpha = 0.1f)
            .compositeOver(MaterialTheme.colorScheme.surfaceContainer)

    /** Default [Color] for [placeholderShimmer]. */
    val shimmerColor: Color
        @Tunable get() = MaterialTheme.colorScheme.onSurface
}

/**
 * A state object that can be used to control placeholders. Placeholders are used when the content
 * that needs to be displayed in a component is not yet available, e.g. it is loading asynchronously.
 *
 * A [PlaceholderState] should be created for each component that has placeholder data. The state is
 * used to coordinate all of the different placeholder effects and animations. The state should be
 * created and remembered (maybe using [rememberPlaceholderState]), and as needed the [isVisible]
 * property should be updated to show/hide the placeholder.
 *
 * Deviations:
 *  - Upstream reads the derived alphas from a `mutableStateOf` frame clock that `AppScaffold` owns
 *    (`AnimationCoordinator`) and lets recomposition re-run the draw lambdas. Hibari's draw happens
 *    in a `Drawable`, so the state keeps a list of registered drawables and pokes them every frame;
 *    the clock is per state rather than per screen, which only changes when two components with
 *    different states share a shimmer loop.
 *  - `@RememberInComposition` has no counterpart, so the constructor is callable anywhere.
 *
 * @param isVisible whether the placeholder will be displayed. This should be modified later
 *   updating the state using [PlaceholderState.isVisible]
 */
class PlaceholderState(isVisible: Boolean) {

    private var currentVisible: Boolean = isVisible
    private val invalidators = ArrayList<PlaceholderInvalidator>()
    private val animationHelper = PlaceholderAnimationHelper()

    private val wipeOffInterpolator: Easing = CubicBezierEasing(0f, 0.2f, 1f, 0.6f)
    private val resetFadeInInterpolator: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    private val resetFadeOutInterpolator: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /**
     * Whether the placeholder should be visible. Note that if there is an animation running, this
     * is the target state for the animation.
     */
    var isVisible: Boolean
        get() = currentVisible
        set(newVisible) {
            // We don't want to both register for changes in _visible and make them.
            if (newVisible == currentVisible) return
            currentVisible = newVisible
            animationHelper.startAnimation(
                duration = if (newVisible) {
                    PLACEHOLDER_RESET_ANIMATION_DURATION_MS
                } else {
                    PLACEHOLDER_WIPE_OFF_PROGRESSION_DURATION_MS
                },
            )
            animationHelper.tickIfNeeded()
        }

    /** Returns true while there is an animation in progress to show/hide the placeholders. */
    internal val isAnimationRunning: Boolean
        get() = animationHelper.isAnimationRunning()

    internal val frameTimeMillis: Long
        get() = animationHelper.frameTimeMillis()

    // Called by Modifiers using this state to coordinate animations
    internal fun register(invalidator: PlaceholderInvalidator) {
        if (!invalidators.contains(invalidator)) {
            invalidators.add(invalidator)
            animationHelper.register()
        }
        animationHelper.tickIfNeeded()
    }

    internal fun unregister(invalidator: PlaceholderInvalidator) {
        if (invalidators.remove(invalidator)) {
            animationHelper.unregister()
        }
    }

    /**
     * The current value of the placeholder wipe off visual effect gradient progression alpha. The
     * progression is a 45 degree angle sweep across the whole screen running from outside of the
     * Top|Left of the screen to Bottom|Right used as the anchor for wipe-off gradient effects.
     *
     * The time taken for this progression is 80ms.
     */
    internal val placeholderWipeOffAlpha: Float
        get() = wipeOffInterpolator.transform(
            (animationHelper.animationProgress() / PLACEHOLDER_WIPE_OFF_PROGRESSION_ALPHA_RELATIVE)
                .coerceAtMost(1f),
        )

    /**
     * The current value of the placeholder visual effect gradient progression alpha/opacity during
     * the fade-in part of reset placeholder animation. This allows the effect to be faded in during
     * 450ms.
     */
    internal val resetPlaceholderFadeInAlpha: Float
        get() = ((animationHelper.animationProgress() - PLACEHOLDER_RESET_ANIMATION_OUTGOING_DURATION_RELATIVE) /
            (1f - PLACEHOLDER_RESET_ANIMATION_OUTGOING_DURATION_RELATIVE))
            .let { absoluteProgression ->
                if (absoluteProgression < 0f) {
                    0f
                } else {
                    // lerp(0.1f, 1f, absoluteProgression), spelled out.
                    resetFadeInInterpolator.transform(0.1f + 0.9f * absoluteProgression)
                }
            }

    /**
     * The current value of the placeholder visual effect gradient progression alpha/opacity during
     * the fade-out part of reset placeholder animation. This allows the effect to be faded out
     * during 450ms.
     */
    internal val resetPlaceholderFadeOutAlpha: Float
        get() = run {
            val absoluteProgression =
                animationHelper.animationProgress() / PLACEHOLDER_RESET_ANIMATION_OUTGOING_DURATION_RELATIVE
            resetFadeOutInterpolator.transform(1f - absoluteProgression)
        }

    private fun notifyInvalidators() {
        for (i in invalidators.indices) {
            invalidators[i].invalidatePlaceholder()
        }
    }

    /**
     * Upstream's `PlaceholderAnimationHelper`, with the frame clock it used to borrow from
     * `AnimationCoordinator` folded in: [Choreographer] supplies the same monotonic frame time, and
     * the loop runs only while somebody is registered, as upstream's comment demands.
     */
    private inner class PlaceholderAnimationHelper : Choreographer.FrameCallback {

        /** How many Modifiers are using this, if this is 0, no animations will run. */
        private var registeredUsers = 0
        private var scheduled = false
        private var lastFrameMillis = Long.MIN_VALUE

        /**
         * The start time in milliseconds for the animation, or Long.MIN_VALUE if no animation is in
         * progress.
         */
        private var startOfTransitionAnimation = Long.MIN_VALUE

        /** The time in milliseconds for when the animation will end, or MAX if none is in progress. */
        private var endOfTransitionAnimation = Long.MAX_VALUE

        /** Returns true if there is an animation in progress. */
        fun isAnimationRunning(): Boolean = maybeGetFrameMillis() != Long.MAX_VALUE

        /** Starts an animation for the given duration. */
        fun startAnimation(duration: Long) {
            val frameMillis = frameTimeMillis()
            startOfTransitionAnimation = frameMillis
            endOfTransitionAnimation = frameMillis + duration
        }

        /**
         * Returns a number between 0f and 1f representing the linear progress of this animation, or
         * 1f if there is no animation running.
         */
        fun animationProgress(): Float {
            val frameMillis = maybeGetFrameMillis()
            if (frameMillis == Long.MAX_VALUE) return 1f
            return (frameMillis - startOfTransitionAnimation).toFloat() /
                (endOfTransitionAnimation - startOfTransitionAnimation)
        }

        /** Returns the current frame time in milliseconds. */
        fun frameTimeMillis(): Long =
            if (lastFrameMillis == Long.MIN_VALUE) SystemClock.uptimeMillis() else lastFrameMillis

        fun register() {
            registeredUsers++
        }

        fun unregister() {
            registeredUsers = (registeredUsers - 1).coerceAtLeast(0)
            if (registeredUsers == 0) stop()
        }

        /** Kicks the clock after a state change or a new registration. */
        fun tickIfNeeded() {
            if (registeredUsers > 0 && (isVisible || hasPendingAnimation())) schedule()
        }

        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            lastFrameMillis = frameTimeNanos / NANOS_PER_MILLI
            notifyInvalidators()
            // The final frame of an animation is still delivered: `isAnimationRunning` retires the
            // window as it reports it over, so the last invalidation draws the settled state.
            if (registeredUsers > 0 && (isVisible || isAnimationRunning())) {
                schedule()
            } else {
                lastFrameMillis = Long.MIN_VALUE
            }
        }

        private fun hasPendingAnimation(): Boolean = startOfTransitionAnimation != Long.MIN_VALUE

        private fun schedule() {
            if (scheduled) return
            scheduled = true
            Choreographer.getInstance().postFrameCallback(this)
        }

        private fun stop() {
            if (scheduled) {
                Choreographer.getInstance().removeFrameCallback(this)
                scheduled = false
            }
            lastFrameMillis = Long.MIN_VALUE
        }

        // Returns the frame time if we are inside an animation, Long.MAX_VALUE otherwise.
        // If an animation just finished, update start/end times.
        private fun maybeGetFrameMillis(): Long {
            if (startOfTransitionAnimation == Long.MIN_VALUE || registeredUsers == 0) return Long.MAX_VALUE
            val frameMillis = frameTimeMillis()
            return if (frameMillis >= endOfTransitionAnimation) {
                startOfTransitionAnimation = Long.MIN_VALUE
                endOfTransitionAnimation = Long.MAX_VALUE
                Long.MAX_VALUE
            } else {
                frameMillis
            }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** What a placeholder drawable needs, as an immutable value so attribute diffing can skip a retune. */
private data class PlaceholderOverlaySpec(
    val state: PlaceholderState,
    val shape: Shape,
    val color: Color,
    val reduceMotion: Boolean,
)

/**
 * A drawables' side of [PlaceholderState]'s frame clock.
 *
 * Internal, not private: [PlaceholderState.register] is internal upstream and cannot expose a
 * private-in-file parameter type. The name is placeholder-prefixed, so it claims nothing generic.
 */
internal fun interface PlaceholderInvalidator {
    fun invalidatePlaceholder()
}

/**
 * The shared half of the two placeholder effects: one entry in the host view's overlay, sized to the
 * view, alive for as long as the state's frame clock needs it.
 */
private abstract class PlaceholderOverlayDrawable(
    protected val host: View,
    val spec: PlaceholderOverlaySpec,
) : Drawable(), PlaceholderInvalidator, View.OnAttachStateChangeListener {

    protected val density: Float = host.resources.displayMetrics.density
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radii = FloatArray(8)
    private val rect = RectF()
    private val outline = Path()
    private val hostLocation = IntArray(2)
    private val rootLocation = IntArray(2)

    init {
        spec.state.register(this)
        host.addOnAttachStateChangeListener(this)
    }

    /** Undo everything [register] did, so the state's clock can stop when the view goes away. */
    fun dispose() {
        spec.state.unregister(this)
        host.removeOnAttachStateChangeListener(this)
    }

    protected abstract fun onDrawPlaceholder(canvas: Canvas, width: Int, height: Int)

    final override fun draw(canvas: Canvas) {
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return
        // `ViewOverlay` neither sizes nor invalidates its drawables, so keep the bounds honest here.
        if (bounds.width() != w || bounds.height() != h) setBounds(0, 0, w, h)
        onDrawPlaceholder(canvas, w, h)
    }

    override fun invalidatePlaceholder() {
        invalidateSelf()
    }

    override fun onViewAttachedToWindow(v: View) {
        spec.state.register(this)
    }

    override fun onViewDetachedFromWindow(v: View) {
        spec.state.unregister(this)
    }

    /** Fills the component's shape: the `drawOutline(shape.createOutline(size, ...))` of upstream. */
    protected fun drawShape(canvas: Canvas, width: Int, height: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (spec.shape is RectangleShape) {
            // Upstream's own shortcut: a rectangle needs no outline maths at all.
            canvas.drawRect(0f, 0f, w, h, paint)
            return
        }
        spec.shape.toRadii(w, h, density, radii)
        rect.set(0f, 0f, w, h)
        outline.reset()
        outline.addRoundRect(rect, radii, Path.Direction.CW)
        canvas.drawPath(outline, paint)
    }

    /** The view's offset inside its root, i.e. upstream's `LayoutCoordinates.positionInRoot()`. */
    protected fun positionInRoot(out: FloatArray) {
        val root = host.rootView
        if (root === host) {
            out[0] = 0f
            out[1] = 0f
            return
        }
        host.getLocationInWindow(hostLocation)
        root.getLocationInWindow(rootLocation)
        out[0] = (hostLocation[0] - rootLocation[0]).toFloat()
        out[1] = (hostLocation[1] - rootLocation[1]).toFloat()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** The skeleton rectangle/circle behind what should have been loaded. */
private class PlaceholderSkeletonDrawable(
    host: View,
    spec: PlaceholderOverlaySpec,
) : PlaceholderOverlayDrawable(host, spec) {

    override fun onDrawPlaceholder(canvas: Canvas, width: Int, height: Int) {
        val state = spec.state
        if (!state.isVisible && (spec.reduceMotion || !state.isAnimationRunning)) return
        val outlineAlpha =
            if (spec.reduceMotion) {
                1f
            } else if (state.isVisible) {
                state.resetPlaceholderFadeInAlpha
            } else {
                1f - state.placeholderWipeOffAlpha
            }
        // Compose's `drawRect(color, alpha = a)` scales the colour's own alpha by `a`; out-of-range
        // interpolation values clamp there, so they clamp here.
        paint.color = spec.color
            .copy(alpha = (spec.color.alpha * outlineAlpha.coerceIn(0f, 1f)))
            .toArgb()
        drawShape(canvas, width, height)
    }
}

/** The 45 degree screen-wide sweep that tells the user the data is still coming. */
private class PlaceholderShimmerDrawable(
    host: View,
    spec: PlaceholderOverlaySpec,
) : PlaceholderOverlayDrawable(host, spec) {

    private val offset = FloatArray(2)
    private val progressionInterpolator: Easing = CubicBezierEasing(0.3f, 0f, 0.7f, 1f)

    override fun onDrawPlaceholder(canvas: Canvas, width: Int, height: Int) {
        val state = spec.state
        // Upstream's `generateBrush` returns null in exactly these two cases.
        if (!state.isVisible || state.isAnimationRunning) return
        val maxScreenDimension = maxScreenDimensionPx()
        val halfGradientWidth = maxScreenDimension * 2f.pow(1.5f) / 2f
        val progression = placeholderShimmerProgression
        val from = -maxScreenDimension * 0.5f
        val to = maxScreenDimension * 1.5f
        val screenShimmerProgression = from + (to - from) * progression
        positionInRoot(offset)
        val shimmerX = screenShimmerProgression - offset[0]
        val shimmerY = screenShimmerProgression - offset[1]
        val alpha = placeholderShimmerAlpha
        paint.shader = LinearGradient(
            shimmerX - halfGradientWidth,
            shimmerY - halfGradientWidth,
            shimmerX + halfGradientWidth,
            shimmerY + halfGradientWidth,
            intArrayOf(
                spec.color.copy(alpha = 0f).toArgb(),
                spec.color.copy(alpha = alpha).toArgb(),
                spec.color.copy(alpha = 0f).toArgb(),
            ),
            floatArrayOf(0.1f, 0.65f, 0.9f),
            Shader.TileMode.CLAMP,
        )
        drawShape(canvas, width, height)
    }

    private fun maxScreenDimensionPx(): Float {
        val resources = host.resources
        val config = resources.configuration
        return max(config.screenHeightDp, config.screenWidthDp) * resources.displayMetrics.density
    }

    private fun shimmerAbsoluteProgression(): Float =
        spec.state.frameTimeMillis
            .mod(PLACEHOLDER_SHIMMER_GAP_BETWEEN_ANIMATION_LOOPS_MS)
            .coerceAtMost(PLACEHOLDER_SHIMMER_DURATION_MS)
            .toFloat() / PLACEHOLDER_SHIMMER_DURATION_MS

    /**
     * The current value of the placeholder visual effect gradient progression in Px, as it moves
     * across the screen: off screen to the left, across, and off screen to the right after 800ms.
     */
    private val placeholderShimmerProgression: Float
        get() = progressionInterpolator.transform(shimmerAbsoluteProgression())

    /**
     * The current value of the placeholder visual effect gradient progression alpha/opacity, faded
     * in and then out during the same 800ms.
     */
    private val placeholderShimmerAlpha: Float
        get() = 0.5f - abs(0.5f - shimmerAbsoluteProgression())
}

private fun Modifier.placeholderSkeletonAttrs(spec: PlaceholderOverlaySpec): Modifier =
    this.thenViewAttribute<View, PlaceholderOverlaySpec?>(
        uniqueKey,
        spec,
    ) {
        installPlaceholderOverlay(PLACEHOLDER_SKELETON_TAG_KEY, it) { view, value ->
            PlaceholderSkeletonDrawable(view, value)
        }
    }

private fun Modifier.placeholderShimmerAttrs(spec: PlaceholderOverlaySpec?): Modifier =
    this.thenViewAttribute<View, PlaceholderOverlaySpec?>(
        uniqueKey,
        spec,
    ) {
        installPlaceholderOverlay(PLACEHOLDER_SHIMMER_TAG_KEY, it) { view, value ->
            PlaceholderShimmerDrawable(view, value)
        }
    }

/**
 * Swaps this view's overlay entry tagged [tagKey] for one built from [spec], or drops it when
 * [spec] is null. The two placeholder effects are separate entries, so both can be live at once.
 *
 * The tag is how the entry is found again: `ViewOverlay` is a public class with `add`, `remove` and
 * `clear` only - its `size()` / `getDrawable()` are not public API - so the drawable has to be
 * remembered by hand.
 */
private fun View.installPlaceholderOverlay(
    tagKey: Int,
    spec: PlaceholderOverlaySpec?,
    create: (View, PlaceholderOverlaySpec) -> PlaceholderOverlayDrawable,
) {
    val existing = getTag(tagKey) as? PlaceholderOverlayDrawable
    if (existing != null) {
        if (existing.spec == spec) return
        setTag(tagKey, null)
        overlay.remove(existing)
        existing.dispose()
    }
    if (spec == null) return
    val drawable = create(this, spec)
    setTag(tagKey, drawable)
    overlay.add(drawable)
}

/**
 * Tag keys under which each placeholder effect remembers its own overlay entry, because `ViewOverlay`
 * exposes no way to enumerate what it holds.
 *
 * Both are resource ids from this module's `res/values/ids.xml`, which is a requirement and not a
 * style choice: `View.setTag(int, Object)` throws `IllegalArgumentException` unless
 * `key >>> 24 >= 2` (frameworks/base `core/java/android/view/View.java:28068-28074`), and
 * `View.generateViewId()` deliberately clamps to 1..0x00FFFFFF so it can never collide with an
 * aapt-generated id (`:30845-30855`) — a high byte of 0 is exactly the rejected case, so a generated
 * key throws on the first `setTag` instead of identifying the entry.
 */
private val PLACEHOLDER_SKELETON_TAG_KEY = R.id.hibari_wear_placeholder_skeleton
private val PLACEHOLDER_SHIMMER_TAG_KEY = R.id.hibari_wear_placeholder_shimmer

private val PLACEHOLDER_SHIMMER_DURATION_MS = MotionDurationTokens.DurationExtraLong2.toLong()
private val PLACEHOLDER_WIPE_OFF_PROGRESSION_DURATION_MS =
    MotionDurationTokens.DurationMedium2.toLong()
private val PLACEHOLDER_SHIMMER_GAP_BETWEEN_ANIMATION_LOOPS_MS =
    MotionDurationTokens.DurationExtraLong4.toLong().times(2L)
private const val PLACEHOLDER_WIPE_OFF_PROGRESSION_ALPHA_DURATION_MS = 80L
private val PLACEHOLDER_WIPE_OFF_PROGRESSION_ALPHA_RELATIVE =
    PLACEHOLDER_WIPE_OFF_PROGRESSION_ALPHA_DURATION_MS.toFloat() /
        PLACEHOLDER_WIPE_OFF_PROGRESSION_DURATION_MS
private val PLACEHOLDER_RESET_ANIMATION_INCOMING_DURATION_MS =
    MotionDurationTokens.DurationMedium1.toLong()
private val PLACEHOLDER_RESET_ANIMATION_OUTGOING_DURATION_MS =
    MotionDurationTokens.DurationShort3.toLong()
private val PLACEHOLDER_RESET_ANIMATION_DURATION_MS =
    PLACEHOLDER_RESET_ANIMATION_INCOMING_DURATION_MS + PLACEHOLDER_RESET_ANIMATION_OUTGOING_DURATION_MS
private val PLACEHOLDER_RESET_ANIMATION_OUTGOING_DURATION_RELATIVE =
    PLACEHOLDER_RESET_ANIMATION_OUTGOING_DURATION_MS.toFloat() / PLACEHOLDER_RESET_ANIMATION_DURATION_MS
