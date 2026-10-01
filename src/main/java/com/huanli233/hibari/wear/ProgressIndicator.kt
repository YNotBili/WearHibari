package com.huanli233.hibari.wear

import android.graphics.Canvas
import android.graphics.Paint
import com.huanli233.hibari.animation.FiniteAnimationSpec
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.locals.LocalLayoutDirection
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.lerp
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.LayoutDirection
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.attributes.progressIndicatorAttrs
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import com.huanli233.hibari.wear.view.GapExtraProgress
import com.huanli233.hibari.wear.view.WearCircularProgressView
import com.huanli233.hibari.wear.view.WearLinearProgressView
import com.huanli233.hibari.wear.view.drawIndicatorSegment
import com.huanli233.hibari.wear.view.gapSweepFor
import com.huanli233.hibari.wear.view.wrapProgress
import kotlin.math.abs
import kotlin.math.min

/**
 * Ported from androidx.wear.compose.material3.ProgressIndicatorColors. Upstream hands `Brush`es
 * around; every brush here is a solid colour, so [Color] is enough. The state-resolving members are
 * named `*For(enabled)` rather than upstream's `indicatorBrush(enabled)` because their return type is
 * the colour the field of the same name already holds.
 *
 * The two overflow entries are here because upstream's `copy(indicatorColor = …)` and
 * `overflowTrackBrush(enabled, fraction)` both reach for them, and the segmented indicator paints an
 * overflow track: without the fields a caller could not express that colour at all.
 */
data class ProgressIndicatorColors(
    val indicatorColor: Color,
    val trackColor: Color,
    val overflowTrackColor: Color,
    val disabledIndicatorColor: Color,
    val disabledTrackColor: Color,
    val disabledOverflowTrackColor: Color,
) {

    /** `ProgressIndicatorColors.indicatorBrush(enabled)` (`ProgressIndicator.kt:231-233`). */
    fun indicatorColorFor(enabled: Boolean): Color =
        if (enabled) indicatorColor else disabledIndicatorColor

    /** `ProgressIndicatorColors.trackBrush(enabled)` (`:240-242`). */
    fun trackColorFor(enabled: Boolean): Color = if (enabled) trackColor else disabledTrackColor

    /**
     * `ProgressIndicatorColors.overflowTrackBrush(enabled, fraction)` (`:252-261`). Upstream blends
     * only when both brushes are `SolidColor`, which is the only shape a Hibari [Color] can have.
     */
    fun overflowTrackColorFor(enabled: Boolean, fraction: Float = 1f): Color = when {
        !enabled -> disabledOverflowTrackColor
        fraction < 1f -> lerp(indicatorColor, overflowTrackColor, fraction)
        else -> overflowTrackColor
    }
}

