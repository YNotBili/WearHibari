package com.huanli233.hibari.wear

import com.huanli233.hibari.wear.SwipeToDismissValue.Default
import com.huanli233.hibari.wear.SwipeToDismissValue.Dismissed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the arithmetic behind `BasicSwipeToDismissBox` before any view exists to be wrong about.
 *
 * Every expectation below is computed by hand from the upstream formula, and the working is written
 * next to it; the implementation was not consulted for any of these numbers. The two anchor
 * magnitudes used throughout are upstream's: `anchors = mapOf(Default to 0f, Dismissed to 960f)`
 * (`BasicSwipeToDismissBox.kt:120-121`) at density `1f`, so the positional threshold is
 * `abs(0.5f * (960f - 0f)) = 480f` (`BasicSwipeToDismissBox.kt:462` + `SwipeableV2.kt:657-660`) and
 * the velocity threshold is `800f.dp * 1f = 800f` px/s (`SwipeableV2.kt:672`).
 */
class SwipeToDismissTest {

    /** Upstream's map literal, so `Default` really is the first key for the tie-breaks below. */
    private fun anchors(maxWidthPx: Float = 960f) = mapOf(Default to 0f, Dismissed to maxWidthPx)

    private fun settle(
        offsetPx: Float,
        from: SwipeToDismissValue,
        velocityPxPerSecond: Float = 0f,
        maxWidthPx: Float = 960f,
        density: Float = 1f,
    ): SwipeToDismissValue =
        SwipeToDismissMath.settleTarget(
            offsetPx,
            from,
            velocityPxPerSecond,
            anchors(maxWidthPx),
            density,
        )

    // region progress

    @Test
    fun progressIsOffsetOverScreenWidth() {
        // offset / maxWidthPx, clamped to 0f..1f (BasicSwipeToDismissBox.kt:150-153).
        assertEquals(0f, SwipeToDismissMath.dismissalProgress(0f, 960f), 0f)
        assertEquals(0.5f, SwipeToDismissMath.dismissalProgress(480f, 960f), 0f)
        assertEquals(0.25f, SwipeToDismissMath.dismissalProgress(240f, 960f), 0f)
        assertEquals(1f, SwipeToDismissMath.dismissalProgress(960f, 960f), 0f)
        // Overshoot and the (unreachable-by-clamping) negative side both round to the bounds.
        assertEquals(1f, SwipeToDismissMath.dismissalProgress(1440f, 960f), 0f)
        assertEquals(0f, SwipeToDismissMath.dismissalProgress(-100f, 960f), 0f)
    }

    @Test
    fun uninitialisedOffsetAndZeroWidthReadAsZero() {
        // `if (state.swipeableState.offset?.isNaN() == true || maxWidthPx == 0f) 0f` (:150).
        assertEquals(0f, SwipeToDismissMath.dismissalProgress(Float.NaN, 960f), 0f)
        assertEquals(0f, SwipeToDismissMath.dismissalProgress(300f, 0f), 0f)
        assertFalse(SwipeToDismissMath.isSwiping(SwipeToDismissMath.dismissalProgress(1f, 0f)))
    }

    @Test
    fun isSwipingIsStrictlyPositiveProgress() {
        // `derivedStateOf { progress > 0 }` (:157).
        assertFalse(SwipeToDismissMath.isSwiping(0f))
        assertTrue(SwipeToDismissMath.isSwiping(0.001f))
        assertTrue(SwipeToDismissMath.isSwiping(1f))
    }

    // endregion

    // region the 0.5f positional threshold, both directions

    @Test
    fun positiveSwipeNeedsHalfTheWidth() {
        // currentAnchor = 0f, so absoluteThreshold = abs(0f + 480f) = 480f, and the test is
        // `if (offset < absoluteThreshold) currentValue else upper` (SwipeableV2.kt:524-528).
        // 479 < 480 -> stay at Default; 480 is NOT < 480 -> Dismissed, i.e. the boundary dismisses.
        assertEquals(Default, settle(479f, from = Default))
        assertEquals(Dismissed, settle(480f, from = Default))
        assertEquals(Dismissed, settle(481f, from = Default))
        assertEquals(Dismissed, settle(960f, from = Default))
    }

