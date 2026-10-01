/**
 * Header deviations from androidx.wear.compose.material3.Stepper, all of them gaps rather than
 * choices — see the numbered notes below. Nothing here is silently stubbed.
 *
 * TEXT INPUT. The brief asked whether upstream's editable `BasicTextField` + keyboard-visibility
 * path has a Hibari counterpart. It does not exist *in this component*: `material3/Stepper.kt` (485
 * lines), `materialcore/Stepper.kt` (177 lines) and `material/Stepper.kt` have no text field at all —
 * `grep -rn "BasicTextField\|KeyboardVisibility\|TextFieldValue" androidx/wear/compose` returns zero
 * hits, and that is zero across the *whole* wear tree: no `TimeInput` or `EditableBase` file is
 * vendored here, and `material3/TimePicker.kt` matches none of those three names either. So there is
 * no upstream text field to port, and no upstream parameter is dropped for lack of one. Upstream's
 * `Stepper.content` is an opaque `BoxScope` slot (`material3/Stepper.kt:114`) and the value display is
 * the caller's business.
 *
 * Hibari itself *does* have a text-input path, so nothing would block an editable readout:
 * `com.huanli233.hibari.material.EditText` (`AppCompatEditText`, `inputType`, `doOnTextChanged`)
 * and `hibari-material/TextField.kt` (`TextInputEditText`), both on `hibari-wear`'s compile path
 * through `api(project(":hibari-material"))`. Put one of them in `content` and the keyboard, the
 * value and the increment/decrement buttons all drive the same `onValueChange`. So the answer to
 * the empirical question is "a path exists", and the answer for this file is "there was nothing to
 * wire": no upstream parameter is dropped for lack of text input.
 *
 * What is actually dropped, and why:
 * 1. `interactionSource`, `ripple()` indication and `semantics { role = Role.Button }` on the
 *    increase/decrease button (`material3/Stepper.kt:418,438,443-451`) — no interaction-source, ripple
 *    or semantics layer in this module (same omissions as `Button.kt` / `Card.kt`). Nothing fades on
 *    press here — with no `pressedContainerColor` in the spec a press leaves the container alone, as
 *    upstream's *colour* does; only the enabled/disabled pair in [ContainerSpec] animates.
 *    `Modifier.rangeSemantics` is *not* in this list: unlike the
 *    Slider, upstream's `Stepper` never applies it, only telling the caller to (`Stepper.kt:67,137`),
 *    so the caller-side position is the same here.
 * 2. `animateButtonShape` (`material3/Stepper.kt:421-428`) — upstream springs the container from
 *    `StepperButtonShape` (= `ShapeTokens.CornerFull`, `460-461`) to `StepperButtonPressedShape`
 *    (= `MaterialTheme.shapes.small`, `464-465`) over `motionScheme.fastSpatialSpec<Float>()
 *    .faster(200f)` on press and `slowSpatialSpec()` on release. There is no `MotionScheme` port
 *    (`Theme.kt` documents that) and no interaction source to drive it, so the container keeps the
 *    resting shape while pressed. No substitute radius is invented.
 * 3. `pressedShape` therefore has no slot on the increase/decrease button and is absent rather
 *    than dead.
 * 4. `colors` defaults to `null` and is resolved in the body: a `@Tunable` default-expression is
 *    hoisted out of the tuner scope, where `StepperDefaults.colors()` cannot read the theme.
 * 5. The content slot is `ColumnScope`, not `BoxScope`: `contentAlignment` is reproduced with
 *    `LinearLayout.setGravity`, which a `FrameLayout` has no equivalent of, and `BoxScope`'s
 *    `Modifier.gravity` would write `FrameLayout.LayoutParams` into a `LinearLayout` and crash.
 * 6. `Arrangement.spacedBy(VerticalSpacing)` becomes top+bottom margins on the content frame — the
 *    only two gaps in a three-child column, so the geometry is identical.
 * 7. `LocalHapticFeedback` / `HapticFeedbackType` do not exist here, so the tick is performed on the
 *    clicked view with the platform constants of the same name (`material3/Stepper.kt:366-369`),
 *    guarded at their own API levels — `SEGMENT_FREQUENT_TICK` is 34 and `GESTURE_END` is 30. Below a
 *    guard the step is silent, which is the same outcome the platform gives an id it does not know;
 *    no stand-in constant is substituted, because that would buzz where upstream is quiet.
 *    `Slider.kt`'s `sliderRangeHaptic` carries the same pair with the same guards.
 * 8. Upstream shares one `RangeDefaults` and one `Modifier.repeatableClickable` between the two range
 *    controls (`materialcore/RangeDefaults.kt:56-76`; the click helper is installed at
 *    `material3/Stepper.kt:440-445` and at `materialcore/Slider.kt:52-57`, both on
 *    `materialcore/RepeatableClickable.kt:69-79`). Hibari keeps a private copy per file instead, so
 *    the maths is re-implemented here under `stepper`-prefixed names and the gesture loop exists
 *    twice — [stepperRepeatableClickable] here, `SliderButtonView` in `view/WearSliderView.kt` — with
 *    the same 500 ms / 60 ms numbers. That duplication is known and should collapse into one shared
 *    modifier; it is not hidden behind a claim of sharing. Upstream's `RangeIcons.Minus` is not
 *    ported: it is an `ImageVector`, and [Icon] can only tint what the framework hands back (see
 *    `Icon.kt`).
 * 9. Upstream has no `StepperTokens` — `StepperDefaults` carries the literal TODO "b/369766493 - Add
 *    color tokens for Stepper" — so the colours resolve straight off `ColorSchemeKeyTokens` and no
 *    new token object is invented. There are also no text styles in upstream's `StepperDefaults`;
 *    [IconSize] is its only dimension there.
 * 10. `StepperLevelIndicator`, referenced by upstream's KDoc, belongs to `LevelIndicator.kt`.
 */

