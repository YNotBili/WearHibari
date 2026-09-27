package com.huanli233.hibari.wear

import com.huanli233.hibari.animation.CubicBezierEasing
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.currentTuner
import com.huanli233.hibari.runtime.getValue
import com.huanli233.hibari.runtime.mutableStateOf
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.runtime.setValue
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.unit.toPx
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.lazy.ListTransformParams
import com.huanli233.hibari.wear.view.PickerContentProps
import com.huanli233.hibari.wear.view.PickerProps
import com.huanli233.hibari.wear.view.WearPickerView

/**
 * A scrollable list of items to pick from. By default items are repeated "infinitely" in both
 * directions, unless [PickerState.shouldRepeatOptions] is false.
 *
 * Ported from androidx.wear.compose.material3.Picker. Its `ScalingLazyColumn` becomes
 * [WearPickerView], which keeps upstream's scaling curve, snap fling, gradient fade and rotary snap;
 * see that class for the list of behaviours that could not survive the move to Views and why.
 *
 * Parameter deviations: the first is forced by the compiler plugin, the rest are places where the
 * Views port is simply behind.
 *  - `gradientColor` is `Color?` rather than defaulting to `MaterialTheme.colorScheme.background`,
 *    and `rotaryScrollableBehavior` defaults to `null`: a `@Tunable` function's default expressions
 *    are hoisted into a non-`@Tunable` method, so they may not call a theme getter. Passing `null`
 *    selects exactly what upstream's default would have produced; passing [Color.Unspecified]
 *    explicitly still selects the masking branch, as upstream documents.
 *  - `LocalReduceMotion` and `LocalTouchExplorationStateProvider` are read by the view — the setting
 *    when the attributes land and again on attach, the exploration state when a gesture happens —
 *    rather than recomposing the picker when they change. Upstream's `ScalingLazyColumn` swaps in
 *    `ReduceMotionScalingParams` under reduce motion, and so does [WearPickerView]; that is why
 *    [pickerScalingParams] is not handed to the view as the final word.
 *  - `animateScrollToOption` reaches the right item but not along upstream's curve: upstream's timing
 *    comes from Compose's `LazyListState.animateScrollToItem`, which is outside the reference tree.
 *    See [WearPickerView.animateScrollToOption].
 *  - `state.toRotarySnapLayoutInfoProvider()` is not a separate object: the view is the layout-info
 *    provider, and reports the same four numbers `PickerRotarySnapLayoutInfoProvider` did.
 *
 * @param state The state of the component.
 * @param contentDescription A block which computes text used by accessibility services to describe
 *   what the selected option represents. Called on every tune while the picker is not scrolling,
 *   which is upstream's `if (!state.isScrollInProgress && latestContentDescription != null)`.
 * @param modifier [Modifier] to be applied to the Picker.
 * @param readOnly Determines whether the [Picker] should display other available options for this
 *   field, inviting the user to scroll to change the value. When true, only the currently selected
 *   option (and optionally a label) is shown.
 * @param readOnlyLabel A slot for a label displayed above the selected option while read-only. It is
 *   overlaid on the selected option, so give the label `Gravity.TOP_CENTER` via `Modifier.gravity`.
 * @param onSelected Action triggered when the Picker is selected by clicking. Used by accessibility
 *   semantics, which facilitates implementation of multi-picker screens.
 * @param verticalSpacing The amount of vertical spacing in [Dp] between items. Can be negative.
 * @param gradientRatio The size relative to the Picker height that the top and bottom gradients
 *   take. Default 0.33, so a third at each end. Must be between 0.0 and 0.5; 0.0 disables them.
 * @param gradientColor Should be the colour outside of the Picker, so there is continuity. With a
 *   custom background it must be [Color.Unspecified], which switches to gradient masking.
 * @param userScrollEnabled Whether the picker may be scrolled by touch or rotary at all. Different
 *   from [readOnly], which changes what is displayed.
 * @param rotaryScrollableBehavior Parameter for changing rotary behavior. `null` falls back to
 *   [PickerDefaults.rotarySnapBehavior], which is what upstream's default produces. Supply a
 *   [PickerRotaryBehavior] to change the sensitivity, the snap offset or whether the snap buzzes; the
 *   picker scrolls itself whatever that object's [PickerRotaryBehavior.state] says, so build it from
 *   this picker's state. Gap: upstream's `rotaryScrollableBehavior` is `RotaryScrollableBehavior?` and
 *   documents that "passing null turns off the rotary handling", but `null` here means "the default"
 *   because that is what a hoisted default parameter has to mean, so no value turns rotary off.
 * @param option A block describing the content. Inside it, [PickerScope.selectedOptionIndex] says
 *   which option is selected.
 */
