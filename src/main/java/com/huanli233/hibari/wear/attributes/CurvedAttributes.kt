package com.huanli233.hibari.wear.attributes

import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.wear.CurvedAngularWidthSizeWrapper
import com.huanli233.hibari.wear.CurvedChild
import com.huanli233.hibari.wear.CurvedDirection
import com.huanli233.hibari.wear.CurvedLayoutDirection
import com.huanli233.hibari.wear.CurvedPaddingWrapper
import com.huanli233.hibari.wear.CurvedParentDataWrapper
import com.huanli233.hibari.wear.CurvedScopeParentData
import com.huanli233.hibari.wear.CurvedSweepSizeWrapper

/**
 * The curved half of `Modifier` for [com.huanli233.hibari.wear.CurvedLayout]: angular / radial padding,
 * angular / radial size and weight.
 *
 * Upstream keeps these on its own `CurvedModifier` type, whose elements *wrap* the curved child they
 * apply to (its `CurvedChild`). Hibari has a single [Modifier] chain per node, so curved elements ride
 * along with the view attributes and are pulled back out of the chain when the container builds its
 * tree, in chain order, exactly like upstream's `CurvedModifier.wrap`.
 *
 * That merge is visible at call sites and worth spelling out: `Modifier.padding(...)` (foundation)
 * shrinks the leaf's rectangular box, while [curvedPadding] moves the leaf's annulus sector. Both are
 * legal on the same child and they do different things.
 *
 * Not ported, with reasons:
 *  - `CurvedModifier.background` / `radialGradientBackground` / `angularGradientBackground`
 *    (upstream CurvedDraw.kt): they stroke the child's sector into the *container's* `DrawScope`
 *    before the child's content. Views give the container no such layer — it hosts child
 *    [android.view.View]s and paints nothing itself.
 *  - `CurvedSemantics.kt`: no semantics layer to merge `contentDescription` / `traversalIndex` into.
 *  - `CurvedPadding`, `CurvedLengthSize`, `curvedAreaFill` and the `*AsTextUnit` padding variants are
 *    not in the pinned reference tree at all (its padding file exposes `ArcPaddingValues` plus four
 *    `CurvedModifier.padding` overloads), so they are not invented here.
 */
internal interface CurvedModifierElement {
    fun curvedWrap(child: CurvedChild, direction: CurvedLayoutDirection): CurvedChild
}

/**
 * `ArcPaddingValues` from androidx.wear.compose.foundation.CurvedPadding: padding for each edge of an
 * annulus sector, where `outer` / `inner` are radial and `before` / `after` follow the direction the
 * content would be laid out in if it were drawn clockwise.
 *
 * Upstream asks for a Compose `LayoutDirection` in the before / after calculations; Hibari has none, so
 * the resolved `isLtr` boolean is handed in instead (the values themselves are direction-independent in
 * the default implementation, as upstream).
 */
interface ArcPaddingValues {
    /** Padding in the outward direction from the center of the container. */
    fun calculateOuterPadding(radialDirection: CurvedDirection.Radial): Dp

    /** Padding in the inwards direction towards the center of the container. */
    fun calculateInnerPadding(radialDirection: CurvedDirection.Radial): Dp

    /** Padding before the content as drawn clockwise: the edge with the smallest angle. */
    fun calculateBeforePadding(isLtr: Boolean, angularDirection: CurvedDirection.Angular): Dp

    /** Padding after the content as drawn clockwise: the edge with the biggest angle. */
    fun calculateAfterPadding(isLtr: Boolean, angularDirection: CurvedDirection.Angular): Dp
}

/** Padding on each edge of the content in dp, applied to a concrete edge regardless of direction. */
fun ArcPaddingValues(
    outer: Dp = 0.dp,
    inner: Dp = 0.dp,
    before: Dp = 0.dp,
    after: Dp = 0.dp,
): ArcPaddingValues = ArcPaddingValuesImpl(outer, inner, before, after)

/** [all] dp of additional space along each edge of the content. */
fun ArcPaddingValues(all: Dp): ArcPaddingValues = ArcPaddingValuesImpl(all, all, all, all)

/**
 * [radial] dp towards and away from the centre, and [angular] dp before and after the content — the
 * angular part is measured at the content's own measure radius.
 */
fun ArcPaddingValues(radial: Dp = 0.dp, angular: Dp = 0.dp): ArcPaddingValues =
    ArcPaddingValuesImpl(radial, radial, angular, angular)

private class ArcPaddingValuesImpl(
    val outer: Dp,
    val inner: Dp,
    val before: Dp,
    val after: Dp,
) : ArcPaddingValues {

    override fun calculateOuterPadding(radialDirection: CurvedDirection.Radial): Dp = outer

    override fun calculateInnerPadding(radialDirection: CurvedDirection.Radial): Dp = inner

    override fun calculateBeforePadding(
        isLtr: Boolean,
        angularDirection: CurvedDirection.Angular,
    ): Dp = before

    override fun calculateAfterPadding(
        isLtr: Boolean,
        angularDirection: CurvedDirection.Angular,
    ): Dp = after

    override fun equals(other: Any?): Boolean =
        other is ArcPaddingValuesImpl &&
            outer == other.outer &&
            inner == other.inner &&
            before == other.before &&
            after == other.after

    override fun hashCode(): Int =
        ((outer.hashCode() * 31 + inner.hashCode()) * 31 + before.hashCode()) * 31 + after.hashCode()

    override fun toString(): String =
        "ArcPaddingValues(outer=$outer, inner=$inner, before=$before, after=$after)"
}

