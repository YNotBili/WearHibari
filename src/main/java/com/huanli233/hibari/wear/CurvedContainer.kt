package com.huanli233.hibari.wear

import android.graphics.Paint
import android.graphics.PointF
import android.os.Build
import android.text.TextPaint
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.wear.attributes.ArcPaddingValues
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.unit.isUnspecified
import com.huanli233.hibari.ui.unit.takeOrElse
import com.huanli233.hibari.ui.flattenToList
import com.huanli233.hibari.ui.node.MeasurePolicy
import com.huanli233.hibari.ui.node.Measurable
import com.huanli233.hibari.ui.node.Placeable
import com.huanli233.hibari.ui.text.fontVariationSettings
import com.huanli233.hibari.ui.text.toAndroidStyle
import com.huanli233.hibari.ui.unit.Constraints
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.TextUnit
import com.huanli233.hibari.wear.attributes.CurvedModifierElement
import com.huanli233.hibari.wear.view.CurvedAnchor
import com.huanli233.hibari.wear.view.CurvedLeafSlot
import com.huanli233.hibari.wear.view.WarpOffset
import kotlin.jvm.JvmInline
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ported from androidx.wear.compose.foundation.CurvedAlignment.
 *
 * `Radial` / `Angular` stay `@JvmInline value class`es over a ratio and keep upstream's ratios
 * (`foundation/CurvedAlignment.kt:29-76`: `Inner` is 1, `Outer` is 0, `Start` is 0, `End` is 1), so
 * `Custom(0.25f)` behaves identically here.
 */
interface CurvedAlignment {
    /**
     * How to lay down components when they are thinner than the container: the radial analogue of
     * `Alignment.Vertical` in a straight `Row`.
     */
    @JvmInline
    value class Radial internal constructor(internal val ratio: Float) {
        companion object {
            /** Put the child closest to the center of the container, within the available space. */
            val Inner: Radial = Radial(1f)

            /** Put the child in the middle point of the available space. */
            val Center: Radial = Radial(0.5f)

            /** Put the child farthest from the center of the container, within the available space. */
            val Outer: Radial = Radial(0f)

            /** Align the child in a custom position, 0 means [Outer], 1 means [Inner]. */
            fun Custom(ratio: Float): Radial = Radial(ratio)
        }
    }

    /**
     * How to lay down components when they have a smaller sweep than their container: the angular
     * analogue of `Alignment.Horizontal` in a straight `Column`.
     */
    @JvmInline
    value class Angular internal constructor(internal val ratio: Float) {
        companion object {
            /** Put the child at the angular start of the layout of the container. */
            val Start: Angular = Angular(0f)

            /** Put the child in the middle point of the available space. */
            val Center: Angular = Angular(0.5f)

            /** Put the child at the angular end of the layout of the container. */
            val End: Angular = Angular(1f)

            /** Align the child in a custom position, 0 means [Start], 1 means [End]. */
            fun Custom(ratio: Float): Angular = Angular(ratio)
        }
    }
}

/**
 * Ported from androidx.wear.compose.foundation.CurvedDirection.
 *
 * [resolveClockwise] takes a resolved `isLtr` boolean where upstream takes Compose's
 * `LayoutDirection` value (`foundation/CurvedDirection.kt:32-68`, mirrored here including the
 * `else -> isLtr` fall-through for `Normal`), because this port carries the direction on the child
 * tree and hands the same decision to
 * [com.huanli233.hibari.wear.attributes.ArcPaddingValues]. `curvedIsLtr` resolves the boolean from
 * `LocalLayoutDirection`, the local upstream reads (`foundation/CurvedLayout.kt:202-203`), so
 * `Normal` / `Reversed` mean here what they mean there — including for a subtree that provides its own
 * direction, and including upstream's own split personality where `clockwise()` honours layout
 * direction and `absoluteClockwise()` (`foundation/CurvedLayout.kt:179-180`) does not.
 */
interface CurvedDirection {
    /** The direction in which components are laid out on a curved row. */
    @JvmInline
    value class Angular internal constructor(internal val value: Int) {
        /** Clockwise-ness of this direction under an LTR layout, or RTL when [isLtr] is false. */
        internal fun resolveClockwise(isLtr: Boolean): Boolean = when (this) {
            Reversed -> !isLtr
            Clockwise -> true
            CounterClockwise -> false
            else -> isLtr // Normal
        }

        companion object {
            /** Clockwise for LTR, counter-clockwise for RTL. Generally for layouts at the top. */
            val Normal: Angular = Angular(0)

            /** Counter-clockwise for LTR, clockwise for RTL. Generally for layouts at the bottom. */
            val Reversed: Angular = Angular(1)

            /** Go in Clockwise direction, independently of layout direction. */
            val Clockwise: Angular = Angular(2)

            /** Go in Counter Clockwise direction, independently of layout direction. */
            val CounterClockwise: Angular = Angular(3)
        }
    }

    /** The direction in which components are laid down on a curved column. */
    @JvmInline
    value class Radial internal constructor(internal val value: Int) {
        companion object {
            /** Lay components starting farther away from the center and going inwards. */
            val OutsideIn: Radial = Radial(0)

            /** Lay components starting near the center and going outwards. */
            val InsideOut: Radial = Radial(1)
        }
    }
}

/**
 * The polar-coordinate child tree of a [CurvedLayout], plus the measure policy that runs it.
 *
 * This is why `CurvedLayout` is one [com.huanli233.hibari.ui.node.Node] and not nested hosts the way
 * `Row` / `Column` are nested `LinearLayout`s: every leaf — every `curvedComposable`, every
 * `curvedText` — is emitted as a *direct* child of the single host, in tree order, and [measure] walks
 * the recorded [CurvedChild] tree against that flat list. That keeps upstream's measurement sub-phases
 * (`initializeMeasure` / `estimateThickness` / `radialPosition` / `angularPosition`) in one pass, which
 * matters because an annulus sector cannot be expressed in [Constraints]: a nested host could never be
 * told which slice of the circle it owns.
 *
 * Deviations from upstream, mechanical rather than behavioural:
 *  - Leaves are addressed by index instead of consuming a shared `Iterator<Measurable>`, so upstream's
 *    `require(!iterator.hasNext()) { "unused measurable" }` (`foundation/CurvedLayout.kt:139-142`),
 *    which only makes sense against an iterator, has no counterpart, and a leaf whose index falls
 *    outside the host's child list simply measures nothing. Adding or removing a leaf is safe, and so
 *    is a retune that keeps the count but swaps a leaf's view class (a `curvedText` becoming a
 *    `curvedComposable` at the same index): the host rebuilds its cached measurables whenever a
 *    wrapped view or node is no longer the live one — `Renderer.kt:402-414`.
 *  - `isLookingAhead` is gone: Hibari has no lookahead measure pass.
 *  - Placement recurses through the tree instead of returning nested placement blocks.
 *  - Upstream's `CurvedModifier.background` / `radialGradientBackground` / `angularGradientBackground`
 *    are not here. They stroke an annulus sector into the *container's* `DrawScope`; in Views the
 *    container's own view is `Renderer`'s internal `LayoutNodeHost`, which paints nothing and cannot be
 *    subclassed from this module, so there is no draw pass to paint into. See [CurvedLayout]'s KDoc.
 */
