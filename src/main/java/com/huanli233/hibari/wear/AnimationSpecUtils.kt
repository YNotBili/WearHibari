/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huanli233.hibari.wear

import androidx.annotation.FloatRange
import com.huanli233.hibari.animation.AnimationVector
import com.huanli233.hibari.animation.AnimationVector1D
import com.huanli233.hibari.animation.AnimationVector2D
import com.huanli233.hibari.animation.AnimationVector3D
import com.huanli233.hibari.animation.AnimationVector4D
import com.huanli233.hibari.animation.FiniteAnimationSpec
import com.huanli233.hibari.animation.SpringSpec
import com.huanli233.hibari.animation.TwoWayConverter
import com.huanli233.hibari.animation.VectorizedFiniteAnimationSpec
import com.huanli233.hibari.runtime.withFrameMillis
import java.util.concurrent.TimeUnit

/**
 * Ported from `androidx.wear.compose.material3.AnimationSpecUtils`
 * (`material3/AnimationSpecUtils.kt:49-123`, `:125-192`, `:194-201` and `:265`), the `FiniteAnimationSpec`
 * speed/delay combinators the wear components hang their motion on, plus [waitUntil], the frame-clocked
 * bounded wait upstream's press animations sit on.
 *
 * `hibari-animation` is itself a port of `androidx.compose.animation.core`, so every type these
 * extensions touch — [FiniteAnimationSpec], [SpringSpec], [VectorizedFiniteAnimationSpec],
 * [AnimationVector] and its four sealed subclasses, [TwoWayConverter] — exists here with upstream's
 * exact shape and needs no stand-in: `SpringSpec(dampingRatio, stiffness, visibilityThreshold)` is
 * declared in that order at `AnimationSpec.kt:120-124`, `FiniteAnimationSpec.vectorize` at
 * `AnimationSpec.kt:62-66`, and `AnimationVector` is a `sealed class` with exactly the four vector
 * widths (`AnimationVectors.kt:12, 85, 128, 179, 239`), which is what makes [times]' `when`
 * exhaustive without an `else`. Nothing was invented and no number was substituted.
 *
 * Visibility follows upstream: all four combinators are `internal` there (`:55`, `:71`, `:84`, `:118`)
 * and so is [waitUntil] (`:194`), while `MAX_WAIT_TIME_MILLIS` is `private` there (`:265`) and stays
 * `private` here. Every consumer in this module — `MotionScheme.kt`, `view/WearButtonGroupView.kt`,
 * `ContainerDrawable.kt`, `IconButton.kt`, `Stepper.kt`, `ButtonGroup.kt`, `AlertDialog.kt`,
 * `TimePicker.kt`, `ConfirmationDialog.kt` — lives in the same compilation unit, so `internal` is
 * enough and no wider surface is published than upstream has.
 *
 * The `factor` arithmetic is upstream's, to the operation: [speedFactor] squares the factor onto the
 * spring's **stiffness only** (`:60`), leaving `dampingRatio` and `visibilityThreshold` alone, which is
 * why a `faster(100f)` (factor 2) is four times the stiffness rather than twice — `1400f -> 5600f` for
 * a `StiffnessMediumLow` press, as `view/WearButtonGroupView.kt` currently computes by hand in its
 * `ButtonGroupDownStiffness`.
 * Every other spec type is wrapped rather than rebuilt (`:61`), so a `TweenSpec`'s duration is
 * stretched by `1 / factor` and its easing curve is preserved, at the vectorised level.
 *
 * Deviations, both of them reach rather than behaviour:
 *  - [times] is `private` here where upstream declares it `internal` (`:183`). It exists only to scale
 *    a velocity by the speed factor inside [WrappedVectorizedAnimationSpec]. Publishing a generic
 *    `operator` extension at module scope would capture every `AnimationVector * Float` in this
 *    module, including the files other work is still landing, so the name stays local; nothing outside
 *    this file needs it.
 *  - `Unit`-carrying method: upstream's `getDurationNanos` is annotated
 *    `@Suppress("MethodNameUnits")` (`:176`) for a Compose-internal lint id that has no meaning in this
 *    project, so the suppression is dropped and the method kept.
 *
 * Not ported into *this file* — the rest of `AnimationSpecUtils.kt` is not spec arithmetic, and it
 * splits four ways: `waitUntil` is ported below, `animatedDelay` is ported but lives elsewhere, and the
 * two bullets below are each "not built here" for a reason stated in place — neither is a missing
 * engine, and neither should be read as a blocker:
 *  - `animateEnabledStateColor` (`:93-111`): a `@Composable` whose whole body is
 *    `animateColorAsState(...)`, and that function is declared nowhere in this repo (searched every
 *    module: zero hits). The old note excused it as "no substrate", which is wrong: `Animatable<T, V>`
 *    is public (`hibari-animation/.../Animatable.kt:31`), `animateFloatAsState` shows the `@Tunable`
 *    shape such a wrapper takes (`AnimateAsState.kt:61`), and `TwoWayConverter` with its factory are
 *    public (`VectorConverters.kt:20`, `:42`). The one leg that really is absent is the colour one —
 *    `VectorConverters.kt:57-98` declares nine `VectorConverter`s, for `Float`, `Int`, `Rect`, `Dp`,
 *    `DpOffset`, `Size`, `Offset`, `IntOffset` and `IntSize`, and none for `Color` — as is a host that
 *    retunes per animated value. So an equivalent here is a converter plus a small host, i.e. an
 *    addition, not the port of something missing. It stays unwritten because nothing needs it: the
 *    enabled/disabled and resting/pressed pairs ride inside [ContainerSpec], and [ContainerDrawable]
 *    swaps them on the view's drawable state, so the colour animates inside the draw rather than as an
 *    animated `Color` value — the route `IconButton.kt`, `TextButton.kt`, `Slider.kt` and
 *    `SwitchButton.kt` each record in their own headers.
 *  - `waitUntil` (`:194-201`) and `MAX_WAIT_TIME_MILLIS` (`:265`) **are** ported, at the bottom of this
 *    file. The claim this bullet used to make — that `withFrameMillis` does not exist — was false: it
 *    lives in `hibari-runtime`'s `MonotonicFrameClock.kt`, both as the `MonotonicFrameClock` member and
 *    as the context-resolving top-level used here, and `view/WearButtonGroupView.kt` was already
 *    polling the frame clock with a private copy of this helper that now folds into this one. Of
 *    upstream's three consumers, only `ButtonGroup.kt:466` has a call site here:
 *    `rememberAnimatedPressedButtonShape` (`material3/RoundButton.kt:114`) and
 *    `rememberAnimatedToggleRoundedCornerShape` (`material3/AnimatedToggleRoundedCornerShape.kt:130`)
 *    both wait on an `Animatable` press progress this module does not keep — their morph is
 *    interpolated by [ContainerDrawable] off `state_pressed` (see [ContainerSpec]) and the checked half
 *    is answered in composition — so there is no coroutine to park and no floor to wait for. Giving
 *    them a caller would be a second mechanism, not a port.
 *  - `animatedDelay` (`:203-208`) is **ported, but not into this file**: it is a delay policy rather
 *    than spec arithmetic, and its condition is upstream's `LocalReduceMotion`
 *    (`foundation/CompositionLocals.kt:39-55`), which has nothing to do with animation specs. It lives
 *    next to that read now, as `wearAnimatedDelay` in `ReduceMotion.kt`. Upstream's own call sites pair
 *    the two (`material3/ConfirmationDialog.kt:273` with `:278`,
 *    `material3/OpenOnPhoneDialog.kt:200` with `:204`), and the consumers here now pair them the same
 *    way — `ConfirmationDialogContent`, `SuccessConfirmationDialogContent`,
 *    `FailureConfirmationDialogContent`, `confirmationDialogContentWrapper` and
 *    `confirmationDialogIconContainer`, plus `OpenOnPhoneDialogContent`; the earlier branchless local
 *    copy, `dialogAnimatedDelay`, is gone.
 *  - `FadeLabel` (`:210-263`) is missing because nobody has written it, not because it cannot be
 *    written: the two blockers this bullet used to name are both false. [LocalTextConfiguration] landed
 *    (`TextConfiguration.kt:23`), and the `animationSpec.faster(200f)` at `:238` is this file's own
 *    [faster] (`:71-76`). Its remaining parts are all here too — [Text], `Animatable`, `LaunchedEffect`,
 *    `snapTo` (`Animatable.kt:359`) — so the whole of it is a component-shaped port, not a spec utility:
 *    upstream's `graphicsLayer { alpha = abs(animatedAlpha.value) }` (`:251`) has no Hibari counterpart
 *    and would land as `Modifier.alpha` on the label host, which is a per-frame retune unless the
 *    animatable is passed through `bindState`. Its two would-be consumers are already here and already
 *    documented: `TimePicker.kt:320` and `DatePicker.kt:382` each swap the heading text at once, with
 *    upstream's fade dropped.
 *
 * @see MotionScheme
 */

