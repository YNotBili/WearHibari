package com.huanli233.hibari.wear

import android.view.Gravity
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.CornerBasedShape
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.thenLayoutAttribute
import com.huanli233.hibari.ui.thenUnitLayoutAttribute
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.view.ButtonGroupLayout
import com.huanli233.hibari.wear.view.WearButtonGroupView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Upstream's `ButtonGroupDefaults.FullWidthHorizontalPaddingPercentage`, i.e. the padding at each
 * side of the [ButtonGroup] as a percentage of the screen height. Kept at file scope because a
 * `const` member declared after the `@Tunable` that reads it trips the object-ordering check.
 */
private const val buttonGroupFullWidthHorizontalPaddingPercentage: Float = 5.2f

/**
 * Ported from androidx.wear.compose.material3.ButtonGroup (`material3/ButtonGroup.kt:84`).
 *
 * A row of buttons that share one width between them: [buttonGroupComputeWidths] hands each child a
 * slice proportional to its `Modifier.weight`, pulling back from the others wherever that would put
 * a child under its `Modifier.minWidth`, and [buttonGroupPressExpandedWidths] then grows the touched
 * child at its neighbour(s)' expense so the group stays exactly as wide while a button is pressed.
 *
 * What did not come over, and why:
 *  - `transformation: SurfaceTransformation?`: `SurfaceTransformation` is not ported, so there is
 *    nothing to apply. Upstream's deprecated overload without it is skipped too, along with its
 *    `@DeprecationLevel.HIDDEN` Java-compat twin.
 *  - `Modifier.animateWidth(interactionSource)`'s parameter, not its behaviour: Hibari has no
 *    `InteractionSource` to observe, so [WearButtonGroupView] reads the press off its own touch
 *    stream instead — see [ButtonGroupScope.animateWidth] for what that costs.
 *  - `verticalAlignment` is an Android gravity (`Gravity.TOP`, `Gravity.CENTER_VERTICAL`,
 *    `Gravity.BOTTOM`) because this port has no `Alignment`.
 *  - `ButtonGroupDefaults.minimumVerticalListContentPadding` is not ported: it exists to feed
 *    `TransformingLazyColumnItemScope.minimumVerticalContentPadding`, which the `wear.lazy` port has no
 *    equivalent of yet, so it would return a number nobody can consume.
 *  - `contentPadding` is nullable and resolved in the body: a `@Tunable` default-parameter expression
 *    is hoisted into a non-`@Tunable` method, where calling [ButtonGroupDefaults.fullWidthPaddings]
 *    would not be allowed.
 *
 * [shape] and [connected] are the two additions, and they are not ports of anything — see
 * [ButtonGroupDefaults.buttonShape], which they feed.
 *
 * @param modifier Modifier to be applied to the button group
 * @param spacing the amount of spacing between buttons
 * @param expansionWidth how much buttons grow when pressed
 * @param contentPadding The spacing values to apply internally between the container and the
 *   content; null means [ButtonGroupDefaults.fullWidthPaddings].
 * @param verticalAlignment the vertical alignment of the button group's children.
 * @param shape the shape the group reads as as a whole; only a [CornerBasedShape] can be split per
 *   corner, so any other shape leaves the children's own shapes alone. Not a port: upstream's
 *   `ButtonGroup` has no shape parameter and leaves each child's container alone.
 * @param connected false keeps every child's own container, which is what upstream does; true lets
 *   the group rewrite the shared edges from [shape]. It is off by default precisely because it is the
 *   invented half: a plain [ButtonGroup] should behave like the port, and [SplitButtonGroup] asks for
 *   the corners explicitly.
 * @param content the content and properties of each button. The Ux guidance is to use no more than
 *   3 buttons within a ButtonGroup. Note that this content is on the [ButtonGroupScope], to provide
 *   access to the three modifiers that configure the buttons.
 */
@Tunable
fun ButtonGroup(
    modifier: Modifier = Modifier,
    spacing: Dp = ButtonGroupDefaults.Spacing,
    expansionWidth: Dp = ButtonGroupDefaults.ExpansionWidth,
    contentPadding: PaddingValues? = null,
    verticalAlignment: Int = Gravity.CENTER_VERTICAL,
    shape: Shape = ButtonGroupDefaults.shape,
    connected: Boolean = false,
    content: @Tunable ButtonGroupScope.() -> Unit,
) {
    val scope = content
    Node(
        modifier = modifier
            .viewClass(WearButtonGroupView::class.java)
            .buttonGroupLayout(
                ButtonGroupLayout(
                    spacing = spacing,
                    expansionWidth = expansionWidth,
                    verticalAlignment = verticalAlignment,
                    shape = shape,
                    connectEdges = connected,
                ),
            )
            .padding(contentPadding ?: ButtonGroupDefaults.fullWidthPaddings()),
    ) {
        ButtonGroupScopeInstance.scope()
    }
}

