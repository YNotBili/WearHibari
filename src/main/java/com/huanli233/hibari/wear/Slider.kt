package com.huanli233.hibari.wear

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.Row
import com.huanli233.hibari.foundation.attributes.height
import com.huanli233.hibari.foundation.attributes.matchParentHeight
import com.huanli233.hibari.foundation.attributes.matchParentWidth
import com.huanli233.hibari.foundation.attributes.size
import com.huanli233.hibari.foundation.attributes.width
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.util.lerp
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.tokens.SliderTokens
import com.huanli233.hibari.wear.view.SliderButtonView
import com.huanli233.hibari.wear.view.WearSliderView
import kotlin.math.roundToInt

/**
 * [Slider] allows users to make a selection from a range of values. The range of selections is shown
 * as a bar between the minimum and maximum values of the range, from which users may select a single
 * value. Slider is ideal for adjusting settings such as volume or brightness.
 *
 * Value is increased and decreased by clicking the buttons at the start and end of the control, which
 * can be given custom icons through [decreaseIcon] and [increaseIcon]. Holding one down starts
 * repeating 500 ms in and then steps every 60 ms, which are upstream's `repeatableClickable` defaults
 * (`materialcore/RepeatableClickable.kt:75-76`, installed at `materialcore/Slider.kt:52-57`). The bar
 * in the middle is split into segments when [segmented] is set; one step is
 * `(endInclusive - start) / (steps + 1)`.
 *
 * Hibari shape of the port: a [Row] of 52.dp (`SLIDER_HEIGHT`) painted through [ContainerSpec]
 * (upstream's `BoxWithConstraints(...).height(SLIDER_HEIGHT).clip(shape)` around a
 * `Row(...).background(...)`), holding the [SliderButtonView] start button, the [WearSliderView] bar
 * that carries `Modifier.drawProgressBar`, and the end button. `BoxWithConstraints` itself goes with
 * the ripple it only existed to size: upstream reads `maxWidth` from it for
 * `LocalIndication provides ripple(radius = maxWidth)`, and Hibari has no indication system.
 *
 *
 * **What upstream's `Slider` does not have**, and which is therefore absent here rather than hidden:
 *  - **No thumb, no drag, no tap-to-position.** This file's slider moves its value only through the
 *    two buttons; `SliderPosition`, `onValueChangeFinished`, `interactionSource` and the
 *    pixel-to-value hit testing of a draggable thumb belong to core `androidx.compose.material3.Slider`,
 *    which this component is not. The only `ViewConfiguration.scaledTouchSlop` this port consumes is
 *    the tap slop of the two buttons, standing in for upstream's `waitForUpOrCancellation()` followed
 *    by `repeatingJob.cancel()` (`materialcore/RepeatableClickable.kt:116-117`); the slop threshold
 *    itself is Hibari's, since the compose-foundation implementation that decides when that wait is
 *    cancelled is not in the reference tree. The active/inactive track split is upstream's selected
 *    (12.dp) over unselected (4.dp) bars, and its "tick marks" are the 2.dp separator dots; both are
 *    ported.
 *  - **No rotary input.** `Slider.kt` never imports `rotaryScroll`, `RotaryScrollable` or
 *    `Modifier.focusInterop`, and inside material3 only `Picker.kt` imports
 *    `androidx.wear.compose.foundation.rotary` (the date and time pickers get it through `Picker`);
 *    elsewhere rotary belongs to the lazy columns and the pager, which a slider is not part of. So
 *    there is nothing here to degrade: rotary turns a slider's value the way it turns any focusable
 *    on Views, which is to say not at all until a rotary host is ported.
 *  - **Semantics degrades to three things.** Upstream's surface is
 *    `Modifier.rangeSemantics(value, enabled, onValueChange, valueRange, steps)` on the root
 *    (`Slider.kt:138`, the helper itself in `RangeSemantics.kt:39-66`, not in this file),
 *    `Modifier.semantics(mergeDescendants = true) { progressBarRangeInfo = ProgressBarRangeInfo(value,
 *    valueRange) }` on each button (`Slider.kt:192-194`, `227-229`), and `Modifier.semantics {
 *    role = Role.Button }` inside `InlineSliderButton` (`materialcore/Slider.kt:49`). Views has no
 *    `mergeDescendants`, and the `rangeSemantics` payload cannot be reproduced whole: the pinned
 *    androidx.core 1.12.0 exposes `setRangeInfo(RangeInfoCompat)` and `ACTION_ARGUMENT_PROGRESS_VALUE`
 *    on `AccessibilityNodeInfoCompat` but no `ACTION_SET_PROGRESS`, so even publishing the range would
 *    leave TalkBack with nothing to act on — a documented gap, not an oversight. A sighted user cannot
 *    drag a value here either, so the two are consistent. What survives: each button is a real
 *    clickable view with an `onClick` and its icon keeps its `contentDescription`, an off slider
 *    reports `isEnabled = false`, and the bar labels itself `android.widget.ProgressBar` (see
 *    [WearSliderView.onInitializeAccessibilityNodeInfo]).
 *  - **Colour state is resolved at tune time, not animated.** Upstream's four resolvers return
 *    `State<Color>` from `animateColorAsState` (`Slider.kt:665-700`); the enabled/disabled swap here is
 *    instant, the same trade [ButtonColors] documents. The *value* tween is ported, because upstream
 *    gives it an explicit `tween(DurationShort3, EasingStandardDecelerate)` (`Slider.kt:174-184`, i.e.
 *    150 ms on `CubicBezierEasing(0f, 0f, 0f, 1f)` per `tokens/MotionTokens.kt:44,61`) instead of a
 *    default spring.
 *  - **Every `Brush` is a solid [Color]**, following [ProgressIndicatorColors]. The two icon
 *    descriptions are no longer a deviation: upstream's
 *    `wear_m3c_slider_{decrease,increase}_content_description` strings
 *    (`internal/Strings.kt:95-99`) are read from this module's own
 *    `res/values/strings.xml`, whose keys and default English text are upstream's
 *    (`res/values/wear_m3c_strings.xml:16-17`). Only the *shape* differs — upstream reaches them as a
 *    `@Composable get()` used as a default argument, which a `@Tunable` default expression cannot do,
 *    so [SliderDefaults.DecreaseIcon] and [SliderDefaults.IncreaseIcon] take `String? = null` and
 *    resolve [SliderDefaults.decreaseIconContentDescription] / [increaseIconContentDescription] in the
 *    body.
 *  - **`shape` and `colors` default to `null`** rather than to `SliderDefaults.shape` /
 *    `SliderDefaults.sliderColors()`: a `@Tunable` function's default expressions are hoisted out of
 *    its tuner scope, so nothing that reads the theme can sit there. Both are resolved in the body.
 *
 * @param value Current value of the Slider. If outside of [valueRange] provided, value will be coerced
 *   to this range.
 * @param onValueChange Lambda in which value should be updated.
 * @param steps Specifies the number of discrete values, excluding min and max values, evenly
 *   distributed across the whole value range. Must not be negative. If 0, slider will have only min and
 *   max values and no steps in between.
 * @param decreaseIcon A slot for an icon which is placed on the decrease (start) button such as
 *   [SliderDefaults.DecreaseIcon].
 * @param increaseIcon A slot for an icon which is placed on the increase (end) button such as
 *   [SliderDefaults.IncreaseIcon].
 * @param modifier Modifiers for the Slider layout.
 * @param enabled Controls the enabled state of the slider. When `false`, this slider will not be
 *   clickable.
 * @param valueRange Range of values that Slider value can take. Passed [value] will be coerced to this
 *   range.
 * @param segmented A boolean value which specifies whether a bar will be split into segments or not.
 *   Recommendation is while using this flag do not have more than [SliderDefaults.MaxSegmentSteps]
 *   steps as it might affect user experience. By default true if number of [steps] is <=
 *   [SliderDefaults.MaxSegmentSteps].
 * @param shape Defines slider's shape. Defaults to [SliderDefaults.shape]; it is strongly recommended
 *   to leave it alone, as this shape is a key characteristic of the Wear Material 3 theme.
 * @param colors [SliderColors] that will be used to resolve the background and content color for this
 *   slider in different states. Defaults to [SliderDefaults.sliderColors].
 */