package com.huanli233.hibari.wear

import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import com.huanli233.hibari.foundation.Column
import com.huanli233.hibari.foundation.ColumnScope
import com.huanli233.hibari.foundation.attributes.height
import com.huanli233.hibari.foundation.attributes.margin
import com.huanli233.hibari.foundation.attributes.matchParentSize
import com.huanli233.hibari.foundation.attributes.matchParentWidth
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.foundation.attributes.size
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
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.util.lerp
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import com.huanli233.hibari.wear.tokens.ShapeTokens
import kotlin.math.roundToInt

/**
 * Ported from androidx.wear.compose.material3.Stepper.
 *
 * [Stepper] allows users to make a selection from a range of values. It's a full-screen control
 * with increase button on the top, decrease button on the bottom and a slot (expected to have
 * either [Text] or [Button]) in the middle. Value can be increased and decreased by clicking on the
 * increase and decrease buttons. Buttons can have custom icons - [decreaseIcon] and [increaseIcon].
 * Step value is calculated as the difference between min and max values divided by [steps]+1.
 * Stepper itself doesn't show the current value but can be displayed via the content slot or a
 * level indicator if required. If [value] is not equal to any step value, then it will be coerced
 * to the closest step value. However, the [value] itself will not be changed and [onValueChange] in
 * this case will not be triggered.
 *
 * Holding either button repeats it: [StepperDefaults.RepeatInitialDelayMillis] ms after the press
 * and then every [StepperDefaults.RepeatIncrementalDelayMillis] ms, and the release click is
 * swallowed so a long press neither double-counts nor lands as a plain tap.
 *
 * See the file header for the parameters upstream carries that this port does not accept.
 *
 * @param value Current value of the Stepper. If outside of [valueRange] provided, value will be
 *   coerced to this range.
 * @param onValueChange Lambda in which value should be updated.
 * @param steps Specifies the number of discrete values, excluding min and max values, evenly
 *   distributed across the whole value range. Must not be negative. If 0, stepper will have only
 *   min and max values and no steps in between.
 * @param decreaseIcon A slot for an icon which is placed on the decrease (bottom) button.
 * @param increaseIcon A slot for an icon which is placed on the increase (top) button.
 * @param modifier Modifiers for the Stepper layout.
 * @param enabled Whether the [Stepper] is enabled.
 * @param valueRange Range of values that Stepper value can take. Passed [value] will be coerced to
 *   this range.
 * @param colors [StepperColors] that will be used to resolve the colors used for this [Stepper].
 *   Leave at `null` for [StepperDefaults.colors]; see file-header note 4.
 * @param content Content body for the Stepper.
 */
