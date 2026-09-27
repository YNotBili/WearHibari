package com.huanli233.hibari.wear.view

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * The angular slot a [com.huanli233.hibari.wear.CurvedLayout] computed for one of its children,
 * pushed onto the child's own View.
 *
 * Hibari's layout engine only places rectangular Views at (x, y): [com.huanli233.hibari.ui.node.Placeable]
 * ends up in `View.layout(l, t, r, b)` and has no notion of rotation. Upstream gets its rotation from
 * `Placeable.placeWithLayer(layerBlock = { rotationZ = ... })`, whose Views equivalent is
 * [android.view.View.setRotation] — and only the child view can apply that to itself without the
 * container reaching into the runtime's measurable internals. So the container writes into this object
 * during its placement pass and the value is forwarded straight to the owning view.
 *
 * Pushing rather than letting the view read the slot in `onLayout` is deliberate: `View.layout()` skips
 * `onLayout` when the frame is unchanged, so a child that keeps its size but changes angle would keep
 * drawing at the old one. `setRotation` also invalidates the parent with the transformed bounds, which a
 * bare `invalidate()` on the child would not.
 */
internal class CurvedLeafSlot {

    /** Bound by the rect leaf view; null for an arc-text leaf. */
    var rectOwner: WearCurvedLeafView? = null
        private set

    /** Bound by the arc-text leaf view; null for a rect leaf. */
    var arcOwner: WearCurvedLayoutTextView? = null
        private set

    /** `rotationZ` in degrees, upstream's `place()` result. 0f for self-curving children. */
    var rotationDegrees: Float = 0f
        set(value) {
            field = value
            rectOwner?.rotation = value
        }

    /** False until the container has assigned an arc; the text view then keeps its own anchor. */
    var arcApplied: Boolean = false

    var arcClockwise: Boolean = true
        set(value) {
            field = value
            arcOwner?.clockwise = value
        }

    /** Where the middle of this child's text sits, in canvas degrees (270 = 12 o'clock). */
    var arcAnchorDegrees: Float = WearCurvedTextView.TopAnchor
        set(value) {
            field = value
            arcOwner?.anchorDegrees = value
        }

    /**
     * The child's angular budget. Equal to the sweep the container measured for it, so the view's own
     * ellipsize/clip only kicks in when its `CurvedTextSpec.maxSweepDegrees` is the tighter of the two.
     */
    var arcMaxSweepDegrees: Float = 0f
        set(value) {
            field = value
            arcOwner?.maxSweepDegrees = value
        }

    /** Distance from the container's circle edge inward to this child's outer edge, in px. */
    var arcOuterPaddingPx: Float = 0f
        set(value) {
            field = value
            arcOwner?.outerPaddingPx = value
        }

    fun bind(view: WearCurvedLeafView) {
        rectOwner = view
        view.rotation = rotationDegrees
    }

    fun bind(view: WearCurvedLayoutTextView) {
        arcOwner = view
        view.applyCurvedSlot(this)
    }
}

/**
 * The wrapper a [com.huanli233.hibari.wear.CurvedLayout] puts around each `curvedComposable` child.
 *
 * Its only job is to own the rotation the container assigns: upstream wraps the same content in a
 * `Box` and applies `rotationZ` with `TransformOrigin.Center`
 * (`foundation/CurvedComposable.kt:220-231`), which is what `View.rotation` does with the default
 * pivot (`width / 2`, `height / 2`).
 */
internal class WearCurvedLeafView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var slot: CurvedLeafSlot? = null
        set(value) {
            field = value
            value?.bind(this)
        }
}

/**
 * A `curvedText` child: [WearCurvedTextView] plus the angular slot the enclosing
 * [com.huanli233.hibari.wear.CurvedLayout] measured for it.
 *
 * The view is laid out as the container's full diameter square, so its own
 * `min(width, height) / 2 - outerPaddingPx` lands exactly on the annulus sector the container picked,
 * and `anchorDegrees` points at the middle of the child's sweep. That is how the existing single-label
 * renderer is reused rather than duplicated: the container decides *where* the arc is, the view still
 * decides how glyphs are warped onto it.
 */
internal class WearCurvedLayoutTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WearCurvedTextView(context, attrs, defStyleAttr) {

    var slot: CurvedLeafSlot? = null
        set(value) {
            field = value
            value?.bind(this)
        }

    /**
     * Re-applies the slot after binding, which also covers a view created after the first placement
     * pass. Writes go through the inherited properties only when they differ, since each of them
     * invalidates the view.
     */
    fun applyCurvedSlot(slot: CurvedLeafSlot) {
        if (!slot.arcApplied) return
        if (clockwise != slot.arcClockwise) clockwise = slot.arcClockwise
        if (anchorDegrees != slot.arcAnchorDegrees) anchorDegrees = slot.arcAnchorDegrees
        if (maxSweepDegrees != slot.arcMaxSweepDegrees) maxSweepDegrees = slot.arcMaxSweepDegrees
        if (outerPaddingPx != slot.arcOuterPaddingPx) outerPaddingPx = slot.arcOuterPaddingPx
        // The container centres the child inside its own slot, which is CurvedAnchor.Center's ratio.
        if (anchorType != CurvedAnchor.Center) anchorType = CurvedAnchor.Center
    }
}