@Tunable
fun Picker(
    state: PickerState,
    contentDescription: (() -> String)?,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    readOnlyLabel: (@Tunable BoxScope.() -> Unit)? = null,
    onSelected: () -> Unit = {},
    verticalSpacing: Dp = 0.dp,
    gradientRatio: Float = PickerDefaults.GradientRatio,
    gradientColor: Color? = null,
    userScrollEnabled: Boolean = true,
    rotaryScrollableBehavior: PickerRotaryBehavior? = null,
    option: @Tunable PickerScope.(index: Int) -> Unit,
) {
    require(gradientRatio in 0f..0.5f) { "gradientRatio should be between 0.0 and 0.5" }
    val context = currentContext
    val parentTunation = currentTuner.tunation
    val pickerScope = remember(state) { PickerScopeImpl(state) }
    val rotary = rotaryScrollableBehavior ?: remember(state) { PickerDefaults.rotarySnapBehavior(state) }
    val resolvedGradientColor = gradientColor ?: MaterialTheme.colorScheme.background
    val valueDescription =
        if (!state.isScrollInProgress) contentDescription?.invoke() else null

    Node(
        modifier = modifier
            .viewClass(WearPickerView::class.java)
            .pickerProps(
                PickerProps(
                    state = state,
                    verticalSpacingPx = verticalSpacing.toPx(context),
                    gradientRatio = gradientRatio,
                    gradientColor = resolvedGradientColor,
                    transform = pickerScalingParams(),
                    readOnly = readOnly,
                    userScrollEnabled = userScrollEnabled,
                    rotary = rotary,
                    valueDescription = valueDescription,
                )
            )
            .pickerContent(
                PickerContentProps(
                    parentTunation = parentTunation,
                    scope = pickerScope,
                    option = option,
                    readOnlyLabel = readOnlyLabel,
                    onSelected = onSelected,
                )
            )
    )
}

/**
 * Creates a [PickerState] that is remembered across retunes.
 *
 * Upstream wraps this in `rememberSaveable(..., saver = PickerState.Saver)`. There is no
 * `rememberSaveable`, no `Saver` type and no parcelable restoration layer in Hibari's retune path —
 * the same gap [com.huanli233.hibari.wear.lazy.ScalingLazyListState] documents — so the state is
 * plain `remember` and does not survive an activity recreation; [PickerState] therefore carries no
 * `Saver` companion either, rather than faking one.
 *
 * @param initialNumberOfOptions the number of options.
 * @param initiallySelectedIndex the index of the option to show in the center at the start, zero-based.
 * @param shouldRepeatOptions if true (the default), the options will be repeated.
 */
@Tunable
fun rememberPickerState(
    initialNumberOfOptions: Int,
    initiallySelectedIndex: Int = 0,
    shouldRepeatOptions: Boolean = true,
): PickerState = remember(initialNumberOfOptions, initiallySelectedIndex, shouldRepeatOptions) {
    PickerState(initialNumberOfOptions, initiallySelectedIndex, shouldRepeatOptions)
}

/**
 * A state object that can be hoisted to observe item selection. In most cases it is created via
 * [rememberPickerState].
 *
 * Ported from androidx.wear.compose.material3.PickerState, including the item/option mapping: the
 * picker holds `LARGE_NUMBER_OF_ITEMS` slots when repeating, so many items map to one option and
 * [optionsOffset] carries that mapping across a change of [numberOfOptions].
 *
 * The `ScrollableState` interface is not implemented — Hibari has no `ScrollScope`/`MutatePriority`
 * to satisfy — so `scroll(scrollPriority, block)`, `dispatchRawDelta(delta)` and
 * `Modifier.scrollableForTouchExploration`'s use of them are absent. The three read-only properties
 * they are built on ([isScrollInProgress], [canScrollForward], [canScrollBackward]) are ported.
 * `scrollToOption`/`animateScrollToOption` are not `suspend`: they only need the main thread, which
 * a `@Tunable` body already runs on, and `launch { state.scrollToOption(i) }` still compiles against
 * them.
 *
 * @param initialNumberOfOptions the number of options.
 * @param initiallySelectedIndex the index of the option to show in the center at the start, zero-based.
 * @param shouldRepeatOptions if true (the default), the options will be repeated.
 */