/**
 * Ported from androidx.wear.compose.material3.CircularProgressIndicator.
 *
 * Progress changes are sprung, as upstream springs them: the same `Animatable.animateTo` calls the
 * two implementations make (`CircularProgressIndicator.kt:398-415`, `:451-494`), on the same specs
 * upstream resolves off `MaterialTheme.motionScheme` — `determinateCircularProgressAnimationSpec` =
 * `slowEffectsSpec()` (`ProgressIndicator.kt:491-492`) and `progressOverflowColorAnimationSpec` =
 * `fastEffectsSpec()` (`:495-496`) — and a jump of more than a whole circle gets
 * `createOverflowProgressAnimationSpec` (`ProgressIndicator.kt:443-473`). The animated overflow colour
 * fraction is carried the same way, so the track blends indicator to overflow across it
 * (`ProgressIndicator.kt:252-261`, applied at `CircularProgressIndicator.kt:508-512`).
 *
 * Differences from upstream, all in where the animation is hosted and what feeds it:
 *  - `progress: () -> Float` is a plain [Float]. Upstream's `snapshotFlow(updatedProgress)`
 *    `.collectLatest {}` re-emits when the read value changes; here each attribute application carrying
 *    a different value is one emission, and the emission's job is cancelled before the next one starts,
 *    which is `collectLatest`'s semantics. Re-applying the same value starts nothing.
 *  - the animation lives on the view, not in this body: state invalidation here is per Tunation, so a
 *    per-frame read inside a `@Tunable` would re-tune the subtree every frame, and `Modifier.bindState`
 *    cannot help (its value is the `State` instance, so it never reports as changed). The specs are
 *    therefore read at tune and carried in as data through [CircularProgressMotion]. Because they are
 *    read per tune rather than once — unlike upstream, whose never-restarting `LaunchedEffect(Unit)`
 *    (`:405`) captures the specs of the first composition — a retune that changes both the motion
 *    scheme and the progress animates that one step on the previously applied specs.
 *  - upstream's `LaunchedEffect(Unit)` also captures `startAngle`/`endAngle` forever (`:408`), so its
 *    full-circle coercion (`coercedProgressWithGap`, `:556-564`) keeps testing the first composition's
 *    angles. Here the coercion reads the angles the view currently holds, and
 *    [com.huanli233.hibari.wear.attributes.progressIndicatorAttrs] pushes `progress` *before* them: a
 *    retune that changes the progress and the angles together coerces on the previous angles.
 *  - `clearAndSetSemantics {}` and `focusable()` are dropped: no semantics layer here.
 *  - Upstream draws into `Spacer(modifier.fillMaxSize())`, so an unsized indicator takes its parent;
 *    a Views `wrap_content` has no such constraint to fill, so the view measures itself - to
 *    [CircularProgressIndicatorDefaults.IndeterminateCircularIndicatorDiameter] while indeterminate
 *    (upstream's own `Modifier.size(…)`, `:203`) and to the 56.dp it has always used otherwise.
 *
 * @param allowProgressOverflow When progress overflow is allowed, values smaller than 0.0 will be
 *   coerced to 0, while values larger than 1.0 will be wrapped around and shown as overflow with a
 *   different track color [ProgressIndicatorColors.overflowTrackColor]. For example values 1.2, 2.2
 *   etc will be shown as 20% progress with the overflow color. When progress overflow is not allowed,
 *   progress values will be coerced into the range 0..1. (`CircularProgressIndicator.kt:96-100`, with
 *   upstream's `ProgressIndicatorColors.overflowTrackBrush` link naming the solid-colour counterpart
 *   this module has.)
 *
 *   Which of those two behaviours a value gets is decided by this flag alone, here as in upstream's two
 *   implementation composables (`CircularProgressIndicator.kt:128-150`), and it selects three more
 *   things with it:
 *    - the space the spring runs in - the raw value when allowed, `coercedProgressWithGap`
 *      (`:556-564`, i.e. the 0..1 coercion plus the full-circle `GapExtraProgress` overshoot) when not,
 *      see [WearCircularProgressView] and its `animationTargetFor`;
 *    - the three-phase `createOverflowProgressAnimationSpec` curve for a jump of more than a whole
 *      circle, which upstream only reaches from the overflow implementation (`:465-467`);
 *    - the animated overflow-colour blend: only the overflow implementation holds the colour `Animatable`
 *      (`:459`) and feeds its fraction to `overflowTrackBrush(enabled, fraction)` (`:508-512`), which is
 *      [ProgressIndicatorColors.overflowTrackColorFor] here, so with the flag off the track past the
 *      arc is [ProgressIndicatorColors.trackColor] and no blend runs at all.
 *
 *   Flipping the flag while a transition is in flight snaps the arc and the colour fraction to the value
 *   currently requested instead of carrying the transition over - upstream's branch switch leaves the old
 *   `remember { Animatable(…) }` behind and seeds the new one at `updatedProgress()`, which is the same
 *   jump.
 * @param colors Defaults to `null` and resolves in the body: `ProgressIndicatorDefaults.colors()`
 *   reads `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable`
 *   `$default` method that cannot. [strokeWidth] and [gapSize] are nullable for the same reason -
 *   upstream's defaults there are `@Composable`/parameter-reading expressions.
 */
