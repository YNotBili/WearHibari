package com.huanli233.hibari.wear

import android.view.View
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.foundation.BoxScopeInstance
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.layout.LayoutScopeMarker
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.locals.LocalLayoutDirection
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.LayoutDirection
import com.huanli233.hibari.ui.unit.TextUnit
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.view.CurvedAnchor
import com.huanli233.hibari.wear.view.CurvedLeafSlot
import com.huanli233.hibari.wear.view.CurvedTextOverflow
import com.huanli233.hibari.wear.view.WarpOffset
import com.huanli233.hibari.wear.view.WearCurvedLayoutTextView
import com.huanli233.hibari.wear.view.WearCurvedLeafView
import com.huanli233.hibari.wear.view.WearCurvedTextView

/**
 * Ported from androidx.wear.compose.foundation.CurvedLayout.
 *
 * The angle arithmetic — degrees to radians, `anchor.toRadians() - ratio * totalSweep`, the diameter
 * taken from the bounded constraints, `PointF(radius, radius)` as the circle's centre — is upstream's,
 * verbatim (`foundation/CurvedLayout.kt:121-130`, `:153-161`, `:164`); see [CurvedLayoutMeasurePolicy]
 * for how the tree it runs on is built.
 *
 * How this differs from upstream, and why:
 *  - One host, one measure pass. Every leaf below a [CurvedLayout] — including leaves inside nested
 *    `curvedRow` / `curvedBox` / `curvedColumn` — is emitted as a direct child of a single
 *    [com.huanli233.hibari.ui.node.Node], exactly like upstream's single `Layout` whose
 *    `SubComposition` flattens the whole tree (`foundation/CurvedLayout.kt:113-120`, recursing
 *    through `ContainerChild.SubComposition`, `foundation/CurvedContainer.kt:57-63`). Nested hosts
 *    are not an option: [com.huanli233.hibari.ui.unit.Constraints] carries only pixel ranges, so a
 *    nested host could never be told which sector of the circle it owns.
 *  - Parameters travel on the ordinary [Modifier] chain, not on a separate `CurvedModifier` type.
 *    See [com.huanli233.hibari.wear.attributes.CurvedModifierElement] and `CurvedModifier.wrap`
 *    (`foundation/CurvedModifier.kt:69-71`), which this order copies.
 *  - Children are real Views, so they are placed, not redrawn. A `curvedComposable` child is a
 *    rectangle moved onto its sector and rotated about its centre (upstream's `place()` + `rotationZ`
 *    with `TransformOrigin.Center`, `foundation/CurvedComposable.kt:218-231`); a [curvedText] child
 *    is a [com.huanli233.hibari.wear.view.WearCurvedTextView] told which sector to curve itself
 *    along — upstream draws glyphs from the container's own `DrawScope`
 *    (`foundation/BasicCurvedText.kt:243-253`), so text is the one thing a View child cannot do
 *    without being the whole circle. Hence a `curvedText` leaf is laid out over the container's full
 *    box (that is how the view computes the arc from `min(width, height) / 2`), and a rectangular
 *    `Modifier` on such a leaf — `size`, `padding`, a background — applies to that box rather than to
 *    the label.
 *  - `androidx.wear.widget.ArcLayout` (the class that really is on this module's classpath;
 *    `androidx.wear.widget.CurvedLayout` does not exist in `androidx.wear:wear:1.4.0`) was
 *    considered and rejected as the backend: it takes one layout-wide `thickness`, one
 *    `maxAngleDegrees` and per-child `angleStart` / `angleEnd`, so it can express a single flat
 *    curved row of rotated children and nothing else upstream needs — no nested `curvedBox` /
 *    `curvedColumn`, no weighted sweep distribution, no annulus fitted per child. For the same reason
 *    `androidx.wear.widget.CurvedTextView` is not used for [curvedText]: it renders one label at one
 *    fixed anchor and has no way to be told a sector by a container.
 *  - Not ported: upstream's `CurvedModifier.background` family (`foundation/CurvedDraw.kt:35-115`,
 *    whose shared internal implementation runs on to `:135`) —
 *    they stroke a sector into the container's `DrawScope`, and the view a node with a measure policy
 *    gets is `Renderer`'s own internal `LayoutNodeHost` (`Renderer.kt:88-94`), which paints nothing
 *    and cannot be subclassed from this module; `CurvedSemantics.kt` (no semantics layer); and
 *    lookahead measure (`foundation/CurvedLayout.kt:136`, `foundation/CurvedComposable.kt:122-124`).
 *  - [modifier] on the layout itself is weaker than upstream's, for a reason that lives in the
 *    runtime: `Renderer.render` applies a node's attributes only to the view it builds from a
 *    `viewClass`, and returns a bare `LayoutNodeHost` for a node that carries a measure policy
 *    without applying anything (`Renderer.kt:88-94`). A layout attribute on this call — `size`,
 *    `width`, `padding` — therefore never reaches the host's `LayoutParams`, and those `LayoutParams`
 *    are the only route by which a caller's size would come back as the constraints the host reads its
 *    diameter from. The host itself never consults them: it measures to its own placeable
 *    (`Renderer.kt:397-399`). Wrapping the host in one plain `FrameLayout` that carries [modifier] —
 *    what `Box` does — is the fix, and it belongs to the integration pass rather than here.
 *
 * @param anchor the angle children are laid out relative to, in degrees: 0 is 3 o'clock, 90 is 6
 *   o'clock, and the default 270 is the top of the screen (equivalent to
 *   [WearCurvedTextView.TopAnchor]).
 * @param anchorType where the content sits relative to [anchor]; upstream's `AnchorType`, which this
 *   module already spells as [CurvedAnchor] with the same 0 / 0.5 / 1 ratios.
 * @param radialAlignment radial alignment for children thinner than this layout; when null, children
 *   keep their own alignment.
 * @param angularDirection the direction children run along the arc; see [CurvedDirection.Angular].
 */
