package com.huanli233.hibari.wear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The width solve behind [ButtonGroup]: [buttonGroupComputeWidths] for the widths at rest and
 * [buttonGroupPressExpandedWidths] for the press expansion. Both are pure, so this is a plain JVM test.
 *
 * Every expectation below is worked out by hand from upstream's algorithm
 * (`androidx/wear/compose/material3/ButtonGroup.kt:497` and the grow loop at `:149`), and the
 * arithmetic is written out next to the number it produces, so a disagreement can be settled against
 * upstream rather than against this port. Three invariants run through the whole file and each case
 * checks one by hand:
 *
 *  - the widths at rest sum to `availableWidth - totalSpacing`, because the space is only ever moved
 *    between children, never invented (`buttonGroupComputeWidths` step 6 taxes and subsidises);
 *  - the expansion leaves the sum alone: whatever a pressed child gains, its neighbour(s) lose;
 *  - `totalSpacing = spacingPx * (n - 1)`, which is 0 for a one-child group and *-spacing* for the
 *    empty one — the empty case is asserted separately rather than assumed sane.
 */
class ButtonGroupWidthsTest {

    // ---- widths at rest ---------------------------------------------------------------------------

    @Test
    fun `equal weights split the distributable width`() {
        // spacing 4 over 2 children -> totalSpacing 4, so 200 - 4 = 196 is distributable.
        // Both weights are 1 of a total 2, so each takes 196/2 = 98. Neither is under its 48 minimum,
        // so the constraint pass has nothing to fix and the answer is the raw ratio.
        val widths = buttonGroupComputeWidths(listOf(48f to 1f, 48f to 1f), spacingPx = 4, availableWidth = 200)
        assertArrayEquals(intArrayOf(98, 98), widths)
        // 98 + 98 + one 4px gap is exactly the 200 available.
        assertEquals(200, widths.sum() + 4 * (widths.size - 1))
    }

    @Test
    fun `weights are ratios of the distributable width`() {
        // 196 distributable again, over a total weight of 4: 196 * 1/4 = 49 and 196 * 3/4 = 147.
        // 49 clears the 48 floor by one pixel, so no minimum binds and the ratio survives untouched.
        val widths = buttonGroupComputeWidths(listOf(48f to 1f, 48f to 3f), spacingPx = 4, availableWidth = 200)
        assertArrayEquals(intArrayOf(49, 147), widths)
        assertEquals(196, widths.sum())
    }

    @Test
    fun `a bound minimum is paid for by the others in proportion to their weights`() {
        // Three children, totalSpacing 8, so 192 is distributable over a total weight of 4:
        //   A(min 80, w 1) -> 48, B(min 20, w 1) -> 48, C(min 20, w 2) -> 96.
        // Slack per weight: A (48-80)/1 = -32, B (48-20)/1 = 28, C (96-20)/2 = 38, so A is served
        // first. A is raised to its 80 minimum, which puts 32 into `owedWidth`; B is next and is taxed
        // 32 * 1/3 = 10.667 -> 37.333, leaving 21.333 owed; C is last with `remainingWeight` down to
        // 2, so it pays the whole remainder: 21.333 * 2/2 = 21.333 -> 74.667.
        // Rounding: 37.333 -> 37, 74.667 -> 75.
        val widths = buttonGroupComputeWidths(
            listOf(80f to 1f, 20f to 1f, 20f to 2f),
            spacingPx = 4,
            availableWidth = 200,
        )
        assertArrayEquals(intArrayOf(80, 37, 75), widths)
        // The 32 A got is exactly the 32 B and C gave up, so the row is still 192 wide.
        assertEquals(192, widths.sum())
    }

