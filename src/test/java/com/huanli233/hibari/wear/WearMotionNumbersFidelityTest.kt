package com.huanli233.hibari.wear

import com.huanli233.hibari.animation.CubicBezierEasing
import com.huanli233.hibari.animation.FiniteAnimationSpec
import com.huanli233.hibari.animation.SpringSpec
import com.huanli233.hibari.wear.tokens.MotionDurationTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The motion numbers: the six springs `MotionScheme` hands out, the `MotionTokens` duration table, and
 * the reveal geometry that decides where a released swipe settles.
 *
 * All three are pure. The springs are `material3/MotionScheme.kt:116-247` over the private constants
 * at `:259-276`; the durations are `tokens/MotionTokens.kt:30-45`; and the reveal rules are
 * `material3/SwipeToReveal.kt`'s private `computeTarget` (`:1832-1883`), whose file-private constants
 * (`:1910-1967`) are collected on [RevealMath] because nothing outside that file can reach them.
 */
class WearMotionNumbersFidelityTest {

    /** Every spec the two schemes hand out is a `SpringSpec`; the cast is upstream's own shape. */
    private fun spring(spec: FiniteAnimationSpec<Float>): SpringSpec<Float> = spec as SpringSpec<Float>

    private fun stiffnessOf(spec: FiniteAnimationSpec<Float>): Float = spring(spec).stiffness

    private fun dampingOf(spec: FiniteAnimationSpec<Float>): Float = spring(spec).dampingRatio

    // ---- MotionScheme -----------------------------------------------------------------------------

    @Test
    fun standardSchemeSpatialSpringsUseTheThreeStiffnesses() {
        // `material3/MotionScheme.kt:267-269` with `StandardSpatialDampingRatio = 1.0f` (`:260`).
        val standard = MotionScheme.standard()
        assertEquals(1f, dampingOf(standard.defaultSpatialSpec<Float>()), 0f)
        assertEquals(500f, stiffnessOf(standard.defaultSpatialSpec<Float>()), 0f)
        assertEquals(1400f, stiffnessOf(standard.fastSpatialSpec<Float>()), 0f)
        assertEquals(260f, stiffnessOf(standard.slowSpatialSpec<Float>()), 0f)
        assertEquals(1f, dampingOf(standard.slowSpatialSpec<Float>()), 0f)
    }

    @Test
    fun effectsSpringsDifferFromSpatialOnlyByNothingInTheStandardScheme() {
        // Both families use the same three stiffnesses (`:263-265` against `:267-269`) and both
        // damping ratios are 1f, `Spring.DampingRatioNoBouncy` (`:261`, which hibari-animation spells
        // as 1f at `VectorizedAnimationSpec.kt:837`). So a standard spatial spec and its effects twin
        // are the same value, which is why `SpringSpec.equals` answers true here.
        val standard = MotionScheme.standard()
        assertEquals(
            spring(standard.defaultSpatialSpec<Float>()),
            spring(standard.defaultEffectsSpec<Float>()),
        )
        assertEquals(
            spring(standard.fastSpatialSpec<Float>()),
            spring(standard.fastEffectsSpec<Float>()),
        )
        assertEquals(
            spring(standard.slowSpatialSpec<Float>()),
            spring(standard.slowEffectsSpec<Float>()),
        )
        assertEquals(1f, dampingOf(standard.fastEffectsSpec<Float>()), 0f)
    }

    @Test
    fun expressiveSchemeBendsTheThreeSpatialSpringsAndLeavesEffectsAlone() {
        // `material3/MotionScheme.kt:271-276`.
        val expressive = MotionScheme.expressive()
        assertEquals(0.75f, dampingOf(expressive.defaultSpatialSpec<Float>()), 0f)
        assertEquals(350f, stiffnessOf(expressive.defaultSpatialSpec<Float>()), 0f)
        assertEquals(0.7f, dampingOf(expressive.fastSpatialSpec<Float>()), 0f)
        assertEquals(800f, stiffnessOf(expressive.fastSpatialSpec<Float>()), 0f)
        assertEquals(0.8f, dampingOf(expressive.slowSpatialSpec<Float>()), 0f)
        assertEquals(200f, stiffnessOf(expressive.slowSpatialSpec<Float>()), 0f)
        // The effects trio is shared verbatim with `standard()`, so it must not drift.
        val standard = MotionScheme.standard()
        assertEquals(
            spring(standard.defaultEffectsSpec<Float>()),
            spring(expressive.defaultEffectsSpec<Float>()),
        )
        assertEquals(
            spring(standard.slowEffectsSpec<Float>()),
            spring(expressive.slowEffectsSpec<Float>()),
        )
        assertNotEquals(
            spring(expressive.defaultSpatialSpec<Float>()),
            spring(standard.defaultSpatialSpec<Float>()),
        )
    }

