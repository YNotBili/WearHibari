package com.huanli233.hibari.wear

import com.huanli233.hibari.animation.CubicBezierEasing
import com.huanli233.hibari.animation.TweenSpec
import com.huanli233.hibari.animation.tween
import com.huanli233.hibari.wear.lazy.ListTransformParams
import com.huanli233.hibari.wear.lazy.WearListTransformDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers that decide where a `ScalingLazyColumn` row is drawn and how long the scroll indicator's
 * thumb is, both of which are pure functions of their arguments.
 *
 * The scaling side is `ScalingLazyColumnDefaults.scalingParams()` and
 * `calculateScaleAndAlpha` in `androidx.wear.compose.foundation.lazy`; the indicator side is
 * `ScrollStateAdapter` (`material3/ScrollIndicator.kt:668-695`) and `ScalingLazyColumnStateAdapter`
 * (`:728-774`) reduced to plain parameters, because Hibari has no `layoutInfo` to read.
 */
class WearScrollNumbersFidelityTest {

    private val params = ListTransformParams()

    // ---- ScalingLazyColumn scaling ----------------------------------------------------------------

    @Test
    fun scalingParamsCarryUpstreamsSevenNumbers() {
        // `foundation/lazy/ScalingLazyColumn.kt:840-848`.
        assertEquals(0.7f, params.edgeScale, 0f)
        assertEquals(0.5f, params.edgeAlpha, 0f)
        assertEquals(0.2f, params.minElementHeight, 0f)
        assertEquals(0.6f, params.maxElementHeight, 0f)
        assertEquals(0.35f, params.minTransitionArea, 0f)
        assertEquals(0.55f, params.maxTransitionArea, 0f)
        // upstream's resolver is `(it.maxHeight / 20f).toInt()`, i.e. five percent of the viewport.
        assertEquals(0.05f, params.viewportVerticalOffsetFraction, 0f)
        assertEquals(CubicBezierEasing(0.3f, 0f, 0.7f, 1f), params.scaleInterpolator)
    }

    @Test
    fun reduceMotionKeepsTheGeometryAndDropsTheEffect() {
        // `ScalingLazyColumnMeasure.kt:248-256`: the wrapper replaces two numbers and inherits the
        // rest from whatever it was handed.
        val reduced = WearListTransformDefaults.ReduceMotion
        assertEquals(1f, reduced.edgeScale, 0f)
        assertEquals(1f, reduced.edgeAlpha, 0f)
        assertTrue(reduced.reduceMotion)
        assertEquals(0.2f, reduced.minElementHeight, 0f)
        assertEquals(0.6f, reduced.maxElementHeight, 0f)
        assertEquals(0.55f, reduced.maxTransitionArea, 0f)
        assertEquals(CubicBezierEasing(0.3f, 0f, 0.7f, 1f), reduced.scaleInterpolator)
        assertEquals(params, WearListTransformDefaults.ScalingLazy)
    }

    @Test
    fun theDrivingDistanceIsTheSmallerOfTheTwoClearances() {
        // `min(viewPortEndPx - itemTopPx, itemBottomPx - viewPortStartPx)`
        // (`ScalingLazyColumnMeasure.kt:331-332`), with the viewport padded by 5%. Two items mirrored
        // about the midline therefore take the same branch of the `min` in opposite directions, and an
        // inverted or missing `min` separates them.
        val top = params.progressFor(top = 10f, height = 20f, viewportHeight = 100f)
        val bottom = params.progressFor(top = 80f, height = 20f, viewportHeight = 100f)
        assertEquals(top, bottom, 0f)
        // Both sit 25px of clearance from their nearest edge, a 0.25 fraction against a 0.35 line.
        assertTrue("expected a ramp at both edges, got $top / $bottom", top > 0f)
    }

    @Test
    fun anItemExactlyOnTheTransitionLineIsStillFullSize() {
        // Upstream's test is `itemEdgeAsFractionOfViewPort < scalingLineAsFractionOfViewPort`
        // (`ScalingLazyColumnMeasure.kt:347`), so equality leaves the item alone: at top 70 the
        // clearance is 100 - 65 = 35 against a 35 line.
        assertEquals(0f, params.progressFor(top = 70f, height = 20f, viewportHeight = 100f), 0f)
        // One pixel further down and the clearance is 34, which is inside the band.
        assertTrue(
            "expected a ramp one pixel past the line",
            params.progressFor(top = 71f, height = 20f, viewportHeight = 100f) > 0f,
        )
    }