/**
 * Returns a new [FiniteAnimationSpec] that is a slower or faster version of this one.
 *
 * Ported from `material3/AnimationSpecUtils.kt:49-63`.
 *
 * @param factor How much to speed or slow the animation. 0f -> runs forever, zero speed (not
 *   allowed) 0.5f -> half speed 1f -> current speed 2f -> double speed
 */
internal fun <T> FiniteAnimationSpec<T>.speedFactor(
    @FloatRange(from = 0.0, fromInclusive = false) factor: Float,
): FiniteAnimationSpec<T> {
    require(factor > 0f) { "factor has to be positive. Was: $factor" }
    return when (this) {
        is SpringSpec -> SpringSpec(dampingRatio, stiffness * factor * factor, visibilityThreshold)
        else -> WrappedAnimationSpec(this, factor)
    }
}

/**
 * Returns a new [FiniteAnimationSpec] that is a faster version of this one.
 *
 * Ported from `material3/AnimationSpecUtils.kt:65-76`.
 *
 * @param speedupPct How much to speed up the animation, as a percentage of the current speed. 0f
 *   being no change, 100f being double, speed and so on.
 */
internal fun <T> FiniteAnimationSpec<T>.faster(
    @FloatRange(from = 0.0) speedupPct: Float,
): FiniteAnimationSpec<T> {
    require(speedupPct >= 0f) { "speedupPct has to be positive. Was: $speedupPct" }
    return speedFactor(1 + speedupPct / 100)
}