/**
 * Two or more buttons sharing one pill, the first keeping its leading corners and the last its
 * trailing ones, so the edges between them disappear.
 *
 * # Not a port
 *
 * `androidx.wear.compose.material3` has no `SplitButtonGroup`. It was grep-checked across the whole
 * of `androidx/wear/compose` in the reference tree and the name does not occur once, and
 * `ButtonGroup.kt` contains no shape or corner code at all — its measure block never reads a child's
 * shape. The nearest thing upstream has to a name like this one is the `SplitCheckboxButton` /
 * `SplitRadioButton` / `SplitSwitchButton` family, and those are *one control with two tappable
 * halves*, not a row of independent buttons. Their shared radius,
 * `private val SPLIT_SECTIONS_SHAPE = ShapeTokens.CornerExtraSmall` (`CheckboxButton.kt:1624`,
 * `RadioButton.kt:1506`, `SwitchButton.kt:2046`), clips all four corners of **each** half, so even
 * there the outer silhouette is 4.dp rather than a large pill with collapsed inner corners. The
 * idea of a group that assigns corners by position comes from Compose *Material* —
 * `androidx.compose.material3`'s `SegmentedButton`, whose defaults hand out a leading, middle and
 * trailing connected shape — a phone library that is not in this reference tree, so it is named here
 * from reputation rather than from a file this port could check.
 *
 * So this function, [ButtonGroupDefaults.SplitSpacing], [ButtonGroupDefaults.CollapsedCornerSize]
 * and [ButtonGroupDefaults.buttonShape] are an extension written in the port's own voice, not
 * upstream behaviour. Nothing ported depends on them: the width solve, the weights, the minimums and
 * the press expansion all stand without a line of the corner code, and a plain [ButtonGroup] — left
 * on its default `connected = false` and its default [ButtonGroupDefaults.Spacing] — is what upstream
 * actually draws.
 *
 * @param modifier Modifier to be applied to the button group
 * @param expansionWidth how much buttons grow when pressed
 * @param contentPadding The spacing values to apply internally between the container and the
 *   content; null means [ButtonGroupDefaults.fullWidthPaddings].
 * @param verticalAlignment the vertical alignment of the button group's children.
 * @param shape the shape the group reads as as a whole.
 * @param content the content and properties of each button, on [ButtonGroupScope].
 */
@Tunable
fun SplitButtonGroup(
    modifier: Modifier = Modifier,
    expansionWidth: Dp = ButtonGroupDefaults.ExpansionWidth,
    contentPadding: PaddingValues? = null,
    verticalAlignment: Int = Gravity.CENTER_VERTICAL,
    shape: Shape = ButtonGroupDefaults.shape,
    content: @Tunable ButtonGroupScope.() -> Unit,
) = ButtonGroup(
    modifier = modifier,
    spacing = ButtonGroupDefaults.SplitSpacing,
    expansionWidth = expansionWidth,
    contentPadding = contentPadding,
    verticalAlignment = verticalAlignment,
    shape = shape,
    connected = true,
    content = content,
)

/**
 * Ported from androidx.wear.compose.material3.ButtonGroupScope (`material3/ButtonGroup.kt:251`).
 *
 * Upstream pushes this data down the tree as a `ParentDataModifierNode`; here it lands on the layout
 * params of [WearButtonGroupView], which is the Views equivalent of the same one-way channel. The
 * three modifiers are the same three, and the same three only.
 */
interface ButtonGroupScope {

    /**
     * [ButtonGroup] uses a ratio of all sibling item [weight]s to assign a width to each item. The
     * horizontal space is distributed using [weight] first, and this will only be changed if any item
     * would be smaller than its [minWidth].
     *
     * @param weight The main way of distributing available space. This is a relative measure, and
     *   items with no weight specified will have a default of 1f.
     */
    fun Modifier.weight(weight: Float): Modifier