class PickerState(
    initialNumberOfOptions: Int,
    initiallySelectedIndex: Int = 0,
    val shouldRepeatOptions: Boolean = true,
) {
    init {
        verifyNumberOfOptions(initialNumberOfOptions)
    }

    private var _numberOfOptions by mutableStateOf(initialNumberOfOptions)

    /** Represents how many different choices are presented by this [Picker]. */
    var numberOfOptions: Int
        get() = _numberOfOptions
        set(newNumberOfOptions) {
            verifyNumberOfOptions(newNumberOfOptions)
            // The mapping between the currently selected item and the currently selected option has
            // to survive the change.
            optionsOffset = positiveModulo(
                selectedOptionIndex.coerceAtMost(newNumberOfOptions - 1) - centerItemIndex,
                newNumberOfOptions,
            )
            _numberOfOptions = newNumberOfOptions
        }

    /** Index of the selected option (i.e. at the center). Reads a state, so option content that uses
     * it is retuned when the centre item changes — upstream's `derivedStateOf` recomposition. */
    val selectedOptionIndex: Int
        get() = (centerItemIndex + optionsOffset) % numberOfOptions

    /**
     * Instantly scroll to an option. With no picker attached yet the position simply lands in this
     * state, which the picker adopts as soon as it is composed — upstream's `scrollToItem` on a
     * `ScalingLazyListState` behaves the same way before the column is visible.
     */
    fun scrollToOption(index: Int) {
        val view = picker
        if (view != null) {
            view.scrollToOption(index)
        } else {
            centerItemIndex = closestTargetItemIndex(index)
            centerItemScrollOffset = 0f
        }
    }

    /**
     * Animate (smooth scroll) to the given option at [index].
     *
     * A smooth scroll always happens to the closest item if [shouldRepeatOptions] is true. For
     * example, picker values are `0 1 2 3 0 1 2 [3] 0 1 2 3` and the target is `0`: one step forward,
     * three back, so it scrolls forward. When both distances are equal it scrolls backwards —
     * upstream's `stepsPrev <= stepsNext` branch.
     */
    fun animateScrollToOption(index: Int) {
        val view = picker ?: return scrollToOption(index)
        view.animateScrollToOption(index)
    }

    /** Whether a scroll gesture or animation is currently driving the picker. */
    val isScrollInProgress: Boolean get() = _isScrollInProgress

    /** The ported half of `ScrollableState`: whether a further forward scroll would move anything. */
    val canScrollForward: Boolean get() = picker?.canScrollForward() == true

    /** The ported half of `ScrollableState`: whether a further backward scroll would move anything. */
    val canScrollBackward: Boolean get() = picker?.canScrollBackward() == true

    internal fun numberOfItems(): Int =
        if (!shouldRepeatOptions) numberOfOptions else LARGE_NUMBER_OF_ITEMS

    /**
     * The difference between the option to select for the current [numberOfOptions] and the selection
     * made with the initial one. With repetition there are many more items than options, so many items
     * map to one option. Upstream's comment on the equivalent field gives that mapping as
     * `itemIndex - optionsOffset ≡ optionIndex (mod numberOfOptions)`, but no code anywhere subtracts
     * it: upstream's own option slot and its `selectedOptionIndex` both add, as do [selectedOptionIndex]
     * below and `WearPickerView.optionIndexOf`, and the two assignments to it are written to suit. The
     * addition is what is reproduced here; upstream's comment is the thing that is wrong.
     */
    internal var optionsOffset by mutableStateOf(0)

    /** The item straddling the focal line. Observable: [selectedOptionIndex] is built from it. */
    internal var centerItemIndex by mutableStateOf(
        initialCenterItemIndex(initialNumberOfOptions, initiallySelectedIndex, shouldRepeatOptions)
    )

    /**
     * Signed distance in pixels from the focal line to [centerItemIndex]'s centre, positive below it.
     * Upstream reports `centerItemScrollOffset: Int`; Compose's live scroll position is a float, and
     * the float is what the rotary snap and shim maths are written against, so it is kept here. Not
     * observable: it changes every frame, and nothing in a picker's content should be retuned per
     * frame the way Compose only redraws.
     */
    internal var centerItemScrollOffset: Float = 0f

    internal var picker: WearPickerView? = null

    private var _isScrollInProgress by mutableStateOf(false)

    internal fun setScrollInProgress(inProgress: Boolean) {
        _isScrollInProgress = inProgress
    }

    /** Function which calculates the real position of an option. */
    internal fun closestTargetItemIndex(option: Int): Int =
        if (!shouldRepeatOptions) {
            option
        } else {
            // The distance to the target option in front or behind; the smaller one is taken.
            val stepsPrev = positiveModulo(selectedOptionIndex - option, numberOfOptions)
            val stepsNext = positiveModulo(option - selectedOptionIndex, numberOfOptions)
            centerItemIndex + if (stepsPrev <= stepsNext) -stepsPrev else stepsNext
        }

    private fun verifyNumberOfOptions(numberOfOptions: Int) {
        require(numberOfOptions > 0) { "The picker should have at least one item." }
        require(numberOfOptions < LARGE_NUMBER_OF_ITEMS / 3) {
            // Set an upper limit to ensure there are at least 3 repeats of all the options
            "The picker should have less than ${LARGE_NUMBER_OF_ITEMS / 3} items"
        }
    }

    private companion object {
        /**
         * Upstream's `run { val repeats = numberOfItems() / numberOfOptions; … }`: start in the middle
         * of the repetitions so there is always another full cycle in each direction.
         */
        fun initialCenterItemIndex(
            numberOfOptions: Int,
            initiallySelectedIndex: Int,
            shouldRepeat: Boolean,
        ): Int {
            val items = if (!shouldRepeat) numberOfOptions else LARGE_NUMBER_OF_ITEMS
            val repeats = items / numberOfOptions
            val centerOffset = numberOfOptions * (repeats / 2)
            return centerOffset + initiallySelectedIndex
        }
    }
}