/**
 * Returns a new [FiniteAnimationSpec] that is a slower version of this one.
 *
 * Ported from `material3/AnimationSpecUtils.kt:78-91`.
 *
 * @param slowdownPct How much to slow down the animation, as a percentage of the current speed. 0f
 *   being no change, 50f being half the speed.
 */
internal fun <T> FiniteAnimationSpec<T>.slower(
    @FloatRange(from = 0.0, to = 100.0, toInclusive = false) slowdownPct: Float,
): FiniteAnimationSpec<T> {
    require(slowdownPct >= 0f && slowdownPct < 100f) {
        "slowdownPct has to be between 0 and 100. Was: $slowdownPct"
    }
    return speedFactor(1 - slowdownPct / 100)
}

/**
 * Returns a modified [FiniteAnimationSpec] with a delay of [startDelayMillis].
 *
 * Ported from `material3/AnimationSpecUtils.kt:113-123`. Note that this composes with [faster] in
 * either order and the result differs, exactly as it does upstream: [faster] divides the *whole*
 * duration the wrapped spec reports — a delay already folded in by [delayMillis] included — by its
 * factor, and only then is its own [startDelayMillis] added back on the outside
 * (`WrappedVectorizedAnimationSpec.getDurationNanos`, `:177-179`). So `delayMillis(100).faster(100f)`
 * shortens the wait to 50 ms, while `faster(100f).delayMillis(100)` keeps all 100 ms of it.
 *
 * @param startDelayMillis how long to delay before starting the animation, in ms.
 */
internal fun <T> FiniteAnimationSpec<T>.delayMillis(
    startDelayMillis: Long,
): FiniteAnimationSpec<T> {
    require(startDelayMillis >= 0) { "startDelayMillis has to be positive. Was: $startDelayMillis" }
    return WrappedAnimationSpec(this, 1f, TimeUnit.MILLISECONDS.toNanos(startDelayMillis))
}

/** `material3/AnimationSpecUtils.kt:125-134`. */
private class WrappedAnimationSpec<T>(
    val wrapped: FiniteAnimationSpec<T>,
    val speedupFactor: Float,
    val startDelayNanos: Long = 0,
) : FiniteAnimationSpec<T> {
    override fun <V : AnimationVector> vectorize(
        converter: TwoWayConverter<T, V>,
    ): VectorizedFiniteAnimationSpec<V> =
        WrappedVectorizedAnimationSpec(wrapped.vectorize(converter), speedupFactor, startDelayNanos)
}