    /**
     * Specifies the minimum width this item can be, in Dp. This will only be used if distributing the
     * available space results in an item falling below its minimum width. Note that this is only used
     * before animations, pressing a button may result in neighbour button(s) going below their
     * [minWidth].
     *
     * @param minWidth the minimum width. If none is specified, [ButtonGroupDefaults.MinWidth] is used.
     */
    fun Modifier.minWidth(minWidth: Dp = ButtonGroupDefaults.MinWidth): Modifier

    /**
     * Let this child take part in the press expansion: while it is held down it grows by the group's
     * `expansionWidth` and its neighbour(s) shrink by the same amount, on a spring. Without this
     * modifier a child of a [ButtonGroup] never grows — upstream's
     * `ButtonGroupParentData.DEFAULT.pressedState` is an `Animatable(0f)` that nothing ever drives,
     * and [buttonGroupPressExpandedWidths] reads those zeros straight back out.
     *
     * Ported from `Modifier.animateWidth(interactionSource)` (`material3/ButtonGroup.kt:112`, whose
     * node at `:422` collects `PressInteraction`s from that source and animates a 0..1 value with
     * `MotionScheme.fastSpatialSpec().faster(100f)` in and `slowSpatialSpec()` out). Upstream's
     * parameter is a Compose Foundation `InteractionSource`, which has no Hibari counterpart and is
     * not on this module's import list, so the argument is dropped rather than faked:
     * [WearButtonGroupView] already sees the whole touch stream, so it knows which child is held down
     * without being told, and both springs are reproduced from the theme's own constants. What is
     * genuinely lost is the ability to drive the expansion from an interaction source the child shares
     * with something else — a button activated by keyboard, by accessibility, or by another view's
     * press will not grow here, because there is no stream of interactions for it to arrive on.
     */
    fun Modifier.animateWidth(): Modifier
}

private object ButtonGroupScopeInstance : ButtonGroupScope {

    override fun Modifier.weight(weight: Float): Modifier {
        require(weight >= 0.0) { "invalid weight $weight; must be greater or equal to zero" }
        return this.thenLayoutAttribute<WearButtonGroupView.LayoutParams, Float>(uniqueKey, weight) {
                value, _ ->
            this.weight = value
        }
    }

    override fun Modifier.minWidth(minWidth: Dp): Modifier {
        require(minWidth > 0.dp) { "invalid minWidth $minWidth; must be greater than zero" }
        return this.thenLayoutAttribute<WearButtonGroupView.LayoutParams, Dp>(uniqueKey, minWidth) {
                value, _ ->
            this.minWidth = value
        }
    }

    override fun Modifier.animateWidth(): Modifier =
        this.thenUnitLayoutAttribute<WearButtonGroupView.LayoutParams>(uniqueKey) {
            animatesOnPress = true
        }
}

/**
 * Contains the default values used by [ButtonGroup].
 *
 * Upstream's object (`material3/ButtonGroup.kt:283`) holds exactly `fullWidthPaddings()`,
 * `minimumVerticalListContentPadding`, `ExpansionWidth`, `Spacing`, `MinWidth` and one private
 * percentage constant, and it contains **no shape and no corner logic of any kind** — a
 * `ButtonGroup` leaves every child's own container alone. [SplitSpacing], [shape],
 * [CollapsedCornerSize] and [buttonShape] are therefore not ports; see the note on
 * [SplitButtonGroup] for where they come from and for the fact that the rest of this file does not
 * depend on them.
 */
object ButtonGroupDefaults {

    /** Spacing between buttons. */
    val Spacing: Dp = 4.dp

    /** How much buttons grow (and neighbors shrink) when pressed. */
    val ExpansionWidth: Dp = 24.dp

    /**
     * Default for the minimum width of buttons in a ButtonGroup: upstream's `MinWidth`, which is
     * `minimumInteractiveComponentSize`, i.e. 48.dp (`InteractiveComponentSize.kt:99`).
     */
    val MinWidth: Dp = 48.dp

    /**
     * Not a port. Spacing inside a [SplitButtonGroup]: zero, so the shared edge of two neighbouring
     * children is flush and the collapsed corners meet without a seam. Upstream's `ButtonGroup` has
     * one spacing, [Spacing], and no connected variant to need a second.
     */
    val SplitSpacing: Dp = 0.dp