    // ---- MotionDurationTokens ---------------------------------------------------------------------

    @Test
    fun durationTableIsTheWearSpeedScaleVerbatim() {
        // `tokens/MotionTokens.kt:30-45`, milliseconds. A transposed digit is invisible in review and
        // only reads as "this animation lingers".
        val durations = listOf(
            MotionDurationTokens.DurationShort1 to 50,
            MotionDurationTokens.DurationShort2 to 100,
            MotionDurationTokens.DurationShort3 to 150,
            MotionDurationTokens.DurationShort4 to 200,
            MotionDurationTokens.DurationMedium1 to 250,
            MotionDurationTokens.DurationMedium2 to 300,
            MotionDurationTokens.DurationMedium3 to 350,
            MotionDurationTokens.DurationMedium4 to 400,
            MotionDurationTokens.DurationLong1 to 450,
            MotionDurationTokens.DurationLong2 to 500,
            MotionDurationTokens.DurationLong3 to 550,
            MotionDurationTokens.DurationLong4 to 600,
            MotionDurationTokens.DurationExtraLong1 to 700,
            MotionDurationTokens.DurationExtraLong2 to 800,
            MotionDurationTokens.DurationExtraLong3 to 900,
            MotionDurationTokens.DurationExtraLong4 to 1000,
        )
        assertEquals(16, durations.size)
        for ((actual, expected) in durations) {
            assertEquals(expected, actual)
        }
        // Each band steps by 50 ms and the extra-long band by 100, so one typo breaks two rows.
        assertEquals(50, MotionDurationTokens.DurationMedium2 - MotionDurationTokens.DurationMedium1)
        assertEquals(
            100,
            MotionDurationTokens.DurationExtraLong2 - MotionDurationTokens.DurationExtraLong1,
        )
    }

    // ---- RevealMath -------------------------------------------------------------------------------

    @Test
    fun revealConstantsAreTheUpstreamFilePrivates() {
        // `material3/SwipeToReveal.kt:1910, 1916, 1922, 1859, 1927-1936, 1948-1967`.
        assertEquals(500L, RevealMath.HAPTIC_DEBOUNCING_TIME)
        assertEquals(200f, RevealMath.VELOCITY_NEAR_THRESHOLD_DP, 0f)
        assertEquals(800f, RevealMath.VELOCITY_REVEALED_THRESHOLD_DP, 0f)
        assertEquals(800f, RevealMath.LEGACY_VELOCITY_THRESHOLD_DP, 0f)
        assertEquals(50, RevealMath.SHORT_ANIMATION)
        assertEquals(100, RevealMath.FLASH_ANIMATION)
        assertEquals(200, RevealMath.RAPID_ANIMATION)
        assertEquals(250, RevealMath.QUICK_ANIMATION)
        assertEquals(0.75f, RevealMath.FULL_SWIPE_THRESHOLD_FRACTION, 0f)
        assertEquals(0.06f, RevealMath.BUTTON_VISIBLE_FRACTION, 0f)
        assertEquals(0.12f, RevealMath.BUTTON_FADE_IN_END_FRACTION, 0f)
        assertEquals(0.15f, RevealMath.SINGLE_ICON_VISIBLE_FRACTION, 0f)
        assertEquals(0.30f, RevealMath.DOUBLE_ICON_VISIBLE_FRACTION, 0f)
        assertEquals(0.21f, RevealMath.SINGLE_ICON_FADE_IN_END_FRACTION, 0f)
        assertEquals(0.36f, RevealMath.DOUBLE_ICON_FADE_IN_END_FRACTION, 0f)
        assertEquals(0.0625f, RevealMath.FULL_SCREEN_PADDING_FRACTION, 0f)
        assertEquals(CubicBezierEasing(0.20f, 0.0f, 0.0f, 1.00f), RevealMath.StandardInOut)
        // The published artifact ships the flag on (`WearComposeMaterial3Flags.kt:19-22`), so every
        // branch the tests below take except `flagOffFling...` is the on-path.
        assertTrue(RevealMath.IS_DUAL_FLING_THRESHOLD_ENABLED)
    }