internal class CurvedLayoutMeasurePolicy : MeasurePolicy {

    /**
     * The root sub-layout, a curved row for [CurvedLayout] as in upstream. Mutable because one policy
     * is remembered for the lifetime of the host and its tree is swapped on every tune, so the tree the
     * tune builds and the tree the later layout pass reads are the same object's state either way —
     * see [CurvedLayoutRoot].
     */
    var root: CurvedChild? = null

    var direction: CurvedLayoutDirection =
        curvedInitialDirection(CurvedDirection.Angular.Normal, isLtr = true)

    var anchorDegrees: Float = 270f
    var anchorType: CurvedAnchor = CurvedAnchor.Center

    override fun measure(measurables: List<Measurable>, constraints: Constraints): Placeable {
        val rootChild = root ?: return CurvedLayoutPlaceable(0, 0, null)
        // Upstream's measure block, verbatim: the `require` and the diameter are
        // `foundation/CurvedLayout.kt:121-129`, the radius `:130`, `layout(diameter, diameter)` `:164`.
        require(constraints.hasBoundedHeight || constraints.hasBoundedWidth) {
            "either height or width should be bounded"
        }
        // We take as much room as possible, the same in both dimensions, within the constraints.
        val diameter = min(
            if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE,
            if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE,
        )
        val radius = diameter / 2f
        val env = CurvedMeasureEnvironment(radius, diameter, measurables, densityOf(measurables))

        rootChild.initializeMeasure(env)
        rootChild.estimateThickness(radius)
        rootChild.radialPosition(
            parentOuterRadius = radius,
            parentThickness = rootChild.estimatedThickness,
        )

        val totalSweep = rootChild.sweepRadians
        // Apply anchor & anchorType: `foundation/CurvedLayout.kt:153-161`, centre included.
        val layoutAngleStart = anchorDegrees.curvedToRadians() -
            (if (direction.clockwise()) anchorType.ratio else 1f - anchorType.ratio) * totalSweep
        rootChild.angularPosition(layoutAngleStart, totalSweep, PointF(radius, radius))

        return CurvedLayoutPlaceable(diameter, diameter, rootChild)
    }

    private fun densityOf(measurables: List<Measurable>): Float {
        // The container has no view of its own to read a density from, so a child's context supplies it.
        val context = measurables.firstOrNull()?.context ?: return 1f
        return context.resources.displayMetrics.density
    }
}

/** The host's own placeable: [Placeable.placeAt] is what reaches every child. */
internal class CurvedLayoutPlaceable(
    width: Int,
    height: Int,
    private val root: CurvedChild?,
) : Placeable() {

    init {
        this.width = width
        this.height = height
    }

    override fun placeAt(x: Int, y: Int) {
        root?.place(x, y)
    }
}

/** What the surrounding view offers the curved phases: px conversion and the circle itself. */
internal class CurvedMeasureEnvironment(
    val radius: Float,
    val diameter: Int,
    val measurables: List<Measurable>,
    val density: Float,
)

/**
 * `Dp.toPx()` for the curved phases.
 *
 * Deviation: upstream's `Dp.toPx()` turns [Dp.Unspecified] into NaN and lets it propagate through the
 * measure, which surfaces as an unreadable size. Collapsing it to `0f` here keeps a caller that
 * forgot a bound out of the NaN path — for a `max*` argument that reads as "no upper bound given",
 * which the `maxOf` guards in [CurvedSizeWrapper] then resolve to the minimum.
 */
internal fun Dp.curvedToPx(density: Float): Float =
    if (isUnspecified) 0f else value * density

// -------------------------------------------------------------------------- directions

internal class CurvedLayoutDirection(
    internal val radial: CurvedDirection.Radial,
    internal val angular: CurvedDirection.Angular,
    internal val isLtr: Boolean,
) {
    /** Clockwise-ness of the angular direction, layout direction taken into account. */
    fun clockwise(): Boolean = angular.resolveClockwise(isLtr)

    /** Clockwise-ness ignoring layout direction, which is what drawing text along an arc needs. */
    fun absoluteClockwise(): Boolean =
        angular == CurvedDirection.Angular.Normal || angular == CurvedDirection.Angular.Clockwise

    fun outsideIn(): Boolean = radial == CurvedDirection.Radial.OutsideIn

    fun copy(
        overrideRadial: CurvedDirection.Radial? = null,
        overrideAngular: CurvedDirection.Angular? = null,
    ) = CurvedLayoutDirection(overrideRadial ?: radial, overrideAngular ?: angular, isLtr)
}

/**
 * The direction's contribution to [curvedLayoutDigest]: it changes sector order (`reverseLayout`),
 * rotation side and padding edges, none of which a child's measured size reveals.
 */
internal fun CurvedLayoutDirection.curvedDigestPart(): Int =
    (radial.value * 31 + angular.value) * 31 + if (isLtr) 1 else 2

/** `initialCurvedLayoutDirection`: the radial direction follows from the angular one. */
internal fun curvedInitialDirection(
    angular: CurvedDirection.Angular,
    isLtr: Boolean,
): CurvedLayoutDirection {
    val radialDirection = when (angular) {
        CurvedDirection.Angular.Reversed, CurvedDirection.Angular.CounterClockwise ->
            CurvedDirection.Radial.InsideOut
        else -> CurvedDirection.Radial.OutsideIn
    }
    return CurvedLayoutDirection(radialDirection, angular, isLtr)
}