    @Test
    fun negativeSwipeBackGivesTheSameHalfButNotAtTheBoundary() {
        // From Dismissed: currentAnchor = 960f, lower = Default, distance = 960f,
        // absoluteThreshold = abs(960f - 480f) = 480f, test `if (offset > absoluteThreshold)
        // currentValue else lower` (SwipeableV2.kt:537-545).
        // 481 > 480 -> stays Dismissed; 480 is NOT > 480 -> Default. Asymmetry is upstream's.
        assertEquals(Dismissed, settle(481f, from = Dismissed))
        assertEquals(Default, settle(480f, from = Dismissed))
        assertEquals(Default, settle(100f, from = Dismissed))
    }

    @Test
    fun sittingOnTheCurrentAnchorNeverMoves() {
        // `if (currentAnchor == offset) currentValue` (SwipeableV2.kt:514).
        assertEquals(Default, settle(0f, from = Default))
        assertEquals(Dismissed, settle(960f, from = Dismissed))
    }

    @Test
    fun thresholdScalesWithTheWidth() {
        // Same rule on a 480 px box: threshold = 0.5 * 480 = 240.
        assertEquals(Default, settle(239f, from = Default, maxWidthPx = 480f))
        assertEquals(Dismissed, settle(240f, from = Default, maxWidthPx = 480f))
        assertEquals(Dismissed, settle(241f, from = Dismissed, maxWidthPx = 480f))
    }

    // endregion

    // region the velocity rule

    @Test
    fun velocityWinsBeforeThePositionalThreshold() {
        // Velocity is tested first and short-circuits the threshold maths:
        // `if (velocity >= velocityThresholdPx) closestAnchor(offset, true)` (SwipeableV2.kt:516-519)
        // with velocityThresholdPx = 800 * 1f. 100f is nowhere near 480f, yet the fling decides it.
        // `>=`, so exactly 800f counts as exceeded.
        assertEquals(Dismissed, settle(100f, from = Default, velocityPxPerSecond = 800f))
        assertEquals(Dismissed, settle(100f, from = Default, velocityPxPerSecond = 2000f))
        // 799.99f falls through to the positional branch: 100 < 480 -> Default.
        assertEquals(Default, settle(100f, from = Default, velocityPxPerSecond = 799.99f))
    }

    @Test
    fun aBackwardsFlingAbandonsAnAlmostCompleteSwipe() {
        // `if (velocity <= -velocityThresholdPx) closestAnchor(offset, false)` (SwipeableV2.kt:531-534).
        // closestAnchor(900f, downwards): Default costs 900f - 0f = 900f, Dismissed costs
        // 900f - 960f < 0 -> +inf, so Default is the closest anchor below the offset.
        assertEquals(Default, settle(900f, from = Dismissed, velocityPxPerSecond = -800f))
        // -799f is inside the threshold, so the position decides: 900 > 480 -> still Dismissed.
        assertEquals(Dismissed, settle(900f, from = Dismissed, velocityPxPerSecond = -799f))
    }

    @Test
    fun velocityThresholdIsInDpPerSecondSoDensityMatters() {
        // 800.dp.toPx() at density 2f is 1600f px/s (SwipeableV2.kt:513), so a 900 px/s fling is a
        // plain slow drag there while it dismisses at density 1f.
        assertEquals(Dismissed, settle(100f, from = Default, velocityPxPerSecond = 900f, density = 1f))
        assertEquals(Default, settle(100f, from = Default, velocityPxPerSecond = 900f, density = 2f))
        assertEquals(
            Dismissed,
            settle(100f, from = Default, velocityPxPerSecond = 1600f, density = 2f)
        )
    }

