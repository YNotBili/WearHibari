package com.huanli233.hibari.wear

import com.huanli233.hibari.animation.AnimationVector
import com.huanli233.hibari.animation.FiniteAnimationSpec
import com.huanli233.hibari.animation.Spring
import com.huanli233.hibari.animation.TwoWayConverter
import com.huanli233.hibari.animation.spring
import com.huanli233.hibari.runtime.staticTunationLocalOf

/**
 * Ported from androidx.wear.compose.material3.MotionScheme (material3/MotionScheme.kt:34-249), the
 * six springs every wear component animates on.
 *
 * Hibari's [com.huanli233.hibari.animation] module is a port of compose-animation-core, so
 * `FiniteAnimationSpec`, `spring` and `Spring` all exist with their upstream shapes and the spec
 * getters below keep upstream's exact signatures rather than a duration-table stand-in. That is why
 * this is a real `MotionScheme` and not a bag of constants.
 *
 * Deviation from upstream, forced:
 * - Upstream's `@Immutable` (material3/MotionScheme.kt:34) is a Compose runtime annotation about
 *   snapshot read stability; Hibari has no `@Immutable`/`@Stable`, so it is dropped, as everywhere
 *   else in this module. The schemes are stateless anyway — each of the six getters returns one of
 *   six pre-built springs held by the anonymous object.
 *
 * Retrieval follows upstream exactly: `MaterialTheme.motionScheme` is a member of the
 * `MaterialTheme` object (material3/MaterialTheme.kt:88-89), fed by the
 * `MaterialTheme(motionScheme = ...)` parameter (`:59`) into `LocalMotionScheme provides
 * motionScheme` (`:68`), all three of which are in `Theme.kt`.
 *
 * Not ported:
 * - Upstream's `// TODO - These values should come from Tokens.` (material3/MotionScheme.kt:259) is
 *   still a TODO upstream — [com.huanli233.hibari.wear.tokens.MotionDurationTokens] holds no
 *   stiffness or damping values at all, only millisecond durations, and `MotionScheme` upstream
 *   exposes no duration API either. Anything wanting milliseconds keeps reading the durations.
 *
 * The `internal fun <T> FiniteAnimationSpec<T>.faster(fraction)` family several components apply to
 * these specs (`fastSpatialSpec().faster(200f)`) is a separate file here too, `AnimationSpecUtils.kt`,
 * mirroring upstream's own `material3/AnimationSpecUtils.kt:55-116`. The six specs below are the raw
 * springs, exactly as upstream hands them out.
 */
interface MotionScheme {
    /**
     * A default spatial motion [FiniteAnimationSpec].
     *
     * This motion spec is designed to be applied to animations that can overshoot their targets
     * - such as shape or bounds animations. For color, alpha or other animations which have strict
     *   limits use the `effects` equivalent.
     *
     * [T] is the generic data type that will be animated by the system, as long as the appropriate
     * [TwoWayConverter] for converting the data to and from an [AnimationVector] is supplied.
     */
    fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T>

    /**
     * A fast spatial motion [FiniteAnimationSpec].
     *
     * This motion spec is designed to be applied to animations that can overshoot their targets
     * - such as shape or bounds animations. For color, alpha or other animations which have strict
     *   limits use the `effects` equivalent.
     *
     * [T] is the generic data type that will be animated by the system, as long as the appropriate
     * [TwoWayConverter] for converting the data to and from an [AnimationVector] is supplied.
     */
    fun <T> fastSpatialSpec(): FiniteAnimationSpec<T>

    /**
     * A slow spatial motion [FiniteAnimationSpec].
     *
     * This motion spec is designed to be applied to animations that can overshoot their targets
     * - such as shape or bounds animations. For color, alpha or other animations which have strict
     *   limits use the `effects` equivalent.
     *
     * [T] is the generic data type that will be animated by the system, as long as the appropriate
     * [TwoWayConverter] for converting the data to and from an [AnimationVector] is supplied.
     */
    fun <T> slowSpatialSpec(): FiniteAnimationSpec<T>