@Tunable
fun CircularProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    allowProgressOverflow: Boolean = false,
    startAngle: Float = CircularProgressIndicatorDefaults.StartAngle,
    endAngle: Float = startAngle,
    colors: ProgressIndicatorColors? = null,
    strokeWidth: Dp? = null,
    gapSize: Dp? = null,
) {
    val stroke = strokeWidth ?: CircularProgressIndicatorDefaults.largeStrokeWidth
    val scheme = MaterialTheme.motionScheme
    Node(
        modifier = modifier.viewClass(WearCircularProgressView::class.java).progressIndicatorAttrs(
            spec = ProgressSpec(
                progress = progress,
                indeterminate = false,
                enabled = enabled,
                allowProgressOverflow = allowProgressOverflow,
                colors = colors ?: ProgressIndicatorDefaults.colors(),
                strokeWidth = stroke,
                gapSize = gapSize
                    ?: CircularProgressIndicatorDefaults.calculateRecommendedGapSize(stroke),
                startAngle = startAngle,
                endAngle = endAngle,
                // Upstream's circular indicator reads no layout direction, so it never mirrors.
                flipHorizontal = false,
            ),
        ).circularProgressMotion(
            // The blend upstream only runs while enabled (`ProgressIndicator.kt:252-261`); a disabled
            // indicator shows `disabledOverflowTrackBrush` with no interpolation from the indicator
            // colour, and the view gets only the resolved pair, so the flag has to come with them.
            CircularProgressMotion(
                progressSpec = scheme.slowEffectsSpec<Float>(),
                overflowColorSpec = scheme.fastEffectsSpec<Float>(),
                blendOverflowColor = enabled,
            )
        )
    )
}

/**
 * Indeterminate Material Design circular progress indicator, from
 * androidx.wear.compose.material3.CircularProgressIndicator's second overload.
 *
 * As upstream this one takes no `enabled`: the indeterminate spinner always paints the enabled pair
 * of brushes (`CircularProgressIndicator.kt:214-221` reads `colors.trackBrush`/`indicatorBrush`
 * without a state argument). Nor does it take `allowProgressOverflow` or the two angles: upstream's
 * own rotation supplies them (`CircularRotationStartAngle`, `:569`).
 *
 * Upstream's motion here needs no `MotionScheme` - the three `keyframes` timelines at `:572-608` are
 * hard-coded - so [WearCircularProgressView] runs them as written.
 */
@Tunable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    colors: ProgressIndicatorColors? = null,
    strokeWidth: Dp? = null,
    gapSize: Dp? = null,
) {
    val stroke = strokeWidth ?: CircularProgressIndicatorDefaults.IndeterminateStrokeWidth
    Node(
        modifier = modifier.viewClass(WearCircularProgressView::class.java).progressIndicatorAttrs(
            spec = ProgressSpec(
                progress = 0f,
                indeterminate = true,
                enabled = true,
                allowProgressOverflow = false,
                colors = colors ?: ProgressIndicatorDefaults.colors(),
                strokeWidth = stroke,
                gapSize = gapSize
                    ?: CircularProgressIndicatorDefaults.calculateRecommendedGapSize(stroke),
                startAngle = CircularProgressIndicatorDefaults.StartAngle,
                endAngle = CircularProgressIndicatorDefaults.StartAngle,
                flipHorizontal = false,
            ),
        )
    )
}

