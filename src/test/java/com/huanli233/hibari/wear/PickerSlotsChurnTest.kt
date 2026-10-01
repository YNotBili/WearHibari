package com.huanli233.hibari.wear

import com.huanli233.hibari.animation.CubicBezierEasing
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.wear.lazy.ListTransformParams
import com.huanli233.hibari.wear.view.PickerGroupProps
import com.huanli233.hibari.wear.view.PickerProps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The rule the picker's row rebinds now hang on, and the values that rule is allowed to ignore.
 *
 * `WearPickerView.pickerContent` re-tunes every materialised row for any value it has not seen
 * before, and a row tune is a whole subtree (`hibari-recyclerview`'s `HibariViewHolder.kt:16-28`,
 * `bind` calls `tuneNow` unconditionally). Whether that happens on a retune is decided one level
 * earlier: `ViewAttribute` compares `key` + `value` + `reuseSupported` and not the applier
 * (`hibari-ui/src/main/java/com/huanli233/hibari/ui/Attribute.kt:68-79`), and the diff pairs
 * attributes by `(key, occurrence-within-key)` and hands the view only the ones whose value differs
 * (`hibari-runtime/src/main/java/com/huanli233/hibari/runtime/HibariDiffCallback.kt:179-191`). So the
 * question this file answers is: **what compares equal across a retune that changed nothing?**
 *
 * Two answers, and they are opposite. The numbers are value types and do compare equal, which is why
 * the holder is allowed to leave the rows alone. The slot blocks are function objects and compare by
 * identity only, which is why "left alone" has to be decided from a reference that does not move —
 * the holder in `view/WearPickerViews.kt`, whose `generation` the `Picker` call site publishes as the
 * attribute's value.
 *
 * Why the generation rule is transcribed below rather than called: `PickerSlots`' first parameter is a
 * `Tunation`, and a `Tunation` takes an `android.view.ViewGroup`
 * (`hibari-runtime/src/main/java/com/huanli233/hibari/runtime/Tunation.kt:6-10`). Unit tests in this
 * module run against the stub `android.jar`, `hibari-wear/build.gradle.kts` sets no
 * `unitTests.returnDefaultValues`, and `junit` is the only test dependency, so no `Tunation` — and so
 * no `PickerSlots` — exists on this side. `SlotsRule` below is `PickerSlots.update`'s arithmetic
 * copied line for line minus the `@Tunable` on the parameter types: it pins the *decision*, and if
 * that one line in the view changes, this file has to change with it. What it does not pin is the
 * wiring — a row actually being skipped is only watchable on device.
 */
class PickerSlotsChurnTest {

    /**
     * `PickerSlots.update`, transcribed. Its `parentTunation` and `scope` parameters are left out
     * because `Picker` holds those in the `remember` key instead: a change to either builds a new
     * holder, which is a new attribute value without touching the generation.
     */
    private class SlotsRule {
        var generation = 0
            private set

        var option: ((Int) -> Unit)? = null
            private set

        var readOnlyLabel: (() -> Unit)? = null
            private set

        var onSelected: (() -> Unit)? = null
            private set

        fun update(
            option: (Int) -> Unit,
            readOnlyLabel: (() -> Unit)?,
            onSelected: () -> Unit,
        ) {
            if (this.option !== option || this.readOnlyLabel !== readOnlyLabel) generation++
            this.option = option
            this.readOnlyLabel = readOnlyLabel
            this.onSelected = onSelected
        }
    }

    /** A block that captures, so every evaluation of it is a new object on the JVM. */
    private fun capturing(captured: Int): (Int) -> Unit {
        var total = 0
        return { index -> total += index + captured }
    }

    /** What a row's content ends up writing, so a re-read can be told from a missed one. */
    private var drawn = ""

    private fun draw(text: String) {
        drawn = text
    }

    @Test
    fun theFirstFoldIsAChangeAndEveryFoldAfterItIsNot() {
        val rule = SlotsRule()
        val option = capturing(7)
        val label = { }
        val callback = { }
        // Nothing was stored yet, so the first fold of the very same references moves the generation:
        // the attribute reaches the view, `contentProps` was null before it, and the rows bind.
        rule.update(option, label, callback)
        assertEquals(1, rule.generation)
        // And a host retune that re-delivers those references moves nothing — which is the whole
        // point, since `PickerSlots` declares no `equals`
        // (`view/WearPickerViews.kt`), so the props' remaining field is this number.
        rule.update(option, label, callback)
        rule.update(option, label, callback)
        assertEquals(1, rule.generation)
    }

    @Test
    fun aRecapturedBlockMovesTheGenerationWhateverItRecaptured() {
        val rule = SlotsRule()
        val callback = { }
        rule.update(capturing(7), null, callback)
        val afterFirst = rule.generation
        // Two evaluations of the same block over the same value are two objects, so this moves the
        // generation even though nothing about the row's output changed. That is the safe direction:
        // the rows are re-tuned, and they re-tune to what the new block would have drawn anyway.
        rule.update(capturing(7), null, callback)
        assertEquals(afterFirst + 1, rule.generation)
        // A genuine recapture — a different value — is indistinguishable from the line above, which
        // is exactly why the holder cannot be more clever than this without comparing capture fields.
        rule.update(capturing(8), null, callback)
        assertEquals(afterFirst + 2, rule.generation)
    }

