package com.huanli233.hibari.wear

import android.graphics.drawable.AnimatedVectorDrawable
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
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.TunationLocalProvider
import com.huanli233.hibari.runtime.bindState
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.currentTuner
import com.huanli233.hibari.runtime.effects.DisposableEffect
import com.huanli233.hibari.runtime.effects.LaunchedEffect
import com.huanli233.hibari.runtime.locals.LocalLayoutDirection
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.CircleShape
import com.huanli233.hibari.ui.geometry.CornerBasedShape
import com.huanli233.hibari.ui.geometry.RoundedCornerShape
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.text.TextAlign
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.thenUnitViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.ui.unit.LayoutDirection
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
 * back handling, no swipe-to-dismiss, no focus. `KeepScreenOn()` is the exception — upstream gates it
 * on `visible`, and here [confirmationDialogContentWrapper] calls it for as long as the surface is
 * placed.
 * Upstream's `visible` parameter goes too — the caller places this surface itself, exactly like
 * [Card], and stops composing it to hide it. `durationMillis` is still honoured: the timer that
 * calls `onDismissRequest` once the message has been up belongs to the content, not the window.
 *
 * Not ported, with reasons:
 *  - `properties: DialogProperties` — window configuration.
 *  - `LocalAccessibilityManager.calculateRecommendedTimeoutMillis`: [durationMillis] is used
 *    verbatim instead of being stretched for the content shown. That stretch lives in Compose's
 *    platform a11y layer, which has no Hibari counterpart yet.
 *  - The animated-vector defaults are ported as [ConfirmationDialogDefaults.SuccessIcon],
 *    [ConfirmationDialogDefaults.ConnectionFailureIcon], [ConfirmationDialogDefaults.GenericFailureIcon]
 *    and the deprecated [ConfirmationDialogDefaults.FailureIcon] alias, and the four success / failure
 *    entry points carry upstream's parameter defaults verbatim (`material3/ConfirmationDialog.kt:364`,
 *    `:421`, `:489`, `:552`), so an omitted `content` draws the artwork. Only the play differs: upstream loads
 *    `AnimatedImageVector.animatedVectorResource` + `rememberAnimatedVectorPainter` and flips an `atEnd`
 *    boolean after `IconDelay` = `DurationShort2` = 100 ms (`:616`, `:626`, `:620-623`, `:815`), neither
 *    class being in this module's dependency set. The same three XML bytes are therefore loaded as a
 *    platform `AnimatedVectorDrawable` and driven by drawable level, the route `OpenOnPhoneDialog.kt`
 *    established; the consequences (the play is owned by the drawable rather than by the composition, so
 *    a re-tune neither restarts nor stops it, and upstream's `AnimatedVectorPainter` is not in the
 *    reference tree so nothing about its `atEnd` handling beyond the flip can be read) are written at the
 *    members themselves. `GenericFailureIcon` has no play to lose — upstream draws it as a static
 *    `ImageVector` (`:679`, `:681`).
 *  - `LocalReduceMotion` is sampled through [wearReduceMotionEnabled], which reads `Settings.Global`
 *    live rather than caching it and refreshing from a `ContentObserver` the way upstream's local does
 *    (`foundation/CompositionLocals.kt:44-52`, `:103`). Each use below mirrors upstream's own shape:
 *    one sample in the tunable body, handed to [wearAnimatedDelay] so the entry delay is skipped
 *    outright under reduced motion. See `ReduceMotion.kt` for the whole picture.
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