/**
 * Ported from androidx.wear.compose.material3.LinearProgressIndicator (`:78-107`).
 *
 * Upstream gives this component no `allowProgressOverflow` - the value is coerced to 0..1 at
 * `:91`/`:95` - and no arc angles.
 *
 * Progress changes are sprung on `linearProgressAnimationSpec` (`LinearProgressIndicator.kt:89-98`,
 * `:254-255`), which is `MaterialTheme.motionScheme.defaultEffectsSpec()` read here at tune and carried
 * in as data; the interpolation itself runs on the view, for the reason the circular component's header
 * gives. Upstream's `snapshotFlow(updatedProgress).collectLatest {}` re-emits only on a changed value,
 * and so does the view's setter.
 *
 * The accessibility description is not ported, and the reason is not the usual one: the reference tree
 * gives `LinearProgressIndicator` no semantics at all - it sets no state description, and
 * `material3/internal/Strings.kt` has no progress entry in `Strings` or `Plurals` while
 * `res/values/wear_m3c_strings.xml` has no key containing "progress" (`grep -rn` over both). So there
 * is no upstream English copy to move to the call site, and no plural to choose between - a plural
 * would take `%d` twice, which no call site here does. When this module grows an accessibility layer,
 * the key has to be written along with it rather than assumed to exist: `hibari-wear`'s
 * `res/values/strings.xml` carries none today.
 *
 * @param strokeWidth Defaults to upstream's own default, `LinearProgressIndicatorDefaults.StrokeWidthLarge`
 *   (`LinearProgressIndicator.kt:82`, `:197`) - a plain `12.dp` `val`, not a `MaterialTheme` or screen
 *   read, so it survives hoisting into the non-`@Tunable` `$default` method and the parameter is *not*
 *   nullable, unlike [CircularProgressIndicator]'s, whose `largeStrokeWidth` reads the screen size and so
 *   cannot be written as a default expression at all. The `require` below is upstream's own
 *   (`:85`, repeated in the content implementation at `:132`): a stroke thinner than
 *   [LinearProgressIndicatorDefaults.StrokeWidthSmall] throws rather than being clamped up, so it is kept
 *   as a throw and not softened here.
 */
@Tunable
fun LinearProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    colors: ProgressIndicatorColors? = null,
    strokeWidth: Dp = LinearProgressIndicatorDefaults.StrokeWidthLarge,
    enabled: Boolean = true,
) {
    require(strokeWidth >= LinearProgressIndicatorDefaults.StrokeWidthSmall) {
        "Stroke width cannot be less than ${LinearProgressIndicatorDefaults.StrokeWidthSmall}"
    }
    Node(
        modifier = modifier.viewClass(WearLinearProgressView::class.java).progressIndicatorAttrs(
            spec = ProgressSpec(
                progress = progress,
                indeterminate = false,
                enabled = enabled,
                allowProgressOverflow = false,
                colors = colors ?: ProgressIndicatorDefaults.colors(),
                strokeWidth = strokeWidth,
                // The linear bar has no gap: upstream draws one stroke along a line, so the circular
                // segment gap never applies. `Dp.Unspecified` keeps the field honest rather than
                // inventing a number the view must then ignore.
                gapSize = Dp.Unspecified,
                startAngle = CircularProgressIndicatorDefaults.StartAngle,
                endAngle = CircularProgressIndicatorDefaults.StartAngle,
                // `LinearProgressIndicator.kt:135`, `:143`: only this indicator mirrors on the X axis,
                // and only for a right-to-left layout. The circular indicator is not direction
                // dependent upstream, so it passes false.
                flipHorizontal = linearProgressIsRtl(),
            ),
        ).linearProgressMotion(
            MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        )
    )
}

/**
 * `LocalLayoutDirection.current == LayoutDirection.Rtl` (`LinearProgressIndicator.kt:135`). Read as a
 * top-level `@Tunable` rather than inline: a local is an ambient read, so it belongs in a tunable body
 * and not in a default expression or an attribute applier lambda.
 */
@Tunable
private fun linearProgressIsRtl(): Boolean = LocalLayoutDirection.current == LayoutDirection.Rtl