    @Test
    fun aMutationBehindAnUnmovedReferenceIsNotAMove() {
        val rule = SlotsRule()
        val callback = { }
        // The known boundary, stated rather than papered over: a fold can only look at the reference
        // it was handed. This block reads a value through the box a `var` gets, and that box stays one
        // object however many times the value inside it is replaced.
        var text = "3"
        val block: (Int) -> Unit = { _ -> draw(text) }
        rule.update(block, null, callback)
        val afterFirst = rule.generation
        block(0)
        assertEquals("3", drawn)
        text = "4"
        rule.update(block, null, callback)
        // No move, so nothing re-tunes the rows — while invoking the very same block does read the
        // new value. That is the gap: content that changes behind a stable reference reaches a view
        // only when something else re-tunes it, which is why such content has to be subscribed to
        // (`Modifier.bindState`, `hibari-runtime/src/main/java/com/huanli233/hibari/runtime/
        // StateBinding.kt:26-40`) rather than captured.
        assertEquals(afterFirst, rule.generation)
        assertSame(block, rule.option)
        block(0)
        assertEquals("4", drawn)
    }

    @Test
    fun aSwappedCallbackTravelsWithoutMovingAnything() {
        val rule = SlotsRule()
        val option = capturing(7)
        var ticks = 0
        rule.update(option, null) { ticks++ }
        val afterFirst = rule.generation
        val other = { }
        rule.update(option, null, other)
        // No move, so no row re-tune — and the view still reads the *new* block, because the holder
        // is refreshed in place rather than replaced: `pickerContent` holds the same object and every
        // click goes through it. That is what makes skipping the rows safe for a callback slot, and
        // what a value compared in the attribute would have got wrong in the other direction.
        assertEquals(afterFirst, rule.generation)
        assertSame(other, rule.onSelected)
    }

    @Test
    fun readOnlyLabelIsContentNotCallback() {
        val rule = SlotsRule()
        val option = capturing(7)
        val callback = { }
        rule.update(option, null, callback)
        val afterFirst = rule.generation
        // The label is drawn content, so appearing is a change even though no block was there to
        // compare against before — `null !== label` moves it, and the same label again does not.
        val label = { }
        rule.update(option, label, callback)
        assertEquals(afterFirst + 1, rule.generation)
        rule.update(option, label, callback)
        assertEquals(afterFirst + 1, rule.generation)
        rule.update(option, null, callback)
        assertEquals(afterFirst + 2, rule.generation)
    }

    @Test
    fun slotBlocksHaveNoValueEquality() {
        // The language fact the holder is built on: equal capture for capture, still two objects. No
        // structural comparison of two blocks can tell a recapture from a re-evaluation, so identity
        // is the only question that can be asked of a slot — and it is the question `SlotsRule` above
        // answers, biased to the side that re-tunes.
        val first: (Int) -> Unit = capturing(7)
        val second: (Int) -> Unit = capturing(7)
        assertNotEquals(first, second)
        assertEquals(first, first)
        val absent: ((Int) -> Unit)? = null
        val alsoAbsent: ((Int) -> Unit)? = null
        assertNotEquals(absent, first)
        assertEquals(absent, alsoAbsent)
    }

    // The other half: everything the holder leaves out of the comparison really does compare equal,
    // so the slots are the only thing left that can force the work.

    /** The eight numbers `Picker` hands over for one column, built twice as two retunes would. */
    private fun propsOf(
        state: PickerState,
        verticalSpacingPx: Int = 12,
        gradientRatio: Float = 0.33f,
        gradientColor: Color = Color.Red,
        transform: ListTransformParams = pickerTransform(),
        readOnly: Boolean = false,
        userScrollEnabled: Boolean = true,
        rotary: PickerRotaryBehavior? = null,
        valueDescription: String? = "3 o'clock",
    ) = PickerProps(
        state = state,
        verticalSpacingPx = verticalSpacingPx,
        gradientRatio = gradientRatio,
        gradientColor = gradientColor,
        transform = transform,
        readOnly = readOnly,
        userScrollEnabled = userScrollEnabled,
        rotary = rotary,
        valueDescription = valueDescription,
    )

    /**
     * `Picker`'s scaling numbers as a per-tune construction would build them, fresh
     * `CubicBezierEasing` and all — the shape the shared `PickerScalingParams` replaced.
     */
    private fun pickerTransform(): ListTransformParams = ListTransformParams(
        edgeScale = 0.45f,
        edgeAlpha = 1.0f,
        minElementHeight = 0.0f,
        maxElementHeight = 0.0f,
        minTransitionArea = 0.45f,
        maxTransitionArea = 0.45f,
        scaleInterpolator = CubicBezierEasing(0.25f, 0.00f, 0.75f, 1.00f),
        viewportVerticalOffsetFraction = 1f / 5f,
    )