@Tunable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    steps: Int,
    modifier: Modifier = Modifier,
    decreaseIcon: @Tunable () -> Unit = { SliderDefaults.DecreaseIcon() },
    increaseIcon: @Tunable () -> Unit = { SliderDefaults.IncreaseIcon() },
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..(steps + 1).toFloat(),
    segmented: Boolean = steps <= SliderDefaults.MaxSegmentSteps,
    shape: Shape? = null,
    colors: SliderColors? = null,
) {
    require(steps >= 0) { "steps should be >= 0" }
    val currentStep =
        remember(value, valueRange, steps) { sliderSnapValueToStep(value, valueRange, steps) }
    val sliderShape = shape ?: SliderDefaults.shape
    val sliderColors = colors ?: SliderDefaults.sliderColors()
    val visibleSegments = if (segmented) steps + 1 else 1

    val updateValue: (Int, View) -> Unit = { stepDiff, view ->
        val newValue =
            sliderCalculateCurrentStepValue(currentStep + stepDiff, steps, valueRange)
        if (newValue != value) {
            onValueChange(newValue)
            view.sliderRangeHaptic(
                newValue > valueRange.start && newValue < valueRange.endInclusive
            )
        }
    }
    val decreaseButtonEnabled = enabled && currentStep > 0
    val increaseButtonEnabled = enabled && currentStep < steps + 1
    val decreaseContent = decreaseIcon
    val increaseContent = increaseIcon

    Row(
        modifier = modifier
            .container(
                ContainerSpec(
                    shape = sliderShape,
                    containerColor = sliderColors.containerColor(enabled),
                )
            )
            .matchParentWidth()
            .height(SLIDER_HEIGHT),
    ) {
        SliderInlineButton(
            enabled = decreaseButtonEnabled,
            onClick = { view -> updateValue(-1, view) },
            iconColor = sliderColors.buttonIconColor(decreaseButtonEnabled),
            modifier = Modifier.width(CONTROL_SIZE).matchParentHeight(),
            content = decreaseContent,
        )
        Node(
            modifier = Modifier
                .viewClass(WearSliderView::class.java)
                .weight(1f)
                .height(BAR_HEIGHT)
                .gravity(Gravity.CENTER_VERTICAL)
                .sliderBar(
                    SliderBarSpec(
                        valueRatio = currentStep.toFloat() / (steps + 1).toFloat(),
                        visibleSegments = visibleSegments,
                        segmented = segmented,
                        shape = sliderShape,
                        selectedBarColor = sliderColors.barColor(enabled, true),
                        unselectedBarColor = sliderColors.barColor(enabled, false),
                        selectedBarSeparatorColor = sliderColors.barSeparatorColor(enabled, true),
                        unselectedBarSeparatorColor = sliderColors.barSeparatorColor(enabled, false),
                    )
                )
        )
        SliderInlineButton(
            enabled = increaseButtonEnabled,
            onClick = { view -> updateValue(1, view) },
            iconColor = sliderColors.buttonIconColor(increaseButtonEnabled),
            modifier = Modifier.width(CONTROL_SIZE).matchParentHeight(),
            content = increaseContent,
        )
    }
}