/** The whole state of one progress view as one comparable value, so an unchanged retune re-applies nothing. */
data class ProgressSpec(
    val progress: Float,
    val indeterminate: Boolean,
    val enabled: Boolean,
    /** Circular only: values past 1.0 wrap and paint the rest of the sweep in the overflow track colour. */
    val allowProgressOverflow: Boolean,
    val colors: ProgressIndicatorColors,
    val strokeWidth: Dp,
    /** The circular indicator's end gap; [Dp.Unspecified] on the linear one, which has no gap. */
    val gapSize: Dp,
    val startAngle: Float,
    val endAngle: Float,
    /** Linear only: upstream's `.scale(scaleX = -1f)` for a right-to-left layout (`:143`). */
    val flipHorizontal: Boolean,
)

/**
 * The motion the determinate circular indicator runs, resolved off [MaterialTheme.motionScheme] at tune
 * and handed to the view as data: `determinateCircularProgressAnimationSpec` (`ProgressIndicator.kt:491-492`)
 * and `progressOverflowColorAnimationSpec` (`:495-496`).
 *
 * It rides on its own attribute rather than on [ProgressSpec] because [progressIndicatorAttrs] is the
 * owner of that value's shape and resolves the colours before the view ever sees them; the blend flag
 * below is a colour concern it cannot express.
 *
 * Equality is what keeps a retune that changed nothing from re-applying: both specs come from
 * [MaterialTheme.motionScheme] as the six pre-built `SpringSpec` instances the scheme objects hold
 * (`MotionScheme.kt:126-160`, `:199-233`) rather than fresh ones per call, and `SpringSpec` compares
 * damping/stiffness/visibilityThreshold structurally (`hibari-animation/AnimationSpec.kt:133-141`), so
 * even a scheme that built a fresh spring per call would hand out an equal value. Only a custom scheme
 * giving out a spec type with no structural `equals` compares unequal and re-applies every retune - and
 * that costs a field assignment, not an animation: [WearCircularProgressView] reads these specs when an
 * emission starts, so a re-push of equal-or-equal-looking specs never restarts a transition.
 */
internal data class CircularProgressMotion(
    val progressSpec: FiniteAnimationSpec<Float>,
    val overflowColorSpec: FiniteAnimationSpec<Float>,
    /**
     * Upstream's `enabled`: `overflowTrackBrush(enabled, fraction)` (`ProgressIndicator.kt:252-261`)
     * blends `indicatorBrush` into `overflowTrackBrush` only while enabled, and hands back the plain
     * `disabledOverflowTrackBrush` when it is not.
     */
    val blendOverflowColor: Boolean,
)

/**
 * [CircularProgressMotion] onto the circular view. Separate from
 * [com.huanli233.hibari.wear.attributes.progressIndicatorAttrs], and applied after it, so the view has
 * its progress and angles before it gets the specs those values will be animated on.
 */
private fun Modifier.circularProgressMotion(motion: CircularProgressMotion): Modifier =
    this.thenViewAttribute<WearCircularProgressView, CircularProgressMotion>(uniqueKey, motion) {
        applyProgressMotion(it)
    }

/** `linearProgressAnimationSpec` (`LinearProgressIndicator.kt:254-255`) onto the linear view. */
private fun Modifier.linearProgressMotion(spec: FiniteAnimationSpec<Float>): Modifier =
    this.thenViewAttribute<WearLinearProgressView, FiniteAnimationSpec<Float>>(uniqueKey, spec) {
        applyProgressSpec(it)
    }

/**
 * Upstream's `DrawScope.drawCircularProgressIndicator` (`CircularProgressIndicator.kt:262-322`) - the
 * non-animating entry point a caller with its own animation uses.
 *
 * Hibari has no `DrawScope`, so the three things one quietly supplies - the canvas, a reusable
 * [Paint] and the density for the `Dp` conversions - are arguments. [paint] must not be allocated per
 * call: this is a draw routine, and Compose's scope owns its paint for exactly that reason. The
 * drawing area is the canvas's own bounds, which is what `size` means upstream.
 *
 * `allowProgressOverflow` is honoured here (one frame needs no animator); the disabled pair of
 * [ProgressIndicatorColors] is selected through [enabled], as upstream's `*Brush(enabled)` calls do.
 */