    @Test
    fun `successive bound minimums cascade the rest of the debt`() {
        // Four children, totalSpacing 12, 228 distributable, every weight 1 -> 57 each.
        // Slack: A (57-120) = -63, C (57-55) = 2, B (57-40) = 17, D (57-10) = 47 -> order A, C, B, D.
        //   A is 63 short of its 120 minimum and is raised to 120, so 63 is owed. remainingWeight
        //     drops to 3.
        //   C is taxed 63 * 1/3 = 21 but has only 2 of slack over its 55 minimum, so it pays 2, keeps
        //     the other 59 in the debt, and remainingWeight drops to 2.
        //   B is taxed 61 * 1/2 = 30.5 against 17 of slack over its 40: it pays 17, lands on its
        //     minimum, and leaves 44 owed. remainingWeight drops to 1.
        //   D is taxed the whole remainder, 44 * 1/1 = 44, affordable out of its 47 of slack, so
        //     57 - 44 = 13 and the debt closes at zero, which is what the `require` checks.
        val widths = buttonGroupComputeWidths(
            listOf(120f to 1f, 40f to 1f, 55f to 1f, 10f to 1f),
            spacingPx = 4,
            availableWidth = 240,
        )
        assertArrayEquals(intArrayOf(120, 40, 55, 13), widths)
        assertEquals(228, widths.sum())
    }

    @Test
    fun `room for exactly the minimums leaves everyone on their minimum`() {
        // totalSpacing 8 and minimums summing to 192, i.e. `extraSpace` is 200 - 200 = 0. Zero is not
        // negative, so the minimums are *not* thrown away, and each child ends on its floor.
        // Raw distribution over 192: A 48, B 48, C 96. Slack per weight: A (48-100)/1 = -52,
        // C (96-62)/2 = 17, B (48-30)/1 = 18 -> order A, C, B.
        //   A takes 52 from the others. C is taxed 52 * 2/3 = 34.667 but only has 34 to give, so it
        //     lands on 62 and still owes 18. B, the last with remainingWeight 1, is taxed exactly 18
        //     and lands exactly on its 30 minimum.
        val widths = buttonGroupComputeWidths(
            listOf(100f to 1f, 30f to 1f, 62f to 2f),
            spacingPx = 4,
            availableWidth = 200,
        )
        assertArrayEquals(intArrayOf(100, 30, 62), widths)
        assertEquals(192, widths.sum())
    }

    @Test
    fun `a zero weight child still gets its minimum and the weighted ones fund it`() {
        // totalSpacing 8, 192 distributable over a total weight of 2, and A has weight 0:
        // A = 192 * 0/2 = 0, B = C = 96. A sorts to Float.MIN_VALUE because it is a zero-weight item
        // below its minimum, i.e. before everything else, and is raised to 48 - a debt of 48 that its
        // weight can never repay, since `remainingWeight -= 0` leaves it at 2. B is then taxed
        // 48 * 1/2 = 24 -> 72, and C the remaining 24 * 1/1 = 24 -> 72.
        val widths = buttonGroupComputeWidths(
            listOf(48f to 0f, 48f to 1f, 48f to 1f),
            spacingPx = 4,
            availableWidth = 200,
        )
        assertArrayEquals(intArrayOf(48, 72, 72), widths)
        assertEquals(192, widths.sum())
    }

    @Test
    fun `no weights at all leaves the group at its minimums and narrower than its parent`() {
        // totalWeight 0, so the whole distribution block is skipped and each item keeps the width it
        // was seeded with, which is its own minimum: 48 and 48. The constraint loop then breaks on the
        // very first iteration (`remainingWeight == 0f`), so the row measures 48 + 48 + one 4px gap =
        // 100 of the 200 available. Upstream flags exactly this at :513 with "should we really handle
        // the totalWeight <= 0 case? If so, we need to leave items at their minWidth and center the
        // whole thing?" - it leaves them, uncentred, and this test is the record that it does.
        val widths = buttonGroupComputeWidths(listOf(48f to 0f, 48f to 0f), spacingPx = 4, availableWidth = 200)
        assertArrayEquals(intArrayOf(48, 48), widths)
        assertEquals(100, widths.sum() + 4)
    }