/* Upstream reaches its springs through `MaterialTheme.motionScheme`, and so does this file: the specs
 * are read inside the tunable body that uses them and handed to the coroutine, never hoisted to a
 * module-level val — a `staticTunationLocal` read is only available while a tune is running. */

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
                morphSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
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
    // Upstream samples `LocalReduceMotion` once per composable and passes it into `animatedDelay`
    // (`material3/ConfirmationDialog.kt:273` then `:278`), so the setting is read in the tunable body
    // rather than inside the coroutine.
    val reduceMotion = wearReduceMotionEnabled(context)
    // Upstream's `TextOpacityAnimationSpec` is itself a `@Composable get()`
    // (`material3/ConfirmationDialog.kt:1072-1073`), so the read belongs here and the value is what
    // the coroutine captures.
    val textOpacitySpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    LaunchedEffect(Unit) {
        wearAnimatedDelay(MotionDurationTokens.DurationShort2.toLong(), reduceMotion)
        opacity.animateTo(1f, textOpacitySpec)
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
                    morphSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    rotateFrom = ConfirmationIconInitialAngle,
                    iconColor = resolved.iconColor,
                    containerColor = resolved.iconContainerColor,
                    content = iconSlot,
                )
            }
            if (textSlot != null) {
                Spacer(Modifier.height(LinearContentSpacing))
                // Upstream's linear-content scope is `LocalContentColor` + `LocalTextStyle` +
                // `LocalTextConfiguration(Center, Ellipsis, LinearContentMaxLines)`
                // (`material3/ConfirmationDialog.kt:295-304`), i.e. [provideContentColorAndStyle]'s
                // three-local form; upstream's curved-content sibling provides the colour alone (`:928`).
                provideContentColorAndStyle(
                    resolved.textColor,
                    MaterialTheme.typography.titleMedium,
                    TextConfiguration(
                        TextAlign.Center,
                        TextOverflow.Ellipsis,
                        maxLines = ConfirmationDialogDefaults.LinearContentMaxLines,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .matchParentWidth()
                            // Reading `opacity.value` here would re-tune the whole dialog once per frame
                            // of the text fade; alpha is a plain view property, so bind the state.
                            .bindState(uniqueKey, opacity.asState()) { this.alpha = it }
                            .dialogChildGravity(Gravity.CENTER_HORIZONTAL),
                        content = { textSlot.invoke(this) },
                    )
                }
                Spacer(Modifier.height(LinearContentSpacing))
            }
        }
    }
}

/**
 * Success confirmation dialog: a green-flagged transient message, haptic on entry.
 *
 * @param content upstream's default is `{ ConfirmationDialogDefaults.SuccessIcon() }`
 *   (`material3/ConfirmationDialog.kt:364`), written here as the parameter default it is upstream.
 */
@Tunable
fun SuccessConfirmationDialog(
    onDismissRequest: () -> Unit,
    curvedText: (CurvedLayoutScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    colors: ConfirmationDialogColors? = null,
    durationMillis: Long = ConfirmationDialogDefaults.DurationMillis,
    content: @Tunable () -> Unit = { ConfirmationDialogDefaults.SuccessIcon() },
) {
    val resolved = colors ?: ConfirmationDialogDefaults.successColors()
    dialogAutoDismiss(onDismissRequest, durationMillis)
    SuccessConfirmationDialogContent(
        curvedText = curvedText,
        // Upstream's `performHapticFeedback = { hapticFeedback.performHapticFeedback(Confirm) }` is a
        // parameter of its animate-wrapper, not of the content, so the buzz rides the dialog entry
        // point and a direct [SuccessConfirmationDialogContent] call stays silent as upstream's does.
        modifier = modifier.dialogHaptics(confirm = true),
        colors = resolved,
        content = content,
    )
}

/**
 * Content of [SuccessConfirmationDialog]: no dismiss timer and, as upstream, no haptic.
 *
 * @param content the same upstream default as on [SuccessConfirmationDialog] (`:421`).
 */
@Tunable
fun SuccessConfirmationDialogContent(
    curvedText: (CurvedLayoutScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    colors: ConfirmationDialogColors? = null,
    content: @Tunable () -> Unit = { ConfirmationDialogDefaults.SuccessIcon() },
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
            // Upstream's `successIconContainer` samples the local inside this same lambda
            // (`material3/ConfirmationDialog.kt:1000` then `:1003`).
            val reduceMotion = wearReduceMotionEnabled(context)
            LaunchedEffect(Unit) {
                wearAnimatedDelay(MotionDurationTokens.DurationShort2.toLong(), reduceMotion)
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
                Box(modifier = Modifier.gravity(Gravity.CENTER)) {
                    provideDialogIconColor(resolved.iconColor) { iconSlot() }
                }
            }
        },
    )
}

/**
 * Failure confirmation dialog: a failure-flagged transient message, haptic on entry.
 *
 * @param content upstream's default is `{ ConfirmationDialogDefaults.ConnectionFailureIcon() }`
 *   (`material3/ConfirmationDialog.kt:489`); [GenericFailureIcon] is the alternative upstream's own
 *   `:477-478` recommends for a generic error.
 */