/**
 * [Slider] for an `Int` range, ported from upstream's `valueProgression` overload.
 *
 * A number of steps is calculated as the difference between max and min values of [valueProgression]
 * divided by `valueProgression.step` - 1. For example, with a range of 100..120 and a step of 5, the
 * steps are 100 (first), 105, 110, 115 and 120 (last). If the range is not equally divisible by the
 * step, `valueProgression.last` is adjusted to the closest divisible value in the range, so 1..13 with
 * a step of 5 gives 1 (first), 6 and 11 (last).
 *
 * @param value Current value of the Slider. If outside of [valueProgression] provided, value will be
 *   coerced to this range.
 * @param onValueChange Lambda in which value should be updated.
 * @param valueProgression Progression of values that Slider value can take. Consists of rangeStart,
 *   rangeEnd and step; the range will be equally divided by the step size.
 * @param decreaseIcon A slot for an icon which is placed on the decrease (start) button.
 * @param increaseIcon A slot for an icon which is placed on the increase (end) button.
 * @param modifier Modifiers for the Slider layout.
 * @param enabled Controls the enabled state of the slider.
 * @param segmented Whether the bar is split into segments. By default true if the number of steps is
 *   <= [SliderDefaults.MaxSegmentSteps].
 * @param shape Defines slider's shape. Defaults to [SliderDefaults.shape].
 * @param colors [SliderColors] for this slider in different states. Defaults to
 *   [SliderDefaults.sliderColors].
 */
@Tunable
fun Slider(
    value: Int,
    onValueChange: (Int) -> Unit,
    valueProgression: IntProgression,
    modifier: Modifier = Modifier,
    decreaseIcon: @Tunable () -> Unit = { SliderDefaults.DecreaseIcon() },
    increaseIcon: @Tunable () -> Unit = { SliderDefaults.IncreaseIcon() },
    enabled: Boolean = true,
    segmented: Boolean = valueProgression.sliderStepsNumber() <= SliderDefaults.MaxSegmentSteps,
    shape: Shape? = null,
    colors: SliderColors? = null,
) {
    Slider(
        value = value.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
        steps = valueProgression.sliderStepsNumber(),
        modifier = modifier,
        decreaseIcon = decreaseIcon,
        increaseIcon = increaseIcon,
        enabled = enabled,
        valueRange = valueProgression.first.toFloat()..valueProgression.last.toFloat(),
        segmented = segmented,
        shape = shape,
        colors = colors,
    )
}

/**
 * Hibari form of `materialcore.InlineSliderButton` as the slider uses it: a [CONTROL_SIZE] wide,
 * full-height, centred box whose content is tinted with the icon colour the button's own enabled
 * state resolves, which is upstream's `SliderButtonContent` + `LocalContentColor provides`.
 */
@Tunable
private fun SliderInlineButton(
    enabled: Boolean,
    onClick: (View) -> Unit,
    iconColor: Color,
    modifier: Modifier,
    content: @Tunable () -> Unit,
) {
    val slot = content
    Node(
        modifier = modifier
            .viewClass(SliderButtonView::class.java)
            .sliderInlineButton(SliderButtonCommand(enabled, onClick))
    ) {
        provideContentColor(iconColor) { slot() }
    }
}

/** Defaults used by slider. */
object SliderDefaults {
    /** The recommended size for Slider button icons. */
    val IconSize: Dp = 24.dp

    /** The maximum recommended number of steps for a segmented [Slider]. */
    val MaxSegmentSteps: Int = 8

    /**
     * The default content description for the increase icon. Upstream's is a `@Composable get()`
     * reading `R.string.wear_m3c_slider_increase_content_description`
     * (`internal/Strings.kt` `SliderIncreaseIconContentDescription`); here it is a `@Tunable` getter
     * over the same key, declared in this module with upstream's name and default English text.
     */
    val increaseIconContentDescription: String
        @Tunable get() = currentContext.getString(R.string.wear_m3c_slider_increase_content_description)

    /** [increaseIconContentDescription], for the decrease button: `..._decrease_content_description`. */
    val decreaseIconContentDescription: String
        @Tunable get() = currentContext.getString(R.string.wear_m3c_slider_decrease_content_description)

    /** The recommended [Shape] for Slider: `SliderTokens.ContainerShape`, i.e. corner large. */
    val shape: Shape
        @Tunable get() = MaterialTheme.shapes.fromToken(SliderTokens.ContainerShape)

