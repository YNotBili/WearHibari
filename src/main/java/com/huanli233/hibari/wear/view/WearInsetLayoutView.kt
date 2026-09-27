package com.huanli233.hibari.wear.view

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.huanli233.hibari.wear.WearBoxedEdges
import com.huanli233.hibari.wear.WearEdgeInsets
import com.huanli233.hibari.wear.WearInsets
import kotlin.math.min

/**
 * The container that replaces `androidx.wear.widget.BoxInsetLayout` in `AppScaffold`, so the
 * safe-area behaviour of this module is computed by this module.
 *
 * Neither it nor `BoxInsetLayout` clips to the screen's circle: a boxed child is *positioned*
 * inside the box, and the round clip upstream is applied elsewhere entirely
 * (`navigation3/PredictiveBackScene.kt:110` `Modifier.clip(if (isRoundDevice) CircleShape else
 * RectangleShape)`). That stays true here.
 *
 * Why a `ViewGroup` rather than a `MeasurePolicy`: the framework's own containers do the same —
 * `Box` is `Modifier.viewClass(FrameLayout::class.java)` (`hibari-foundation/Box.kt:19-26`) and
 * `Row`/`Column` are `LinearLayout`, with per-child layout state living in the parent's
 * `LayoutParams` (`thenLayoutAttribute` mutates the child's real `layoutParams` in place,
 * `hibari-ui/Attribute.kt:84-96`). A `MeasurePolicy` would replace the view entirely
 * (`Renderer.kt:69-74` swaps in a `LayoutNodeHost`) and `BoxScope.gravity`, which writes
 * `FrameLayout.LayoutParams.gravity`, would stop reaching any layout. So the *View* is the ported
 * unit here, and `Insets.kt` holds the maths.
 *
 * ## The inset it applies, and where it comes from
 *
 * [WearInsets.contentInsetsPx] — Wear Compose's `PaddingDefaults`: 10% of the screen's height dp
 * on top and bottom, 5.2% of the screen's width dp on left and right, each `ceil`ed in dp first
 * (`material3/Padding.kt:34,41-44,50,57-60`, assembled as `PaddingValues(horizontal, vertical)` at
 * `material3/ScreenScaffold.kt:786-792`). A child pulls itself into that box per edge with
 * [WearInsetLayoutView.LayoutParams.boxedEdges], the flag values `BoxInsetLayout` used
 * ([WearBoxedEdges]).
 *
 * ## Differences from `BoxInsetLayout`, and which side is upstream-correct
 *
 * `BoxInsetLayout`'s algorithm below is read from `androidx.wear:wear:1.4.0` on this machine
 * (`androidx/wear/widget/BoxInsetLayout.class`, disassembled with `javap`/`jadx`; line numbers are
 * from that decompilation, so they are approximate, the code is not).
 *
 *  1. **Amount.** `BoxInsetLayout.calculateInset` = `(int)(0.146447f * max(min(measuredWidth,
 *     screenWidth), min(measuredHeight, screenHeight)))` — one number for all four edges. Compose's
 *     is `ceil(screenHeightDp * 0.10)` vertically and `ceil(screenWidthDp * 0.052)` horizontally.
 *     On a 384 px square screen at density 2 (192 x 192 dp): `BoxInsetLayout` insets 56 px on every
 *     edge; Compose insets 40 px top and bottom (`ceil(19.2) = 20 dp`) and 20 px left and right
 *     (`ceil(9.984) = 10 dp`). **Compose is upstream-correct** for this port; the `BoxInsetLayout`
 *     figure is the Views lineage's inscribed-square factor (`(1 - 1/sqrt(2)) / 2`), which is not
 *     what Wear Compose computes.
 *  2. **Shape dependence.** `BoxInsetLayout` applies its inset only when
 *     `Configuration.isScreenRound` (`BoxInsetLayout.java:55,74,217-242`); on a square watch every
 *     child keeps the full screen and `boxedEdges` is a no-op. Compose's padding has no round
 *     branch at all, so **this view insets square screens too**, and deliberately never reads
 *     `isScreenRound` — there is nothing here for `WearScreen.isRound` to gate.
 *  3. **Size source.** `BoxInsetLayout` measures its inset against `Resources.getSystem()`
 *     display metrics captured in its constructor, so the inset is the *physical screen's* and is
 *     wrong in split-screen or when embedded. Compose reads `LocalConfiguration.screenWidthDp /
 *     screenHeightDp`, which are window-relative and exclude system bars. This view follows
 *     Compose, reading `resources.configuration` at every measure.
 *  4. **How the child is constrained.** `BoxInsetLayout` measures children with the *unboxed*
 *     constraints first and only re-measures one that overflows (`BoxInsetLayout.java:192-214`),
 *     so a `match_parent` child can be measured twice at two different sizes. Here the inset is
 *     folded into `getChildMeasureSpec` up front — one pass, and the boxed child never sees more
 *     space than the box, which is what `Modifier.padding` does upstream.
 *  5. **Conditionality of the opt-in.** `BoxInsetLayout` adds the inset to a left edge only when
 *     the child's `width == MATCH_PARENT` or its gravity carries `LEFT` (`BoxInsetLayout.java:217`),
 *     i.e. the flag silently stops working for, say, a centred `wrap_content` child boxed on
 *     LEFT+RIGHT. Here the flag means the flag: an opted-in edge is inset whatever the child's size
 *     or gravity, which is also what a `PaddingValues` edge does upstream.
 *  6. **Rounding.** `BoxInsetLayout` truncates with a cast; `Dp.toPx` rounds, so this view rounds
 *     (`WearInsets.insetsPx`).
 *
 * ## What is *not* a port
 *
 * The per-edge opt-in itself. Upstream `AppScaffold` is a bare
 * `Box(Modifier.fillMaxSize().background(...))` (`material3/AppScaffold.kt:71-82`): no insets, no
 * child-level flags, and the padding instead arrives as `ScreenScaffold`'s `PaddingValues` handed to
 * the content lambda. [WearBoxedEdges] exists because this module already published `BoxedEdges`
 * and `Modifier.boxedEdges(Int)`, not because Wear Compose has the feature — so on the question of
 * "which of the two is upstream-correct", the honest answer for this whole container is *neither*:
 * the amounts are Compose's, the opt-in mechanic is the Views widget's, and a fully faithful
 * `AppScaffold` would apply no padding here at all.
 */