fun Canvas.drawCircularProgressIndicator(
    progress: Float,
    colors: ProgressIndicatorColors,
    strokeWidth: Dp,
    paint: Paint,
    density: Float,
    enabled: Boolean = true,
    allowProgressOverflow: Boolean = false,
    startAngle: Float = CircularProgressIndicatorDefaults.StartAngle,
    endAngle: Float = startAngle,
    gapSize: Dp = CircularProgressIndicatorDefaults.calculateRecommendedGapSize(strokeWidth),
) {
    val strokePx = strokeWidth.value * density
    val gapPx = gapSize.value * density
    val minSize = min(width, height).toFloat()
    if (strokePx <= 0f || minSize <= strokePx * 2f) return

    val fullSweep = 360f - ((startAngle - endAngle) % 360 + 360) % 360
    val gapSweep = gapSweepFor(strokePx, gapPx, minSize)
    val hasOverflow = allowProgressOverflow && progress > 1f
    val wrappedProgress = wrapProgress(progress, allowProgressOverflow)
    val progressSweep = fullSweep * wrappedProgress

    // The track background, or the overflow track when the value has wrapped past full.
    drawIndicatorSegment(
        paint = paint,
        startAngle = startAngle + progressSweep,
        sweep = fullSweep - progressSweep,
        gapSweep = gapSweep,
        color = if (hasOverflow) {
            colors.overflowTrackColorFor(enabled)
        } else {
            colors.trackColorFor(enabled)
        },
        strokeWidth = strokePx,
    )

    if (!allowProgressOverflow && startAngle == endAngle && wrappedProgress == 1f) {
        // Upstream hands the merge branch `gapSweep = gapFraction`, a value in *degrees* that its
        // animation drives from 1 down to 0 as the animated target overshoots to 1.05
        // (`coercedProgressWithGap`, `CircularProgressIndicator.kt:556-564`, applied by the caller at
        // `:402`). Stated literally here rather than "corrected" to a fraction of the gap sweep, which
        // would leave a visible break in a full ring.
        val gapFraction = abs(1f + GapExtraProgress - progress) / GapExtraProgress
        drawIndicatorSegment(
            paint = paint,
            startAngle = startAngle,
            sweep = progressSweep,
            gapSweep = gapFraction,
            color = colors.indicatorColorFor(enabled),
            strokeWidth = strokePx,
        )
    } else {
        drawIndicatorSegment(
            paint = paint,
            startAngle = startAngle,
            sweep = progressSweep,
            gapSweep = gapSweep,
            color = colors.indicatorColorFor(enabled),
            strokeWidth = strokePx,
        )
    }
}

/**
 * Contains defaults for the progress indicators that upstream shares: the colour sets. Upstream keeps
 * its stroke widths off this object, on
 * [CircularProgressIndicatorDefaults] and [LinearProgressIndicatorDefaults].
 */
object ProgressIndicatorDefaults {
    /** `ProgressIndicatorDefaults.OverflowTrackColorAlpha` (`ProgressIndicator.kt:107`). */
    internal const val OverflowTrackColorAlpha = 0.6f

    @Tunable
    fun colors(): ProgressIndicatorColors = MaterialTheme.colorScheme.defaultProgressIndicatorColors