    /**
     * The recommended decrease icon: upstream's `Icons.Remove`, restated as the same 960-unit path in
     * a [Drawable] because the Views side can only tint what the framework hands back (see [Icon]).
     *
     * @param modifier Modifier to be applied to the decrease icon.
     * @param contentDescription The content description for the decrease icon. Null resolves
     *   [decreaseIconContentDescription] in the body: upstream can read a `@Composable get()` as a
     *   default value, but a `@Tunable` default expression is hoisted into a non-`@Tunable` method with
     *   no tuner to ask, so the resource is read here instead.
     */
    @Tunable
    fun DecreaseIcon(
        modifier: Modifier = Modifier,
        contentDescription: String? = null,
    ) {
        val description = contentDescription ?: decreaseIconContentDescription
        Icon(
            image = SliderIconDrawable(SliderRemoveIconPath),
            contentDescription = description,
            modifier = modifier.size(DpSize(IconSize, IconSize)),
        )
    }

    /**
     * The recommended increase icon, upstream's `Icons.Add`.
     *
     * @param modifier Modifier to be applied to the increase icon.
     * @param contentDescription The content description for the increase icon. Null resolves
     *   [increaseIconContentDescription] in the body, for the reason given on [DecreaseIcon].
     */
    @Tunable
    fun IncreaseIcon(
        modifier: Modifier = Modifier,
        contentDescription: String? = null,
    ) {
        val description = contentDescription ?: increaseIconContentDescription
        Icon(
            image = SliderIconDrawable(SliderAddIconPath),
            contentDescription = description,
            modifier = modifier.size(DpSize(IconSize, IconSize)),
        )
    }

    /**
     * Creates a [SliderColors] that represents the default background and content colors used in an
     * [Slider]. Upstream memoises the palette on the [ColorScheme]; see that class for why the caches
     * are gone.
     */
    @Tunable
    fun sliderColors(): SliderColors = MaterialTheme.colorScheme.defaultSliderColor

    /**
     * Creates a [SliderColors] that represents the default background and content colors used in an
     * [Slider], with any specified colour replacing the default for its role.
     *
     * @param containerColor The background color of this [Slider] when enabled
     * @param buttonIconColor The color of the icon of buttons when enabled
     * @param selectedBarColor The color of the progress bar when enabled
     * @param unselectedBarColor The background color of the progress bar when enabled
     * @param selectedBarSeparatorColor The color of separator between visible segments within the
     *   selected portion of the bar when enabled
     * @param unselectedBarSeparatorColor The color of unselected separator between visible segments
     *   within the unselected portion of the bar when enabled
     * @param disabledContainerColor The background color of this [Slider] when disabled
     * @param disabledButtonIconColor The color of the icon of buttons when disabled
     * @param disabledSelectedBarColor The color of the progress bar when disabled
     * @param disabledUnselectedBarColor The background color of the progress bar when disabled
     * @param disabledSelectedBarSeparatorColor The color of selected separator between visible
     *   segments when disabled
     * @param disabledUnselectedBarSeparatorColor The color of unselected separator between visible
     *   segments when disabled
     */
    @Tunable
    fun sliderColors(
        containerColor: Color = Color.Unspecified,
        buttonIconColor: Color = Color.Unspecified,
        selectedBarColor: Color = Color.Unspecified,
        unselectedBarColor: Color = Color.Unspecified,
        selectedBarSeparatorColor: Color = Color.Unspecified,
        unselectedBarSeparatorColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledButtonIconColor: Color = Color.Unspecified,
        disabledSelectedBarColor: Color = Color.Unspecified,
        disabledUnselectedBarColor: Color = Color.Unspecified,
        disabledSelectedBarSeparatorColor: Color = Color.Unspecified,
        disabledUnselectedBarSeparatorColor: Color = Color.Unspecified,
    ): SliderColors = MaterialTheme.colorScheme.defaultSliderColor.copy(
        containerColor = containerColor,
        buttonIconColor = buttonIconColor,
        selectedBarColor = selectedBarColor,
        unselectedBarColor = unselectedBarColor,
        selectedBarSeparatorColor = selectedBarSeparatorColor,
        unselectedBarSeparatorColor = unselectedBarSeparatorColor,
        disabledContainerColor = disabledContainerColor,
        disabledButtonIconColor = disabledButtonIconColor,
        disabledSelectedBarColor = disabledSelectedBarColor,
        disabledUnselectedBarColor = disabledUnselectedBarColor,
        disabledSelectedBarSeparatorColor = disabledSelectedBarSeparatorColor,
        disabledUnselectedBarSeparatorColor = disabledUnselectedBarSeparatorColor,
    )

    /**
     * Creates a [SliderColors] as an alternative to the default colors, providing a visual indication
     * of value changes within a [Slider]: the selected bar becomes `primaryDim`.
     */
    @Tunable
    fun variantSliderColors(): SliderColors = MaterialTheme.colorScheme.defaultVariantSliderColor