@Tunable
fun Stepper(
    value: Float,
    onValueChange: (Float) -> Unit,
    steps: Int,
    decreaseIcon: @Tunable () -> Unit,
    increaseIcon: @Tunable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..(steps + 1).toFloat(),
    colors: StepperColors? = null,
    content: @Tunable ColumnScope.() -> Unit,
) {
    StepperImpl(
        value = value,
        onValueChange = onValueChange,
        steps = steps,
        decreaseIcon = decreaseIcon,
        increaseIcon = increaseIcon,
        valueRange = valueRange,
        modifier = modifier,
        colors = colors ?: StepperDefaults.colors(),
        enabled = enabled,
        content = content,
    )
}

/**
 * Ported from androidx.wear.compose.material3.Stepper (the `Int` / [IntProgression] overload).
 *
 * A number of steps is calculated as the difference between max and min values of
 * [valueProgression] divided by [valueProgression].step - 1. For example, with a range of 100..120
 * and a step 5, number of steps will be (120-100)/ 5 - 1 = 3. Steps are 100(first), 105, 110, 115,
 * 120(last)
 *
 * If [valueProgression] range is not equally divisible by [valueProgression].step, then
 * [valueProgression].last will be adjusted to the closest divisible value in the range. For
 * example, 1..13 range and a step = 5, steps will be 1(first), 6, 11(last)
 *
 * If [value] is not equal to any step value, then it will be coerced to the closest step value.
 * However, the [value] itself will not be changed and [onValueChange] in this case will not be
 * triggered.
 *
 * @param value Current value of the Stepper. If outside of [valueProgression] provided, value will
 *   be coerced to this range.
 * @param onValueChange Lambda in which value should be updated.
 * @param valueProgression Progression of values that Stepper value can take. Consists of
 *   rangeStart, rangeEnd and step. Range will be equally divided by step size.
 * @param modifier Modifiers for the Stepper layout.
 * @param decreaseIcon A slot for an icon which is placed on the decrease (bottom) button.
 * @param increaseIcon A slot for an icon which is placed on the increase (top) button.
 * @param enabled Whether the [Stepper] is enabled.
 * @param colors [StepperColors] that will be used to resolve the colors used for this [Stepper].
 *   Leave at `null` for [StepperDefaults.colors]; see file-header note 4.
 * @param content Content body for the Stepper.
 */
@Tunable
fun Stepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    valueProgression: IntProgression,
    decreaseIcon: @Tunable () -> Unit,
    increaseIcon: @Tunable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: StepperColors? = null,
    content: @Tunable ColumnScope.() -> Unit,
) {
    Stepper(
        value = value.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
        steps = valueProgression.stepperStepsNumber(),
        modifier = modifier,
        valueRange = valueProgression.first.toFloat()..valueProgression.last.toFloat(),
        decreaseIcon = decreaseIcon,
        increaseIcon = increaseIcon,
        colors = colors,
        enabled = enabled,
        content = content,
    )
}

/**
 * Defaults used by [Stepper].
 *
 * Upstream keeps [ButtonWeight], [ContentWeight], [ButtonWidth], [ButtonHeight] and
 * [VerticalSpacing] as internal top-level declarations and calls the resting shape
 * `StepperButtonShape`; the port contract forbids unnamed top-level helpers, so they live here as
 * `StepperDefaults` members with upstream's literals unchanged. [RepeatInitialDelayMillis] and
 * [RepeatIncrementalDelayMillis] are upstream's `repeatableClickable` defaults, which `Stepper`
 * never overrides.
 */
object StepperDefaults {
    /** Default size for increase and decrease icons. */
    val IconSize: Dp = 24.dp

    /** Weight proportion of the space taken by the increase/decrease buttons. */
    const val ButtonWeight = 0.35f

    /** Weight proportion of the space taken by the content. */
    const val ContentWeight = 0.3f

    /** Width of the increase/decrease icon button. */
    val ButtonWidth: Dp = 60.dp

    /** Height of the increase/decrease icon button. */
    val ButtonHeight: Dp = 48.dp

    /** Vertical spacing between the elements of the [Stepper]. */
    val VerticalSpacing: Dp = 8.dp

    /** Start shape for the increase/decrease buttons: upstream's `StepperButtonShape`. */
    val ButtonShape: Shape = ShapeTokens.CornerFull