// ------------------------------------------------------------------ padding

/** [curvedPadding] with an explicit [ArcPaddingValues]. */
fun Modifier.curvedPadding(paddingValues: ArcPaddingValues): Modifier =
    this.then(CurvedPaddingElement(paddingValues))

/**
 * Space along the content's edges, in dp. `before` / `after` are applied as if the content were drawn
 * clockwise, at the midpoint of the content when converting dp to angle.
 */
fun Modifier.curvedPadding(outer: Dp, inner: Dp, before: Dp, after: Dp): Modifier =
    curvedPadding(ArcPaddingValues(outer, inner, before, after))

/** [radial] dp on the outer and inner edges, [angular] dp before and after the content. */
fun Modifier.curvedPadding(radial: Dp = 0.dp, angular: Dp = 0.dp): Modifier =
    curvedPadding(radial, radial, angular, angular)

/** [all] dp of additional space around the content. */
fun Modifier.curvedPadding(all: Dp = 0.dp): Modifier = curvedPadding(all, all, all, all)

private class CurvedPaddingElement(private val values: ArcPaddingValues) :
    Modifier.Element,
    CurvedModifierElement {
    override fun curvedWrap(child: CurvedChild, direction: CurvedLayoutDirection): CurvedChild =
        CurvedPaddingWrapper(child, direction, values)
}

// ------------------------------------------------------------------ size

/** `curvedSizeIn` with both sweep bounds and both thickness bounds pinned to one value. */
fun Modifier.curvedSize(sweepDegrees: Float, thickness: Dp): Modifier =
    curvedSizeIn(
        minSweepDegrees = sweepDegrees,
        maxSweepDegrees = sweepDegrees,
        minThickness = thickness,
        maxThickness = thickness,
    )

/**
 * Restrict the content between the given bounds: [minSweepDegrees] / [maxSweepDegrees] in degrees of
 * arc, [minThickness] / [maxThickness] as radial size in dp.
 */
fun Modifier.curvedSizeIn(
    minSweepDegrees: Float = 0f,
    maxSweepDegrees: Float = 360f,
    minThickness: Dp = 0.dp,
    maxThickness: Dp = Dp.Infinity,
): Modifier = this.then(CurvedSweepSizeElement(minSweepDegrees, maxSweepDegrees, minThickness, maxThickness))

/** The sweep (angular size) of the content, in degrees. */
fun Modifier.curvedAngularSize(sweepDegrees: Float): Modifier =
    curvedSizeIn(minSweepDegrees = sweepDegrees, maxSweepDegrees = sweepDegrees)

/**
 * The sweep (arc length) of the content in dp, measured at the centre of the item — for a
 * `curvedText` child at the text baseline, as upstream notes.
 */
fun Modifier.curvedAngularSizeDp(angularWidth: Dp): Modifier =
    this.then(CurvedAngularWidthElement(angularWidth, angularWidth, 0.dp, Dp.Infinity))

/** The radial size (thickness) of the content, in dp. */
fun Modifier.curvedRadialSize(thickness: Dp): Modifier =
    curvedSizeIn(minThickness = thickness, maxThickness = thickness)

/**
 * Name-level convenience for [curvedAngularSizeDp]. Curved space has no width: this is arc length at
 * the item's centre, and [curvedHeight] is the radial thickness.
 */
fun Modifier.curvedWidth(width: Dp): Modifier = curvedAngularSizeDp(width)

/** Name-level convenience for [curvedRadialSize]; see [curvedWidth]. */
fun Modifier.curvedHeight(height: Dp): Modifier = curvedRadialSize(height)

private class CurvedSweepSizeElement(
    private val minSweepDegrees: Float,
    private val maxSweepDegrees: Float,
    private val minThickness: Dp,
    private val maxThickness: Dp,
) : Modifier.Element, CurvedModifierElement {
    override fun curvedWrap(child: CurvedChild, direction: CurvedLayoutDirection): CurvedChild =
        CurvedSweepSizeWrapper(child, minSweepDegrees, maxSweepDegrees, minThickness, maxThickness)
}

private class CurvedAngularWidthElement(
    private val minAngularWidth: Dp,
    private val maxAngularWidth: Dp,
    private val minThickness: Dp,
    private val maxThickness: Dp,
) : Modifier.Element, CurvedModifierElement {
    override fun curvedWrap(child: CurvedChild, direction: CurvedLayoutDirection): CurvedChild =
        CurvedAngularWidthSizeWrapper(child, minAngularWidth, maxAngularWidth, minThickness, maxThickness)
}

// ------------------------------------------------------------------ weight

/**
 * Size the element proportionally to its [weight] relative to its weighted siblings — the remaining
 * sweep in a curved row, the remaining thickness in a curved column. The parent measures the
 * unweighted children first and divides what is left over the sum of the weights.
 *
 * Upstream defers its positivity check into the parent-data lambda, so a bad weight only fails during
 * measure; here it fails at the call site that is wrong.
 */
fun Modifier.curvedWeight(weight: Float): Modifier {
    require(weight > 0f) { "Weights must be positive." }
    return this.then(CurvedWeightElement(weight))
}

private class CurvedWeightElement(private val weight: Float) :
    Modifier.Element,
    CurvedModifierElement {
    override fun curvedWrap(child: CurvedChild, direction: CurvedLayoutDirection): CurvedChild =
        CurvedParentDataWrapper(child) { parentData ->
            (parentData as? CurvedScopeParentData ?: CurvedScopeParentData())
                .also { it.weight = weight }
        }
}