    /**
     * The variant palette with per-role overrides.
     *
     * Faithfully includes an upstream quirk: this overload copies from `defaultSliderColor`, not from
     * [variantSliderColors], so an override left `Unspecified` falls back to the *non*-variant role
     * colour.
     *
     * @param containerColor The background color of this [Slider] when enabled
     * @param buttonIconColor The color of the icon of buttons when enabled
     * @param selectedBarColor The color of the progress bar when enabled
     * @param unselectedBarColor The background color of the progress bar when enabled
     * @param selectedBarSeparatorColor The color of separator between visible segments within the
     *   selected portion of the bar when enabled
     * @param unselectedBarSeparatorColor The color of unselected separator between visible segments
     *   within the unselected portion of the bar when enabled
     * @param disabledContainerColor The background color of this [Slider] when disabled
     * @param disabledButtonIconColor The color of the icon of buttons when disabled
     * @param disabledSelectedBarColor The color of the progress bar when disabled
     * @param disabledUnselectedBarColor The background color of the progress bar when disabled
     * @param disabledSelectedBarSeparatorColor The color of selected separator between visible
     *   segments when disabled
     * @param disabledUnselectedBarSeparatorColor The color of unselected separator between visible
     *   segments when disabled
     */
    @Tunable
    fun variantSliderColors(
        containerColor: Color = Color.Unspecified,
        buttonIconColor: Color = Color.Unspecified,
        selectedBarColor: Color = Color.Unspecified,
        unselectedBarColor: Color = Color.Unspecified,
        selectedBarSeparatorColor: Color = Color.Unspecified,
        unselectedBarSeparatorColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledButtonIconColor: Color = Color.Unspecified,
        disabledSelectedBarColor: Color = Color.Unspecified,
        disabledUnselectedBarColor: Color = Color.Unspecified,
        disabledSelectedBarSeparatorColor: Color = Color.Unspecified,
        disabledUnselectedBarSeparatorColor: Color = Color.Unspecified,
    ): SliderColors = MaterialTheme.colorScheme.defaultSliderColor.copy(
        containerColor = containerColor,
        buttonIconColor = buttonIconColor,
        selectedBarColor = selectedBarColor,
        unselectedBarColor = unselectedBarColor,
        selectedBarSeparatorColor = selectedBarSeparatorColor,
        unselectedBarSeparatorColor = unselectedBarSeparatorColor,
        disabledContainerColor = disabledContainerColor,
        disabledButtonIconColor = disabledButtonIconColor,
        disabledSelectedBarColor = disabledSelectedBarColor,
        disabledUnselectedBarColor = disabledUnselectedBarColor,
        disabledSelectedBarSeparatorColor = disabledSelectedBarSeparatorColor,
        disabledUnselectedBarSeparatorColor = disabledUnselectedBarSeparatorColor,
    )

    private val ColorScheme.defaultSliderColor: SliderColors
        get() = SliderColors(
            containerColor = SliderTokens.ContainerColor.resolve(this),
            buttonIconColor = SliderTokens.ButtonIconColor.resolve(this),
            selectedBarColor = SliderTokens.SelectedBarColor.resolve(this),
            unselectedBarColor = SliderTokens.UnselectedBarColor.resolve(this)
                .copy(alpha = SliderTokens.UnselectedBarOpacity),
            selectedBarSeparatorColor = SliderTokens.SelectedBarSeparatorColor.resolve(this),
            unselectedBarSeparatorColor = SliderTokens.UnselectedBarSeparatorColor.resolve(this)
                .copy(alpha = SliderTokens.UnselectedBarSeparatorOpacity),
            disabledContainerColor = SliderTokens.DisabledContainerColor.resolve(this)
                .toDisabledColor(SliderTokens.DisabledContainerOpacity),
            disabledButtonIconColor = SliderTokens.DisabledButtonIconColor.resolve(this)
                .toDisabledColor(SliderTokens.DisabledButtonIconOpacity),
            disabledSelectedBarColor = SliderTokens.DisabledSelectedBarColor.resolve(this),
            disabledUnselectedBarColor = SliderTokens.DisabledUnselectedBarColor.resolve(this)
                .copy(alpha = SliderTokens.DisabledUnselectedBarOpacity),
            disabledSelectedBarSeparatorColor =
            SliderTokens.DisabledSelectedBarSeparatorColor.resolve(this),
            disabledUnselectedBarSeparatorColor =
            SliderTokens.DisabledUnselectedBarSeparatorColor.resolve(this)
                .copy(alpha = SliderTokens.DisabledUnselectedBarSeparatorOpacity),
        )

    private val ColorScheme.defaultVariantSliderColor: SliderColors
        get() = SliderColors(
            containerColor = SliderTokens.ContainerColor.resolve(this),
            buttonIconColor = SliderTokens.ButtonIconColor.resolve(this),
            selectedBarColor = SliderTokens.VariantSelectedBarColor.resolve(this),
            unselectedBarColor = SliderTokens.UnselectedBarColor.resolve(this)
                .copy(alpha = SliderTokens.UnselectedBarOpacity),
            selectedBarSeparatorColor = SliderTokens.SelectedBarSeparatorColor.resolve(this),
            unselectedBarSeparatorColor = SliderTokens.VariantUnselectedBarSeparatorColor.resolve(this)
                .copy(alpha = SliderTokens.UnselectedBarSeparatorOpacity),
            disabledContainerColor = SliderTokens.DisabledContainerColor.resolve(this)
                .toDisabledColor(SliderTokens.DisabledContainerOpacity),
            disabledButtonIconColor = SliderTokens.DisabledButtonIconColor.resolve(this)
                .toDisabledColor(SliderTokens.DisabledButtonIconOpacity),
            disabledSelectedBarColor = SliderTokens.DisabledSelectedBarColor.resolve(this),
            disabledUnselectedBarColor = SliderTokens.DisabledUnselectedBarColor.resolve(this)
                .copy(alpha = SliderTokens.DisabledUnselectedBarOpacity),
            disabledSelectedBarSeparatorColor =
            SliderTokens.DisabledSelectedBarSeparatorColor.resolve(this),
            disabledUnselectedBarSeparatorColor =
            SliderTokens.DisabledUnselectedBarSeparatorColor.resolve(this)
                .copy(alpha = SliderTokens.DisabledUnselectedBarSeparatorOpacity),
        )
}