    @Test
    fun halfwayThroughTheBandIsHalfwayToTheEdgeScale() {
        // Clearance 17.5 of a 35 line: the raw ramp is exactly 0.5, and the interpolator's control
        // points (0.3, 0) / (0.7, 1) are point-symmetric about (0.5, 0.5), so the eased value is 0.5
        // too. Scale and alpha are then straight lerps from 1f to their edge values.
        val progress = params.progressFor(top = 2.5f, height = 20f, viewportHeight = 100f)
        assertEquals(0.5f, progress, 1e-4f)
        assertEquals(0.85f, params.scaleFor(progress), 1e-4f)
        assertEquals(0.75f, params.alphaFor(progress), 1e-4f)
        // And at the two ends, by definition: 0f is full size and 1f is `edgeScale` / `edgeAlpha`.
        assertEquals(1f, params.scaleFor(0f), 0f)
        assertEquals(0.7f, params.scaleFor(1f), 0f)
        assertEquals(0.5f, params.alphaFor(1f), 0f)
    }

    @Test
    fun theInterpolatorEasesInOutRatherThanRunningStraightThrough() {
        val eased = params.scaleInterpolator
        assertEquals(0f, eased.transform(0f), 0f)
        assertEquals(1f, eased.transform(1f), 0f)
        // An ease-in-out is behind the diagonal at the start and ahead of it at the end, so an item
        // holds near full size as it enters the band and near edge size as it leaves.
        assertTrue("expected an ease-in at 0.25", eased.transform(0.25f) < 0.25f)
        assertTrue("expected an ease-out at 0.75", eased.transform(0.75f) > 0.75f)
    }

    // ---- scroll indicator fractions ---------------------------------------------------------------

    @Test
    fun theThumbSpansBetweenThreeTenthsAndSevenTenths() {
        // `material3/ScrollIndicator.kt:347-348`, and the tween at `:344-345`.
        assertEquals(0.3f, ScrollIndicatorDefaults.MinSizeFraction, 0f)
        assertEquals(0.7f, ScrollIndicatorDefaults.MaxSizeFraction, 0f)
        assertEquals(
            tween<Float>(durationMillis = 500, easing = CubicBezierEasing(0f, 0f, 0f, 1f)),
            ScrollIndicatorDefaults.PositionAnimationSpec,
        )
        assertEquals(500, (ScrollIndicatorDefaults.PositionAnimationSpec as TweenSpec<Float>).durationMillis)
        // `CubicBezierEasing(0f, 0f, 0f, 1f)` is an ease-out, not the symmetric curve the list uses.
        assertTrue(
            "expected an ease-out at 0.5",
            (ScrollIndicatorDefaults.PositionAnimationSpec as TweenSpec<Float>).easing.transform(0.5f) > 0.5f,
        )
    }

    @Test
    fun aScrollStateThatCannotScrollShowsAFullLengthThumb() {
        // Upstream `ScrollIndicator.kt:670-688`: `maxValue == 0` answers the position with 0f, and a
        // container plus content of nothing answers the size with `maxSizeFraction`.
        assertEquals(0f, ScrollIndicatorFractions.forScrollState(0, 0, 0).positionFraction, 0f)
        assertEquals(0.7f, ScrollIndicatorFractions.forScrollState(0, 0, 0).sizeFraction, 0f)
        // Half a screen scrolled of a screen-and-a-half of content: 100 / (100 + 100) and 50 / 100.
        assertEquals(0.5f, ScrollIndicatorFractions.forScrollState(50, 100, 100).positionFraction, 0f)
        assertEquals(0.5f, ScrollIndicatorFractions.forScrollState(50, 100, 100).sizeFraction, 0f)
        // At the end of the range the position is exactly 1f while the thumb is unchanged - the two
        // halves of the formula are independent and a coupling here would show as a shrinking thumb.
        assertEquals(1f, ScrollIndicatorFractions.forScrollState(100, 100, 100).positionFraction, 0f)
        assertEquals(0.5f, ScrollIndicatorFractions.forScrollState(100, 100, 100).sizeFraction, 0f)
    }