    @Test
    fun opposingVelocityDoesNotDismiss() {
        // A positive swipe with a backwards fling still needs the positional threshold:
        // -2000f >= 800f is false, so the position branch runs and 100 < 480 -> Default.
        assertEquals(Default, settle(100f, from = Default, velocityPxPerSecond = -2000f))
        // Symmetrically, a rightward fling cannot keep a leftward swipe at Dismissed:
        // 2000f <= -800f is false -> position branch, 900 > 480 -> Dismissed stays.
        assertEquals(Dismissed, settle(900f, from = Dismissed, velocityPxPerSecond = 2000f))
    }

    @Test
    fun closestAnchorIgnoresAnchorsBehindTheOffset() {
        // SwipeableV2.kt:768-775: cost = anchor - offset (up) or offset - anchor (down),
        // negative cost -> +inf, ties keep the first key in map order (Default, Dismissed).
        assertEquals(Default, SwipeToDismissMath.closestAnchor(anchors(), 0f, searchUpwards = true))
        assertEquals(Default, SwipeToDismissMath.closestAnchor(anchors(), 0f, searchUpwards = false))
        assertEquals(
            Dismissed,
            SwipeToDismissMath.closestAnchor(anchors(), 480f, searchUpwards = true)
        )
        // Offset past every anchor: both costs are negative, so upstream's minBy keeps the first
        // entry, which is Default.
        assertEquals(
            Default,
            SwipeToDismissMath.closestAnchor(anchors(), 1000f, searchUpwards = true)
        )
    }

    // endregion

    // region what a progress value looks like

    @Test
    fun contentScaleShrinksLinearlyAgainstProgress() {
        // lerp(1f, 0.7f, p) = (1 - p) * 1f + p * 0.7f (BasicSwipeToDismissBox.kt:185-187,
        // lerp from androidx.compose.ui.util), then coerced into 0.7f..1f.
        // p = 0    -> 1.000
        // p = 0.25 -> 0.75 * 1 + 0.25 * 0.7 = 0.75 + 0.175 = 0.925
        // p = 0.5  -> 0.5 + 0.35 = 0.85
        // p = 1    -> 0 + 0.7 = 0.7
        assertEquals(1f, SwipeToDismissMath.contentScale(0f), 1e-5f)
        assertEquals(0.925f, SwipeToDismissMath.contentScale(0.25f), 1e-5f)
        assertEquals(0.85f, SwipeToDismissMath.contentScale(0.5f), 1e-5f)
        assertEquals(0.7f, SwipeToDismissMath.contentScale(1f), 1e-5f)
        // The clamp: at full dismissal the scale sits exactly on SCALE_MIN, and no progress value
        // can push past it.
        assertEquals(SwipeToDismissMath.SCALE_MIN, SwipeToDismissMath.contentScale(1f), 0f)
        assertEquals(SwipeToDismissMath.SCALE_MAX, SwipeToDismissMath.contentScale(0f), 0f)
    }

    @Test
    fun contentScrimSaturatesAtSixtyPercent() {
        // min(progress / 2f, 0.3f) (BasicSwipeToDismissBox.kt:210-216).
        // p = 0    -> 0 / 2   = 0
        // p = 0.25 -> 0.25/2  = 0.125
        // p = 0.5  -> 0.5 / 2 = 0.25
        // p = 1    -> 0.5 -> capped to 0.3
        assertEquals(0f, SwipeToDismissMath.contentScrimAlpha(0f), 0f)
        assertEquals(0.125f, SwipeToDismissMath.contentScrimAlpha(0.25f), 1e-5f)
        assertEquals(0.25f, SwipeToDismissMath.contentScrimAlpha(0.5f), 1e-5f)
        assertEquals(0.3f, SwipeToDismissMath.contentScrimAlpha(1f), 1e-5f)
        // The cap starts biting at p = 0.6, which is where 0.6 / 2 == MAX_CONTENT_SCRIM_ALPHA.
        assertEquals(0.275f, SwipeToDismissMath.contentScrimAlpha(0.55f), 1e-5f)
        assertEquals(0.3f, SwipeToDismissMath.contentScrimAlpha(0.65f), 1e-5f)
    }