// -------------------------------------------------------------------------- layout info

/**
 * The result of [CurvedChild.radialPosition], before the angle is known. Upstream's
 * `PartialLayoutInfo`; `measureRadius` is the radius at which arc length and linear distance agree.
 */
internal class CurvedPartialLayoutInfo(
    val sweepRadians: Float,
    val outerRadius: Float,
    val thickness: Float,
    val measureRadius: Float,
)

/** The annulus sector [CurvedChild.angularPosition] settled on. Upstream's `CurvedLayoutInfo`. */
internal class CurvedLayoutInfo(
    val sweepRadians: Float,
    val outerRadius: Float,
    val center: PointF,
    val startAngleRadians: Float,
)

// -------------------------------------------------------------------------- children

/**
 * Base class for children of a [CurvedLayout]: upstream's `CurvedChild`.
 *
 * The positioning calls must run in order — [initializeMeasure], [estimateThickness],
 * [radialPosition], [angularPosition], then [place] — because each reads what the previous cached.
 */
internal abstract class CurvedChild {

    /** This leaf's position in the flat leaf list, which is also its index into the measurables. */
    var leafIndex: Int = -1

    private var partial: CurvedPartialLayoutInfo? = null

    var layoutInfo: CurvedLayoutInfo? = null
        private set

    /** Estimation of our thickness; an upper bound, as upstream requires. */
    var estimatedThickness: Float = 0f
        private set

    val sweepRadians: Float
        get() = checkNotNull(partial) { "radialPosition has not run" }.sweepRadians

    val measureRadius: Float
        get() = checkNotNull(partial) { "radialPosition has not run" }.measureRadius

    open fun initializeMeasure(env: CurvedMeasureEnvironment) {}

    open fun computeParentData(): Any? = null

    fun estimateThickness(maxRadius: Float): Float =
        doEstimateThickness(maxRadius).also { estimatedThickness = it }

    abstract fun doEstimateThickness(maxRadius: Float): Float

    fun radialPosition(
        parentOuterRadius: Float,
        parentThickness: Float,
    ): CurvedPartialLayoutInfo = doRadialPosition(parentOuterRadius, parentThickness).also { partial = it }

    abstract fun doRadialPosition(
        parentOuterRadius: Float,
        parentThickness: Float,
    ): CurvedPartialLayoutInfo

    fun angularPosition(
        parentStartAngleRadians: Float,
        parentSweepRadians: Float,
        center: PointF,
    ): Float {
        val position = doAngularPosition(parentStartAngleRadians, parentSweepRadians, center)
        val info = checkNotNull(partial) { "radialPosition has not run" }
        layoutInfo = CurvedLayoutInfo(info.sweepRadians, info.outerRadius, center, position)
        return position
    }

    open fun doAngularPosition(
        parentStartAngleRadians: Float,
        parentSweepRadians: Float,
        center: PointF,
    ): Float = parentStartAngleRadians

    /** Nested / wrapped children holding the leaves, in document order. */
    open fun subChildren(): List<CurvedChild> = emptyList()

    abstract fun place(offsetX: Int, offsetY: Int)

    /** Everything taken from the DSL rather than from a child's measured size. See [curvedLayoutDigest]. */
    open fun digest(): Int = javaClass.name.hashCode()
}

/** A child that owns exactly one emitted view. */
internal abstract class CurvedLeafChild : CurvedChild() {
    /** Written by the placement pass; [CurvedLeafSlot] forwards it to the leaf's own view. */
    abstract val slot: CurvedLeafSlot
}

/** Base class for sub-layouts: upstream's `ContainerChild`. */
internal abstract class CurvedContainerChild(
    internal val direction: CurvedLayoutDirection,
    internal val reverseLayout: Boolean,
    internal val children: List<CurvedChild>,
) : CurvedChild() {

    /** Children in layout order, which [reverseLayout] flips. */
    internal val childrenInLayoutOrder: List<CurvedChild>
        get() = children.indices.map { ix ->
            children[if (reverseLayout) children.size - 1 - ix else ix]
        }

    override fun initializeMeasure(env: CurvedMeasureEnvironment) {
        for (child in children) child.initializeMeasure(env)
    }

    override fun subChildren(): List<CurvedChild> = children

    override fun doEstimateThickness(maxRadius: Float): Float =
        children.maxOfOrNull { it.estimateThickness(maxRadius) } ?: 0f

    override fun place(offsetX: Int, offsetY: Int) {
        for (child in children) child.place(offsetX, offsetY)
    }

    override fun digest(): Int =
        children.fold(javaClass.name.hashCode() * 31 + direction.curvedDigestPart()) { acc, child ->
            31 * acc + child.digest()
        }
}

/** `CurvedRowChild`: thickness is the thickest child, sweep the sum of the children's sweeps. */
internal class CurvedRowChild(
    direction: CurvedLayoutDirection,
    internal val radialAlignment: CurvedAlignment.Radial?,
    children: List<CurvedChild>,
) : CurvedContainerChild(direction, !direction.clockwise(), children) {

    override fun doRadialPosition(
        parentOuterRadius: Float,
        parentThickness: Float,
    ): CurvedPartialLayoutInfo {
        // Position children, sum angles.
        var totalSweep = 0f
        for (child in children) {
            var childRadialPosition = parentOuterRadius
            var childThickness = parentThickness
            if (radialAlignment != null) {
                childRadialPosition = parentOuterRadius -
                    radialAlignment.ratio * (parentThickness - child.estimatedThickness)
                childThickness = child.estimatedThickness
            }
            child.radialPosition(childRadialPosition, childThickness)
            totalSweep += child.sweepRadians
        }
        return CurvedPartialLayoutInfo(
            totalSweep,
            parentOuterRadius,
            parentThickness,
            parentOuterRadius - parentThickness / 2,
        )
    }

    override fun doAngularPosition(
        parentStartAngleRadians: Float,
        parentSweepRadians: Float,
        center: PointF,
    ): Float {
        val ordered = childrenInLayoutOrder
        val weights = ordered.map { (it.computeParentData() as? CurvedScopeParentData)?.weight ?: 0f }
        val sumWeights = weights.fold(0f) { acc, weight -> acc + weight }
        var unweightedSweep = 0f
        ordered.forEachIndexed { ix, node -> if (weights[ix] == 0f) unweightedSweep += node.sweepRadians }
        val extraSpace = parentSweepRadians - unweightedSweep

        var currentStartAngle = parentStartAngleRadians
        ordered.forEachIndexed { ix, node ->
            val actualSweep =
                if (weights[ix] > 0f) extraSpace * weights[ix] / sumWeights else node.sweepRadians
            node.angularPosition(currentStartAngle, actualSweep, center)
            currentStartAngle += actualSweep
        }
        return parentStartAngleRadians
    }

    override fun digest(): Int = super.digest() * 31 + (radialAlignment?.ratio?.toRawBits() ?: 0)
}