    @Test
    fun iconFadeWindowDependsOnWhetherASecondActionExists() {
        // `startFadeInFraction` / `endFadeInFraction` (`material3/SwipeToReveal.kt:1744-1756`).
        assertEquals(0.15f, RevealMath.startFadeInFraction(hasSecondaryAction = false), 0f)
        assertEquals(0.30f, RevealMath.startFadeInFraction(hasSecondaryAction = true), 0f)
        assertEquals(0.21f, RevealMath.endFadeInFraction(hasSecondaryAction = false), 0f)
        assertEquals(0.36f, RevealMath.endFadeInFraction(hasSecondaryAction = true), 0f)
        // The shared ramp (`:1129-1135`): strictly above the start, clamped at the end.
        assertEquals(0f, RevealMath.fadeInFraction(10f, 10f, 20f), 0f)
        assertEquals(0.5f, RevealMath.fadeInFraction(15f, 10f, 20f), 0f)
        assertEquals(1f, RevealMath.fadeInFraction(30f, 10f, 20f), 0f)
        assertEquals(0f, RevealMath.fadeInFraction(0f, 10f, 20f), 0f)
        // ... and an unmeasured screen keeps the button hidden instead of dividing by zero.
        assertEquals(0f, RevealMath.buttonAlpha(50f, 0f), 0f)
    }

    @Test
    fun revealThresholdAndRatioFollowTheDualFlingFlag() {
        // `revealThresholdPx` (`:362-372`): 75% of the screen minus the component's own inset.
        assertEquals(146f, RevealMath.revealThresholdPx(200f, 192f, 60f), 1e-4f)
        // Flag off, the threshold is the revealing anchor itself (the `:1859` branch's value).
        assertEquals(
            60f,
            RevealMath.revealThresholdPx(200f, 192f, 60f, isDualFlingEnabled = false),
            0f,
        )
        // `calculateRevealedRatio` (`:822-836`) answers 0.5 for a single action or a full-width
        // anchor, and otherwise never goes negative however far the revealing anchor sits.
        assertEquals(0.5f, RevealMath.calculateRevealedRatio(true, 100f, 200f, 200f, 60f), 0f)
        assertEquals(0.5f, RevealMath.calculateRevealedRatio(false, 200f, 200f, 200f, 60f), 0f)
        assertEquals(
            0.6142857f,
            RevealMath.calculateRevealedRatio(false, 100f, 200f, 192f, 60f),
            1e-5f,
        )
        assertEquals(0f, RevealMath.calculateRevealedRatio(false, 100f, 200f, 200f, 180f), 0f)
    }

    @Test
    fun anchorSetOrderDecidesEveryTie() {
        // `anchorsFor` (`:383-395`) builds `Covered` first, and `nearestAnchor` (`:1845`, `:1852`)
        // keeps the first key on a tie, so an item parked 30px off `Covered` closes rather than
        // revealing.
        val anchors = RevealMath.anchorsFor(
            screenWidthPx = 200f,
            revealingAnchorPx = 60f,
            direction = 1,
            isBidirectional = true,
        )
        assertEquals(5, anchors.size)
        assertEquals(RevealValue.Covered, RevealMath.nearestAnchor(anchors, -30f))
        assertEquals(-200f, RevealMath.minPosition(anchors), 0f)
        assertEquals(200f, RevealMath.maxPosition(anchors), 0f)
        // No revealing anchor at all and both `Revealing` keys are absent (`:356`).
        assertEquals(3, RevealMath.anchorsFor(200f, null, 1, true).size)
        // A one-sided set keeps the left keys off the map, in upstream's insertion order.
        assertEquals(
            listOf(RevealValue.Covered, RevealValue.RightRevealed, RevealValue.RightRevealing),
            RevealMath.anchorsFor(200f, 60f, 1, false).keys.toList(),
        )
    }

    @Test
    fun aStillReleasedSwipeIgnoresDirection() {
        val anchors = RevealMath.anchorsFor(200f, 60f, 1, true)
        var fastFlingRequests = 0
        val onFling: () -> Unit = { fastFlingRequests++ }
        assertEquals(
            RevealValue.Covered,
            RevealMath.computeTarget(-30f, 0f, anchors, { _, _ -> 0f }, { it }, onFling),
        )
        assertEquals(0, fastFlingRequests)
    }