    @Test
    fun backgroundScrimFadesOutAsTheForegroundLifts() {
        // 0.5f * (1 - progress) (BasicSwipeToDismissBox.kt:231-238).
        // p = 0    -> 0.5 * 1.00 = 0.5
        // p = 0.25 -> 0.5 * 0.75 = 0.375
        // p = 0.5  -> 0.5 * 0.50 = 0.25
        // p = 1    -> 0.5 * 0.00 = 0
        assertEquals(0.5f, SwipeToDismissMath.backgroundScrimAlpha(0f), 1e-5f)
        assertEquals(0.375f, SwipeToDismissMath.backgroundScrimAlpha(0.25f), 1e-5f)
        assertEquals(0.25f, SwipeToDismissMath.backgroundScrimAlpha(0.5f), 1e-5f)
        assertEquals(0f, SwipeToDismissMath.backgroundScrimAlpha(1f), 1e-5f)
        // The two scrims cross where p / 2 == 0.5 * (1 - p), i.e. p = 0.5, both at 0.25 - which is
        // what makes the handover from foreground to background read as a single fade.
        assertEquals(
            SwipeToDismissMath.backgroundScrimAlpha(0.5f),
            SwipeToDismissMath.contentScrimAlpha(0.5f),
            1e-5f,
        )
    }

    @Test
    fun squeezePushesTheShrunkContentRightByHalfTheGap() {
        // squeezeOffset = max(0f, (1f - scale) * maxWidthPx / 2f) (:188-189), scale from the test
        // above. At p = 0.5 on a 960 px screen: (1 - 0.85) * 960 / 2 = 0.15 * 480 = 72.
        assertEquals(72f, SwipeToDismissMath.squeezeOffsetPx(0.5f, 960f), 0.01f)
        // At rest the content is full size, so the squeeze is zero; at full dismissal
        // (1 - 0.7) * 960 / 2 = 0.3 * 480 = 144.
        assertEquals(0f, SwipeToDismissMath.squeezeOffsetPx(0f, 960f), 0f)
        assertEquals(144f, SwipeToDismissMath.squeezeOffsetPx(1f, 960f), 0.01f)
    }

    @Test
    fun translationSlidesToTheFullWidthOnlyAfterSeventyPercent() {
        // squeezeMode: translationX == squeezeOffset (:191-194).
        // slide: lerp(squeezeOffset, maxWidthPx, max(0f, progress - 0.7f) / 0.3f) (:196-201).
        // p = 0.5 -> max(0, -0.2)/0.3 = 0 -> lerp(72, 960, 0) = 72, i.e. still squeezed.
        assertEquals(72f, SwipeToDismissMath.contentTranslationXPx(0.5f, 960f, true), 0.01f)
        assertEquals(72f, SwipeToDismissMath.contentTranslationXPx(0.5f, 960f, false), 0.01f)
        // p = 1 -> squeezeOffset = 144, weight = (1 - 0.7)/0.3 = 1 -> lerp(144, 960, 1) = 960.
        assertEquals(144f, SwipeToDismissMath.contentTranslationXPx(1f, 960f, true), 0.01f)
        assertEquals(960f, SwipeToDismissMath.contentTranslationXPx(1f, 960f, false), 0.01f)
        // p = 0.7 is exactly where the slide starts: weight 0, squeezeOffset
        // = (1 - (1 - 0.3 * 0.7)) * 480 = 0.21 * 480 = 100.8.
        assertEquals(100.8f, SwipeToDismissMath.contentTranslationXPx(0.7f, 960f, false), 0.01f)
    }

    // endregion

    // region the state machine

