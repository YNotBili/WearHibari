package com.huanli233.hibari.wear

import com.huanli233.hibari.animation.AnimationVector1D
import com.huanli233.hibari.animation.FloatExponentialDecaySpec
import com.huanli233.hibari.animation.VectorConverter
import com.huanli233.hibari.animation.SpringSpec
import com.huanli233.hibari.animation.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The speed/delay combinators every wear component's motion is built from. These are arithmetic, so
 * they are checkable on the JVM without a device — and a wrong factor here shows up on screen only as
 * "the animation feels a bit off", which is not something a review catches.
 */
class AnimationSpecUtilsTest {

    private val zero = AnimationVector1D(0f)
    private val one = AnimationVector1D(1f)

    private fun durationMillis(spec: com.huanli233.hibari.animation.FiniteAnimationSpec<Float>): Long =
        spec.vectorize(Float.VectorConverter)
            .getDurationNanos(zero, one, zero) / 1_000_000L

    @Test
    fun speedFactorSquaresStiffnessOnly() {
        // `material3/AnimationSpecUtils.kt:60`: a spring is rebuilt with `stiffness * factor *
        // factor`, damping and threshold untouched, so `faster(100f)` is four times the stiffness.
        val base = SpringSpec<Float>(dampingRatio = 1f, stiffness = 1400f)
        val sped = base.speedFactor(2f) as SpringSpec<Float>
        assertEquals(5600f, sped.stiffness, 0.0001f)
        assertEquals(1f, sped.dampingRatio, 0.0001f)
    }

    @Test
    fun fasterAndSlowerAreInversePercentages() {
        val base = TweenSpec<Float>(durationMillis = 1000)
        assertEquals(500L, durationMillis(base.faster(100f)))
        assertEquals(2000L, durationMillis(base.slower(50f)))
        // 0f is documented as "no change" for both, and it is the identity for spring stiffness too.
        assertEquals(1400f, (SpringSpec<Float>(stiffness = 1400f).faster(0f) as SpringSpec<Float>).stiffness, 0.0001f)
        assertEquals(1000L, durationMillis(base.faster(0f)))
    }

    @Test
    fun delayAndSpeedDoNotCommute() {
        // The wrapped spec reports its own delay to the wrapper, so speeding up scales a delay that is
        // already inside, while a delay added on the outside is not scaled at all
        // (`getDurationNanos`, `:177-179`).
        val base = TweenSpec<Float>(durationMillis = 1000)
        assertEquals(550L, durationMillis(base.delayMillis(100).faster(100f)))
        assertEquals(600L, durationMillis(base.faster(100f).delayMillis(100)))
    }

    @Test
    fun combinatorsRejectTheirOwnDomain() {
        assertThrows(IllegalArgumentException::class.java) {
            TweenSpec<Float>().speedFactor(0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TweenSpec<Float>().faster(-1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TweenSpec<Float>().slower(100f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TweenSpec<Float>().delayMillis(-1)
        }
    }

    @Test
    fun whichSpecsAreSafeToRebuildPerTune() {
        // A `*Defaults` getter that builds a spring on every tune is still a stable attribute value,
        // because `SpringSpec.equals` compares damping/stiffness/threshold and `MotionScheme` hands out
        // pre-built springs anyway.
        assertEquals(
            MotionScheme.standard().defaultSpatialSpec<Float>(),
            MotionScheme.standard().defaultSpatialSpec<Float>(),
        )
        // The decay spec has no such `equals`, so a fresh one per tune is never equal to the previous
        // round's and re-applies its attribute every time — which is why `PagerDefaults` holds a single
        // shared instance behind `PagerFlingSpec`'s default instead of calling the constructor there.
        assertNotEquals(FloatExponentialDecaySpec(), FloatExponentialDecaySpec())
    }
}