/**
 * `material3/AnimationSpecUtils.kt:136-180`. All three accessors forward to the wrapped spec with the
 * delay subtracted and the play time scaled by [speedupFactor]; the velocity result is scaled by the
 * same factor (`:173`), because covering the same distance in `1/factor` of the time means `factor`
 * times the velocity, and during the delay both value and velocity are simply held at their initial
 * vectors (`:148-150`, `:165-167`).
 */
private class WrappedVectorizedAnimationSpec<V : AnimationVector>(
    val wrapped: VectorizedFiniteAnimationSpec<V>,
    val speedupFactor: Float,
    val startDelayNanos: Long = 0,
) : VectorizedFiniteAnimationSpec<V> {

    override fun getValueFromNanos(
        playTimeNanos: Long,
        initialValue: V,
        targetValue: V,
        initialVelocity: V,
    ): V =
        if (playTimeNanos < startDelayNanos) {
            initialValue
        } else {
            wrapped.getValueFromNanos(
                ((playTimeNanos - startDelayNanos) * speedupFactor).toLong(),
                initialValue,
                targetValue,
                initialVelocity,
            )
        }

    override fun getVelocityFromNanos(
        playTimeNanos: Long,
        initialValue: V,
        targetValue: V,
        initialVelocity: V,
    ): V =
        if (playTimeNanos < startDelayNanos) {
            initialVelocity
        } else {
            wrapped.getVelocityFromNanos(
                ((playTimeNanos - startDelayNanos) * speedupFactor).toLong(),
                initialValue,
                targetValue,
                initialVelocity,
            ) * speedupFactor
        }

    override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long =
        (wrapped.getDurationNanos(initialValue, targetValue, initialVelocity) / speedupFactor)
            .toLong() + startDelayNanos
}

/**
 * `material3/AnimationSpecUtils.kt:182-192` — scale every component of an [AnimationVector]. Private
 * here for the reason stated in this file's header: an `operator` extension published at module scope
 * would bind every `AnimationVector * Float` in the module, and this file is its only user. The `when`
 * is exhaustive because `AnimationVector` is a `sealed class` with exactly these four widths
 * (`AnimationVectors.kt:12`), so no `else` arm is needed or added.
 */
@Suppress("UNCHECKED_CAST")
private operator fun <T : AnimationVector> T.times(k: Float): T {
    val t = this as AnimationVector
    return when (t) {
        is AnimationVector1D -> AnimationVector1D(t.value * k)
        is AnimationVector2D -> AnimationVector2D(t.v1 * k, t.v2 * k)
        is AnimationVector3D -> AnimationVector3D(t.v1 * k, t.v2 * k, t.v3 * k)
        is AnimationVector4D -> AnimationVector4D(t.v1 * k, t.v2 * k, t.v3 * k, t.v4 * k)
    }
        as T
}

/**
 * Suspends until [condition] holds, sampling it once per frame, and gives up after
 * [MAX_WAIT_TIME_MILLIS].
 *
 * Ported from `material3/AnimationSpecUtils.kt:194-201`, verbatim including the trailing `return`,
 * which is a no-op in a `Unit` function but is what upstream writes. The bail-out is not decoration:
 * the one Hibari call site (`view/WearButtonGroupView.kt`, upstream's `ButtonGroup.kt:466`) waits on a
 * grow that the release has already cancelled, so without it a tap shorter than the grow would leave
 * the coroutine parked for good. Upstream's other two consumers guard the same wait with
 * `!progress.isRunning`, which this helper deliberately does not add — the condition is the caller's.
 *
 * [com.huanli233.hibari.runtime.withFrameMillis] takes the clock off the calling coroutine context and
 * falls back to the Choreographer-backed
 * [com.huanli233.hibari.runtime.DefaultMonotonicFrameClock], so the loop is driven by real frames
 * wherever it runs; the animation scopes that reach this do not install a clock of their own.
 */
internal suspend fun waitUntil(condition: () -> Boolean) {
    val initialTimeMillis = withFrameMillis { it }
    while (!condition()) {
        val timeMillis = withFrameMillis { it }
        if (timeMillis - initialTimeMillis > MAX_WAIT_TIME_MILLIS) return
    }
    return
}

/** `material3/AnimationSpecUtils.kt:265`, the cap [waitUntil] waits for. */
private const val MAX_WAIT_TIME_MILLIS = 1_000L
