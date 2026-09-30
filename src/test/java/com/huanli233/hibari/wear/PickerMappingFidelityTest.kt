package com.huanli233.hibari.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The item/option mapping in [PickerState], pinned one layer below `PickerStateTest`.
 *
 * That test reads `selectedOptionIndex`, which is `(centerItemIndex + optionsOffset) %
 * numberOfOptions` (`Picker.kt:203-204`, upstream `material3/Picker.kt:384-386`) and therefore sees
 * the mapping only modulo the option count. Everything here is picked to be invisible to that
 * reading: the offset itself, the absolute focal item, the direction a tie went, and what a rejected
 * resize leaves behind.
 *
 * Upstream's comment on the field (`material3/Picker.kt:463`) states the map as
 * `itemIndex - optionsOffset = (mod numberOfOptions) optionIndex`, but every consumer adds, upstream
 * included - `selectedOptionIndex` (`:386`) and its `optionsOffset` assignment (`:374-379`) both do,
 * as does this port's `Picker.kt:204` and `WearPickerViews.kt`'s `optionIndexOf`. The addition is
 * what these tests pin.
 */
class PickerMappingFidelityTest {

    private val center = 50_000_000 // 4 * (25_000_000 / 2), the repeating picker's midline item

    @Test
    fun theFocalItemIsTheCentrepieceOfTheRepetitions() {
        // repeats = 100_000_000 / n, centerOffset = n * (repeats / 2), integer division throughout
        // (upstream `material3/Picker.kt:467-469`).
        assertEquals(center + 2, PickerState(4, initiallySelectedIndex = 2).centerItemIndex)
        // 7 does not divide 100_000_000 (7 x 14_285_714 = 99_999_998), yet it divides the halved
        // offset 49_999_999, which is what makes a non-terminating option count still land squarely.
        assertEquals(49_999_999 + 5, PickerState(7, initiallySelectedIndex = 5).centerItemIndex)
        // Not repeating: repeats is 1, so the centre offset is n * (1 / 2) = 0 and the focal item is
        // the option itself.
        assertEquals(2, PickerState(4, initiallySelectedIndex = 2, shouldRepeatOptions = false).centerItemIndex)
    }

    @Test
    fun onlyARepeatingPickerGetsTheHundredMillionSlots() {
        // `numberOfItems()` (`Picker.kt:243-244`), the divisor behind every one of those numbers.
        assertEquals(100_000_000, PickerState(4).numberOfItems())
        assertEquals(4, PickerState(4, shouldRepeatOptions = false).numberOfItems())
        assertEquals(1, PickerState(1, shouldRepeatOptions = false).numberOfItems())
    }

    @Test
    fun optionsOffsetCarriesTheMapAcrossAResize() {
        val state = PickerState(4, initiallySelectedIndex = 2)
        assertEquals(0, state.optionsOffset)
        // positiveModulo(min(2, 2) - 50_000_002, 3): 50_000_000 has digit sum 5, so it is 2 mod 3,
        // so -50_000_000 is -2, and the triple-modulo form gives 1.
        state.numberOfOptions = 3
        assertEquals(1, state.optionsOffset)
        // Then with 6 options: 6 x 8_333_333 = 49_999_998, so 50_000_000 is 2 mod 6 and the offset
        // positiveModulo(2 - 50_000_002, 6) is 4 - note that the offset is recomputed from the
        // *current* offset's option, not from the previous offset.
        state.numberOfOptions = 6
        assertEquals(4, state.optionsOffset)
        assertEquals(2, state.selectedOptionIndex)
    }

    @Test
    fun aResizeNeverMovesTheFocalItem() {
        val state = PickerState(8, initiallySelectedIndex = 6)
        state.numberOfOptions = 4
        // optionsOffset = positiveModulo(min(6, 3) - 50_000_006, 4) = positiveModulo(-50_000_003, 4),
        // and 50_000_003 ends in 03, so it is 3 mod 4 and the offset is 1.
        assertEquals(1, state.optionsOffset)
        // The item never moved: only the map did, which is the whole point of upstream's setter
        // (`material3/Picker.kt:370-381`).
        assertEquals(50_000_006, state.centerItemIndex)
        assertEquals(3, state.selectedOptionIndex)
    }