    @Test
    fun `room too small for the minimums throws the minimums away`() {
        // Minimums 100 + 20 + 90 = 210 plus 8 of spacing do not fit in 200, so `extraSpace` is -18 and
        // every minimum is reset to 0 before the constraint pass. What is left is the raw weighted
        // split of 192 over a total weight of 4: A 48, B 48, C 96 - with A under the 100 it asked for,
        // because upstream would rather break a child's floor than overflow the group.
        val widths = buttonGroupComputeWidths(
            listOf(100f to 1f, 20f to 1f, 90f to 2f),
            spacingPx = 4,
            availableWidth = 200,
        )
        assertArrayEquals(intArrayOf(48, 48, 96), widths)
        assertEquals(192, widths.sum())
    }

    @Test
    fun `a single child takes the whole width`() {
        // totalSpacing = 4 * (1 - 1) = 0, so the one child owns all 200: 200 * 1/1 = 200, comfortably
        // over its 48 minimum. The gap parameter is paid for by nobody at all in a one-child group.
        val widths = buttonGroupComputeWidths(listOf(48f to 1f), spacingPx = 4, availableWidth = 200)
        assertArrayEquals(intArrayOf(200), widths)
    }

    @Test
    fun `no children solve to no widths`() {
        // helper is empty, so totalSpacing is 4 * (0 - 1) = -4 and minSpaceNeeded -4: the arithmetic
        // is nonsense, but nothing consumes it, because the distribution loop, the sort, the
        // constraint loop and the result array are all empty. owedWidth stays 0, which is what lets
        // the `require` through.
        val widths = buttonGroupComputeWidths(emptyList(), spacingPx = 4, availableWidth = 200)
        assertEquals(0, widths.size)
    }

    @Test
    fun `a space smaller than the gaps collapses to zero widths`() {
        // totalSpacing 80 against 10 available: minSpaceNeeded is 224, so `extraSpace` < 0 and the
        // minimums are zeroed; the raw split is then (10 - 80) * 1/3 = -23.333 each. Every one of
        // those is below the (now zero) minimum, so the constraint pass raises each to 0 and piles up
        // 70 of debt that the final `remainingWeight == 0f` branch of the `require` forgives.
        val widths = buttonGroupComputeWidths(
            listOf(48f to 1f, 48f to 1f, 48f to 1f),
            spacingPx = 40,
            availableWidth = 10,
        )
        assertArrayEquals(intArrayOf(0, 0, 0), widths)
    }

    // ---- press expansion --------------------------------------------------------------------------

    @Test
    fun `a pressed first child takes its expansion from its only neighbour`() {
        // 2 children, so `1 until lastIndex` = `1 until 1` is empty and index 0 uses the edge branch:
        // growth = round(1f * 24) = 24 taken whole from widths[1] and given to widths[0].
        // Built on the rest widths of the equal-weight case above.
        val widths = buttonGroupPressExpandedWidths(
            widths = intArrayOf(98, 98),
            animatedSizes = floatArrayOf(1f, 0f),
            expansionWidthPx = 24f,
        )
        assertArrayEquals(intArrayOf(122, 74), widths)
        assertEquals(196, widths.sum())
    }

    @Test
    fun `a pressed middle child splits its expansion across both neighbours`() {
        // index 1 of 3 is `1 until 2` -> the middle branch: growth = (24/2).roundToInt() * 2 = 24 and
        // each neighbour gives growth/2 = 12.
        val widths = buttonGroupPressExpandedWidths(
            widths = intArrayOf(70, 70, 70),
            animatedSizes = floatArrayOf(0f, 1f, 0f),
            expansionWidthPx = 24f,
        )
        assertArrayEquals(intArrayOf(58, 94, 58), widths)
        assertEquals(210, widths.sum())
    }

    @Test
    fun `a middle expansion is rounded to an even number of pixels`() {
        // 25px of expansion: the middle branch computes growth = (25/2).roundToInt() * 2 =
        // round(12.5) * 2 = 13 * 2 = 26, so each neighbour gives exactly 13. Without the parity step
        // the neighbours would each lose 25/2 = 12 to integer division while the middle gained 25,
        // and the row would come out one pixel wider than its parent; the even growth is also what
        // keeps the pressed button's centre - and the label inside it - where it was.
        val widths = buttonGroupPressExpandedWidths(
            widths = intArrayOf(70, 70, 70),
            animatedSizes = floatArrayOf(0f, 1f, 0f),
            expansionWidthPx = 25f,
        )
        assertArrayEquals(intArrayOf(57, 96, 57), widths)
        assertEquals(210, widths.sum())
    }

