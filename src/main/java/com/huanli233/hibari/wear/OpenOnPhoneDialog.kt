package com.huanli233.hibari.wear

import android.content.Context
import android.provider.Settings
import android.view.Gravity
import com.huanli233.hibari.animation.Animatable
import com.huanli233.hibari.animation.LinearEasing
import com.huanli233.hibari.animation.animateFloatAsState
import com.huanli233.hibari.animation.tween
import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.attributes.alpha
import com.huanli233.hibari.foundation.attributes.matchParentSize
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.foundation.attributes.size
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.effects.LaunchedEffect
import com.huanli233.hibari.runtime.getValue
import com.huanli233.hibari.runtime.mutableStateOf
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.runtime.setValue
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.CircleShape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.lerp
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.attributes.curvedPadding
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import com.huanli233.hibari.wear.tokens.MotionDurationTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ported from androidx.wear.compose.material3.{OpenOnPhoneDialog, OpenOnPhoneDialogContent}.
 *
 * Window presentation is not hibari-wear's job: no `Dialog`/`DialogFragment` hosting, no scrim, no
 * back handling, no swipe-to-dismiss, no focus. `KeepScreenOn()` is the exception — upstream gates it
 * on `visible`, and here [OpenOnPhoneDialogContent] calls it for as long as the surface is placed.
 * Upstream's `visible` parameter goes too — the caller places this surface itself, exactly like
 * [Card], and stops composing it to hide it. The `durationMillis` timer that calls
 * `onDismissRequest` is content, not window, and is ported.
 *
 * One thing moves the other way: upstream's opaque `colorScheme.background` sits on the `Dialog`'s
 * own full-screen box, so upstream's `OpenOnPhoneDialogContent` is transparent. With no window to
 * carry it, [dialogSurface] is applied to the root here instead, which makes the *Content* function
 * paint a backdrop upstream's does not.
 *
 * Not ported, with reasons:
 *  - `properties: DialogProperties` — window configuration.
 *  - `LocalAccessibilityManager.calculateRecommendedTimeoutMillis`, which stretches [durationMillis]
 *    before it reaches the ring. The platform call behind it —
 *    `AccessibilityManager.getRecommendedTimeoutMillis(flags, millis)` — is reachable from
 *    `currentContext` but is API 29 against this module's minSdk 25, and both the
 *    contains-icons/text/controls → flag mapping and the pre-29 fallback live in compose-ui, which
 *    the reference tree does not carry, so there is nothing to copy verbatim. [durationMillis] is
 *    therefore used as given.
 *  - `Modifier.focusable()` on the progress ring — hibari-wear has no focus layer to give it to.
 *  - Semantics: upstream's `clearAndSetSemantics {}` on the plate box is dropped, as everywhere in
 *    this module (no semantics layer; the same note is made in `AlertDialog.kt`).
 *  - `OpenOnPhoneDialogDefaults.Icon`, the default `content`: it is an `AnimatedVectorDrawable`
 *    loaded as `R.drawable.wear_m3c_open_on_phone_animation` — three filled paths (laptop, phone,
 *    dot) in a 520x520 viewport, held at `scaleY = 0` until `IconDelay` (67 ms) and then path-morphed
 *    and translated — and this module ships no drawable resources, so no drawable id may be invented.
 *    The slot therefore has no default, and nothing here draws a hand-made replacement for those paths.
 *  - `OpenOnPhoneDialogDefaults.text` is ported as [OpenOnPhoneDialogDefaults.text], reading the
 *    upstream key from this module's own `res/values/strings.xml`.
 *  - `CurvedScope.openOnPhoneDialogCurvedText` is here as the top-level
 *    [CurvedLayoutScope.openOnPhoneDialogCurvedText]: this module spells upstream's `CurvedScope`
 *    [CurvedLayoutScope], and upstream's `CurvedTextStyle` is a plain [TextStyle], which is what
 *    [CurvedLayoutScope.curvedText] takes. The slot type itself is upstream's.
 *
 * Everything upstream animates here is animated: the linear 0→1 progress sweep over
 * `durationMillis - DurationLong2`, the `DurationShort3`-delayed opacity fade of the curved label,
 * and at the end the plate/icon colour reversal, the ring's fade-out and the plate shrinking back to
 * nothing as its padding opens up. The `LocalReduceMotion` branches are ported too: under
 * `reduce_motion` upstream waits out the sweep instead of tweening it (so the progress stays 0),
 * skips the entry delay and never emits the ring — all three are reproduced here, the setting read
 * once because upstream caches it.
 */

