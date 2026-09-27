package com.huanli233.hibari.wear

import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import com.huanli233.hibari.animation.Animatable
import com.huanli233.hibari.animation.AnimationSpec
import com.huanli233.hibari.animation.spring
import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.foundation.Column
import com.huanli233.hibari.foundation.ColumnScope
import com.huanli233.hibari.foundation.Spacer
import com.huanli233.hibari.foundation.attributes.alpha
import com.huanli233.hibari.foundation.attributes.height
import com.huanli233.hibari.foundation.attributes.matchParentSize
import com.huanli233.hibari.foundation.attributes.matchParentWidth
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.foundation.attributes.size
import com.huanli233.hibari.foundation.attributes.translationX
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.TunationLocalProvider
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.currentTuner
import com.huanli233.hibari.runtime.effects.DisposableEffect
import com.huanli233.hibari.runtime.effects.LaunchedEffect
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.CircleShape
import com.huanli233.hibari.ui.geometry.CornerBasedShape
import com.huanli233.hibari.ui.geometry.RoundedCornerShape
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.thenUnitViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.attributes.curvedPadding
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import com.huanli233.hibari.wear.tokens.MotionDurationTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ported from androidx.wear.compose.material3.{ConfirmationDialog, ConfirmationDialogContent,
 * SuccessConfirmationDialog, FailureConfirmationDialog} and their `*Content` siblings.
 *
 * Window presentation is not hibari-wear's job: no `Dialog`/`DialogFragment` hosting, no scrim, no
 * back handling, no swipe-to-dismiss, no focus, and `KeepScreenOn()` (a window flag) goes with them.
 * Upstream's `visible` parameter goes too — the caller places this surface itself, exactly like
 * [Card], and stops composing it to hide it. `durationMillis` is still honoured: the timer that
 * calls `onDismissRequest` once the message has been up belongs to the content, not the window.
 *
 * Not ported, with reasons:
 *  - `properties: DialogProperties` — window configuration.
 *  - `LocalAccessibilityManager.calculateRecommendedTimeoutMillis`: [durationMillis] is used
 *    verbatim instead of being stretched for the content shown. That stretch lives in Compose's
 *    platform a11y layer, which has no Hibari counterpart yet.
 *  - The animated-vector defaults `ConfirmationDialogDefaults.SuccessIcon`,
 *    `ConnectionFailureIcon`, `GenericFailureIcon` and the deprecated `FailureIcon` alias: they load
 *    `R.drawable.wear_m3c_*` from the AndroidX resource package, this module ships no resources, and
 *    no drawable id may be invented. Their `content` slots therefore have no default.
 *  - `LocalReduceMotion`: upstream zeroes the entry delays when the wearer has reduced motion on,
 *    and `OpenOnPhoneDialog` additionally freezes its progress and jumps to the end state. There is
 *    no reduce-motion local in hibari-wear yet, so the animations always run.
 *  - `CurvedScope.confirmationDialogCurvedText` is here as the top-level
 *    [CurvedLayoutScope.confirmationDialogCurvedText]: this module spells upstream's `CurvedScope`
 *    [CurvedLayoutScope], and upstream's `CurvedTextStyle` is a plain [TextStyle], which is what
 *    [CurvedLayoutScope.curvedText] takes. The slot type itself is upstream's.
 *
 * There is no turn-page or expand state in this file to port: checked against the reference tree,
 * upstream's confirmation dialog is not an expandable page and `androidx/wear/compose/material3`
 * declares no turn-page or expandable transition state at all, so `ExpandableItem` / `Scaffold`'s
 * expand state belongs to other components. Neither does the dialog's own show/hide alpha-and-scale
 * transition or the swipe-to-dismiss: those live in upstream's `Dialog.kt`, behind
 * `AnimateConfirmationDialog`, which is only the timer here.
 *
 * What *is* animated, matching what upstream animates: the icon plate's -45° settle
 * (`slowEffectsSpec`) and its circle→`CornerFull`-to-target corner morph (`defaultSpatialSpec`, or
 * `fastEffectsSpec` for the failure plate), the failure content's two-stage `translationX` shake, the
 * success plate's height growth out of the rotated circle, and the `DurationShort2`-delayed opacity
 * fades on the linear text and the curved label. That is all of it, including upstream's
 * `graphicsLayer { clip = true }`: in all three variants the clipped layer sits on a *childless*
 * plate Box, with the icon slot as its sibling rather than its content, so nothing is clipped in
 * either port and drawing the plate background at the morphed radius is the whole of the effect.
 * Content the caller puts in the icon slot overflows the plate here exactly as it does upstream.
 */