    /**
     * `ProgressIndicatorDefaults.colors(indicatorColor, trackColor, overflowTrackColor,
     * disabledIndicatorColor, disabledTrackColor, disabledOverflowTrackColor)`
     * (`ProgressIndicator.kt:60-75`), every [Color.Unspecified] entry keeping the default the way
     * upstream's `copy` does.
     *
     * The `Brush` overload (`:89-104`) has no counterpart: every brush in this module is a solid
     * colour, so a `Brush` parameter would accept values the view then throws away.
     */
    @Tunable
    fun colors(
        indicatorColor: Color = Color.Unspecified,
        trackColor: Color = Color.Unspecified,
        overflowTrackColor: Color = Color.Unspecified,
        disabledIndicatorColor: Color = Color.Unspecified,
        disabledTrackColor: Color = Color.Unspecified,
        disabledOverflowTrackColor: Color = Color.Unspecified,
    ): ProgressIndicatorColors {
        val defaults = MaterialTheme.colorScheme.defaultProgressIndicatorColors
        return defaults.copy(
            indicatorColor = indicatorColor.takeIfSpecified(defaults.indicatorColor),
            trackColor = trackColor.takeIfSpecified(defaults.trackColor),
            overflowTrackColor = overflowTrackColor.takeIfSpecified(defaults.overflowTrackColor),
            disabledIndicatorColor =
                disabledIndicatorColor.takeIfSpecified(defaults.disabledIndicatorColor),
            disabledTrackColor = disabledTrackColor.takeIfSpecified(defaults.disabledTrackColor),
            disabledOverflowTrackColor =
                disabledOverflowTrackColor.takeIfSpecified(defaults.disabledOverflowTrackColor),
        )
    }
}

/**
 * Contains default values for [CircularProgressIndicator] - upstream's
 * `CircularProgressIndicatorDefaults` (`CircularProgressIndicator.kt:523-554`), member for member.
 *
 * The two stroke widths are `@Composable get()` upstream because they pick by screen size; here they
 * are `@Tunable get()` reading [WearScreen.isSmallScreen] off [currentContext], so a retune on a
 * configuration change is what re-evaluates them.
 */
object CircularProgressIndicatorDefaults {

    /**
     * `CircularProgressIndicatorDefaults.largeStrokeWidth` (`:525-526`): upstream's
     * `if (isSmallScreen()) 8.dp else 12.dp`, where `isSmallScreen()` is `screenWidthDp < 225`
     * (`materialcore/Resources.kt`, ported as [WearScreen.isSmallScreen]). `@Composable get()` there,
     * `@Tunable get()` here - the screen configuration is an ambient read, so a retune is what
     * re-evaluates it. This is what [CircularProgressIndicator] defaults `strokeWidth` to.
     */
    val largeStrokeWidth: Dp
        @Tunable get() = if (WearScreen.isSmallScreen(currentContext)) 8.dp else 12.dp

    /**
     * `CircularProgressIndicatorDefaults.smallStrokeWidth` (`:529-530`): upstream's
     * `if (isSmallScreen()) 5.dp else 8.dp`. A recommended value, not a default - the indicator itself
     * starts at [largeStrokeWidth].
     */
    val smallStrokeWidth: Dp
        @Tunable get() = if (WearScreen.isSmallScreen(currentContext)) 5.dp else 8.dp

    /**
     * The angle the progress arc starts at: 270 degrees, the top of the screen.
     * `CircularProgressIndicatorDefaults.StartAngle` (`:532-537`). Shared: `ArcProgressIndicator`'s
     * `calculateRecommendedGapSize` KDoc points at it, and
     * `SegmentedCircularProgressIndicator` defaults both its angles to it
     * (`SegmentedCircularProgressIndicator.kt:98-99`, `:284-285`).
     */
    const val StartAngle: Float = 270f

    /**
     * Recommended gap size for [strokeWidth] - `strokeWidth / 3f`
     * (`CircularProgressIndicatorDefaults.calculateRecommendedGapSize`, `:539-544`).
     */
    fun calculateRecommendedGapSize(strokeWidth: Dp): Dp = strokeWidth / 3f

    /**
     * `CircularProgressIndicatorDefaults.FullScreenPadding` (`:546-547`), which upstream defines as
     * `PaddingDefaults.edgePadding` - a flat 2.dp (`material3/Padding.kt:62-63`, already ported as
     * [PaddingDefaults.edgePadding]). The value a caller puts around an indicator that fills the
     * screen.
     */
    val FullScreenPadding: Dp = PaddingDefaults.edgePadding