class WearInsetLayoutView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    /**
     * The screen's inset box for the current measure, resolved from the view's own configuration.
     * Recomputed in [onMeasure] so a rotation, a density change or a size-class change lands on the
     * next layout pass instead of on a stale cache.
     */
    private var screenInsets: WearEdgeInsets = WearEdgeInsets.NONE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        screenInsets = WearInsets.contentInsetsPx(context)

        var maxWidth = paddingLeft + paddingRight
        var maxHeight = paddingTop + paddingBottom
        var childState = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val params = layoutParamsFor(child)
            val inset = WearInsets.boxedInsetsPx(screenInsets, params.boxedEdges)

            val horizontalUsed =
                paddingLeft + paddingRight + params.leftMargin + params.rightMargin +
                    inset.left + inset.right
            val verticalUsed =
                paddingTop + paddingBottom + params.topMargin + params.bottomMargin +
                    inset.top + inset.bottom

            child.measure(
                getChildMeasureSpec(widthMeasureSpec, horizontalUsed, params.width),
                getChildMeasureSpec(heightMeasureSpec, verticalUsed, params.height),
            )

            maxWidth = maxOf(maxWidth, child.measuredWidth + horizontalUsed)
            maxHeight = maxOf(maxHeight, child.measuredHeight + verticalUsed)
            childState = combineMeasuredStates(childState, child.measuredState)
        }

        maxWidth = maxOf(maxWidth, suggestedMinimumWidth)
        maxHeight = maxOf(maxHeight, suggestedMinimumHeight)

        setMeasuredDimension(
            resolveSizeAndState(maxWidth, widthMeasureSpec, childState),
            resolveSizeAndState(
                maxHeight,
                heightMeasureSpec,
                childState shl MEASURED_HEIGHT_STATE_SHIFT,
            ),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t
        val viewDirection = layoutDirection

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val params = layoutParamsFor(child)
            val inset = WearInsets.boxedInsetsPx(screenInsets, params.boxedEdges)

            val frameLeft = paddingLeft + params.leftMargin + inset.left
            val frameTop = paddingTop + params.topMargin + inset.top
            val frameRight = width - paddingRight - params.rightMargin - inset.right
            val frameBottom = height - paddingBottom - params.bottomMargin - inset.bottom

            val gravity = if (params.gravity == FrameLayout.LayoutParams.UNSPECIFIED_GRAVITY) {
                DEFAULT_CHILD_GRAVITY
            } else {
                params.gravity
            }
            val absoluteGravity = Gravity.getAbsoluteGravity(gravity, viewDirection)

            // Never wider than its box, the way FrameLayout clamps before positioning.
            val childWidth = min(child.measuredWidth, (frameRight - frameLeft).coerceAtLeast(0))
            val childHeight = min(child.measuredHeight, (frameBottom - frameTop).coerceAtLeast(0))

            val childLeft = when (absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
                Gravity.CENTER_HORIZONTAL ->
                    frameLeft + (frameRight - frameLeft - childWidth) / 2

                Gravity.RIGHT -> frameRight - childWidth
                else -> frameLeft
            }
            val childTop = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
                Gravity.CENTER_VERTICAL -> frameTop + (frameBottom - frameTop - childHeight) / 2
                Gravity.BOTTOM -> frameBottom - childHeight
                else -> frameTop
            }

            child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams): LayoutParams =
        if (p is LayoutParams) LayoutParams(p) else LayoutParams(p.width, p.height)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams): Boolean = p is LayoutParams

    /**
     * `Renderer` hands every child a `WRAP_CONTENT` instance of the parent's own `$LayoutParams`
     * (`Renderer.kt:97-115`) and `ViewGroup.addViewInner` regenerates anything that fails
     * [checkLayoutParams], so the foreign branch below is a belt-and-braces path, not the authored
     * one; treating a stranger as unboxed keeps it laid out anyway.
     */
    private fun layoutParamsFor(child: View): LayoutParams {
        val source = child.layoutParams
        return if (source is LayoutParams) {
            source
        } else {
            when (source) {
                is MarginLayoutParams -> LayoutParams(source)
                else -> LayoutParams(source.width, source.height)
            }
        }
    }

    /**
     * Child layout parameters: `FrameLayout`'s gravity and margins — so `BoxScope.gravity` keeps
     * working — plus the edges this child boxes itself into.
     *
     * Named and shaped like `BoxInsetLayout.LayoutParams` (`BOX_*` values and the `boxedEdges`
     * field included) on purpose: `Modifier.boxedEdges` then only needs its type argument swapped,
     * `thenLayoutAttribute<WearInsetLayoutView.LayoutParams, Int>`, and the lambda body
     * (`boxedEdges = value`) is unchanged.
     */
    class LayoutParams : FrameLayout.LayoutParams {

        /** Bit mask of [WearBoxedEdges]; [WearBoxedEdges.NONE] leaves the child at the screen edge. */
        var boxedEdges: Int = WearBoxedEdges.NONE

        constructor(width: Int, height: Int) : super(width, height)

        constructor(width: Int, height: Int, gravity: Int) : super(width, height, gravity)

        constructor(source: LayoutParams) : super(source) {
            boxedEdges = source.boxedEdges
        }

        constructor(source: ViewGroup.LayoutParams) : super(source)

        constructor(source: MarginLayoutParams) : super(source)

        constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    }

    private companion object {
        /** Same default as `BoxInsetLayout.DEFAULT_CHILD_GRAVITY` (8388659) and `FrameLayout`: top-start. */
        val DEFAULT_CHILD_GRAVITY = Gravity.TOP or Gravity.START
    }
}