    @Test
    fun aFastFlingBypassesTheAnchorSetAndAsksForThePartialHapticToBeSkipped() {
        // `computeTarget` (`:1846-1856`): at or over `VelocityRevealedThreshold` the far end in the
        // direction of travel wins outright, and `onFastFling` - the only writer of
        // `RevealState.skipPartialHaptic` (`:1036-1045`) - runs exactly once per fling.
        val anchors = RevealMath.anchorsFor(200f, 60f, 1, true)
        var fastFlingRequests = 0
        val onFling: () -> Unit = { fastFlingRequests++ }
        val noThreshold: (Float, Boolean) -> Float = { _, _ -> 0f }
        val densityOne: (Float) -> Float = { it }
        assertEquals(
            RevealValue.RightRevealed,
            RevealMath.computeTarget(-30f, -1000f, anchors, noThreshold, densityOne, onFling),
        )
        assertEquals(
            RevealValue.LeftRevealed,
            RevealMath.computeTarget(-30f, 1000f, anchors, noThreshold, densityOne, onFling),
        )
        assertEquals(2, fastFlingRequests)
        // Between the two thresholds nothing is bypassed and the callback is not run: this is the
        // "advance one anchor" case, and backwards from -30 that is `RightRevealing`, one item away.
        fastFlingRequests = 0
        assertEquals(
            RevealValue.RightRevealing,
            RevealMath.computeTarget(-30f, -300f, anchors, noThreshold, densityOne, onFling),
        )
        assertEquals(
            RevealValue.Covered,
            RevealMath.computeTarget(-30f, 300f, anchors, noThreshold, densityOne, onFling),
        )
        assertEquals(0, fastFlingRequests)
    }

    @Test
    fun aSlowSwipeIsJudgedAgainstTheAnchorItLeft() {
        val anchors = RevealMath.anchorsFor(200f, 60f, 1, true)
        var distance = -1f
        var completing: Boolean? = null
        val threshold: (Float, Boolean) -> Float = { total, isCompleting ->
            distance = total
            completing = isCompleting
            40f
        }
        val densityOne: (Float) -> Float = { it }
        // The flanking anchors are `RightRevealing` (-60) and `Covered` (0), 60 apart, and the offset
        // is 30 away from the one it came from, so a 40 threshold holds it there.
        assertEquals(
            RevealValue.RightRevealing,
            RevealMath.computeTarget(-30f, 100f, anchors, threshold, densityOne, {}),
        )
        assertEquals(60f, distance, 0f)
        assertEquals(false, completing)
        // Under the threshold the same offset crosses over instead.
        assertEquals(
            RevealValue.Covered,
            RevealMath.computeTarget(-30f, 100f, anchors, { _, _ -> 20f }, densityOne, {}),
        )
    }

    @Test
    fun completingTransitionMeasuresFromTheRevealingAnchorAndAsksForACustomThreshold() {
        // The `isCompleting` pair (`:1871-1873`) is `RightRevealed` -> `RightRevealing`, their
        // distance is the full 140px, and the origin flips to the Revealing anchor (`:1875-1877`), so
        // an offset of -100 counts as 40 travelled, not 100.
        val anchors = RevealMath.anchorsFor(200f, 60f, 1, false)
        var distance = -1f
        var completing: Boolean? = null
        val densityOne: (Float) -> Float = { it }
        assertEquals(
            RevealValue.RightRevealing,
            RevealMath.computeTarget(
                currentOffset = -100f,
                velocity = 100f,
                anchors = anchors,
                positionalThreshold = { total, isCompleting ->
                    distance = total
                    completing = isCompleting
                    50f
                },
                velocityThresholdPx = densityOne,
                onFastFling = {},
            ),
        )
        assertEquals(140f, distance, 0f)
        assertEquals(true, completing)
        // The same numbers with a threshold the offset has already passed complete instead.
        assertEquals(
            RevealValue.RightRevealed,
            RevealMath.computeTarget(-100f, 100f, anchors, { _, _ -> 30f }, densityOne, {}),
        )
    }

    @Test
    fun flagOffFlingOnlyAdvancesOneAnchor() {
        // The `!isSwipeToRevealDualFlingThresholdEnabled` branch (`:1857-1861`) has no far-end
        // bypass, so even 1000dp/s takes a single step and never reports a fast fling.
        val anchors = RevealMath.anchorsFor(200f, 60f, 1, true)
        var fastFlingRequests = 0
        assertEquals(
            RevealValue.RightRevealing,
            RevealMath.computeTarget(
                currentOffset = -30f,
                velocity = -1000f,
                anchors = anchors,
                positionalThreshold = { _, _ -> 0f },
                velocityThresholdPx = { it },
                onFastFling = { fastFlingRequests++ },
                isDualFlingEnabled = false,
            ),
        )
        assertEquals(0, fastFlingRequests)
    }
}