/** Contains the default values used by [Picker]. */
object PickerDefaults {
    /**
     * Default Picker gradient ratio — the proportion of the Picker height allocated to each of the
     * top and bottom gradients.
     */
    const val GradientRatio: Float = 0.33f

    /**
     * Default rotary behavior for [Picker]: scroll behavior with snap, so the selected item lands in
     * the center. Upstream's `RotaryScrollableDefaults.snapBehavior(state, state.toRotarySnapLayoutInfoProvider())`,
     * which fixes `snapOffset = 0.dp`, `hapticFeedbackEnabled = true` and
     * `snapSensitivity = LowSnapSensitivity` (`0.4f`).
     *
     * A plain function rather than a `@Tunable` one: it reads no theme or tunation local, so a call
     * site outside a tune is legal, which is what `Picker`'s hoisted default-parameter expressions
     * require of everything here.
     */
    fun rotarySnapBehavior(state: PickerState): PickerRotaryBehavior =
        PickerRotaryBehavior(state = state)
}

/**
 * Rotary handling for [Picker]: upstream's `RotaryScrollableBehavior`, reduced to the settings that
 * survive the move — the behaviour object's coroutine plumbing lives in [WearPickerView].
 *
 * The three derived numbers are `RotarySnapSensitivityValues(snapSensitivity)` in
 * RotaryScrollable.kt, interpolating between the default set `(1f, 1.5f, 3f)` and the pager set
 * `(5f, 7.5f, 5f)`; `0.1f` floors on the dividers avoid the divide-by-zero upstream guards too.
 *
 * @param state Which picker this was built for. Nothing reads it: [WearPickerView] is its own
 *   `RotarySnapLayoutInfoProvider` for the picker it belongs to, so a behaviour carrying another
 *   picker's state still scrolls the one it was handed to. Upstream's equivalent does route the
 *   scroll — `RotaryScrollableDefaults.snapBehavior(state, state.toRotarySnapLayoutInfoProvider())` —
 *   so this is the one place where the port is more permissive than the original and wrong to be.
 * @param snapSensitivity `0.0..1.0`, upstream's `RotaryScrollableDefaults.LowSnapSensitivity` (`0.4f`)
 *   to `HighSnapSensitivity` (`0.8f`).
 * @param snapOffset Distance from the center of the scrollable to the center of the snapped item.
 * @param hapticsEnabled Whether a snap still asks for haptic feedback; the constant used is
 *   [android.view.HapticFeedbackConstants.CLOCK_TICK], not upstream's per-device table.
 */