    /**
     * A default effects motion [FiniteAnimationSpec].
     *
     * This motion spec is designed to be applied to animations that have strict limits and which
     * shouldn't overshoot their targets - such as color or alpha animations. For shape, bounds or
     * other animations which don't have strict limits use the 'spatial` equivalent.
     *
     * [T] is the generic data type that will be animated by the system, as long as the appropriate
     * [TwoWayConverter] for converting the data to and from an [AnimationVector] is supplied.
     */
    fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T>

    /**
     * A fast effects motion [FiniteAnimationSpec].
     *
     * This motion spec is designed to be applied to animations that have strict limits and which
     * shouldn't overshoot their targets - such as color or alpha animations. For shape, bounds or
     * other animations which don't have strict limits use the 'spatial` equivalent.
     *
     * [T] is the generic data type that will be animated by the system, as long as the appropriate
     * [TwoWayConverter] for converting the data to and from an [AnimationVector] is supplied.
     */
    fun <T> fastEffectsSpec(): FiniteAnimationSpec<T>

    /**
     * A slow effects motion [FiniteAnimationSpec].
     *
     * This motion spec is designed to be applied to animations that have strict limits and which
     * shouldn't overshoot their targets - such as color or alpha animations. For shape, bounds or
     * other animations which don't have strict limits use the 'spatial` equivalent.
     *
     * [T] is the generic data type that will be animated by the system, as long as the appropriate
     * [TwoWayConverter] for converting the data to and from an [AnimationVector] is supplied.
     */
    fun <T> slowEffectsSpec(): FiniteAnimationSpec<T>

    companion object {
        /**
         * Returns a standard Material motion scheme.
         *
         * The standard scheme is Material's basic motion scheme for utilitarian UI elements and
         * recurring interactions. It provides a linear motion feel.
         *
         * Upstream material3/MotionScheme.kt:115-177.
         */
        @Suppress("UNCHECKED_CAST")
        fun standard(): MotionScheme =
            object : MotionScheme {
                private val defaultSpatialSpec: FiniteAnimationSpec<Any> =
                    spring<Any>(
                        dampingRatio = StandardSpatialDampingRatio,
                        stiffness = StandardDefaultStiffness,
                    )

                private val fastSpatialSpec: FiniteAnimationSpec<Any> =
                    spring<Any>(
                        dampingRatio = StandardSpatialDampingRatio,
                        stiffness = StandardFastStiffness,
                    )

                private val slowSpatialSpec: FiniteAnimationSpec<Any> =
                    spring<Any>(
                        dampingRatio = StandardSpatialDampingRatio,
                        stiffness = StandardSlowStiffness,
                    )

                private val defaultEffectsSpec: FiniteAnimationSpec<Any> =
                    spring<Any>(
                        dampingRatio = EffectsDampingRatio,
                        stiffness = EffectsDefaultStiffness,
                    )

                private val fastEffectsSpec: FiniteAnimationSpec<Any> =
                    spring<Any>(
                        dampingRatio = EffectsDampingRatio,
                        stiffness = EffectsFastStiffness,
                    )

                private val slowEffectsSpec: FiniteAnimationSpec<Any> =
                    spring<Any>(
                        dampingRatio = EffectsDampingRatio,
                        stiffness = EffectsSlowStiffness,
                    )

                override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> {
                    return defaultSpatialSpec as FiniteAnimationSpec<T>
                }

                override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> {
                    return fastSpatialSpec as FiniteAnimationSpec<T>
                }

                override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> {
                    return slowSpatialSpec as FiniteAnimationSpec<T>
                }

                override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> {
                    return defaultEffectsSpec as FiniteAnimationSpec<T>
                }

                override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> {
                    return fastEffectsSpec as FiniteAnimationSpec<T>
                }

                override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> {
                    return slowEffectsSpec as FiniteAnimationSpec<T>
                }
            }

        /**
         * Returns an expressive Material motion scheme.
         *
         * The expressive scheme is Material's recommended motion scheme for prominent UI elements
         * and hero interactions. It provides a visually engaging motion feel.
         *
         * Upstream material3/MotionScheme.kt:179-247. The three spatial springs differ from
         * [standard]; the three effects springs are the same stiffness/damping pairs.
         */
        @Suppress("UNCHECKED_CAST")
        fun expressive(): MotionScheme =
            object : MotionScheme {
                private val defaultSpatialSpec: FiniteAnimationSpec<Any> =
                    spring<Any>(
                        dampingRatio = ExpressiveDefaultDamping,
                        stiffness = ExpressiveDefaultStiffness,
                    )

                private val fastSpatialSpec: FiniteAnimationSpec<Any> =
                    spring<Any>(
                        dampingRatio = ExpressiveFastDamping,
                        stiffness = ExpressiveFastStiffness,
                    )

                private val slowSpatialSpec: FiniteAnimationSpec<Any> =
                    spring<Any>(
                        dampingRatio = ExpressiveSlowDamping,
                        stiffness = ExpressiveSlowStiffness,
                    )

                private val defaultEffectsSpec: FiniteAnimationSpec<Any> =
                    spring<Any>(
                        dampingRatio = EffectsDampingRatio,
                        stiffness = EffectsDefaultStiffness,
                    )

                private val fastEffectsSpec: FiniteAnimationSpec<Any> =
                    spring<Any>(
                        dampingRatio = EffectsDampingRatio,
                        stiffness = EffectsFastStiffness,
                    )

                private val slowEffectsSpec: FiniteAnimationSpec<Any> =
                    spring<Any>(
                        dampingRatio = EffectsDampingRatio,
                        stiffness = EffectsSlowStiffness,
                    )

                override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> {
                    return defaultSpatialSpec as FiniteAnimationSpec<T>
                }

                override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> {
                    return fastSpatialSpec as FiniteAnimationSpec<T>
                }

                override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> {
                    return slowSpatialSpec as FiniteAnimationSpec<T>
                }

                override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> {
                    return defaultEffectsSpec as FiniteAnimationSpec<T>
                }

                override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> {
                    return fastEffectsSpec as FiniteAnimationSpec<T>
                }

                override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> {
                    return slowEffectsSpec as FiniteAnimationSpec<T>
                }
            }
    }
}

