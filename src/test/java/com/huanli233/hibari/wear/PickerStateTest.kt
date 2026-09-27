package com.huanli233.hibari.wear

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one piece of [PickerState] that is pure arithmetic, and the piece everything else is written
 * against: a repeating picker holds `LARGE_NUMBER_OF_ITEMS = 100_000_000` slots (`Picker.kt` in
 * androidx.wear.compose.material3, its file-private constant) for a handful of options, so many items
 * map to one option and both the starting index and [PickerState.numberOfOptions]'s setter exist to
 * keep that map straight.
 *
 * Every expectation here is computed by hand from upstream's formulas, none from the Kotlin:
 *
 *  - starting index — `val repeats = numberOfItems() / numberOfOptions`
 *    `val centerOffset = numberOfOptions * (repeats / 2)`
 *    `ScalingLazyListState(centerOffset + initiallySelectedIndex, 0)`, with integer division.
 *  - selection — `selectedOptionIndex = (centerItemIndex + optionsOffset) % numberOfOptions`.
 *  - destination — `stepsPrev = positiveModulo(selected - option, n)`,
 *    `stepsNext = positiveModulo(option - selected, n)`, then
 *    `centerItemIndex + if (stepsPrev <= stepsNext) -stepsPrev else stepsNext`, so a tie goes
 *    backwards. `positiveModulo(n, mod) = ((n % mod) + mod) % mod`.
 *  - resize — `optionsOffset = positiveModulo(selected.coerceAtMost(new - 1) - centerItemIndex, new)`.
 *
 * Worked for `n = 4`: repeats = 100_000_000 / 4 = 25_000_000, centerOffset = 4 x 12_500_000 =
 * 50_000_000, which is itself a multiple of 4, so the centre item reports exactly the option it was
 * asked for. The `n = 7` case is the interesting one: 100_000_000 is *not* a multiple of 7
 * (7 x 14_285_714 = 99_999_998), yet centerOffset = 7 x 7_142_857 = 49_999_999 is, so the halving of
 * `repeats` is what makes the initial mapping come out right — and a picker whose item count is not
 * an exact multiple of its option count is the normal case, not the edge one.
 */
class PickerStateTest {

    @Test
    fun repeatingPickerStartsMidRepetitions() {
        assertEquals(0, PickerState(initialNumberOfOptions = 4).selectedOptionIndex)
        assertEquals(2, PickerState(4, initiallySelectedIndex = 2).selectedOptionIndex)
        // 49_999_999 + 5 = 50_000_004; 50_000_004 - 49_999_999 = 5, and 49_999_999 = 7 x 7_142_857.
        assertEquals(5, PickerState(7, initiallySelectedIndex = 5).selectedOptionIndex)
    }

    @Test
    fun nonRepeatingPickerStartsAtItsOnlyCycle() {
        // repeats = numberOfOptions / numberOfOptions = 1, so centerOffset = n * (1 / 2) = n * 0 = 0.
        assertEquals(2, PickerState(4, 2, shouldRepeatOptions = false).selectedOptionIndex)
        assertEquals(0, PickerState(4, initiallySelectedIndex = 0).selectedOptionIndex)
        assertEquals(
            3,
            PickerState(5, initiallySelectedIndex = 3, shouldRepeatOptions = false)
                .selectedOptionIndex,
        )
    }

    @Test
    fun equalDistancesScrollBackwards() {
        val state = PickerState(4, initiallySelectedIndex = 2)
        // Selected 2, target 0: stepsPrev = 2, stepsNext = positiveModulo(-2, 4) = 2. A tie, so
        // backwards two items, from 50_000_002 to 50_000_000 — a multiple of 4, hence option 0.
        state.scrollToOption(0)
        assertEquals(0, state.selectedOptionIndex)
    }