/** `CurvedBoxChild`: children overlap angularly, sweep is the biggest child's. */
internal class CurvedBoxChild(
    direction: CurvedLayoutDirection,
    internal val radialAlignment: CurvedAlignment.Radial?,
    internal val angularAlignment: CurvedAlignment.Angular?,
    children: List<CurvedChild>,
) : CurvedContainerChild(direction, reverseLayout = false, children) {

    override fun doRadialPosition(
        parentOuterRadius: Float,
        parentThickness: Float,
    ): CurvedPartialLayoutInfo {
        // Position children, take max sweep.
        var maxSweep = 0f
        for (child in children) {
            var childRadialPosition = parentOuterRadius
            var childThickness = parentThickness
            if (radialAlignment != null) {
                childRadialPosition = parentOuterRadius -
                    radialAlignment.ratio * (parentThickness - child.estimatedThickness)
                childThickness = child.estimatedThickness
            }
            child.radialPosition(childRadialPosition, childThickness)
            maxSweep = maxOf(maxSweep, child.sweepRadians)
        }
        return CurvedPartialLayoutInfo(
            maxSweep,
            parentOuterRadius,
            parentThickness,
            parentOuterRadius - parentThickness / 2,
        )
    }

    override fun doAngularPosition(
        parentStartAngleRadians: Float,
        parentSweepRadians: Float,
        center: PointF,
    ): Float {
        for (child in children) {
            var childAngularPosition = parentStartAngleRadians
            var childSweep = parentSweepRadians
            if (angularAlignment != null) {
                childAngularPosition = parentStartAngleRadians +
                    angularAlignment.ratio * (parentSweepRadians - child.sweepRadians)
                childSweep = child.sweepRadians
            }
            child.angularPosition(childAngularPosition, childSweep, center)
        }
        return parentStartAngleRadians
    }

    override fun digest(): Int =
        (super.digest() * 31 + (radialAlignment?.ratio?.toRawBits() ?: 0)) *
            31 + (angularAlignment?.ratio?.toRawBits() ?: 0)
}

/**
 * `CurvedColumnChild`: the first child is the outermost, thickness is the sum of the children's
 * thicknesses and the sweep is the biggest child's.
 */
internal class CurvedColumnChild(
    direction: CurvedLayoutDirection,
    internal val angularAlignment: CurvedAlignment.Angular?,
    children: List<CurvedChild>,
) : CurvedContainerChild(direction, !direction.outsideIn(), children) {

    override fun doEstimateThickness(maxRadius: Float): Float {
        var currentMaxRadius = maxRadius
        for (node in children) currentMaxRadius -= node.estimateThickness(currentMaxRadius)
        return maxRadius - currentMaxRadius
    }

    override fun doRadialPosition(
        parentOuterRadius: Float,
        parentThickness: Float,
    ): CurvedPartialLayoutInfo {
        // Compute the space taken by weighted children and what is left for them.
        val ordered = childrenInLayoutOrder
        val weights = ordered.map { (it.computeParentData() as? CurvedScopeParentData)?.weight ?: 0f }
        val sumWeights = weights.fold(0f) { acc, weight -> acc + weight }
        var unweightedThickness = 0f
        ordered.forEachIndexed { ix, node ->
            if (weights[ix] == 0f) unweightedThickness += node.estimatedThickness
        }
        val extraSpace = parentThickness - unweightedThickness

        var outerRadius = parentOuterRadius
        ordered.forEachIndexed { ix, node ->
            val actualThickness =
                if (weights[ix] > 0f) extraSpace * weights[ix] / sumWeights else node.estimatedThickness
            node.radialPosition(outerRadius, actualThickness)
            outerRadius -= actualThickness
        }
        var maxSweep = 0f
        for (node in ordered) maxSweep = maxOf(maxSweep, node.sweepRadians)

        return CurvedPartialLayoutInfo(
            maxSweep,
            parentOuterRadius,
            parentOuterRadius - outerRadius,
            (parentOuterRadius + outerRadius) / 2,
        )
    }

    override fun doAngularPosition(
        parentStartAngleRadians: Float,
        parentSweepRadians: Float,
        center: PointF,
    ): Float {
        for (child in children) {
            var childAngularPosition = parentStartAngleRadians
            var childSweep = parentSweepRadians
            if (angularAlignment != null) {
                childAngularPosition = parentStartAngleRadians +
                    angularAlignment.ratio * (parentSweepRadians - child.sweepRadians)
                childSweep = child.sweepRadians
            }
            child.angularPosition(childAngularPosition, childSweep, center)
        }
        return parentStartAngleRadians
    }

    override fun digest(): Int = super.digest() * 31 + (angularAlignment?.ratio?.toRawBits() ?: 0)
}

// -------------------------------------------------------------------------- leaves

/**
 * `CurvedComposableChild`: a normal rectangular Hibari subtree that is moved onto the arc and rotated
 * so that its base faces the screen centre.
 *
 * [computeAnnulusRadii], the sweep derived from the chord and the placement arithmetic are copied
 * from upstream's `CurvedComposableChild` (`foundation/CurvedComposable.kt:129-156`, `:176-190`) and
 * its `place()` (`:193-234`); [CurvedLeafSlot.rotationDegrees] is how
 * `placeWithLayer(layerBlock = { rotationZ = ... })` (`foundation/CurvedComposable.kt:220-231`) is
 * spelled on a View.
 */