/**
 * Represents the background and content colors used in [Slider] in different states.
 *
 * Upstream's four state resolvers return `State<Color>` from `animateColorAsState`; here they are
 * plain functions of the state, resolved once per tune by [Slider] and handed to
 * [ContainerSpec]/[WearSliderView]. The names, the argument lists and which field each state picks
 * are upstream's.
 *
 * @param containerColor The background color of this [Slider] when enabled.
 * @param buttonIconColor The color of the icon of buttons when enabled.
 * @param selectedBarColor The color of the progress bar when enabled.
 * @param unselectedBarColor The background color of the progress bar when enabled.
 * @param selectedBarSeparatorColor The color of selected separator between visible segments when
 *   enabled.
 * @param unselectedBarSeparatorColor The color of unselected separator between visible segments when
 *   enabled.
 * @param disabledContainerColor The background color of this [Slider] when disabled.
 * @param disabledButtonIconColor The color of the icon of buttons when disabled.
 * @param disabledSelectedBarColor The color of the progress bar when disabled.
 * @param disabledUnselectedBarColor The background color of the progress bar when disabled.
 * @param disabledSelectedBarSeparatorColor The color of selected separator between visible segments
 *   when disabled.
 * @param disabledUnselectedBarSeparatorColor The color of unselected separator between visible
 *   segments when disabled.
 * @constructor create an instance with arbitrary colors. See [SliderDefaults.sliderColors] for the
 *   default implementation that follows Material specifications.
 */
class SliderColors(
    val containerColor: Color,
    val buttonIconColor: Color,
    val selectedBarColor: Color,
    val unselectedBarColor: Color,
    val selectedBarSeparatorColor: Color,
    val unselectedBarSeparatorColor: Color,
    val disabledContainerColor: Color,
    val disabledButtonIconColor: Color,
    val disabledSelectedBarColor: Color,
    val disabledUnselectedBarColor: Color,
    val disabledSelectedBarSeparatorColor: Color,
    val disabledUnselectedBarSeparatorColor: Color,
) {

    /**
     * Returns a copy of this SliderColors optionally overriding some of the values. As upstream, a
     * [Color.Unspecified] argument keeps the current value rather than clearing the role.
     *
     * @param containerColor The background color of this [Slider] when enabled.
     * @param buttonIconColor The color of the icon of buttons when enabled.
     * @param selectedBarColor The color of the progress bar when enabled.
     * @param unselectedBarColor The background color of the progress bar when enabled.
     * @param selectedBarSeparatorColor The color of selected separator between visible segments when
     *   enabled.
     * @param unselectedBarSeparatorColor The color of unselected separator between visible segments
     *   when enabled.
     * @param disabledContainerColor The background color of this [Slider] when disabled.
     * @param disabledButtonIconColor The color of the icon of buttons when disabled.
     * @param disabledSelectedBarColor The color of the progress bar when disabled.
     * @param disabledUnselectedBarColor The background color of the progress bar when disabled.
     * @param disabledSelectedBarSeparatorColor The color of selected separator between visible
     *   segments when disabled.
     * @param disabledUnselectedBarSeparatorColor The color of unselected separator between visible
     *   segments when disabled.
     */
    fun copy(
        containerColor: Color = this.containerColor,
        buttonIconColor: Color = this.buttonIconColor,
        selectedBarColor: Color = this.selectedBarColor,
        unselectedBarColor: Color = this.unselectedBarColor,
        selectedBarSeparatorColor: Color = this.selectedBarSeparatorColor,
        unselectedBarSeparatorColor: Color = this.unselectedBarSeparatorColor,
        disabledContainerColor: Color = this.disabledContainerColor,
        disabledButtonIconColor: Color = this.disabledButtonIconColor,
        disabledSelectedBarColor: Color = this.disabledSelectedBarColor,
        disabledUnselectedBarColor: Color = this.disabledUnselectedBarColor,
        disabledSelectedBarSeparatorColor: Color = this.disabledSelectedBarSeparatorColor,
        disabledUnselectedBarSeparatorColor: Color = this.disabledUnselectedBarSeparatorColor,
    ): SliderColors = SliderColors(
        containerColor = containerColor.takeOrElse { this.containerColor },
        buttonIconColor = buttonIconColor.takeOrElse { this.buttonIconColor },
        selectedBarColor = selectedBarColor.takeOrElse { this.selectedBarColor },
        unselectedBarColor = unselectedBarColor.takeOrElse { this.unselectedBarColor },
        selectedBarSeparatorColor =
        selectedBarSeparatorColor.takeOrElse { this.selectedBarSeparatorColor },
        unselectedBarSeparatorColor =
        unselectedBarSeparatorColor.takeOrElse { this.unselectedBarSeparatorColor },
        disabledContainerColor = disabledContainerColor.takeOrElse { this.disabledContainerColor },
        disabledButtonIconColor = disabledButtonIconColor.takeOrElse { this.disabledButtonIconColor },
        disabledSelectedBarColor = disabledSelectedBarColor.takeOrElse { this.disabledSelectedBarColor },
        disabledUnselectedBarColor =
        disabledUnselectedBarColor.takeOrElse { this.disabledUnselectedBarColor },
        disabledSelectedBarSeparatorColor = disabledSelectedBarSeparatorColor.takeOrElse {
            this.disabledSelectedBarSeparatorColor
        },
        disabledUnselectedBarSeparatorColor = disabledUnselectedBarSeparatorColor.takeOrElse {
            this.disabledUnselectedBarSeparatorColor
        },
    )

    internal fun containerColor(enabled: Boolean): Color =
        if (enabled) containerColor else disabledContainerColor

    internal fun buttonIconColor(enabled: Boolean): Color =
        if (enabled) buttonIconColor else disabledButtonIconColor

    internal fun barSeparatorColor(enabled: Boolean, selected: Boolean): Color = when {
        enabled && selected -> selectedBarSeparatorColor
        enabled && !selected -> unselectedBarSeparatorColor
        !enabled && selected -> disabledSelectedBarSeparatorColor
        else -> disabledUnselectedBarSeparatorColor
    }

    internal fun barColor(enabled: Boolean, selected: Boolean): Color = when {
        enabled && selected -> selectedBarColor
        enabled && !selected -> unselectedBarColor
        !enabled && selected -> disabledSelectedBarColor
        else -> disabledUnselectedBarColor
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null) return false
        if (this::class != other::class) return false

        other as SliderColors

        if (containerColor != other.containerColor) return false
        if (buttonIconColor != other.buttonIconColor) return false
        if (selectedBarColor != other.selectedBarColor) return false
        if (unselectedBarColor != other.unselectedBarColor) return false
        if (selectedBarSeparatorColor != other.selectedBarSeparatorColor) return false
        if (unselectedBarSeparatorColor != other.unselectedBarSeparatorColor) return false
        if (disabledContainerColor != other.disabledContainerColor) return false
        if (disabledButtonIconColor != other.disabledButtonIconColor) return false
        if (disabledSelectedBarColor != other.disabledSelectedBarColor) return false
        if (disabledUnselectedBarColor != other.disabledUnselectedBarColor) return false
        if (disabledSelectedBarSeparatorColor != other.disabledSelectedBarSeparatorColor) return false
        if (disabledUnselectedBarSeparatorColor != other.disabledUnselectedBarSeparatorColor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = containerColor.hashCode()
        result = 31 * result + buttonIconColor.hashCode()
        result = 31 * result + selectedBarColor.hashCode()
        result = 31 * result + unselectedBarColor.hashCode()
        result = 31 * result + selectedBarSeparatorColor.hashCode()
        result = 31 * result + unselectedBarSeparatorColor.hashCode()
        result = 31 * result + disabledContainerColor.hashCode()
        result = 31 * result + disabledButtonIconColor.hashCode()
        result = 31 * result + disabledSelectedBarColor.hashCode()
        result = 31 * result + disabledUnselectedBarColor.hashCode()
        result = 31 * result + disabledSelectedBarSeparatorColor.hashCode()
        result = 31 * result + disabledUnselectedBarSeparatorColor.hashCode()
        return result
    }
}