    /**
     * Not a port. The pill a connected group reads as, i.e. the shape whose outer corners survive on
     * the first and last child. Upstream gives [ButtonGroup] no shape at all and leaves it to each
     * [Button].
     */
    val shape: Shape = ButtonDefaults.shape

    /**
     * Not a port. Radius kept on a shared edge: the number is upstream's
     * `SPLIT_SECTIONS_SHAPE = ShapeTokens.CornerExtraSmall` (`CheckboxButton.kt:1624`,
     * `RadioButton.kt:1506`, `SwitchButton.kt:2046`), which in this module is
     * [ShapeDefaults.ExtraSmall] — 4.dp. Upstream clips the *whole* of each half of a
     * `Split*Button` with it; applying it only to the corners that face a neighbour is this port's
     * invention, and no `ButtonGroup` measurement reads it.
     */
    val CollapsedCornerSize: Dp = ShapeDefaults.ExtraSmall

    /**
     * Return the recommended padding to use as the contentPadding of a [ButtonGroup], when it takes
     * the full width of the screen.
     *
     * Upstream scales a percentage of the screen *height*, not of the width, so that the inset stays
     * the same on a round screen whichever way it is worn.
     */
    @Tunable
    fun fullWidthPaddings(): PaddingValues {
        val screenHeight = currentContext.resources.configuration.screenHeightDp
        return PaddingValues(
            horizontal = (screenHeight * buttonGroupFullWidthHorizontalPaddingPercentage / 100f).dp,
            vertical = 0.dp,
        )
    }

    /**
     * `ButtonGroupDefaults.minimumVerticalListContentPadding` (material3/ButtonGroup.kt:306-307):
     * `screenHeightFraction(LARGE_VERTICAL_CONTENT_PADDING_FRACTION)`, the content padding a
     * [ButtonGroup] asks the list for when it sits at the top or bottom edge. Upstream's consumer is
     * `TransformingLazyColumnItemScope.minimumVerticalContentPadding`, which the `wear.lazy` port has
     * no counterpart for, so this is the number without the hook — the same shape
     * [CardDefaults.minimumVerticalListContentPadding] and the button-family equivalents have.
     */
    val minimumVerticalListContentPadding: Dp
        @Tunable get() = screenHeightFraction(LARGE_VERTICAL_CONTENT_PADDING_FRACTION)

    /**
     * Not a port — see the note on [SplitButtonGroup]. Upstream has nothing like this: its
     * `ButtonGroup` measure block never touches a child's shape.
     *
     * The shape of the child at [index] of [itemCount] inside a connected group.
     *
     * The first child keeps only its leading corners, the last only its trailing ones, and a middle
     * child keeps none of them, so that adjacent buttons read as one pill; the corners on a shared
     * edge collapse to [collapsedCornerSize] rather than to zero. A group of one is left whole, and
     * so is any shape that is not corner-based, since the assignment needs four independent corners.
     *
     * @param index position of the child in the group, counting only visible children.
     * @param itemCount number of children the group placed.
     * @param shape the shape of the group as a whole.
     * @param collapsedCornerSize radius for a corner that faces a neighbour.
     * @param isRightToLeft the group mirrors its children for RTL, so the corner set is mirrored too:
     *   [CornerBasedShape] bakes "start" into the left edge at raster time, where Compose resolves it
     *   against the layout direction.
     */
    fun buttonShape(
        index: Int,
        itemCount: Int,
        shape: Shape = this.shape,
        collapsedCornerSize: Dp = CollapsedCornerSize,
        isRightToLeft: Boolean = false,
    ): Shape {
        val corners = shape as? CornerBasedShape ?: return shape
        if (itemCount <= 1) return corners
        val connected = when (index) {
            0 -> corners.copy(topEnd = collapsedCornerSize, bottomEnd = collapsedCornerSize)
            itemCount - 1 -> corners.copy(topStart = collapsedCornerSize, bottomStart = collapsedCornerSize)
            else -> CornerBasedShape(
                topStart = collapsedCornerSize,
                topEnd = collapsedCornerSize,
                bottomEnd = collapsedCornerSize,
                bottomStart = collapsedCornerSize,
            )
        }
        if (!isRightToLeft) return connected
        return CornerBasedShape(
            topStart = connected.topEnd,
            topEnd = connected.topStart,
            bottomEnd = connected.bottomStart,
            bottomStart = connected.bottomEnd,
        )
    }
}

/**
 * Hand [spec] to a [WearButtonGroupView] as one attribute: Hibari diffs by `equals`, so a retune that
 * changes nothing leaves the attribute equal and the group without a pending relayout.
 */