    /** The initial delay before a held increase/decrease button starts repeating, in ms. */
    const val RepeatInitialDelayMillis = 500L

    /** The delay between each repeated click while a button is held, in ms. */
    const val RepeatIncrementalDelayMillis = 60L

    /**
     * Creates a [StepperColors] that represents the default colors used in a [Stepper].
     *
     * There are no Stepper design tokens upstream (its TODO b/369766493 says so), so these are the
     * colour-scheme roles upstream reads, not new tokens.
     */
    @Tunable
    fun colors(): StepperColors = MaterialTheme.colorScheme.defaultStepperColors

    /**
     * Creates a [StepperColors] that represents the default colors used in a [Stepper].
     *
     * @param contentColor the content color for this [Stepper].
     * @param buttonContainerColor the button container color for this [Stepper].
     * @param buttonIconColor the button icon color for this [Stepper].
     * @param disabledContentColor the disabled content color for this [Stepper].
     * @param disabledButtonContainerColor the disabled button container color for this [Stepper].
     * @param disabledButtonIconColor the disabled button icon color for this [Stepper].
     */
    @Tunable
    fun colors(
        contentColor: Color = Color.Unspecified,
        buttonContainerColor: Color = Color.Unspecified,
        buttonIconColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
        disabledButtonContainerColor: Color = Color.Unspecified,
        disabledButtonIconColor: Color = Color.Unspecified,
    ): StepperColors =
        MaterialTheme.colorScheme.defaultStepperColors.copy(
            contentColor = contentColor,
            buttonContainerColor = buttonContainerColor,
            buttonIconColor = buttonIconColor,
            disabledContentColor = disabledContentColor,
            disabledButtonContainerColor = disabledButtonContainerColor,
            disabledButtonIconColor = disabledButtonIconColor,
        )

    private val ColorScheme.defaultStepperColors: StepperColors
        get() = StepperColors(
            contentColor = ColorSchemeKeyTokens.OnSurface.resolve(this),
            buttonContainerColor = ColorSchemeKeyTokens.PrimaryContainer.resolve(this),
            buttonIconColor = ColorSchemeKeyTokens.Primary.resolve(this),
            disabledContentColor = ColorSchemeKeyTokens.OnSurface.resolve(this)
                .toDisabledColor(ColorScheme.DisabledContentAlpha),
            disabledButtonContainerColor = ColorSchemeKeyTokens.OnSurface.resolve(this)
                .toDisabledColor(ColorScheme.DisabledContainerAlpha),
            disabledButtonIconColor = ColorSchemeKeyTokens.OnSurface.resolve(this)
                .toDisabledColor(ColorScheme.DisabledContentAlpha),
        )
}

/**
 * Ported from androidx.wear.compose.material3.StepperColors.
 *
 * A plain class with hand-written [copy]/[equals]/[hashCode] rather than a data class, exactly
 * upstream and for upstream's reason: [copy] has to fold [Color.Unspecified] back onto the previous
 * value, which a generated `copy` would not do.
 *
 * @param contentColor the content color of this [Stepper].
 * @param buttonContainerColor the button background color of this [Stepper].
 * @param buttonIconColor icon tint [Color] for this [Stepper].
 * @param disabledContentColor the content color of this [Stepper] in disabled state.
 * @param disabledButtonContainerColor the button background color of this [Stepper] in disabled
 *   state.
 * @param disabledButtonIconColor icon tint [Color] for this [Stepper] in disabled state.
 */
