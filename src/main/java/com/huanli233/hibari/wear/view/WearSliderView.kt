package com.huanli233.hibari.wear.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.view.ViewCompat
import com.huanli233.hibari.animation.CubicBezierEasing
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.wear.tokens.MotionDurationTokens
import kotlin.math.abs

/**
 * Ported from `Modifier.drawProgressBar` (`material3/Slider.kt:744-783`),
 * `DrawScope.drawSelectedProgressBar` (`785-801`), `DrawScope.drawUnselectedProgressBar` (`803-815`)
 * and `DrawScope.drawProgressBarSeparator` (`817-824`) in androidx.wear.compose.material3.Slider. The
 * four bar dimensions (`837-843`) and both the segment-padding and the separator-placement formulas
 * come from that file line for line: the extra `(width + 2 * separatorRadius) * ratio +
 * segmentBarPadding` on the selected bar (`754-759`) is what makes its end land flush against the
 * separator dot instead of short of it, so it is not an approximation that can be dropped.
 *
 * Deviations, all of them mechanical rather than visual:
 *  - `valueRatio` arrives as a property instead of an `animateFloatAsState` value. The tween is
 *    ported here (`MotionTokens.DurationShort3` with `MotionTokens.EasingStandardDecelerate`),
 *    including the rule that the first value is never animated.
 *  - `LocalLayoutDirection.current` becomes [ViewCompat.getLayoutDirection], the same resolved
 *    left-to-right question asked of the view tree instead of of the composition.
 *  - `BlendMode.Src` is [PorterDuff.Mode.SRC] on the separator paint only, as upstream. That mode
 *    replaces the destination instead of compositing over it, so a separator circle with alpha
 *    knocks out whatever is underneath; upstream wraps this drawing in no graphics layer either, so
 *    the hole lands on the container fill in both toolkits.
 *  - The clip runs through [Shape.toRadii], which caps a corner at 0.45 of the shorter side where
 *    Compose caps it at 0.5. On the 12.dp bar the caller's 26.dp corner is capped either way, to
 *    5.4 dp here and 6 dp there.
 */
class WearSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.FILL }
    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.FILL
        it.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
    private val rect = RectF()
    private val clipPath = Path()
    private val radii = FloatArray(8)
    private var ratioAnimator: ValueAnimator? = null
    private var initialised = false

    /** What is on screen right now; it chases [valueRatio] through the ported tween. */
    private var drawnRatio = 0f

    /** Target `currentStep / (steps + 1)`, the value upstream hands to `animateFloatAsState`. */
    var valueRatio: Float = 0f
        set(value) {
            if (initialised && field == value) return
            val from = drawnRatio
            field = value
            if (!initialised) {
                initialised = true
                drawnRatio = value
                invalidate()
                return
            }
            if (from == value) {
                ratioAnimator?.cancel()
                ratioAnimator = null
                return
            }
            animateRatioTo(from, value)
        }

    /** Segments the bar is split into: `steps + 1` when segmented, otherwise 1. */
    var visibleSegments: Int = 1
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Upstream's `segmented` flag: it selects the widened bar geometry, not just the separators. */
    var segmented: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Shape the bar drawing is clipped to, from `Modifier.clip(shape)` on the bar's `Box`. */
    var shape: Shape? = null
        set(value) {
            if (field === value) return
            field = value
            invalidate()
        }

    var selectedBarColor: Color = Color.Unspecified
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var unselectedBarColor: Color = Color.Unspecified
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var selectedBarSeparatorColor: Color = Color.Unspecified
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var unselectedBarSeparatorColor: Color = Color.Unspecified
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * The residual of upstream's `Modifier.progressSemantics(...)`, which labels the node
     * `android.widget.ProgressBar` next to a `ProgressBarRangeInfo` (Slider.kt:138, 193). Only the
     * class name is published here: no range info, for the reason given on
     * [com.huanli233.hibari.wear.Slider].
     */
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // Called as a setter rather than `info.className = ...`: the platform getter returns
        // CharSequence while the setter takes String, so Kotlin gives no synthetic property here.
        info.setClassName(ProgressBar::class.java.name)
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val rtl = ViewCompat.getLayoutDirection(this) == View.LAYOUT_DIRECTION_RTL

        val clipShape = shape
        var saved = false
        if (clipShape != null) {
            clipShape.toRadii(viewWidth, viewHeight, density, radii)
            rect.set(0f, 0f, viewWidth, viewHeight)
            clipPath.reset()
            clipPath.addRoundRect(rect, radii, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
            saved = true
        }

        val separatorRadiusPx = BarSeparatorRadius.value * density
        val ratio = drawnRatio
        val barFillWidth =
            if (segmented && ratio > 0f) {
                (viewWidth + 2 * separatorRadiusPx) * ratio + SegmentBarPadding.value * density
            } else {
                viewWidth * ratio
            }

        drawProgressBar(
            canvas,
            color = unselectedBarColor,
            left = directedValue(rtl, barFillWidth, 0f),
            fillWidth = viewWidth - barFillWidth,
            thickness = UnselectedBarHeight.value * density,
            viewHeight = viewHeight,
        )
        drawProgressBar(
            canvas,
            color = selectedBarColor,
            left = directedValue(rtl, 0f, viewWidth - barFillWidth),
            fillWidth = barFillWidth,
            thickness = SelectedBarHeight.value * density,
            viewHeight = viewHeight,
        )

        val segments = visibleSegments
        if (segments > 1) {
            val segmentWidth = (viewWidth + 2 * separatorRadiusPx) / segments
            for (separator in 1 until segments) {
                val color =
                    if (separator.toFloat() / segments.toFloat() <= ratio) {
                        selectedBarSeparatorColor
                    } else {
                        unselectedBarSeparatorColor
                    }
                val position = (if (rtl) segments - separator else separator) * segmentWidth -
                    separatorRadiusPx
                drawProgressBarSeparator(canvas, color, position, separatorRadiusPx, viewHeight)
            }
        }

        if (saved) canvas.restore()
    }

    /** `CornerRadius(barHeight / 2)` on a `drawRoundRect`, which is how both tracks get their ends. */
    private fun drawProgressBar(
        canvas: Canvas,
        color: Color,
        left: Float,
        fillWidth: Float,
        thickness: Float,
        viewHeight: Float,
    ) {
        if (color.isUnspecified || fillWidth <= 0f || thickness <= 0f) return
        val radius = thickness / 2
        rect.set(left, (viewHeight - thickness) / 2, left + fillWidth, (viewHeight + thickness) / 2)
        barPaint.color = color.toArgb()
        canvas.drawRoundRect(rect, radius, radius, barPaint)
    }

    private fun drawProgressBarSeparator(
        canvas: Canvas,
        color: Color,
        position: Float,
        radius: Float,
        viewHeight: Float,
    ) {
        if (color.isUnspecified || radius <= 0f) return
        separatorPaint.color = color.toArgb()
        canvas.drawCircle(position, viewHeight / 2, radius, separatorPaint)
    }

    /** `directedValue(direction, ltrValue, rtlValue)` from androidx.wear.compose.materialcore. */
    private fun directedValue(rtl: Boolean, ltrValue: Float, rtlValue: Float): Float =
        if (!rtl) ltrValue else rtlValue

    private fun animateRatioTo(from: Float, to: Float) {
        ratioAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(from, to)
        animator.duration = RatioAnimationMillis
        animator.interpolator = StandardDecelerateInterpolator
        animator.addUpdateListener {
            drawnRatio = it.animatedValue as Float
            invalidate()
        }
        animator.start()
        ratioAnimator = animator
    }

    override fun onDetachedFromWindow() {
        ratioAnimator?.cancel()
        ratioAnimator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        // The four drawing dimensions from the bottom of upstream's Slider.kt; the layout ones stay
        // with the layout in Slider.kt.
        val SelectedBarHeight = 12.dp
        val UnselectedBarHeight = 4.dp
        val BarSeparatorRadius = 2.dp
        val SegmentBarPadding = 4.dp
        val RatioAnimationMillis = MotionDurationTokens.DurationShort3.toLong()

        /**
         * `MotionTokens.EasingStandardDecelerate`, restated because this module's token file carries
         * no easings: `CubicBezierEasing(0.0f, 0.0f, 0.0f, 1.0f)`. `android.view.animation` has had
         * its own `TimeInterpolator` dropped from the compileSdk stubs, so the bridge is spelled as an
         * `Interpolator`, which is the API 1 subtype of `android.animation.TimeInterpolator`.
         */
        val StandardDecelerateInterpolator: Interpolator = object : Interpolator {
            private val easing = CubicBezierEasing(0.0f, 0.0f, 0.0f, 1.0f)

            override fun getInterpolation(input: Float): Float = easing.transform(input)
        }
    }
}