/** Everything [WearSliderView] draws, as one immutable value so a no-change retune stays a no-op. */
private data class SliderBarSpec(
    val valueRatio: Float,
    val visibleSegments: Int,
    val segmented: Boolean,
    val shape: Shape,
    val selectedBarColor: Color,
    val unselectedBarColor: Color,
    val selectedBarSeparatorColor: Color,
    val unselectedBarSeparatorColor: Color,
)

private data class SliderButtonCommand(
    val enabled: Boolean,
    val onClick: (View) -> Unit,
)

/**
 * The port of `repeatableClickable`'s click half (`materialcore/RepeatableClickable.kt:89-101`),
 * applied to [SliderButtonView] for the repeat.
 * [com.huanli233.hibari.wear.attributes.clickable] is not used because it also turns long-clicking
 * on (`WearInteractionAttributes.kt:22`), and the framework long press fires on the very 500 ms
 * boundary the repeat owns.
 *
 * The command carries a lambda, so the attribute never compares equal and every retune re-applies it.
 * That is deliberate and cheap here: all three writes are sets, not adds — `setOnClickListener`
 * replaces the handler, so a retune cannot stack listeners the way an `addOn*Listener` would.
 */
private fun Modifier.sliderInlineButton(command: SliderButtonCommand): Modifier =
    this.thenViewAttribute<SliderButtonView, SliderButtonCommand>(uniqueKey, command) { value ->
        isEnabled = value.enabled
        isClickable = value.enabled
        setOnClickListener(
            if (value.enabled) View.OnClickListener { clicked -> value.onClick(clicked) } else null
        )
    }

private fun Modifier.sliderBar(spec: SliderBarSpec): Modifier =
    this.thenViewAttribute<WearSliderView, SliderBarSpec>(uniqueKey, spec) {
        valueRatio = it.valueRatio
        visibleSegments = it.visibleSegments
        segmented = it.segmented
        shape = it.shape
        selectedBarColor = it.selectedBarColor
        unselectedBarColor = it.unselectedBarColor
        selectedBarSeparatorColor = it.selectedBarSeparatorColor
        unselectedBarSeparatorColor = it.unselectedBarSeparatorColor
    }