    @Test
    fun `a press halfway through its animation moves half the expansion`() {
        // The animated value is a continuous 0..1, not a boolean: value = 0.5 * 24 = 12, index 0 is an
        // edge so growth = round(12f) = 12, taken from widths[1].
        val widths = buttonGroupPressExpandedWidths(
            widths = intArrayOf(70, 70, 70),
            animatedSizes = floatArrayOf(0.5f, 0f, 0f),
            expansionWidthPx = 24f,
        )
        assertArrayEquals(intArrayOf(82, 58, 70), widths)
        assertEquals(210, widths.sum())
    }

    @Test
    fun `a pressed last child shrinks the child before it`() {
        // index 2 == lastIndex, so the else branch takes the long way round: widths[2-1] -= 24.
        val widths = buttonGroupPressExpandedWidths(
            widths = intArrayOf(70, 70, 70),
            animatedSizes = floatArrayOf(0f, 0f, 1f),
            expansionWidthPx = 24f,
        )
        assertArrayEquals(intArrayOf(70, 46, 94), widths)
        assertEquals(210, widths.sum())
    }

    @Test
    fun `two pressed children can squeeze the one between them under its minimum`() {
        // The loop compounds in index order: index 0 first takes 24 from the middle (70 -> 46), then
        // index 1's own zero growth changes nothing, then index 2 takes another 24 from it
        // (46 -> 22) while both ends grow to 94. 22 is below the 48dp minimum such a button would
        // carry, and that is documented rather than accidental - `ButtonGroupScope.minWidth` says the
        // expansion may push a neighbour under it, because the minimums are solved once, at rest.
        val widths = buttonGroupPressExpandedWidths(
            widths = intArrayOf(70, 70, 70),
            animatedSizes = floatArrayOf(1f, 0f, 1f),
            expansionWidthPx = 24f,
        )
        assertArrayEquals(intArrayOf(94, 22, 94), widths)
        assertEquals(210, widths.sum())
    }

    @Test
    fun `a one child group never expands`() {
        // Upstream guards the whole block with `if (measurables.size > 1)`: with nothing to take it
        // from, a lone pressed button would otherwise grow past the group's own width.
        val widths = buttonGroupPressExpandedWidths(
            widths = intArrayOf(200),
            animatedSizes = floatArrayOf(1f),
            expansionWidthPx = 24f,
        )
        assertArrayEquals(intArrayOf(200), widths)
    }

    @Test
    fun `children that did not ask to animate leave the rest widths alone`() {
        // Every animated size is 0, which is what a child without `Modifier.animateWidth` contributes
        // (upstream's `ButtonGroupParentData.DEFAULT.pressedState` is an `Animatable(0f)` that nothing
        // ever drives), and growth = round(0f) = 0 for all three.
        val widths = buttonGroupPressExpandedWidths(
            widths = intArrayOf(70, 70, 70),
            animatedSizes = floatArrayOf(0f, 0f, 0f),
            expansionWidthPx = 24f,
        )
        assertArrayEquals(intArrayOf(70, 70, 70), widths)
    }

    @Test
    fun `rest widths and expansion add up to the same row`() {
        // End to end on the two-child case: 200 - 4 = 196 split 1:1 = 98/98, then a fully pressed
        // first child of 24px -> 122/74. The row is still 196 + 4 = 200 wide, which is the point of
        // the group: it never resizes itself when a button is held.
        val rest = buttonGroupComputeWidths(listOf(48f to 1f, 48f to 1f), spacingPx = 4, availableWidth = 200)
        val pressed = buttonGroupPressExpandedWidths(rest, floatArrayOf(1f, 0f), 24f)
        assertArrayEquals(intArrayOf(122, 74), pressed)
        assertEquals(200, pressed.sum() + 4)
    }
}