internal fun Modifier.buttonGroupLayout(spec: ButtonGroupLayout): Modifier =
    this.thenViewAttribute<WearButtonGroupView, ButtonGroupLayout>(uniqueKey, spec) {
        buttonGroupLayout = it
    }

/**
 * Upstream's `ComputeHelper` (`material3/ButtonGroup.kt:481`), the working row of
 * [buttonGroupComputeWidths]: the two inputs, the place the result has to go back to, and the width
 * being solved for.
 */
private data class ButtonGroupComputeHelper(
    var minWidth: Float,
    val weight: Float,
    val originalIndex: Int,
    var width: Float,
)

/**
 * Computes the base widths of the items "at rest", i.e. when there is no user interaction.
 *
 * Ported line for line from `computeWidths` (`material3/ButtonGroup.kt:497`), which upstream marks
 * `@VisibleForTesting internal` for exactly the reason this port can unit-test it: no Android types on
 * either edge. The solve is:
 *
 *  1. Every item starts at `width = minWidth`.
 *  2. `totalSpacing = spacingPx * (n - 1)` and `minSpaceNeeded = totalSpacing + Σ minWidth`, so
 *     `extraSpace = availableWidth - minSpaceNeeded` says whether the minimums fit at all.
 *  3. If the weights sum above zero, every item is then handed `weight / totalWeight` of
 *     `availableWidth - totalSpacing`, *ignoring the minimums entirely*.
 *  4. If `extraSpace < 0` the minimums are thrown away (`minWidth = 0f` on every row) and step 3's
 *     answer stands: children narrower than they asked for rather than a group wider than its parent.
 *  5. Otherwise the floors are enforced in one pass over the rows sorted by `(width - minWidth) /
 *     weight` — the slack per unit of weight — so whoever runs out of slack first is dealt with
 *     first. A zero-weight row is never taxed, because its share of the debt is
 *     `owedWidth * weight / remainingWeight` = 0, so it is sorted by hand instead: to
 *     `Float.MIN_VALUE` when it is below its minimum, i.e. served before everyone so that the rows
 *     after it fund it, and to `Float.MAX_VALUE` when it is not — which is also the only case step 3
 *     could not serve, since a total weight of 0 makes that block distribute nothing and leaves every
 *     row at its own minimum.
 *  6. Walking that order, a row below its floor is raised to it and the raise becomes `owedWidth`. A
 *     row with slack is taxed `owedWidth * its weight / remainingWeight`, where `remainingWeight`
 *     still counts the row being taxed, so the debt is shared strictly in proportion to the weights
 *     left — but never below its own floor, and whatever it cannot pay stays owed for the rows after
 *     it. That is the re-solve: one binding floor re-taxes everyone still unfloored, and the pass
 *     order means the tax lands on the slackest first.
 *  7. `require` that the debt is settled or that no weight is left to collect it from, then round
 *     each row to Int pixels and file it back at its `originalIndex`.
 *
 * Step 6's "cannot pay" is what makes the single sorted pass equivalent to the iteration it replaces:
 * a row that hits its floor mid-tax leaves the rest of the debt to the next row, so no second loop is
 * needed to re-solve.
 *
 * @param items the minimum width and weight of the items
 * @param spacingPx the spacing between items, in pixels
 * @param availableWidth the total available space.
 */