    @Test
    fun shorterWayRoundWinsAcrossTheWrap() {
        val state = PickerState(4, initiallySelectedIndex = 2)
        state.scrollToOption(0)
        // Selected 0 at item 50_000_000, target 3: stepsPrev = positiveModulo(0 - 3, 4) = 1,
        // stepsNext = 3. One step back beats three forward, landing on 49_999_999, whose remainder
        // mod 4 is 3 — so the option index wraps backwards through zero rather than running the long
        // way round.
        state.scrollToOption(3)
        assertEquals(3, state.selectedOptionIndex)
        // Selected 3, target 1: stepsPrev = 2, stepsNext = positiveModulo(1 - 3, 4) = 2. Tied again,
        // so back to 49_999_997, whose last two digits 97 give 97 mod 4 = 1.
        state.scrollToOption(1)
        assertEquals(1, state.selectedOptionIndex)
    }

    @Test
    fun everyOptionIsReachable() {
        val state = PickerState(initialNumberOfOptions = 7)
        for (option in 0 until 7) {
            state.scrollToOption(option)
            assertEquals(option, state.selectedOptionIndex)
        }
        // And back round the other way, since each hop above was one step at a time.
        state.scrollToOption(6)
        assertEquals(6, state.selectedOptionIndex)
    }

    @Test
    fun nonRepeatingPickerGoesStraightToTheItem() {
        val state = PickerState(5, initiallySelectedIndex = 4, shouldRepeatOptions = false)
        // Upstream returns `option` unchanged when the options are not repeated: no shortest way
        // round, so option 1 from option 4 really does travel three items backwards.
        state.scrollToOption(1)
        assertEquals(1, state.selectedOptionIndex)
        state.scrollToOption(0)
        assertEquals(0, state.selectedOptionIndex)
    }

    @Test
    fun shrinkingKeepsTheSelectionAndClampsItIntoRange() {
        val state = PickerState(4, initiallySelectedIndex = 2)
        // optionsOffset = positiveModulo(min(2, 3 - 1) - 50_000_002, 3). 50_000_000 has digit sum 5,
        // so it is 2 mod 3 and -50_000_000 is -2, giving positiveModulo(-2, 3) = 1. Then
        // (50_000_002 + 1) mod 3: 50_000_003 has digit sum 8, so it is 2 — still the same option.
        state.numberOfOptions = 3
        assertEquals(2, state.selectedOptionIndex)
        // 50_000_002 + 4 = 50_000_006; 6 x 8_333_333 = 49_999_998, leaving 8, so 8 mod 6 = 2 again.
        state.numberOfOptions = 6
        assertEquals(2, state.selectedOptionIndex)
    }

    @Test
    fun shrinkingPastTheSelectionPullsItToTheLastOption() {
        val state = PickerState(8, initiallySelectedIndex = 6)
        // optionsOffset = positiveModulo(min(6, 4 - 1) - 50_000_006, 4). 50_000_003 mod 4 is 3
        // (last two digits 03), so -50_000_003 is -3 and positiveModulo(-3, 4) = 1. Then
        // (50_000_006 + 1) mod 4 = 50_000_007 mod 4 = 3, the largest option still on the list.
        state.numberOfOptions = 4
        assertEquals(3, state.selectedOptionIndex)
    }

    @Test
    fun optionCountIsBoundsChecked() {
        for (illegal in listOf(0, -1)) {
            try {
                PickerState(initialNumberOfOptions = illegal)
                throw AssertionError("expected a rejection of $illegal options")
            } catch (expected: IllegalArgumentException) {
                assertEquals("The picker should have at least one item.", expected.message)
            }
        }
        // Upstream's ceiling is `numberOfOptions < LARGE_NUMBER_OF_ITEMS / 3`, i.e. < 33_333_333, so
        // that at least three full cycles of every option exist.
        try {
            PickerState(initialNumberOfOptions = 100_000_000 / 3)
            throw AssertionError("expected a rejection of the upper bound itself")
        } catch (expected: IllegalArgumentException) {
            assertEquals(
                "The picker should have less than ${100_000_000 / 3} items",
                expected.message,
            )
        }
        PickerState(initialNumberOfOptions = 100_000_000 / 3 - 1)
    }
}