/**
 * `HapticFeedbackType.SegmentFrequentTick` and `GestureEnd`, which Compose forwards to the platform
 * constants of the same name (`Slider.kt:149-153` upstream). Both post-date the module's minSdk of 25,
 * so below their own level the step is silent — exactly what the platform does with an id it does not
 * know, which is why the guards matter only for honesty: `SEGMENT_FREQUENT_TICK` is API 34 and
 * `GESTURE_END` is API 30. The same pair, gated the same way, is in `Stepper.kt`'s
 * `stepperHapticFeedback`; the two are deliberately identical.
 */
private fun View.sliderRangeHaptic(interiorValue: Boolean) {
    if (interiorValue) {
        if (Build.VERSION.SDK_INT >= 34) {
            performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
        }
    } else if (Build.VERSION.SDK_INT >= 30) {
        performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
    }
}

/**
 * `RangeDefaults.snapValueToStep` (`materialcore/RangeDefaults.kt:69-76`): the step *index* [value]
 * sits on, clamped to `0..steps + 1`. Named for the shared range helper it ports, kept private so the
 * range controls stay free of each other.
 */
private fun sliderSnapValueToStep(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Int =
    ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start) * (steps + 1))
        .roundToInt()
        .coerceIn(0, steps + 1)

/**
 * `RangeDefaults.calculateCurrentStepValue` (`materialcore/RangeDefaults.kt:56-66`): step index back to
 * a value through the same `lerp`, coerced into the range.
 */
private fun sliderCalculateCurrentStepValue(
    currentStep: Int,
    steps: Int,
    valueRange: ClosedFloatingPointRange<Float>,
): Float =
    lerp(
        valueRange.start,
        valueRange.endInclusive,
        currentStep.toFloat() / (steps + 1).toFloat(),
    )
        .coerceIn(valueRange)

/**
 * `RangeSemantics.stepsNumber` (`RangeSemantics.kt:68`): the steps between, excluding the two ends.
 */
private fun IntProgression.sliderStepsNumber(): Int = (last - first) / step - 1

/** A material icon path in the 960-unit viewport upstream authors them in. */
private class SliderIconDrawable(private val iconPath: Path) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.FILL
        // `materialPath(fill = SolidColor(Color.White))`: the ImageView tint replaces the channels, so
        // the fill only decides what an untinted slider looks like.
        it.color = 0xFFFFFFFF.toInt()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val scale = minOf(b.width(), b.height()) / SliderIconViewport
        val saved = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(scale, scale)
        canvas.drawPath(iconPath, paint)
        canvas.restoreToCount(saved)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION") // Required below API 33, ignored by Canvas above it.
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** `ImageVector.Builder(viewportWidth = MaterialIconViewPortDimension)` from upstream's Icons.kt. */
private const val SliderIconViewport = 960f

/** `Icons.Add`, verbatim. */
private val SliderAddIconPath = Path().apply {
    moveTo(440f, 520f)
    lineTo(240f, 520f)
    quadTo(223f, 520f, 211.5f, 508.5f)
    quadTo(200f, 497f, 200f, 480f)
    quadTo(200f, 463f, 211.5f, 451.5f)
    quadTo(223f, 440f, 240f, 440f)
    lineTo(440f, 440f)
    lineTo(440f, 240f)
    quadTo(440f, 223f, 451.5f, 211.5f)
    quadTo(463f, 200f, 480f, 200f)
    quadTo(497f, 200f, 508.5f, 211.5f)
    quadTo(520f, 223f, 520f, 240f)
    lineTo(520f, 440f)
    lineTo(720f, 440f)
    quadTo(737f, 440f, 748.5f, 451.5f)
    quadTo(760f, 463f, 760f, 480f)
    quadTo(760f, 497f, 748.5f, 508.5f)
    quadTo(737f, 520f, 720f, 520f)
    lineTo(520f, 520f)
    lineTo(520f, 720f)
    quadTo(520f, 737f, 508.5f, 748.5f)
    quadTo(497f, 760f, 480f, 760f)
    quadTo(463f, 760f, 451.5f, 748.5f)
    quadTo(440f, 737f, 440f, 720f)
    lineTo(440f, 520f)
    close()
}

/** `Icons.Remove`, verbatim. */
private val SliderRemoveIconPath = Path().apply {
    moveTo(240f, 520f)
    quadTo(223f, 520f, 211.5f, 508.5f)
    quadTo(200f, 497f, 200f, 480f)
    quadTo(200f, 463f, 211.5f, 451.5f)
    quadTo(223f, 440f, 240f, 440f)
    lineTo(720f, 440f)
    quadTo(737f, 440f, 748.5f, 451.5f)
    quadTo(760f, 463f, 760f, 480f)
    quadTo(760f, 497f, 748.5f, 508.5f)
    quadTo(737f, 520f, 720f, 520f)
    lineTo(240f, 520f)
    close()
}

// The three layout dimensions from the bottom of upstream's Slider.kt; the bar's four drawing
// dimensions live in WearSliderView, which is the only place they can be read in pixels.
private val SLIDER_HEIGHT = 52.dp
private val CONTROL_SIZE = 48.dp
private val BAR_HEIGHT = 12.dp