@Tunable
fun CurvedLayout(
    modifier: Modifier = Modifier,
    anchor: Float = 270f,
    anchorType: CurvedAnchor = CurvedAnchor.Center,
    radialAlignment: CurvedAlignment.Radial? = null,
    angularDirection: CurvedDirection.Angular = CurvedDirection.Angular.Normal,
    content: CurvedLayoutScope.() -> Unit,
) {
    val direction = curvedInitialDirection(angularDirection, curvedIsLtr())
    val children = curvedScopeChildren(direction, content)
    CurvedLayoutRoot(
        modifier = modifier,
        root = CurvedRowChild(direction, radialAlignment, children),
        direction = direction,
        anchor = anchor,
        anchorType = anchorType,
    )
}

/**
 * Ported from `CurvedScope.curvedRow`, promoted to a component so it can be a root.
 *
 * Thickness is the thickest child and the total angle is the sum of the children's angles, as upstream.
 * [anchor] / [anchorType] / [angularDirection] exist here because a root has no enclosing container to
 * inherit them from.
 */
@Tunable
fun CurvedRow(
    modifier: Modifier = Modifier,
    anchor: Float = 270f,
    anchorType: CurvedAnchor = CurvedAnchor.Center,
    radialAlignment: CurvedAlignment.Radial? = null,
    angularDirection: CurvedDirection.Angular = CurvedDirection.Angular.Normal,
    content: CurvedLayoutScope.() -> Unit,
) {
    val direction = curvedInitialDirection(angularDirection, curvedIsLtr())
    CurvedLayoutRoot(
        modifier = modifier,
        root = CurvedRowChild(direction, radialAlignment, curvedScopeChildren(direction, content)),
        direction = direction,
        anchor = anchor,
        anchorType = anchorType,
    )
}

/**
 * Ported from `CurvedScope.curvedBox`, promoted to a component so it can be a root.
 *
 * Children sit on top of each other and on the arc: the thickness is the thickest child's and the angle
 * is the biggest child's, as upstream.
 */