/**
 * The dialog that says "this continues on your phone": a circular plate with an icon, a progress ring
 * that fills over [durationMillis], and curved text along the bottom edge.
 *
 * @param curvedText filled inside the `CurvedLayout` this dialog wraps its label in, along the bottom
 *   edge; [openOnPhoneDialogCurvedText] gives upstream's sweep budget and edge padding. Upstream's
 *   parameter of this name is a *required* nullable — the default label comes from
 *   `OpenOnPhoneDialogDefaults.text`, not from this signature — and it is optional here only because
 *   this module ships no such string, so there is nothing to suggest at the call site.
 * @param colors resolved in the body from [OpenOnPhoneDialogDefaults.colors]; a `@Tunable` default
 *   expression may not call a `@Tunable` getter.
 * @param content the icon slot. Upstream sizes this slot to nothing — the centring comes from the
 *   plate box's `contentAlignment`, and only the default icon applies [OpenOnPhoneDialogDefaults.IconSize]
 *   to itself — so a caller's own icon has to carry its own size. Null because upstream's default is
 *   the `wear_m3c_open_on_phone_animation` drawable, which is not shipped here.
 */
@Tunable
fun OpenOnPhoneDialog(
    onDismissRequest: () -> Unit,
    curvedText: (CurvedLayoutScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
    colors: OpenOnPhoneDialogColors? = null,
    durationMillis: Long = OpenOnPhoneDialogDefaults.DurationMillis,
    content: (@Tunable () -> Unit)? = null,
) {
    val resolved = colors ?: OpenOnPhoneDialogDefaults.colors()
    val iconSlot = content
    // Upstream asks the accessibility manager to stretch the timeout and dismisses through the same
    // callback the window's swipe would use; both share `dialogAutoDismiss` with ConfirmationDialog.
    dialogAutoDismiss(onDismissRequest, durationMillis)
    OpenOnPhoneDialogContent(
        curvedText = curvedText,
        durationMillis = durationMillis,
        modifier = modifier,
        colors = resolved,
        content = iconSlot,
    )
}

/**
 * Content of [OpenOnPhoneDialog], with no dismiss timer. [durationMillis] is the total time the ring
 * is drawn for, as upstream documents: the last `DurationLong2` of it belongs to the closing
 * animation, so the sweep itself covers the remainder.
 *
 * @param curvedText filled inside the `CurvedLayout` this content wraps its label in.
 * @param durationMillis upstream documents this as the value already stretched for accessibility; no
 *   stretch is applied here, so it is the same value [OpenOnPhoneDialog] dismisses on.
 */
@Tunable
fun OpenOnPhoneDialogContent(
    curvedText: (CurvedLayoutScope.() -> Unit)?,
    durationMillis: Long,
    modifier: Modifier = Modifier,
    colors: OpenOnPhoneDialogColors? = null,
    content: (@Tunable () -> Unit)? = null,
) {
    val resolved = colors ?: OpenOnPhoneDialogDefaults.colors()
    val context = currentContext
    val iconSlot = content
    val label = curvedText
    // Upstream's `if (visible) KeepScreenOn()` (material3/OpenOnPhoneDialog.kt:119-123): the flag held
    // for the whole showing, so the ring animation runs to completion instead of the screen timing
    // out mid-sweep. This port has no `visible` parameter — the caller places the surface and stops
    // placing it to hide it — so the surface's presence is the gate, and [KeepScreenOn]'s
    // `RememberObserver` clears the flag when it goes away.
    KeepScreenOn()
    // `LocalReduceMotion.current`, through the module's one sampler. Upstream caches it for the
    // composition, so it is read once here rather than on every retune; a change to the setting takes
    // effect on the next composition. See `ReduceMotion.kt`.
    val reduceMotion = remember { wearReduceMotionEnabled(context) }

    val progressAnimatable = remember { Animatable(0f) }
    val labelOpacity = remember { Animatable(0f) }
    var finalAnimation by remember { mutableStateOf(false) }
    val progressDuration = durationMillis - MotionDurationTokens.DurationLong2.toLong()
    // Upstream reads all four off `MaterialTheme.motionScheme` in this same body
    // (`material3/OpenOnPhoneDialog.kt:199, 225-227`) and hands the values into its coroutines and
    // `animate*AsState` calls, so the theme — not a hard-coded spring — decides what runs.
    val alphaAnimationSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val sizeAnimationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val progressAlphaAnimationSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val colorReversalAnimationSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    LaunchedEffect(durationMillis) {
        launch {
            // Upstream's `animatedDelay(DurationShort3, reduceMotionEnabled)`
            // (`material3/OpenOnPhoneDialog.kt:204`): the delay is skipped outright, not shortened.
            wearAnimatedDelay(MotionDurationTokens.DurationShort3.toLong(), reduceMotion)
            labelOpacity.animateTo(1f, alphaAnimationSpec)
        }
        launch {
            if (reduceMotion) {
                // Upstream waits the sweep out rather than tweening it, so the progress stays 0 —
                // and no ring is drawn at all (see below).
                delay(progressDuration)
            } else {
                progressAnimatable.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = progressDuration.toInt(),
                        easing = LinearEasing,
                    ),
                )
            }
            finalAnimation = true
        }
    }

    // `animateFloatAsState(if (finalAnimation) 0f else 1f, …)`: the plate's opening padding and the
    // ring's alpha both ride it, so one reading serves both.
    val sizeFraction = animateFloatAsState(
        targetValue = if (finalAnimation) 0f else 1f,
        animationSpec = sizeAnimationSpec,
    ).value
    val progressAlpha = animateFloatAsState(
        targetValue = if (finalAnimation) 0f else 1f,
        animationSpec = progressAlphaAnimationSpec,
    ).value
    // Upstream reverses the two colours with `animateColorAsState`; one fraction plus `lerp` over the
    // same spec lands on the same per-channel interpolation.
    val reversal = animateFloatAsState(
        targetValue = if (finalAnimation) 1f else 0f,
        animationSpec = colorReversalAnimationSpec,
    ).value
    val iconColor = lerp(resolved.iconColor, resolved.iconContainerColor, reversal)
    val plateColor = lerp(resolved.iconContainerColor, resolved.iconColor, reversal)

    // Coerced because a spring can overshoot below 0, which would make the padding and the stroke
    // negative — upstream's own comment.
    val platePadding = ((ProgressStrokeWidth + ProgressPadding) * sizeFraction).coerceAtLeast(0.dp)
    val strokeWidth = (ProgressStrokeWidth * sizeFraction).coerceAtLeast(0.dp)

    Box(modifier = modifier.container(dialogSurface()).matchParentSize()) {
        val topPadding = dialogScreenHeightFraction(context, HeightPaddingFraction)
        val plateSize = dialogScreenWidthFraction(context, SizeFraction)
        Box(
            modifier = Modifier
                .gravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .padding(top = topPadding)
                .size(DpSize(plateSize, plateSize)),
        ) {
            // `iconAndProgressContainer`: fillMaxSize().padding(padding).clip(circle).background().
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(platePadding),
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .container(
                            ContainerSpec(
                                shape = CircleShape,
                                containerColor = plateColor,
                            ),
                        ),
                ) { }
            }
            // `if (!LocalReduceMotion.current) IconContainerProgressIndicator(...)`: with the sweep
            // frozen at 0 there is nothing for the ring to show, so upstream does not emit it.
            if (!reduceMotion) {
                CircularProgressIndicator(
                    progress = progressAnimatable.value,
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(progressAlpha),
                    colors = ProgressIndicatorDefaults.colors(
                        indicatorColor = resolved.progressIndicatorColor,
                        trackColor = resolved.progressTrackColor,
                    ),
                    strokeWidth = strokeWidth,
                )
            }
            if (iconSlot != null) {
                val slot = iconSlot
                // Centred through its own gravity: the plate is a FrameLayout host, which upstream's
                // `contentAlignment = Center` has no group-level equivalent for. The plate box and the
                // ring fill the plate, so they need none.
                Box(modifier = Modifier.gravity(Gravity.CENTER)) {
                    provideDialogIconColor(iconColor) { slot() }
                }
            }
        }
        if (label != null) {
            provideDialogText(resolved.textColor, null) {
                // Upstream's `CurvedLayout(anchor = 90f, angularDirection = Reversed)`: 90f is
                // 6 o'clock, and the caller's label is filled into the layout.
                CurvedLayout(
                    modifier = Modifier.alpha(labelOpacity.value),
                    anchor = 90f,
                    angularDirection = CurvedDirection.Angular.Reversed,
                    content = label,
                )
            }
        }
    }
}