internal class CurvedComposableChild(
    override val slot: CurvedLeafSlot,
    private val direction: CurvedLayoutDirection,
    val radialAlignment: CurvedAlignment.Radial,
    val rotationLocked: Boolean,
    val modifier: Modifier,
    val content: @Tunable BoxScope.() -> Unit,
) : CurvedLeafChild() {

    private var childWidth = 0
    private var childHeight = 0
    private var placeable: Placeable? = null
    private var assignedSweep = 0f

    override fun initializeMeasure(env: CurvedMeasureEnvironment) {
        // Upstream measures a curved composable against unbounded constraints: on an arc the item keeps
        // its own size, and the annulus it needs is derived from that afterwards.
        val measurable = env.measurables.getOrNull(leafIndex) ?: return
        val measured = measurable.measure(Constraints())
        placeable = measured
        childWidth = measured.width
        childHeight = measured.height
    }

    override fun doEstimateThickness(maxRadius: Float): Float {
        // The annulus needed as if the child were top aligned: an upper bound.
        val (innerRadius, outerRadius) = computeAnnulusRadii(maxRadius, 0f)
        return outerRadius - innerRadius
    }

    override fun doRadialPosition(
        parentOuterRadius: Float,
        parentThickness: Float,
    ): CurvedPartialLayoutInfo {
        val parentInnerRadius = parentOuterRadius - parentThickness
        val (myInnerRadius, myOuterRadius) = computeAnnulusRadii(
            curvedLerp(parentOuterRadius, parentInnerRadius, radialAlignment.ratio),
            radialAlignment.ratio,
        )
        // The child's width is the chord of the sector it sits in. A child wider than that sector's
        // circle has no solution: upstream lets asin() return NaN there and the whole row then lays out
        // at NaN, so the ratio is clamped, which only changes behaviour upstream cannot render anyway.
        val halfWidthRatio = (childWidth / 2f / myInnerRadius).coerceAtMost(1f)
        return CurvedPartialLayoutInfo(
            2f * asin(halfWidthRatio),
            myOuterRadius,
            myOuterRadius - myInnerRadius,
            (myInnerRadius + myOuterRadius) / 2,
        )
    }

    override fun doAngularPosition(
        parentStartAngleRadians: Float,
        parentSweepRadians: Float,
        center: PointF,
    ): Float = parentStartAngleRadians.also { assignedSweep = parentSweepRadians }

    override fun place(offsetX: Int, offsetY: Int) {
        val info = layoutInfo ?: return
        val placed = placeable ?: return

        // Upstream place(): push the top-left corner out to the outer radius so the top edge's corners
        // touch it, then walk the centre back in by half the height.
        val radiusToTopLeft = info.outerRadius
        val radiusToTopCenter = sqrt(
            (curvedPow2(radiusToTopLeft) - curvedPow2(placed.width / 2f)).coerceAtLeast(0f)
        )
        val radiusToCenter = radiusToTopCenter - placed.height / 2f
        val centerAngle = info.startAngleRadians + assignedSweep / 2f
        val childCenterX = info.center.x + radiusToCenter * cos(centerAngle)
        val childCenterY = info.center.y + radiusToCenter * sin(centerAngle)
        val positionX = (childCenterX - placed.width / 2f).roundToInt() + offsetX
        val positionY = (childCenterY - placed.height / 2f).roundToInt() + offsetY

        val clockwise = direction.absoluteClockwise()
        slot.rotationDegrees = if (rotationLocked) {
            0f
        } else {
            (centerAngle + if (clockwise) 0f else PI.toFloat()).curvedToDegrees() - 270f
        }
        placed.placeAt(positionX, positionY)
    }

    override fun digest(): Int =
        ((javaClass.name.hashCode() * 31 + direction.curvedDigestPart()) * 31 +
            radialAlignment.ratio.toRawBits()) * 31 + rotationLocked.hashCode()

    /**
     * Inner / outer radii of the annulus sector that fits this child's box, given the distance from the
     * circle's centre to a point on the box's side. Upstream's `computeAnnulusRadii`
     * (`foundation/CurvedComposable.kt:176-190`), line for line.
     *
     * @param targetRadius the distance to that point.
     * @param radiusAlpha which point: 0 the outer corner, 1 the inner corner.
     */
    private fun computeAnnulusRadii(targetRadius: Float, radiusAlpha: Float): Pair<Float, Float> {
        val topSquared = curvedPow2(childWidth / 2f)
        val radiusInBox = sqrt(curvedPow2(targetRadius) - topSquared)
        val outerRadius = sqrt(topSquared + curvedPow2(radiusInBox + radiusAlpha * childHeight))
        val innerRadius = sqrt(topSquared + curvedPow2(radiusInBox - (1 - radiusAlpha) * childHeight))
        return innerRadius to outerRadius
    }
}

/**
 * `CurvedTextChild`: an arc of text placed by describing its sector to the existing
 * [com.huanli233.hibari.wear.view.WearCurvedTextView] instead of by rotating a rectangle.
 *
 * The sweep / thickness / baseline arithmetic is upstream's (`textWidth / realMeasureRadius`,
 * `textHeight`, `parentOuterRadius - baseLinePosition` — `foundation/BasicCurvedText.kt:212-230`,
 * with the height and baseline from `CurvedTextDelegate.updateMeasures`, `:400-412`). The
 * *measurement* is necessarily repeated here: the container has to know how much angle a label takes
 * before it can place its siblings, and `WearCurvedTextView.onMeasure` reports 0 because its content
 * is drawn rather than laid out. [configurePaint] is the mirror of that view's own paint
 * configuration and has to stay in step with it.
 *
 * Upstream's `maxWidth = 2 * sqrt(textHeight * (radius - textHeight / 4))` heuristic
 * (`foundation/BasicCurvedText.kt:176-192`) is not ported: it sizes the empty `Box` node upstream
 * measures only so the text child has a compose-ui node to hang semantics on, and that node draws
 * nothing. This leaf's node is a real view whose box is the container's whole square, so there is no
 * box to size — and no semantics layer to size it for. See [CurvedLayout] for what else is unported.
 *
 * Inside a container the view's `anchorDegrees`, `anchorType`, `maxSweepDegrees` and `outerPaddingPx`
 * belong to the layout, not to the caller: the sector overwrites them on every placement pass, so
 * distance from the rim is expressed with `Modifier.curvedPadding(outer = ...)` (which upstream's
 * `PaddingWrapper` folds into `parentOuterRadius`) rather than with `CurvedTextSpec.outerPaddingPx`, and
 * `CurvedTextSpec.maxSweepDegrees` only ever acts as an upper bound on the sector's own sweep.
 */