@Tunable
fun CurvedBox(
    modifier: Modifier = Modifier,
    anchor: Float = 270f,
    anchorType: CurvedAnchor = CurvedAnchor.Center,
    radialAlignment: CurvedAlignment.Radial? = null,
    angularAlignment: CurvedAlignment.Angular? = null,
    angularDirection: CurvedDirection.Angular = CurvedDirection.Angular.Normal,
    content: CurvedLayoutScope.() -> Unit,
) {
    val direction = curvedInitialDirection(angularDirection, curvedIsLtr())
    CurvedLayoutRoot(
        modifier = modifier,
        root = CurvedBoxChild(
            direction,
            radialAlignment,
            angularAlignment,
            curvedScopeChildren(direction, content),
        ),
        direction = direction,
        anchor = anchor,
        anchorType = anchorType,
    )
}

/**
 * Ported from `CurvedScope.curvedColumn`, promoted to a component so it can be a root.
 *
 * The first child is the outermost, thickness is the sum of the children's thicknesses and the angle is
 * the biggest child's, as upstream.
 */
@Tunable
fun CurvedColumn(
    modifier: Modifier = Modifier,
    anchor: Float = 270f,
    anchorType: CurvedAnchor = CurvedAnchor.Center,
    radialDirection: CurvedDirection.Radial? = null,
    angularAlignment: CurvedAlignment.Angular? = null,
    angularDirection: CurvedDirection.Angular = CurvedDirection.Angular.Normal,
    content: CurvedLayoutScope.() -> Unit,
) {
    val direction = curvedInitialDirection(angularDirection, curvedIsLtr())
        .copy(overrideRadial = radialDirection)
    CurvedLayoutRoot(
        modifier = modifier,
        root = CurvedColumnChild(direction, angularAlignment, curvedScopeChildren(direction, content)),
        direction = direction,
        anchor = anchor,
        anchorType = anchorType,
    )
}

/** Runs [content] against a fresh scope of [direction] and returns the children it recorded. */
private fun curvedScopeChildren(
    direction: CurvedLayoutDirection,
    content: CurvedLayoutScope.() -> Unit,
): List<CurvedChild> = CurvedLayoutScopeImpl(direction).apply(content).children

/**
 * The one emission point: remember the policy, hand it the tree built this tune, and emit the leaves in
 * the order the policy addresses them by index.
 */
@Tunable
internal fun CurvedLayoutRoot(
    modifier: Modifier,
    root: CurvedContainerChild,
    direction: CurvedLayoutDirection,
    anchor: Float,
    anchorType: CurvedAnchor,
) {
    val leaves = curvedCollectLeaves(root)

    // One policy is remembered for the host's lifetime and its tree is swapped on every tune: the host
    // adopts a node's measure policy only when the patcher hands it a new node (`Patcher.kt:113`), so
    // the object the tune writes the tree into has to be the object the layout pass reads it from.
    val policy = remember { CurvedLayoutMeasurePolicy() }
    policy.root = root
    policy.direction = direction
    policy.anchorDegrees = anchor
    policy.anchorType = anchorType

    Node(
        modifier = modifier.thenViewAttribute<View, Int>(
            uniqueKey,
            curvedLayoutDigest(root, anchor, anchorType),
        ) {
            // The digest changes whenever an input the measure pass reads from the DSL changes, and
            // requestLayout is what makes the container place its children against the new tree. It
            // runs on a patched host only — `Renderer.render` applies nothing to a measure-policy node
            // (`Renderer.kt:88-94`), so the first pass comes from the host's own `node` setter.
            requestLayout()
        },
        measurePolicy = policy,
        content = { CurvedLayoutContent(leaves) },
    )
}