/* `MotionScheme.standard()` / `.expressive()` are not ported, so the specs they hand out are inlined
 * with their literal springs: dampingRatio 1f for standard spatial and every effects spec
 * (`Spring.DampingRatioNoBouncy`), stiffness 1400f fast / 500f default / 260f slow. */

/** `MaterialTheme.motionScheme.fastEffectsSpec()`. */
internal val DialogFastEffectsSpec: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 1400f)

/** `MaterialTheme.motionScheme.defaultEffectsSpec()`. */
internal val DialogDefaultEffectsSpec: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 500f)

/** `MaterialTheme.motionScheme.slowEffectsSpec()`. */
internal val DialogSlowEffectsSpec: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 260f)

/** `MaterialTheme.motionScheme.defaultSpatialSpec()`. */
internal val DialogDefaultSpatialSpec: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 500f)

/**
 * `ConfirmationDialogColors`: the three colour roles a confirmation dialog paints.
 *
 * Upstream holds these three as plain `val`s too — no `animateColor`, no `MutableState` — and hands
 * them out from a cached `ColorScheme` getter. Here they are recomputed per tune, so `equals` is what
 * lets a retune that changed nothing skip the plate and text redraw.
 */
class ConfirmationDialogColors(
    val iconColor: Color,
    val iconContainerColor: Color,
    val textColor: Color,
) {
    /** An [Color.Unspecified] role keeps its current value, as upstream's `takeOrElse` chain does. */
    fun copy(
        iconColor: Color = this.iconColor,
        iconContainerColor: Color = this.iconContainerColor,
        textColor: Color = this.textColor,
    ): ConfirmationDialogColors = ConfirmationDialogColors(
        iconColor = iconColor.takeOrElse { this.iconColor },
        iconContainerColor = iconContainerColor.takeOrElse { this.iconContainerColor },
        textColor = textColor.takeOrElse { this.textColor },
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConfirmationDialogColors) return false
        return iconColor == other.iconColor &&
            iconContainerColor == other.iconContainerColor &&
            textColor == other.textColor
    }

    override fun hashCode(): Int {
        var result = iconColor.hashCode()
        result = 31 * result + iconContainerColor.hashCode()
        result = 31 * result + textColor.hashCode()
        return result
    }
}

/**
 * Transient confirmation dialog with an icon and optional curved text along the bottom edge; the
 * text should be one or two words, longer content belongs in the [text]-slot overload.
 *
 * @param curvedText filled inside the `CurvedLayout` the dialog wraps its label in, at the bottom
 *   edge, as upstream. Use [confirmationDialogCurvedText] for the default sweep and padding.
 * @param colors resolved in the body from [ConfirmationDialogDefaults.colors]; a `@Tunable` default
 *   expression may not call a `@Tunable` getter.
 * @param content the icon slot, sized to [ConfirmationDialogDefaults.IconSize] upstream.
 */