/**
 * Upstream's `CurvedScope.openOnPhoneDialogCurvedText`: the dialog's curved label — a 200° sweep
 * budget (`OpenOnPhoneMaxSweepAngle`) and `PaddingDefaults.edgePadding` on every edge — filled inside
 * the [CurvedLayout] that [OpenOnPhoneDialog] builds around its `curvedText` slot.
 *
 * [style] is required, as upstream: `OpenOnPhoneDialogDefaults.curvedTextStyle` reads the theme, so it
 * has to be resolved by the caller's slot lambda, which is a tunable context, rather than defaulted
 * from this plain scope member.
 *
 * One thing the style cannot carry through: upstream's `CurvedTextStyle` has two trackings, a
 * clockwise one and a wider counter-clockwise one — arc *Large* pairs `ArcLargeTrackingTop` 0.4.sp
 * with `ArcLargeTrackingBottom` 1.6.sp — and this module's [TextStyle] carries only the first (see
 * the note at the top of `TypographyTokens`). This label runs counter-clockwise (`Reversed` at 6
 * o'clock), so upstream sets its glyphs 1.6.sp apart; here the style's 0.4.sp is used instead. A
 * caller who wants upstream's counter-clockwise tracking skips this helper and passes
 * `letterSpacingCounterClockwise` to [CurvedLayoutScope.curvedText] directly.
 */