/**
 * Emits one view per leaf, in leaf order.
 *
 * A `curvedComposable` leaf becomes a [WearCurvedLeafView] (the box that receives the rotation), a
 * `curvedText` leaf becomes a [WearCurvedLayoutTextView]. Both are handed their [CurvedLeafSlot], which
 * the measure pass writes into and the view pushes onto itself.
 *
 * A slot is rebuilt with its leaf on every tune, so binding one that the placement pass has not yet
 * filled has to schedule a layout: `WearCurvedLeafView` takes its rotation from the slot and
 * `WearCurvedLayoutTextView` takes its whole sector from it, and without a placement pass the rebind
 * would leave a rect leaf sitting upright at 0 degrees and a text leaf back at the spec's own anchor.
 * The digest on the host is not enough for this — it ignores a leaf's colour and a
 * `curvedComposable`'s own content, and those are exactly the changes that retune a leaf without
 * retuning the geometry.
 */
@Tunable
internal fun CurvedLayoutContent(leaves: List<CurvedLeafChild>) {
    for (leaf in leaves) {
        when (leaf) {
            is CurvedComposableChild -> {
                val content = leaf.content
                Node(
                    modifier = leaf.modifier
                        .viewClass(WearCurvedLeafView::class.java)
                        .thenViewAttribute<WearCurvedLeafView, CurvedLeafSlot>(uniqueKey, leaf.slot) {
                            slot = it
                            if (!it.arcApplied) requestLayout()
                        },
                    content = { BoxScopeInstance.content() },
                )
            }

            is CurvedTextChild -> {
                // Resolved here rather than in the DSL member: the scope functions are plain Kotlin, and
                // contentColorFor is a @Tunable read. Same rule as the standalone CurvedText component,
                // which resolves against a transparent background.
                val resolved = contentColorFor(Color.Transparent, leaf.spec.color)
                Node(
                    modifier = leaf.modifier
                        .viewClass(WearCurvedLayoutTextView::class.java)
                        .curvedText(leaf.spec.copy(color = resolved))
                        .thenViewAttribute<WearCurvedLayoutTextView, CurvedLeafSlot>(uniqueKey, leaf.slot) {
                            slot = it
                            if (!it.arcApplied) requestLayout()
                        },
                )
            }
        }
    }
}

/**
 * The DSL of a [CurvedLayout]: upstream's `CurvedScope`.
 *
 * Named `CurvedLayoutScope` because `CurvedScope` is too generic a name to claim in a package this
 * module is filling in from several ports at once; the members keep upstream's lowercase names, so
 * `curvedRow { }` / `curvedBox { }` / `curvedColumn { }` read as they do upstream.
 *
 * The container members are not `@Tunable`: they only record structure. The [curvedComposable] content
 * is, and it is stored until [CurvedLayoutContent] emits it.
 */
@LayoutScopeMarker
interface CurvedLayoutScope {
    /** Children side by side along the arc: thickness of the thickest, angle of the sum. */
    fun curvedRow(
        modifier: Modifier = Modifier,
        radialAlignment: CurvedAlignment.Radial? = null,
        angularDirection: CurvedDirection.Angular? = null,
        content: CurvedLayoutScope.() -> Unit,
    )

    /** Children on top of each other and on the arc: thickness and angle of the biggest. */
    fun curvedBox(
        modifier: Modifier = Modifier,
        radialAlignment: CurvedAlignment.Radial? = null,
        angularAlignment: CurvedAlignment.Angular? = null,
        content: CurvedLayoutScope.() -> Unit,
    )

    /** Children stacked radially, the first one outermost: thickness of the sum, angle of the biggest. */
    fun curvedColumn(
        modifier: Modifier = Modifier,
        radialDirection: CurvedDirection.Radial? = null,
        angularAlignment: CurvedAlignment.Angular? = null,
        content: CurvedLayoutScope.() -> Unit,
    )

    /**
     * A normal subtree that is rotated onto the arc.
     *
     * [rotationLocked] keeps the child upright wherever it sits on the circle, matching upstream: the
     * rotation is skipped but the *space* it occupies is still computed as if it were rotated, so it
     * suits square or round content and may need manual sizing otherwise.
     */
    fun curvedComposable(
        modifier: Modifier = Modifier,
        radialAlignment: CurvedAlignment.Radial = CurvedAlignment.Radial.Center,
        rotationLocked: Boolean = false,
        content: @Tunable BoxScope.() -> Unit,
    )