@Tunable
fun ConfirmationDialog(
    onDismissRequest: () -> Unit,
    curvedText: (CurvedLayoutScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    colors: ConfirmationDialogColors? = null,
    durationMillis: Long = ConfirmationDialogDefaults.DurationMillis,
    content: @Tunable () -> Unit,
) {
    val resolved = colors ?: ConfirmationDialogDefaults.colors()
    dialogAutoDismiss(onDismissRequest, durationMillis)
    ConfirmationDialogContent(
        curvedText = curvedText,
        modifier = modifier,
        colors = resolved,
        content = content,
    )
}

/**
 * Transient confirmation dialog with an icon and short linear [text], which should stay within
 * [ConfirmationDialogDefaults.LinearContentMaxLines] lines.
 */
@Tunable
fun ConfirmationDialog(
    onDismissRequest: () -> Unit,
    text: (@Tunable ColumnScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    colors: ConfirmationDialogColors? = null,
    durationMillis: Long = ConfirmationDialogDefaults.DurationMillis,
    content: @Tunable () -> Unit,
) {
    val resolved = colors ?: ConfirmationDialogDefaults.colors()
    dialogAutoDismiss(onDismissRequest, durationMillis)
    ConfirmationDialogContent(
        text = text,
        modifier = modifier,
        colors = resolved,
        content = content,
    )
}

/** Content of the curved-content dialog, with no dismiss timer: `ConfirmationDialogContent`. */
@Tunable
fun ConfirmationDialogContent(
    curvedText: (CurvedLayoutScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    colors: ConfirmationDialogColors? = null,
    content: @Tunable () -> Unit,
) {
    val resolved = colors ?: ConfirmationDialogDefaults.colors()
    val iconSlot = content
    confirmationDialogContentWrapper(
        curvedText = curvedText,
        modifier = modifier,
        colors = resolved,
        iconContainer = {
            // `iconContainer(curvedContent = true)`: a screen-width fraction plate, extraLarge corners.
            confirmationDialogIconContainer(
                size = dialogScreenWidthFraction(currentContext, ConfirmationSizeFraction),
                targetShape = MaterialTheme.shapes.extraLarge,
                morphSpec = DialogDefaultSpatialSpec,
                rotateFrom = ConfirmationIconInitialAngle,
                iconColor = resolved.iconColor,
                containerColor = resolved.iconContainerColor,
                content = iconSlot,
            )
        },
    )
}

/** Content of the linear-content dialog, with no dismiss timer: `ConfirmationDialogContent`. */
@Tunable
fun ConfirmationDialogContent(
    text: (@Tunable ColumnScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    colors: ConfirmationDialogColors? = null,
    content: @Tunable () -> Unit,
) {
    val resolved = colors ?: ConfirmationDialogDefaults.colors()
    val context = currentContext
    val iconSlot = content
    val textSlot = text
    val opacity = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        dialogAnimatedDelay(MotionDurationTokens.DurationShort2.toLong())
        opacity.animateTo(1f, DialogFastEffectsSpec)
    }
    Box(modifier = modifier.container(dialogSurface()).matchParentSize()) {
        val horizontalPadding =
            dialogScreenWidthFraction(context, HorizontalLinearContentPaddingFraction)
        Column(
            modifier = Modifier
                .gravity(Gravity.CENTER)
                .padding(horizontal = horizontalPadding)
                .dialogChildGravity(Gravity.CENTER_HORIZONTAL),
        ) {
            Box(
                // Upstream's `contentAlignment = Center` needs no stand-in here: the plate centres
                // itself with `BoxScope.gravity`, which is how a FrameLayout host does it.
                modifier = Modifier.gravity(Gravity.CENTER_HORIZONTAL),
            ) {
                // `iconContainer(curvedContent = false)`: the fixed 80.dp plate, large corners.
                confirmationDialogIconContainer(
                    size = ConfirmationLinearIconContainerSize,
                    targetShape = MaterialTheme.shapes.large,
                    morphSpec = DialogDefaultSpatialSpec,
                    rotateFrom = ConfirmationIconInitialAngle,
                    iconColor = resolved.iconColor,
                    containerColor = resolved.iconContainerColor,
                    content = iconSlot,
                )
            }
            if (textSlot != null) {
                Spacer(Modifier.height(LinearContentSpacing))
                provideDialogText(resolved.textColor, MaterialTheme.typography.titleMedium) {
                    Column(
                        modifier = Modifier
                            .matchParentWidth()
                            .alpha(opacity.value)
                            .dialogChildGravity(Gravity.CENTER_HORIZONTAL),
                        content = { textSlot.invoke(this) },
                    )
                }
                Spacer(Modifier.height(LinearContentSpacing))
            }
        }
    }
}

/** Success confirmation dialog: a green-flagged transient message, haptic on entry. */
@Tunable
fun SuccessConfirmationDialog(
    onDismissRequest: () -> Unit,
    curvedText: (CurvedLayoutScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    colors: ConfirmationDialogColors? = null,
    durationMillis: Long = ConfirmationDialogDefaults.DurationMillis,
    content: (@Tunable () -> Unit)? = null,
) {
    val resolved = colors ?: ConfirmationDialogDefaults.successColors()
    val iconSlot = content
    dialogAutoDismiss(onDismissRequest, durationMillis)
    SuccessConfirmationDialogContent(
        curvedText = curvedText,
        // Upstream's `performHapticFeedback = { hapticFeedback.performHapticFeedback(Confirm) }` is a
        // parameter of its animate-wrapper, not of the content, so the buzz rides the dialog entry
        // point and a direct [SuccessConfirmationDialogContent] call stays silent as upstream's does.
        modifier = modifier.dialogHaptics(confirm = true),
        colors = resolved,
        content = iconSlot,
    )
}

/** Content of [SuccessConfirmationDialog]: no dismiss timer and, as upstream, no haptic. */
@Tunable
fun SuccessConfirmationDialogContent(
    curvedText: (CurvedLayoutScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    colors: ConfirmationDialogColors? = null,
    content: (@Tunable () -> Unit)? = null,
) {
    val resolved = colors ?: ConfirmationDialogDefaults.successColors()
    val context = currentContext
    val iconSlot = content
    confirmationDialogContentWrapper(
        curvedText = curvedText,
        modifier = modifier,
        colors = resolved,
        iconContainer = {
            // `successIconContainer`: `size(width, animatedHeight)` tipped 45° and clipped to a
            // circle, so the settled plate is a vertical lens.
            val width = dialogScreenWidthFraction(context, SuccessWidthFraction)
            val targetHeight = dialogScreenHeightFraction(context, SuccessHeightFraction)
            val height = remember { Animatable(width.value) }
            LaunchedEffect(Unit) {
                dialogAnimatedDelay(MotionDurationTokens.DurationShort2.toLong())
                height.animateTo(targetHeight.value, SuccessContainerAnimationSpec)
            }
            Box(
                modifier = Modifier.gravity(Gravity.CENTER),
            ) {
                // Each child carries its own gravity: a FrameLayout host has no group alignment, so
                // upstream's `contentAlignment = Center` becomes one `BoxScope.gravity` per child.
                Box(
                    modifier = Modifier
                        .gravity(Gravity.CENTER)
                        .size(DpSize(width, height.value.dp))
                        .dialogRotation(SuccessContainerRotation)
                        .container(
                            ContainerSpec(
                                shape = CircleShape,
                                containerColor = resolved.iconContainerColor,
                            ),
                        ),
                ) { }
                if (iconSlot != null) {
                    val slot = iconSlot
                    Box(modifier = Modifier.gravity(Gravity.CENTER)) {
                        provideDialogIconColor(resolved.iconColor) { slot() }
                    }
                }
            }
        },
    )
}

/** Failure confirmation dialog: a failure-flagged transient message, haptic on entry. */
@Tunable
fun FailureConfirmationDialog(
    onDismissRequest: () -> Unit,
    curvedText: (CurvedLayoutScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    colors: ConfirmationDialogColors? = null,
    durationMillis: Long = ConfirmationDialogDefaults.DurationMillis,
    content: (@Tunable () -> Unit)? = null,
) {
    val resolved = colors ?: ConfirmationDialogDefaults.failureColors()
    val iconSlot = content
    dialogAutoDismiss(onDismissRequest, durationMillis)
    FailureConfirmationDialogContent(
        curvedText = curvedText,
        // `performHapticFeedback = { hapticFeedback.performHapticFeedback(Reject) }`, on the wrapper.
        modifier = modifier.dialogHaptics(confirm = false),
        colors = resolved,
        content = iconSlot,
    )
}

/** Content of [FailureConfirmationDialog]: the plate morphs circle→extraLarge and the icon shakes. */
@Tunable
fun FailureConfirmationDialogContent(
    curvedText: (CurvedLayoutScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    colors: ConfirmationDialogColors? = null,
    content: (@Tunable () -> Unit)? = null,
) {
    val resolved = colors ?: ConfirmationDialogDefaults.failureColors()
    val context = currentContext
    val iconSlot = content
    confirmationDialogContentWrapper(
        curvedText = curvedText,
        modifier = modifier,
        colors = resolved,
        iconContainer = {
            val size = dialogScreenWidthFraction(context, FailureSizeFraction)
            val shake = remember { Animatable(FailureContentTransitionStart) }
            LaunchedEffect(Unit) {
                dialogAnimatedDelay(MotionDurationTokens.DurationShort3.toLong())
                shake.animateTo(FailureContentTransitionMiddle, FailureContentFirstSpec)
                shake.animateTo(FailureContentTransitionEnd, FailureContentSecondSpec)
            }
            // `failureIconContainer`: the plate morphs on `fastEffectsSpec` and does not rotate,
            // while `IconContainer` itself carries the two-stage `translationX` shake.
            confirmationDialogIconContainer(
                size = size,
                targetShape = MaterialTheme.shapes.extraLarge,
                morphSpec = DialogFastEffectsSpec,
                rotateFrom = null,
                iconColor = resolved.iconColor,
                containerColor = resolved.iconContainerColor,
                shakeXPx = shake.value,
                content = iconSlot,
            )
        },
    )
}

/**
 * Upstream's `CurvedScope.confirmationDialogCurvedText`: the dialog's curved label — a 120° sweep
 * budget (`CurvedTextDefaults.StaticContentMaxSweepAngle`) and `PaddingDefaults.edgePadding` on every
 * edge — filled inside the [CurvedLayout] that [ConfirmationDialog] builds around its `curvedText`
 * slot.
 *
 * [style] is required, as upstream: `ConfirmationDialogDefaults.curvedTextStyle` reads the theme, so it
 * has to be resolved by the caller's slot lambda, which is a tunable context, rather than defaulted
 * from this plain scope member.
 */
fun CurvedLayoutScope.confirmationDialogCurvedText(
    text: String,
    style: TextStyle,
): Unit = curvedText(
    text = text,
    style = style,
    maxSweepAngle = ConfirmationDialogDefaults.CurvedTextMaxSweepAngle,
    modifier = Modifier.curvedPadding(ConfirmationCurvedTextEdgePadding),
)

/** Contains the default values used by [ConfirmationDialog]. */
object ConfirmationDialogDefaults {

    /** Default timeout for a [ConfirmationDialog], in milliseconds. */
    val DurationMillis: Long = 4000L

    /** Default icon size for the curved-content [ConfirmationDialog]. */
    val IconSize: Dp = 52.dp

    /** Default icon size for the linear-content [ConfirmationDialog]. */
    val SmallIconSize: Dp = 36.dp

    /**
     * `LinearContentMaxLines`: the linear text should not exceed 3 lines. Upstream enforces it, plus
     * centred alignment and ellipsis, through `LocalTextConfiguration`; hibari-wear has no
     * text-configuration local that [Text] reads, so the number is only carried here for the caller.
     */
    const val LinearContentMaxLines = 3

    /** `CurvedTextDefaults.StaticContentMaxSweepAngle`: the arc budget a dialog label gets. */
    const val CurvedTextMaxSweepAngle: Float = 120f

    /** The default style for curved text content: `MaterialTheme.typography.arcLarge`. */
    @Tunable
    fun curvedTextStyle(): TextStyle = MaterialTheme.typography.arcLarge

    /** `ConfirmationDialogDefaults.colors()`: primary icon on an on-primary plate. */
    @Tunable
    fun colors(): ConfirmationDialogColors = defaultConfirmationDialogColors()

    /** [colors] with the listed roles overridden; [Color.Unspecified] keeps the default. */
    @Tunable
    fun colors(
        iconColor: Color = Color.Unspecified,
        iconContainerColor: Color = Color.Unspecified,
        textColor: Color = Color.Unspecified,
    ): ConfirmationDialogColors = defaultConfirmationDialogColors().copy(
        iconColor = iconColor,
        iconContainerColor = iconContainerColor,
        textColor = textColor,
    )

    /** Success-variant colours: the same three roles as [colors]. */
    @Tunable
    fun successColors(): ConfirmationDialogColors = defaultConfirmationDialogColors()

    /** [successColors] with the listed roles overridden. */
    @Tunable
    fun successColors(
        iconColor: Color = Color.Unspecified,
        iconContainerColor: Color = Color.Unspecified,
        textColor: Color = Color.Unspecified,
    ): ConfirmationDialogColors = defaultConfirmationDialogColors().copy(
        iconColor = iconColor,
        iconContainerColor = iconContainerColor,
        textColor = textColor,
    )

    /** Failure variant: an `errorDim` icon on an 80%-alpha `onError` plate. */
    @Tunable
    fun failureColors(): ConfirmationDialogColors = failureConfirmationDialogColors()

    /** [failureColors] with the listed roles overridden. */
    @Tunable
    fun failureColors(
        iconColor: Color = Color.Unspecified,
        iconContainerColor: Color = Color.Unspecified,
        textColor: Color = Color.Unspecified,
    ): ConfirmationDialogColors = failureConfirmationDialogColors().copy(
        iconColor = iconColor,
        iconContainerColor = iconContainerColor,
        textColor = textColor,
    )

    @Tunable
    private fun failureConfirmationDialogColors(): ConfirmationDialogColors {
        val scheme = MaterialTheme.colorScheme
        return ConfirmationDialogColors(
            iconColor = ColorSchemeKeyTokens.ErrorDim.resolve(scheme),
            iconContainerColor = ColorSchemeKeyTokens.OnError.resolve(scheme).copy(alpha = 0.8f),
            textColor = ColorSchemeKeyTokens.OnBackground.resolve(scheme),
        )
    }

    @Tunable
    private fun defaultConfirmationDialogColors(): ConfirmationDialogColors {
        val scheme = MaterialTheme.colorScheme
        return ConfirmationDialogColors(
            iconColor = ColorSchemeKeyTokens.Primary.resolve(scheme),
            iconContainerColor = ColorSchemeKeyTokens.OnPrimary.resolve(scheme),
            textColor = ColorSchemeKeyTokens.OnBackground.resolve(scheme),
        )
    }

    // Upstream's animated-vector icon defaults — `SuccessIcon`, `ConnectionFailureIcon`,
    // `GenericFailureIcon` and the deprecated `FailureIcon` — are not here: they need drawables this
    // module does not ship, and `IconDelay` they wait for is `DurationShort2`, used above.
}

/* ------------------------------------------------------------------ *
 * Internals                                                           *
 * ------------------------------------------------------------------ */

/**
 * `AnimateConfirmationDialog`'s timer, minus the window: the message stays up for [durationMillis]
 * and then asks the caller to dismiss it.
 *
 * Upstream runs it as `LaunchedEffect(visible, a11yDurationMillis)`, so it starts when the dialog
 * shows, restarts when either key changes, and is cancelled outright when the effect leaves the
 * composition. [LaunchedEffect] here is `remember(keys) { tunerScope.launch { } }` and what it
 * remembers is the `Job`, which is not a `RememberObserver`: nothing cancels it when the keys change
 * or when the dialog is removed from the tree, so the timer would keep counting and call
 * [onDismissRequest] on a dialog that left the window up to [durationMillis] ago. [DisposableEffect]
 * is used instead because its handle runs on the emitted view's `onDetachedFromWindow` as well as on
 * a key change and on the tuner's dispose.
 *
 * What this still does not give is upstream's re-arm on a second showing: there is no `visible`
 * key here, and hibari never prunes the remembered values of a path whose nodes went away, so the
 * caller has to place the dialog in a fresh subtree to get the timer (and the entry animations)
 * again.
 */
@Tunable
internal fun dialogAutoDismiss(onDismissRequest: () -> Unit, durationMillis: Long) {
    val dismiss = onDismissRequest
    val scope = currentTuner.coroutineScope
    DisposableEffect(durationMillis) {
        val timer = scope.launch {
            delay(durationMillis)
            dismiss()
        }
        val stop: () -> Unit = { timer.cancel() }
        stop
    }
}

/** `animatedDelay(duration, reduceMotionEnabled)`, with the reduce-motion branch dropped. */
internal suspend fun dialogAnimatedDelay(durationMillis: Long) {
    delay(durationMillis)
}

/**
 * `ConfirmationDialogContentWrapper`: the dialog surface, the icon plate centred over it, and the
 * curved label above that, faded in over [DialogFastEffectsSpec] after `DurationShort2`.
 */
@Tunable
private fun confirmationDialogContentWrapper(
    curvedText: (CurvedLayoutScope.() -> Unit)?,
    modifier: Modifier,
    colors: ConfirmationDialogColors,
    iconContainer: @Tunable BoxScope.() -> Unit,
) {
    val container = iconContainer
    val slot = curvedText
    val opacity = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        dialogAnimatedDelay(MotionDurationTokens.DurationShort2.toLong())
        opacity.animateTo(1f, DialogFastEffectsSpec)
    }
    Box(modifier = modifier.container(dialogSurface()).matchParentSize()) {
        container()
        if (slot != null) {
            provideDialogText(colors.textColor, null) {
                // `ConfirmationDialogContentWrapper`'s CurvedLayout: anchor 90f is 6 o'clock, and the
                // label is filled into it by the caller, so the alpha fades the layout itself.
                CurvedLayout(
                    modifier = Modifier.alpha(opacity.value),
                    anchor = 90f,
                    angularDirection = CurvedDirection.Angular.Reversed,
                    content = slot,
                )
            }
        }
    }
}

/**
 * `IconContainer` + `iconContainer` / `failureIconContainer`: a shaped plate behind the icon whose
 * corners open from a circle into [targetShape] on [morphSpec], optionally settling from
 * [rotateFrom] degrees on `slowEffectsSpec` (the failure plate does not rotate), and shaken
 * horizontally by [shakeXPx] (`FailureContentTransition`).
 *
 * A `BoxScope` extension because the plate positions itself with `BoxScope.gravity`, the stand-in for
 * upstream's `Modifier.align(Alignment.Center)` inside a `BoxScope.IconContainer`.
 */
@Tunable
private fun BoxScope.confirmationDialogIconContainer(
    size: Dp,
    targetShape: Shape,
    morphSpec: AnimationSpec<Float>,
    rotateFrom: Float? = ConfirmationIconInitialAngle,
    iconColor: Color,
    containerColor: Color,
    shakeXPx: Float = 0f,
    content: (@Tunable () -> Unit)?,
) {
    val slot = content
    val startAngle = rotateFrom ?: 0f
    val rotation = remember { Animatable(startAngle) }
    val morph = remember { Animatable(0f) }
    val spec = morphSpec
    LaunchedEffect(Unit) {
        dialogAnimatedDelay(MotionDurationTokens.DurationShort2.toLong())
        launch { morph.animateTo(1f, spec) }
        if (rotateFrom != null) rotation.animateTo(0f, DialogSlowEffectsSpec)
    }
    Box(
        modifier = Modifier
            .gravity(Gravity.CENTER)
            .translationX(shakeXPx),
    ) {
        // Each child carries its own gravity: a FrameLayout host has no group alignment, so
        // upstream's `contentAlignment = Center` becomes one `BoxScope.gravity` per child.
        // The plate alone carries the rotation and the morphing corner radius: upstream applies
        // `graphicsLayer { rotationZ; shape; clip }` to the background box, not to its content.
        Box(
            modifier = Modifier
                .gravity(Gravity.CENTER)
                .size(DpSize(size, size))
                .dialogRotation(rotation.value)
                .container(
                    ContainerSpec(
                        shape = morphedDialogShape(targetShape, size) { morph.value },
                        containerColor = containerColor,
                    ),
                ),
        ) { }
        if (slot != null) {
            Box(modifier = Modifier.gravity(Gravity.CENTER)) {
                provideDialogIconColor(iconColor) { slot() }
            }
        }
    }
}

/**
 * `AnimatedRoundedCornerShape(CircleShape, target) { progress }`: upstream lerps each corner from the
 * resolved circle radius to the target radius in pixels, and every plate here is square, so lerping
 * the [Dp] radius by the same fraction lands on the same numbers. Upstream lerps the four corners
 * separately (and swaps start/end under RTL); one radius for all four is exact because both the
 * circle and the `large` / `extraLarge` targets in this port set all four corners alike. A [target]
 * that is not a [CornerBasedShape] has no radius to lerp toward and is used as-is.
 */
private fun morphedDialogShape(
    target: Shape,
    size: Dp,
    progress: () -> Float,
): Shape {
    val targetRadius = (target as? CornerBasedShape)?.topStart ?: return target
    val circleRadius = size / 2f
    return RoundedCornerShape(circleRadius + (targetRadius - circleRadius) * progress())
}

@Tunable
internal fun provideDialogIconColor(color: Color, content: @Tunable () -> Unit) {
    provideContentColor(color, content)
}

/**
 * Scopes the text colour and, when given, the style — upstream's paired `LocalContentColor` and
 * `LocalTextStyle` providers. A null [textStyle] means inherit, which is how the curved-content
 * wrapper differs from the linear-content one.
 */
@Tunable
internal fun provideDialogText(
    contentColor: Color,
    textStyle: TextStyle?,
    content: @Tunable () -> Unit,
) {
    val style = textStyle
    if (style == null) {
        provideContentColor(contentColor, content)
    } else {
        TunationLocalProvider(
            LocalContentColor provides contentColor,
            LocalTextStyle provides style,
            content = content,
        )
    }
}

/**
 * `LocalHapticFeedback.performHapticFeedback(Confirm | Reject)`. A view attribute rather than an
 * effect: it fires exactly when the dialog's surface is created, which is when upstream's show
 * animation fires, and a retune that leaves the attribute equal does not re-buzz.
 *
 * `HapticFeedbackConstants.CONFIRM` (16) and `REJECT` (17) are API 30 constants, so below `R` nothing
 * is performed rather than feeding the framework a constant it does not know. Upstream's own fallback
 * is picked inside Compose's `AndroidHapticFeedback`, which is not part of the reference tree, so no
 * stand-in is invented here.
 */
private fun Modifier.dialogHaptics(confirm: Boolean): Modifier =
    this.thenUnitViewAttribute<View>(uniqueKey) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performHapticFeedback(
                if (confirm) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT,
            )
        }
    }

/** `DialogWidthPaddingFraction` / `DialogHeightPaddingFraction`, and the fractions built from them. */
private const val DialogWidthPaddingFraction = 0.2315f
private const val DialogHeightPaddingFraction = 0.176f
private const val ConfirmationSizeFraction = 1 - DialogWidthPaddingFraction * 2
private const val SuccessWidthFraction = 1 - DialogWidthPaddingFraction * 2
private const val FailureSizeFraction = 1 - DialogWidthPaddingFraction * 2
private const val SuccessHeightFraction = 1 - DialogHeightPaddingFraction * 2
private const val HorizontalLinearContentPaddingFraction = 0.12f

private val ConfirmationLinearIconContainerSize: Dp = 80.dp
private val LinearContentSpacing: Dp = 8.dp

/**
 * `PaddingDefaults.edgePadding`, which upstream hands to `CurvedModifier.padding`. Upstream's
 * constant is a flat 2.dp (material3/Padding.kt:63) with no screen-shape branch, and a
 * `CurvedLayoutScope` member is not `@Tunable` so it would have no context to ask the screen with
 * even if there were one to ask.
 */
private val ConfirmationCurvedTextEdgePadding: Dp = 2.dp

/** `ConfirmationIconInitialAngle`, and the 45° the success plate is tipped by. */
private const val ConfirmationIconInitialAngle = -45f
private const val SuccessContainerRotation = 45f

/** `SuccessContainerAnimationSpec = spring(dampingRatio = 0.55f, stiffness = 800f)`. */
private val SuccessContainerAnimationSpec: AnimationSpec<Float> =
    spring(dampingRatio = 0.55f, stiffness = 800f)

/** `FailureContentTransition`, and the two specs that walk it. */
private const val FailureContentTransitionStart = -8f
private const val FailureContentTransitionMiddle = -15f
private const val FailureContentTransitionEnd = 0f

/**
 * `FailureContentAnimationSpecs[0]`, whose upstream `spring` also passes `visibilityThreshold = 0f`
 * so the stage runs to the end of the spring's own duration instead of rounding off early. That one
 * cannot be carried: this module's `FloatSpringSpec` gets its duration by dividing the displacement
 * by the threshold, so a zero threshold makes it non-finite and the first stage never hands over to
 * the second, parking the plate at -15. With the default threshold the first shake runs a little
 * shorter than upstream's.
 */
private val FailureContentFirstSpec: AnimationSpec<Float> =
    spring(dampingRatio = ExpressiveDefaultDamping, stiffness = ExpressiveDefaultStiffness)
private val FailureContentSecondSpec: AnimationSpec<Float> =
    spring(dampingRatio = 0.5f, stiffness = ExpressiveDefaultStiffness)

/** `ExpressiveDefaultDamping` / `ExpressiveDefaultStiffness` from `MotionScheme.kt`. */
private const val ExpressiveDefaultDamping = 0.75f
private const val ExpressiveDefaultStiffness = 350f