fun CurvedLayoutScope.openOnPhoneDialogCurvedText(
    text: String,
    style: TextStyle,
): Unit = curvedText(
    text = text,
    style = style,
    // Upstream merges the style into these arguments inside `curvedText`, so an unset tracking falls
    // back to the style's. The curved view here reads tracking off the arguments only, so the
    // fallback is spelled out at the call.
    letterSpacing = style.letterSpacing,
    maxSweepAngle = OpenOnPhoneDialogDefaults.CurvedTextMaxSweepAngle,
    modifier = Modifier.curvedPadding(OpenOnPhoneCurvedTextEdgePadding),
)

/** Contains the default values used by [OpenOnPhoneDialog]. */
object OpenOnPhoneDialogDefaults {

    /** Default timeout for the dialog, in milliseconds. */
    val DurationMillis: Long = 4000L

    /**
     * Upstream's `OpenOnPhoneDialogDefaults.text` (`material3/OpenOnPhoneDialog.kt:306-307`), a
     * `@Composable get()` over `stringResource(R.string.wear_m3c_open_on_phone)` — "Check your phone",
     * verbatim from `res/values/wear_m3c_strings.xml:20`. A `@Tunable` getter is this module's
     * counterpart, so the string is read from resources rather than carried as a literal.
     */
    val text: String
        @Tunable get() = currentContext.getString(R.string.wear_m3c_open_on_phone)

    /** The size upstream's default icon is drawn at; private there, public here because the slot has no default. */
    val IconSize: Dp = 52.dp

    /**
     * `OpenOnPhoneMaxSweepAngle`: the arc budget this dialog's curved label gets. Public where
     * upstream keeps that const file-private; [ConfirmationDialogDefaults] publishes its equivalent
     * for the same reason, so a caller writing their own label can reuse the budget.
     */
    const val CurvedTextMaxSweepAngle: Float = 200f

    /**
     * The default style for curved text content: `MaterialTheme.typography.arcLarge`.
     *
     * Upstream's is `public val curvedTextStyle: CurvedTextStyle @Composable get()` — a Tunable
     * cannot be a property getter, and this module's arc styles are plain [TextStyle]s, so it is a
     * function of the same name. See [openOnPhoneDialogCurvedText] for the tracking that shape
     * cannot carry.
     */
    @Tunable
    fun curvedTextStyle(): TextStyle = MaterialTheme.typography.arcLarge

    /** `primaryContainer` plate with an `onPrimaryContainer` icon, `primary` ring on `onPrimary`. */
    @Tunable
    fun colors(): OpenOnPhoneDialogColors = defaultOpenOnPhoneDialogColors()