/**
 * Ported from `materialcore.InlineSliderButton` (`materialcore/Slider.kt:39-63`) together with the
 * `materialcore.repeatableClickable` it installs (`RepeatableClickable.kt:69-121`): a fixed-width,
 * full-height box that centres its content and, once the finger has been held for `initialDelay`,
 * re-fires its click every `incrementalDelay` until the gesture ends or the button disables itself.
 * Both delays are upstream's defaults, 500 ms and 60 ms (`RepeatableClickable.kt:75-76`), and the
 * `repeatableClickable` at the slider's call site passes no `onRepeatableClick`, so a repeat is the
 * same click as a release (`RepeatableClickable.kt:78`).
 *
 * Two things upstream wraps around that are left out. `Modifier.semantics { role = Role.Button }`
 * (`materialcore/Slider.kt:49`) has no Views equivalent beyond the click the view already performs,
 * and the `LocalIndication provides ripple(bounded = false, radius = maxWidth)` the slider installs
 * over its row (`material3/Slider.kt:163-164`) is dropped along with the rest of the indication
 * system (see [com.huanli233.hibari.wear.MaterialTheme]), so a press reads as the colour swap alone.
 *
 * Public because it is the node [com.huanli233.hibari.wear.Slider] instantiates through
 * [com.huanli233.hibari.ui.viewClass]; upstream's own version is a library-internal surface, so treat
 * it as an implementation detail rather than as API.
 */
class SliderButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * The threshold upstream's gesture detection runs on: Compose reads it from
     * `LocalViewConfiguration.touchSlop`, and [ViewConfiguration.getScaledTouchSlop] is the same
     * platform number Views use to decide that a press has become a drag.
     */
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    /** Upstream's `ignoreOnClick`: set once a repeat has already delivered the action. */
    private var suppressClick = false

    private val repeatRunnable = Runnable { repeatTick() }

    init {
        // The repeat is ours; the framework long press fires on the same 500 ms and would only
        // contend with it for the pressed state.
        isLongClickable = false
    }

    /**
     * Upstream centres the icon with `contentAlignment = Alignment.Center`. `FrameLayout` has no
     * container-level gravity — unlike `LinearLayout` — so the Views equivalent is to hand out a
     * default child gravity; a child that carries its own `layout_gravity` still wins.
     */
    override fun generateDefaultLayoutParams(): LayoutParams = centredLayoutParams()

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams =
        LayoutParams(context, attrs).apply { keepIfUnspecified() }

    override fun generateLayoutParams(p: android.view.ViewGroup.LayoutParams): LayoutParams =
        (if (p is LayoutParams) p else LayoutParams(p.width, p.height)).apply { keepIfUnspecified() }

    private fun FrameLayout.LayoutParams.keepIfUnspecified() {
        if (gravity == Gravity.NO_GRAVITY) gravity = Gravity.CENTER
    }

    private fun centredLayoutParams(): LayoutParams =
        LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER }

    /**
     * The release click of a gesture that already repeated is swallowed, which is upstream's
     * `if (!ignoreOnClick) currentOnClick()`. The repeat itself bypasses this by calling
     * `super.performClick()` from [repeatTick].
     */
    override fun performClick(): Boolean {
        if (suppressClick) {
            suppressClick = false
            return false
        }
        return super.performClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                suppressClick = false
                downX = event.x
                downY = event.y
                removeCallbacks(repeatRunnable)
                if (isEnabled) postDelayed(repeatRunnable, InitialDelayMillis)
            }

            MotionEvent.ACTION_MOVE ->
                // The cancellation half of upstream's `waitForUpOrCancellation`: past the tap slop the
                // gesture is no longer a tap, so the repeating job is torn down.
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                    removeCallbacks(repeatRunnable)
                }

            // Removal runs whether or not the view is still enabled: a button that reaches its bound
            // mid-hold gets disabled by the retune, and a `return` above would have left the repeat
            // posted with nothing left to cancel it.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(repeatRunnable)
        }
        return handled
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(repeatRunnable)
        super.onDetachedFromWindow()
    }

    /**
     * `onRepeatableClick` defaults to `onClick` upstream, so a repeat is the same click the release
     * would have sent. It goes straight to `super.performClick()` because [performClick]'s suppression
     * flag is there for the release, not for the repeat.
     *
     * The enabled check is upstream's `while (enabled)` (RepeatableClickable.kt:110), which stops the
     * loop the moment the button disables itself — holding increase to the top step, say. Without it
     * the re-post below never ends.
     */
    private fun repeatTick() {
        if (!isEnabled) return
        suppressClick = true
        super.performClick()
        postDelayed(repeatRunnable, IncrementalDelayMillis)
    }

    private companion object {
        const val InitialDelayMillis = 500L
        const val IncrementalDelayMillis = 60L
    }
}