class StepperColors(
    val contentColor: Color,
    val buttonContainerColor: Color,
    val buttonIconColor: Color,
    val disabledContentColor: Color,
    val disabledButtonContainerColor: Color,
    val disabledButtonIconColor: Color,
) {
    /**
     * Returns a copy of this [StepperColors] optionally overriding some of the values.
     *
     * @param contentColor The content color of this [Stepper].
     * @param buttonContainerColor The button background color of this [Stepper].
     * @param buttonIconColor Icon tint [Color] for this [Stepper].
     * @param disabledContentColor The content color of this [Stepper] in disabled state.
     * @param disabledButtonContainerColor The button background color of this [Stepper] in disabled
     *   state.
     * @param disabledButtonIconColor Icon tint [Color] for this [Stepper] in disabled state.
     */
    fun copy(
        contentColor: Color = this.contentColor,
        buttonContainerColor: Color = this.buttonContainerColor,
        buttonIconColor: Color = this.buttonIconColor,
        disabledContentColor: Color = this.disabledContentColor,
        disabledButtonContainerColor: Color = this.disabledButtonContainerColor,
        disabledButtonIconColor: Color = this.disabledButtonIconColor,
    ): StepperColors = StepperColors(
        contentColor = contentColor.takeOrElse { this.contentColor },
        buttonContainerColor = buttonContainerColor.takeOrElse { this.buttonContainerColor },
        buttonIconColor = buttonIconColor.takeOrElse { this.buttonIconColor },
        disabledContentColor = disabledContentColor.takeOrElse { this.disabledContentColor },
        disabledButtonContainerColor =
            disabledButtonContainerColor.takeOrElse { this.disabledButtonContainerColor },
        disabledButtonIconColor =
            disabledButtonIconColor.takeOrElse { this.disabledButtonIconColor },
    )

    internal fun contentColor(enabled: Boolean): Color =
        if (enabled) contentColor else disabledContentColor

    internal fun buttonContainerColor(enabled: Boolean): Color =
        if (enabled) buttonContainerColor else disabledButtonContainerColor

    internal fun buttonIconColor(enabled: Boolean): Color =
        if (enabled) buttonIconColor else disabledButtonIconColor

    /**
     * The container as [ContainerDrawable] wants it. Upstream resolves one colour per frame from
     * `interactionSource` (and changes only the *shape* on press, `material3/Stepper.kt:447-450`);
     * here the disabled variant rides along and the drawable swaps on the view's enabled state, which
     * is the only transition the stepper animates. [buttonContainerColor] is still resolved for the
     * caller so the resting colour is right even if a host view never reports `state_enabled`. No
     * pressed variant is passed, so a press stays colourless here exactly as upstream's does.
     */
    internal fun containerSpec(shape: Shape, enabled: Boolean): ContainerSpec = ContainerSpec(
        shape = shape,
        containerColor = buttonContainerColor(enabled),
        disabledContainerColor = disabledButtonContainerColor,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is StepperColors) return false

        if (contentColor != other.contentColor) return false
        if (buttonContainerColor != other.buttonContainerColor) return false
        if (buttonIconColor != other.buttonIconColor) return false
        if (disabledContentColor != other.disabledContentColor) return false
        if (disabledButtonContainerColor != other.disabledButtonContainerColor) return false
        if (disabledButtonIconColor != other.disabledButtonIconColor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = contentColor.hashCode()
        result = 31 * result + buttonContainerColor.hashCode()
        result = 31 * result + buttonIconColor.hashCode()
        result = 31 * result + disabledContentColor.hashCode()
        result = 31 * result + disabledButtonContainerColor.hashCode()
        result = 31 * result + disabledButtonIconColor.hashCode()
        return result
    }
}

@Tunable
private fun StepperImpl(
    value: Float,
    onValueChange: (Float) -> Unit,
    steps: Int,
    decreaseIcon: @Tunable () -> Unit,
    increaseIcon: @Tunable () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    valueRange: ClosedFloatingPointRange<Float>,
    colors: StepperColors,
    content: @Tunable ColumnScope.() -> Unit,
) {
    require(steps >= 0) { "Number of steps should be non-negative." }
    val currentStep = remember(value, valueRange, steps) {
        stepperSnapValueToStep(value, valueRange, steps)
    }

    val verticalPadding = stepperVerticalContentPadding(currentContext)

    val updateValue: (Int, View) -> Unit = { stepDiff, view ->
        val newValue =
            stepperCalculateCurrentStepValue(currentStep + stepDiff, steps, valueRange)
        if (newValue != value) {
            onValueChange(newValue)
            view.stepperHapticFeedback(
                atEdge = !(newValue > valueRange.start && newValue < valueRange.endInclusive),
            )
        }
    }

    val increaseButtonEnabled = enabled && (currentStep < steps + 1)
    val decreaseButtonEnabled = enabled && (currentStep > 0)

    Column(
        modifier = modifier.matchParentSize(),
    ) {
        StepperButton(
            onClick = { view -> updateValue(1, view) },
            colors = colors,
            enabled = increaseButtonEnabled,
            contentAlignment = Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            paddingValues = PaddingValues(top = verticalPadding),
            content = increaseIcon,
        )

        // Upstream: Box(Modifier.fillMaxWidth().weight(ContentWeight), contentAlignment = Center).
        // `height(0.dp)` alongside the weight is what makes a LinearLayout split the space
        // proportionally instead of topping each child up from its measured height.
        Column(
            modifier = Modifier
                .matchParentWidth()
                .weight(StepperDefaults.ContentWeight)
                .height(0.dp)
                .margin(
                    PaddingValues(
                        top = StepperDefaults.VerticalSpacing,
                        bottom = StepperDefaults.VerticalSpacing,
                    )
                )
                .stepperChildGravity(Gravity.CENTER),
        ) {
            // Upstream folds this provider into `provideScopeContent` at the public entry point.
            provideContentColor(colors.contentColor(enabled)) { content() }
        }

        StepperButton(
            onClick = { view -> updateValue(-1, view) },
            colors = colors,
            enabled = decreaseButtonEnabled,
            contentAlignment = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            paddingValues = PaddingValues(bottom = verticalPadding),
            content = decreaseIcon,
        )
    }
}