    /** [colors] with the listed roles overridden; [Color.Unspecified] keeps the default. */
    @Tunable
    fun colors(
        iconColor: Color = Color.Unspecified,
        iconContainerColor: Color = Color.Unspecified,
        progressIndicatorColor: Color = Color.Unspecified,
        progressTrackColor: Color = Color.Unspecified,
        textColor: Color = Color.Unspecified,
    ): OpenOnPhoneDialogColors = defaultOpenOnPhoneDialogColors().copy(
        iconColor = iconColor,
        iconContainerColor = iconContainerColor,
        progressIndicatorColor = progressIndicatorColor,
        progressTrackColor = progressTrackColor,
        textColor = textColor,
    )

    @Tunable
    private fun defaultOpenOnPhoneDialogColors(): OpenOnPhoneDialogColors {
        val scheme = MaterialTheme.colorScheme
        return OpenOnPhoneDialogColors(
            iconColor = ColorSchemeKeyTokens.OnPrimaryContainer.resolve(scheme),
            iconContainerColor = ColorSchemeKeyTokens.PrimaryContainer.resolve(scheme),
            progressIndicatorColor = ColorSchemeKeyTokens.Primary.resolve(scheme),
            progressTrackColor = ColorSchemeKeyTokens.OnPrimary.resolve(scheme),
            textColor = ColorSchemeKeyTokens.OnBackground.resolve(scheme),
        )
    }

    // Only the `Icon` is unported here: upstream's is the animated
    // `R.drawable.wear_m3c_open_on_phone_animation` vector (with its private `IconDelay` of 67 ms), and
    // this module ships no drawable resources, so no drawable id may be invented. Its `text` is
    // ported — see [OpenOnPhoneDialogDefaults.text].
}

/**
 * `OpenOnPhoneDialogColors`.
 *
 * @param iconColor Tint of the icon — also the colour the plate reverses *to* when the sweep ends.
 * @param iconContainerColor Plate behind the icon.
 * @param progressIndicatorColor The indicator arc.
 * @param progressTrackColor The remaining track.
 * @param textColor The curved label.
 */
class OpenOnPhoneDialogColors(
    val iconColor: Color,
    val iconContainerColor: Color,
    val progressIndicatorColor: Color,
    val progressTrackColor: Color,
    val textColor: Color,
) {
    fun copy(
        iconColor: Color = this.iconColor,
        iconContainerColor: Color = this.iconContainerColor,
        progressIndicatorColor: Color = this.progressIndicatorColor,
        progressTrackColor: Color = this.progressTrackColor,
        textColor: Color = this.textColor,
    ): OpenOnPhoneDialogColors = OpenOnPhoneDialogColors(
        iconColor = iconColor.takeOrElse { this.iconColor },
        iconContainerColor = iconContainerColor.takeOrElse { this.iconContainerColor },
        progressIndicatorColor = progressIndicatorColor.takeOrElse { this.progressIndicatorColor },
        progressTrackColor = progressTrackColor.takeOrElse { this.progressTrackColor },
        textColor = textColor.takeOrElse { this.textColor },
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OpenOnPhoneDialogColors) return false
        return iconColor == other.iconColor &&
            iconContainerColor == other.iconContainerColor &&
            progressIndicatorColor == other.progressIndicatorColor &&
            progressTrackColor == other.progressTrackColor &&
            textColor == other.textColor
    }

    override fun hashCode(): Int {
        var result = iconColor.hashCode()
        result = 31 * result + iconContainerColor.hashCode()
        result = 31 * result + progressIndicatorColor.hashCode()
        result = 31 * result + progressTrackColor.hashCode()
        result = 31 * result + textColor.hashCode()
        return result
    }
}

/** `HeightPaddingFraction` / `WidthPaddingFraction`, and the size built from the latter. */
private const val HeightPaddingFraction = 0.157f
private const val WidthPaddingFraction = 0.176f
private const val SizeFraction = 1 - WidthPaddingFraction * 2

/** `progressIndicatorStrokeWidth` / `progressIndicatorPadding`. */
private val ProgressStrokeWidth: Dp = 5.dp
private val ProgressPadding: Dp = 5.dp

/**
 * `PaddingDefaults.edgePadding`, which upstream hands to `CurvedModifier.padding` for this label.
 * Upstream's constant is a flat 2.dp (`Padding.kt`), not a screen-shaped one, so nothing is lost by
 * keeping it a constant here; and a `CurvedLayoutScope` member is not `@Tunable`, so it would have no
 * context to ask `WearScreen.edgePaddingDp` with even if it were.
 */
private val OpenOnPhoneCurvedTextEdgePadding: Dp = 2.dp