    @Test
    fun selectionIdentitySurvivesEveryResize() {
        // The setter's contract, checked over a grid rather than one worked example: after a change
        // of option count the same option is still selected, unless it no longer exists, and then the
        // last one is (`coerceAtMost(newNumberOfOptions - 1)`, `Picker.kt:195`).
        for (options in listOf(2, 3, 4, 5, 7, 8, 12)) {
            for (start in 0 until options) {
                for (newOptions in listOf(1, 2, 3, 5, 9, 16)) {
                    val state = PickerState(options, initiallySelectedIndex = start)
                    state.numberOfOptions = newOptions
                    assertEquals(
                        "n=$options start=$start -> m=$newOptions",
                        minOf(start, newOptions - 1),
                        state.selectedOptionIndex,
                    )
                }
            }
        }
    }

    @Test
    fun theShorterWayRoundIsOnlyVisibleInTheItemIndex() {
        val state = PickerState(4, initiallySelectedIndex = 1)
        assertEquals(50_000_001, state.centerItemIndex)
        // Target 3: stepsPrev = positiveModulo(1 - 3, 4) = 2 and stepsNext = positiveModulo(3 - 1, 4) = 2.
        // A tie goes backwards (`stepsPrev <= stepsNext`, `Picker.kt:287`), and the forward route would
        // have landed on option 3 as well, so only the absolute index shows which way it went.
        assertEquals(49_999_999, state.closestTargetItemIndex(3))
        // Target 2: stepsPrev = 3, stepsNext = 1, so forwards.
        assertEquals(50_000_002, state.closestTargetItemIndex(2))
        // The option already chosen costs nothing either way.
        assertEquals(50_000_001, state.closestTargetItemIndex(1))
        // With nothing wrapped around it, `closestTargetItemIndex` is the identity on the option
        // (upstream `material3/Picker.kt:473-483` returns `option` unchanged).
        assertEquals(
            3,
            PickerState(5, initiallySelectedIndex = 4, shouldRepeatOptions = false)
                .closestTargetItemIndex(3),
        )
    }

    @Test
    fun scrollToOptionWithoutAViewWritesTheSameIndex() {
        // `scrollToOption` falls back to `centerItemIndex = closestTargetItemIndex(index)` with
        // `centerItemScrollOffset = 0` (`Picker.kt:211-219`) when no picker has attached yet, which is
        // upstream's `scalingLazyListState.scrollToItem(getClosestTargetItemIndex(index), 0)`.
        val state = PickerState(4, initiallySelectedIndex = 1)
        state.scrollToOption(3)
        assertEquals(49_999_999, state.centerItemIndex)
        assertEquals(3, state.selectedOptionIndex)
        assertEquals(0f, state.centerItemScrollOffset, 0f)
        // ... and a scroll costs no items when it is already there.
        state.scrollToOption(3)
        assertEquals(49_999_999, state.centerItemIndex)
    }

    @Test
    fun aRejectedResizeLeavesTheMappingAlone() {
        val state = PickerState(4, initiallySelectedIndex = 2)
        // `verifyNumberOfOptions` runs before the offset is recomputed (`Picker.kt:188-199`), so a
        // rejected count cannot corrupt the map on its way out.
        assertThrows(IllegalArgumentException::class.java) { state.numberOfOptions = 0 }
        assertEquals(0, state.optionsOffset)
        assertEquals(50_000_002, state.centerItemIndex)
        assertEquals(4, state.numberOfOptions)
        assertEquals(2, state.selectedOptionIndex)
        assertThrows(IllegalArgumentException::class.java) {
            state.numberOfOptions = 100_000_000 / 3
        }
        assertEquals(4, state.numberOfOptions)
    }
}