@ConsistentCopyVisibility
data class PickerRotaryBehavior internal constructor(
    val state: PickerState,
    val snapSensitivity: Float = LowSnapSensitivity,
    val snapOffsetDp: Dp = 0.dp,
    val hapticsEnabled: Boolean = true,
) {
    val minThresholdDivider: Float = lerpDividers(MinDividerDefault, MinDividerHigh)
        .coerceAtLeast(0.1f)

    val maxThresholdDivider: Float = lerpDividers(MaxDividerDefault, MaxDividerHigh)
        .coerceAtLeast(0.1f)

    val resistanceFactor: Float = ResistanceDefault + (ResistanceHigh - ResistanceDefault) * sensitivityFraction

    private val sensitivityFraction: Float
        get() = (snapSensitivity - LowSnapSensitivity) /
            (HighSnapSensitivity - LowSnapSensitivity)

    private fun lerpDividers(from: Float, to: Float): Float = from + (to - from) * sensitivityFraction

    internal companion object {
        const val LowSnapSensitivity: Float = 0.4f
        const val HighSnapSensitivity: Float = 0.8f
        const val MinDividerDefault = 1f
        const val MinDividerHigh = 5f
        const val MaxDividerDefault = 1.5f
        const val MaxDividerHigh = 7.5f
        const val ResistanceDefault = 3f
        const val ResistanceHigh = 5f
    }
}

/** Receiver scope which is used by [Picker]. */
interface PickerScope {
    /** Index of the option selected (i.e., at the center). */
    val selectedOptionIndex: Int
}

private class PickerScopeImpl(private val pickerState: PickerState) : PickerScope {
    override val selectedOptionIndex: Int get() = pickerState.selectedOptionIndex
}

private fun positiveModulo(n: Int, mod: Int) = ((n % mod) + mod) % mod

/**
 * `pickerScalingParams()`, with the numbers `Picker.kt` passes to
 * `ScalingLazyColumnDefaults.scalingParams`: an item shrinks to 45% by the screen edge without any
 * alpha change, the transition line is fixed at 45% of the viewport (both element-height bounds are
 * `0f`, so every row shares one line), and the band is measured from a fifth of the viewport in —
 * upstream's `viewportVerticalOffsetResolver = { (it.maxHeight / 5f).toInt() }`, expressed as the
 * fraction [ListTransformParams] takes.
 *
 * `reduceMotion` is left at its default: upstream resolves it inside `ScalingLazyColumn`, so it is the
 * view's to fill in once per attribute change.
 */
private fun pickerScalingParams(): ListTransformParams = ListTransformParams(
    edgeScale = 0.45f,
    edgeAlpha = 1.0f,
    minElementHeight = 0.0f,
    maxElementHeight = 0.0f,
    minTransitionArea = 0.45f,
    maxTransitionArea = 0.45f,
    scaleInterpolator = CubicBezierEasing(0.25f, 0.00f, 0.75f, 1.00f),
    viewportVerticalOffsetFraction = 1f / 5f,
)

private fun Modifier.pickerProps(props: PickerProps): Modifier =
    this.thenViewAttribute<WearPickerView, PickerProps>(uniqueKey, props) {
        pickerProps = it
    }

private fun Modifier.pickerContent(content: PickerContentProps): Modifier =
    this.thenViewAttribute<WearPickerView, PickerContentProps>(uniqueKey, content) {
        pickerContent = it
    }

private const val LARGE_NUMBER_OF_ITEMS = 100_000_000