@Tunable
private fun ColumnScope.StepperButton(
    onClick: (View) -> Unit,
    contentAlignment: Int,
    paddingValues: PaddingValues,
    enabled: Boolean,
    colors: StepperColors,
    shape: Shape = StepperDefaults.ButtonShape,
    content: @Tunable () -> Unit,
) {
    Column(
        modifier = Modifier
            .gravity(Gravity.CENTER_HORIZONTAL)
            .weight(StepperDefaults.ButtonWeight)
            .height(0.dp)
            .padding(paddingValues)
            .stepperChildGravity(contentAlignment),
    ) {
        Column(
            modifier = Modifier
                .size(DpSize(StepperDefaults.ButtonWidth, StepperDefaults.ButtonHeight))
                .container(colors.containerSpec(shape, enabled))
                .stepperRepeatableClickable(enabled = enabled, onClick = onClick)
                .stepperChildGravity(Gravity.CENTER),
        ) {
            provideContentColor(colors.buttonIconColor(enabled)) { content() }
        }
    }
}

/**
 * `verticalContentPadding()` from Stepper.kt: 5.2% of the screen *height*. Distinct from
 * `WearScreen.verticalContentPaddingDp`, which is the `PaddingDefaults` helper over 10% of the
 * height and is used by other components.
 *
 * Upstream reads `LocalConfiguration`, which recomposes on a configuration change; here the value
 * is taken from the node's [Context] and a retune is what re-reads it.
 */
private fun stepperVerticalContentPadding(context: Context): Dp =
    (context.resources.configuration.screenHeightDp * 5.2f / 100f).dp

/**
 * `RangeDefaults.snapValueToStep`, inlined because `RangeDefaults` is shared with the Slider port.
 */
private fun stepperSnapValueToStep(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Int =
    ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start) * (steps + 1))
        .roundToInt()
        .coerceIn(0, steps + 1)