internal fun buttonGroupComputeWidths(
    items: List<Pair<Float, Float>>,
    spacingPx: Int,
    availableWidth: Int,
): IntArray {
    val helper = Array(items.size) { index ->
        val pair = items[index]
        ButtonGroupComputeHelper(pair.first, pair.second, index, pair.first)
    }
    val totalSpacing = spacingPx * (helper.size - 1)
    val minSpaceNeeded = totalSpacing + helper.map { it.width }.sum()

    val totalWeight = helper.map { it.weight }.sum()

    val extraSpace = availableWidth - minSpaceNeeded
    if (totalWeight > 0) {
        for (ix in helper.indices) {
            // Initial distribution ignores minWidth.
            helper[ix].width = (availableWidth - totalSpacing) * helper[ix].weight / totalWeight
        }
    }

    // If we don't have extra space, ensure at least all sizes are >= 0
    if (extraSpace < 0) {
        helper.forEach { it.minWidth = 0f }
    }

    // Sort them. We will have:
    // * Items with weight == 0 and less width required (usually 0)
    // * Items with weight > 0 and less width required
    // * Items with weight > 0, sorted for the order in which they may get below their
    //   minimum width
    //   as we take away space.
    // * Items with weight == 0 and enough width (This can only happen if totalWeight
    //   == 0)
    helper.sortBy {
        if (it.weight == 0f) {
            if (it.width < it.minWidth) Float.MIN_VALUE else Float.MAX_VALUE
        } else {
            (it.width - it.minWidth) / it.weight
        }
    }

    // ** Redistribute width to match constraints
    // The total weight of the items we haven't processed yet
    var remainingWeight = totalWeight
    // How much width we added to the processed items and we need to take from the remaining ones.
    var owedWidth = 0f
    for (ix in helper.indices) {
        if (remainingWeight == 0f) break

        val item = helper[ix]
        if (item.width < item.minWidth) {
            // Item is too small, make it bigger.
            owedWidth += item.minWidth - item.width
            item.width = item.minWidth
        } else {
            // We have width to give, just need to be careful not to go below minWidth.
            val needToGive = owedWidth * item.weight / remainingWeight
            val canGive = needToGive.coerceAtMost(item.width - item.minWidth)
            item.width -= canGive
            owedWidth -= canGive
        }
        remainingWeight -= item.weight
    }
    // Check that things went as expected.
    require(abs(owedWidth) < 1e-4f || abs(remainingWeight) < 1e-4f) {
        "There was a problem computing the width of the button group's items, " +
            "owedWidth = $owedWidth, remainingWeight = $remainingWeight"
    }

    // Reconstruct the original order using the 'originalIndex'
    val ret = IntArray(helper.size) { 0 }
    helper.forEach { ret[it.originalIndex] = it.width.roundToInt() }
    return ret
}

/**
 * Grows the pressed children of a [ButtonGroup] at their neighbours' expense.
 *
 * Ported from the `if (measurables.size > 1)` block of upstream's measure lambda
 * (`material3/ButtonGroup.kt:149`), which runs straight after `computeWidths` on its result; here the
 * two are separate functions so the arithmetic is testable, and [widths] is still modified in place
 * and handed back. [animatedSizes] is upstream's `configs.map { it.pressedState.value }`, one
 * continuous 0..1 press per child, so a child that never asked for [ButtonGroupScope.animateWidth]
 * contributes a zero and neither grows nor is asked to shrink. The invariants:
 *
 *  - a pressed edge child takes [expansionWidthPx] from its only neighbour, so the group width is
 *    unchanged;
 *  - a pressed *middle* child takes half from each side, and rounds its own growth up to an even
 *    number of pixels first, because an odd growth would move the middle of the button — and with it
 *    the icon and label inside — by half a pixel;
 *  - neighbours are shrunk without reference to their own `minWidth`, which is exactly what
 *    `ButtonGroupScope.minWidth` documents ("pressing a button may result in neighbour button(s)
 *    going below their minWidth"), and the loop compounds in index order, so two simultaneously
 *    pressed children can squeeze the one between them hard.
 *
 * @param widths the at-rest widths from [buttonGroupComputeWidths]; mutated in place.
 * @param animatedSizes this child's 0..1 pressed value, positionally aligned with [widths].
 * @param expansionWidthPx how much a fully pressed child grows, in pixels: upstream's `expandAmountPx`.
 */
internal fun buttonGroupPressExpandedWidths(
    widths: IntArray,
    animatedSizes: FloatArray,
    expansionWidthPx: Float,
): IntArray {
    if (widths.size > 1) {
        for (index in widths.indices) {
            val value = animatedSizes[index] * expansionWidthPx
            // How much we need to grow the pressed item.
            val growth: Int
            if (index in 1 until widths.lastIndex) {
                // index is in the middle. Ensure we keep the size of the middle item with
                // the same parity, so its content remains in place.
                growth = (value / 2).roundToInt() * 2
                widths[index - 1] -= growth / 2
                widths[index + 1] -= growth / 2
            } else {
                growth = value.roundToInt()
                if (index == 0) {
                    // index == 0, and we know there are at least 2 items.
                    widths[1] -= growth
                } else {
                    // index == widths.lastIndex, and we know there are at least 2 items.
                    widths[index - 1] -= growth
                }
            }
            // Grow the pressed item
            widths[index] += growth
        }
    }
    return widths
}