internal class CurvedTextChild(
    override val slot: CurvedLeafSlot,
    val spec: CurvedTextSpec,
    val clockwise: Boolean,
    val modifier: Modifier,
) : CurvedLeafChild() {

    // Upstream measures and draws with a single `TextPaint` carrying anti-alias, FILL, subpixel text
    // and linear text metrics (`foundation/BasicCurvedText.kt:271-276`). This port splits that one
    // paint into a budget paint here and the view's draw paint
    // (`WearCurvedTextView.textPaint`), so the two flag sets have to stay identical:
    // `SUBPIXEL_TEXT_FLAG` changes what `measureText` returns and `LINEAR_TEXT_FLAG` changes
    // `fontMetrics`, and both feed the sweep and thickness that the view then draws into. With the
    // flags left at anti-alias only, the arc this child budgets is not the arc the view paints.
    private val paint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
    private var textWidth = 0f
    private var textHeight = 0f
    private var baseLinePosition = 0f
    private var warpOffsetPx = 0f
    private var placeable: Placeable? = null
    private var assignedSweep = 0f

    override fun initializeMeasure(env: CurvedMeasureEnvironment) {
        configurePaint(env.density)
        val fm = paint.fontMetrics
        val glyphHeight = fm.descent - fm.ascent
        val lineHeightPx =
            if (spec.style.lineHeight.isSp) spec.style.lineHeight.value * env.density else -1f
        // Extra leading is split across the two halves so the box, not the baseline, is centred —
        // the same correction WearCurvedTextView applies on draw. Upstream tests the line-height
        // sentinel with `>= 0f` (`foundation/BasicCurvedText.kt:407`), which lets a 0.sp line height
        // collapse the box to zero thickness; mirroring the view matters more here than matching that,
        // since the two must budget and draw the same height.
        val diff = if (lineHeightPx > 0f) lineHeightPx - glyphHeight else 0f
        val actualAscent = -fm.ascent + diff / 2f
        val actualDescent = fm.descent + diff / 2f
        textHeight = actualAscent + actualDescent
        baseLinePosition = if (clockwise) actualAscent else actualDescent
        // Mirrors `WearCurvedTextView.warpRadiusOffset`, which is upstream's
        // `CurvedTextStyle.WarpOffset.determineWarpRadiusOffset`
        // (`foundation/CurvedTextStyle.kt:555-569`). Zero below API 29 because upstream gates the
        // whole warping block on `SDK_INT >= Q` (`foundation/BasicCurvedText.kt:299-303`); the view
        // carries the same gate, and the two must move together or the budget stops matching the draw.
        warpOffsetPx =
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) 0f
            else
                when (spec.warpOffset) {
                    WarpOffset.None -> 0f
                    WarpOffset.HalfAscent -> -paint.ascent() / 2f
                    WarpOffset.HalfOpticalHeight -> -(paint.ascent() + paint.descent()) / 2f
                    WarpOffset.Ascent -> -paint.ascent()
                    WarpOffset.Descent -> -paint.descent()
                }
        textWidth = paint.measureText(spec.text.toString())
        // The view is laid out over the whole circle box, so its own
        // min(width, height) / 2 - outerPaddingPx lands on the sector picked below.
        val measurable = env.measurables.getOrNull(leafIndex) ?: return
        placeable = measurable.measure(Constraints.fixed(env.diameter, env.diameter))
    }

    override fun doEstimateThickness(maxRadius: Float): Float = textHeight

    override fun doRadialPosition(
        parentOuterRadius: Float,
        parentThickness: Float,
    ): CurvedPartialLayoutInfo {
        val baselineRadius = parentOuterRadius - baseLinePosition
        // The radius at which the text keeps its width: warp moves the drawn line in or out. Upstream
        // divides unconditionally and clamps nothing (`foundation/BasicCurvedText.kt:225`) — a radius
        // that reaches zero there leaves the row laying out at Infinity — so both branches below are
        // ours.
        val realMeasureRadius = baselineRadius + warpOffsetPx * (if (clockwise) 1f else -1f)
        val sweep = if (realMeasureRadius <= 0f) {
            0f
        } else {
            // The view itself truncates to CurvedTextSpec.maxSweepDegrees, so the sector it is given is
            // clamped to the same budget: otherwise siblings are spaced for text that is not drawn.
            min(textWidth / realMeasureRadius, spec.maxSweepDegrees.curvedToRadians())
        }
        return CurvedPartialLayoutInfo(sweep, parentOuterRadius, textHeight, baselineRadius)
    }

    override fun doAngularPosition(
        parentStartAngleRadians: Float,
        parentSweepRadians: Float,
        center: PointF,
    ): Float = parentStartAngleRadians.also { assignedSweep = parentSweepRadians }

    override fun place(offsetX: Int, offsetY: Int) {
        val info = layoutInfo ?: return
        val placed = placeable ?: return
        val sweep = min(info.sweepRadians, assignedSweep)
        slot.arcClockwise = clockwise
        slot.arcAnchorDegrees = (info.startAngleRadians + sweep / 2f).curvedToDegrees()
        slot.arcMaxSweepDegrees = sweep.curvedToDegrees()
        slot.arcOuterPaddingPx = (info.center.x - info.outerRadius).coerceAtLeast(0f)
        slot.arcApplied = true
        slot.rotationDegrees = 0f
        placed.placeAt(offsetX, offsetY)
    }

    override fun digest(): Int {
        var result = javaClass.name.hashCode()
        result = result * 31 + spec.text.hashCode()
        // The whole style, not just its size: family and weight change the advance, hence the sweep.
        result = result * 31 + spec.style.hashCode()
        result = result * 31 + spec.letterSpacing.hashCode()
        result = result * 31 + spec.letterSpacingCounterClockwise.hashCode()
        result = result * 31 + spec.maxSweepDegrees.hashCode()
        result = result * 31 + spec.warpOffset.hashCode()
        return result * 31 + if (clockwise) 1 else 2
    }

    /**
     * Mirrors `WearCurvedTextView.configurePaint`; anything that changes glyph advance belongs there
     * and so belongs here — including the fall-through to the style's own tracking, which is how
     * upstream folds a `TextStyle` into a `CurvedTextStyle`
     * (`foundation/CurvedTextStyle.kt:243-255`). Budgeting the sweep without it would let a label
     * built from a tracked style overflow the sector measured for it. sp is scaled by density alone
     * and em is not handled, as in the view.
     */
    private fun configurePaint(density: Float) {
        val style = spec.style
        if (style.fontSize.isSp) paint.textSize = style.fontSize.value * density
        val chosen = if (!clockwise && spec.letterSpacingCounterClockwise != TextUnit.Unspecified) {
            spec.letterSpacingCounterClockwise
        } else {
            spec.letterSpacing.takeOrElse { style.letterSpacing }
        }
        paint.letterSpacing = if (chosen == TextUnit.Unspecified) 0f else when {
            chosen.isEm -> chosen.value
            chosen.isSp && paint.textSize > 0f -> chosen.value * density / paint.textSize
            else -> 0f
        }
        if (Build.VERSION.SDK_INT >= 26) {
            paint.fontVariationSettings = style.fontWeight.fontVariationSettings(style.widthAxis)
            style.fontFeatureSettings?.let { paint.fontFeatureSettings = it }
        }
        style.fontFamily.typeface(style.fontWeight.toAndroidStyle())?.let { paint.typeface = it }
    }
}