    /**
     * Text drawn along the arc, the container sibling of the standalone [CurvedText] component and
     * upstream's `basicCurvedText` (`foundation/BasicCurvedText.kt:83-98`).
     *
     * Unlike `basicCurvedText`, which paints glyphs from the container's draw scope
     * (`foundation/BasicCurvedText.kt:243-253`), this emits a
     * [com.huanli233.hibari.wear.view.WearCurvedTextView] over the container's full box and hands it
     * the sector the container measured. The visible difference: [maxSweepAngle] is enforced twice,
     * once when the container budgets the angle and once when the view clips the text, so a label
     * that is too long for [maxSweepAngle] truncates exactly as it would outside a container.
     *
     * @param angularDirection overrides the enclosing direction for this label only; 12-o'clock-up
     *   text at the bottom of the screen needs [CurvedDirection.Angular.CounterClockwise].
     * @param warpOffset upstream's `CurvedTextStyle.warpOffset`, which for its warped renderer picks
     *   the line that keeps its width while the glyph outlines bend
     *   (`foundation/WarpedCurvedTextRenderer.kt:117`). This renderer cannot bend outlines, so the
     *   value picks which line of the label rides the arc instead, and the whole label sits that far
     *   off the sector upstream would put it on: [WarpOffset.None] is the choice that reproduces
     *   upstream's radial placement, at upstream's own sweep for that radius.
     * @param letterSpacing an unspecified value falls back to [style]'s own tracking, as upstream's
     *   `CurvedTextStyle` does (`foundation/CurvedTextStyle.kt:243-255`); both the container's angle
     *   budget and the view's glyphs are measured with the resolved value.
     */
    fun curvedText(
        text: String,
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        background: Color = Color.Transparent,
        fontSize: TextUnit = TextUnit.Unspecified,
        letterSpacing: TextUnit = TextUnit.Unspecified,
        letterSpacingCounterClockwise: TextUnit = TextUnit.Unspecified,
        style: TextStyle = TextStyle(),
        angularDirection: CurvedDirection.Angular? = null,
        maxSweepAngle: Float = WearCurvedTextView.DefaultMaxSweepDegrees,
        overflow: CurvedTextOverflow = CurvedTextOverflow.Clip,
        warpOffset: WarpOffset = WarpOffset.HalfOpticalHeight,
    )
}

/**
 * Records the curved tree. [direction] is what a nested container inherits, and each sub-layout copies
 * it with its own angular / radial override, as upstream's `ContainerChild` does.
 */