/** `RangeDefaults.calculateCurrentStepValue`, same reason. */
private fun stepperCalculateCurrentStepValue(
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

/** `IntProgression.stepsNumber()` from RangeSemantics.kt, same reason. */
private fun IntProgression.stepperStepsNumber(): Int = (last - first) / step - 1

/**
 * `LocalHapticFeedback` has no Hibari equivalent, so the feedback is performed on the view that was
 * clicked. [atEdge] picks upstream's `GestureEnd` branch, otherwise `SegmentFrequentTick`
 * (`material3/Stepper.kt:366-369`). Each constant is gated at the API level it was added at — 30 for
 * `GESTURE_END`, 34 for `SEGMENT_FREQUENT_TICK` — and silent below that, as `Slider.kt`'s
 * `sliderRangeHaptic` is.
 */
private fun View.stepperHapticFeedback(atEdge: Boolean) {
    if (atEdge) {
        if (Build.VERSION.SDK_INT >= 30) {
            performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
        }
    } else if (Build.VERSION.SDK_INT >= 34) {
        performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
    }
}

/**
 * The Views equivalent of Compose's `Box(contentAlignment = ...)`. A `FrameLayout` has no default
 * child gravity, which is why the frames here are vertical `LinearLayout`s and why the content slot
 * is a `ColumnScope` (see file-header note 5).
 */
private fun Modifier.stepperChildGravity(gravity: Int): Modifier =
    this.thenViewAttribute<LinearLayout, Int>(uniqueKey, gravity) { setGravity(it) }

/**
 * Ported from materialcore's `Modifier.repeatableClickable`, which pairs a `clickable` with a
 * `pointerInput` gesture: hold past [initialDelayMillis] and the click repeats every
 * [incrementalDelayMillis] until the finger lifts, and the release click is then swallowed.
 *
 * `onClick` gets the view so the caller can feed haptics back through it. Upstream's
 * `interactionSource`, `indication`, `onClickLabel` and `role` are the ripple and semantics carriers
 * this module drops everywhere, and its `onRepeatableClick` parameter defaults to `onClick`, which
 * is all [Stepper] ever passes (`materialcore/RepeatableClickable.kt:70-78`).
 *
 * Unlike `SliderButtonView`, a drag away from the button does *not* end the repeat here — only a lift
 * or a cancel does, matching the one upstream line that is in the reference tree,
 * `waitForUpOrCancellation()` followed by `repeatingJob.cancel()`
 * (`materialcore/RepeatableClickable.kt:116-117`). What `waitForUpOrCancellation` itself counts as a
 * cancellation is compose-foundation, which is not vendored, so the slider's tap-slop teardown is a
 * reading of it rather than a port; this side takes the literal one.
 */
private fun Modifier.stepperRepeatableClickable(
    enabled: Boolean,
    initialDelayMillis: Long = StepperDefaults.RepeatInitialDelayMillis,
    incrementalDelayMillis: Long = StepperDefaults.RepeatIncrementalDelayMillis,
    onClick: (View) -> Unit,
): Modifier = this.thenViewAttribute<View, StepperRepeatSpec>(
    uniqueKey,
    StepperRepeatSpec(enabled, initialDelayMillis, incrementalDelayMillis, onClick),
) { spec ->
    // One gesture per view, kept alive across retunes and re-armed in place: a fresh Runnable per
    // tune would leave the previous repeat loop posted on the handler with nothing left to cancel it.
    // The single-slot `tag` is what makes that re-arm possible, and its hazard: if anything else ever
    // claims `tag` on this view the cast fails, a second gesture is installed over the first, and the
    // first one's `repeatTick` stays posted. Uncontested today — this is the module's only plain-`tag`
    // write, and `hibari-ui/.../ViewHierarchyPrinter.kt:147` only reads it for debug output.
    val gesture = (tag as? StepperRepeatGesture) ?: StepperRepeatGesture(this).also {
        tag = it
        setOnTouchListener(it)
    }
    gesture.spec = spec
    isEnabled = enabled
    isClickable = enabled
    // The framework's own long press must not compete with the repeat loop; the click still fires on
    // up, which is upstream's `clickable` half of the pair.
    isLongClickable = false
    setOnClickListener(if (enabled) gesture else null)
}

private data class StepperRepeatSpec(
    val enabled: Boolean,
    val initialDelayMillis: Long,
    val incrementalDelayMillis: Long,
    val onClick: (View) -> Unit,
)

private class StepperRepeatGesture(private val host: View) :
    View.OnTouchListener,
    View.OnClickListener {

    var spec: StepperRepeatSpec? = null

    /** Upstream's `ignoreOnClick`: set once a repeat has fired, cleared when the click is delivered. */
    private var repeatHappened = false

    private val repeatTick = object : Runnable {
        override fun run() {
            val current = spec ?: return
            repeatHappened = true
            if (current.enabled) {
                current.onClick(host)
                host.postDelayed(this, current.incrementalDelayMillis)
            }
        }
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                repeatHappened = false
                spec?.takeIf { it.enabled }?.let { view.postDelayed(repeatTick, it.initialDelayMillis) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.removeCallbacks(repeatTick)
        }
        // False leaves the event to the view, so it keeps its pressed state, its ripple-free click
        // on up and its accessibility handling.
        return false
    }

    override fun onClick(view: View) {
        if (!repeatHappened) spec?.onClick?.invoke(view)
        repeatHappened = false
    }
}