    @Test
    fun theScalingNumbersCompareByValueNotIdentity() {
        // Two separate constructions, one `CubicBezierEasing` each: equal, because that class compares
        // its four coefficients (`hibari-animation/src/main/java/com/huanli233/hibari.animation/
        // Easing.kt:154-160`). So the shared instance is an allocation change, not a behaviour change.
        assertEquals(pickerTransform(), pickerTransform())
        // And the reduce-motion swap the view makes on the incoming params is still a difference, so
        // blanking the scale under the accessibility setting cannot pass as no change.
        assertNotEquals(pickerTransform(), pickerTransform().copy(reduceMotion = true))
    }

    @Test
    fun aRetuneThatChangedNothingLeavesThePropsEqual() {
        val state = PickerState(4)
        assertEquals(propsOf(state = state), propsOf(state = state))
        // The grouped half of the diff compares `Map`s, and map equality walks hash buckets, so
        // `hashCode` has to agree with `equals` or a real no-change retune still looks like a change
        // (`HibariDiffCallback.kt:56-61` asks both questions).
        assertEquals(propsOf(state = state).hashCode(), propsOf(state = state).hashCode())
        // `Color` is a value class over one `ULong`
        // (`hibari-ui/src/main/java/com/huanli233/hibari/ui/graphics/Color.kt:19`), so the theme
        // colour the picker resolves per tune compares by its bits. `Unspecified` is a real value in
        // the pack and that is how the masking branch is picked, so it has to compare distinct.
        assertEquals(
            propsOf(state = state, gradientColor = Color.Unspecified),
            propsOf(state = state, gradientColor = Color.Unspecified),
        )
        assertNotEquals(
            propsOf(state = state),
            propsOf(state = state, gradientColor = Color.Unspecified),
        )
    }

    @Test
    fun everyRealChangeStillReachesTheView() {
        // Skipping the rebuild is only safe while a genuine change still compares unequal — one field
        // at a time.
        val state = PickerState(4)
        val other = PickerState(4)
        // Two states that report the same selection and are still two pickers: `PickerState` declares
        // no `equals`, so a swap is a difference by identity, and `applyProps` re-registers the view
        // on the new state because of it.
        assertEquals(state.selectedOptionIndex, other.selectedOptionIndex)
        assertNotEquals(propsOf(state = state), propsOf(state = other))
        assertNotEquals(propsOf(state = state), propsOf(state = state, verticalSpacingPx = 13))
        assertNotEquals(propsOf(state = state), propsOf(state = state, gradientRatio = 0.25f))
        assertNotEquals(propsOf(state = state), propsOf(state = state, readOnly = true))
        assertNotEquals(propsOf(state = state), propsOf(state = state, userScrollEnabled = false))
        assertNotEquals(propsOf(state = state), propsOf(state = state, valueDescription = "4 o'clock"))
        assertNotEquals(propsOf(state = state), propsOf(state = state, valueDescription = null))
        assertNotEquals(
            propsOf(state = state),
            propsOf(state = state, transform = pickerTransform().copy(edgeScale = 0.7f)),
        )
        // The rotary behaviour is remembered per state at the call site, so this pins the other half:
        // a sensitivity the wearer changed is a difference, and `applyProps` reads exactly this
        // comparison to decide whether to drop an in-flight rotary gesture.
        assertEquals(
            propsOf(state = state, rotary = PickerRotaryBehavior(state)),
            propsOf(state = state, rotary = PickerRotaryBehavior(state)),
        )
        assertNotEquals(
            propsOf(state = state, rotary = PickerRotaryBehavior(state)),
            propsOf(state = state, rotary = PickerRotaryBehavior(state, snapSensitivity = 0.8f)),
        )
    }

    @Test
    fun theGroupRowComparesByItsNumbersToo() {
        val left = PickerState(4)
        val right = PickerState(6)
        fun group(
            selected: PickerState? = left,
            autoCenter: Boolean = true,
            propagate: Boolean = false,
            singlePointer: Boolean = true,
        ) = PickerGroupProps(
            autoCenter = autoCenter,
            propagateMinConstraints = propagate,
            singlePointerInput = singlePointer,
            centeringDampingRatio = 1f,
            centeringStiffness = 1400f,
            selectedState = selected,
        )
        // `WearPickerGroupView.pickerGroupProps` re-derives the centring target and calls
        // `requestLayout()` for anything it reads as new, so a no-op retune has to compare equal or
        // every host retune costs the row a layout pass.
        assertEquals(group(), group())
        assertEquals(group().hashCode(), group().hashCode())
        assertNotEquals(group(), group(autoCenter = false))
        assertNotEquals(group(), group(propagate = true))
        assertNotEquals(group(), group(singlePointer = false))
        // Which column is selected is what answers TalkBack's scroll actions through the row, and the
        // `readOnly` shim of every column hangs on it.
        assertNotEquals(group(), group(selected = right))
        assertNotEquals(group(selected = null), group())
    }
}
