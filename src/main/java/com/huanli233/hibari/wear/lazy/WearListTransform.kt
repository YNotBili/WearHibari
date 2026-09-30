package com.huanli233.hibari.wear.lazy

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.WearableLinearLayoutManager
import com.huanli233.hibari.animation.CubicBezierEasing
import com.huanli233.hibari.animation.Easing
import kotlin.math.min

/**
 * The per-item scale/fade profile Wear's lazy lists apply near the viewport edges.
 *
 * Field names, defaults and the curve are taken from `ScalingLazyColumnDefaults.scalingParams()`
 * and `ScalingLazyColumnMeasure.calculateScaleAndAlpha` in
 * androidx.wear.compose.foundation.lazy.
 *
 * The direction is the part most often got wrong, so stated plainly: an item is full size in the
 * **middle** of the viewport and shrinks as it approaches **either edge**. The driving quantity is
 * not distance from the centre but `min(viewportHeight - top, bottom)` — how far the item's
 * *furthest* edge still is from the nearest screen edge. Under the transition line the item scales
 * down; at or above it, nothing is applied.
 *
 * Six numbers rather than one because the band width tracks the item's own height: an item at
 * `minElementHeight` of the viewport begins shrinking at `minTransitionArea`, one at
 * `maxElementHeight` holds full size until `maxTransitionArea`.
 */
data class ListTransformParams(
    /** Scale at the screen edge (`1f` = no shrink). Upstream default `0.7f`. */
    val edgeScale: Float = 0.7f,
    /** Alpha at the screen edge. Upstream default `0.5f`. */
    val edgeAlpha: Float = 0.5f,
    val minElementHeight: Float = 0.2f,
    val maxElementHeight: Float = 0.6f,
    val minTransitionArea: Float = 0.35f,
    val maxTransitionArea: Float = 0.55f,
    val scaleInterpolator: Easing = CubicBezierEasing(0.3f, 0f, 0.7f, 1f),
    /**
     * Upstream pads the composed range by `viewportHeight / 20` so shrunk items leave no gap. It
     * shifts where the transition line is measured from and is kept so a ported call site sees the
     * same numbers. Expressed as a fraction rather than upstream's `(Constraints) -> Int` resolver
     * (`foundation/lazy/ScalingLazyColumn.kt:848`), so the truncation to whole pixels that resolver
     * performs is applied where the pad is used, in [progressFor] — on a 193 px viewport upstream's pad
     * is 9 px, not 9.65.
     */
    val viewportVerticalOffsetFraction: Float = 1f / 20f,
    /** What `LocalReduceMotion` swaps in upstream: no scaling, no fading. */
    val reduceMotion: Boolean = false,
) {

    /** `line` in the source: where the transition begins, in viewport fractions. */
    fun transitionLineFor(itemHeightFraction: Float): Float {
        if (maxElementHeight <= minElementHeight) return minTransitionArea
        val sizeRatio = ((itemHeightFraction - minElementHeight) / (maxElementHeight - minElementHeight))
            .coerceIn(0f, 1f)
        return minTransitionArea + (maxTransitionArea - minTransitionArea) * sizeRatio
    }

    /**
     * Eased ramp for one laid-out child: `0f` = full size, `1f` = at [edgeScale]/[edgeAlpha]. Zero
     * when the child sits clear of both transition bands.
     */
    fun progressFor(child: View, viewportHeight: Int): Float = progressFor(
        top = child.top.toFloat(),
        height = child.height.toFloat(),
        viewportHeight = viewportHeight.toFloat(),
    )

    /**
     * Pure form of [progressFor], in pixels, taking the child's unadjusted [top] and [height].
     * Split out so the curve can be tested without an Android `View`.
     */
    fun progressFor(top: Float, height: Float, viewportHeight: Float): Float {
        if (viewportHeight <= 0f || reduceMotion) return 0f
        // Upstream's resolver returns an Int, so the pad is a whole number of pixels.
        val offset = (viewportHeight * viewportVerticalOffsetFraction).toInt().toFloat()
        val adjustedTop = top - offset
        val bottom = adjustedTop + height
        val distance = min(viewportHeight - adjustedTop, bottom)
        val fraction = distance / viewportHeight
        val line = transitionLineFor(height / viewportHeight)
        if (line <= 0f || fraction >= line) return 0f
        return scaleInterpolator.transform(1f - fraction / line)
    }

    fun scaleFor(progress: Float): Float = 1f + (edgeScale - 1f) * progress

    fun alphaFor(progress: Float): Float = 1f + (edgeAlpha - 1f) * progress
}

/**
 * Applies [ListTransformParams] to each child as the layout manager finishes laying it out.
 *
 * The pivot mirrors upstream's `scaledTop`: an item whose centre is above the viewport midline
 * shrinks toward its **bottom** edge, one below toward its **top**, so the gap always opens toward
 * the screen edge and never toward the centre.
 *
 * What this cannot do, stated rather than papered over: `WearableLinearLayoutManager` positions
 * children by their *untransformed* decor bounds, so scaling a child does not shorten its slot and
 * siblings do not slide toward the focal line the way Compose re-measures and reflows. A heavily
 * scaled item keeps its full-height gap. This is the same trade-off the classic Wear
 * `ScalingLayoutCallback` sample makes.
 */
class WearListTransformLayoutCallback(
    var params: ListTransformParams = ListTransformParams(),
) : WearableLinearLayoutManager.LayoutCallback() {

    override fun onLayoutFinished(child: View, parent: RecyclerView) {
        val viewportHeight = parent.height
        if (viewportHeight == 0) return

        val progress = params.progressFor(child, viewportHeight)
        // A scroll re-lays out every visible child each frame and hands back the same numbers for
        // everything except the two rows crossing the transition band, so each write is guarded:
        // assigning a View property dirties its render node even when the value is unchanged.
        val scale: Float
        val alphaValue: Float
        val pivotYValue: Float
        if (progress == 0f) {
            scale = 1f
            alphaValue = 1f
            pivotYValue = 0f
        } else {
            val centreAboveMidline = (child.top + child.top + child.height) < viewportHeight
            scale = params.scaleFor(progress)
            alphaValue = params.alphaFor(progress)
            pivotYValue = if (centreAboveMidline) child.height.toFloat() else 0f
            val pivotXValue = child.width / 2f
            if (child.pivotX != pivotXValue) child.pivotX = pivotXValue
        }
        if (child.scaleX != scale) child.scaleX = scale
        if (child.scaleY != scale) child.scaleY = scale
        if (child.alpha != alphaValue) child.alpha = alphaValue
        if (child.pivotY != pivotYValue) child.pivotY = pivotYValue
    }
}

object WearListTransformDefaults {
    /** `ScalingLazyColumnDefaults.scalingParams()` with every field at its upstream default. */
    val ScalingLazy = ListTransformParams()

    /** What `LocalReduceMotion` resolves to when the wearer has reduced motion on. */
    val ReduceMotion = ScalingLazy.copy(edgeScale = 1f, edgeAlpha = 1f, reduceMotion = true)
}

/**
 * `LinearLayoutManager` exposes `canScrollVertically()` but no setter, so `userScrollEnabled = false`
 * has to come from a subclass rather than a property write.
 */
class WearScrollableLinearLayoutManager(
    context: Context,
    callback: WearableLinearLayoutManager.LayoutCallback,
    private val scrollEnabled: Boolean,
) : WearableLinearLayoutManager(context, callback) {
    override fun canScrollVertically(): Boolean = scrollEnabled
}