    @Test
    fun squeezeModeIsOffWhileDismissingAndBackOnAtDefault() {
        // LaunchedEffect(state.isAnimationRunning) { if (targetValue == Dismissed) false } (:159-163)
        assertFalse(SwipeToDismissMath.squeezeModeAfterAnimationChange(true, Dismissed))
        assertFalse(SwipeToDismissMath.squeezeModeAfterAnimationChange(false, Dismissed))
        // The body only acts on Dismissed; a Default target leaves the flag alone.
        assertTrue(SwipeToDismissMath.squeezeModeAfterAnimationChange(true, Default))
        assertFalse(SwipeToDismissMath.squeezeModeAfterAnimationChange(false, Default))
        // LaunchedEffect(state.targetValue) { if (!squeezeMode && targetValue == Default) true }
        // (:164-168).
        assertTrue(SwipeToDismissMath.squeezeModeAfterTargetChange(false, Default))
        assertTrue(SwipeToDismissMath.squeezeModeAfterTargetChange(true, Default))
        assertFalse(SwipeToDismissMath.squeezeModeAfterTargetChange(false, Dismissed))
    }

    @Test
    fun aFullDismissalRoundTripRestoresSqueezeMode() {
        var squeezeMode = true
        // Drag past 480 px with no fling: targetValue becomes Dismissed while the finger is still
        // down (settleTarget at velocity 0f -> Dismissed), the animation to Dismissed starts, and
        // squeezeMode drops.
        assertEquals(Dismissed, settle(500f, from = Default))
        squeezeMode = SwipeToDismissMath.squeezeModeAfterAnimationChange(squeezeMode, Dismissed)
        assertFalse(squeezeMode)
        // The box then snaps back to Default (BasicSwipeToDismissBox.kt:305-309 does exactly this
        // once currentValue reaches Dismissed); the target change puts squeezing back on.
        squeezeMode = SwipeToDismissMath.squeezeModeAfterTargetChange(squeezeMode, Default)
        assertTrue(squeezeMode)
    }

    @Test
    fun settleLandsOnTheValueWhoseAnchorItStoppedNextTo() {
        // `anchors.entries.firstOrNull { abs(anchorOffset - endOffset) < 0.5f }?.key`, and the
        // previous value is kept when nothing is that close (SwipeableV2.kt:420-425).
        assertEquals(Default, SwipeToDismissMath.settledValue(anchors(), 0f))
        assertEquals(Dismissed, SwipeToDismissMath.settledValue(anchors(), 960f))
        // 960 - 959.7 = 0.3 < 0.5, but |0 - 959.7| is not, so Dismissed wins on the first match.
        assertEquals(Dismissed, SwipeToDismissMath.settledValue(anchors(), 959.7f))
        // 0.6 px off the Default anchor is outside the 0.5 px window, and the other anchor is far:
        // upstream keeps the previous value rather than snapping the enum.
        assertNull(SwipeToDismissMath.settledValue(anchors(), 0.6f))
        assertNull(SwipeToDismissMath.settledValue(anchors(), 480f))
    }

    @Test
    fun thresholdsAreTheUpstreamConstants() {
        // Copied from BasicSwipeToDismissBox.kt:637-642 and SwipeableV2.kt:672, not re-derived:
        // a typo here silently changes when a screen dismisses.
        assertEquals(0.5f, SwipeToDismissMath.SWIPE_THRESHOLD, 0f)
        assertEquals(1f, SwipeToDismissMath.SCALE_MAX, 0f)
        assertEquals(0.7f, SwipeToDismissMath.SCALE_MIN, 0f)
        assertEquals(0.3f, SwipeToDismissMath.MAX_CONTENT_SCRIM_ALPHA, 0f)
        assertEquals(0.5f, SwipeToDismissMath.MAX_BACKGROUND_SCRIM_ALPHA, 0f)
        assertEquals(800f, SwipeToDismissMath.VELOCITY_THRESHOLD_DP, 0f)
        assertEquals(480f, SwipeToDismissMath.positionalThresholdPx(960f), 0f)
        assertEquals(1600f, SwipeToDismissMath.velocityThresholdPx(2f), 0f)
    }

    // endregion
}