internal class CurvedLayoutScopeImpl(
    private val direction: CurvedLayoutDirection,
) : CurvedLayoutScope {

    val children = mutableListOf<CurvedChild>()

    private fun add(child: CurvedChild, modifier: Modifier) {
        children += curvedWrappedWith(child, modifier, direction)
    }

    private fun subScope(
        angularDirection: CurvedDirection.Angular?,
        radialDirection: CurvedDirection.Radial? = null,
        content: CurvedLayoutScope.() -> Unit,
    ): Pair<CurvedLayoutDirection, List<CurvedChild>> {
        val childDirection = direction.copy(
            overrideRadial = radialDirection,
            overrideAngular = angularDirection,
        )
        return childDirection to CurvedLayoutScopeImpl(childDirection).apply(content).children
    }

    override fun curvedRow(
        modifier: Modifier,
        radialAlignment: CurvedAlignment.Radial?,
        angularDirection: CurvedDirection.Angular?,
        content: CurvedLayoutScope.() -> Unit,
    ) {
        val (childDirection, subChildren) = subScope(angularDirection, content = content)
        add(CurvedRowChild(childDirection, radialAlignment, subChildren), modifier)
    }

    override fun curvedBox(
        modifier: Modifier,
        radialAlignment: CurvedAlignment.Radial?,
        angularAlignment: CurvedAlignment.Angular?,
        content: CurvedLayoutScope.() -> Unit,
    ) {
        // Upstream's curvedBox takes no angular override: it inherits the enclosing direction
        // (`foundation/CurvedBox.kt:48-57`).
        val (childDirection, subChildren) =
            subScope(angularDirection = null, content = content)
        add(CurvedBoxChild(childDirection, radialAlignment, angularAlignment, subChildren), modifier)
    }

    override fun curvedColumn(
        modifier: Modifier,
        radialDirection: CurvedDirection.Radial?,
        angularAlignment: CurvedAlignment.Angular?,
        content: CurvedLayoutScope.() -> Unit,
    ) {
        val (childDirection, subChildren) =
            subScope(angularDirection = null, radialDirection = radialDirection, content = content)
        add(CurvedColumnChild(childDirection, angularAlignment, subChildren), modifier)
    }

    override fun curvedComposable(
        modifier: Modifier,
        radialAlignment: CurvedAlignment.Radial,
        rotationLocked: Boolean,
        content: @Tunable BoxScope.() -> Unit,
    ) {
        add(
            CurvedComposableChild(
                slot = CurvedLeafSlot(),
                direction = direction,
                radialAlignment = radialAlignment,
                rotationLocked = rotationLocked,
                // The chain is kept on the leaf node as well: the curved elements are pulled out of it
                // when the tree is built, the view attributes stay on it and reach the view.
                modifier = modifier,
                content = content,
            ),
            modifier,
        )
    }

    override fun curvedText(
        text: String,
        modifier: Modifier,
        color: Color,
        background: Color,
        fontSize: TextUnit,
        letterSpacing: TextUnit,
        letterSpacingCounterClockwise: TextUnit,
        style: TextStyle,
        angularDirection: CurvedDirection.Angular?,
        maxSweepAngle: Float,
        overflow: CurvedTextOverflow,
        warpOffset: WarpOffset,
    ) {
        val childDirection = direction.copy(overrideAngular = angularDirection)
        val resolvedSize = if (fontSize != TextUnit.Unspecified) fontSize else style.fontSize
        // Upstream merges the style's tracking into the text style it measures and draws with; the
        // spec carries tracking separately from the TextStyle, so the fall-through happens here.
        val resolvedTracking =
            if (letterSpacing != TextUnit.Unspecified) letterSpacing else style.letterSpacing
        add(
            CurvedTextChild(
                slot = CurvedLeafSlot(),
                spec = CurvedTextSpec(
                    text = text,
                    style = style.copy(fontSize = resolvedSize),
                    color = color,
                    background = background,
                    // The view needs a direction to pick its tracking variant and its warp side with;
                    // the container's resolved absolute direction is the same value the measure pass
                    // will push back, so setting it here keeps the first frame consistent.
                    clockwise = childDirection.absoluteClockwise(),
                    maxSweepDegrees = maxSweepAngle,
                    warpOffset = warpOffset,
                    letterSpacing = resolvedTracking,
                    letterSpacingCounterClockwise = letterSpacingCounterClockwise,
                    overflow = overflow,
                ),
                clockwise = childDirection.absoluteClockwise(),
                modifier = modifier,
            ),
            modifier,
        )
    }
}

/**
 * Upstream's `initialCurvedLayoutDirection` reads `LocalLayoutDirection.current`, and Hibari has the
 * same local: [LocalLayoutDirection] is a `staticTunationLocalOf<LayoutDirection>`, provided by
 * `HibariView.runTunable` from `ViewCompat.getLayoutDirection(hostView)` and overridable per subtree
 * with `TunationLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl)`. Reading it here —
 * rather than the screen's `Configuration`, which is app-wide and would silently mis-mirror a subtree
 * that overrides its own direction — keeps [CurvedDirection.Angular.Normal] and
 * [CurvedDirection.Angular.Reversed] resolving exactly as upstream does, including upstream's own
 * limitation that a *later* change of a static local does not recompose.
 */
@Tunable
private fun curvedIsLtr(): Boolean = LocalLayoutDirection.current == LayoutDirection.Ltr