/**
 * Tunable local used to pass [MotionScheme] down the tree.
 *
 * Setting the value here is typically done as part of [MaterialTheme]. To retrieve the current
 * value, use [MaterialTheme.motionScheme]. Upstream's `staticCompositionLocalOf`
 * (material3/MotionScheme.kt:257) becomes Hibari's `staticTunationLocalOf`; the seed is the same
 * [MotionScheme.standard].
 *
 * Upstream keeps this `internal`, and so it is here: [MaterialTheme] in `Theme.kt` is the only
 * writer, and `MaterialTheme.motionScheme` — its object member, as upstream has it
 * (material3/MaterialTheme.kt:88-89) — the only intended reader.
 */
internal val LocalMotionScheme = staticTunationLocalOf { MotionScheme.standard() }

// TODO(upstream) - These values should come from Tokens. (material3/MotionScheme.kt:259)
private const val StandardSpatialDampingRatio = 1.0f
private const val EffectsDampingRatio = Spring.DampingRatioNoBouncy

internal const val EffectsDefaultStiffness = 500f
internal const val EffectsFastStiffness = 1400f
internal const val EffectsSlowStiffness = 260f

internal const val StandardDefaultStiffness = 500f
internal const val StandardFastStiffness = 1400f
internal const val StandardSlowStiffness = 260f

internal const val ExpressiveDefaultStiffness = 350f
internal const val ExpressiveFastStiffness = 800f
internal const val ExpressiveSlowStiffness = 200f
internal const val ExpressiveDefaultDamping = 0.75f
internal const val ExpressiveFastDamping = 0.7f
internal const val ExpressiveSlowDamping = 0.8f