// -------------------------------------------------------------------------- wrappers

/**
 * Base class for a child in a curved modifier chain: forwards everything. Upstream's
 * `BaseCurvedChildWrapper`.
 */
internal open class CurvedChildWrapper(val wrapped: CurvedChild) : CurvedChild() {

    override fun initializeMeasure(env: CurvedMeasureEnvironment) = wrapped.initializeMeasure(env)

    override fun computeParentData(): Any? = wrapped.computeParentData()

    override fun doEstimateThickness(maxRadius: Float): Float = wrapped.estimateThickness(maxRadius)

    override fun doRadialPosition(parentOuterRadius: Float, parentThickness: Float) =
        wrapped.radialPosition(parentOuterRadius, parentThickness)

    override fun doAngularPosition(
        parentStartAngleRadians: Float,
        parentSweepRadians: Float,
        center: PointF,
    ) = wrapped.angularPosition(parentStartAngleRadians, parentSweepRadians, center)

    override fun subChildren(): List<CurvedChild> = listOf(wrapped)

    override fun place(offsetX: Int, offsetY: Int) = wrapped.place(offsetX, offsetY)

    override fun digest(): Int = 31 * javaClass.name.hashCode() + wrapped.digest()
}

/**
 * `PaddingWrapper`: radial padding grows the sector, angular padding is an arc length turned into an
 * angle at the wrapped child's measure radius.
 */
internal class CurvedPaddingWrapper(
    wrapped: CurvedChild,
    private val direction: CurvedLayoutDirection,
    private val paddingValues: ArcPaddingValues,
) : CurvedChildWrapper(wrapped) {

    private var outerPx = 0f
    private var innerPx = 0f
    private var beforePx = 0f
    private var afterPx = 0f

    override fun initializeMeasure(env: CurvedMeasureEnvironment) {
        outerPx = paddingValues.calculateOuterPadding(direction.radial).curvedToPx(env.density)
        innerPx = paddingValues.calculateInnerPadding(direction.radial).curvedToPx(env.density)
        beforePx = paddingValues
            .calculateBeforePadding(direction.isLtr, direction.angular)
            .curvedToPx(env.density)
        afterPx = paddingValues
            .calculateAfterPadding(direction.isLtr, direction.angular)
            .curvedToPx(env.density)
        wrapped.initializeMeasure(env)
    }

    override fun doEstimateThickness(maxRadius: Float) =
        wrapped.estimateThickness(maxRadius) + outerPx + innerPx

    override fun doRadialPosition(parentOuterRadius: Float, parentThickness: Float): CurvedPartialLayoutInfo {
        val inner =
            wrapped.radialPosition(parentOuterRadius - outerPx, parentThickness - outerPx - innerPx)
        val angularPadding = (beforePx + afterPx) / inner.measureRadius
        return CurvedPartialLayoutInfo(
            inner.sweepRadians + angularPadding,
            inner.outerRadius + outerPx,
            inner.thickness + innerPx + outerPx,
            inner.measureRadius,
        )
    }

    override fun doAngularPosition(
        parentStartAngleRadians: Float,
        parentSweepRadians: Float,
        center: PointF,
    ): Float {
        val startAngularPadding = beforePx / measureRadius
        val angularPadding = (beforePx + afterPx) / measureRadius
        return wrapped.angularPosition(
            parentStartAngleRadians + startAngularPadding,
            parentSweepRadians - angularPadding,
            center,
        ) - startAngularPadding
    }

    override fun digest(): Int = super.digest() * 31 + paddingValues.hashCode()
}

/** `BaseSizeWrapper`: clamps thickness and sweep without moving the wrapped child's radius. */
internal abstract class CurvedSizeWrapper(
    wrapped: CurvedChild,
    private val minThickness: Dp,
    private val maxThickness: Dp,
) : CurvedChildWrapper(wrapped) {

    private var minThicknessPx = 0f
    private var maxThicknessPx = 0f

    override fun initializeMeasure(env: CurvedMeasureEnvironment) {
        minThicknessPx = minThickness.curvedToPx(env.density)
        maxThicknessPx = maxThickness.curvedToPx(env.density)
        wrapped.initializeMeasure(env)
    }

    /**
     * Upstream's `coerceIn(minThicknessPx, maxThicknessPx)` throws an [IllegalArgumentException] when a
     * caller writes the bounds the wrong way round and takes the layout down with it; crossing over to
     * the minimum instead keeps a mistuned value local to one child.
     */
    private fun thicknessBounds(): Pair<Float, Float> =
        minThicknessPx to maxOf(minThicknessPx, maxThicknessPx)

    override fun doEstimateThickness(maxRadius: Float): Float {
        val (min, max) = thicknessBounds()
        return wrapped.estimateThickness(maxRadius).coerceIn(min, max)
    }

    protected abstract fun calculateSweepRadians(sweepRadians: Float, measureRadius: Float): Float

    override fun doAngularPosition(
        parentStartAngleRadians: Float,
        parentSweepRadians: Float,
        center: PointF,
    ): Float {
        wrapped.angularPosition(
            parentStartAngleRadians,
            calculateSweepRadians(parentSweepRadians, measureRadius),
            center,
        )
        return parentStartAngleRadians
    }

    override fun doRadialPosition(parentOuterRadius: Float, parentThickness: Float): CurvedPartialLayoutInfo {
        val inner = wrapped.radialPosition(parentOuterRadius, estimatedThickness)
        return CurvedPartialLayoutInfo(
            calculateSweepRadians(inner.sweepRadians, inner.measureRadius),
            parentOuterRadius,
            estimatedThickness,
            inner.measureRadius + inner.outerRadius - parentOuterRadius,
        )
    }

    override fun digest(): Int =
        (super.digest() * 31 + minThickness.hashCode()) * 31 + maxThickness.hashCode()
}