    /**
     * `CircularProgressIndicatorDefaults.IndeterminateStrokeWidth` (`:552-553`), the stroke of the
     * indeterminate spinner.
     */
    val IndeterminateStrokeWidth: Dp = 3.dp

    /**
     * `CircularProgressIndicatorDefaults.IndeterminateCircularIndicatorDiameter` (`:549-550`), the
     * diameter upstream's indeterminate overload gives its canvas (`:203`). Internal there, internal
     * here.
     */
    internal val IndeterminateCircularIndicatorDiameter: Dp = 24.dp
}

/**
 * Contains defaults for [LinearProgressIndicator] - upstream's
 * `LinearProgressIndicatorDefaults` (`LinearProgressIndicator.kt:190-215`).
 *
 * Unlike the circular pair these are plain values: the linear bar does not pick by screen size, it
 * fixes [StrokeWidthSmall] as the floor instead, and [LinearProgressIndicator] enforces it. Being plain
 * is load-bearing, not just tidiness: [StrokeWidthLarge] is read straight as the component's default
 * argument, which is only legal because a `@Tunable` default expression is hoisted into a non-`@Tunable`
 * `$default` method that can resolve a plain `val` but no `MaterialTheme` or screen read.
 */
object LinearProgressIndicatorDefaults {

    /** Large stroke width, and the default [strokeWidth] of [LinearProgressIndicator] (`:197`). */
    val StrokeWidthLarge: Dp = 12.dp

    /**
     * Small stroke width, and the minimum [LinearProgressIndicator] accepts (`:205`): below it the
     * dot at the end of the range cannot be told apart from the line.
     */
    val StrokeWidthSmall: Dp = 8.dp

    /** Radius of the dot at the end of the bar (`:208`), internal upstream. */
    internal val DotRadius: Dp = 2.dp

    /** Margin the dot sits in from the end of the bar (`:211`), internal upstream. */
    internal val DotMargin: Dp = 4.dp

    /** Horizontal padding around the whole bar (`:214`), internal upstream. */
    internal val OuterHorizontalMargin: Dp = 2.dp
}

/**
 * `ColorScheme.defaultProgressIndicatorColors` (`ProgressIndicator.kt:109-138`), entry for entry:
 * indicator on `Primary`, track on `SurfaceContainer`, both overflow entries on `Primary` at
 * [ProgressIndicatorDefaults.OverflowTrackColorAlpha] (the disabled one then run through
 * `DisabledContainerAlpha`), and both disabled content entries on `OnSurface`. Upstream caches the
 * built object behind the getter (`defaultProgressIndicatorColorsCached`, `:111`); there is no
 * composition to cache against here, so this rebuilds on a read like every other colour factory in
 * the module.
 */
internal val ColorScheme.defaultProgressIndicatorColors: ProgressIndicatorColors
    @Tunable get() {
        val primary = ColorSchemeKeyTokens.Primary.resolve(this)
        val overflow = primary.copy(alpha = ProgressIndicatorDefaults.OverflowTrackColorAlpha)
        val onSurface = ColorSchemeKeyTokens.OnSurface.resolve(this)
        return ProgressIndicatorColors(
            indicatorColor = primary,
            trackColor = ColorSchemeKeyTokens.SurfaceContainer.resolve(this),
            overflowTrackColor = overflow,
            disabledIndicatorColor = onSurface.toDisabledColor(ColorScheme.DisabledContentAlpha),
            disabledTrackColor = onSurface.toDisabledColor(ColorScheme.DisabledContainerAlpha),
            disabledOverflowTrackColor =
                overflow.toDisabledColor(ColorScheme.DisabledContainerAlpha),
        )
    }

private fun Color.takeIfSpecified(fallback: Color): Color =
    if (isSpecified) this else fallback