    @Test
    fun aLongScrollableAreaClampsToTheMinimumThumb() {
        // 100 / (100 + 300) = 0.25, which the coerceIn raises to `minSizeFraction`; a short one,
        // 100 / (100 + 50), stays at two thirds and proves the clamp is not a constant.
        assertEquals(0.3f, ScrollIndicatorFractions.forScrollState(0, 300, 100).sizeFraction, 0f)
        val short = ScrollIndicatorFractions.forScrollState(0, 50, 100).sizeFraction
        assertEquals(2f / 3f, short, 1e-6f)
    }

    @Test
    fun aListThatFitsGetsAFullThumbAtTheTop() {
        // The port's own "nothing is scrolled" branch (`ScrollIndicator.kt:119-121`), which is what
        // upstream's arithmetic also yields once every item is visible: first index 0, last index
        // total, so `distanceFromEnd` is 0 and the size is total / total = 1 -> `maxSizeFraction`.
        val fits = ScrollIndicatorFractions.forScalingLazyColumn(
            centerItemIndex = 0,
            itemCount = 5,
            visibleItemCount = 5,
            canScrollForward = false,
            canScrollBackward = false,
        )
        assertEquals(0f, fits.positionFraction, 0f)
        assertEquals(0.7f, fits.sizeFraction, 0f)
        // An empty list is the other short circuit, and it is not the same answer.
        val empty = ScrollIndicatorFractions.forScalingLazyColumn(0, 0, 3, true, false)
        assertEquals(0f, empty.positionFraction, 0f)
        assertEquals(0f, empty.sizeFraction, 0f)
    }

    @Test
    fun theScalingLazyColumnThumbReadsACentredWindow() {
        // decimalFirst = c + 0.5 - span/2, decimalLast = c + 0.5 + span/2, then upstream's
        // `first / (first + (total - last))` and `(last - first) / total` (`:734-744`, `:760-766`).
        // Mid-list: window [1.5, 7.5] of 12 -> 1.5 / (1.5 + 4.5) and 6 / 12.
        val middle = ScrollIndicatorFractions.forScalingLazyColumn(4, 12, 6, true, true)
        assertEquals(0.25f, middle.positionFraction, 0f)
        assertEquals(0.5f, middle.sizeFraction, 0f)
        // At the top the first index clamps to 0, so the position is 0 however far the list runs;
        // at the bottom the last index clamps to the total, which empties the denominator.
        assertEquals(
            0f,
            ScrollIndicatorFractions.forScalingLazyColumn(0, 10, 3, false, true).positionFraction,
            0f,
        )
        assertEquals(
            1f,
            ScrollIndicatorFractions.forScalingLazyColumn(9, 10, 3, true, false).positionFraction,
            0f,
        )
        // A three-of-ten window is 0.3 of the list, which is exactly the floor - the clamp is doing
        // real work here rather than being a coincidence of the middle case.
        assertEquals(
            0.3f,
            ScrollIndicatorFractions.forScalingLazyColumn(0, 10, 3, false, true).sizeFraction,
            0f,
        )
    }

    @Test
    fun anUnknownWindowSizeDegradesToTheFocalItemAlone() {
        // `visibleItemCount = null` is the state's honest answer today, and it makes the window one
        // item wide: position reduces to c / (total - 1) and the size to 1 / total, i.e. always the
        // floor for any list longer than three items (`ScrollIndicator.kt:105-107`).
        val unknown = ScrollIndicatorFractions.forScalingLazyColumn(2, 10, null, true, true)
        assertEquals(2f / 9f, unknown.positionFraction, 1e-6f)
        assertEquals(0.3f, unknown.sizeFraction, 0f)
        // The two ends still land on 0f and 1f, which is what the KDoc claims for them.
        assertEquals(
            0f,
            ScrollIndicatorFractions.forScalingLazyColumn(0, 10, null, false, true).positionFraction,
            0f,
        )
        assertEquals(
            1f,
            ScrollIndicatorFractions.forScalingLazyColumn(9, 10, null, true, false).positionFraction,
            0f,
        )
    }
}