/** `SweepSizeWrapper`: the sweep is bounded directly, in degrees. */
internal class CurvedSweepSizeWrapper(
    wrapped: CurvedChild,
    private val minSweepDegrees: Float,
    private val maxSweepDegrees: Float,
    minThickness: Dp,
    maxThickness: Dp,
) : CurvedSizeWrapper(wrapped, minThickness, maxThickness) {

    override fun calculateSweepRadians(sweepRadians: Float, measureRadius: Float): Float {
        val min = minSweepDegrees.curvedToRadians()
        // Upstream's bare `coerceIn` throws on reversed bounds (`foundation/CurvedSize.kt:116`);
        // this repeats the crossed-bounds guard from [thicknessBounds] rather than the throw.
        val max = maxOf(min, maxSweepDegrees.curvedToRadians())
        return sweepRadians.coerceIn(min, max)
    }

    override fun digest(): Int =
        (super.digest() * 31 + minSweepDegrees.hashCode()) * 31 + maxSweepDegrees.hashCode()
}

/** `AngularWidthSizeWrapper`: the sweep is bounded by an arc length read at the measure radius. */
internal class CurvedAngularWidthSizeWrapper(
    wrapped: CurvedChild,
    private val minAngularWidth: Dp,
    private val maxAngularWidth: Dp,
    minThickness: Dp,
    maxThickness: Dp,
) : CurvedSizeWrapper(wrapped, minThickness, maxThickness) {

    private var minAngularWidthPx = 0f
    private var maxAngularWidthPx = 0f

    override fun initializeMeasure(env: CurvedMeasureEnvironment) {
        minAngularWidthPx = minAngularWidth.curvedToPx(env.density)
        maxAngularWidthPx = maxAngularWidth.curvedToPx(env.density)
        super.initializeMeasure(env)
    }

    override fun calculateSweepRadians(sweepRadians: Float, measureRadius: Float): Float {
        // Upstream divides unconditionally (`foundation/CurvedSize.kt:140`), so a radius that reaches
        // zero sends the bound to infinity. The bail and the `maxOf` crossed-bounds guard are both
        // this port's, same reasoning as [thicknessBounds].
        if (measureRadius <= 0f) return sweepRadians
        val min = minAngularWidthPx / measureRadius
        return sweepRadians.coerceIn(min, maxOf(min, maxAngularWidthPx / measureRadius))
    }

    override fun digest(): Int =
        (super.digest() * 31 + minAngularWidth.hashCode()) * 31 + maxAngularWidth.hashCode()
}

/** `ParentDataWrapper`: how [curvedWeight] reaches the containers' distribution of leftover space. */
internal class CurvedParentDataWrapper(
    wrapped: CurvedChild,
    private val modifyParentData: (Any?) -> Any?,
) : CurvedChildWrapper(wrapped) {

    override fun computeParentData(): Any? = modifyParentData(wrapped.computeParentData())

    override fun digest(): Int = super.digest() * 31 + computeParentData().hashCode()
}

/** `CurvedScopeParentData`: the only parent data the curved containers read. */
internal data class CurvedScopeParentData(var weight: Float = 0f)

// -------------------------------------------------------------------------- helpers

/**
 * Builds the wrapper chain for one child from the curved elements on [modifier], outermost first —
 * the same `foldRight` order upstream's `CurvedModifier.wrap` uses.
 */
internal fun curvedWrappedWith(
    child: CurvedChild,
    modifier: Modifier,
    direction: CurvedLayoutDirection,
): CurvedChild =
    modifier.flattenToList().filterIsInstance<CurvedModifierElement>()
        .foldRight(child) { element, acc -> element.curvedWrap(acc, direction) }

/**
 * The flat leaf list in emission order, each leaf told its index.
 *
 * Emission ([CurvedLayoutContent]) walks this same list, so a leaf's index is its measurable index in
 * the host for every structural change that reaches the host's child list; see the caveat listed on
 * [CurvedLayoutMeasurePolicy].
 */
internal fun curvedCollectLeaves(root: CurvedChild): List<CurvedLeafChild> {
    val out = ArrayList<CurvedLeafChild>()
    root.curvedCollectLeavesInto(out)
    return out
}

private fun CurvedChild.curvedCollectLeavesInto(out: MutableList<CurvedLeafChild>) {
    if (this is CurvedLeafChild) {
        leafIndex = out.size
        out += this
    } else {
        for (child in subChildren()) child.curvedCollectLeavesInto(out)
    }
}

private fun curvedLerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

private fun curvedPow2(x: Float): Float = x * x

internal fun Float.curvedToRadians(): Float = this * PI.toFloat() / 180f

internal fun Float.curvedToDegrees(): Float = this * 180f / PI.toFloat()

/**
 * A digest of every input the container took from the DSL rather than from a child's measured size.
 *
 * A tune reaches a host view only through its attributes — [com.huanli233.hibari.runtime.Renderer]
 * applies the ones that changed when it patches a node (`Patcher.kt:110-116`) — and curved parameters
 * are not attributes of anything, so [CurvedLayout] publishes this number as one: changed means its
 * applier asks for the placement pass again against the fresh tree. The tree's own contributions come
 * from [CurvedChild.digest], which already carries every direction. Leaf-level changes this digest
 * deliberately leaves out — a colour, a `curvedComposable`'s own content — are covered by
 * [CurvedLayoutContent], which relayouts whenever it binds a slot the placement pass has not filled.
 * In practice a retune that reaches the leaves asks for a pass anyway, since a leaf's slot is a fresh
 * object per tune and so never compares equal; the digest is what carries the geometry changes.
 */
internal fun curvedLayoutDigest(
    root: CurvedChild,
    anchorDegrees: Float,
    anchorType: CurvedAnchor,
): Int {
    var result = root.digest()
    result = result * 31 + anchorDegrees.hashCode()
    return result * 31 + anchorType.ratio.toRawBits()
}