@Tunable
fun FailureConfirmationDialog(
    onDismissRequest: () -> Unit,
    curvedText: (CurvedLayoutScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    colors: ConfirmationDialogColors? = null,
    durationMillis: Long = ConfirmationDialogDefaults.DurationMillis,
    content: @Tunable () -> Unit = { ConfirmationDialogDefaults.ConnectionFailureIcon() },
) {
    val resolved = colors ?: ConfirmationDialogDefaults.failureColors()
    dialogAutoDismiss(onDismissRequest, durationMillis)
    FailureConfirmationDialogContent(
        curvedText = curvedText,
        // `performHapticFeedback = { hapticFeedback.performHapticFeedback(Reject) }`, on the wrapper.
        modifier = modifier.dialogHaptics(confirm = false),
        colors = resolved,
        content = content,
    )
}

/**
 * Content of [FailureConfirmationDialog]: the plate morphs circle→extraLarge and the icon shakes.
 *
 * @param content the same upstream default as on [FailureConfirmationDialog] (`:552`).
 */
@Tunable
fun FailureConfirmationDialogContent(
    curvedText: (CurvedLayoutScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    colors: ConfirmationDialogColors? = null,
    content: @Tunable () -> Unit = { ConfirmationDialogDefaults.ConnectionFailureIcon() },
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
            val reduceMotion = wearReduceMotionEnabled(context)
            LaunchedEffect(Unit) {
                wearAnimatedDelay(MotionDurationTokens.DurationShort3.toLong(), reduceMotion)
                shake.animateTo(FailureContentTransitionMiddle, FailureContentFirstSpec)
                shake.animateTo(FailureContentTransitionEnd, FailureContentSecondSpec)
            }
            // `failureIconContainer`: the plate morphs on `fastEffectsSpec` and does not rotate,
            // while `IconContainer` itself carries the two-stage `translationX` shake. The shake is
            // bound rather than read: it lands on `View.translationX`, and `shake.value` in this body
            // would retune the whole dialog once per frame of the spring.
            confirmationDialogIconContainer(
                size = size,
                targetShape = MaterialTheme.shapes.extraLarge,
                morphSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                rotateFrom = null,
                iconColor = resolved.iconColor,
                containerColor = resolved.iconContainerColor,
                shakeModifier = Modifier.bindState(uniqueKey, shake.asState()) {
                    this.translationX = it
                },
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
 * [style] is required, exactly as upstream has it (`material3/ConfirmationDialog.kt:592` — a plain
 * `public fun`, not `@Composable`, so it cannot default from `curvedTextStyle`, which upstream only
 * recommends at `:593-594` and which reads the theme: [ConfirmationDialogDefaults.curvedTextStyle]
 * here, a `@Composable get()` there at `:604-605`). The slot it is called from is plain in both trees —
 * [CurvedLayout]'s `content` is a `CurvedLayoutScope.() -> Unit` (`CurvedLayout.kt:96`) — so the caller
 * resolves the style and hands it in.
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
     * `LinearContentMaxLines`: the linear text should not exceed 3 lines (`material3/ConfirmationDialog.kt:1058`).
     * [ConfirmationDialogContent] hands it to [Text] through [LocalTextConfiguration], together with
     * centred alignment and ellipsis, so a caller's bare [Text] in the `text` slot is budgeted by the
     * dialog.
     */
    const val LinearContentMaxLines = 3

    /** `CurvedTextDefaults.StaticContentMaxSweepAngle`: the arc budget a dialog label gets. */
    const val CurvedTextMaxSweepAngle: Float = 120f

    /** The default style for curved text content: `MaterialTheme.typography.arcLarge`. */
    @Tunable
    fun curvedTextStyle(): TextStyle = MaterialTheme.typography.arcLarge

    /**
     * `ConfirmationDialogDefaults.SuccessIcon` (`material3/ConfirmationDialog.kt:613-631`): the check
     * mark at [IconSize], drawn with the plate's ambient content colour and played once after
     * `IconDelay` — upstream's `animatedDelay(IconDelay, reduceMotionEnabled)` then `atEnd = true`
     * (`:620-623`), where `IconDelay = MotionTokens.DurationShort2.toLong()` = 100 ms (`:815`,
     * `tokens/MotionTokens.kt:43`).
     *
     * Upstream loads the artwork as an `AnimatedImageVector` and hands it to
     * `rememberAnimatedVectorPainter` (`:616`, `:626`). Neither class exists in this module's dependency
     * set, so the same XML bytes are loaded as a platform `AnimatedVectorDrawable` and driven the way
     * `OpenOnPhoneDialog.kt:399-426` drives its artwork: `start()` on the drawable's own clock, then
     * `setLevel(ConfirmationIconLevelEnd)` — the javap read of the `android-37.1` stub this session shows
     * `AnimatedVectorDrawable` overriding `onLevelChange(int)` while exposing no public seek-to-end. What
     * that costs: the play belongs to the drawable rather than to the composition, so a re-tune of this
     * member does not restart it and the dialog leaving the tree does not stop it; and
     * `androidx.compose.ui.graphics.vector.AnimatedVectorPainter` is not in the reference tree, so nothing
     * about upstream's `atEnd` beyond the flip itself can be read here. The XML parks the check path at
     * `trimPathEnd="0"` and animates it to `1` over 267 ms, so the animated path lands on the completed
     * mark from its own keyframes, and the reduce-motion branch — level set, never started — is the
     * module's standing reading of "no motion, end frame".
     *
     * No tint is applied from here: upstream's `Icon` tints off `LocalContentColor`, and [FixedSizeIcon]
     * resolves that same ambient, which both dialog plates hand down through [provideDialogIconColor].
     */
    @Tunable
    fun SuccessIcon(modifier: Modifier = Modifier) {
        val context = currentContext
        val reduceMotion = wearReduceMotionEnabled(context)
        val drawable = remember(context) {
            context.getDrawable(R.drawable.wear_m3c_check_animation) as? AnimatedVectorDrawable
        }
        LaunchedEffect(drawable, reduceMotion) {
            val icon = drawable ?: return@LaunchedEffect
            if (!reduceMotion) icon.start()
            wearAnimatedDelay(MotionDurationTokens.DurationShort2.toLong(), reduceMotion)
            icon.setLevel(ConfirmationIconLevelEnd)
        }
        // Upstream pins `LayoutDirection.Ltr` for this icon alone (`:624`), carried verbatim; the
        // direction-sensitive part is the artwork's, not this file's.
        TunationLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            FixedSizeIcon(
                image = drawable,
                contentDescription = null,
                iconSize = IconSize,
                modifier = modifier,
            )
        }
    }

    /**
     * `ConfirmationDialogDefaults.ConnectionFailureIcon` (`material3/ConfirmationDialog.kt:639-655`): the
     * broken-phone-pair artwork at [IconSize], on the same load-and-scrub route (and with the same two
     * costs) as [SuccessIcon]; upstream's `IconDelay`/`atEnd` trigger here is `:646-649`. Its XML drives
     * five targets through `trimPathEnd` keyframes, so the finished drawing is what the play lands on.
     *
     * Upstream does not wrap this one in `LocalLayoutDirection` (`:650`), and neither does this file.
     */
    @Tunable
    fun ConnectionFailureIcon(modifier: Modifier = Modifier) {
        val context = currentContext
        val reduceMotion = wearReduceMotionEnabled(context)
        val drawable = remember(context) {
            context.getDrawable(R.drawable.wear_m3c_failure_animation) as? AnimatedVectorDrawable
        }
        LaunchedEffect(drawable, reduceMotion) {
            val icon = drawable ?: return@LaunchedEffect
            if (!reduceMotion) icon.start()
            wearAnimatedDelay(MotionDurationTokens.DurationShort2.toLong(), reduceMotion)
            icon.setLevel(ConfirmationIconLevelEnd)
        }
        FixedSizeIcon(
            image = drawable,
            contentDescription = null,
            iconSize = IconSize,
            modifier = modifier,
        )
    }

    /**
     * The deprecated alias of [ConnectionFailureIcon] (`material3/ConfirmationDialog.kt:663-670`), kept
     * with upstream's message and replacement so a caller migrating from Wear Compose 1.5 sees the same
     * warning they would there.
     */
    @Deprecated(
        "This composable is provided for backwards compatibility with Compose for Wear OS 1.5. " +
            "It has been renamed to clarify the meaning of the icon. Use ConnectionFailureIcon instead.",
        replaceWith = ReplaceWith("ConnectionFailureIcon"),
        level = DeprecationLevel.WARNING,
    )
    @Tunable
    fun FailureIcon(modifier: Modifier = Modifier): Unit = ConnectionFailureIcon(modifier)

    /**
     * `ConfirmationDialogDefaults.GenericFailureIcon` (`material3/ConfirmationDialog.kt:677-685`): the
     * plain error glyph at [IconSize]. Upstream loads it as an `ImageVector` with
     * `rememberVectorPainter` (`:679`, `:681`) — a *static* vector, with no `atEnd` and no delay, unlike
     * the two animated defaults above — so this port loads `wear_m3c_error.xml` (a `<vector>` root) and
     * draws it; nothing is played and nothing is scrubbed.
     */
    @Tunable
    fun GenericFailureIcon(modifier: Modifier = Modifier) {
        val context = currentContext
        val drawable = remember(context) { context.getDrawable(R.drawable.wear_m3c_error) }
        FixedSizeIcon(
            image = drawable,
            contentDescription = null,
            iconSize = IconSize,
            modifier = modifier,
        )
    }

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
 * composition. [LaunchedEffect] here does the same: it remembers a `LaunchedEffectCanceller`
 * (`hibari-runtime/.../Effects.kt:22-39`), whose `onForgotten`/`onAbandoned` cancel the `Job`, so a
 * key change and a node leaving the tree both stop the timer instead of letting it fire
 * [onDismissRequest] on a dialog that went away up to [durationMillis] ago. An earlier revision of
 * this function used [DisposableEffect] on the belief that nothing pruned those remembered values;
 * that belief is false, and the `DisposableEffect` route was the leakier of the two, because its
 * handle reaches a view only through the node that created it.
 *
 * What this still does not give is upstream's re-arm on a second showing: there is no `visible` key
 * here, so the caller has to place the dialog in a fresh subtree to get the timer (and the entry
 * animations) again.
 */
@Tunable
internal fun dialogAutoDismiss(onDismissRequest: () -> Unit, durationMillis: Long) {
    val dismiss = onDismissRequest
    LaunchedEffect(durationMillis) {
        delay(durationMillis)
        dismiss()
    }
}

/**
 * `ConfirmationDialogContentWrapper`: the dialog surface, the icon plate centred over it, and the
 * curved label above that, faded in over `MaterialTheme.motionScheme.fastEffectsSpec()` after
 * `DurationShort2`.
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
    // Upstream's `if (visible) KeepScreenOn()` (material3/ConfirmationDialog.kt:880-884), on the same
    // fan-in point: this keeps the screen awake while the enter/exit animations run. There is no
    // `visible` parameter here — the caller places the surface and stops placing it to hide it — so
    // this surface's presence is the gate, and [KeepScreenOn]'s `RememberObserver` clears the flag
    // when the branch goes away.
    KeepScreenOn()
    val opacity = remember { Animatable(0f) }
    val labelOpacitySpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val reduceMotion = wearReduceMotionEnabled(currentContext)
    LaunchedEffect(Unit) {
        wearAnimatedDelay(MotionDurationTokens.DurationShort2.toLong(), reduceMotion)
        opacity.animateTo(1f, labelOpacitySpec)
    }
    Box(modifier = modifier.container(dialogSurface()).matchParentSize()) {
        container()
        if (slot != null) {
            provideDialogText(colors.textColor, null) {
                // `ConfirmationDialogContentWrapper`'s CurvedLayout: anchor 90f is 6 o'clock, and the
                // label is filled into it by the caller, so the alpha fades the layout itself.
                // Not a `bindState` like the linear text above: a measure-policy host gets no
                // attributes applied at creation (`Renderer.kt:99-104`), and a binding's value never
                // changes, so the subscription would install on no frame at all.
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
 * horizontally by [shakeModifier] (`FailureContentTransition`, bound by the caller so the shake
 * retunes nothing).
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
    shakeModifier: Modifier = Modifier,
    content: (@Tunable () -> Unit)?,
) {
    val slot = content
    val startAngle = rotateFrom ?: 0f
    val rotation = remember { Animatable(startAngle) }
    val morph = remember { Animatable(0f) }
    val spec = morphSpec
    val rotationSpec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    val reduceMotion = wearReduceMotionEnabled(currentContext)
    LaunchedEffect(Unit) {
        wearAnimatedDelay(MotionDurationTokens.DurationShort2.toLong(), reduceMotion)
        launch { morph.animateTo(1f, spec) }
        if (rotateFrom != null) rotation.animateTo(0f, rotationSpec)
    }
    Box(
        modifier = Modifier
            .gravity(Gravity.CENTER)
            .then(shakeModifier),
    ) {
        // Each child carries its own gravity: a FrameLayout host has no group alignment, so
        // upstream's `contentAlignment = Center` becomes one `BoxScope.gravity` per child.
        // The plate alone carries the rotation and the morphing corner radius: upstream applies
        // `graphicsLayer { rotationZ; shape; clip }` to the background box, not to its content.
        Box(
            modifier = Modifier
                .gravity(Gravity.CENTER)
                .size(DpSize(size, size))
                // `rotation.value` read here would retune the dialog every frame of the settle;
                // `dialogRotation` only ever wrote this same view property, so bind the state instead.
                .bindState(uniqueKey, rotation.asState()) { this.rotation = it }
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

/**
 * `Drawable.MAX_LEVEL`, the value [ConfirmationDialogDefaults.SuccessIcon] and
 * [ConfirmationDialogDefaults.ConnectionFailureIcon] scrub their `AnimatedVectorDrawable` to. Spelled as
 * a literal because the `android-37.1` stub this module compiles against carries no `MAX_LEVEL` constant
 * to name (javap this session), for the same reason as `OpenOnPhoneIconLevelEnd` in `OpenOnPhoneDialog.kt`.
 */
private const val ConfirmationIconLevelEnd = 10000
